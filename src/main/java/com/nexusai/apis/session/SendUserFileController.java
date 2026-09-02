package com.nexusai.apis.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * SendUserFile 文件下载端点 · {@code GET /api/v1/sessions/{sessionId}/send-user-file/download?path=...}。
 *
 * <p><b>WHY（Q3 前端交付返工 2026-08-23）</b>: {@code SendUserFileTool} 经 STOMP 推送
 * {@code SendUserFileEvent}（type=send_user_file）到 {@code /topic/sessions/{sess-xxx}}，
 * 事件只携带文件<b>元数据</b>（{@code file_path + size + description}）；前端要拿到实际字节必须
 * 经本端点读取服务器文件。此前（commit 31b80e58）交付停留在"传输层"——事件能推但前端无下载端点
 * 可拉字节，端到端"文件发给用户"不通。本端点补全该缺口：CC 的 web viewer 经 bridge +
 * {@code file_uuid} 下载（SendUserFileTool.ts:101-116），Java web 端等价物 = 本 REST 端点
 * （后端即宿主服务器，文件已在其文件系统）。
 *
 * <p><b>契约</b>:
 * <ul>
 *   <li>{@code path} 为 {@code SendUserFileEvent.filePath}（服务器<b>绝对路径</b>，由工具调用方
 *       生成，前端原样回传）。</li>
 *   <li>成功 → {@code 200 application/octet-stream} + {@code Content-Disposition: attachment} +
 *       {@code Content-Length}，流式输出（不整读进内存）。</li>
 *   <li>失败 → {@code 400}（缺 path / 非法路径 / 非文件）｜ {@code 404}（不存在）｜
 *       {@code 403}（不可读）｜ {@code 500}（IO 异常），body 为 {@code {ok:false, message}}。</li>
 * </ul>
 *
 * <p><b>安全（登记受控残留）</b>: 当前 {@code nexusai.security.require-oauth-auth=false}（fail-open
 * 过渡态），本端点<b>未加独立鉴权</b>——任何能触达后端的人可经任意绝对路径读取服务器上当前 OS 用户
 * 可读的文件（SendUserFile 特性本质即"agent 把任意文件发给用户"，文件系统访问面与
 * {@code Read/Bash/SendUserFileTool} 工具同界）。生产 OAuth 网关兜底后随 /api/v1 统一收口（同
 * AttachmentController 鉴权待办口径）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/send-user-file")
public class SendUserFileController {

    private static final Logger log = LoggerFactory.getLogger(SendUserFileController.class);

    /**
     * 下载 SendUserFile 事件指向的文件实际字节 · 前端收到 {@code send_user_file} 事件后调用。
     *
     * @param sessionId 会话 UUID/键（路径参数 · 事件 {@code sessionId} 原样回传，fail-open 阶段未做
     *                  会话存在性校验，同 SessionFileController.list 口径）
     * @param filePath  {@code SendUserFileEvent.filePath}（服务器绝对路径，query 参数）
     * @return 文件流（octet-stream + attachment）或 {@code {ok:false, message}} 错误体
     */
    @GetMapping("/download")
    public ResponseEntity<?> download(@PathVariable String sessionId,
                                      @RequestParam(value = "path", required = false) String filePath) {
        if (log.isDebugEnabled()) {
            log.debug("[SendUserFile] 下载请求: sessionId={} path={}", sessionId, filePath);
        }
        if (filePath == null || filePath.isBlank()) {
            log.warn("[SendUserFile] 下载拒绝: 缺少 path 参数 sessionId={}", sessionId);
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "缺少下载路径参数 path"));
        }
        Path p;
        try {
            p = Path.of(filePath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("[SendUserFile] 下载拒绝: 非法文件路径 path={} sessionId={} err={}",
                    filePath, sessionId, e.toString());
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "非法文件路径"));
        }
        // stat 语义对齐 SendUserFileTool.execute（Files.readAttributes 复刻 CC fs.stat：
        // 不存在抛 NoSuchFileException → 404；存在但非文件 → 400 "路径不是文件"）
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(p, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            log.warn("[SendUserFile] 下载 404: 文件不存在 path={} sessionId={}", p, sessionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "文件不存在"));
        } catch (IOException e) {
            log.error("[SendUserFile] 下载失败: 读取文件属性异常 path={} sessionId={} err={}",
                    p, sessionId, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", "读取文件信息失败"));
        }
        if (!attrs.isRegularFile()) {
            log.warn("[SendUserFile] 下载拒绝: 路径非文件 path={} sessionId={}", p, sessionId);
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "路径不是文件"));
        }
        if (!Files.isReadable(p)) {
            log.warn("[SendUserFile] 下载拒绝: 文件不可读 path={} sessionId={}", p, sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "message", "文件不可读"));
        }
        try {
            InputStream in = Files.newInputStream(p);
            InputStreamResource resource = new InputStreamResource(in);
            String filename = sanitizeFilename(p.getFileName().toString());
            if (log.isInfoEnabled()) {
                log.info("[SendUserFile] 下载命中: sessionId={} path={} size={}B filename={}",
                        sessionId, p, attrs.size(), filename);
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentLength(attrs.size())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (IOException e) {
            log.error("[SendUserFile] 下载失败: 打开文件流异常 path={} sessionId={} err={}",
                    p, sessionId, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", "读取文件流失败"));
        }
    }

    /**
     * 文件名净化 · 仅保留安全展示字符，剥离引号/换行等可注入 Content-Disposition 头的控制字符。
     */
    private static String sanitizeFilename(String name) {
        return name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }
}

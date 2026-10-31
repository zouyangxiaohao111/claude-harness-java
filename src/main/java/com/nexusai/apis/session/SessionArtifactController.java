package com.nexusai.apis.session;

import com.nexusai.application.session.SessionArtifactService;
import com.nexusai.model.session.dto.ArtifactContentUpdateRequest;
import com.nexusai.model.session.dto.ArtifactContentUpdateResponse;
import com.nexusai.model.session.dto.SessionArtifactDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 会话「产物 / 来源」只读 REST · GET /api/v1/sessions/{sessionId}/artifacts。
 *
 * <p><b>语义</b>：读 {@code session_artifact} 表（V205，由 {@link SessionArtifactService}
 * 轮末异步抽取写入），按 kind 分两桶返回。前端定时轮询本端点即可。
 *
 * <p><b>⛔ 本端点不触发抽取</b>：抽取只由 ChatService 轮末触发 —— 否则前端每次轮询都会打一次
 * 模型（轮询频率 × 会话数 = 费用放大器）。
 *
 * <p><b>空态</b>：无产物/来源 → 200 + 两个空数组（空列表是合法状态，不用 404，
 * 与 TodoStatusController 同款约定）。会话 id 不存在同样返回空数组（只读投影，不暴露存在性）。
 *
 * <p>路由与既有 /api/v1/sessions/** 无冲突（ChatController /{sessionId}、TodoStatusController /todos、
 * SessionFilesController /files）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/artifacts")
public class SessionArtifactController {

    @Autowired private SessionArtifactService sessionArtifactService;

    /**
     * 会话产物/来源列表 · GET /api/v1/sessions/{sessionId}/artifacts。
     *
     * @param sessionId 会话 id（sess-xxx）
     * @return 200 {deliverables: SessionArtifactDto[], sources: SessionArtifactDto[]}
     */
    @GetMapping
    public ArtifactsResponse list(@PathVariable String sessionId) {
        SessionArtifactService.ArtifactBuckets buckets = sessionArtifactService.list(sessionId);
        return new ArtifactsResponse(buckets.deliverables(), buckets.sources());
    }

    /**
     * 产物内容预览 · GET /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content。
     *
     * <p><b>WHY</b>：产物 {@code local_path} 是<b>归档副本</b>的绝对路径
     * （{@code {configHome}/artifacts/{sessionId}/...}），不在任何项目根内 ⇒ 浏览器端
     * 走项目文件通道必然报「文件路径非法」。产物只能走本端点。
     *
     * <p><b>错误语义</b>：不属于该会话 / 产物行不存在 / 文件已删除 → 404；
     * 路径越出本会话归档目录 → 400（GlobalExceptionHandler 统一出 RFC 7807 体）。
     *
     * @param sessionId  会话 id（sess-xxx）
     * @param artifactId 产物行 id（art-xxx）
     * @return 200 {content, mime, size, truncated, binary}
     */
    @GetMapping("/{artifactId}/content")
    public SessionArtifactService.ArtifactContent content(@PathVariable String sessionId,
                                                          @PathVariable String artifactId) {
        return sessionArtifactService.readContent(sessionId, artifactId);
    }

    /** 出站形状（两桶分列，前端按桶渲染）。 */
    public record ArtifactsResponse(List<SessionArtifactDto> deliverables, List<SessionArtifactDto> sources) {}

    // ───────────────────────── 原始字节 / 文本写回 ─────────────────────────

    /**
     * 产物原始字节 · GET /api/v1/sessions/{sessionId}/artifacts/{artifactId}/raw。
     *
     * <p><b>WHY 需要它</b>：{@code /content} 只下发文本（二进制只回 {@code binary=true}、内容为
     * null）⇒ docx / pdf / xlsx / 图片在浏览器里根本打不开（用户报「产物点不开」）。本端点下发
     * <b>原始字节</b>，浏览器按 {@code Content-Type} 直接渲染 / 下载。
     *
     * <p><b>Content-Type 判定口径</b>：按<b>文件真实扩展名</b>（不是 DB 行 title —— title 是展示名，
     * 可能压根没有扩展名）经 {@code ArtifactFileKind.contentTypeOf} 得出 —— 与 {@code /content}、
     * {@code PUT /content} 共用同一张表（单一真源），不会出现「同一文件不同端点不同类型」。
     *
     * <p><b>Content-Disposition</b>：{@code inline; filename*=UTF-8''<percent 编码>}（RFC 5987）——
     * 用 {@code inline} 而非 {@code attachment}，浏览器才能<b>直接渲染</b>（pdf / 图片 / html）；
     * 文件名做 percent 编码 ⇒ 控制字符 / 换行被编码掉，⛔ 不会有头注入。
     *
     * <p><b>错误语义</b>（与 {@code /content} 完全同款）：不属于该会话 / 产物行不存在 / 文件已删除
     * → 404；路径越出本会话归档目录 / 符号链接越界 → 400。
     *
     * @param sessionId  会话 id（sess-xxx）
     * @param artifactId 产物行 id（art-xxx）
     * @return 200 原始字节 + Content-Type + Content-Disposition
     */
    @GetMapping("/{artifactId}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable String sessionId,
                                       @PathVariable String artifactId) {
        SessionArtifactService.ArtifactRaw raw = sessionArtifactService.readRaw(sessionId, artifactId);
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(raw.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(raw.fileName()))
                // nosniff：禁止浏览器猜 MIME（否则一个 .txt 里的 HTML 片段会被当 HTML 执行）
                .header("X-Content-Type-Options", "nosniff")
                // [2026-11-01 安全] CSP sandbox allow-scripts：
                //   /raw 以 text/html 内联下发**模型生成/用户提供的 HTML**，而本仓后端无鉴权且开着
                //   local-read ⇒ 产物里的脚本若在应用 origin 执行，就能借用户的浏览器调本地 API
                //   读宿主机文件。sandbox allow-scripts 让 HTML/JS 照常跑，但落在**不透明源**里，
                //   拿不到同源权限 ⇒ 断掉这条链，且不改变「HTML 产物在浏览器直接渲染」的用户体验。
                //   ⚠️ 完整 `sandbox`（无 allow-scripts）会让产物里的图表/动画脚本失效 ⇒ 不用。
                .header("Content-Security-Policy", "sandbox allow-scripts")
                .body(raw.bytes());
    }

    /**
     * 产物文本内容写回 · PUT /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content。
     *
     * <p><b>WHY 只能改文本类</b>：docx / xlsx / pdf / 图片是二进制，把字符串写进去会<b>当场写坏
     * 交付物</b>（用户以为保存成功，其实文件已损坏）⇒ 二进制一律 400（服务层按
     * {@code ArtifactFileKind.isText} 判定，与 {@code /content}、{@code /raw} 同源）。
     *
     * <p><b>写哪里</b>：写回<b>归档副本</b>（{@code local_path} 指向的文件），⛔ 不动用户项目里的
     * 原文件 —— 产物通道的语义就是「编辑已归档的交付物」，动原文件会绕过用户的版本管理。
     *
     * <p><b>原子写</b>：服务层先写同目录临时文件再原子改名覆盖 ⇒ 写一半失败不会留半截文件。
     *
     * @param sessionId  会话 id（sess-xxx）
     * @param artifactId 产物行 id（art-xxx）
     * @param request    请求体 {@code {content}}；content 为 null ⇒ 400
     * @return 200 {id, sizeBytes, updatedAt}
     */
    @PutMapping("/{artifactId}/content")
    public ArtifactContentUpdateResponse updateContent(
            @PathVariable String sessionId,
            @PathVariable String artifactId,
            @RequestBody(required = false) ArtifactContentUpdateRequest request) {
        String content = request == null ? null : request.content();
        return sessionArtifactService.writeTextContent(sessionId, artifactId, content);
    }

    /**
     * Content-Type 解析（防御：MIME 串来自本仓固定表，但解析失败不该把整个请求打成 500）。
     *
     * @param contentType MIME 串（可带 {@code ; charset=utf-8}）
     * @return 解析出的 MediaType；解析失败 → {@code application/octet-stream}
     */
    private static MediaType mediaTypeOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** percent 编码用的十六进制表（大写，RFC 5987 允许）。 */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * {@code Content-Disposition} 头值：{@code inline; filename*=UTF-8''<percent 编码>}。
     *
     * <p>⛔ 只保留「未保留字符」（字母数字 + {@code - . _ ~}），其余（含空格、中文、{@code ;}、
     * 换行）全部 percent 编码 ⇒ 头值恒为 ASCII，⛔ 不会有头注入（换行被编码成 {@code %0D%0A}）。
     *
     * @param fileName 展示用文件名（DB 行 title；空则用副本 basename）
     */
    private static String inlineDisposition(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "artifact" : fileName.trim();
        return "inline; filename*=UTF-8''" + percentEncode(name);
    }

    /** UTF-8 字节 → percent 编码（未保留字符原样保留）。 */
    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }
}

package com.nexusai.kb.api;

import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.kb.app.image.KbImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 知识库图片读取 · 只读端点。
 *
 * <h2>⭐ 为什么必须有它</h2>
 * 图片落库后 {@code kb_chunk_image.url} 里存的是
 * {@code /api/v1/kb/images/<knowledgeId>/<file>} —— 如果没有这个端点，该 URL 是死的：
 * 前端 {@code <img src>} 会 404，用户看到「解析完成、有图片记录、但一张图都显示不出来」。
 * 图库面板（{@code KbGalleryPanel}）直接把这个 url 塞进 {@code <img src>} 即可。
 *
 * <h2>⭐ 只做「读」，不做「列 / 改 / 删」</h2>
 * 列表走 {@code GET /api/v1/knowledge-bases/{id}/images}（{@code KbImageService}），
 * 属性编辑走 {@code PUT /api/v1/knowledge/image/{id}/{chunkId}}（{@code KbKnowledgeExtService}）。
 * 本端点只有一条路径，避免与既有入口重复。
 *
 * <h2>⚠️ 路径逃逸</h2>
 * 两段路径变量都经 {@link KbImageStorage#keyOf} 校验（只允许 {@code [A-Za-z0-9._-]}，
 * 且拒绝 {@code .}/{@code ..}）⇒ {@code ../../etc/passwd} 无法拼进存储键。
 */
@RestController
public class KbImageController {

    private static final Logger log = LoggerFactory.getLogger(KbImageController.class);

    private final KbImageStorage imageStorage;

    public KbImageController(KbImageStorage imageStorage) {
        this.imageStorage = imageStorage;
    }

    /**
     * 读取一张知识库图片。
     *
     * @param knowledgeId 知识条目 ID（路径段，仅用于拼键与日志）
     * @param fileName    已生成的文件名（{@code img-0001.png}）
     * @return 图片字节流
     */
    @GetMapping(KbImageStorage.URL_PREFIX + "/{knowledgeId}/{fileName}")
    public ResponseEntity<byte[]> read(@PathVariable String knowledgeId,
                                       @PathVariable String fileName) {
        String key = KbImageStorage.keyOf(knowledgeId, fileName);
        if (key == null) {
            // ⛔ 不回显路径：那是信息泄露面
            log.warn("[图片API] 拒绝非法路径 knowledge={} file={}", knowledgeId, fileName);
            throw new NotFoundException("图片不存在");
        }
        byte[] data;
        try (InputStream in = imageStorage.open(key)) {
            data = in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            log.info("[图片API] 读取失败 knowledge={} file={} 原因={}",
                knowledgeId, fileName, e.toString());
            throw new NotFoundException("图片不存在或已删除");
        }
        if (data.length == 0) {
            throw new NotFoundException("图片内容为空");
        }
        if (log.isDebugEnabled()) {
            log.debug("[图片API] 读取命中 knowledge={} file={} 字节={}", knowledgeId, fileName, data.length);
        }
        return ResponseEntity.ok()
            .contentType(mediaTypeOf(fileName))
            // 图片不可变（文件名含解析轮次序号、重解析会换新文件）⇒ 长缓存省带宽
            .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(data);
    }

    /** 扩展名 → Content-Type（白名单；未知按 {@code image/png}，与存储层默认扩展名一致）。 */
    private static MediaType mediaTypeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (name.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        }
        return MediaType.IMAGE_PNG;
    }
}

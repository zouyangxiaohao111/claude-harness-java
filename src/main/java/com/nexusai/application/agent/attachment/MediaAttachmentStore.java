package com.nexusai.application.agent.attachment;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 媒体附件路径存储 · [attachments-v2 Step2] image/video/audio/file 多类型上传落盘（对齐
 * {@link PdfAttachmentStore} 路径通道语义）。
 *
 * <p><b>定位</b>：{@code POST /api/v1/attachments/upload} 放宽为多类型后，非 PDF 的
 * image/video/audio/file 落盘本存储 {@code {configHome}/media-cache/{sessionId}/{id}.{ext}}，
 * 返回 {@code contentId}。后端消费时按 <b>磁盘路径</b>解析（对齐 CC 路径通道语义：
 * 文件在盘，路径可重建，避免 &gt;5MB base64 放大）。
 *
 * <p><b>与 {@link PdfAttachmentStore} 同构</b>（继承 {@link AttachmentStoreBase}）：
 * <ul>
 *   <li>目录 {@code {configHome}/media-cache/{sessionId}/{id}.{ext}}，ext =
 *       {@code mediaType.split('/')[1] || 'bin'}</li>
 *   <li>内存索引会话分桶，200 上限按会话 FIFO 逐出（仅删内存索引，磁盘文件保留）</li>
 *   <li>id 每会话独立自增从 1（对齐 CC PastedContent.id 顺序数字 id）</li>
 *   <li>写盘 FileChannel.force(true) 刷盘（data-sync 语义）</li>
 *   <li>失败返回 null 不抛异常</li>
 * </ul>
 *
 * <p><b>路径确定性</b>：{@link #getPath(String, long, String)} 恒返回确定性路径，不依赖内存索引
 * （即使被逐出 / 服务重启仍可按 contentId + mediaType 还原磁盘路径读文件）。
 */
@Component
public final class MediaAttachmentStore extends AttachmentStoreBase<MediaAttachmentStore.StoredMedia> {

    /** 媒体缓存根目录名 · 对齐 ImageAttachmentStore/PdfAttachmentStore 对等名。 */
    public static final String MEDIA_CACHE_DIR = "media-cache";

    /** 每会话内存索引上限 · 对齐 MAX_STORED_PATHS=200（imageStore.ts:10）。 */
    public static final int MAX_STORED_MEDIA_PATHS = 200;

    /**
     * 单条媒体存储记录。
     *
     * @param id        媒体 id（路径通道 contentId）
     * @param path      磁盘绝对路径 {@code {configHome}/media-cache/{sessionId}/{id}.{ext}}
     * @param filename  客户端原始文件名（显示名）
     * @param size      原始字节数
     * @param mediaType MIME 类型（如 'video/mp4'）
     */
    public record StoredMedia(long id, String path, String filename, long size, String mediaType) {}

    // ════════════════════════════════════════════════════════════════════
    // 模板方法钩子（子类差异点）
    // ════════════════════════════════════════════════════════════════════

    /** 媒体缓存目录名。 */
    @Override
    protected String cacheDirName() {
        return MEDIA_CACHE_DIR;
    }

    /** 由 mediaType 推扩展名 · {@code mediaType.split('/')[1] || 'bin'}（对齐 imageStore.ts:34 语义）。 */
    @Override
    protected String extensionOf(String mediaType) {
        if (mediaType == null) {
            return "bin";
        }
        int slash = mediaType.indexOf('/');
        if (slash < 0 || slash == mediaType.length() - 1) {
            return "bin";
        }
        return mediaType.substring(slash + 1);
    }

    /** 新建会话桶 · FIFO 插入序（同 Image/Pdf store）。 */
    @Override
    protected Map<Long, StoredMedia> newSessionMap() {
        return new LinkedHashMap<>(256, 0.75f, false);
    }

    // ════════════════════════════════════════════════════════════════════
    // 目录与路径（对齐 PdfAttachmentStore 模式）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 媒体缓存目录 · {@code {configHome}/media-cache/{sessionId}}。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @return {@code {configHome}/media-cache/{sessionId}}
     */
    public Path getMediaStoreDir(String sessionId) {
        return storeDir(sessionId);
    }

    /**
     * 媒体文件路径 · 确定性推导：{@code {storeDir}/{id}.{ext}}。不依赖内存索引，
     * 即使索引逐出 / 服务重启仍可还原（CC 路径通道：文件在盘，路径可重建）。
     *
     * @param sessionId 会话 id
     * @param id        媒体 id（contentId）
     * @param mediaType MIME 类型（推扩展名）
     * @return {@code {storeDir}/{id}.{ext}} 绝对路径
     */
    public String getMediaPath(String sessionId, long id, String mediaType) {
        return buildPath(sessionId, id, mediaType);
    }

    // ════════════════════════════════════════════════════════════════════
    // 落盘（上传端点 U1 调用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 落盘一个媒体文件（自动分配 id）· 上传端点主入口（对齐 {@link PdfAttachmentStore#store}）。
     *
     * <p>从 {@link InputStream} 流式写入 {@code {storeDir}/{id}.{ext}}，FileChannel.force(true)
     * 刷盘，随后 FIFO 逐出 + 登记内存索引。失败不抛出，返回 null。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @param in        上传文件输入流（调用方负责关闭）
     * @param size      声明大小（字节，上传校验已算，供写盘后核对）
     * @param filename  客户端原始文件名（显示名，可 null）
     * @param mediaType MIME 类型（推扩展名）
     * @return 落盘结果；失败返回 null
     */
    public StoredMedia store(String sessionId, InputStream in, long size, String filename, String mediaType) {
        String sid = resolveSessionId(sessionId);
        long id = nextId(sid);
        String resolvedMediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        try {
            Path dir = getMediaStoreDir(sid);
            Files.createDirectories(dir);
            String path = getMediaPath(sid, id, resolvedMediaType);
            writeStream(path, in, size);
            StoredMedia stored = new StoredMedia(id, path, filename, size, resolvedMediaType);
            put(sid, id, stored);
            if (log.isDebugEnabled()) {
                log.debug("媒体附件落盘成功：session={} id={} filename={} mediaType={} size={}B path={}",
                        sid, id, filename, resolvedMediaType, size, path);
            }
            return stored;
        } catch (Exception e) {
            log.warn("媒体附件落盘失败：session={} id={} filename={} mediaType={} size={}B 原因={}",
                    sid, id, filename, resolvedMediaType, size, e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 读取（路径通道消费侧用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取内存索引记录（含 filename/size/mediaType 元数据）· 需内存命中；被逐出 / 重启后未命中返回 null。
     *
     * @param sessionId 会话 id
     * @param id        媒体 id（contentId）
     * @return 存储记录；未命中返回 null
     */
    public StoredMedia get(String sessionId, long id) {
        return super.get(sessionId, id);
    }

    /**
     * 读取磁盘路径 · 路径通道解析入口（确定性，不依赖内存索引）。
     * 恒返回 {@code {configHome}/media-cache/{sessionId}/{id}.{ext}}（mediaType 推扩展名）。
     *
     * @param sessionId 会话 id
     * @param id        媒体 id（contentId）
     * @param mediaType MIME 类型（推扩展名）
     * @return 媒体磁盘绝对路径
     */
    public String getPath(String sessionId, long id, String mediaType) {
        return getMediaPath(sessionId, id, mediaType);
    }

    /**
     * 读取客户端原始文件名（显示名）· 需内存命中；未命中返回 null。
     *
     * @param sessionId 会话 id
     * @param id        媒体 id（contentId）
     * @return 原始文件名；未命中返回 null
     */
    public String getFilename(String sessionId, long id) {
        StoredMedia media = get(sessionId, id);
        return media == null ? null : media.filename();
    }

    /**
     * 读取 MIME 类型 · 需内存命中；未命中返回 null。
     *
     * @param sessionId 会话 id
     * @param id        媒体 id（contentId）
     * @return MIME 类型；未命中返回 null
     */
    public String getMediaType(String sessionId, long id) {
        StoredMedia media = get(sessionId, id);
        return media == null ? null : media.mediaType();
    }

    // ════════════════════════════════════════════════════════════════════
    // 清理（对齐 PdfAttachmentStore 清理语义）
    // ════════════════════════════════════════════════════════════════════

    /** 清空全部会话媒体内存索引（不动磁盘）。 */
    public void clearAll() {
        super.clearAll();
    }

    /**
     * 清理单个会话的媒体缓存（会话结束/删除时调用）· 清内存桶 + 删除 {@code media-cache/{sessionId}/} 目录。
     *
     * @param sessionId 会话 id
     */
    public void cleanupSession(String sessionId) {
        super.cleanupSession(sessionId);
    }
}

package com.nexusai.application.agent.attachment;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PDF 附件路径存储 · U1 multipart 上传落盘（&gt;5MB 大 PDF 走路径通道，对齐 CC 路径通道语义）。
 *
 * <p><b>U1 混合传输（C）定位</b>：PDF ≤5MB 走 base64 直传（图片附件通道 A1，{@link ImageAttachmentStore}
 * 语义）；&gt;5MB 经 {@code POST /api/v1/attachments/upload} multipart 上传本存储<b>落盘</b>，返回
 * {@code contentId}。后端消费时按 <b>磁盘路径</b>解析（CC {@code utils/pdf.ts} readPDF 以 filePath
 * 读取，非 base64 内存直发 —— 这就是「路径通道」），避免 &gt;5MB base64 放大 ~33% 后逼近 Anthropic
 * API 32MB 请求上限。
 *
 * <p>[attachments-v2 重构] 本类继承 {@link AttachmentStoreBase}（公共基础设施下沉基类），
 * 保留全部公共 API 签名（调用方零改动）。
 *
 * <p><b>路径确定性（Java 侧路径通道强化）</b>：{@link #getPath(String, long)} 恒返回确定性路径
 * {@code {configHome}/pdf-cache/{sessionId}/{id}.pdf}，不依赖内存索引 —— 即使被 200 上限逐出 /
 * 服务重启，下游仍可按 contentId 还原磁盘路径读文件（CC 路径通道语义：文件在盘，路径可重建）。
 * {@link #get(String, long)} / {@link #getFilename(String, long)} 需内存索引命中（记录文件名等元数据）。
 *
 * <p><b>size 阈值来源</b>：上传上限对齐 {@link com.nexusai.application.agent.tool.impl.PdfSupport#PDF_MAX_EXTRACT_SIZE}
 * （100MB，apiLimits.ts:72）；base64 直传阈值对齐
 * {@link MediaLimitConstants#API_IMAGE_MAX_BASE64_SIZE}（5MB，apiLimits.ts:19）。落盘路径经
 * {@code FileChannel.force(true)} 刷盘（基类 writeStream，data-sync 语义，防「上传成功但字节未落盘」）。
 */
@Component
public final class PdfAttachmentStore extends AttachmentStoreBase<PdfAttachmentStore.StoredPdf> {

    /** PDF 缓存根目录名 · 对齐 ImageAttachmentStore {@code IMAGE_STORE_DIR='image-cache'} 的 pdf 对等名。 */
    public static final String PDF_CACHE_DIR = "pdf-cache";

    /** 每会话内存索引上限 · 对齐 ImageAttachmentStore {@code MAX_STORED_IMAGE_PATHS=200}（imageStore.ts:10）。 */
    public static final int MAX_STORED_PDF_PATHS = 200;

    /**
     * 单条 PDF 存储记录。
     *
     * @param id       PDF id（路径通道 contentId）
     * @param path     磁盘绝对路径 {@code {configHome}/pdf-cache/{sessionId}/{id}.pdf}
     * @param filename 客户端原始文件名（显示名）
     * @param size     原始字节数
     */
    public record StoredPdf(long id, String path, String filename, long size) {}

    // ════════════════════════════════════════════════════════════════════
    // 模板方法钩子（子类差异点）
    // ════════════════════════════════════════════════════════════════════

    /** PDF 缓存目录名 · 对齐 ImageAttachmentStore {@code IMAGE_STORE_DIR} 的 pdf 对等名。 */
    @Override
    protected String cacheDirName() {
        return PDF_CACHE_DIR;
    }

    /** PDF 扩展名恒 'pdf'（路径通道文件名 {@code {id}.pdf}）。 */
    @Override
    protected String extensionOf(String mediaType) {
        return "pdf";
    }

    /** 新建会话桶 · FIFO 插入序（同 ImageAttachmentStore）。 */
    @Override
    protected Map<Long, StoredPdf> newSessionMap() {
        return new LinkedHashMap<>(256, 0.75f, false);
    }

    // ════════════════════════════════════════════════════════════════════
    // 目录与路径（对齐 ImageAttachmentStore imageStore.ts:18-36 模式）
    // ════════════════════════════════════════════════════════════════════

    /**
     * PDF 缓存目录 · {@code {configHome}/pdf-cache/{sessionId}}。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @return {@code {configHome}/pdf-cache/{sessionId}}
     */
    public Path getPdfStoreDir(String sessionId) {
        return storeDir(sessionId);
    }

    /**
     * PDF 文件路径 · 确定性推导（路径通道核心）：{@code {storeDir}/{id}.pdf}。不依赖内存索引，
     * 即使索引逐出 / 服务重启仍可还原（CC 路径通道：文件在盘，路径可重建）。
     *
     * @param sessionId 会话 id
     * @param id        PDF id（contentId）
     * @return {@code {storeDir}/{id}.pdf} 绝对路径
     */
    public String getPdfPath(String sessionId, long id) {
        return buildPath(sessionId, id, "pdf");
    }

    // ════════════════════════════════════════════════════════════════════
    // 落盘（上传端点 U1 调用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 落盘一个 PDF（自动分配 id）· U1 上传端点主入口。
     *
     * <p>从 {@link InputStream} 流式写入 {@code {storeDir}/{id}.pdf}，{@code FileChannel.force(true)}
     * 刷盘（基类 writeStream，data-sync 语义），随后 FIFO 逐出 + 登记内存索引。
     * 失败不抛出，返回 null（对齐 CC storeImage catch → null，imageStore.ts:75-78）。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @param in        上传文件输入流（调用方负责关闭；本方法内读取后由调用方 try-with-resources）
     * @param size      声明大小（字节，上传校验已算，供写盘后核对）
     * @param filename  客户端原始文件名（显示名，可 null）
     * @return 落盘结果；失败返回 null
     */
    public StoredPdf store(String sessionId, InputStream in, long size, String filename) {
        String sid = resolveSessionId(sessionId);
        long id = nextId(sid);
        try {
            Path dir = getPdfStoreDir(sid);
            Files.createDirectories(dir);
            String path = getPdfPath(sid, id);
            writeStream(path, in, size);
            StoredPdf stored = new StoredPdf(id, path, filename, size);
            put(sid, id, stored);
            if (log.isDebugEnabled()) {
                log.debug("PDF 附件落盘成功：session={} id={} filename={} size={}B path={}（U1 路径通道）",
                        sid, id, filename, size, path);
            }
            return stored;
        } catch (Exception e) {
            log.warn("PDF 附件落盘失败：session={} id={} filename={} size={}B 原因={}",
                    sid, id, filename, size, e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 读取（路径通道消费侧 U2 用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取内存索引记录（含 filename/size 元数据）· 需内存命中；被逐出 / 重启后未命中返回 null
     * （同 ImageAttachmentStore {@code get}）。
     *
     * @param sessionId 会话 id
     * @param id        PDF id（contentId）
     * @return 存储记录；未命中返回 null
     */
    public StoredPdf get(String sessionId, long id) {
        return super.get(sessionId, id);
    }

    /**
     * 读取磁盘路径 · 路径通道解析入口（确定性，不依赖内存索引）。
     * 恒返回 {@code {configHome}/pdf-cache/{sessionId}/{id}.pdf}；文件是否在盘由调用方 {@code Files.exists} 判定。
     *
     * @param sessionId 会话 id
     * @param id        PDF id（contentId）
     * @return PDF 磁盘绝对路径
     */
    public String getPath(String sessionId, long id) {
        return getPdfPath(sessionId, id);
    }

    /**
     * 读取客户端原始文件名（显示名）· 需内存命中；未命中返回 null。
     *
     * @param sessionId 会话 id
     * @param id        PDF id（contentId）
     * @return 原始文件名；未命中返回 null
     */
    public String getFilename(String sessionId, long id) {
        StoredPdf pdf = get(sessionId, id);
        return pdf == null ? null : pdf.filename();
    }

    // ════════════════════════════════════════════════════════════════════
    // 清理（对齐 ImageAttachmentStore 清理语义）
    // ════════════════════════════════════════════════════════════════════

    /** 清空全部会话 PDF 内存索引（不动磁盘）· 对齐 ImageAttachmentStore {@code clearStoredImagePaths}。 */
    public void clearAll() {
        super.clearAll();
    }

    /**
     * 清理单个会话的 PDF 缓存（会话结束/删除时调用）· 清内存桶 + 删除 {@code {pdf-cache}/{sessionId}/} 目录。
     * 对齐 {@link ImageAttachmentStore#cleanupSession}。
     *
     * @param sessionId 会话 id
     */
    public void cleanupSession(String sessionId) {
        super.cleanupSession(sessionId);
    }
}

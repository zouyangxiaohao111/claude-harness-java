package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.impl.PdfSupport;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PDF 附件处理器 · R1 分页决策 + document/image block 注入（≤{@link PdfSupport#PDF_MAX_PAGES_PER_READ} 页）。
 *
 * <p><b>定位</b>：主 user 消息附件消费处（LlmAgentLoop A4 注入，对齐 CC attachments.ts:1062-1071
 * prompt 数组 {@code [{type:'text'}, ...blocks]}）。[附件双模式] 三条传输通道在此统一解析
 * （{@link #resolveFilePath(String, String, String, String)}）：
 * <ul>
 *   <li><b>path 附件通道</b>（local-read 前后端同机外部绝对路径直读，附件表零拷贝注册同源）：非空 path
 *       直读外部文件（{@link #resolveExternalPath}）——&gt;5MB 本地桌面附件省 upload 的落点</li>
 *   <li><b>路径通道</b>（&gt;5MB multipart 上传落盘 / 附件表注册，base64=null + contentId）：优先经
 *       {@link AttachmentService#getPath}（attachments 表统一 contentId 中心）还原真实 path，附件表无记录
 *       （历史存量 store contentId）→ 回退 {@link PdfAttachmentStore#getPath}（CC 路径通道语义，pdf.ts
 *       readPDF 以 filePath 读取）</li>
 *   <li><b>base64 通道</b>（≤5MB 直传，base64 非空）：解码写临时 PDF 文件（分页/页提取均需磁盘路径，
 *       对齐 CC readPDF 以 filePath 读取）</li>
 * </ul>
 *
 * <p><b>分页决策 + 三态解析</b>（对齐 CC FileReadTool.ts:894-1017 + pdf.ts:179-300）：
 * <ol>
 *   <li>分页：{@link PdfSupport#getPDFPageCount} → 页数 &gt; {@link PdfSupport#PDF_MAX_PAGES_PER_READ}(20)
 *       → {@link Resolution#NEEDS_SUBAGENT}（R2 subagent 解析标记）；页数 null（无法确定）→ 继续三态
 *       （CC :949-955 {@code pageCount !== null && pageCount > threshold} 等价）</li>
 *   <li>≤3MB（{@link PdfSupport#PDF_EXTRACT_SIZE_THRESHOLD}）→ document block（CC :1001-1015）</li>
 *   <li>&gt;3MB ≤100MB → {@link PdfSupport#extractPDFPages} 逐页 JPEG → image blocks
 *       （CC :916-945 页图 image blocks 送达）</li>
 *   <li>&gt;100MB / 读取失败 / 密码保护 → {@link Resolution#ERROR}（PdfError reason/message）</li>
 * </ol>
 *
 * <p><b>注入模式</b>：{@link #registerPdfAttachments} 解析附件 → 存 {@code pendingPdfs}（按会话），
 * 主 user 消息构造（LlmAgentLoop {@code buildUserMessageWithImages} → {@link #drainPendingPdfs}）消费：
 * NEEDS_SUBAGENT → 注入文本说明（R2 后续处理），不注入媒体块。
 *
 * <p><b>并发</b>：pending 表 {code synchronized(lock)} 复合操作（同 {@link ImageAttachmentStore} 模式）。
 * <b>失败语义</b>：resolve 失败不抛出 → {@code ERROR}（fail loud，调用方 warn 跳过该附件，不中断整个请求）。
 */
@Component
public final class PdfAttachmentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PdfAttachmentProcessor.class);

    /** 无会话兜底桶名 · 同 {@link ImageAttachmentStore} / {@link PdfAttachmentStore}。 */
    private static final String UNKNOWN_SESSION = "unknown";

    /** 解析结果分派：INJECT=直接注入 blocks；NEEDS_SUBAGENT=&gt;20 页需 subagent（R2）；ERROR=解析失败跳过。 */
    public enum Resolution { INJECT, NEEDS_SUBAGENT, ERROR }

    /**
     * resolvePdfBlocks 结果。
     *
     * @param resolution       分派
     * @param blocks           待注入 content blocks（INJECT 时 document/image block；其余空列表）
     * @param pageCount        页数（null = 无法确定）
     * @param error            ERROR 时的错误（CC PDFError 等价）
     * @param visionContentIds [pdf-vision-align] 文本模型 PDF 页图注册的 ImageAttachmentStore contentId
     *                         列表（多模态路径 = 空列表；文本模型路径非空，供 LlmAgentLoop 拼 vision_analyze 说明）
     */
    public record PdfBlocksResult(
            Resolution resolution,
            List<ContentBlockParam> blocks,
            Integer pageCount,
            PdfSupport.PdfError error,
            List<Long> visionContentIds) {

        public static PdfBlocksResult inject(List<ContentBlockParam> blocks, Integer pageCount) {
            return new PdfBlocksResult(Resolution.INJECT, blocks, pageCount, null, List.of());
        }

        public static PdfBlocksResult needsSubagent(Integer pageCount) {
            return new PdfBlocksResult(Resolution.NEEDS_SUBAGENT, List.of(), pageCount, null, List.of());
        }

        public static PdfBlocksResult failure(PdfSupport.PdfError error, Integer pageCount) {
            return new PdfBlocksResult(Resolution.ERROR, List.of(), pageCount, error, List.of());
        }

        /** [pdf-vision-align] 文本模型 PDF 页图注册成功（resolution=INJECT，无 document/image block，仅 contentId 列表）。 */
        public static PdfBlocksResult injectVision(List<Long> contentIds, Integer pageCount) {
            return new PdfBlocksResult(Resolution.INJECT, List.of(), pageCount, null, contentIds);
        }
    }

    /**
     * 待注入 PDF（{@link #drainPendingPdfs} 返回，供 A4 主 user 消息注入）。
     *
     * @param filename         客户端原始文件名（可 null）
     * @param blocks           待注入 content blocks（needsSubagent=false 时非空）
     * @param needsSubagent    true = &gt;20 页，需 subagent 解析（R2），本次不注入媒体块
     * @param pageCount        页数（可 null）
     * @param visionContentIds [pdf-vision-align] 文本模型 PDF 页图注册 contentId 列表（多模态路径 = 空列表）
     */
    public record PendingPdf(String filename, List<ContentBlockParam> blocks, boolean needsSubagent,
                             Integer pageCount, List<Long> visionContentIds) {}

    /** 会话 → 待注入 PDF 列表（drain 消费并清除，只注入一次，对齐 CC prompt submit 一次性构建）。 */
    private final Map<String, List<PendingPdf>> pendingPdfs = new HashMap<>();

    /** 锁：保护 {@link #pendingPdfs} 复合操作（get + put / remove）。 */
    private final Object lock = new Object();

    /** PDF 路径存储 · 路径通道 contentId → 磁盘路径解析（base64 通道可 null → 系统临时目录兜底）。 */
    @Autowired(required = false)
    private PdfAttachmentStore pdfAttachmentStore;

    /** 测试 / 非 Spring 场景注入 PdfAttachmentStore（生产由 Spring 字段注入）。 */
    public void setPdfAttachmentStore(PdfAttachmentStore pdfAttachmentStore) {
        this.pdfAttachmentStore = pdfAttachmentStore;
    }

    /**
     * [附件双模式] 附件表（attachments）统一 contentId 注册中心 · path/upload 大文件附件 contentId → 真实 path。
     *
     * <p>{@code resolvePathChannel} 路径通道优先经本表解析（用户拍板 2026-09-02：所有产生 contentId 的
     * PDF 附件统一注册附件表，contentId = attachments 自增 id，零拷贝引用外部 path）；附件表无记录
     * （历史存量 store contentId）→ 回退 {@link PdfAttachmentStore}。{@code @Autowired(required=false)}：
     * 测试 / 非 Spring 场景 new 出本类时为 null → 恒走 PdfAttachmentStore 回退（现状不变）。
     */
    @Autowired(required = false)
    private AttachmentService attachmentService;

    /** 测试 / 非 Spring 场景注入 AttachmentService（生产由 Spring 字段注入）。 */
    public void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 解析 PDF 附件 → content blocks（document block / 页图 image blocks）· R1 核心。
     *
     * <p>[附件双模式] 输入三通道（互斥，contentId/base64 二参即 3 参签名可承载的全部通道；path 通道
     * 见 6 参重载）：
     * <ul>
     *   <li>{@code contentId} 非空白（base64 空，路径通道）→ {@link #resolvePathChannel}（附件表优先 /
     *       {@link PdfAttachmentStore#getPath} 回退）还原磁盘路径</li>
     *   <li>{@code base64} 非空白（base64 通道）→ 解码写临时 PDF 文件（分页/提取需磁盘路径）</li>
     * </ul>
     *
     * @param sessionId 会话 id（PdfAttachmentStore 按会话分桶 / 缓存目录）
     * @param contentId PDF contentId（路径通道；可 null）
     * @param base64    base64 直传内容（base64 通道；可 null）
     * @return 解析结果（INJECT / NEEDS_SUBAGENT / ERROR）
     */
    public PdfBlocksResult resolvePdfBlocks(String sessionId, String contentId, String base64) {
        // 旧 3 参（多模态默认）→ 委托 5 参重载（textModel=false, imageStore=null），既有调用方零改动
        return resolvePdfBlocks(sessionId, contentId, base64, false, null);
    }

    /**
     * [pdf-vision-align] 解析 PDF 附件 → content blocks / 页图注册 · 5 参重载。
     *
     * <p>新增 <b>文本模型分支</b>（textModel=true && imageStore != null）：PDF 恒转逐页 JPEG 注册到
     * {@link ImageAttachmentStore}（contentId 列表返回，供 LlmAgentLoop 文本模型分支拼 vision_analyze
     * 说明）——文本模型发不出 document block（deepseek 400 根因），改用视觉工具路由。
     * textModel=false → 走原三态（document / 页图 image blocks，现状不动）。
     *
     * <p>尺寸上限检查（&gt;maxExtract → ERROR）两分支共享，先行于三态（≤3MB document 分支不触发
     * too_large，行为与原顺序等价）。
     *
     * @param sessionId 会话 id（PdfAttachmentStore 按会话分桶 / 缓存目录）
     * @param contentId PDF contentId（路径通道；可 null）
     * @param base64    base64 直传内容（base64 通道；可 null）
     * @param textModel 当前请求模型是否为文本模型（无图片能力 → 页图注册路由）
     * @param imageStore 图片附件缓存（textModel=true 时必须非 null；null → 回落原三态）
     * @return 解析结果（INJECT / NEEDS_SUBAGENT / ERROR）
     */
    public PdfBlocksResult resolvePdfBlocks(String sessionId, String contentId, String base64,
                                            boolean textModel, ImageAttachmentStore imageStore) {
        // [附件双模式] 旧 5 参（contentId/base64 双通道）→ 委托 6 参重载（path=null → 三通道等价），
        //   既有调用方（测试 / LlmAgentLoop 文本模型分支）零改动
        return resolvePdfBlocks(sessionId, null, contentId, base64, textModel, imageStore);
    }

    /**
     * [pdf-vision-align][附件双模式] 解析 PDF 附件 → content blocks / 页图注册 · 6 参重载（path 附件直读）。
     *
     * <p>新增 <b>path 直读通道</b>（附件双模式：local-read 外部绝对路径附件，resolveAttachments 已注册
     * 附件表零拷贝，doRun 消费时仍可直接读外部 path —— 与附件表 contentId 解析同源同文件，见
     * {@link #resolveFilePath(String, String, String, String)}）。复用既有分页决策：≤3MB → document block；
     * &gt;3MB → extractPDFPages 逐页 image block；文本模型（textModel=true）→ 恒转页图注册。
     *
     * <p>文本模型分支（textModel=true && imageStore != null）：PDF 恒转逐页 JPEG 注册到
     * {@link ImageAttachmentStore}（contentId 列表返回，供 LlmAgentLoop 文本模型分支拼 vision_analyze
     * 说明）——文本模型发不出 document block（deepseek 400 根因），改用视觉工具路由。
     * textModel=false → 走原三态（document / 页图 image blocks，现状不动）。
     *
     * <p>尺寸上限检查（&gt;maxExtract → ERROR）两分支共享，先行于三态（≤3MB document 分支不触发
     * too_large，行为与原顺序等价）。
     *
     * @param sessionId 会话 id（PdfAttachmentStore 按会话分桶 / 缓存目录）
     * @param path      本地绝对路径（path 附件直读通道；可 null）
     * @param contentId PDF contentId（路径通道；可 null）
     * @param base64    base64 直传内容（base64 通道；可 null）
     * @param textModel 当前请求模型是否为文本模型（无图片能力 → 页图注册路由）
     * @param imageStore 图片附件缓存（textModel=true 时必须非 null；null → 回落原三态）
     * @return 解析结果（INJECT / NEEDS_SUBAGENT / ERROR）
     */
    public PdfBlocksResult resolvePdfBlocks(String sessionId, String path, String contentId, String base64,
                                            boolean textModel, ImageAttachmentStore imageStore) {
        Path filePath;
        try {
            filePath = resolveFilePath(sessionId, path, contentId, base64);
        } catch (NumberFormatException e) {
            return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.UNKNOWN,
                "PDF contentId 非数字，无法解析"), null);
        } catch (Exception e) {
            return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.UNKNOWN,
                "PDF 附件路径解析失败: " + (e.getMessage() == null ? e.toString() : e.getMessage())), null);
        }

        // ── 分页决策 · 对齐 CC FileReadTool.ts:949-955（pageCount null → 继续三态）──
        Integer pageCount = PdfSupport.getPDFPageCount(filePath);
        if (pageCount != null && pageCount > PdfSupport.PDF_MAX_PAGES_PER_READ) {
            if (log.isDebugEnabled()) {
                log.debug("[U2 分页决策] PDF 页数 {} 超出单次直接读取上限 {} → NEEDS_SUBAGENT（R2 subagent 分页解析）path={}",
                    pageCount, PdfSupport.PDF_MAX_PAGES_PER_READ, filePath);
            }
            return PdfBlocksResult.needsSubagent(pageCount);
        }
        if (log.isDebugEnabled()) {
            log.debug("[U2 分页决策] PDF 页数 {} ≤ {} → 直接注入（document/image block / 文本模型页图注册）path={}",
                pageCount == null ? "未知(null)" : pageCount, PdfSupport.PDF_MAX_PAGES_PER_READ, filePath);
        }

        // ── 尺寸读取 + 上限检查（>maxExtract → ERROR，两分支共享）──
        long size;
        try {
            size = Files.size(filePath);
        } catch (Exception e) {
            return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.UNKNOWN,
                "PDF 读取 size 失败: " + (e.getMessage() == null ? e.toString() : e.getMessage())), pageCount);
        }
        if (size > PdfSupport.getMaxExtractSize()) {
            return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.TOO_LARGE,
                "PDF exceeds maximum allowed size for text extraction ("
                    + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(
                        PdfSupport.getMaxExtractSize()) + ")."), pageCount);
        }

        // ── [pdf-vision-align] 文本模型分支 · 恒转页图注册（≤100MB 任意大小；document block 文本模型发不出去）──
        if (textModel && imageStore != null) {
            PdfSupport.PdfExtractResult extract = PdfSupport.extractPDFPages(
                filePath, cacheBaseDir(sessionId).resolve("pages-" + UUID.randomUUID()), null, null);
            if (!extract.success()) {
                if (log.isDebugEnabled()) {
                    log.debug("[U2 三态][pdf-vision-align] extractPDFPages 失败（文本模型页图注册）path={} reason={} message={}",
                        filePath, extract.error().reason(), extract.error().message());
                }
                return PdfBlocksResult.failure(extract.error(), pageCount);
            }
            if (log.isInfoEnabled()) {
                log.info("[U2 三态][pdf-vision-align] PDF 文本模型 → 恒转页图注册 path={} pages={} size={}B",
                    filePath, pageCount, size);
            }
            return registerPageImagesToStore(extract.data(), sessionId, imageStore, pageCount);
        }

        // ── 三态解析 · ≤3MB → document block；>3MB → extractPDFPages 逐页 image block ──
        if (size <= PdfSupport.getExtractSizeThreshold()) {
            // 三态 1 · document block（CC FileReadTool.ts:1001-1015）
            PdfSupport.PdfReadResult read = PdfSupport.readPDF(filePath);
            if (!read.success()) {
                if (log.isDebugEnabled()) {
                    log.debug("[U2 三态] readPDF 失败 path={} reason={} message={}",
                        filePath, read.error().reason(), read.error().message());
                }
                return PdfBlocksResult.failure(read.error(), pageCount);
            }
            if (log.isInfoEnabled()) {
                log.info("[U2 三态] PDF ≤{}B → document block path={} pages={} size={}B",
                    PdfSupport.getExtractSizeThreshold(), filePath, pageCount, size);
            }
            return PdfBlocksResult.inject(List.of(
                ContentBlockParam.DocumentBlockParam.of(PdfSupport.PDF_MEDIA_TYPE, read.data().base64())),
                pageCount);
        }

        // 三态 2 · >3MB ≤100MB → extractPDFPages 逐页 image block（CC FileReadTool.ts:916-945）
        PdfSupport.PdfExtractResult extract = PdfSupport.extractPDFPages(
            filePath, cacheBaseDir(sessionId).resolve("pages-" + UUID.randomUUID()), null, null);
        if (!extract.success()) {
            if (log.isDebugEnabled()) {
                log.debug("[U2 三态] extractPDFPages 失败 path={} reason={} message={}",
                    filePath, extract.error().reason(), extract.error().message());
            }
            return PdfBlocksResult.failure(extract.error(), pageCount);
        }
        List<ContentBlockParam> imageBlocks = loadPageImageBlocks(extract.data());
        if (imageBlocks.isEmpty()) {
            return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.CORRUPTED,
                "PDF 页图产出为空（渲染失败）"), pageCount);
        }
        if (log.isInfoEnabled()) {
            log.info("[U2 三态] PDF >{}B → 逐页 image block path={} pages={} size={}B 页图={}",
                PdfSupport.getExtractSizeThreshold(), filePath, pageCount, size, imageBlocks.size());
        }
        return PdfBlocksResult.inject(imageBlocks, pageCount);
    }

    /**
     * [附件双模式] 3 参路径解析（contentId/base64 双通道）· 委托 4 参重载（path=null → 三通道等价）。
     *
     * @param sessionId 会话 id
     * @param contentId PDF contentId（路径通道；可 null）
     * @param base64    base64 直传内容（base64 通道；可 null）
     * @return 磁盘路径
     * @throws NumberFormatException contentId 非数字
     * @throws Exception             路径解析 / 解码写文件失败
     */
    private Path resolveFilePath(String sessionId, String contentId, String base64) throws Exception {
        return resolveFilePath(sessionId, null, contentId, base64);
    }

    /**
     * [pdf-vision-align][附件双模式] 解析 PDF 附件 → 磁盘路径（三通道互斥，抽取自 resolvePdfBlocks 供重载复用）。
     * {@code path} 非空白（path 附件，local-read 外部绝对路径直读）→ 直读外部 path；contentId 非空白
     * （路径通道）→ {@link #resolvePathChannel}（附件表优先 / store 回退）；base64 非空白（base64 通道）→
     * 解码写临时文件；三者皆无 → {@link IllegalArgumentException}（fail loud，由调用方映射 ERROR）。
     *
     * @param sessionId 会话 id
     * @param path      本地绝对路径（path 附件；可 null）
     * @param contentId PDF contentId（路径通道；可 null）
     * @param base64    base64 直传内容（base64 通道；可 null）
     * @return 磁盘路径
     * @throws NumberFormatException contentId 非数字
     * @throws Exception             路径解析 / 解码写文件失败
     */
    private Path resolveFilePath(String sessionId, String path, String contentId, String base64) throws Exception {
        if (path != null && !path.isBlank()) {
            return resolveExternalPath(path);
        }
        if (contentId != null && !contentId.isBlank()) {
            return resolvePathChannel(sessionId, contentId);
        } else if (base64 != null && !base64.isBlank()) {
            return writeBase64Channel(sessionId, base64);
        }
        throw new IllegalArgumentException("PDF 附件既无 path 也无 contentId（路径通道）也无 base64（直传通道），无法解析");
    }

    /**
     * [附件双模式] path 附件（local-read 前后端同机外部绝对路径）直读通道：校验文件存在即返回磁盘路径
     * （复用 resolvePdfBlocks 分页决策 ≤3MB document / &gt;3MB image block / 文本模型页图注册）。
     * 附件表注册是零拷贝（register 存 path 引用），故 path 直读与附件表 contentId 解析同源同文件。
     */
    private static Path resolveExternalPath(String path) throws Exception {
        if (path.isBlank()) {
            throw new IllegalArgumentException("PDF path 附件路径为空");
        }
        Path p = Path.of(path.trim());
        if (!Files.exists(p)) {
            throw new IllegalStateException("PDF 外部路径文件不存在: " + p);
        }
        if (log.isDebugEnabled()) {
            log.debug("[U2] PDF path 附件通道直读: path={}", p);
        }
        return p;
    }

    /**
     * 把 RunRequest.attachments() 的 type=pdf 项解析为待注入 PDF（pendingPdfs），供 A4 主 user
     * 消息构造 drain 消费 · R1 生产链路接线。
     *
     * <p>对齐 {@code registerRunPromptImages}（F1）模式：doRun 入口调用（先于首个 user 消息构造），
     * 主 user 消息构造 {@code buildUserMessageWithImages} → {@link #drainPendingPdfs} 消费。
     * NEEDS_SUBAGENT（&gt;20 页）同样登记（PendingPdf.needsSubagent=true），注入侧转文本说明（R2 后续）。
     *
     * @param sessionId  会话 id（路径通道 PdfAttachmentStore 解析用）
     * @param sessionKey 待注入 PDF 会话键（pending 表分桶 · 同 {@code imageSessionKey}）
     * @param attachments RunRequest.attachments()（可为 null/空）
     * @return 已登记 PDF 数（含 NEEDS_SUBAGENT；0 = 无 PDF 附件）
     */
    public int registerPdfAttachments(String sessionId, String sessionKey,
                                      List<AttachmentRequest> attachments) {
        // 旧 3 参（多模态默认）→ 委托 5 参重载（textModel=false, imageStore=null），既有调用方零改动
        return registerPdfAttachments(sessionId, sessionKey, attachments, false, null);
    }

    /**
     * [pdf-vision-align][附件双模式] 把 RunRequest.attachments() 的 type=pdf 项解析为待注入 PDF（pendingPdfs）。
     *
     * <p>[附件双模式] 每项按三通道解析（{@link #resolvePdfBlocks(String, String, String, String, boolean,
     * ImageAttachmentStore)}）：path 附件（非空 path 直读外部绝对路径）→ base64/contentId 通道不变。
     * 新增 textModel+imageStore：文本模型 PDF → 页图注册 contentId 列表（PendingPdf.visionContentIds），
     * 供 LlmAgentLoop 文本模型分支拼 vision_analyze 说明（不发 document/image block）。
     *
     * @param sessionId   会话 id（路径通道解析用）
     * @param sessionKey  待注入 PDF 会话键（pending 表分桶 · 同 {@code imageSessionKey}）
     * @param attachments RunRequest.attachments()（可为 null/空）
     * @param textModel   当前请求模型是否为文本模型（无图片能力 → 页图注册路由）
     * @param imageStore  图片附件缓存（textModel=true 时须注入；null → 回落原三态）
     * @return 已登记 PDF 数（含 NEEDS_SUBAGENT；0 = 无 PDF 附件）
     */
    public int registerPdfAttachments(String sessionId, String sessionKey,
                                      List<AttachmentRequest> attachments,
                                      boolean textModel, ImageAttachmentStore imageStore) {
        if (attachments == null || attachments.isEmpty()) {
            return 0;
        }
        List<PendingPdf> pending = new ArrayList<>();
        int registered = 0;
        for (AttachmentRequest att : attachments) {
            if (att == null || !isPdfAttachment(att)) {
                continue;
            }
            // [附件双模式] 传 att.path()：path 附件（local-read 外部绝对路径）→ 直读外部 path；
            //   path 空 → contentId/base64 双通道（内容Id附件表化解析 / ≤5MB base64 直传）不变
            PdfBlocksResult result = resolvePdfBlocks(sessionId, att.path(), att.contentId(), att.base64(),
                textModel, imageStore);
            if (result.resolution() == Resolution.INJECT) {
                pending.add(new PendingPdf(att.filename(), result.blocks(), false, result.pageCount(),
                    result.visionContentIds()));
                registered++;
            } else if (result.resolution() == Resolution.NEEDS_SUBAGENT) {
                pending.add(new PendingPdf(att.filename(), List.of(), true, result.pageCount(), List.of()));
                registered++;
            } else {
                log.warn("[U2] PDF 附件解析失败跳过: contentId={} filename={} reason={} message={}",
                    att.contentId(), att.filename(),
                    result.error().reason(), result.error().message());
            }
        }
        if (!pending.isEmpty()) {
            String key = normalizeSessionKey(sessionKey);
            synchronized (lock) {
                pendingPdfs.computeIfAbsent(key, k -> new ArrayList<>()).addAll(pending);
            }
            if (log.isDebugEnabled()) {
                log.debug("[U2] PDF 附件已登记为待注入: sessionKey={} PDF数={}（A4 主 user 消息 PDF blocks 注入）",
                    key, pending.size());
            }
        }
        if (log.isInfoEnabled() && registered > 0) {
            log.info("[U2] PDF 附件生产链路接通: sessionId={} PDF数={}（RunRequest.attachments → PdfAttachmentProcessor → 主 user 消息 PDF blocks 注入）",
                sessionId, registered);
        }
        return registered;
    }

    /**
     * 消费并清除本会话待注入 PDF（drain 语义 · 同 {@code drainPendingPromptImages}）·
     * A4 主 user 消息构造调用。空 → 空列表。
     *
     * @param sessionKey 待注入 PDF 会话键（与 {@link #registerPdfAttachments} 相同）
     * @return 待注入 PDF 列表（消费后清除）
     */
    public List<PendingPdf> drainPendingPdfs(String sessionKey) {
        String key = normalizeSessionKey(sessionKey);
        synchronized (lock) {
            List<PendingPdf> drained = pendingPdfs.remove(key);
            return drained == null ? List.of() : drained;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 通道解析
    // ════════════════════════════════════════════════════════════════════

    /**
     * 路径通道：contentId → 磁盘路径（附件表统一解析优先）· CC 路径通道语义（文件在盘路径可重建）。
     *
     * <p>[附件双模式 · 用户拍板 2026-09-02] 产生 contentId 的 PDF 附件统一注册附件表
     * （attachments），contentId = attachments 自增 id → 先经 {@link AttachmentService#getPath} 解析
     * 真实 path（零拷贝引用，本地桌面 >5MB 附件传 path 直读的落点）；附件表无记录（历史存量 store
     * contentId / 非 Spring 单测）→ 回退 {@link PdfAttachmentStore#getPath}。文件不存在 → corrupted 错误。
     * 附件表与 store 均未注入且无记录 → unknown 错误（fail loud）。
     */
    private Path resolvePathChannel(String sessionId, String contentId) throws Exception {
        long pdfId = Long.parseLong(contentId.trim());
        // 附件表统一解析优先（内容一致性 + 支持 F5/重启后 contentId 仍可还原外部 path）
        if (attachmentService != null) {
            // [附件双模式 · id 空间防撞] mediaType 必须为 pdf 才视为附件表命中（对齐发送侧
            //   ChatService.resolveAttachments 的 resolveContentIdInTable "application/pdf" 前缀校验）：
            //   历史存量 store contentId 与附件表其它类型行同数字撞号时（该 contentId 经发送侧已判为
            //   store 真源），此处若不加校验会错取附件表非 pdf 行 path 当作 PDF 读 → 张冠李戴。
            com.nexusai.repository.session.entity.AttachmentRecord rec = attachmentService.getContent(pdfId);
            String mt = rec == null ? null : rec.getMediaType();
            if (rec != null && rec.getPath() != null && !rec.getPath().isBlank()
                    && mt != null && mt.toLowerCase().startsWith("application/pdf")) {
                return toExistingPath(pdfId, rec.getPath(), "附件表");
            }
            if (log.isDebugEnabled()) {
                log.debug("[U2] PDF 附件表无 pdf 记录（附件表无行 / mediaType 非 pdf 撞号 / 历史存量 store contentId），"
                        + "回退 PdfAttachmentStore: session={} contentId={} 附件表mediaType={}", sessionId, contentId, mt);
            }
        }
        if (pdfAttachmentStore == null) {
            throw new IllegalStateException("PdfAttachmentStore 未注入，无法按 contentId 解析 PDF 路径");
        }
        return toExistingPath(pdfId, pdfAttachmentStore.getPath(sessionId, pdfId), "store");
    }

    /** 把解析出的 path 归一为已存在的磁盘文件（不存在 → corrupted 错误，fail loud）。 */
    private static Path toExistingPath(long pdfId, String pathStr, String source) throws Exception {
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalStateException("PDF 磁盘路径为空: contentId=" + pdfId + "（来源=" + source + "）");
        }
        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalStateException("PDF 磁盘文件不存在: " + path + "（来源=" + source + "）");
        }
        if (log.isDebugEnabled()) {
            log.debug("[U2] PDF 路径通道: contentId={} path={}（来源={}）", pdfId, path, source);
        }
        return path;
    }

    /**
     * base64 通道：解码写临时 PDF 文件（分页/提取均需磁盘路径，对齐 CC readPDF 以 filePath 读取）。
     * 写在会话缓存目录（pdf-cache/{sessionId}）下，随会话清理；非法 base64 → IllegalArgumentException。
     */
    private Path writeBase64Channel(String sessionId, String base64) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64);
        Path dir = cacheBaseDir(sessionId);
        Files.createDirectories(dir);
        Path file = dir.resolve("attach-" + UUID.randomUUID() + ".pdf");
        Files.write(file, bytes);
        if (log.isDebugEnabled()) {
            log.debug("[U2] PDF base64 通道: session={} 解码写临时文件 size={}B path={}", sessionId, bytes.length, file);
        }
        return file;
    }

    /**
     * 会话缓存目录 · PdfAttachmentStore 可用 → {@code {configHome}/pdf-cache/{sessionId}}（随会话清理）；
     * 否则（base64 通道 / 非 Spring 单测）→ 系统临时目录 {@code pdf-attach-{sessionId}} 兜底。
     */
    private Path cacheBaseDir(String sessionId) {
        String sid = normalizeSessionKey(sessionId);
        if (pdfAttachmentStore != null) {
            return pdfAttachmentStore.getPdfStoreDir(sid);
        }
        return Path.of(System.getProperty("java.io.tmpdir", ".")).resolve("pdf-attach-" + sid);
    }

    // ════════════════════════════════════════════════════════════════════
    // 页图 image blocks（对齐 CC FileReadTool.ts:916-945）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取 extractPDFPages 产出目录的全部 page-*.jpg → base64 → {@link ContentBlockParam.ImageBlockParam}
     * 列表（CC :917 readdir 过滤 .jpg 排序 → :922-937 image block）。0 页 / 读取失败 → 空列表。
     */
    private List<ContentBlockParam> loadPageImageBlocks(PdfSupport.PdfExtractData data) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        Path outputDir = Path.of(data.outputDir());
        if (!Files.isDirectory(outputDir)) {
            return blocks;
        }
        try (var stream = Files.list(outputDir)) {
            List<Path> jpegs = stream
                .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                .sorted()
                .toList();
            for (Path img : jpegs) {
                byte[] bytes = Files.readAllBytes(img);
                blocks.add(ContentBlockParam.ImageBlockParam.of(
                    "image/jpeg", Base64.getEncoder().encodeToString(bytes)));
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[U2] PDF 页图读取失败 outputDir={} cause={}", outputDir, e.toString());
            }
            return List.of();
        }
        return blocks;
    }

    /**
     * [pdf-vision-align] 把 extractPDFPages 产出页图逐页注册到 {@link ImageAttachmentStore} → contentId 列表。
     *
     * <p><b>逐页读-注册-释放</b>（不批量驻留全部页 base64）：Files.list try-with-resources 关闭目录流，
     * 过滤 .jpg 排序 → 逐页 Files.readAllBytes → {@code imageStore.store(sessionId, base64, "image/jpeg")}
     * 收集 {@link ImageAttachmentStore.StoredImage#id()}。contentIds 空（无 jpg 产出 / 全部注册失败）
     * → CORRUPTED error（fail loud，不发空 contentId 给模型）；否则 INJECT 携带 visionContentIds
     * （供 LlmAgentLoop 文本模型分支拼 vision_analyze 说明）。
     *
     * @param data      extractPDFPages 成功载荷（outputDir / filePath / count）
     * @param sessionId 会话 id（ImageAttachmentStore 按会话分桶）
     * @param imageStore 图片附件缓存（须非 null）
     * @param pageCount 页数（透传，可 null）
     * @return INJECT（visionContentIds）/ ERROR（CORRUPTED）
     */
    private PdfBlocksResult registerPageImagesToStore(PdfSupport.PdfExtractData data, String sessionId,
                                                      ImageAttachmentStore imageStore, Integer pageCount) {
        List<Long> contentIds = new ArrayList<>();
        Path outputDir = Path.of(data.outputDir());
        if (Files.isDirectory(outputDir)) {
            try (var stream = Files.list(outputDir)) {
                List<Path> jpegs = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .toList();
                for (Path img : jpegs) {
                    byte[] bytes = Files.readAllBytes(img);
                    ImageAttachmentStore.StoredImage stored =
                        imageStore.store(sessionId, Base64.getEncoder().encodeToString(bytes), "image/jpeg");
                    if (stored != null) {
                        contentIds.add(stored.id());
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[U2][pdf-vision-align] PDF 页图注册失败 outputDir={} cause={}", outputDir, e.toString());
                }
                return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.CORRUPTED,
                    "PDF 页图注册失败: " + (e.getMessage() == null ? e.toString() : e.getMessage())), pageCount);
            }
        }
        if (contentIds.isEmpty()) {
            return PdfBlocksResult.failure(new PdfSupport.PdfError(PdfSupport.ErrorReason.CORRUPTED,
                "PDF 页图注册为空（渲染失败）"), pageCount);
        }
        if (log.isInfoEnabled()) {
            log.info("[U2][pdf-vision-align] PDF 文本模型页图注册完成 path={} 页图={} contentIds={}",
                data.filePath(), contentIds.size(), contentIds);
        }
        return PdfBlocksResult.injectVision(contentIds, pageCount);
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    /** 是否为 PDF 附件（type=pdf 或 mediaType=application/pdf）。 */
    public static boolean isPdfAttachment(AttachmentRequest att) {
        String type = att.type();
        if (type != null && "pdf".equalsIgnoreCase(type)) {
            return true;
        }
        String mediaType = att.mediaType();
        return mediaType != null && PdfSupport.PDF_MEDIA_TYPE.equalsIgnoreCase(mediaType);
    }

    /** 会话键归一化：null/空白 → 'unknown' 兜底（同 ImageAttachmentStore）。 */
    private static String normalizeSessionKey(String sessionKey) {
        return sessionKey == null || sessionKey.isBlank() ? UNKNOWN_SESSION : sessionKey;
    }
}

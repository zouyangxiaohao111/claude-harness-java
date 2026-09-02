package com.nexusai.application.agent.tool.impl;

import com.nexusai.infra.llm.ModelCapabilityResolver;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * PDF 解析支持 · 严格对齐 CC {@code Open-ClaudeCode/src/utils/pdf.ts}（readPDF / getPDFPageCount /
 * extractPDFPages）+ {@code utils/pdfUtils.ts}（isPDFSupported）+ {@code constants/apiLimits.ts}（PDF 常量）。
 *
 * <p><b>CC → Java 机制替换</b>（行为对齐，实现等价）：CC 依赖 poppler-utils 外部二进制
 * （pdfinfo 页数 / pdftoppm 渲染）；Java 以 Apache PDFBox 3.0.5 进程内等价实现
 * （用户 2026-08-05 拍板「剩余拍板项严格和CC对齐，许可添加POM」）：
 * <ul>
 *   <li>{@code pdfinfo Pages: N} → {@link PDDocument#getNumberOfPages()}</li>
 *   <li>{@code pdftoppm -jpeg -r 100} → {@link PDFRenderer#renderImageWithDPI(int, float, ImageType)} + ImageIO JPEG</li>
 *   <li>{@code isPdftoppmAvailable()}（pdftoppm -v 探测）→ pdfbox 为编译期依赖，进程内恒可用，
 *       {@code 'unavailable'} 错误在 Java 恒不可达（保留枚举成员仅为 CC 契约完整性）</li>
 * </ul>
 *
 * <p><b>错误语义对齐</b>（CC pdf.ts:14-23 PDFError reason 联合）：
 * <ul>
 *   <li>{@code empty} — 0 字节（CC pdf.ts:50-55）</li>
 *   <li>{@code too_large} — readPDF &gt; 20MB（CC apiLimits.ts:54）/ extract &gt; 100MB（CC apiLimits.ts:72）</li>
 *   <li>{@code corrupted} — 缺 {@code %PDF-} magic（CC pdf.ts:77-86）；提取路径解析/渲染失败
 *       （CC pdf.ts:247-254 stderr 匹配 damaged/corrupt/invalid 等价）</li>
 *   <li>{@code password_protected} — {@link InvalidPasswordException}（CC pdf.ts:237-245 stderr 匹配 password 等价）</li>
 *   <li>{@code unknown} — 其余异常（CC pdf.ts:104-112/291-299 errorMessage 等价）</li>
 * </ul>
 */
public final class PdfSupport {

    private static final Logger log = LoggerFactory.getLogger(PdfSupport.class);

    // ════════════════════════════════════════════════════════════════════════
    // PDF 常量 · 对齐 CC constants/apiLimits.ts
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: PDF_TARGET_RAW_SIZE (apiLimits.ts:54) = 20MB — base64 后 ~27MB 留对话余量。 */
    public static final long PDF_TARGET_RAW_SIZE = 20L * 1024 * 1024;
    /** CC original: PDF_EXTRACT_SIZE_THRESHOLD (apiLimits.ts:66) = 3MB — 超过则走页图提取（telemetry 用）。本常量作文档默认值；生效值经 {@link #getExtractSizeThreshold()}（yml 可配）。 */
    public static final long PDF_EXTRACT_SIZE_THRESHOLD = 3L * 1024 * 1024;
    /** CC original: PDF_MAX_EXTRACT_SIZE (apiLimits.ts:72) = 100MB — 提取路径最大文件尺寸。本常量作文档默认值；生效值经 {@link #getMaxExtractSize()}（yml 可配）。 */
    public static final long PDF_MAX_EXTRACT_SIZE = 100L * 1024 * 1024;

    // ════════════════════════════════════════════════════════════════════════
    // 可配置阈值 · yml nexusai.pdf.extract-size-threshold / max-extract-size → PdfSupportConfig
    // ════════════════════════════════════════════════════════════════════════

    /** 生效提取阈值（默认 3MB，对齐 CC apiLimits.ts:66）· volatile：PdfSupportConfig 启动期写入，运行期只读。 */
    private static volatile long extractSizeThreshold = PDF_EXTRACT_SIZE_THRESHOLD;
    /** 生效最大提取尺寸（默认 100MB，对齐 CC apiLimits.ts:72）· volatile：PdfSupportConfig 启动期写入，运行期只读。 */
    private static volatile long maxExtractSize = PDF_MAX_EXTRACT_SIZE;

    /** 当前生效提取阈值（可配置，默认 3MB 对齐 CC apiLimits.ts:66）。 */
    public static long getExtractSizeThreshold() {
        return extractSizeThreshold;
    }

    /** 当前生效最大提取尺寸（可配置，默认 100MB 对齐 CC apiLimits.ts:72）。 */
    public static long getMaxExtractSize() {
        return maxExtractSize;
    }

    /**
     * 配置 PDF 提取阈值（PdfSupportConfig @Value 注入 yml nexusai.pdf.*；测试经本方法重置）。
     *
     * <p>校验：{@code extractSizeThreshold ≥ 1}、{@code maxExtractSize ≥ extractSizeThreshold}，
     * 非法抛 {@link IllegalArgumentException}（fail loud，配置错值必须在启动期暴露）。
     *
     * @param extractSizeThreshold 提取阈值（B）
     * @param maxExtractSize       最大提取尺寸（B）
     */
    public static void setThresholds(long extractSizeThreshold, long maxExtractSize) {
        if (extractSizeThreshold < 1 || maxExtractSize < 1) {
            throw new IllegalArgumentException("PDF 提取阈值必须 ≥1B: extractSizeThreshold="
                + extractSizeThreshold + " maxExtractSize=" + maxExtractSize);
        }
        if (maxExtractSize < extractSizeThreshold) {
            throw new IllegalArgumentException("PDF maxExtractSize 不能小于 extractSizeThreshold: "
                + maxExtractSize + " < " + extractSizeThreshold);
        }
        PdfSupport.extractSizeThreshold = extractSizeThreshold;
        PdfSupport.maxExtractSize = maxExtractSize;
        if (log.isInfoEnabled()) {
            log.info("PdfSupport 阈值配置生效: extract={}B maxExtract={}B", extractSizeThreshold, maxExtractSize);
        }
    }
    /** CC original: PDF_MAX_PAGES_PER_READ (apiLimits.ts:77) = 20 — 单次 pages 参数最多页数。 */
    public static final int PDF_MAX_PAGES_PER_READ = 20;
    /** CC original: PDF_AT_MENTION_INLINE_THRESHOLD (apiLimits.ts:83) = 10 — 超过则禁止无 pages 全读。 */
    public static final int PDF_AT_MENTION_INLINE_THRESHOLD = 10;
    /** CC original: API_PDF_MAX_PAGES (apiLimits.ts:59) = 100 — API 侧硬限制；CC 客户端不校验（注释说明），Java 同。 */
    public static final int API_PDF_MAX_PAGES = 100;
    /** Document 块媒体类型 · CC FileReadTool.ts:1008 {@code media_type: 'application/pdf'}。 */
    public static final String PDF_MEDIA_TYPE = "application/pdf";

    // ════════════════════════════════════════════════════════════════════════
    // 结果类型 · 对齐 CC pdf.ts:14-27 PDFError / PDFResult<T>
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: PDFError.reason (pdf.ts:14-23) — 'unavailable' 保留契约，Java 恒不可达（pdfbox 进程内）。 */
    public enum ErrorReason { EMPTY, TOO_LARGE, PASSWORD_PROTECTED, CORRUPTED, UNKNOWN, UNAVAILABLE }

    /** CC original: PDFError (pdf.ts:14-23) — {reason, message}。 */
    public record PdfError(ErrorReason reason, String message) {}

    /** CC original: readPDF 成功载荷 (pdf.ts:34-42) — {type:'pdf', file:{filePath, base64, originalSize}}。 */
    public record PdfData(String filePath, String base64, long originalSize) {}

    /** CC original: PDFResult&lt;T&gt; (pdf.ts:25-27) — success/error 二分。 */
    public record PdfReadResult(boolean success, PdfData data, PdfError error) {

        public static PdfReadResult success(PdfData data) {
            return new PdfReadResult(true, data, null);
        }

        public static PdfReadResult failure(PdfError error) {
            return new PdfReadResult(false, null, error);
        }
    }

    /** CC original: PDFExtractPagesResult (pdf.ts:137-145) — {type:'parts', file:{filePath, originalSize, outputDir, count}}。 */
    public record PdfExtractData(String filePath, long originalSize, String outputDir, int count) {}

    /** CC original: extractPDFPages 返回 (pdf.ts:179-300)。 */
    public record PdfExtractResult(boolean success, PdfExtractData data, PdfError error) {

        public static PdfExtractResult success(PdfExtractData data) {
            return new PdfExtractResult(true, data, null);
        }

        public static PdfExtractResult failure(PdfError error) {
            return new PdfExtractResult(false, null, error);
        }
    }

    private PdfSupport() {
        // utility class，禁止实例化
    }

    // ════════════════════════════════════════════════════════════════════════
    // readPDF · 对齐 CC pdf.ts:34-113
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 读取 PDF 文件并 base64 编码（对齐 CC {@code readPDF}，pdf.ts:34-113）。
     *
     * <p>检查顺序固定（CC 原顺序）：
     * <ol>
     *   <li>0 字节 → {@code empty}（pdf.ts:50-55）</li>
     *   <li>&gt; 20MB → {@code too_large}（pdf.ts:60-68，base64 后 ~27MB 逼近 API 32MB 请求上限）</li>
     *   <li>前 5 字节非 {@code %PDF-} → {@code corrupted}（pdf.ts:72-86，防 HTML 改名 .pdf 进入对话历史——
     *       一旦进入，后续每次 API 调用都 400 "The PDF specified was not valid"，session 不可恢复）</li>
     *   <li>成功 → base64（pdf.ts:88）</li>
     * </ol>
     * 页数不在本方法校验（pdf.ts:90-91 注释：无法不解析就数页，API 会强制 100 页上限并报错）。
     *
     * @param filePath PDF 文件绝对路径
     * @return 成功携带 PdfData，失败携带 PdfError
     */
    public static PdfReadResult readPDF(Path filePath) {
        try {
            long originalSize = Files.size(filePath);
            if (originalSize == 0) {
                return PdfReadResult.failure(new PdfError(ErrorReason.EMPTY,
                    "PDF file is empty: " + filePath));
            }
            if (originalSize > PDF_TARGET_RAW_SIZE) {
                return PdfReadResult.failure(new PdfError(ErrorReason.TOO_LARGE,
                    "PDF file exceeds maximum allowed size of "
                        + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(PDF_TARGET_RAW_SIZE) + "."));
            }
            byte[] fileBuffer = Files.readAllBytes(filePath);
            // %PDF- magic 校验（CC pdf.ts:77-86）— 前 5 字节 ASCII
            if (fileBuffer.length < 5
                || !"%PDF-".equals(new String(fileBuffer, 0, 5, StandardCharsets.US_ASCII))) {
                return PdfReadResult.failure(new PdfError(ErrorReason.CORRUPTED,
                    "File is not a valid PDF (missing %PDF- header): " + filePath));
            }
            String base64 = Base64.getEncoder().encodeToString(fileBuffer);
            if (log.isInfoEnabled()) {
                log.info("PdfSupport: readPDF 成功 path={} size={}B base64Len={}",
                    filePath, originalSize, base64.length());
            }
            return PdfReadResult.success(new PdfData(filePath.toString(), base64, originalSize));
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("PdfSupport: readPDF 失败 path={} cause={}", filePath, e.toString());
            }
            return PdfReadResult.failure(new PdfError(ErrorReason.UNKNOWN,
                e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // getPDFPageCount · 对齐 CC pdf.ts:119-135（pdfinfo → pdfbox）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 读取 PDF 页数（对齐 CC {@code getPDFPageCount}，pdf.ts:119-135）。
     *
     * <p>CC 用 {@code pdfinfo} 外部二进制，失败/不可用 → null；Java 用 pdfbox 进程内解析，
     * 任何加载异常（含密码保护、损坏）→ null（与 pdfinfo 退出码非 0 → null 等价）。
     *
     * @param filePath PDF 文件绝对路径
     * @return 页数；无法确定 → null
     */
    public static Integer getPDFPageCount(Path filePath) {
        try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
            int count = doc.getNumberOfPages();
            if (log.isDebugEnabled()) {
                log.debug("PdfSupport: getPDFPageCount path={} pages={}", filePath, count);
            }
            return count;
        } catch (Exception e) {
            // pdfinfo 等价：无法确定页数（文件损坏/密码保护/IO 失败）→ null
            if (log.isDebugEnabled()) {
                log.debug("PdfSupport: getPDFPageCount 无法确定 path={} cause={}", filePath, e.toString());
            }
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // extractPDFPages · 对齐 CC pdf.ts:179-300（pdftoppm → pdfbox 渲染）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 把 PDF 页渲染为 JPEG 图片写入 {@code outputDir}（对齐 CC {@code extractPDFPages}，pdf.ts:179-300）。
     *
     * <p>CC 用 {@code pdftoppm -jpeg -r 100 -f first -l last} 产出 {@code page-01.jpg} 等；
     * Java 用 {@link PDFRenderer} 100 DPI 渲染 + ImageIO JPEG 写出，命名 {@code page-%02d.jpg}（1-based）。
     *
     * <p>检查/错误顺序（CC 原顺序）：
     * <ol>
     *   <li>0 字节 → {@code empty}（pdf.ts:188-193）</li>
     *   <li>&gt; 100MB → {@code too_large}（pdf.ts:195-203）</li>
     *   <li>可用性检查（pdf.ts:205-215 isPdftoppmAvailable）→ Java 恒可用（pdfbox 进程内），跳过</li>
     *   <li>渲染异常 → {@link InvalidPasswordException} → {@code password_protected}（pdf.ts:237-245 等价）；
     *       IOException → {@code corrupted}（pdf.ts:247-254 等价）；其余 → {@code unknown}（pdf.ts:256-259 等价）</li>
     *   <li>0 页产出 → {@code corrupted}（pdf.ts:267-275 等价）</li>
     * </ol>
     *
     * @param filePath  PDF 文件绝对路径
     * @param outputDir 输出目录（由调用方计算；CC 为 {@code getToolResultsDir()/pdf-{uuid}}）
     * @param firstPage 起始页（1-based，含）；null → 第 1 页（CC pdf.ts:224-226 仅 truthy 才传 -f）
     * @param lastPage  结束页（1-based，含）；null 或 {@link Integer#MAX_VALUE}（open-ended）→ 末页（CC pdf.ts:227-229）
     * @return 成功携带 PdfExtractData（count = 渲染页数），失败携带 PdfError
     */
    public static PdfExtractResult extractPDFPages(Path filePath, Path outputDir,
                                                   Integer firstPage, Integer lastPage) {
        try {
            long originalSize = Files.size(filePath);
            if (originalSize == 0) {
                return PdfExtractResult.failure(new PdfError(ErrorReason.EMPTY,
                    "PDF file is empty: " + filePath));
            }
            if (originalSize > getMaxExtractSize()) {
                return PdfExtractResult.failure(new PdfError(ErrorReason.TOO_LARGE,
                    "PDF file exceeds maximum allowed size for text extraction ("
                        + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(getMaxExtractSize()) + ")."));
            }
            // CC pdf.ts:219 mkdir(outputDir, {recursive:true})
            Files.createDirectories(outputDir);

            try (PDDocument doc = Loader.loadPDF(filePath.toFile())) {
                int total = doc.getNumberOfPages();
                int from = firstPage == null ? 1 : Math.max(1, firstPage);
                int to = lastPage == null || lastPage == Integer.MAX_VALUE
                    ? total : Math.min(total, lastPage);
                if (log.isInfoEnabled()) {
                    log.info("PdfSupport: extractPDFPages 渲染范围 path={} 页 {}-{} / {} outputDir={}",
                        filePath, from, to, total, outputDir);
                }
                PDFRenderer renderer = new PDFRenderer(doc);
                int count = 0;
                for (int p = from; p <= to; p++) {
                    // 100 DPI = pdftoppm -r 100（pdf.ts:223）
                    BufferedImage img = renderer.renderImageWithDPI(p - 1, 100f, ImageType.RGB);
                    File out = outputDir.resolve(String.format("page-%02d.jpg", p)).toFile();
                    if (!ImageIO.write(img, "jpeg", out)) {
                        throw new IOException("JPEG writer unavailable for " + out);
                    }
                    // [pdf-vision-align 内存修复] 渲染循环内立即释放大页图 raster，避免多页 BufferedImage 驻留堆内存
                    // （页图 JPEG 已落盘，内存副本不再需要）。资源核验：PDDocument 走 try-with-resources 自动
                    //   关闭（doc.close() → PDFBox 释放底层 RandomAccessRead/RandomAccessFile）；ImageIO.write 传
                    //   File 目标时内部自建并关闭 ImageOutputStream（finally 关闭）；PDFRenderer 无 close()（PDFBox
                    //   3.x，绑定 doc 生命周期，doc 关闭即释放）。readPDF 纯 Files.readAllBytes 无 PDFBox 对象。
                    img.flush();
                    count++;
                }
                if (count == 0) {
                    return PdfExtractResult.failure(new PdfError(ErrorReason.CORRUPTED,
                        "PDF rendering produced no output pages. The PDF may be invalid."));
                }
                if (log.isInfoEnabled()) {
                    log.info("PdfSupport: extractPDFPages 完成 path={} 页图={} outputDir={}",
                        filePath, count, outputDir);
                }
                return PdfExtractResult.success(new PdfExtractData(
                    filePath.toString(), originalSize, outputDir.toString(), count));
            } catch (InvalidPasswordException e) {
                // CC pdf.ts:237-245 stderr /password/i → password_protected
                if (log.isInfoEnabled()) {
                    log.info("PdfSupport: extractPDFPages 密码保护 path={}", filePath);
                }
                return PdfExtractResult.failure(new PdfError(ErrorReason.PASSWORD_PROTECTED,
                    "PDF is password-protected. Please provide an unprotected version."));
            } catch (IOException e) {
                // CC pdf.ts:247-254 stderr /damaged|corrupt|invalid/i → corrupted
                if (log.isDebugEnabled()) {
                    log.debug("PdfSupport: extractPDFPages 解析/渲染失败 path={} cause={}",
                        filePath, e.toString());
                }
                return PdfExtractResult.failure(new PdfError(ErrorReason.CORRUPTED,
                    "PDF file is corrupted or invalid."));
            } catch (RuntimeException e) {
                // CC pdf.ts:256-259 unknown: "pdftoppm failed: stderr" 等价
                if (log.isDebugEnabled()) {
                    log.debug("PdfSupport: extractPDFPages 未知失败 path={} cause={}",
                        filePath, e.toString());
                }
                return PdfExtractResult.failure(new PdfError(ErrorReason.UNKNOWN,
                    "PDF rendering failed: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        } catch (Exception e) {
            // 外层（stat / mkdir 失败）
            if (log.isDebugEnabled()) {
                log.debug("PdfSupport: extractPDFPages 外层失败 path={} cause={}", filePath, e.toString());
            }
            return PdfExtractResult.failure(new PdfError(ErrorReason.UNKNOWN,
                e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // isPDFSupported · 对齐 CC pdfUtils.ts:59-61
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 当前模型是否支持全 PDF document 块读取（对齐 CC {@code isPDFSupported}，pdfUtils.ts:59-61）。
     *
     * <p>CC 语义：唯一不支持 PDF 的是 Haiku 3（先于 PDF 支持发布的旧模型，子串匹配覆盖
     * Bedrock 前缀 / Vertex @-dates 等 provider 格式）；其余模型均支持。
     *
     * <p>Java 映射：{@code mainLoopModel} 来自 {@code ToolUseContext.getAppState().apply(null)}
     * 快照的 {@code 'mainLoopModel'} 键（Java appStateRef 语义 · LlmAgentLoop:1392 读取键；
     * 仅 skill inline override 写入，Java 默认模型不含 claude-3-haiku）；null/空白 → 视为支持
     * （CC getMainLoopModel() 恒有值且非 haiku 时 supported=true 等价）。
     *
     * @param mainLoopModel 当前主循环模型名（可 null）
     * @return true = 支持全 PDF document 块
     */
    public static boolean isPDFSupported(String mainLoopModel) {
        if (mainLoopModel == null || mainLoopModel.isBlank()) {
            return true;
        }
        return !mainLoopModel.toLowerCase().contains("claude-3-haiku");
    }

    /**
     * 当前请求模型是否支持全 PDF document 块读取（PDF 注入路由判定）· 委托
     * {@link ModelCapabilityResolver#supportsImage}（models.type ∈ {vision,multimodal} → true；
     * deepseek=chat → false）。
     *
     * <p><b>与 1 参 {@link #isPDFSupported(String)} 的语义差异（规则七显式暴露）</b>：
     * <ul>
     *   <li>1 参 = 工具说明用 CC 名字契约（仅 claude-3-haiku 子串禁用），供 ReadFileTool.prompt 文案</li>
     *   <li>3 参 = 当前请求模型能力判定（PDF 注入路由用）：有图片能力的模型才支持 PDF document 块直发，
     *       文本模型（deepseek）改页图注册 + vision_analyze 路由（避免 deepseek 400 根因）</li>
     * </ul>
     *
     * <p>⚠️ <b>决策</b>：{@code modelMapper == null}（非 Spring 单测 / 未注入）→ 回落 1 参 CC 契约
     * （{@code return isPDFSupported(modelName)}）——否则既有多模态送达测试（无 mapper 构造）
     * 会全部翻成文本模型分支，破坏 PDF document/页图送达契约。
     *
     * @param modelMapper    模型 mapper（null → 回落 1 参 CC 契约）
     * @param providerMapper 提供商 mapper（null → ModelNameResolver 按 name 兼容路径）
     * @param modelName      当前请求模型名（可 null）
     * @return 模型能力支持 → true；未知/失败 → false（保守）
     */
    public static boolean isPDFSupported(ModelMapper modelMapper, ProviderMapper providerMapper, String modelName) {
        if (modelMapper == null) {
            // 非 Spring 单测 / 未注入 → 回落 1 参 CC 名字契约（工具说明语义），保持既有多模态送达测试绿
            return isPDFSupported(modelName);
        }
        return ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, modelName);
    }
}

package com.nexusai.kb.app.parser;

import com.nexusai.application.agent.tool.impl.PdfSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PDF 解析器 · 文本层优先 + 视觉补强（P-1「pdf 用视觉模型解析」/ P-4「pdf 里有图片的直接调视觉」）。
 *
 * <p><b>为什么必须两通道</b>：电子版 PDF 的文本层最准（无模型开销、不乱码）；扫描件/图片版 PDF
 * 的文本层为空，只能靠页图过视觉模型。两者按 <b>页</b> 判定（一份 PDF 常见「前几页电子版、
 * 后几页扫描件」混排），不是整份一刀切。
 *
 * <p><b>视觉触发判据（派单书 §3.3，逐条照抄，⛔ 不自行放宽）</b>：
 * <ol>
 *   <li>该页文本 &lt; {@value #MIN_TEXT_CHARS_PER_PAGE} 字符（疑似扫描件）</li>
 *   <li>该页有内嵌图片，且该页文本 &lt; {@value #IMAGE_PAGE_TEXT_THRESHOLD} 字符（图文混排，图里才是正文）</li>
 *   <li>该页文本为 0（第 1 条的边界，单独判是为了让日志能区分「空页」与「极短页」）</li>
 * </ol>
 *
 * <p><b>为什么用 {@code PDFTextStripper} 而不是 {@code PdfSupport.readPDF}</b>：{@code readPDF}
 * 的产物是<b>整份文件的 base64</b>（供把 PDF 作为 document 块发给模型），不做文本抽取，与「取文本层」
 * 无关。页数/页渲染才复用 {@link PdfSupport#getPDFPageCount} / {@link PdfSupport#extractPDFPages}。
 *
 * <p><b>fail-soft（硬约束）</b>：视觉调用失败<b>绝不让整份解析失败</b> —— 该页退回文本层，
 * metadata 记 {@code visionFailed=true} + 原因，并打 {@code log.warn}。理由：入库的价值在「有内容」，
 * 一次视觉超时不该让用户的上传白费；失败可观测即可。
 *
 * <p><b>串行</b>：视觉调用逐页串行（派单书硬约束）—— 并发会把模型配额打爆，且失败时无法定位到页。
 */
@Component
@Order(10)
public class KbPdfParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbPdfParser.class);

    /** 单页文本低于此值 → 疑似扫描件，走视觉（派单书 §3.3 第 1 条）。 */
    static final int MIN_TEXT_CHARS_PER_PAGE = 100;
    /** 页内有图且文本低于此值 → 图里可能才是正文，走视觉（派单书 §3.3 第 2 条）。 */
    static final int IMAGE_PAGE_TEXT_THRESHOLD = 500;
    /** 页数上限（派单书硬约束；超出只解析前 200 页并记 truncated）。 */
    static final int MAX_PAGES = 200;
    /** 单文档文本上限（与 Office/文本解析器统一口径）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    /** 视觉能力（可 null → 只抽文本层；Spring 装配恒注入，单测可传 null）。 */
    private final KbVisionCaller visionCaller;

    @Autowired
    public KbPdfParser(KbVisionCaller visionCaller) {
        this.visionCaller = visionCaller;
    }

    /** 无视觉能力的构造（弱降级：仅文本层）· 供离线单测使用。 */
    KbPdfParser() {
        this(null);
    }

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null && PDF_EXTENSIONS.contains(ext)) {
            return true;
        }
        return "application/pdf".equals(KbParsers.normalizeMime(mimeType));
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName().toString();
        KbParsers.Truncation result;
        List<KbImageRef> images = new ArrayList<>();
        Map<String, String> meta = KbParsers.newMeta();

        int pageCount;
        int parsedPages;
        List<String> pageTexts;
        List<Integer> imageCountsPerPage;
        Path imageDir = KbImageSink.resolveImageDir(file, ctx);

        // ── 1. 文本层 + 内嵌图（一次开文档，避免重复解析）──
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            pageCount = doc.getNumberOfPages();
            parsedPages = Math.min(pageCount, MAX_PAGES);
            pageTexts = new ArrayList<>(parsedPages);
            imageCountsPerPage = new ArrayList<>(parsedPages);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= parsedPages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = normalizePageText(stripper.getText(doc));
                pageTexts.add(pageText);
                imageCountsPerPage.add(extractEmbeddedImages(doc, doc.getPage(p - 1), imageDir, p, images));
            }
        } catch (Exception e) {
            throw new KbParseException("PDF 文本层解析失败（文件损坏或加密）: " + fileName
                + " — " + e.getMessage(), e);
        }

        // ── 2. 逐页判定是否需要视觉补强 ──
        List<Integer> visionPages = new ArrayList<>();
        for (int i = 0; i < parsedPages; i++) {
            if (needsVision(pageTexts.get(i), imageCountsPerPage.get(i))) {
                visionPages.add(i + 1);
            }
        }
        boolean visionFailed = false;
        String visionFailureReason = null;
        int visionDone = 0;

        if (!visionPages.isEmpty()) {
            boolean visionAllowed = ctx == null || ctx.visionEnabled();
            boolean available = visionAllowed && visionCaller != null && visionCaller.isAvailable();
            if (!available) {
                visionFailureReason = visionAllowed
                    ? "视觉模型未配置（settings.multimodalModelName 缺失或 provider 不可用）"
                    : "调用方关闭了视觉补强";
                if (log.isInfoEnabled()) {
                    log.info("KbPdfParser: 需要视觉补强的页={} 但不可用（{}），仅保留文本层 file={}",
                        visionPages, visionFailureReason, fileName);
                }
                // 不是失败：文本层还在，只是没有补强。仍记原因便于排查「为什么扫描件没内容」
                meta.put("visionSkipped", "true");
                meta.put(KbParsedDocument.META_VISION_FAILURE_REASON, visionFailureReason);
            } else {
                VisionOutcome outcome = applyVision(file, imageDir, pageTexts, visionPages, ctx, images);
                visionFailed = outcome.failed();
                visionFailureReason = outcome.reason();
                visionDone = outcome.appliedPages();
            }
        }

        // ── 3. 按页拼 Markdown ──
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parsedPages; i++) {
            String body = pageTexts.get(i);
            sb.append(pageHeading(i + 1)).append("\n\n");
            if (!body.isEmpty()) {
                sb.append(body).append("\n\n");
            }
        }
        result = KbParsers.truncate(sb.toString().strip(), MAX_TEXT_CHARS);

        // ── 4. 元数据 ──
        KbParsers.fillCommonMetadata(meta, fileName, null, this);
        meta.put(KbParsedDocument.META_MIME_TYPE, PdfSupport.PDF_MEDIA_TYPE);
        meta.put(KbParsedDocument.META_PAGE_COUNT, String.valueOf(pageCount));
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(result.text().length()));
        if (pageCount > parsedPages) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
            meta.put("parsedPages", String.valueOf(parsedPages));
        }
        if (visionFailed) {
            meta.put(KbParsedDocument.META_VISION_FAILED, "true");
            meta.put(KbParsedDocument.META_VISION_FAILURE_REASON,
                visionFailureReason == null ? "视觉调用失败" : visionFailureReason);
        }
        if (visionDone > 0) {
            meta.put(KbParsedDocument.META_VISION_PAGES, String.valueOf(visionDone));
        }
        if (!images.isEmpty()) {
            meta.put("imageCount", String.valueOf(images.size()));
        }

        if (log.isInfoEnabled()) {
            log.info("KbPdfParser: 解析完成 file={} 页数={} 解析页={} 视觉页={} 视觉失败={} 图片={} chars={}",
                fileName, pageCount, parsedPages, visionDone, visionFailed, images.size(), result.text().length());
        }
        return new KbParsedDocument(result.text(), images, meta);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 视觉补强
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 视觉补强 · 连续页合并成区间渲染（{@link PdfSupport#extractPDFPages} 只支持连续区间），
     * 再按页序串行调视觉模型，结果原地写回 {@code pageTexts}。
     *
     * <p>逐页独立 fail-soft：某页失败只影响该页（保留文本层），不让整份解析失败。
     *
     * @return 汇总结果（是否发生过失败 / 失败原因 / 成功页数）
     */
    private VisionOutcome applyVision(Path file, Path imageDir, List<String> pageTexts,
                                      List<Integer> visionPages, KbParseContext ctx,
                                      List<KbImageRef> images) {
        String fileName = file.getFileName().toString();
        Path renderDir = imageDir.resolve("pages");
        int completed = 0;
        List<String> failures = new ArrayList<>();

        for (int[] range : contiguousRanges(visionPages)) {
            int from = range[0];
            int to = range[1];
            PdfSupport.PdfExtractResult extract = PdfSupport.extractPDFPages(file, renderDir, from, to);
            if (!extract.success()) {
                String reason = "第 " + from + "-" + to + " 页渲染失败: " + extract.error().message();
                failures.add(reason);
                log.warn("KbPdfParser: 页图渲染失败，保留文本层 file={} 原因={}", fileName, reason);
                continue;
            }
            for (int p = from; p <= to; p++) {
                Path jpg = renderDir.resolve(String.format("page-%02d.jpg", p));
                if (!Files.exists(jpg)) {
                    failures.add("第 " + p + " 页页图缺失");
                    log.warn("KbPdfParser: 页图缺失 file={} page={}", fileName, p);
                    continue;
                }
                String absolute = jpg.toAbsolutePath().toString();
                try {
                    String described = visionCaller.describeImage(jpg, null, ctx);
                    if (isNoTextSentinel(described)) {
                        // 纯图/空白页：视觉模型明确回了 NO_TEXT，不把哨兵写进正文
                        if (pageTexts.get(p - 1).isEmpty()) {
                            pageTexts.set(p - 1, "");
                        }
                        images.add(new KbImageRef(p, absolute, "", KbImageRef.KIND_PDF_PAGE));
                        completed++;
                        continue;
                    }
                    pageTexts.set(p - 1, described.strip());
                    images.add(new KbImageRef(p, absolute, truncateCaption(described), KbImageRef.KIND_PDF_PAGE));
                    completed++;
                } catch (Exception e) {
                    failures.add("第 " + p + " 页视觉调用失败: " + e.getMessage());
                    log.warn("KbPdfParser: 视觉调用失败，该页保留文本层 file={} page={} 原因={}",
                        fileName, p, e.getMessage());
                    images.add(KbImageRef.of(p, absolute, KbImageRef.KIND_PDF_PAGE));
                }
            }
        }
        return new VisionOutcome(completed, !failures.isEmpty(),
            failures.isEmpty() ? null : String.join("；", failures.subList(0, Math.min(failures.size(), 3))));
    }

    /** 视觉补强汇总（{@code appliedPages}=成功页数，{@code failed}=是否有失败页）。 */
    private record VisionOutcome(int appliedPages, boolean failed, String reason) {
    }

    /** 把有序页号压成连续区间（[from, to] 闭区间）。 */
    static List<int[]> contiguousRanges(List<Integer> pages) {
        List<int[]> ranges = new ArrayList<>();
        if (pages == null || pages.isEmpty()) {
            return ranges;
        }
        List<Integer> sorted = new ArrayList<>(pages);
        java.util.Collections.sort(sorted);
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i < sorted.size(); i++) {
            int cur = sorted.get(i);
            if (cur == prev + 1) {
                prev = cur;
                continue;
            }
            ranges.add(new int[]{start, prev});
            start = cur;
            prev = cur;
        }
        ranges.add(new int[]{start, prev});
        return ranges;
    }

    /** 视觉模型「本页没有可读文字」的哨兵（见 {@code KbVisionModelCaller.DEFAULT_PAGE_PROMPT}）。 */
    static boolean isNoTextSentinel(String described) {
        return described != null && "NO_TEXT".equalsIgnoreCase(described.strip());
    }

    /** 整页渲染图的 caption 只需开头一小段（完整文本已进正文）。 */
    private static String truncateCaption(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip().replace('\n', ' ');
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    // ════════════════════════════════════════════════════════════════════════
    // 判定与抽取
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 该页是否需要视觉补强（派单书 §3.3 三条判据）。
     *
     * @param pageText  该页文本层内容（已归一化，可空串）
     * @param imageCount 该页内嵌图片数
     */
    static boolean needsVision(String pageText, int imageCount) {
        int len = pageText == null ? 0 : pageText.strip().length();
        if (len == 0) {
            return true;
        }
        if (len < MIN_TEXT_CHARS_PER_PAGE) {
            return true;
        }
        return imageCount > 0 && len < IMAGE_PAGE_TEXT_THRESHOLD;
    }

    /**
     * 归一化单页文本：把 PDFTextStripper 产出的多个空行压成一个，去首尾空白。
     *
     * <p>不合并行内空白 —— 表格行的对齐靠空格保持，压掉会破坏列关系。
     */
    static String normalizePageText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replace('\r', '\n')
            .replaceAll("\n{3,}", "\n\n")
            .strip();
    }

    /**
     * 抽取该页内嵌位图并落盘，返回图片数。
     *
     * <p>遍历页资源的 XObject；表单 XObject（{@code PDFormXObject}）里的图片也要进去找（嵌套很常见）。
     * JPEG 直出（{@code PDImageXObject} 自带 {@code getSuffix()}）；其余统一解码成 PNG ——
     * JBIG2/JPX 等特殊编码解码失败时跳过该图（记 debug），不影响正文。
     *
     * @param doc      文档（图片对象绑定文档生命周期）
     * @param page     页
     * @param imageDir 输出目录
     * @param pageIndex 页号（1-based）
     * @param images   收集器（原地追加）
     * @return 本页成功抽取的图片数
     */
    private static int extractEmbeddedImages(PDDocument doc, PDPage page, Path imageDir, int pageIndex,
                                             List<KbImageRef> images) {
        PDResources resources = page == null ? null : page.getResources();
        if (resources == null) {
            return 0;
        }
        List<PDImageXObject> found = new ArrayList<>();
        collectImageXObjects(resources, found, 0);
        int saved = 0;
        for (PDImageXObject img : found) {
            try {
                String suffix = img.getSuffix() == null ? "" : img.getSuffix().toLowerCase(Locale.ROOT);
                if ("jpg".equals(suffix) || "jpeg".equals(suffix) || "png".equals(suffix)) {
                    Path out = imageDir.resolve(String.format("pdf-p%03d-img%02d.%s", pageIndex, saved + 1, suffix));
                    try (java.io.InputStream in = img.createInputStream()) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    addImageRef(images, pageIndex, out);
                    saved++;
                    continue;
                }
                BufferedImage decoded = img.getImage();
                if (decoded == null) {
                    continue;
                }
                try {
                    Path out = imageDir.resolve(String.format("pdf-p%03d-img%02d.png", pageIndex, saved + 1));
                    if (ImageIO.write(decoded, "png", out.toFile())) {
                        addImageRef(images, pageIndex, out);
                        saved++;
                    }
                } finally {
                    decoded.flush();
                }
            } catch (Exception e) {
                // 单张图失败不影响整页/整份解析（图片是补充材料）
                if (log.isDebugEnabled()) {
                    log.debug("KbPdfParser: 内嵌图抽取失败 page={} 原因={}", pageIndex, e.getMessage());
                }
            }
        }
        return saved;
    }

    private static void addImageRef(List<KbImageRef> images, int pageIndex, Path out) {
        images.add(KbImageRef.of(pageIndex, out.toAbsolutePath().toString(), KbImageRef.KIND_PDF_EMBEDDED));
    }

    /** 递归收集位图 XObject（表单 XObject 可能多层嵌套）。 */
    private static void collectImageXObjects(PDResources resources, List<PDImageXObject> out, int depth) {
        if (resources == null || depth > 8) {
            // 深度上限防病态 PDF 的循环引用 / 深层嵌套导致栈溢出
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject obj;
            try {
                obj = resources.getXObject(name);
            } catch (Exception e) {
                continue;
            }
            if (obj instanceof PDImageXObject image) {
                out.add(image);
            } else if (obj instanceof PDFormXObject form) {
                collectImageXObjects(form.getResources(), out, depth + 1);
            }
        }
    }

    /**
     * 重建索引 / 预览场景的便捷入口：只抽文本、不做视觉（{@code visionEnabled=false} 语义）。
     *
     * <p>WHY 需要它：重解析/预览只需要「這份文档有什么字」，为它付一遍视觉模型调用费不合理；
     * 且离线环境（无多模态档位）也要能跑通。
     */
    public KbParsedDocument parseTextOnly(Path file) {
        return parse(file, KbParseContext.defaults().withoutVision());
    }

    /** 页标题格式 · 单一来源（正文拼装与测试断言共用，避免测试里手抄字符串走样）。 */
    static String pageHeading(int pageNo) {
        return "## 第 " + pageNo + " 页";
    }
}

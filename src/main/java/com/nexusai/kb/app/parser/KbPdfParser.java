package com.nexusai.kb.app.parser;

import com.nexusai.application.agent.tool.impl.PdfSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 解析器 · 文本层优先 + 视觉补强（P-1「pdf 用视觉模型解析」/ P-4「pdf 里有图片的直接调视觉」）。
 *
 * <p><b>为什么必须两通道</b>：电子版 PDF 的文本层最准（无模型开销、不乱码）；扫描件/图片版 PDF
 * 的文字可能是排版乱码或低质量内嵌 OCR，只能靠页图过视觉模型。两者按 <b>页</b> 判定（一份 PDF 常见
 * 「前几页电子版、后几页扫描件」混排），不是整份一刀切。
 *
 * <p><b>视觉触发判据（对齐源站 {@code WeKnora/docreader/parser/pdf_parser.py:76-93, 193-210}）</b>：
 * <ol>
 *   <li>主信号 · 该页图片 bbox 面积之和 ÷ 页面面积 ≥ {@value #SCAN_IMAGE_AREA_RATIO}
 *       ⇒ 扫描页（电子版页面实测 ~0.0-0.05，扫描页 ~1.0+，阈值留足安全边际）</li>
 *   <li>次信号 · 该页文本 &lt; {@value #SCAN_MIN_CHARS_PER_PAGE} 字符
 *       <b>且</b> 面积比 ≥ {@value #LOW_TEXT_IMAGE_RATIO} ⇒ 扫描页（文字极少但并非空白页）</li>
 *   <li>其余（含「无文字 + 无图」的真空白页）⇒ 原生文本页：不渲染页图、不调视觉</li>
 * </ol>
 *
 * <p><b>为什么主信号是图片面积比而不是字符数</b>：扫描件常带一层低质量的内嵌 OCR 文字层
 * （几百上千字但全是乱码/错字）。按字符数判会把这种页当成「原生文本 PDF」⇒ 把乱码入库、
 * 视觉根本不触发（源站注释原话：<i>Trusting that embedded text layer is what produced
 * garbled RAG content before</i>）。面积比不受文字层质量影响：整页一张大图 ⇒ 比值接近 1。
 *
 * <h2>本轮新增 · 原生文本页的三层取舍链（严格对齐 pdf_parser.py:2330-2356）</h2>
 * <pre>
 * plain 良好 &amp;&amp; 没有过滤过 ⇒ 用 plain（不跑版面）
 * 否则                    ⇒ 跑版面（LAYOUT_ORDERING + DETECT_HEADINGS）
 * filtered（过滤发生过）   ⇒ 强制 layout，禁用 plain 回退
 * </pre>
 * 版面重建包含三件事，与源站一一对应：
 * <ul>
 *   <li><b>隐藏文字过滤</b>（{@code FILTER_HIDDEN_TEXT}，pdf_parser.py:116 / :742-800）——
 *       render-mode 3（不可见）与出 CropBox 的字形一律剔除，且<b>一旦过滤发生就绝不回退</b>
 *       （:2338-2356）。这不是「锦上添花」：不可见文字会进知识库、被召回、喂给模型 ⇒ 提示注入面。</li>
 *   <li><b>字号推标题</b>（{@code DETECT_HEADINGS}，pdf_parser.py:113 / :1132-1166）——
 *       行高 ÷ 正文中位高 ≥2.0/1.6/1.35 ⇒ #/##/###；行长 &lt;8 或 &gt;80、以句末标点结尾不升；
 *       命中行超 40% 整体放弃（字号太均匀不可信）。</li>
 *   <li><b>多栏阅读顺序</b>（{@code LAYOUT_ORDERING}，pdf_parser.py:106 / :881-1177）——
 *       按字形 x 区间找「全高净空槽」递归切列，按列优先、行次之输出；窄边条（水印/页码）剔除。</li>
 * </ul>
 *
 * <p><b>⛔ 旋转页的取舍（有意偏离）</b>：pdfium 的字形坐标在「未旋转用户空间」，PDFBox 的
 * {@code TextPosition} 在「旋转后显示空间」，两者混用会让列/标题全错。故旋转页<b>跳过版面分析</b>
 * 并打 WARN，只用纯文本 —— 算错比不算更糟。但 render-mode 3 的判定与坐标无关，旋转页仍会剔除
 * 不可见字形（安全优先，源站因坐标同源没有这条分支）。
 *
 * <p><b>为什么用 {@code PDFTextStripper} 而不是 {@code PdfSupport.readPDF}</b>：{@code readPDF}
 * 的产物是<b>整份文件的 base64</b>（供把 PDF 作为 document 块发给模型），不做文本抽取，与「取文本层」
 * 无关。页数/页渲染才复用 {@link PdfSupport#getPDFPageCount} / {@link PdfSupport#extractPDFPages}。
 *
 * <p><b>fail-soft（硬约束）</b>：视觉调用失败<b>绝不让整份解析失败</b> —— 该页退回文本层，
 * metadata 记 {@code visionFailed=true} + 原因，并打 {@code log.warn}。理由：入库的价值在「有内容」，
 * 一次视觉超时不该让用户的上传白费；失败可观测即可。
 *
 * <p><b>视觉并发（有界）</b>：页图渲染仍<b>串行</b>（{@code PDDocument}/{@code PDFRenderer} 非线程安全），
 * 识别阶段改用<b>有界固定线程池</b>（{@code nexusai.knowledge.vision.page-concurrency}，默认 4）
 * 并发调视觉模型，结果按页号升序回填 —— 并发度有界不会打爆模型配额，失败仍逐页可定位（WARN 带页号）。
 */
@Component
@Order(10)
public class KbPdfParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbPdfParser.class);

    /**
     * 图片覆盖面积比 ≥ 此值 ⇒ 判为扫描页（源站 {@code SCAN_IMAGE_AREA_RATIO}，pdf_parser.py:77-80）。
     *
     * <p>电子版页面实测 ~0.0-0.05，扫描页 ~1.0+，0.5 留足安全边际。
     */
    static final double SCAN_IMAGE_AREA_RATIO = 0.5;
    /** 单页文本低于此值 ⇒ 无可用文本层（源站 {@code SCAN_MIN_CHARS_PER_PAGE}，pdf_parser.py:81-82）。 */
    static final int SCAN_MIN_CHARS_PER_PAGE = 10;
    /**
     * 文本极少时，图片面积比达到此值才渲成图（源站 {@code _LOW_TEXT_IMAGE_RATIO}，pdf_parser.py:83-85）。
     *
     * <p>WHY：避免把「没有文字的真正空白页」（无图）也渲成图送视觉，白花钱。
     */
    static final double LOW_TEXT_IMAGE_RATIO = 0.1;
    /**
     * 页数上限<b>默认值</b>（派单书硬约束；超出只解析前 N 页并记 truncated）。
     *
     * <p>生效值经 {@link #maxPages} 注入（yml {@code nexusai.knowledge.pdf.max-pages}，缺省本常量）；
     * 本常量保留作文档默认值与离线单测的参照。
     */
    static final int MAX_PAGES = 200;
    /** 单文档文本上限（与 Office/文本解析器统一口径）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    // ════════════════════════════════════════════════════════════════════════
    // 版面重建常量 · 逐条对齐源站 pdf_parser.py:105-140
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 多栏阅读顺序开关 · 源站 env {@code DOCREADER_PDF_LAYOUT_ORDERING}（pdf_parser.py:106）。
     *
     * <p><b>为什么硬编码 true</b>：本仓没有「解析引擎配置」的读入口（{@code KbParserRegistry}
     * 只做引擎选择，不读 docreader 的 env）。派单书决策 3：硬编码 + javadoc 标注源站 env 名，
     * 后续接入配置时只需把常量换成读取。
     */
    static final boolean LAYOUT_ORDERING = true;
    /**
     * 字号推标题开关 · 源站 env {@code DOCREADER_PDF_DETECT_HEADINGS}（pdf_parser.py:113）。
     *
     * <p>⚠️ 只在<b>走版面路径</b>的页上生效（源站同一约束：字号信息只在 layout 抽取里拿到）。
     */
    static final boolean DETECT_HEADINGS = true;
    /**
     * 隐藏文字过滤开关 · 源站 env {@code DOCREADER_PDF_FILTER_HIDDEN_TEXT}（pdf_parser.py:116）。
     *
     * <p>⛔ 关掉它等于把「白底白字/render-mode 3 的提示注入文本」直接放进知识库。
     */
    static final boolean FILTER_HIDDEN_TEXT = true;
    /** 字形间距超过「行内中位字宽 × 此比例」时补一个空格（源站 {@code WORD_GAP_WIDTH_RATIO}，pdf_parser.py:109）。 */
    static final double WORD_GAP_WIDTH_RATIO = 0.4;
    /** 窄于页宽此比例的列 → 视为边条/水印（源站 {@code MARGIN_COL_WIDTH_RATIO}，pdf_parser.py:118）。 */
    static final double MARGIN_COL_WIDTH_RATIO = 0.12;
    /** 升格标题的最小行长（源站 {@code MIN_HEADING_LINE_CHARS}，pdf_parser.py:122）。 */
    static final int MIN_HEADING_LINE_CHARS = 8;
    /** 升格标题的最大行长（源站 {@code _segments_to_markdown} 里写死的 80，pdf_parser.py:1141）。 */
    static final int MAX_HEADING_LINE_CHARS = 80;
    /** 命中标题的行占比上限；超过则整体放弃（源站 pdf_parser.py:1157-1158）。 */
    static final double HEADING_MAX_HIT_FRACTION = 0.4;

    // ════════════════════════════════════════════════════════════════════════
    // 内嵌图过滤常量 · 逐条对齐源站 pdf_parser.py:92-105
    // ════════════════════════════════════════════════════════════════════════

    /** 内嵌图抽取开关 · 源站 env {@code DOCREADER_PDF_EXTRACT_EMBEDDED_IMAGES}（pdf_parser.py:95）。 */
    static final boolean EXTRACT_EMBEDDED_IMAGES = true;
    /** 内嵌图保留的最小像素边长（宽 <b>和</b> 高都要满足）· 源站 {@code EMBED_MIN_PIXELS}（pdf_parser.py:97）。 */
    static final int EMBED_MIN_PIXELS = 80;
    /** 内嵌图保留的最小页面占比 · 源站 {@code EMBED_MIN_AREA_RATIO}（pdf_parser.py:99）。 */
    static final double EMBED_MIN_AREA_RATIO = 0.01;
    /** 同一张图出现在 ≥ 此比例的文本页上 ⇒ 判水印/页眉 logo · 源站 {@code EMBED_REPEAT_PAGE_FRAC}（pdf_parser.py:102）。 */
    static final double EMBED_REPEAT_PAGE_FRAC = 0.5;
    /** 单文档内嵌图保留上限 · 源站 {@code EMBED_MAX_IMAGES}（pdf_parser.py:105）。 */
    static final int EMBED_MAX_IMAGES = 50;

    // ════════════════════════════════════════════════════════════════════════
    // 正则 · 逐条对齐源站（pdf_parser.py 顶部 + :1246-1335）
    // ════════════════════════════════════════════════════════════════════════

    /** 源站 {@code _NUMERIC_ROW_RE}：短标签 + 末尾一个数值（pdf_parser.py:1216-1219）。 */
    private static final Pattern NUMERIC_ROW = Pattern.compile(
        "^([^\\d\\n]+?)\\s+([+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?%?)$");
    /** 源站 {@code _NUMERIC_VALUE_LINE_RE}：整行只有一个数值（pdf_parser.py:935-937）。 */
    private static final Pattern NUMERIC_VALUE_LINE = Pattern.compile(
        "^[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?%?$");
    /** 源站 {@code re.fullmatch(r"[\\d\\s./()%+-]+", t)}：纯数字/单位符号行（pdf_parser.py:937-940）。 */
    private static final Pattern NUMERIC_ALLOWED = Pattern.compile("[\\d\\s./()%+-]+");
    /**
     * 源站 {@code numbers = re.compile(r"[+−-]?\\d+(?:[.,:/]\\d+)*(?:[%‰])?")}（pdf_parser.py:1304）。
     *
     * <p>⚠️ {@code −} 是 U+2212 减号（不是 ASCII '-'），源站两者都认。
     */
    private static final Pattern NUMBERS = Pattern.compile("[+\\u2212-]?\\d+(?:[.,:/]\\d+)*(?:[%‰])?");
    /** 源站 {@code re.fullmatch(r"[\\s.,;:!?…·\\-–—]+", ln)}：纯标点行（pdf_parser.py:1189-1191）。 */
    private static final Pattern PUNCT_ONLY = Pattern.compile("[\\s.,;:!?…·\\-–—]+");
    /** 源站 {@code re.search(r"\\[\\w+,\\s", plain)}：学术引用形如 {@code [Smith, 2020]}（pdf_parser.py:1265）。 */
    private static final Pattern BRACKET_CITE = Pattern.compile("\\[\\w+,\\s");
    /** 源站 {@code re.search(r"\\[\\w+\\s+\\w+\\s+\\d", layout)}：layout 把引用拆散的样子（pdf_parser.py:1332）。 */
    private static final Pattern BRACKET_CITE_BROKEN = Pattern.compile("\\[\\w+\\s+\\w+\\s+\\d");

    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    /** 视觉能力（可 null → 只抽文本层；Spring 装配恒注入，单测可传 null）。 */
    private final KbVisionCaller visionCaller;

    /**
     * 视觉逐页识别的并发度（有界固定线程池大小）。
     *
     * <p>Spring 环境从 {@code nexusai.knowledge.vision.page-concurrency} 读取（缺省 4）；
     * 离线单测不经 Spring ⇒ 字段初值 4 生效。渲染阶段仍是串行，见 {@link #applyVision}。
     */
    @Value("${nexusai.knowledge.vision.page-concurrency:4}")
    private int pageConcurrency = 4;

    /**
     * 单文档解析页数上限（超出部分不解析，只在 metadata 记 {@code truncated}）。
     *
     * <p>Spring 环境从 {@code nexusai.knowledge.pdf.max-pages} 读取（缺省 {@value #MAX_PAGES}）；
     * 离线单测不经 Spring ⇒ 字段初值 = {@link #MAX_PAGES}，也可经 3 参构造器显式指定。
     */
    @Value("${nexusai.knowledge.pdf.max-pages:200}")
    private int maxPages = MAX_PAGES;

    /**
     * 页图渲染的长边上限（px）· 源站 docreader {@code pdf_render_max_edge}（config.py:136-142）。
     *
     * <p>Spring 环境从 {@code nexusai.knowledge.pdf.render-max-edge} 读取（缺省 2000）；
     * 离线单测吃字段初值 2000 —— 与 {@link PdfSupport#PDF_RENDER_MAX_EDGE} 同一口径。
     */
    @Value("${nexusai.knowledge.pdf.render-max-edge:2000}")
    private int renderMaxEdge = PdfSupport.PDF_RENDER_MAX_EDGE;

    @Autowired
    public KbPdfParser(KbVisionCaller visionCaller) {
        this.visionCaller = visionCaller;
    }

    /**
     * 可注入并发度的构造器 · 供离线单测显式指定线程池大小。
     *
     * <p>为什么不靠 {@code @Value} 字段注入：离线单测不经 Spring，{@code @Value} 不生效，
     * 只能吃字段初值 4 —— 想验证「并发度真的决定同时在跑的人数」就需要能显式指定。
     *
     * @param visionCaller    视觉能力（可 null）
     * @param pageConcurrency 识别阶段并发度（&lt;1 时按 1 处理）
     */
    KbPdfParser(KbVisionCaller visionCaller, int pageConcurrency) {
        this(visionCaller, pageConcurrency, MAX_PAGES);
    }

    /**
     * 可注入并发度 + 页数上限的构造器 · 供离线单测显式指定（如「maxPages=3 时第 4 页不处理」）。
     *
     * @param visionCaller    视觉能力（可 null）
     * @param pageConcurrency 识别阶段并发度（&lt;1 时按 1 处理）
     * @param maxPages        页数上限（&lt;=0 时回落 {@link #MAX_PAGES}）
     */
    KbPdfParser(KbVisionCaller visionCaller, int pageConcurrency, int maxPages) {
        this.visionCaller = visionCaller;
        this.pageConcurrency = Math.max(1, pageConcurrency);
        this.maxPages = maxPages > 0 ? maxPages : MAX_PAGES;
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

    /**
     * 能力声明 · 直接复用 {@link #supports} 用的扩展名常量（⛔ 不另抄一份，防止两处漂移）。
     *
     * <p>仅 {@code pdf}：{@code supports} 里的 MIME 分支（{@code application/pdf}）无法用扩展名表达，
     * 故不在此列出。
     */
    @Override
    public Set<String> supportedExtensions() {
        return PDF_EXTENSIONS;
    }

    /** 展示名 · 前端「解析引擎」下拉用。 */
    @Override
    public String displayName() {
        return "内置 PDF 解析";
    }

    /** 引擎族名别名 · 源站内置 PDF 引擎为 {@code builtin}（另收 {@code pdf} 便于按类型点名）。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("builtin", "pdf");
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
        List<Boolean> isTextPage = new ArrayList<>();
        List<Double> areaRatioPerPage = new ArrayList<>();
        /**
         * 每页是否存在图片绘制事件（与 {@code pageTexts} 同下标）。
         *
         * <p>用途只有一个：正文拼装时判断「无文字页要不要留标题」—— 有图的页即便没有文本层
         * 也是真实内容页（图片引用会挂到这一页），不能当空白页跳过。
         */
        List<Boolean> hasImagePerPage = new ArrayList<>();
        /** 每页「分类时刻」的纯文本字符数（页眉页脚去重之前）—— 判定用它，日志也用它，避免两处口径漂移。 */
        List<Integer> plainLengths = new ArrayList<>();
        List<Integer> visionPages = new ArrayList<>();
        /**
         * 每页的版面几何（与 {@code pageTexts} 同下标）· 只用于给 source block 算 bbox，
         * 不参与正文。旋转页与无字形的页存 {@link PageGeometry#NONE}（坐标不可用 ⇒ 不伪造）。
         */
        List<PageGeometry> pageGeometries = new ArrayList<>();
        List<ImageCandidate> imageCandidates = new ArrayList<>();
        Map<String, Integer> imageDropReasons = new HashMap<>();
        Path imageDir = KbImageSink.resolveImageDir(file, ctx);

        // ── 1. 逐页：文本层（+字形）+ 图片绘制事件（面积比 + bbox）──
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            pageCount = doc.getNumberOfPages();
            parsedPages = Math.min(pageCount, maxPages);
            pageTexts = new ArrayList<>(parsedPages);
            areaRatioPerPage = new ArrayList<>(parsedPages);

            for (int p = 1; p <= parsedPages; p++) {
                PDPage page = doc.getPage(p - 1);

                // ① 纯文本 + 字形（一次内容流遍历，纯文本与今天逐字节一致）
                String plain;
                PageGlyphData glyphs;
                try {
                    // 旋转页的 TextPosition 在「旋转后显示空间」⇒ 出页框判定不可信，只剔不可见字形
                    boolean rotated = page.getRotation() % 360 != 0;
                    HiddenAwareStripper stripper = new HiddenAwareStripper(false, !rotated);
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    plain = normalizePageText(stripper.getText(doc));
                    glyphs = stripper.snapshot(page);
                } catch (IOException e) {
                    throw new KbParseException("PDF 文本层解析失败（文件损坏或加密）: " + fileName
                        + " 第 " + p + " 页 — " + e.getMessage(), e);
                }

                // ② 图片绘制事件（面积比是扫描页判定的主信号）
                PageGraphics graphics = analyzePageGraphics(page);
                areaRatioPerPage.add(graphics.areaRatio());
                hasImagePerPage.add(!graphics.draws().isEmpty());
                plainLengths.add(plain.length());
                boolean scanned = needsVision(plain, graphics.areaRatio());
                isTextPage.add(!scanned);
                if (scanned) {
                    // 分类必须在页眉页脚去重之前（源站 Pass1 顺序）⇒ 这里就登记视觉页
                    visionPages.add(p);
                }

                // ③ 选文本：扫描页不做版面重建（内容靠视觉），文本只作兜底
                String body;
                if (scanned) {
                    body = plain;
                    log.info("[PDF] 页面 {} 判定=扫描页（图片面积比={} 纯文本字符={}）⇒ 不做版面重建，"
                            + "文本层仅作视觉失败兜底 file={}",
                        p, fmt(graphics.areaRatio()), plain.length(), fileName);
                } else if (page.getRotation() % 360 != 0) {
                    body = rotatedPageText(doc, page, p, fileName, plain, glyphs);
                } else {
                    body = selectTextLayer(p, fileName, plain, glyphs);
                }
                pageTexts.add(body);

                // ④ 内嵌图候选（只有文本页才抽 —— 源站 text_indices，pdf_parser.py:1897-1902）
                if (EXTRACT_EMBEDDED_IMAGES && !scanned && !graphics.draws().isEmpty()) {
                    collectImageCandidates(graphics, p, pageAreaOf(page), fileName,
                        imageCandidates, imageDropReasons);
                }

                // ⑤ 版面几何（source block 的 bbox 来源）
                //   ⛔ 旋转页的 TextPosition 在「旋转后显示空间」，与 CropBox 不同源 ⇒ 坐标不可信，
                //      不采信几何（各段 locator 只带页码、不带区域）。
                pageGeometries.add(page.getRotation() % 360 == 0
                    ? pageGeometry(glyphs)
                    : PageGeometry.NONE);
            }

            // ── 2. 内嵌图：跨页重复/尺寸/上限筛选后落盘 ──
            List<EmbeddedImage> keptEmbedded = selectEmbeddedImages(imageCandidates,
                countTextPages(isTextPage), imageDropReasons);
            saveEmbeddedImages(keptEmbedded, imageDir, images, imageDropReasons);

            // ── 3. 页眉页脚去重（源站 _strip_repeating_lines，pdf_parser.py:1997-2034）──
            //   ⚠️ 源站顺序是「Pass1 分类（用 plain 长度）→ texts = _strip_repeating_lines(...)」，
            //   即分类发生在去重之前。故 visionPages 已在上面逐页循环里定好，此处只改正文。
            pageTexts = stripRepeatingLines(pageTexts, isTextPage, fileName);
            for (int i = 0; i < parsedPages; i++) {
                // 口径：chars 是「分类时刻的纯文本长度」（去重前）；去重后长度单独打，避免误读
                log.info("[PDF] 页判定 file={} page={} 分类用chars={} 去重后chars={} 图片面积比={} 需要视觉={}",
                    fileName, i + 1, plainLengths.get(i), pageTexts.get(i).length(),
                    fmt(areaRatioPerPage.get(i)), visionPages.contains(i + 1));
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
                    log.info("KbPdfParser: 需要视觉补强的页={} 但不可用（{}），仅保留文本层 file={}",
                        visionPages, visionFailureReason, fileName);
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

            // ── 5. 按页拼 Markdown（空页不产标题，对齐源站 pdf_parser.py 丢弃空文本块）──
            //   「空页」= 无文字（分类时刻纯文本为空）且 未被判为扫描页 且 无图片。
            //   ⚠️ 不要只看 body：页眉页脚去重会把「只有重复页眉」的文本页 body 清空，那仍是有文字的页。
            //   ⭐ 本轮只**旁路记账** source block：正文拼接语句与顺序逐字不变，块只是同一批段落的坐标。
            StringBuilder sb = new StringBuilder();
            List<PendingBlock> pendingBlocks = new ArrayList<>();
            int headingPages = 0;
            boolean anyBodyText = false;
            List<Integer> skippedEmptyPages = new ArrayList<>();
            for (int i = 0; i < parsedPages; i++) {
                int pageNo = i + 1;
                boolean hasText = plainLengths.get(i) > 0;
                boolean scanned = visionPages.contains(pageNo);
                boolean hasImage = Boolean.TRUE.equals(hasImagePerPage.get(i));
                if (!hasText && !scanned && !hasImage) {
                    // 空白页：不产标题、不产正文 —— 否则正文会只剩 "## 第 N 页"，冒充「有内容」
                    //   ⛔ 也不产 source block（没有正文可定位）
                    skippedEmptyPages.add(pageNo);
                    continue;
                }
                String body = pageTexts.get(i);
                sb.append(pageHeading(pageNo)).append("\n\n");
                headingPages++;
                if (!body.isEmpty()) {
                    anyBodyText = true;
                    int bodyStartChar = sb.length();
                    sb.append(body).append("\n\n");
                    collectPageBlocks(pendingBlocks, pageNo, body, bodyStartChar, pageGeometries.get(i));
                }
            }
            if (!skippedEmptyPages.isEmpty()) {
                log.info("KbPdfParser: 空白页跳过 file={} 总页数={} 解析页={} 产出标题页数={} 跳过空页数={} 空页页号={}",
                    fileName, pageCount, parsedPages, headingPages, skippedEmptyPages.size(),
                    abbreviatePages(skippedEmptyPages));
            } else {
                log.info("KbPdfParser: 空白页跳过 file={} 总页数={} 解析页={} 产出标题页数={} 跳过空页数=0",
                    fileName, pageCount, parsedPages, headingPages);
            }
            String rawText = sb.toString();
            // strip 会吃掉前导空白：块区间按「未 strip 的正文」记的 char 下标，必须整体左移同样的码点数
            int leadingCodePoints = rawText.codePointCount(0, rawText.length())
                - rawText.stripLeading().codePointCount(0, rawText.stripLeading().length());
            result = KbParsers.truncate(rawText.strip(), MAX_TEXT_CHARS);
            List<KbSourceBlock> sourceBlocks =
                toSourceBlocks(pendingBlocks, rawText, leadingCodePoints, result.text());
            if (log.isInfoEnabled()) {
                int totalCp = result.text().codePointCount(0, result.text().length());
                int coveredCp = coveredCodePoints(sourceBlocks, totalCp);
                int blockPages = coveredPages(sourceBlocks);
                long withBbox = sourceBlocks.stream()
                    .filter(b -> b.locator().bbox() != null).count();
                log.info("KbPdfParser: 源位置块 file={} 总块数={} 覆盖页数={} 覆盖码点={}/{} 码点覆盖率={} 带bbox块数={}",
                    fileName, sourceBlocks.size(), blockPages, coveredCp, totalCp,
                    totalCp == 0 ? "0.0%" : String.format(Locale.ROOT, "%.1f%%", 100.0 * coveredCp / totalCp),
                    withBbox);
            }
            if (sourceBlocks.isEmpty() && anyBodyText) {
                log.warn("[溯源] PDF 正文非空但未产出 source block file={} 解析页={} chars={}",
                    fileName, parsedPages, result.text().length());
            }

            // ── 6. 元数据 ──
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
            int embeddedKept = keptEmbedded.size();
            int embeddedDropped = imageDropReasons.values().stream().mapToInt(Integer::intValue).sum();
            if (!images.isEmpty()) {
                meta.put("imageCount", String.valueOf(images.size()));
            }
            if (embeddedKept > 0 || embeddedDropped > 0) {
                meta.put("embeddedImages", String.valueOf(embeddedKept));
                meta.put("embeddedImagesDropped", String.valueOf(embeddedDropped));
            }

            // 图片面积比是扫描件判定的主信号 ⇒ 汇总里必须可见，便于排查「为什么这页没过视觉」
            double maxAreaRatio = 0.0;
            int imageDominatedPages = 0;
            for (double ratio : areaRatioPerPage) {
                maxAreaRatio = Math.max(maxAreaRatio, ratio);
                if (ratio >= SCAN_IMAGE_AREA_RATIO) {
                    imageDominatedPages++;
                }
            }
            meta.put(KbParsedDocument.META_SOURCE_BLOCKS, String.valueOf(sourceBlocks.size()));
            log.info("KbPdfParser: 解析完成 file={} 页数={} 解析页={} 视觉页={} 视觉失败={} 图片={}"
                    + "（内嵌图保留={} 丢弃={} 原因={} 图占半页以上的页={} 最大面积比={}） chars={} sourceBlocks={}",
                fileName, pageCount, parsedPages, visionDone, visionFailed, images.size(),
                embeddedKept, embeddedDropped, imageDropReasons, imageDominatedPages,
                fmt(maxAreaRatio), result.text().length(), sourceBlocks.size());

            return new KbParsedDocument(result.text(), images, meta, sourceBlocks);
        } catch (IOException e) {
            throw new KbParseException("PDF 解析失败（文件损坏或加密）: " + fileName
                + " — " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 视觉补强
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 扫描页专用视觉提示词 · <b>只对本解析器的页图使用</b>（其他路径的 prompt 不变）。
     *
     * <p>WHY 不能用「描述这张图」类通用提示：扫描页的页图就是整页文档，我们需要的不是对图片的
     * 描述，而是<b>逐块转写</b>出的正文（入库/召回吃的是文字，图片说明对 RAG 无用）。
     * 通用描述会让模型输出「这是一张黑白扫描的合同页面……」这类元叙述，既占 token 又污染正文。
     *
     * <p>语义对齐源站 docreader 的页级转写意图：逐块转写文字、保留表格/公式/版面顺序为 Markdown、
     * 不描述图片内容本身。
     */
    static final String SCAN_PAGE_PROMPT = "这是一份 PDF 文档的扫描页图片。请逐块转写页面上的全部文字，"
        + "保留表格、公式与版面顺序，输出为 Markdown；不要描述图片本身的内容或外观。";

    /**
     * 视觉补强 · <b>渲染阶段保持串行</b>（连续页合并成区间；{@link PdfSupport#extractPDFPages} 内部用
     * {@code PDDocument}/{@code PDFRenderer}，非线程安全），<b>识别阶段</b>改用有界固定线程池
     * （{@code nexusai.knowledge.vision.page-concurrency}，默认 4）并发调视觉模型，
     * 结果按页号升序回填 {@code pageTexts} 与 {@code images}。
     *
     * <p><b>为什么回填前必须排序</b>：并发完成顺序不确定，但 {@code pageTexts} 的下标与
     * {@code images} 的追加顺序必须与串行版<b>完全一致</b>（页号升序），否则正文页序会随线程调度漂移。
     *
     * <p>逐页独立 fail-soft：某页失败只影响该页（保留文本层），不让整份解析失败。
     * 线程池在 {@code finally} 里 {@code shutdown}（含异常/中断路径）。
     *
     * @return 汇总结果（是否发生过失败 / 失败原因 / 成功页数）
     */
    private VisionOutcome applyVision(Path file, Path imageDir, List<String> pageTexts,
                                      List<Integer> visionPages, KbParseContext ctx,
                                      List<KbImageRef> images) {
        String fileName = file.getFileName().toString();
        Path renderDir = imageDir.resolve("pages");
        // 预处理产物目录：只给「送视觉」的那份用，图片引用（KbImageRef）仍指原页图 ⇒ 用完即删。
        Path preDir = renderDir.resolve("pre");
        List<Path> preFiles = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<PageTask> tasks = new ArrayList<>();

        // ── ① 渲染（串行）：页图落盘之后才进并发识别 ──
        for (int[] range : contiguousRanges(visionPages)) {
            int from = range[0];
            int to = range[1];
            PdfSupport.PdfExtractResult extract =
                PdfSupport.extractPDFPages(file, renderDir, from, to, renderMaxEdge);
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
                // 扫描页预处理（白边裁剪 + 小角度纠偏）· 只换「送视觉」的路径：
                //   PageTask.jpg 是发给视觉模型的图，absolutePath 仍是原页图（图片引用不能被预处理路径污染）。
                //   失败 / 开关关闭时 preprocess 原样返回 jpg ⇒ 走原图，不额外判空。
                Path toSend = KbImagePreprocessor.preprocess(jpg, preDir);
                if (!toSend.equals(jpg)) {
                    preFiles.add(toSend);
                }
                tasks.add(new PageTask(p, toSend, jpg.toAbsolutePath().toString()));
            }
        }

        int completed = 0;
        int total = tasks.size();
        int threads = Math.max(1, Math.min(pageConcurrency, total));
        long startedAt = System.nanoTime();
        if (total > 0) {
            // ── ② 识别（并发，有界）：渲染已完成，按页提交；回填仍按页号升序 ──
            //   ⛔ 不要在这里用 completedFuture（那是串行，会让并发用例恒为 1）
            final java.util.concurrent.atomic.AtomicInteger threadNo =
                new java.util.concurrent.atomic.AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "kb-pdf-vision-" + threadNo.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            List<Future<PageVisionResult>> futures = new ArrayList<>(total);
            try {
                for (PageTask task : tasks) {
                    futures.add(pool.submit(() -> describePage(task, ctx)));
                }
            } finally {
                pool.shutdown();
            }

            // ── ② 回填（单线程、页号升序）：顺序与下标同串行版一致 ──
            //   finally 里删预处理产物：它只服务本次视觉调用，图片引用记的仍是原页图。
            try {
                List<PageVisionResult> results = new ArrayList<>(futures.size());
                for (int i = 0; i < futures.size(); i++) {
                    PageTask task = tasks.get(i);
                    try {
                        results.add(futures.get(i).get());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        results.add(PageVisionResult.failed(task.page(), task.absolutePath(), "视觉调用被中断"));
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        results.add(PageVisionResult.failed(task.page(), task.absolutePath(),
                            String.valueOf(cause.getMessage())));
                    }
                }
                results.sort(Comparator.comparingInt(PageVisionResult::page));

                for (PageVisionResult r : results) {
                    if (r.failed()) {
                        failures.add("第 " + r.page() + " 页视觉调用失败: " + r.reason());
                        log.warn("KbPdfParser: 视觉调用失败，该页保留文本层 file={} page={} 原因={}",
                            fileName, r.page(), r.reason());
                        images.add(KbImageRef.of(r.page(), r.imagePath(), KbImageRef.KIND_PDF_PAGE));
                        continue;
                    }
                    if (r.noText()) {
                        // 纯图/空白页：视觉模型明确回了 NO_TEXT，不把哨兵写进正文
                        if (pageTexts.get(r.page() - 1).isEmpty()) {
                            pageTexts.set(r.page() - 1, "");
                        }
                        images.add(new KbImageRef(r.page(), r.imagePath(), "", KbImageRef.KIND_PDF_PAGE));
                    } else {
                        pageTexts.set(r.page() - 1, r.text());
                        images.add(new KbImageRef(r.page(), r.imagePath(), r.caption(),
                            KbImageRef.KIND_PDF_PAGE));
                    }
                    completed++;
                }
            } finally {
                for (Path pre : preFiles) {
                    try {
                        Files.deleteIfExists(pre);
                    } catch (Exception e) {
                        log.debug("KbPdfParser: 删除预处理临时图失败 path={} 原因={}", pre, e.getMessage());
                    }
                }
                try {
                    // 只在目录已空时才会成功；非空（别的解析同时在用 / 删除失败）就留在那儿，不算错
                    Files.deleteIfExists(preDir);
                } catch (java.nio.file.DirectoryNotEmptyException e) {
                    log.debug("KbPdfParser: 预处理目录非空，保留 path={}", preDir);
                } catch (Exception e) {
                    log.debug("KbPdfParser: 清理预处理目录失败 path={} 原因={}", preDir, e.getMessage());
                }
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info("KbPdfParser: 视觉识别汇总 file={} 并发度={} 视觉页={} 待识别页={} 成功={} 失败页={} 耗时={}ms",
            fileName, threads, visionPages.size(), total, completed, total - completed, elapsedMs);
        return new VisionOutcome(completed, !failures.isEmpty(),
            failures.isEmpty() ? null : String.join("；", failures.subList(0, Math.min(failures.size(), 3))));
    }

    /** 待识别的一页：页号 + 页图路径（渲染产物）· 只读快照，供并发任务使用。 */
    private record PageTask(int page, Path jpg, String absolutePath) {
    }

    /**
     * 一页的视觉识别结果（并发任务产物）。
     *
     * <p>{@code reason} 非空 ⇒ 该页失败（保留文本层）；{@code noText} ⇒ 模型回了 NO_TEXT 哨兵。
     */
    private record PageVisionResult(int page, String imagePath, String text, String caption,
                                    boolean noText, String reason) {

        static PageVisionResult failed(int page, String imagePath, String reason) {
            return new PageVisionResult(page, imagePath, null, null, false, reason);
        }

        boolean failed() {
            return reason != null;
        }
    }

    /**
     * 单页识别 · 在池线程里执行；<b>异常一律转成失败结果</b>（fail-soft，不让整份解析失败）。
     *
     * <p>提示词固定用 {@link #SCAN_PAGE_PROMPT}（逐块转写为 Markdown），不是「描述图片」——
     * 见该常量 javadoc。改前传的是 {@code null}（由调用方实现决定默认提示），会把页图当普通图片描述。
     */
    private PageVisionResult describePage(PageTask task, KbParseContext ctx) {
        try {
            String described = visionCaller.describeImage(task.jpg(), SCAN_PAGE_PROMPT, ctx);
            if (isNoTextSentinel(described)) {
                return new PageVisionResult(task.page(), task.absolutePath(), "", "", true, null);
            }
            return new PageVisionResult(task.page(), task.absolutePath(), described.strip(),
                truncateCaption(described), false, null);
        } catch (Exception e) {
            return PageVisionResult.failed(task.page(), task.absolutePath(), e.getMessage());
        }
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
     * 该页是否需要视觉补强 · 对齐源站 {@code _classify_page}（pdf_parser.py:193-203）。
     *
     * <p><b>为什么主信号是图片面积比而不是字符数</b>：扫描件常带一层低质量的内嵌 OCR
     * 文字层（几百字但全是乱码），按字符数会把它判成「原生文本」⇒ 乱码入库、视觉不触发
     * （源站注释：<i>Trusting that embedded text layer is what produced garbled RAG content before</i>）。
     *
     * <p><b>空页（无文字 + 无图）不在此返回 true</b>：源站 {@code _classify_page} 对它返回
     * {@code "text"}，随后 {@code _route_locked} 只把非空文本块放进正文（pdf_parser.py:2325-2353,
     * 2439-2451），既不入库也不渲图 —— 本方法按同一行为返回 false，避免为真正空白页白付视觉费用。
     *
     * @param pageText        该页文本层内容（已归一化，可空串）
     * @param imageAreaRatio  该页图片 bbox 面积之和 ÷ 页面面积（可 &gt; 1.0，重叠图片所致）
     */
    static boolean needsVision(String pageText, double imageAreaRatio) {
        int len = pageText == null ? 0 : pageText.strip().length();
        // 主信号：图片盖住半页以上 ⇒ 扫描页（哪怕带文字层）
        if (imageAreaRatio >= SCAN_IMAGE_AREA_RATIO) {
            return true;
        }
        // 次信号：文字极少 + 有点图 ⇒ 扫描页
        return len < SCAN_MIN_CHARS_PER_PAGE && imageAreaRatio >= LOW_TEXT_IMAGE_RATIO;
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

    // ════════════════════════════════════════════════════════════════════════
    // 三层取舍链（对齐 pdf_parser.py:2330-2356）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 原生文本页的文本来源选择 · 逐分支对齐源站 {@code _route_locked}（pdf_parser.py:2338-2356）。
     *
     * <pre>
     * ① plain 良好 &amp;&amp; 没过滤过  ⇒ plain
     * ② 否则跑 layout；若发生过过滤 ⇒ layout（绝不复用未过滤的 plain）
     * ③ layout 非空 &amp;&amp; !shouldPreferPlain(plain, layout) ⇒ layout
     * ④ 其余 ⇒ plain
     * </pre>
     *
     * @param pageNo 页号（1-based，仅日志）
     * @param plain  纯文本（PDFTextStripper 原始输出，未过滤）
     * @param glyphs 该页字形（已过滤隐藏/页外）
     */
    private static String selectTextLayer(int pageNo, String fileName, String plain, PageGlyphData glyphs) {
        if (!LAYOUT_ORDERING) {
            log.info("[PDF] 页面 {} 版面重建开关关闭 ⇒ 走 plain file={}", pageNo, fileName);
            return plain;
        }
        boolean plainWellFormed = plainIsWellFormed(plain);
        boolean filtered = glyphs.filtered();
        if (plainWellFormed && !filtered) {
            log.info("[PDF] 页面 {} 文本层质量=良好（plain 已达良好且未发生过滤）⇒ 走 plain file={}",
                pageNo, fileName);
            return plain;
        }
        LayoutResult layout = layoutMarkdown(glyphs, pageNo, fileName);
        if (filtered) {
            // 源站注释（pdf_parser.py:2343-2347）：plain 层可能含隐藏或页外文字，
            // 一旦版面路径证明过滤发生过，就绝不再把未过滤文本作为回退。
            log.warn("[PDF] 页面 {} 发生过隐藏文字过滤 ⇒ 强制 layout，禁用 plain 回退"
                    + "（不可见={} 出页框={}）file={}",
                pageNo, glyphs.invisibleCount(), glyphs.offPageCount(), fileName);
            return layout.text();
        }
        if (!layout.text().isBlank()
            && !shouldPreferPlain(plain, layout.text(), layout.tableHint())) {
            log.info("[PDF] 页面 {} 文本层质量=一般（plain 未达良好）⇒ 走 layout（表格信号={}）file={}",
                pageNo, layout.tableHint(), fileName);
            return layout.text();
        }
        log.info("[PDF] 页面 {} 文本层质量=一般但 layout 更差 ⇒ 回退 plain file={}", pageNo, fileName);
        return plain;
    }

    /**
     * 旋转页的文本来源 · <b>有意偏离源站</b>（源站 pdfium 的坐标本来就在未旋转空间，无此取舍）。
     *
     * <p>PDFBox 的 {@code TextPosition} 坐标是「旋转后显示空间」，与 CropBox 的未旋转用户空间
     * 不可混用 —— 混用会让列切分与标题识别全错，故跳过版面重建（派单书决策 2）。
     *
     * <p>但两件事必须补：
     * <ol>
     *   <li><b>不可见字形（render-mode 3）判定与坐标无关</b> ⇒ 旋转页仍要剔除，否则
     *       「白底不可见提示注入文本」会在旋转页上漏过去（安全优先）。</li>
     *   <li><b>阅读顺序</b>：跳过自建分栏后，只剩 PDFBox 自己的位置排序可以救顺序。
     *       实测 90° 旋转页在 {@code sortByPosition=false} 下会把 {@code Rotated} 拆成
     *       {@code R} + {@code otated}（两个文字块乱序），{@code sortByPosition=true} 才正常。
     *       ⇒ 旋转页统一走一次「按位置排序」的抽取。</li>
     * </ol>
     *
     * <p>额外开销只在旋转页上（少见），代价是每页多一次内容流遍历。
     */
    private static String rotatedPageText(PDDocument doc, PDPage page, int pageNo, String fileName,
                                          String plain, PageGlyphData glyphs) {
        int rotation = page.getRotation() % 360;
        boolean dropInvisible = FILTER_HIDDEN_TEXT && glyphs.invisibleCount() > 0;
        log.warn("[PDF] 页面 {} 旋转 {}° ⇒ 跳过版面分析（列/标题识别不准确），仅用纯文本"
                + "（按位置排序，剔除不可见字形={}，不可见数={}）file={}",
            pageNo, rotation, dropInvisible, glyphs.invisibleCount(), fileName);
        try {
            HiddenAwareStripper stripper = new HiddenAwareStripper(dropInvisible, false);
            // 旋转页不自建分栏 ⇒ 用 PDFBox 的位置排序，否则文字块会按内容流顺序错位
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNo);
            stripper.setEndPage(pageNo);
            return normalizePageText(stripper.getText(doc));
        } catch (IOException e) {
            log.warn("[PDF] 页面 {} 旋转页二次抽取失败，退回未过滤纯文本 file={} 原因={}",
                pageNo, fileName, e.getMessage());
            return plain;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 字形采集（隐藏文字过滤的落点）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 一个字形 · 版面重建的最小单元。
     *
     * <p>坐标为 <b>0 基用户空间</b>：原点 = CropBox 左下角，y 向上（与 PDF 规范一致，
     * 也与源站 pdfium 的 {@code get_charbox} 同向，便于逐条对照源站算法）。
     *
     * @param x0        左边界
     * @param y0        底边（≈ 基线）
     * @param x1        右边界
     * @param y1        顶边（= 基线 + 字高，字高取 PDFBox 的 maxHeight）
     * @param ch        Unicode 文本（可能多字符，如连字 "fi"）
     * @param seq       内容流顺序（调试用）
     * @param invisible 渲染模式是否为 NEITHER（=3，源站 FPDF_TEXTRENDERMODE_INVISIBLE）
     */
    record Glyph(float x0, float y0, float x1, float y1, String ch, int seq, boolean invisible) {

        float width() {
            return x1 - x0;
        }

        float height() {
            return y1 - y0;
        }

        float cx() {
            return (x0 + x1) / 2f;
        }

        float cy() {
            return (y0 + y1) / 2f;
        }

        boolean blank() {
            return ch == null || ch.isEmpty() || ch.isBlank();
        }
    }

    /**
     * 一页的字形采集结果（已过滤）。
     *
     * @param glyphs          保留下来的字形（按内容流顺序）
     * @param width           页宽（CropBox）
     * @param height          页高（CropBox）
     * @param sourceCount     原始字形数（不含 \r\n）
     * @param invisibleCount  被剔除的不可见字形数
     * @param offPageCount    被剔除的页外字形数
     */
    private record PageGlyphData(List<Glyph> glyphs, double width, double height,
                                 int sourceCount, int invisibleCount, int offPageCount) {

        /** 是否发生过过滤（源站 {@code _page_chars} 的 {@code filtered_count > 0}）。 */
        boolean filtered() {
            return invisibleCount > 0 || offPageCount > 0;
        }
    }

    /**
     * 采集字形、同时照旧输出纯文本的 {@link PDFTextStripper}。
     *
     * <p><b>为什么覆写 {@code processTextPosition} 就够</b>（PDFBox 3.0.5 实测）：它在
     * {@code LegacyPDFStreamEngine.showGlyph} 的同步调用链里被调，此时
     * {@code getGraphicsState().getTextState().getRenderingMode()} 仍然有效 ——
     * 能直接拿到 render mode 与现成的 x/y/width/height，不必自己写 {@code PDFStreamEngine}
     * 子类重算 TRM。
     *
     * <p>{@code dropInvisible=true} 时把不可见字形挡在父类之前 ⇒ 产出的纯文本<b>不含</b>
     * 隐藏文字（旋转页的「过滤版纯文本」用它）。
     */
    private static final class HiddenAwareStripper extends PDFTextStripper {

        private final boolean dropInvisible;
        /** 是否启用「出页框」剔除。旋转页的 TextPosition 在旋转后显示空间 ⇒ 页外判定不可信，置 false。 */
        private final boolean filterOffPage;
        private final List<Glyph> captured = new ArrayList<>();
        private int seq;
        private float pageHeight;

        HiddenAwareStripper(boolean dropInvisible, boolean filterOffPage) throws IOException {
            super();
            this.dropInvisible = dropInvisible;
            this.filterOffPage = filterOffPage;
            // 版面重建自己做排序与分栏 ⇒ 关掉 PDFBox 的位置排序，保持「内容流顺序」与源站 plain 一致
            setSortByPosition(false);
        }

        @Override
        protected void startPage(PDPage page) {
            captured.clear();
            seq = 0;
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            pageHeight = box == null ? 0f : box.getHeight();
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            boolean invisible = isInvisible(text);
            if (dropInvisible && invisible) {
                // ⛔ 不交给父类：该字形不会出现在纯文本里
                return;
            }
            capture(text, invisible);
            super.processTextPosition(text);
        }

        /**
         * 渲染模式是否为「既不着色也不描边」（源站 {@code FPDF_TEXTRENDERMODE_INVISIBLE} = 3）。
         *
         * <p>只认 {@link RenderingMode#NEITHER}（int 3）—— 严格照源站，不把 7（NEITHER_CLIP）也算进来。
         */
        private boolean isInvisible(TextPosition text) {
            try {
                PDTextState ts = getGraphicsState() == null ? null : getGraphicsState().getTextState();
                return ts != null && ts.getRenderingMode() == RenderingMode.NEITHER;
            } catch (Exception e) {
                // 判不出来就当可见：宁可多留文字，也不能让整页解析失败
                return false;
            }
        }

        private void capture(TextPosition text, boolean invisible) {
            float h = Math.abs(text.getHeightDir());
            if (h <= 0) {
                h = Math.abs(text.getFontSize()) * 0.6f;
            }
            // getY() 是「距 CropBox 顶边的基线距离」（实测 842 高、y=700 的文字 ⇒ getY()=142）
            // ⇒ 用户空间基线 = 页高 - getY()。字形顶 = 基线 + 字高。
            // ⚠️ 仅对 0/180° 成立；90/270° 时 PDFBox 用的是 pageWidth，故旋转页不采信坐标。
            float baseline = pageHeight - text.getY();
            float x0 = text.getX();
            captured.add(new Glyph(x0, baseline, x0 + text.getWidthDirAdj(), baseline + h,
                text.getUnicode() == null ? "" : text.getUnicode(), seq++, invisible));
        }

        /** 该页字形的过滤结果（隐藏/页外剔除）。 */
        PageGlyphData snapshot(PDPage page) {
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            double w = box == null ? 0 : box.getWidth();
            double h = box == null ? 0 : box.getHeight();
            return collectPageGlyphs(captured, w, h, filterOffPage);
        }
    }

    /**
     * 字形过滤 · 对齐源站 {@code _page_chars}（pdf_parser.py:742-800）。
     *
     * <p>剔除规则（顺序与源站一致）：
     * <ol>
     *   <li>{@code \r} / {@code \n} 字形直接跳过（不算过滤 —— 源站在 {@code source_count++} 之前就 continue）</li>
     *   <li>render mode 3（不可见）⇒ 剔除，计 {@code invisible}</li>
     *   <li>字形框完全落在 CropBox 外 ⇒ 剔除，计 {@code offPage}</li>
     * </ol>
     *
     * <p>页内坐标为 0 基（原点 = CropBox 左下角）⇒ CropBox 即 {@code [0,width] × [0,height]}。
     *
     * @param filterOffPage 是否做「出页框」剔除。旋转页的坐标在旋转后显示空间，
     *                      与 CropBox 不同源 ⇒ 传 false 只做不可见剔除（不猜）。
     */
    static PageGlyphData collectPageGlyphs(List<Glyph> all, double width, double height,
                                           boolean filterOffPage) {
        List<Glyph> kept = new ArrayList<>(all.size());
        int sourceCount = 0;
        int invisible = 0;
        int offPage = 0;
        for (Glyph g : all) {
            if ("\r".equals(g.ch()) || "\n".equals(g.ch())) {
                continue;
            }
            sourceCount++;
            if (FILTER_HIDDEN_TEXT) {
                if (g.invisible()) {
                    invisible++;
                    continue;
                }
                if (filterOffPage) {
                    boolean outside = g.x1() < 0 || g.x0() > width || g.y1() < 0 || g.y0() > height;
                    if (outside) {
                        offPage++;
                        continue;
                    }
                }
            }
            kept.add(g);
        }
        return new PageGlyphData(kept, width, height, sourceCount, invisible, offPage);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 版面重建：分栏 → 行组装 → 标题升格（pdf_parser.py:881-1177）
    // ════════════════════════════════════════════════════════════════════════

    /** 版面重建结果。 */
    private record LayoutResult(String text, boolean tableHint) {
    }

    /**
     * 版面重建 · 对应源站 {@code _extract_layout_text} 的几何部分
     * （{@code _chars_to_layout_markdown}，pdf_parser.py:1169-1177）。
     *
     * <p>列优先、行次之：每个列各自组行 → 各行升格标题 → 列块按顺序拼接。
     */
    private static LayoutResult layoutMarkdown(PageGlyphData data, int pageNo, String fileName) {
        List<Glyph> chars = data.glyphs();
        if (chars.isEmpty()) {
            log.info("[PDF] 版面重建 page={} 无可用字形（过滤后为空）⇒ 输出空文本 file={}", pageNo, fileName);
            return new LayoutResult("", false);
        }
        double scale = medianHeight(chars);
        if (scale <= 0) {
            scale = 1.0;
        }
        double width = data.width();
        List<List<Glyph>> rawColumns = splitColumns(chars, scale, width, 0);
        boolean tableHint = findNumericTableColumns(chars, scale, width, rawColumns) != null;
        List<List<Glyph>> columns = filterReadingColumns(chars, scale, width, rawColumns);

        List<Double> columnSpans = new ArrayList<>(columns.size());
        for (List<Glyph> col : columns) {
            columnSpans.add((double) columnSpan(col));
        }
        log.info("[PDF] 多栏检测 page={} 候选列数={} 采用={} 各列宽度={} 表格信号={} file={}",
            pageNo, rawColumns.size(), columns.size(), fmtList(columnSpans), tableHint, fileName);

        StringBuilder sb = new StringBuilder();
        int promotedTotal = 0;
        int lineTotal = 0;
        boolean abandonedAny = false;
        List<Double> bodyMedians = new ArrayList<>();
        List<String> abandonReasons = new ArrayList<>();

        for (int ci = 0; ci < columns.size(); ci++) {
            List<Line> lines = mergeOrphanPunctuationLines(groupLines(columns.get(ci)));
            HeadingOutcome outcome = segmentsToMarkdown(lines);
            if (!outcome.text().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(outcome.text());
            }
            promotedTotal += outcome.promoted();
            lineTotal += outcome.lineCount();
            abandonedAny |= outcome.abandoned();
            if (outcome.bodyMedian() > 0) {
                bodyMedians.add(outcome.bodyMedian());
            }
            if (outcome.abandoned()) {
                abandonReasons.add("列" + (ci + 1) + "命中行占比过高");
            }
            if (columns.size() > 1) {
                log.info("[PDF] 标题识别 page={} 列={} 行数={} 正文中位高={} 升格行数={} 命中比={} 放弃={} file={}",
                    pageNo, ci + 1, outcome.lineCount(), fmt(outcome.bodyMedian()), outcome.promoted(),
                    fmt(outcome.hitFraction()), outcome.abandoned(), fileName);
            }
        }
        double bodyMedian = medianOf(bodyMedians);
        double hit = lineTotal == 0 ? 0.0 : (double) promotedTotal / lineTotal;
        log.info("[PDF] 标题识别 page={} 正文中位高={} 升格行数={} 命中比={} 放弃={} 放弃原因={} file={}",
            pageNo, fmt(bodyMedian), promotedTotal, fmt(hit), abandonedAny,
            abandonReasons.isEmpty() ? "-" : String.join("；", abandonReasons), fileName);
        return new LayoutResult(sb.toString().strip(), tableHint);
    }

    /** 一行（视觉行）：文本 + 行高中位数 + 字形 + bbox。 */
    record Line(String text, double h, List<Glyph> chars,
                        double x0, double y0, double x1, double y1) {

        double cy() {
            return (y0 + y1) / 2;
        }
    }

    /**
     * 一个字形的列表 → 视觉行 · 对齐源站 {@code _group_lines}（pdf_parser.py:1103-1129）。
     *
     * <p>按 y 中心从上到下分组，容差 = 0.5 × 行高中位数；同组内按 x 排序后拼接文本。
     */
    static List<Line> groupLines(List<Glyph> chars) {
        if (chars == null || chars.isEmpty()) {
            return List.of();
        }
        List<Double> heights = new ArrayList<>();
        for (Glyph g : chars) {
            if (g.height() > 0) {
                heights.add((double) g.height());
            }
        }
        double medH = heights.isEmpty() ? 1.0 : medianOf(heights);

        List<Glyph> ordered = new ArrayList<>(chars);
        // 上→下：用户空间 y 向上 ⇒ 中心 y 降序
        ordered.sort((a, b) -> Double.compare(b.cy(), a.cy()));
        List<List<Glyph>> groups = new ArrayList<>();
        List<Glyph> cur = new ArrayList<>();
        Double ref = null;
        for (Glyph g : ordered) {
            double yc = g.cy();
            if (ref == null || Math.abs(yc - ref) <= 0.5 * medH) {
                cur.add(g);
                if (ref == null) {
                    ref = yc;
                }
            } else {
                groups.add(cur);
                cur = new ArrayList<>();
                cur.add(g);
                ref = yc;
            }
        }
        if (!cur.isEmpty()) {
            groups.add(cur);
        }

        List<Line> out = new ArrayList<>(groups.size());
        for (List<Glyph> group : groups) {
            List<Glyph> sorted = new ArrayList<>(group);
            sorted.sort((a, b) -> Float.compare(a.x0(), b.x0()));
            String text = joinLineGlyphs(sorted);
            if (text.isEmpty()) {
                continue;
            }
            List<Double> hs = new ArrayList<>();
            for (Glyph g : sorted) {
                if (g.height() > 0) {
                    hs.add((double) g.height());
                }
            }
            double lineH = hs.isEmpty() ? medH : medianOf(hs);
            out.add(new Line(text, lineH, sorted, charsBboxX0(sorted), charsBboxY0(sorted),
                charsBboxX1(sorted), charsBboxY1(sorted)));
        }
        return out;
    }

    /**
     * 行内字形拼接（相邻字形间隙超过「中位字宽 × {@value #WORD_GAP_WIDTH_RATIO}」补空格）
     * · 对齐源站 {@code _join_line_glyphs}（pdf_parser.py:1060-1101）。
     *
     * <p>数字对例外：右对齐表格里的 {@code 124} 常被逐位定位，间距略大但不该断开
     * （源站注释：{@code Treating that gap as a word boundary turns 124 into 1 24}）。
     */
    static String joinLineGlyphs(List<Glyph> line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        List<Double> widths = new ArrayList<>();
        for (Glyph g : line) {
            if (g.width() > 0) {
                widths.add((double) g.width());
            }
        }
        double medW = widths.isEmpty() ? 1.0 : medianOf(widths);
        double gapThreshold = medW * WORD_GAP_WIDTH_RATIO;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.size(); i++) {
            Glyph cur = line.get(i);
            String ch = cur.ch();
            if (i == 0) {
                sb.append(ch);
                continue;
            }
            Glyph prev = line.get(i - 1);
            boolean curBlank = isAllWhitespace(ch);
            boolean prevBlank = isAllWhitespace(prev.ch());
            if (curBlank || prevBlank) {
                if (!curBlank || (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ')) {
                    sb.append(ch);
                }
                continue;
            }
            double gap = cur.x0() - prev.x1();
            boolean numericPair = isNumericChar(prev.ch()) && isNumericChar(ch)
                && (hasDigit(prev.ch()) || hasDigit(ch));
            if (gap > gapThreshold && !(numericPair && gap <= medW * 1.25)) {
                sb.append(' ');
            }
            sb.append(ch);
        }
        return sb.toString().strip();
    }

    /**
     * 只含标点的行并到上一行 · 对齐源站 {@code _merge_orphan_punctuation_lines}（pdf_parser.py:1033-1059）。
     *
     * <p>很多 PDF 把图表标签/脚注里的 {@code .} 放在略不同的基线上，按 y 分组会把它切成单独一行。
     */
    static List<Line> mergeOrphanPunctuationLines(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Line> merged = new ArrayList<>(lines.size());
        for (Line ln : lines) {
            String t = ln.text().strip();
            if (!merged.isEmpty() && !t.isEmpty() && codePointLength(t) <= 4 && isPunctOnly(t)) {
                String suffix = t.replaceAll("\\s+", "");
                Line prev = merged.get(merged.size() - 1);
                String prevText = prev.text();
                String newText;
                if (!suffix.isEmpty() && !prevText.isEmpty()
                    && !prevText.endsWith(" ") && !prevText.endsWith("-")) {
                    newText = prevText + suffix;
                } else {
                    newText = (prevText + " " + t).strip();
                }
                merged.set(merged.size() - 1, new Line(newText, prev.h(), prev.chars(),
                    prev.x0(), prev.y0(), prev.x1(), prev.y1()));
                continue;
            }
            merged.add(ln);
        }
        return merged;
    }

    /** 标题升格结果（{@code promoted}=升格行数，{@code abandoned}=命中过多整体放弃）。 */
    record HeadingOutcome(String text, int promoted, int lineCount,
                                  double bodyMedian, double hitFraction, boolean abandoned) {
    }

    /**
     * 行 → Markdown（大字号行升格标题）· 对齐源站 {@code _segments_to_markdown}（pdf_parser.py:1132-1166）。
     *
     * <p>规则：{@code 行高/正文中位高} ≥2.0⇒{@code #}、≥1.6⇒{@code ##}、≥1.35⇒{@code ###}；
     * 行长 &lt;{@value #MIN_HEADING_LINE_CHARS} 或 &gt;{@value #MAX_HEADING_LINE_CHARS} 不升；
     * 以句末标点结尾不升；命中行超 {@value #HEADING_MAX_HIT_FRACTION} 整体放弃（字号太均匀不可信）。
     */
    static HeadingOutcome segmentsToMarkdown(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return new HeadingOutcome("", 0, 0, 0.0, 0.0, false);
        }
        List<Double> hs = new ArrayList<>(lines.size());
        for (Line ln : lines) {
            hs.add(ln.h());
        }
        double body = medianOf(hs);

        int[] levels = new int[lines.size()];
        int promoted = 0;
        if (DETECT_HEADINGS && body > 0) {
            for (int i = 0; i < lines.size(); i++) {
                levels[i] = headingLevel(lines.get(i), body);
                if (levels[i] > 0) {
                    promoted++;
                }
            }
        }
        boolean abandoned = false;
        int cap = Math.max(1, (int) (HEADING_MAX_HIT_FRACTION * lines.size()));
        if (promoted > cap) {
            // 字号太均匀/太噪 ⇒ 全部不升（源站 pdf_parser.py:1157-1158）
            for (int i = 0; i < levels.length; i++) {
                levels[i] = 0;
            }
            abandoned = true;
            promoted = 0;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            int lv = levels[i];
            if (lv > 0) {
                sb.append("#".repeat(lv)).append(' ').append(lines.get(i).text());
            } else {
                sb.append(lines.get(i).text());
            }
        }
        double hit = (double) promoted / lines.size();
        return new HeadingOutcome(sb.toString(), promoted, lines.size(), body, hit, abandoned);
    }

    /** 单行升格级别（0 = 不升）。 */
    private static int headingLevel(Line ln, double body) {
        String txt = ln.text();
        int len = codePointLength(txt);
        if (!DETECT_HEADINGS || body <= 0
            || len > MAX_HEADING_LINE_CHARS || len < MIN_HEADING_LINE_CHARS) {
            return 0;
        }
        if (endsWithSentencePunct(txt)) {
            return 0;
        }
        double ratio = ln.h() / body;
        if (ratio >= 2.0) {
            return 1;
        }
        if (ratio >= 1.6) {
            return 2;
        }
        if (ratio >= 1.35) {
            return 3;
        }
        return 0;
    }

    /** 源站 {@code txt[-1:] in ".。!！?？,，;；:："}。 */
    private static boolean endsWithSentencePunct(String txt) {
        if (txt.isEmpty()) {
            return false;
        }
        char c = txt.charAt(txt.length() - 1);
        return ".。!！?？,，;；:：".indexOf(c) >= 0;
    }

    // ── 分栏 ────────────────────────────────────────────────────────────────

    /**
     * 递归按「全高净空槽」切列 · 对齐源站 {@code _split_columns} / {@code _find_split}
     * （pdf_parser.py:861-878）。
     *
     * <p>净空槽 = 没有任何字形区间跨过的 x 区间（真正的分栏空白），最小宽度
     * {@code max(行高中位 × 2.5, 页宽 × 0.04)}。
     */
    static List<List<Glyph>> splitColumns(List<Glyph> chars, double scale, double width, int depth) {
        List<List<Glyph>> single = new ArrayList<>(1);
        single.add(chars);
        if (chars == null || chars.size() <= 1 || depth > 10) {
            return single;
        }
        double minGap = Math.max(scale * 2.5, width * 0.04);
        Double cut = findSplitX(chars, minGap);
        if (cut == null) {
            return single;
        }
        List<Glyph> left = new ArrayList<>();
        List<Glyph> right = new ArrayList<>();
        for (Glyph g : chars) {
            if (g.cx() < cut) {
                left.add(g);
            } else {
                right.add(g);
            }
        }
        if (left.isEmpty() || right.isEmpty()) {
            return single;
        }
        List<List<Glyph>> out = new ArrayList<>();
        out.addAll(splitColumns(left, scale, width, depth + 1));
        out.addAll(splitColumns(right, scale, width, depth + 1));
        return out;
    }

    /** 最宽的净空槽中点（无可用槽返回 null）· 对齐源站 {@code _find_split(items, 'x', min_gap)}。 */
    static Double findSplitX(List<Glyph> items, double minGap) {
        if (items == null || items.size() < 2) {
            return null;
        }
        List<Glyph> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Float.compare(a.x0(), b.x0()));
        float curEnd = sorted.get(0).x1();
        double bestGap = 0.0;
        Double bestCut = null;
        for (int i = 1; i < sorted.size(); i++) {
            Glyph g = sorted.get(i);
            double gap = g.x0() - curEnd;
            if (gap >= minGap && gap > bestGap) {
                bestGap = gap;
                bestCut = curEnd + gap / 2.0;
            }
            if (g.x1() > curEnd) {
                curEnd = g.x1();
            }
        }
        return bestCut;
    }

    /** 列宽（x 跨度）。 */
    static double columnSpan(List<Glyph> chars) {
        if (chars == null || chars.isEmpty()) {
            return 0.0;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (Glyph g : chars) {
            min = Math.min(min, g.x0());
            max = Math.max(max, g.x1());
        }
        return max - min;
    }

    /**
     * 剔除边条列 · 对齐源站 {@code _filter_reading_columns}（pdf_parser.py:906-931）。
     *
     * <p>先看有没有「窄数值列 + 对齐标签列」的表格形，命中就把整页合成一个流
     * （否则 {@code Aster 124} 会被拆成「标签块 + 数值块」两段）；否则逐列剔除边条。
     */
    static List<List<Glyph>> filterReadingColumns(List<Glyph> chars, double scale, double width,
                                                  List<List<Glyph>> cols) {
        if (chars == null || chars.isEmpty()) {
            List<List<Glyph>> empty = new ArrayList<>(1);
            empty.add(chars == null ? List.of() : chars);
            return empty;
        }
        List<List<Glyph>> columns = cols != null ? cols : splitColumns(chars, scale, width, 0);

        List<List<Glyph>> tablePair = findNumericTableColumns(chars, scale, width, columns);
        if (tablePair != null) {
            List<Glyph> valueCol = tablePair.get(0);
            List<Glyph> labelCol = tablePair.get(1);
            List<Glyph> flattened = new ArrayList<>();
            boolean any = false;
            for (List<Glyph> col : columns) {
                boolean keep = !isArtifactColumn(col, width) || col == valueCol || col == labelCol;
                if (keep) {
                    flattened.addAll(col);
                    any = true;
                }
            }
            if (any) {
                List<List<Glyph>> out = new ArrayList<>(1);
                out.add(flattened);
                return out;
            }
            List<List<Glyph>> whole = new ArrayList<>(1);
            whole.add(chars);
            return whole;
        }

        List<List<Glyph>> kept = new ArrayList<>();
        for (List<Glyph> col : columns) {
            if (!isArtifactColumn(col, width)) {
                kept.add(col);
            }
        }
        if (!kept.isEmpty()) {
            return kept;
        }
        if (columns.size() > 1) {
            List<Glyph> widest = columns.get(0);
            for (List<Glyph> col : columns) {
                if (columnSpan(col) > columnSpan(widest)) {
                    widest = col;
                }
            }
            List<List<Glyph>> only = new ArrayList<>(1);
            only.add(widest);
            return only;
        }
        return columns;
    }

    /**
     * 是否边条/水印列 · 对齐源站 {@code _is_artifact_column}（pdf_parser.py:881-904）。
     *
     * <p>两种形态：① 窄 + 多数行只有 1-2 个字（页边标签）；② 竖排文字（y 跨度远超 x 跨度）。
     */
    static boolean isArtifactColumn(List<Glyph> chars, double width) {
        if (chars == null || chars.isEmpty() || width <= 0) {
            return true;
        }
        double span = columnSpan(chars);
        if (span <= 0) {
            return true;
        }
        List<Line> lines = groupLines(chars);
        double singleFrac = singleLineFraction(lines);
        boolean narrow = span / width < MARGIN_COL_WIDTH_RATIO;
        if (narrow && singleFrac >= 0.45) {
            return true;
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Glyph g : chars) {
            minY = Math.min(minY, g.cy());
            maxY = Math.max(maxY, g.cy());
        }
        double ySpan = maxY - minY;
        return ySpan > span * 3.5 && chars.size() >= 8 && singleFrac >= 0.35;
    }

    /** 「只有 1-2 个字」的行占比 · 对齐源站 {@code _column_single_line_fraction}。 */
    static double singleLineFraction(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0.0;
        }
        int single = 0;
        for (Line ln : lines) {
            if (codePointLength(ln.text()) <= 2) {
                single++;
            }
        }
        return (double) single / lines.size();
    }

    /**
     * 找「窄数值列 + 对齐标签列」· 对齐源站 {@code _find_numeric_table_columns}（pdf_parser.py:950-1025）。
     *
     * <p>用途有两个：① 给 {@code _should_prefer_plain} 的 {@code allow_structured_rows} 提供信号；
     * ② 命中时把整页合成一个流，保住「标签 ↔ 数值」的行对应关系。
     *
     * <p>约束（照抄源站，不加严）：数值列 x 跨度 ≤ 页宽 22%、行数 ≥2、数值行占比 ≥40%、
     * 且列里得有含字母的文本行（排除竖排数字水印）；标签列的表头基线要对齐，
     * 数值行对齐数 ≥ {@code max(2, ceil(n/2))}（只有 1 个数值行时要求 1）。
     *
     * @return {@code [valueCol, labelCol]}；未命中返回 null
     */
    static List<List<Glyph>> findNumericTableColumns(List<Glyph> chars, double scale, double width,
                                                     List<List<Glyph>> cols) {
        if (chars == null || chars.isEmpty() || width <= 0) {
            return null;
        }
        List<List<Glyph>> columns = cols != null ? cols : splitColumns(chars, scale, width, 0);
        if (columns.size() < 2) {
            return null;
        }
        double tolerance = Math.max(scale * 0.75, 2.0);

        for (List<Glyph> valueCol : columns) {
            double spanRatio = columnSpan(valueCol) / width;
            if (spanRatio > 0.22) {
                continue;
            }
            List<Line> valueLines = groupLines(valueCol);
            if (valueLines.size() < 2) {
                continue;
            }
            List<Line> numericLines = new ArrayList<>();
            for (Line ln : valueLines) {
                if (isNumericValueLine(ln.text())) {
                    numericLines.add(ln);
                }
            }
            if (numericLines.isEmpty() || (double) numericLines.size() / valueLines.size() < 0.4) {
                continue;
            }
            List<Line> textLines = new ArrayList<>();
            for (Line ln : valueLines) {
                if (!isNumericValueLine(ln.text()) && hasAlpha(ln.text())) {
                    textLines.add(ln);
                }
            }
            if (textLines.isEmpty()) {
                continue;
            }
            List<Double> valueYs = new ArrayList<>();
            for (Line ln : numericLines) {
                valueYs.add(ln.cy());
            }
            for (List<Glyph> labelCol : columns) {
                if (labelCol == valueCol) {
                    continue;
                }
                List<Line> labelLines = groupLines(labelCol);
                List<Double> labelYs = new ArrayList<>();
                List<Double> labelTextYs = new ArrayList<>();
                for (Line ln : labelLines) {
                    labelYs.add(ln.cy());
                    if (hasAlpha(ln.text())) {
                        labelTextYs.add(ln.cy());
                    }
                }
                boolean headerAligned = false;
                for (Line tl : textLines) {
                    for (double otherY : labelTextYs) {
                        if (Math.abs(tl.cy() - otherY) <= tolerance) {
                            headerAligned = true;
                            break;
                        }
                    }
                    if (headerAligned) {
                        break;
                    }
                }
                if (!headerAligned) {
                    continue;
                }
                int aligned = 0;
                for (double y : valueYs) {
                    for (double otherY : labelYs) {
                        if (Math.abs(y - otherY) <= tolerance) {
                            aligned++;
                            break;
                        }
                    }
                }
                int required = valueYs.size() == 1 ? 1 : Math.max(2, (valueYs.size() + 1) / 2);
                if (aligned >= required) {
                    List<List<Glyph>> pair = new ArrayList<>(2);
                    pair.add(valueCol);
                    pair.add(labelCol);
                    return pair;
                }
            }
        }
        return null;
    }

    /** 整行只有一个数值（源站 {@code _is_numeric_value_line}，pdf_parser.py:933-943）。 */
    static boolean isNumericValueLine(String text) {
        String t = text == null ? "" : text.strip();
        if (NUMERIC_VALUE_LINE.matcher(t).matches()) {
            return true;
        }
        return NUMERIC_ALLOWED.matcher(t).matches() && hasDigit(t);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 择优：plain 是否良好 / layout 是否比 plain 差（pdf_parser.py:1246-1335）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * plain 是否已经是「可用的词与标点」· 对齐源站 {@code _plain_is_well_formed}（pdf_parser.py:1246-1266）。
     *
     * <p>WHY：学术 PDF / 目录本身文本层很好，跑几何版面常把引用与单词破坏掉；
     * 只有「粘连、缺标点」的劣质文本层才值得上 geometry。
     */
    static boolean plainIsWellFormed(String plain) {
        String p = plain == null ? "" : plain.strip();
        if (p.isEmpty()) {
            return false;
        }
        if (BRACKET_CITE.matcher(p).find()) {
            return true;
        }
        if (countOccurrences(p, " . . ") >= 2) {
            return true;
        }
        String[] words = p.split("\\s+");
        // split 在首部为空白时会产生空串；源站 re.findall(r"\S+") 不会
        List<String> nonEmpty = new ArrayList<>(words.length);
        for (String w : words) {
            if (!w.isEmpty()) {
                nonEmpty.add(w);
            }
        }
        if (nonEmpty.size() < 30) {
            return false;
        }
        double total = 0;
        for (String w : nonEmpty) {
            total += codePointLength(w);
        }
        return total / nonEmpty.size() >= 5.0;
    }

    /**
     * layout 是否比 plain 差（是则用 plain）· 对齐源站 {@code _should_prefer_plain}
     * （pdf_parser.py:1268-1335）。
     *
     * @param allowStructuredRows 只有「几何识别出窄数值表格列」时才允许用「数值行成组」这条判据，
     *                            否则真实双栏页会仅因为含数字就被 plain 流替代（源站注释）
     */
    static boolean shouldPreferPlain(String plain, String layout, boolean allowStructuredRows) {
        String lay = layout == null ? "" : layout.strip();
        String pl = plain == null ? "" : plain.strip();
        if (lay.isEmpty()) {
            return true;
        }
        if (pl.isEmpty()) {
            return false;
        }

        // 几何重建绝不能改变数值证据（1.5 -> 1 5、-5 -> 5）；多重集比较允许合法的列重排
        if (!numberMultiset(pl).equals(numberMultiset(lay))) {
            return true;
        }

        if (allowStructuredRows) {
            List<String[]> plainRows = structuredNumericRows(pl);
            if (!plainRows.isEmpty()) {
                List<String[]> layoutRows = structuredNumericRows(lay);
                Map<String, Integer> plainValues = new HashMap<>();
                for (String[] row : plainRows) {
                    plainValues.merge(normalizeNumericValue(row[1]), 1, Integer::sum);
                }
                Map<String, Integer> layoutValues = new HashMap<>();
                for (String[] row : layoutRows) {
                    layoutValues.merge(normalizeNumericValue(row[1]), 1, Integer::sum);
                }
                int missing = 0;
                for (Map.Entry<String, Integer> e : plainValues.entrySet()) {
                    int have = layoutValues.getOrDefault(e.getKey(), 0);
                    if (have < e.getValue()) {
                        missing += e.getValue() - have;
                    }
                }
                if (missing > 0) {
                    return true;
                }
                if (plainRows.size() >= layoutRows.size()) {
                    return true;
                }
            }
        }

        int[] stats = layoutLineStats(lay);
        int n = stats[0];
        if (n == 0) {
            return true;
        }
        if ((double) stats[1] / n >= 0.18 || (double) stats[2] / n >= 0.12) {
            return true;
        }
        double garbled = layoutGarbledLineFraction(lay);
        if (garbled >= 0.20 && layoutGarbledLineFraction(pl) < 0.08) {
            return true;
        }
        if (BRACKET_CITE.matcher(pl).find() && BRACKET_CITE_BROKEN.matcher(lay).find()) {
            return true;
        }
        // 标题/首句应当能在 layout 里存活
        for (String rawLine : pl.split("\\R")) {
            String probe = rawLine.strip();
            if (codePointLength(probe) < 24) {
                continue;
            }
            StringBuilder alnum = new StringBuilder();
            for (int i = 0; i < probe.length() && alnum.length() < 16; i++) {
                char c = probe.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    alnum.append(c);
                }
            }
            if (alnum.length() < 12) {
                continue;
            }
            StringBuilder layoutAlnum = new StringBuilder();
            for (int i = 0; i < lay.length(); i++) {
                char c = lay.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    layoutAlnum.append(c);
                }
            }
            return layoutAlnum.indexOf(alnum.toString()) < 0;
        }
        return false;
    }

    /**
     * 行统计 {@code [总行数, 1-2字行数, 纯标点行数]} · 对齐源站 {@code _layout_line_stats}
     * （pdf_parser.py:1179-1194）。
     */
    static int[] layoutLineStats(String text) {
        List<String> lines = nonBlankLines(text);
        if (lines.isEmpty()) {
            return new int[]{0, 0, 0};
        }
        int single = 0;
        int punctOnly = 0;
        for (String ln : lines) {
            int len = codePointLength(ln);
            if (len <= 2) {
                single++;
            }
            if (len <= 4 && PUNCT_ONLY.matcher(ln).matches()) {
                punctOnly++;
            }
        }
        return new int[]{lines.size(), single, punctOnly};
    }

    /**
     * 破碎 OCR 行占比（一行 ≥6 个词且过半是 1-2 字母的碎词）· 对齐源站
     * {@code _layout_garbled_line_fraction}（pdf_parser.py:1196-1210）。
     */
    static double layoutGarbledLineFraction(String text) {
        List<String> lines = nonBlankLines(text);
        if (lines.isEmpty()) {
            return 0.0;
        }
        int garbled = 0;
        for (String ln : lines) {
            String[] words = ln.split("\\s+");
            if (words.length >= 6) {
                int shortWords = 0;
                for (String w : words) {
                    if (codePointLength(w) <= 2) {
                        shortWords++;
                    }
                }
                if ((double) shortWords / words.length > 0.45) {
                    garbled++;
                }
            }
        }
        return (double) garbled / lines.size();
    }

    /**
     * 紧凑的「标签 + 数值」行 · 对齐源站 {@code _numeric_row_values}（pdf_parser.py:1212-1235）。
     *
     * @return 每项 {@code [行号, 标签, 数值]}
     */
    static List<String[]> numericRowValues(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = NUMERIC_ROW.matcher(lines[i].strip());
            if (!m.matches()) {
                continue;
            }
            String label = m.group(1).strip();
            if (label.isEmpty() || codePointLength(label) > 60
                || label.split("\\s+").length > 4) {
                continue;
            }
            if (!label.isEmpty() && ".。!！?？,，;；:：".indexOf(label.charAt(label.length() - 1)) >= 0) {
                continue;
            }
            if (!hasAlpha(label)) {
                continue;
            }
            rows.add(new String[]{String.valueOf(i), label, m.group(2)});
        }
        return rows;
    }

    /** 至少两行相邻（行距 ≤3）才算「结构化数值表」· 对齐源站 {@code _structured_numeric_rows}（pdf_parser.py:1237-1244）。 */
    static List<String[]> structuredNumericRows(String text) {
        List<String[]> rows = numericRowValues(text);
        if (rows.size() < 2) {
            return List.of();
        }
        for (int i = 1; i < rows.size(); i++) {
            int prev = Integer.parseInt(rows.get(i - 1)[0]);
            int cur = Integer.parseInt(rows.get(i)[0]);
            if (cur - prev <= 3) {
                return rows;
            }
        }
        return List.of();
    }

    /** 数值归一化（源站 {@code value.replace(",", "").lower()}）。 */
    static String normalizeNumericValue(String value) {
        return value == null ? "" : value.replace(",", "").toLowerCase(Locale.ROOT);
    }

    /** 数值多重集（用于「几何重建不得改变数值证据」）。 */
    private static Map<String, Integer> numberMultiset(String text) {
        Map<String, Integer> out = new HashMap<>();
        Matcher m = NUMBERS.matcher(text == null ? "" : text);
        while (m.find()) {
            out.merge(m.group(), 1, Integer::sum);
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 页眉页脚去重（pdf_parser.py:1997-2034）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 删除跨页重复的页眉/页脚行 · 对齐源站 {@code _strip_repeating_lines}（pdf_parser.py:1997-2034）。
     *
     * <p>保守策略（源站注释原文）：只有每页<b>首个/末个</b>非空行是候选，行长 ≤80，
     * 且要在 ≥60% 的文本页上出现（文本页 ≥4 才开始判断），避免误删正文。
     */
    static List<String> stripRepeatingLines(List<String> pageTexts, List<Boolean> isTextPage, String fileName) {
        List<String> texts = new ArrayList<>(pageTexts);
        int textPages = countTextPages(isTextPage);
        if (textPages < 4) {
            log.info("[PDF] 页眉页脚去重 跳过：文本页 {} < 4 file={}", textPages, fileName);
            return texts;
        }
        Map<String, Integer> counter = new HashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            if (!isTextPage.get(i)) {
                continue;
            }
            List<String> lines = nonBlankLines(texts.get(i));
            if (lines.isEmpty()) {
                continue;
            }
            // 源站用 set{首行, 末行}：单行页只算一次
            Set<String> edges = new java.util.LinkedHashSet<>();
            edges.add(lines.get(0));
            edges.add(lines.get(lines.size() - 1));
            for (String edge : edges) {
                if (codePointLength(edge) <= 80) {
                    counter.merge(edge, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(2, (int) (textPages * 0.6));
        Set<String> repeating = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Integer> e : counter.entrySet()) {
            if (e.getValue() >= threshold) {
                repeating.add(e.getKey());
            }
        }
        if (repeating.isEmpty()) {
            log.info("[PDF] 页眉页脚去重 无重复行（阈值={} 文本页={}）file={}", threshold, textPages, fileName);
            return texts;
        }
        log.info("[PDF] 页眉页脚去重 命中 {} 条（阈值={} 文本页={}）行={} file={}",
            repeating.size(), threshold, textPages, abbreviate(repeating), fileName);
        List<String> cleaned = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (!isTextPage.get(i)) {
                cleaned.add(texts.get(i));
                continue;
            }
            // 源站：kept = [ln for ln in text.splitlines() if ln.strip() not in repeating]
            //       "\n".join(kept)
            // ⚠️ 只看「这行是不是重复页眉页脚」，空行照原样保留（splitlines 也保留空行）——
            //    多删空行会改变段落结构，与源站不一致。
            List<String> kept = new ArrayList<>();
            for (String ln : texts.get(i).split("\\R", -1)) {
                if (!ln.strip().isEmpty() && repeating.contains(ln.strip())) {
                    continue;
                }
                kept.add(ln);
            }
            cleaned.add(String.join("\n", kept));
        }
        return cleaned;
    }

    private static int countTextPages(List<Boolean> flags) {
        int n = 0;
        for (boolean b : flags) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 图片：面积比（CTM 口径）+ 内嵌图过滤
    // ════════════════════════════════════════════════════════════════════════

    /** 一页的图形流统计：图片覆盖面积比 + 每个 drawImage 的 bbox。 */
    record PageGraphics(double areaRatio, List<ImageDraw> draws) {
    }

    /** 一次 {@code drawImage} 的页面矩形（用户空间 pt）。 */
    record ImageDraw(PDImage image, double x0, double y0, double x1, double y1) {

        double yTop() {
            return Math.max(y0, y1);
        }

        double area() {
            return Math.abs((x1 - x0) * (y1 - y0));
        }
    }

    /**
     * 一次图形流遍历拿到「面积比 + 全部图片绘制事件」。
     *
     * <p>为什么要 bbox：{@code PDImageXObject} 本身没有位置，位置只在内容流的 {@code cm} 里；
     * 源站也是按 {@code obj.get_bounds()}（页面对象口径）算面积比和取舍内嵌图。
     */
    private static PageGraphics analyzePageGraphics(PDPage page) {
        if (page == null) {
            return new PageGraphics(0.0, List.of());
        }
        double pageArea = pageAreaOf(page);
        if (pageArea <= 0) {
            return new PageGraphics(0.0, List.of());
        }
        PageImageAreaCollector collector = new PageImageAreaCollector(page);
        try {
            collector.processPage(page);
        } catch (Exception e) {
            log.warn("[PDF] 图形流统计失败（面积比按 0、内嵌图跳过）原因={}", e.getMessage());
            return new PageGraphics(0.0, List.of());
        }
        return new PageGraphics(collector.imageArea() / pageArea, collector.draws());
    }

    /** 一页的页面面积（pt²）· 取 <b>CropBox</b>，缺省回落 MediaBox。
     *
     * <p>源站 pdfium 的 {@code page.get_size()} 也是 crop box 口径；两者取不同盒会让
     * CropBox≠MediaBox 的页面比值偏移。
     */
    private static double pageAreaOf(PDPage page) {
        PDRectangle box = page == null ? null : page.getCropBox();
        if (box == null) {
            box = page == null ? null : page.getMediaBox();
        }
        if (box == null) {
            return 0.0;
        }
        return Math.abs((double) box.getWidth() * box.getHeight());
    }

    /**
     * 一页的图片覆盖面积比 · 对齐源站 {@code _page_image_area_ratio}（pdf_parser.py:206-225）。
     *
     * <p>比值可能 &gt; 1.0（重叠/旋转/多图叠加）—— <b>不 clamp</b>。
     *
     * <p>fail-soft：内容流损坏、字体缺失等异常一律返回 0.0 —— 只是这页少一个判据信号，
     * 不该让整份 PDF 解析失败。
     */
    static double pageImageAreaRatio(PDPage page) {
        return analyzePageGraphics(page).areaRatio();
    }

    /**
     * 内嵌图候选 · 已解码的像素尺寸 + 内容哈希（用于跨页重复判定）。
     *
     * @param pageIndex 页号（1-based）
     * @param image     图片对象（文档仍打开期间有效）
     * @param areaRatio 显示面积 ÷ 页面面积
     * @param width     像素宽
     * @param height    像素高
     * @param hash      解码后像素的内容哈希
     * @param yTop      显示区顶边（用户空间；越大越靠上 ⇒ 排序时降序 = 上→下）
     */
    private record ImageCandidate(int pageIndex, PDImage image, double areaRatio,
                                  int width, int height, String hash, double yTop) {
    }

    /** 通过筛选、准备落盘的内嵌图（带 page/顺序，便于命名与排序）。 */
    private record EmbeddedImage(int pageIndex, PDImage image, double yTop) {
    }

    /** 收集候选并就地统计丢弃原因（面积不足/像素过小/解码失败）。 */
    private static void collectImageCandidates(PageGraphics graphics, int pageNo, double pageArea,
                                               String fileName, List<ImageCandidate> out,
                                               Map<String, Integer> reasons) {
        log.info("[PDF] 内嵌图候选 page={} 绘制次数={} 页面面积={} file={}",
            pageNo, graphics.draws().size(), fmt(pageArea), fileName);
        for (ImageDraw draw : graphics.draws()) {
            double areaRatio = pageArea <= 0 ? 0.0 : draw.area() / pageArea;
            if (areaRatio < EMBED_MIN_AREA_RATIO) {
                // 廉价跳过：先按面积比筛掉 logo/装饰，免解码（源站 pdf_parser.py:1919-1921）
                reasons.merge("面积不足", 1, Integer::sum);
                continue;
            }
            int w = draw.image().getWidth();
            int h = draw.image().getHeight();
            if (w < EMBED_MIN_PIXELS || h < EMBED_MIN_PIXELS) {
                reasons.merge("像素过小", 1, Integer::sum);
                log.info("[PDF] 内嵌图丢弃 page={} 原因=像素过小 尺寸={}x{} file={}", pageNo, w, h, fileName);
                continue;
            }
            BufferedImage decoded = null;
            try {
                decoded = draw.image().getImage();
                if (decoded == null) {
                    reasons.merge("解码为空", 1, Integer::sum);
                    continue;
                }
                out.add(new ImageCandidate(pageNo, draw.image(), areaRatio,
                    decoded.getWidth(), decoded.getHeight(), imageHash(decoded), draw.yTop()));
            } catch (Exception e) {
                reasons.merge("解码失败", 1, Integer::sum);
                log.warn("[PDF] 内嵌图解码失败 page={} 尺寸={}x{} 原因={} file={}",
                    pageNo, w, h, e.getMessage(), fileName);
            } finally {
                if (decoded != null) {
                    decoded.flush();
                }
            }
        }
    }

    /**
     * 内嵌图取舍 · 对齐源站 {@code _select_embedded_images}（pdf_parser.py:1814-1856）。
     *
     * <p>顺序（照抄）：面积比 → 像素尺寸 → 跨页重复（水印/logo） → 同页去重 → 数量上限。
     *
     * <p>⚠️ 面积/像素两道闸在 {@link #collectImageCandidates} 已做过一次（源站也是先廉价跳过再判重），
     * 这里是纯函数级兜底：<b>不再重复计入 {@code reasons}</b>，否则同一张图会被算成两次丢弃。
     *
     * @param candidates   已通过面积/尺寸/解码的候选
     * @param numTextPages 文本页数（跨页重复阈值 = {@code max(2, int(文本页 × 0.5))}）
     * @param reasons      丢弃原因计数（原地累加）
     * @return 保留的候选（按页号、页内自上而下排序）
     */
    static List<EmbeddedImage> selectEmbeddedImages(List<ImageCandidate> candidates, int numTextPages,
                                                    Map<String, Integer> reasons) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Set<Integer>> hashPages = new HashMap<>();
        for (ImageCandidate c : candidates) {
            hashPages.computeIfAbsent(c.hash(), k -> new java.util.LinkedHashSet<>()).add(c.pageIndex());
        }
        int repeatThreshold = numTextPages > 0
            ? Math.max(2, (int) (numTextPages * EMBED_REPEAT_PAGE_FRAC))
            : 2;
        Set<String> banned = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Set<Integer>> e : hashPages.entrySet()) {
            if (e.getValue().size() >= repeatThreshold) {
                banned.add(e.getKey());
            }
        }
        if (!banned.isEmpty()) {
            log.info("[PDF] 内嵌图水印判定 重复哈希 {} 个（阈值=出现在 ≥{} 个文本页，文本页={}）",
                banned.size(), repeatThreshold, numTextPages);
        }

        Set<String> seen = new java.util.HashSet<>();
        List<EmbeddedImage> kept = new ArrayList<>();
        for (ImageCandidate c : candidates) {
            if (c.areaRatio() < EMBED_MIN_AREA_RATIO
                || c.width() < EMBED_MIN_PIXELS || c.height() < EMBED_MIN_PIXELS) {
                // 兜底（正常不会到这里）：不计原因，避免与 collectImageCandidates 重复计数
                continue;
            }
            if (banned.contains(c.hash())) {
                reasons.merge("跨页重复(水印/logo)", 1, Integer::sum);
                continue;
            }
            String key = c.pageIndex() + "#" + c.hash();
            if (!seen.add(key)) {
                reasons.merge("同页重复", 1, Integer::sum);
                continue;
            }
            if (kept.size() >= EMBED_MAX_IMAGES) {
                reasons.merge("超出单文档上限(" + EMBED_MAX_IMAGES + ")", 1, Integer::sum);
                continue;
            }
            kept.add(new EmbeddedImage(c.pageIndex(), c.image(), c.yTop()));
        }

        // 页内从上到下（用户空间 y 越大越靠上 ⇒ 降序）
        kept.sort((a, b) -> {
            int byPage = Integer.compare(a.pageIndex(), b.pageIndex());
            return byPage != 0 ? byPage : Double.compare(b.yTop(), a.yTop());
        });
        return kept;
    }

    /**
     * 落盘选中的内嵌图（每页 img 序号从 1 递增，与既有命名一致）。
     *
     * <p>JPEG/PNG 直出原字节（保真且省一次编码），其余编码解码后统一写 PNG。
     */
    private static void saveEmbeddedImages(List<EmbeddedImage> kept, Path imageDir,
                                           List<KbImageRef> images, Map<String, Integer> reasons) {
        Map<Integer, Integer> perPage = new HashMap<>();
        int saved = 0;
        int failed = 0;
        for (EmbeddedImage e : kept) {
            int pageNo = e.pageIndex();
            int indexInPage = perPage.getOrDefault(pageNo, 0) + 1;
            Path out = null;
            try {
                out = writeImage(e.image(), imageDir, pageNo, indexInPage);
            } catch (Exception ex) {
                log.warn("[PDF] 内嵌图写盘失败 page={} 序号={} 原因={}", pageNo, indexInPage, ex.getMessage());
            }
            if (out == null) {
                failed++;
                reasons.merge("写盘失败", 1, Integer::sum);
                continue;
            }
            perPage.put(pageNo, indexInPage);
            images.add(KbImageRef.of(pageNo, out.toAbsolutePath().toString(), KbImageRef.KIND_PDF_EMBEDDED));
            saved++;
        }
        if (saved > 0 || failed > 0) {
            log.info("[PDF] 内嵌图落盘 保留={} 失败={} 每页保留={}", saved, failed, perPage);
        }
    }

    /** 写一张内嵌图（JPEG/PNG 直出，其余解码后写 PNG）；失败返回 null。 */
    private static Path writeImage(PDImage img, Path imageDir, int pageIndex, int indexInPage)
        throws IOException {
        String suffix = img.getSuffix() == null ? "" : img.getSuffix().toLowerCase(Locale.ROOT);
        if ("jpg".equals(suffix) || "jpeg".equals(suffix) || "png".equals(suffix)) {
            Path out = imageDir.resolve(String.format("pdf-p%03d-img%02d.%s", pageIndex, indexInPage, suffix));
            try (InputStream in = img.createInputStream()) {
                Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return out;
        }
        BufferedImage decoded = img.getImage();
        if (decoded == null) {
            return null;
        }
        try {
            Path out = imageDir.resolve(String.format("pdf-p%03d-img%02d.png", pageIndex, indexInPage));
            return ImageIO.write(decoded, "png", out.toFile()) ? out : null;
        } finally {
            decoded.flush();
        }
    }

    /** 像素内容哈希（MD5）· 对应源站 {@code hashlib.md5(pil.tobytes()).hexdigest()} 的用途。 */
    static String imageHash(BufferedImage img) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((img.getWidth() + "x" + img.getHeight()).getBytes(StandardCharsets.US_ASCII));
            int w = img.getWidth();
            int[] row = new int[w];
            for (int y = 0; y < img.getHeight(); y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int v : row) {
                    md.update((byte) (v >>> 16));
                    md.update((byte) (v >>> 8));
                    md.update((byte) v);
                }
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // 哈希失败只影响「跨页重复判定」，不该让整份解析失败
            return "nohash-" + System.identityHashCode(img);
        }
    }

    /**
     * 只做一件事的图形流引擎：累加每个绘制图片的<b>显示 bbox 面积</b>（PDF 用户空间 pt²），
     * 并记录每个 {@code drawImage} 的矩形（供内嵌图过滤使用）。
     *
     * <p><b>WHY 走内容流而不是 XObject 字典</b>：{@code PDImageXObject} 本身没有位置信息，
     * 位置在内容流的 {@code cm} 矩阵里。本引擎覆写 {@code drawImage}，从
     * {@code getCurrentTransformationMatrix()} 取当前 CTM —— PDF 规范里图片总是绘制在
     * 用户空间的单位正方形上（{@code PageDrawer#drawImage} 亦如此），所以把 [0,1]² 四角经
     * CTM 变换后取轴对齐外接矩形，就是该图片在页面上的显示区域。
     *
     * <p>内容流还天然覆盖「Form XObject 嵌套」与「内联图片」两条路径；
     * 同一图片被引用两次会算两次面积，与源站逐 pageobject 累加一致。
     *
     * <p>路径类算子与正文无关，全部空实现；只维护 {@code getCurrentPoint} 需要的当前点，
     * 以免 PDFBox 在 {@code l}/{@code h} 算子处打「无当前点」的 warn 噪声。
     */
    private static final class PageImageAreaCollector extends PDFGraphicsStreamEngine {

        private final java.awt.geom.Point2D.Float currentPoint = new java.awt.geom.Point2D.Float();
        private final List<ImageDraw> draws = new ArrayList<>();
        private double imageArea;

        PageImageAreaCollector(PDPage page) {
            super(page);
        }

        double imageArea() {
            return imageArea;
        }

        List<ImageDraw> draws() {
            return draws;
        }

        @Override
        public void drawImage(PDImage pdImage) {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            if (ctm == null) {
                return;
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (float[] corner : UNIT_SQUARE_CORNERS) {
                java.awt.geom.Point2D p = ctm.transformPoint(corner[0], corner[1]);
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
            }
            imageArea += (maxX - minX) * (maxY - minY);
            draws.add(new ImageDraw(pdImage, minX, minY, maxX, maxY));
        }

        private static final float[][] UNIT_SQUARE_CORNERS = {
            {0f, 0f}, {1f, 0f}, {0f, 1f}, {1f, 1f},
        };

        @Override
        public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1,
                                    java.awt.geom.Point2D p2, java.awt.geom.Point2D p3) {
            // 路径与图片面积无关
        }

        @Override
        public void clip(int windingRule) {
            // 路径与图片面积无关
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint.setLocation(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            currentPoint.setLocation(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint.setLocation(x3, y3);
        }

        @Override
        public java.awt.geom.Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
            // 路径与图片面积无关
        }

        @Override
        public void endPath() {
            // 路径与图片面积无关
        }

        @Override
        public void strokePath() {
            // 路径与图片面积无关
        }

        @Override
        public void fillPath(int windingRule) {
            // 路径与图片面积无关
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            // 路径与图片面积无关
        }

        @Override
        public void shadingFill(COSName shadingName) {
            // 渐变填充不是位图，不计入图片面积（源站也只统计 FPDF_PAGEOBJ_IMAGE）
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 源位置块（page + bbox）· 对齐源站 docreader/parser/source_locator.py
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 一行版面文字的几何（PDF 用户空间 pt，原点 = CropBox 左下角，y 向上）。
     *
     * <p>只留矩形，<b>不持有字形</b>：几何要活到「正文拼装结束」才用得上，
     * 若按页累积 {@link Glyph}，一份 200 页 PDF 会把整篇的字形都留在内存里。
     */
    private record LineGeom(String text, double x0, double y0, double x1, double y1) {
    }

    /** 一页的版面几何；{@link #NONE} 表示坐标不可用（旋转页 / 无字形 / 页框非法）。 */
    private record PageGeometry(double width, double height, List<LineGeom> lines) {

        static final PageGeometry NONE = new PageGeometry(0.0, 0.0, List.of());

        boolean usable() {
            return width > 0 && height > 0 && !lines.isEmpty();
        }
    }

    /**
     * 待落成 {@link KbSourceBlock} 的一段正文（尚未换算码点）。
     *
     * @param page      1-based 页号
     * @param startChar {@code [start, end)} 是<b>正文 StringBuilder 的 char 下标</b>（拼装那一刻记的）
     * @param endChar   同上（不含）
     * @param bbox      比例值 0–1；{@code null} = 该页几何不可用（不伪造）
     */
    record PendingBlock(int page, int startChar, int endChar, double[] bbox) {
    }

    /**
     * 抽取一页的版面几何（与 {@link #layoutMarkdown} 同一条「分栏 → 组行 → 标点合并」流水线）。
     *
     * <p><b>为什么必须复用同一条流水线</b>：段落文本就是 {@code layoutMarkdown} 的输出，
     * 几何行只有用同样的分栏与标点合并才可能逐行对上。用「全页字形按 y 分组」时，双栏页会把
     * 左右两栏同高的字拼成一行 —— 文本永远对不上，bbox 只能退化成整页文字区。
     */
    private static PageGeometry pageGeometry(PageGlyphData data) {
        List<Glyph> chars = data == null ? List.of() : data.glyphs();
        if (chars.isEmpty() || data.width() <= 0 || data.height() <= 0) {
            return PageGeometry.NONE;
        }
        double scale = medianHeight(chars);
        if (scale <= 0) {
            scale = 1.0;
        }
        List<List<Glyph>> columns = filterReadingColumns(chars, scale, data.width(),
            splitColumns(chars, scale, data.width(), 0));
        List<LineGeom> lines = new ArrayList<>();
        for (List<Glyph> col : columns) {
            for (Line ln : mergeOrphanPunctuationLines(groupLines(col))) {
                lines.add(new LineGeom(ln.text(), ln.x0(), ln.y0(), ln.x1(), ln.y1()));
            }
        }
        return new PageGeometry(data.width(), data.height(), lines);
    }

    /**
     * 一页正文按「空行」切段（返回各段的 {@code [start, end)} char 区间，已去首尾空白）。
     *
     * <p>口径与 {@link KbSourceIndex#textBlocks} 的段落一致：连续非空行组成一段，空行是段界。
     * <b>只读切片</b> —— 正文仍由调用方原样拼接，本方法不产出任何文本。
     */
    static List<int[]> paragraphRanges(String body) {
        List<int[]> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        int paraStart = -1;
        int paraEnd = -1;
        int pos = 0;
        while (pos <= body.length()) {
            int nl = body.indexOf('\n', pos);
            int lineEnd = nl < 0 ? body.length() : nl;
            if (body.substring(pos, lineEnd).isBlank()) {
                if (paraStart >= 0) {
                    addParagraph(out, body, paraStart, paraEnd);
                    paraStart = -1;
                }
            } else {
                if (paraStart < 0) {
                    paraStart = pos;
                }
                paraEnd = lineEnd;
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        if (paraStart >= 0) {
            addParagraph(out, body, paraStart, paraEnd);
        }
        return out;
    }

    private static void addParagraph(List<int[]> out, String body, int start, int end) {
        while (start < end && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(body.charAt(end - 1))) {
            end--;
        }
        if (end > start) {
            out.add(new int[]{start, end});
        }
    }

    /**
     * 段落 → 页面区域（比例值 0–1，原点在渲染页左上角）。
     *
     * <p>逐行在<b>该页已有几何行</b>里找对应矩形并求并集；一行都对不上时（{@code PDFTextStripper}
     * 的换行/空格与几何行不同源、或正文来自视觉转写）退化为<b>该页文字区的合并矩形</b>。
     * ⛔ 绝不写 {@code 0,0,0,0}：那会把「定位不到」伪装成「定位到页面左上角的一个点」。
     *
     * @return {@code [x0,y0,x1,y1]}（0–1）；几何不可用时 {@code null}
     */
    private static double[] paragraphBbox(PageGeometry geo, String paragraph) {
        if (geo == null || !geo.usable()) {
            return null;
        }
        List<LineGeom> lines = geo.lines();
        boolean[] used = new boolean[lines.size()];
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean matched = false;
        for (String rawLine : paragraph.split("\n", -1)) {
            String compact = compactForMatch(rawLine);
            if (compact.isEmpty()) {
                continue;
            }
            int idx = matchGeometryLine(lines, used, compact);
            if (idx < 0) {
                continue;
            }
            used[idx] = true;
            LineGeom ln = lines.get(idx);
            minX = Math.min(minX, ln.x0());
            minY = Math.min(minY, ln.y0());
            maxX = Math.max(maxX, ln.x1());
            maxY = Math.max(maxY, ln.y1());
            matched = true;
        }
        if (!matched) {
            for (LineGeom ln : lines) {
                minX = Math.min(minX, ln.x0());
                minY = Math.min(minY, ln.y0());
                maxX = Math.max(maxX, ln.x1());
                maxY = Math.max(maxY, ln.y1());
            }
        }
        return bboxRatio(geo.width(), geo.height(), minX, minY, maxX, maxY);
    }

    /** 在未使用的几何行里找与 {@code compact} 对应的一行：先精确相等，再退化为「该行是段落行的子串」。 */
    private static int matchGeometryLine(List<LineGeom> lines, boolean[] used, String compact) {
        for (int i = 0; i < lines.size(); i++) {
            if (!used[i] && compactForMatch(lines.get(i).text()).equals(compact)) {
                return i;
            }
        }
        int best = -1;
        int bestLen = 1;
        for (int i = 0; i < lines.size(); i++) {
            if (used[i]) {
                continue;
            }
            String key = compactForMatch(lines.get(i).text());
            if (key.length() > bestLen && compact.contains(key)) {
                best = i;
                bestLen = key.length();
            }
        }
        return best;
    }

    /** 匹配用紧凑文本：去 Markdown 标题记号与全部空白（<b>只用于比对</b>，不参与正文）。 */
    static String compactForMatch(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        if (i > 0) {
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
        }
        StringBuilder sb = new StringBuilder(line.length() - i);
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * PDF 用户空间矩形 → 比例值 {@code [x0,y0,x1,y1]}（0–1，原点在渲染页左上角）。
     *
     * <p>用户空间 y 向上，locator 的 y 向下 ⇒ {@code top = height - y1}、{@code bottom = height - y0}。
     * 出页框的坐标夹到 0–1（CropBox 与字形坐标系口径不一致时只丢精度，不产生负数）。
     *
     * @return 退化矩形（宽或高 ≤ 0）返回 {@code null}
     */
    private static double[] bboxRatio(double width, double height, double x0, double y0,
                                      double x1, double y1) {
        if (width <= 0 || height <= 0 || x1 <= x0 || y1 <= y0) {
            return null;
        }
        double rx0 = clamp01(x0 / width);
        double rx1 = clamp01(x1 / width);
        double ry0 = clamp01((height - y1) / height);
        double ry1 = clamp01((height - y0) / height);
        if (rx1 - rx0 <= 0 || ry1 - ry0 <= 0) {
            return null;
        }
        return new double[]{rx0, ry0, rx1, ry1};
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 把一页正文的每个段落登记成 {@link PendingBlock}（char 下标平移 {@code bodyStartChar}）。 */
    private static void collectPageBlocks(List<PendingBlock> out, int pageNo, String body,
                                          int bodyStartChar, PageGeometry geo) {
        for (int[] range : paragraphRanges(body)) {
            out.add(new PendingBlock(pageNo, bodyStartChar + range[0], bodyStartChar + range[1],
                paragraphBbox(geo, body.substring(range[0], range[1]))));
        }
    }

    /**
     * 待定块 → {@link KbSourceBlock}（<b>码点</b>口径，并对齐 {@code strip} / {@code truncate} 后的正文）。
     *
     * <p>三条硬约束：① {@code start}/{@code end} 必须落在 {@code text} 的码点范围内（截断后尾部块被裁剪）；
     * ② {@code end > start}（否则丢弃，{@link KbSourceIndex#of} 也会丢）；③ 按 {@code start} 升序。
     */
    static List<KbSourceBlock> toSourceBlocks(List<PendingBlock> pending, String rawText,
                                              int leadingCodePoints, String text) {
        if (pending == null || pending.isEmpty() || rawText == null || text == null || text.isEmpty()) {
            return List.of();
        }
        int total = text.codePointCount(0, text.length());
        List<KbSourceBlock> out = new ArrayList<>(pending.size());
        for (PendingBlock p : pending) {
            int start = rawText.codePointCount(0, clampChar(rawText, p.startChar())) - leadingCodePoints;
            int end = rawText.codePointCount(0, clampChar(rawText, p.endChar())) - leadingCodePoints;
            int s = Math.max(0, Math.min(start, total));
            int e = Math.max(0, Math.min(end, total));
            if (e <= s) {
                continue;
            }
            out.add(new KbSourceBlock(s, e, KbSourceLocator.pdf(p.page(), p.bbox())));
        }
        out.sort(Comparator.comparingInt(KbSourceBlock::start));
        return out;
    }

    private static int clampChar(String s, int idx) {
        return Math.max(0, Math.min(idx, s.length()));
    }

    /** 被 source block 覆盖的码点数（按码点去重）。 */
    private static int coveredCodePoints(List<KbSourceBlock> blocks, int total) {
        if (blocks == null || blocks.isEmpty() || total <= 0) {
            return 0;
        }
        java.util.BitSet bits = new java.util.BitSet(total);
        for (KbSourceBlock b : blocks) {
            int from = Math.max(0, Math.min(b.start(), total));
            int to = Math.max(from, Math.min(b.end(), total));
            if (to > from) {
                bits.set(from, to);
            }
        }
        return bits.cardinality();
    }

    /** 有 source block 的页数（按 locator 的 page 去重）。 */
    private static int coveredPages(List<KbSourceBlock> blocks) {
        Set<Integer> pages = new java.util.HashSet<>();
        if (blocks != null) {
            for (KbSourceBlock b : blocks) {
                if (b.locator() != null && b.locator().page() != null) {
                    pages.add(b.locator().page());
                }
            }
        }
        return pages.size();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════════

    /** 中位行高（只统计 &gt;0 的字形高度）。 */
    static double medianHeight(List<Glyph> chars) {
        List<Double> hs = new ArrayList<>();
        for (Glyph g : chars) {
            if (g.height() > 0) {
                hs.add((double) g.height());
            }
        }
        return hs.isEmpty() ? 0.0 : medianOf(hs);
    }

    /** 中位数（偶数个取中间两个的平均，与源站 {@code statistics.median} 一致）。 */
    static double medianOf(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static float charsBboxX0(List<Glyph> chars) {
        float v = Float.MAX_VALUE;
        for (Glyph g : chars) {
            v = Math.min(v, g.x0());
        }
        return v;
    }

    private static float charsBboxY0(List<Glyph> chars) {
        float v = Float.MAX_VALUE;
        for (Glyph g : chars) {
            v = Math.min(v, g.y0());
        }
        return v;
    }

    private static float charsBboxX1(List<Glyph> chars) {
        float v = -Float.MAX_VALUE;
        for (Glyph g : chars) {
            v = Math.max(v, g.x1());
        }
        return v;
    }

    private static float charsBboxY1(List<Glyph> chars) {
        float v = -Float.MAX_VALUE;
        for (Glyph g : chars) {
            v = Math.max(v, g.y1());
        }
        return v;
    }

    /** 非空行（去首尾空白，丢弃空行）。 */
    private static List<String> nonBlankLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String ln : text.split("\\R", -1)) {
            String s = ln.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static int codePointLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /**
     * 是否「全是空白字符」· 严格对齐 Python {@code str.isspace()}。
     *
     * <p>⚠️ 空串要返回 <b>false</b>（Python 的 {@code "".isspace()} 是 False）——
     * 有些字形（actualText 覆盖后）unicode 为空串，按空白处理会跳过补空格逻辑。
     */
    private static boolean isAllWhitespace(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 源站 {@code ch in "0123456789.,%+-"}（单字符字形才匹配，多字符连字不参与数字对判定）。 */
    private static boolean isNumericChar(String ch) {
        return ch != null && ch.length() == 1 && "0123456789.,%+-".indexOf(ch.charAt(0)) >= 0;
    }

    private static boolean hasDigit(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAlpha(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPunctOnly(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && ".,;:!?…·".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 页号清单的日志压缩形态：最多列前 10 个，其余给总数。 */
    private static String abbreviatePages(List<Integer> pages) {
        if (pages == null || pages.isEmpty()) {
            return "-";
        }
        int limit = Math.min(10, pages.size());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pages.get(i));
        }
        sb.append(']');
        if (pages.size() > limit) {
            sb.append(" 等共").append(pages.size()).append("页");
        }
        return sb.toString();
    }

    private static String abbreviate(Set<String> lines) {
        List<String> list = new ArrayList<>(lines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            String s = list.get(i);
            sb.append(s.length() > 40 ? s.substring(0, 40) + "…" : s);
        }
        if (list.size() > 3) {
            sb.append(" …共").append(list.size()).append("条");
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmtList(List<Double> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fmt(values.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 页标题格式 · 单一来源（正文拼装与测试断言共用，避免测试里手抄字符串走样）。 */
    static String pageHeading(int pageNo) {
        return "## 第 " + pageNo + " 页";
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
}

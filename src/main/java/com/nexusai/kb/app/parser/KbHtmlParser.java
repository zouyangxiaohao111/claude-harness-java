package com.nexusai.kb.app.parser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.ccil.cowan.tagsoup.HTMLModels;
import org.ccil.cowan.tagsoup.HTMLSchema;
import org.ccil.cowan.tagsoup.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTML / XHTML 专用解析器 · 保留<b>结构</b>（标题层级 / 表格 / 链接 / 正文），而不是拍成扁平文本。
 *
 * <p><b>源站对照</b>：WeKnora {@code docreader/parser/html_parser.py}（{@code HTMLToMarkdownParser}
 * 先用 trafilatura 抽正文再转 Markdown，抽不出来时退回整页 Markdown）。本仓走「Tika XHTML 事件流
 * → Markdown」的等价路线，逐项对齐：
 * <ul>
 *   <li><b>标题层级</b>：{@code h1..h6} → {@code #..######}（复用 {@link KbMarkdownHandler}）</li>
 *   <li><b>表格</b>：{@code table/tr/th/td} → Markdown 表格（同上；{@link KbMarkdownHandler} 已实现）</li>
 *   <li><b>链接</b>：{@code <a href="url">文字</a>} → {@code [文字](url)}。⚠️ {@link KbMarkdownHandler}
 *       的 {@code default} 分支对 {@code <a>} 只保留文字，故本类补这一层（见 {@link HtmlMarkdownHandler}）</li>
 *   <li><b>正文去噪</b>：{@code nav} / {@code footer} / {@code aside} 整块丢弃（见 {@link Html5Schema}）</li>
 * </ul>
 *
 * <p><b>⚠️ 有意的行为变化</b>：{@code .html / .htm / .xhtml} 此前由 {@link KbTextParser} 兜底、
 * 用 {@code ToTextContentHandler} 抽出扁平文本（表格被拍成制表符行、标题层级全部丢失）。
 * 本类以 {@code @Order(25)}（在 PDF 10 / anydoc 12 / 图片 15 / Office 20 之后，Text 30 之前）
 * 接管这三个扩展名，同时 {@link KbTextParser} 已把它们从自己的能力集合里让出。
 * 因此默认路由下 HTML 从此走本解析器 —— 这是<b>故意的</b>，不是回归。
 *
 * <p><b>为什么用 SAX/XHTML 事件而不是 {@code ToTextContentHandler}</b>：后者把表格拍平成
 * {@code \t标段\t金额\t}，结构一旦拍平，下游 {@code KbTextSplitter} 的表格保护与表头前置全部失效。
 *
 * <p><b>⛔ 做不到的两件事（不伪造）</b>：
 * <ol>
 *   <li>{@code sourceBlocks} 恒为空列表：Markdown 是 HTML 的<b>改写</b>结果，码点偏移与原文件字节
 *       不是一一对应；套用 {@link KbSourceIndex#textBlocks} 会把「Markdown 偏移」谎报成「原文件偏移」。</li>
 *   <li>{@code images} 恒为空列表：{@code <img>} 只在 Markdown 里留 {@code ![image]} 占位
 *       （{@link KbMarkdownHandler} 的行为），本类不落盘图片二进制。</li>
 * </ol>
 *
 * <p><b>去噪能力的边界</b>：只删 {@code nav/footer/aside} 三种语义容器；{@code header}、侧栏
 * {@code div}、相关推荐等模板噪声<b>不删</b>（保留页面主标题，避免把装在 {@code <header>} 里的
 * {@code h1} 一起丢掉）。这比源站 trafilatura 的正文提取弱，属已知偏离。
 */
@Component
@Order(25)
public class KbHtmlParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbHtmlParser.class);

    /** 单文档文本上限（与 {@link KbTextParser#MAX_TEXT_CHARS} 同口径，派单书 §3.3）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /** 本解析器明确支持的扩展名（小写、不带点）。 */
    private static final Set<String> EXTENSIONS = Set.of("html", "htm", "xhtml");

    /** 无扩展名时的 MIME 兜底（只认这两个；其它 text/* 仍归 {@link KbTextParser}）。 */
    private static final Set<String> HTML_MIME_TYPES = Set.of("text/html", "application/xhtml+xml");

    /**
     * 整块丢弃的模板容器（正文去噪）。
     *
     * <p>⚠️ 这三个是 HTML5 标签，Tika 2.9.2 的 HTML4 Schema 不认识它们；不改 Schema 时 Tagsoup
     * 会把标签本身当 bogon 丢掉、<b>文字却照常流进正文</b>（实测：导航文字会混在正文开头）。
     * 见 {@link Html5Schema} / {@link #NOISE_MAPPER}。
     */
    private static final Set<String> NOISE_ELEMENTS = Set.of("nav", "footer", "aside");

    /** 参与正文/噪声判定的块级容器（源站 trafilatura 的思路：按块算文本与链接密度）。 */
    private static final Set<String> CONTENT_BLOCKS =
        Set.of("div", "section", "article", "main", "nav", "aside", "footer", "header");

    /** class/id 里的模板噪声词（大小写不敏感）。 */
    private static final Pattern NOISE_WORDS = Pattern.compile(
        "(?i)(^|[-_ ])(nav|menu|sidebar|footer|header|comment|share|breadcrumb|pagination)([-_ ]|$)");

    /** 正文/噪声判定的阈值（与源站 trafilatura 的量级一致，非逐字照抄）。 */
    static final int SHORT_TEXT = 30;
    static final int BODY_TEXT = 200;
    static final double HIGH_LINK_DENSITY = 0.5;
    static final double LOW_LINK_DENSITY = 0.3;

    /**
     * 是否只保留正文块（默认开）。关闭时退回「只删 nav/footer/aside」的旧行为。
     *
     * <p>⚠️ 关闭开关后行为与 2026-11-12 之前逐字一致，便于线上出问题时一键回退。
     */
    @Value("${nexusai.knowledge.html.main-content-only:true}")
    private boolean mainContentOnly = true;

    /** Markdown 表格的对齐分隔行（本仓 {@link KbMarkdownHandler} 固定输出 {@code | --- |}）。 */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|(\\s*-{3,}\\s*\\|)+$");

    /**
     * Tagsoup Schema 单例 · 把 HTML5 的 {@code nav/footer/aside} 登记成「div 同款」块级流元素。
     *
     * <p><b>WHY 必须动 Schema</b>：{@code HtmlParser} 里写死 {@code setFeature(ignore-bogons, true)}
     * （Tika 2.9.2 字节码可见），Schema 不认识的标签直接丢；只替换 {@link HtmlMapper} 是<b>事后</b>的，
     * 到不了事件层（实测：nav/footer 的文字被当无标签文本透传）。
     *
     * <p>Schema 构造完成后只读，可跨请求共享（Tika 自身也把 {@code HTML_SCHEMA} 当静态常量用）。
     */
    private static final Schema HTML5_SCHEMA = new Html5Schema();

    /** 保留 {@code nav/footer/aside} 的 HtmlMapper · 其余一律委托 Tika 默认策略。 */
    private static final HtmlMapper NOISE_MAPPER = new NoisePreservingMapper();

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null) {
            // 有扩展名就只认扩展名：陌生扩展名交给后面的 Text 兜底，⛔ 不按 MIME 抢别人的文件
            return EXTENSIONS.contains(ext);
        }
        // 没有扩展名（上传丢了后缀）才看 MIME
        return HTML_MIME_TYPES.contains(KbParsers.normalizeMime(mimeType));
    }

    /** 能力声明 · 与 {@link #supports} 同源（复用同一个 {@link #EXTENSIONS}）。 */
    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    /** 展示名 · 前端「解析引擎」下拉用。 */
    @Override
    public String displayName() {
        return "内置 HTML 解析";
    }

    /** 引擎族名别名 · 源站内置引擎为 {@code builtin}（另收 {@code html} 便于按类型点名）。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("builtin", "html");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName().toString();

        // ── 第一遍：统计块级元素的文本/链接密度，得出噪声元素的 [start,end] 序号区间 ──
        NoiseProfiler profiler = new NoiseProfiler(mainContentOnly);
        parseOnce(file, fileName, profiler);

        // ── 第二遍：按区间跳过，其余照常转 Markdown ──
        HtmlMarkdownHandler handler = new HtmlMarkdownHandler(profiler.noiseRanges());
        Metadata md = parseOnce(file, fileName, handler);
        String markdown = handler.markdown();

        // 整页都被判噪声 ⇒ 回退整页（⛔ 不产出空文档）
        if (mainContentOnly && markdown.isBlank()) {
            log.warn("KbHtmlParser: 正文判定把整页都删了，回退整页 file={} 丢弃块={} 原因={}",
                fileName, profiler.blocksDropped(), profiler.reasons());
            handler = new HtmlMarkdownHandler(Map.of());
            md = parseOnce(file, fileName, handler);
            markdown = handler.markdown();
        } else {
            log.info("KbHtmlParser: 正文判定 file={} 块={} 丢弃={} 原因={}",
                fileName, profiler.blocksSeen(), profiler.blocksDropped(), profiler.reasons());
        }

        KbParsers.Truncation t = KbParsers.truncate(markdown, MAX_TEXT_CHARS);
        String text = t.text();

        Map<String, String> meta = KbParsers.newMeta();
        meta.putAll(KbTikaSupport.toMetadataMap(md));
        KbParsers.fillCommonMetadata(meta, fileName, md, this);
        if (t.truncated()) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(text.length()));

        // 源位置块：Markdown 是 HTML 的改写结果，码点偏移与原文件不对应 ⇒ 空列表（不伪造，见类注释）
        List<KbSourceBlock> sourceBlocks = List.of();
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, "0");
        log.info("[溯源] {} 类型=html 正文由 HTML 结构转换而来（标题/表格/链接已改写），不产出 source block",
            fileName);

        int headings = countHeadings(text);
        int tables = countTables(text);
        if (text.isEmpty()) {
            log.warn("KbHtmlParser: 未抽出正文（可能只有脚本/样式、或页面结构异常）file={}", fileName);
        }
        log.info("KbHtmlParser: 解析完成 file={} chars={} headings={} tables={}",
            fileName, text.length(), headings, tables);

        // images 恒空：<img> 只会留下 ![image] 占位文本（见类注释）
        return new KbParsedDocument(text, List.of(), meta, sourceBlocks);
    }

    /** 用同一套 Tika 配置跑一遍事件流（两遍解析必须配置完全一致，序号才对得上）。 */
    private Metadata parseOnce(Path file, String fileName, DefaultHandler handler) {
        Metadata md = KbTikaSupport.newMetadata(fileName);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            ParseContext parseCtx = new ParseContext();
            parseCtx.set(HtmlMapper.class, NOISE_MAPPER);
            parseCtx.set(Schema.class, HTML5_SCHEMA);
            // 共享 AutoDetectParser（Tika 自身会把 text/html / application/xhtml+xml 路由到 HtmlParser）
            KbTikaSupport.parser().parse(in, handler, md, parseCtx);
        } catch (Exception e) {
            throw KbTikaSupport.wrap("HTML 解析", file, e);
        }
        return md;
    }

    /**
     * 第一遍的统计器 · 只算数、不产 Markdown。
     *
     * <h2>为什么用「第一遍统计 + 第二遍按序号跳过」</h2>
     * 现有链路是流式转发给 {@link KbMarkdownHandler}，元素内容一旦写进 Markdown 就<b>撤不回来</b>。
     * 要按「整块文本/链接密度」判定噪声，必须先把内容看完再决定 ⇒ 只能再看一遍。
     * 两遍都用同一份 Schema/Mapper 与同一解析器，SAX 事件序列可复现 ⇒ 用「元素序号」做对齐。
     */
    static final class NoiseProfiler extends DefaultHandler {

        /** 一个块级容器的统计量。 */
        private static final class Frame {
            final int seq;
            final String name;
            final String classId;
            final boolean insideArticle;
            int textLen;
            int linkTextLen;
            int linkCount;

            Frame(int seq, String name, String classId, boolean insideArticle) {
                this.seq = seq;
                this.name = name;
                this.classId = classId;
                this.insideArticle = insideArticle;
            }

            double linkDensity() {
                return textLen <= 0 ? 0.0 : (double) linkTextLen / textLen;
            }
        }

        /** 每个 start 事件分配的自增序号（两遍解析里同一元素的序号相同）。 */
        private int seq;
        private int articleDepth;
        private int linkDepth;

        /** 是否做密度/噪声词判定（false 时只记录 nav/footer/aside 语义容器）。 */
        private final boolean mainContentOnly;

        private final Deque<Integer> seqStack = new ArrayDeque<>();
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final List<Frame> openBlocks = new ArrayList<>();

        /** 噪声元素区间：start 序号 → 子树最大 start 序号（end 时 seq 已含全部子孙）。 */
        private final Map<Integer, Integer> noiseRanges = new java.util.LinkedHashMap<>();
        private final Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
        private int blocksSeen;
        private int blocksDropped;

        NoiseProfiler(boolean mainContentOnly) {
            this.mainContentOnly = mainContentOnly;
        }

        Map<Integer, Integer> noiseRanges() {
            return noiseRanges;
        }

        int blocksSeen() {
            return blocksSeen;
        }

        int blocksDropped() {
            return blocksDropped;
        }

        Map<String, Integer> reasons() {
            return reasons;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            String name = tagName(localName, qName);
            int mySeq = ++seq;
            seqStack.push(mySeq);
            if ("article".equals(name) || "main".equals(name)) {
                articleDepth++;
            }
            if ("a".equals(name)) {
                linkDepth++;
                for (Frame f : openBlocks) {
                    f.linkCount++;
                }
            }
            if (CONTENT_BLOCKS.contains(name)) {
                Frame f = new Frame(mySeq, name, classIdOf(atts), articleDepth > 0);
                openBlocks.add(f);
                frames.push(f);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = tagName(localName, qName);
            if ("a".equals(name) && linkDepth > 0) {
                linkDepth--;
            }
            if (CONTENT_BLOCKS.contains(name) && !frames.isEmpty()) {
                Frame f = frames.pop();
                openBlocks.remove(f);
                blocksSeen++;
                String reason = NOISE_ELEMENTS.contains(f.name)
                    ? "语义容器"
                    : (mainContentOnly ? noiseReason(f) : null);
                if (reason != null) {
                    blocksDropped++;
                    // 此处的 seq 已是该元素子树里最后一个 start 的序号（end 不递增）
                    noiseRanges.put(f.seq, seq);
                    reasons.merge(reason, 1, Integer::sum);
                }
            }
            if (("article".equals(name) || "main".equals(name)) && articleDepth > 0) {
                articleDepth--;
            }
            seqStack.pop();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (openBlocks.isEmpty()) {
                return;
            }
            int n = 0;
            for (int i = start; i < start + length; i++) {
                if (!Character.isWhitespace(ch[i])) {
                    n++;
                }
            }
            if (n == 0) {
                return;
            }
            for (Frame f : openBlocks) {
                f.textLen += n;
                if (linkDepth > 0) {
                    f.linkTextLen += n;
                }
            }
        }

        /** 返回丢弃原因；null = 保留。 */
        private static String noiseReason(Frame f) {
            if (f.insideArticle) {
                return null;      // <article>/<main> 内一律当正文
            }
            if (f.textLen < SHORT_TEXT && f.linkCount > 0) {
                return "太短且含链接";
            }
            if (f.textLen < BODY_TEXT && f.linkDensity() > HIGH_LINK_DENSITY) {
                return "链接密度高";
            }
            if (f.classId != null && NOISE_WORDS.matcher(f.classId).find()) {
                return "噪声词";
            }
            return null;
        }

        private static String classIdOf(Attributes atts) {
            if (atts == null) {
                return null;
            }
            String cls = atts.getValue("class");
            String id = atts.getValue("id");
            if (cls == null || cls.isBlank()) {
                return id;
            }
            return id == null || id.isBlank() ? cls : cls + " " + id;
        }
    }

    /** 标题行数（Markdown {@code #{1,6} 空格}）· 仅用于日志与排查。 */
    static int countHeadings(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : markdown.split("\n")) {
            String s = line.strip();
            int hashes = 0;
            while (hashes < s.length() && s.charAt(hashes) == '#') {
                hashes++;
            }
            if (hashes >= 1 && hashes <= 6 && hashes < s.length() && s.charAt(hashes) == ' ') {
                count++;
            }
        }
        return count;
    }

    /** 表格个数（按对齐分隔行计）· 仅用于日志与排查。 */
    static int countTables(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : markdown.split("\n")) {
            if (TABLE_SEPARATOR.matcher(line.strip()).matches()) {
                count++;
            }
        }
        return count;
    }

    // ════════════════════════════════════════════════════════════════════
    // SAX 层：复用 KbMarkdownHandler + 补链接标记 + 去噪
    // ════════════════════════════════════════════════════════════════════

    /**
     * 事件转发器 · 把 Tika 的 XHTML 事件转发给 {@link KbMarkdownHandler}，并补两件它没有的能力。
     *
     * <p><b>1. 链接</b>：{@code <a href>} 在转发前插 {@code [}、转发后插 {@code ](href)}。
     * {@code [} 是<b>懒发</b>的（等链接里出现第一个非空白字符才写）—— 否则
     * {@code <a href="x"></a>} 会留下一个孤立方括号。
     *
     * <p><b>2. 去噪</b>：{@code nav/footer/aside} 的整个区间（含所有子事件与文字）都不转发，
     * 只维护一个深度计数。进入噪声区前先让 delegate 落笔当前段落，避免区前文字和区后正文粘成一段。
     *
     * <p>⚠️ 深度计数必须与 delegate 的 {@code skipDepth} 语义一致：<b>每个 start 都 +1、每个 end 都 -1</b>，
     * ⛔ 不能按「名字是否属于噪声集」判退出（噪声容器内部还可能嵌同名/同名兄弟结构，按名字判会提前退出）。
     */
    static final class HtmlMarkdownHandler extends DefaultHandler {

        private final KbMarkdownHandler delegate = new KbMarkdownHandler(0);

        /** 第一遍判定的噪声元素区间（start 序号 → 子树最大 start 序号）。 */
        private final Map<Integer, Integer> noiseRanges;

        /** 当前事件序号（与 {@link NoiseProfiler} 用同一套分配规则）。 */
        private int seq;

        /** 每个 start 的序号栈（end 时弹出，保证两遍序号一致）。 */
        private final Deque<Integer> seqStack = new ArrayDeque<>();

        /** 当前噪声子树的起始序号（0 = 不在噪声区）。 */
        private int noiseStart;

        /** 当前噪声子树的结束序号（0 = 不在噪声区）。 */
        private int noiseEnd;

        /** 是否在噪声区（characters 用；不用深度计数 —— 空元素不配对会把计数打偏）。 */
        private boolean skipping;

        HtmlMarkdownHandler() {
            this(Map.of());
        }

        HtmlMarkdownHandler(Map<Integer, Integer> noiseRanges) {
            this.noiseRanges = noiseRanges == null ? Map.of() : noiseRanges;
        }

        /** 尚未闭合的链接（栈深通常 0 或 1；嵌套链接不是合法 HTML，仍按栈处理以防事件错配）。 */
        private final Deque<LinkFrame> links = new ArrayDeque<>();

        String markdown() {
            return delegate.markdown();
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            String name = tagName(localName, qName);
            int mySeq = ++seq;
            seqStack.push(mySeq);
            // 仍在某个噪声子树内
            if (noiseEnd >= mySeq) {
                skipping = true;
                return;
            }
            // 本元素自身是第一遍判定的噪声块（含 nav/footer/aside）⇒ 整块跳过
            Integer end = noiseRanges.get(mySeq);
            if (end != null) {
                // 落笔当前段落：否则「噪声前的半句」与「噪声后的正文」会拼成同一段
                delegate.endElement("", "p", "p");
                noiseStart = mySeq;
                noiseEnd = end;
                skipping = true;
                return;
            }
            noiseEnd = 0;
            skipping = false;
            if ("a".equals(name)) {
                links.push(new LinkFrame(hrefOf(atts)));
                delegate.startElement(uri, localName, qName, atts);
                return;
            }
            delegate.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            int mySeq = seqStack.isEmpty() ? -1 : seqStack.pop();
            // 噪声子树内的所有 end 都不转发；到噪声根时复位
            if (noiseEnd > 0 && mySeq >= noiseStart && mySeq <= noiseEnd) {
                if (mySeq == noiseStart) {
                    noiseStart = 0;
                    noiseEnd = 0;
                    skipping = false;
                }
                return;
            }
            String name = tagName(localName, qName);
            delegate.endElement(uri, localName, qName);
            if ("a".equals(name)) {
                LinkFrame frame = links.isEmpty() ? null : links.pop();
                if (frame != null && frame.started && frame.href != null) {
                    emit("](" + linkTarget(frame.href) + ")");
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (skipping) {
                return;
            }
            LinkFrame frame = links.peek();
            if (frame != null && frame.href != null && !frame.started && hasText(ch, start, length)) {
                emit("[");
                frame.started = true;
            }
            delegate.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            // 与 KbMarkdownHandler 的处理保持一致：空白也走 characters（段落内会被压成单空格）
            characters(ch, start, length);
        }

        /** 交给 delegate 的 {@code characters}（保持「单元/段落缓冲」的写入路径不变）。 */
        private void emit(String s) throws SAXException {
            if (s == null || s.isEmpty()) {
                return;
            }
            char[] ch = s.toCharArray();
            delegate.characters(ch, 0, ch.length);
        }

        /** 是否有非空白字符（⛔ 不 new String，逐字符判即可）。 */
        private static boolean hasText(char[] ch, int start, int length) {
            for (int i = start; i < start + length; i++) {
                if (!Character.isWhitespace(ch[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 尚未闭合的链接 · {@code started} = 已经写过 {@code [}（链接里确实有文字）。 */
    private static final class LinkFrame {

        private final String href;
        private boolean started;

        private LinkFrame(String href) {
            this.href = href;
        }
    }

    /**
     * 取 {@code href}（大小写不敏感，兼容 {@code xlink:href}；空值 / {@code javascript:} 视为没有链接）。
     *
     * <p>返回的仍是<b>原样</b> URL（相对路径不解析成绝对地址）—— 源站也保留文档里的写法，
     * 这里没有 base URL 可解析，编造一个绝对地址只会让引用更假。
     */
    private static String hrefOf(Attributes atts) {
        if (atts == null) {
            return null;
        }
        for (int i = 0; i < atts.getLength(); i++) {
            String qn = atts.getQName(i);
            String n = qn != null && !qn.isEmpty() ? qn : atts.getLocalName(i);
            if (n == null) {
                continue;
            }
            int colon = n.indexOf(':');
            String bare = (colon >= 0 ? n.substring(colon + 1) : n).toLowerCase(Locale.ROOT);
            if (!"href".equals(bare)) {
                continue;
            }
            String v = atts.getValue(i);
            if (v == null) {
                return null;
            }
            v = v.trim();
            if (v.isEmpty() || v.regionMatches(true, 0, "javascript:", 0, "javascript:".length())) {
                return null;
            }
            return v;
        }
        return null;
    }

    /**
     * Markdown 链接目标 · 含空格或右括号时用 {@code <...>} 包裹。
     *
     * <p>WHY：{@code [t](http://a/b(c))} 里的 {@code )} 会提前闭合链接，正文后半段被当成普通文本。
     */
    private static String linkTarget(String href) {
        if (href.indexOf(' ') >= 0 || href.indexOf(')') >= 0) {
            return "<" + href.replace(">", "%3E") + ">";
        }
        return href;
    }

    /** 取小写标签名（去命名空间前缀）· 与 {@code KbMarkdownHandler} 的同名逻辑同口径（那边是 private）。 */
    private static String tagName(String localName, String qName) {
        String n = localName != null && !localName.isEmpty() ? localName : qName;
        if (n == null) {
            return "";
        }
        int colon = n.indexOf(':');
        return (colon >= 0 ? n.substring(colon + 1) : n).toLowerCase(Locale.ROOT);
    }

    /**
     * Tagsoup Schema · 把 HTML5 的 {@code nav/footer/aside} 补登记成「block 级 flow 容器」。
     *
     * <p>写法照抄 {@code HTMLSchema} 对 {@code div} 的登记：model = {@code M_PCDATA|M_BLOCK|M_INLINE}、
     * memberOf = {@code M_BLOCK}（即「可放进任何接受块级子元素的父元素」）。
     * 实测不额外调 {@code parent(...)} 也能在 body / div / li 内正常出现。
     */
    static final class Html5Schema extends HTMLSchema {

        private static final int FLOW = Schema.M_PCDATA | HTMLModels.M_BLOCK | HTMLModels.M_INLINE;

        Html5Schema() {
            // ⚠️ 2026-11-12 实测：不登记的元素会被 Tagsoup 当 bogon 丢掉（标签丢、文字照常透传），
            // <div> 也不例外 —— 正文/噪声判定必须能看到块级容器本身，所以整组一起登记。
            for (String tag : CONTENT_BLOCKS) {
                elementType(tag, FLOW, HTMLModels.M_BLOCK, 0);
            }
        }
    }

    /**
     * HtmlMapper · 只对 {@code nav/footer/aside} 保留标签名并禁止丢弃，其余委托
     * {@link DefaultHtmlMapper#INSTANCE}（保持 Tika 原有的安全标签/属性映射，包括 {@code <a href>}）。
     */
    static final class NoisePreservingMapper implements HtmlMapper {

        @Override
        public String mapSafeElement(String name) {
            String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
            // ⚠️ 2026-11-12 实测：Tika 的 DefaultHtmlMapper 会把块级容器（div/section/...）判为
            // 「不映射」⇒ HtmlParser 丢掉标签、只透传文字，正文/噪声判定就看不到容器本身。
            // ⇒ 判定要用到的块级容器一律保留原名（与 nav/footer/aside 同待遇）。
            return CONTENT_BLOCKS.contains(n) ? n : DefaultHtmlMapper.INSTANCE.mapSafeElement(name);
        }

        @Override
        public boolean isDiscardElement(String name) {
            String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return !CONTENT_BLOCKS.contains(n) && DefaultHtmlMapper.INSTANCE.isDiscardElement(name);
        }

        @Override
        public String mapSafeAttribute(String elementName, String attributeName) {
            // class/id 用于正文判定（噪声词），DefaultHtmlMapper 会把它们过滤掉 ⇒ 单独放行。
            // 下游 KbMarkdownHandler 只认 href/src，放行这两个属性不影响产物。
            String a = attributeName == null ? "" : attributeName.toLowerCase(Locale.ROOT);
            if ("class".equals(a) || "id".equals(a)) {
                return a;
            }
            return DefaultHtmlMapper.INSTANCE.mapSafeAttribute(elementName, attributeName);
        }
    }
}

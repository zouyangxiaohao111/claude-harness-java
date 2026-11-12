package com.nexusai.kb.app.parser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * EPUB 解析器 · 按 spine（阅读顺序）逐章解析，产出<b>章节级</b>溯源块。
 *
 * <p><b>源站对照</b>：WeKnora {@code docreader/parser/epub_parser.py}（解 zip →
 * {@code META-INF/container.xml} → OPF → 按 spine 顺序逐章读 XHTML，一章一个
 * {@code SourceLocator(type=section, section=N, title=…)}）。本类是同一流程的 Java 实现：
 * <ol>
 *   <li><b>容器定位</b>：{@code META-INF/container.xml} 里第一个
 *       {@code <rootfile full-path="…">} 指向 OPF（EPUB 允许有多个 rendition，取第一个）。</li>
 *   <li><b>目录结构</b>：OPF 的 {@code <manifest>} 给 id → href/media-type，{@code <spine>} 给按阅读
 *       顺序排列的 {@code idref}。⛔ 只按 spine 顺序，不按 manifest 顺序 —— manifest 是「包里有什么」，
 *       spine 才是「先读哪章」。</li>
 *   <li><b>章节转写</b>：每章 XHTML 走 Tika 的 XHTML 事件流，再交给
 *       {@link KbHtmlParser.HtmlMarkdownHandler}（与 HTML 解析器<b>同一份</b>转换逻辑：标题层级 / 表格 /
 *       链接 / nav·footer·aside 去噪）。⛔ 不另写一套 Markdown 转换，否则同一段 XHTML 走
 *       {@code .xhtml} 直传和走 EPUB 会给两种结果。</li>
 * </ol>
 *
 * <p><b>溯源</b>：每章在最终 Markdown 里占一个连续码点区间，产一个 {@link KbSourceBlock}，locator 用
 * {@link KbSourceLocator#TYPE_SECTION}、{@code section} 记 <b>1-based spine 序号</b>（源站
 * {@code source_locator.go:10-11} 与 :64-65 都是 1-based）、{@code title} 取章节 {@code <title>}，
 * 没有则取第一个 {@code h1}。章节之间用<b>两个换行</b>分隔，分隔符不归属任何块。
 *
 * <p><b>{@code @Order(18)} 的原因（2026-11-12 修正）</b>：路由按 {@code @Order} 升序取第一个命中的
 * 解析器（{@link KbParserRegistry#select}）。{@link KbOfficeParser} 是 {@code @Order(20)} 且其
 * {@code supports()} 认 {@code epub}，所以本类原定的 {@code @Order(35)} 会让 EPUB 默认落到 Tika 的
 * 通用 EpubParser、本类的 section 溯源永远不生效。把本类提到 18（在 Image(15) 之后、Office(20) 之前）
 * 后，EPUB 默认走本类；需要 Tika 通道路径时仍可用 {@code parser_engine_rules} 点名 {@code office}。
 *
 * <p><b>失败语义</b>：坏 zip / 缺 {@code META-INF/container.xml} / 缺 rootfile / OPF 结构非法 /
 * spine 为空 / spine 指向的文件不存在 / 解析后没有任何正文 ⇒ {@link KbParseException}。
 * ⛔ 不返回空文本：空文本会被上层当「解析成功但无内容」入库，用户永远查不到且没有任何错误提示。
 *
 * <p><b>本批不做</b>：图片不抽（{@code images} 恒空列表，由 {@link KbMarkdownImageResolver} 兜底）；
 * 不解析 {@code <guide>}/{@code nav.xhtml} 目录（导航不是正文）；不处理加密 EPUB
 * （{@code META-INF/encryption.xml}，条目解压会失败并抛错，属已知边界）。
 */
@Component
@Order(18)
public class KbEpubParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbEpubParser.class);

    /** 单文档文本上限（与 PDF / Office / HTML / XMind 统一口径）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /** 单个 zip 条目上限（与 {@link KbXmindParser#MAX_CONTENT_BYTES} 同口径）：防 zip 炸弹。 */
    static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;

    /** 容器清单 · EPUB 规范固定路径。 */
    static final String CONTAINER_ENTRY = "META-INF/container.xml";

    /** 本解析器唯一支持的扩展名（小写、不带点）。 */
    private static final Set<String> EXTENSIONS = Set.of("epub");

    /** 无扩展名时的 MIME（上传丢了后缀仍要认）。 */
    private static final Set<String> EPUB_MIME_TYPES = Set.of("application/epub+zip");

    /** 算正文的章节 media-type（EPUB 2/3 规范值；{@code text/html} 兼容手改过的包）。 */
    private static final Set<String> XHTML_MIME_TYPES =
        Set.of("application/xhtml+xml", "text/html", "application/xml", "text/xml");

    /** chapter 数（spine 内可解析的 XHTML 条目）· 写进 metadata 便于线上排查。 */
    static final String META_CHAPTER_COUNT = "epubChapterCount";

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null) {
            // 有扩展名就只认扩展名：⛔ 不按 MIME 抢别的 zip 容器（.docx/.xmind 也是 zip）
            return EXTENSIONS.contains(ext);
        }
        return EPUB_MIME_TYPES.contains(KbParsers.normalizeMime(mimeType));
    }

    /** 能力声明 · 与 {@link #supports} 同源（复用同一个 {@link #EXTENSIONS}）。 */
    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String displayName() {
        return "内置 EPUB 解析";
    }

    /** 引擎族名别名 · 源站内置引擎为 {@code builtin}（另收 {@code epub} 便于按类型点名）。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("builtin", "epub");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString();

        List<Chapter> chapters = readSpine(file, fileName);

        StringBuilder sb = new StringBuilder();
        List<KbSourceBlock> blocks = new ArrayList<>();
        int cpPos = 0;
        int chaptersWithText = 0;
        for (Chapter chapter : chapters) {
            RenderedChapter rendered = renderChapter(chapter, file);
            String markdown = rendered.markdown();
            if (markdown.isBlank()) {
                // 只有图片/空壳的章节（封面页常见）：没有正文就没有可定位区间，⛔ 不产空块
                log.debug("KbEpubParser: 章节无正文，跳过 section 块 file={} section={} href={}",
                    fileName, chapter.spineIndex(), chapter.path());
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
                cpPos += 2;
            }
            int start = cpPos;
            sb.append(markdown);
            int len = markdown.codePointCount(0, markdown.length());
            cpPos += len;
            blocks.add(new KbSourceBlock(start, cpPos,
                sectionLocator(chapter.spineIndex(), rendered.title())));
            chaptersWithText++;
        }

        String full = sb.toString();
        if (full.isBlank()) {
            throw new KbParseException("EPUB 解析后没有任何正文（spine 章节数=" + chapters.size()
                + "，可能只有封面图或 XHTML 全为空）: " + fileName);
        }

        KbParsers.Truncation trunc = KbParsers.truncate(full, MAX_TEXT_CHARS);
        String text = trunc.text();
        List<KbSourceBlock> finalBlocks = trunc.truncated()
            ? clampBlocks(blocks, text.codePointCount(0, text.length()))
            : blocks;

        int totalCp = text.codePointCount(0, text.length());
        int coveredCp = 0;
        for (KbSourceBlock block : finalBlocks) {
            coveredCp += block.length();
        }
        double coverage = totalCp == 0 ? 0.0 : 100.0 * coveredCp / totalCp;

        Map<String, String> meta = KbParsers.newMeta();
        KbParsers.fillCommonMetadata(meta, fileName, null, this);
        String mime = KbTikaSupport.detectMime(file, fileName);
        if (mime != null && !mime.isBlank()) {
            meta.put(KbParsedDocument.META_MIME_TYPE, mime);
        }
        meta.put(META_CHAPTER_COUNT, String.valueOf(chapters.size()));
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, String.valueOf(finalBlocks.size()));
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(text.length()));
        if (trunc.truncated()) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }

        if (log.isInfoEnabled()) {
            log.info("KbEpubParser: 解析完成 file={} 章数={} 有正文章节={} 总字符={} 总码点={}"
                    + " section块={} 码点覆盖率={}%",
                fileName, chapters.size(), chaptersWithText, text.length(), totalCp,
                finalBlocks.size(), String.format(Locale.ROOT, "%.1f", coverage));
        }
        // images 恒空：本批不抽图（KbMarkdownImageResolver 兜底）
        return new KbParsedDocument(text, List.of(), meta, finalBlocks);
    }

    // ════════════════════════════════════════════════════════════════════
    // zip → container.xml → OPF → spine
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读 spine 顺序的章节字节（只读一次 zip，章节内容随结果带出）。
     *
     * @throws KbParseException 坏 zip / 缺 container.xml / 缺 rootfile / OPF 非法 / spine 为空 /
     *                          spine 指向的条目不存在
     */
    private static List<Chapter> readSpine(Path file, String fileName) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new KbParseException("EPUB 文件不存在或不是普通文件: " + file);
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String opfPath = readRootfilePath(zip, fileName);
            byte[] opf = readEntryBytes(zip, opfPath, fileName);
            Package pkg = parsePackage(opf, opfPath, fileName);
            if (pkg.spineIds().isEmpty()) {
                throw new KbParseException("EPUB spine 为空（OPF 里没有 <itemref>，"
                    + "无法确定阅读顺序）: " + fileName);
            }

            List<Chapter> chapters = new ArrayList<>();
            int spineIndex = 0;
            for (String idref : pkg.spineIds()) {
                spineIndex++;
                ManifestItem item = pkg.manifest().get(idref);
                if (item == null) {
                    throw new KbParseException("EPUB spine 第 " + spineIndex + " 项 idref=\"" + idref
                        + "\" 在 manifest 中不存在（包结构损坏）: " + fileName);
                }
                if (!isXhtml(item)) {
                    // spine 也可能排入图片等非文本项：不是章节，跳过但不影响后续序号
                    continue;
                }
                ZipEntry entry = findEntry(zip, item.path());
                if (entry == null) {
                    throw new KbParseException("EPUB spine 第 " + spineIndex + " 项指向的文件不存在: "
                        + item.path() + "（文件=" + fileName + "）");
                }
                chapters.add(new Chapter(spineIndex, item.path(),
                    readEntryBytes(zip, entry, fileName)));
            }
            if (chapters.isEmpty()) {
                throw new KbParseException("EPUB spine 里没有可解析的 XHTML 章节（spine 项="
                    + pkg.spineIds().size() + "）: " + fileName);
            }
            return chapters;
        } catch (ZipException e) {
            // 空文件（0 字节）与坏 zip 都在这里：ZipFile 构造即抛
            throw new KbParseException("不是合法的 EPUB 压缩包（zip 结构损坏或文件为空）: "
                + fileName + " 原因=" + e.getMessage(), e);
        } catch (IOException e) {
            throw new KbParseException("读取 EPUB 文件失败: " + fileName + " 原因=" + e.getMessage(), e);
        }
    }

    /**
     * 从 {@code META-INF/container.xml} 取第一个 rootfile 的 {@code full-path}。
     *
     * @throws KbParseException 缺 container.xml / 没有 rootfile / rootfile 没有 full-path
     */
    private static String readRootfilePath(ZipFile zip, String fileName) {
        ZipEntry container = findEntry(zip, CONTAINER_ENTRY);
        if (container == null) {
            throw new KbParseException("EPUB 缺少 " + CONTAINER_ENTRY
                + "（不是 EPUB 文件或包已损坏）: " + fileName);
        }
        Document doc = parseXml(readEntryBytes(zip, container, fileName),
            "EPUB container.xml", fileName);
        List<Element> rootfiles = elementsNamed(doc, "rootfile");
        if (rootfiles.isEmpty()) {
            throw new KbParseException("EPUB container.xml 里没有 <rootfile> 元素: " + fileName);
        }
        String fullPath = rootfiles.get(0).getAttribute("full-path").trim();
        if (fullPath.isEmpty()) {
            throw new KbParseException("EPUB container.xml 的 <rootfile> 缺少 full-path 属性: " + fileName);
        }
        return normalizeZipPath(resolveZipPath("", fullPath));
    }

    /**
     * 解析 OPF：manifest（id → 路径/media-type）+ spine（idref 有序）。
     *
     * @throws KbParseException OPF 不是合法 XML
     */
    private static Package parsePackage(byte[] payload, String opfPath, String fileName) {
        Document doc = parseXml(payload, "EPUB OPF(" + opfPath + ")", fileName);
        String baseDir = dirOf(opfPath);

        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        for (Element manifestElement : elementsNamed(doc, "manifest")) {
            for (Element item : childElements(manifestElement, "item")) {
                String id = item.getAttribute("id").trim();
                String href = item.getAttribute("href").trim();
                if (id.isEmpty() || href.isEmpty()) {
                    continue;
                }
                manifest.put(id, new ManifestItem(
                    resolveZipPath(baseDir, href), item.getAttribute("media-type")));
            }
        }

        List<String> spineIds = new ArrayList<>();
        for (Element spineElement : elementsNamed(doc, "spine")) {
            for (Element itemref : childElements(spineElement, "itemref")) {
                String idref = itemref.getAttribute("idref").trim();
                if (idref.isEmpty()) {
                    continue;
                }
                // linear="no" 的项仍是 spine 的一部分（例如脚注页），源站也按 spine 全收
                spineIds.add(idref);
            }
        }
        return new Package(manifest, spineIds);
    }

    /** 章节是否按正文处理：优先 media-type；media-type 缺失/异常时按扩展名兜底。 */
    private static boolean isXhtml(ManifestItem item) {
        String mime = KbParsers.normalizeMime(item.mediaType());
        if (!mime.isEmpty()) {
            return XHTML_MIME_TYPES.contains(mime);
        }
        String path = item.path().toLowerCase(Locale.ROOT);
        return path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm");
    }

    // ════════════════════════════════════════════════════════════════════
    // 章节 → Markdown（复用 KbHtmlParser 的事件流转换）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 一章 XHTML → Markdown + 标题。
     *
     * <p>复用 {@link KbHtmlParser.HtmlMarkdownHandler}（包内可见，⛔ 未改 {@link KbHtmlParser} 的公开契约）：
     * 标题 / 表格 / 链接 / 去噪与 HTML 解析器逐字一致。Tika 的 {@link Metadata} 显式设 Content-Type，
     * 因为章节来自 zip 流、没有文件名后缀可给探测器。
     */
    private static RenderedChapter renderChapter(Chapter chapter, Path sourceFile) {
        Metadata md = new Metadata();
        md.set(Metadata.CONTENT_TYPE, mimeOf(chapter.path()));
        md.set(TikaCoreProperties.RESOURCE_NAME_KEY, baseName(chapter.path()));
        KbHtmlParser.HtmlMarkdownHandler handler = new KbHtmlParser.HtmlMarkdownHandler();
        try (InputStream in = new ByteArrayInputStream(chapter.bytes())) {
            KbTikaSupport.parser().parse(in, handler, md, new ParseContext());
        } catch (Exception e) {
            throw KbTikaSupport.wrap("EPUB 章节解析", sourceFile, e);
        }
        String title = cleanTitle(KbTikaSupport.titleOf(md));
        String markdown = handler.markdown();
        if (title.isEmpty()) {
            title = firstH1(markdown);
        }
        return new RenderedChapter(markdown, title);
    }

    /** 章节 MIME · 全部走 XHTML 通道（EPUB 的 html 也是 XHTML 语法）。 */
    private static String mimeOf(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".html")
            || path.toLowerCase(Locale.ROOT).endsWith(".htm")
            ? "text/html"
            : "application/xhtml+xml";
    }

    /** 第一个 {@code h1}（Markdown {@code # 标题}）· 章节 {@code <title>} 缺失时的兜底。 */
    private static String firstH1(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        for (String line : markdown.split("\n")) {
            String s = line.strip();
            if (s.startsWith("# ") && !s.startsWith("## ")) {
                return cleanTitle(s.substring(2));
            }
        }
        return "";
    }

    /** 标题清洗：去首尾空白，内部换行/制表压成单空格（标题里换行会把一行标题断成两行）。 */
    private static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = raw.strip();
        return stripped.isEmpty() ? "" : stripped.replaceAll("\\s+", " ");
    }

    /** 章节的 section locator · {@code section} 是 1-based spine 序号（源站同口径）。 */
    private static KbSourceLocator sectionLocator(int chapterIndex, String title) {
        return new KbSourceLocator(
            KbSourceLocator.TYPE_SECTION, null, false, null, null, null, null, null, null,
            null, null, null, null, null, null, null, chapterIndex,
            title == null || title.isEmpty() ? null : title, null);
    }

    /**
     * 截断后修正源块：丢弃起点已越界的块，末端夹到新文本长度。
     *
     * <p>⛔ 不保留「起点在截断点之后」的块：那会让 locator 指向不存在的文本（KbSourceIndex 虽会
     * 丢弃无效块，但丢在索引层不如在产出层就说清楚）。
     */
    private static List<KbSourceBlock> clampBlocks(List<KbSourceBlock> blocks, int cpLength) {
        List<KbSourceBlock> clamped = new ArrayList<>(blocks.size());
        for (KbSourceBlock block : blocks) {
            if (block.start() >= cpLength) {
                continue;
            }
            int end = Math.min(block.end(), cpLength);
            if (end > block.start()) {
                clamped.add(new KbSourceBlock(block.start(), end, block.locator()));
            }
        }
        return clamped;
    }

    // ════════════════════════════════════════════════════════════════════
    // zip / XML 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 精确名优先；再退化为大小写不敏感扫描（某些打包工具会改大小写）。 */
    private static ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry exact = zip.getEntry(name);
        if (exact != null) {
            return exact;
        }
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (!candidate.isDirectory() && name.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] readEntryBytes(ZipFile zip, String name, String fileName) {
        ZipEntry entry = findEntry(zip, name);
        if (entry == null) {
            throw new KbParseException("EPUB 包内缺少条目: " + name + "（文件=" + fileName + "）");
        }
        return readEntryBytes(zip, entry, fileName);
    }

    /**
     * 读条目内容（有界）· 声明大小与实际读取都卡 {@link #MAX_ENTRY_BYTES}。
     *
     * <p>⚠️ {@code ZipEntry.getSize()} 是 zip 头里的<b>声明值</b>，可以被伪造；故读取时再多读 1 字节
     * 做二次判定，防「声明 1KB、实际解出 1GB」的 zip 炸弹。
     */
    private static byte[] readEntryBytes(ZipFile zip, ZipEntry entry, String fileName) {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] payload = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
            if (payload.length > MAX_ENTRY_BYTES) {
                throw new KbParseException("EPUB 条目超过 " + MAX_ENTRY_BYTES + " 字节上限: "
                    + entry.getName() + "（文件=" + fileName + "）");
            }
            if (payload.length == 0) {
                throw new KbParseException("EPUB 条目为空（0 字节）: " + entry.getName()
                    + "（文件=" + fileName + "）");
            }
            return payload;
        } catch (ZipException e) {
            throw new KbParseException("EPUB 条目无法解压（可能被加密）: " + entry.getName()
                + "（文件=" + fileName + " 原因=" + e.getMessage() + "）", e);
        } catch (IOException e) {
            throw new KbParseException("读取 EPUB 条目失败: " + entry.getName()
                + "（文件=" + fileName + " 原因=" + e.getMessage() + "）", e);
        }
    }

    /**
     * 安全 XML 解析：禁 DOCTYPE 与外部实体（OPF/container 不需要 DTD，允许外部实体会被恶意 EPUB
     * 用来读本机文件 / XXE）。
     */
    private static Document parseXml(byte[] payload, String what, String fileName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(payload));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new KbParseException(what + " 不是合法 XML（文件=" + fileName
                + " 原因=" + e.getMessage() + "）", e);
        }
    }

    /**
     * 全文档按本地名找元素（顺序 = 文档顺序）。
     *
     * <p>先走命名空间通配（OPF 的默认命名空间），取不到再按限定名兜底（有些手改包直接不声明命名空间）。
     */
    private static List<Element> elementsNamed(Document doc, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                out.add(element);
            }
        }
        if (out.isEmpty()) {
            NodeList plain = doc.getElementsByTagName(localName);
            for (int i = 0; i < plain.getLength(); i++) {
                if (plain.item(i) instanceof Element element) {
                    out.add(element);
                }
            }
        }
        return out;
    }

    /** 直接子元素（按本地名过滤；只认元素节点，⛔ 不递归）。 */
    private static List<Element> childElements(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(localNameOf(element))) {
                result.add(element);
            }
        }
        return result;
    }

    /** 元素本地名（去命名空间前缀）；非命名空间模式下 {@code getLocalName()} 为 null。 */
    private static String localNameOf(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String name = element.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /**
     * 解析 zip 内相对路径（相对 OPF 所在目录）· 处理 {@code ../}、{@code ./}、片段、查询串与
     * 百分号编码（含中文文件名的 UTF-8 编码）。
     */
    private static String resolveZipPath(String baseDir, String href) {
        String h = href == null ? "" : href.trim();
        int hash = h.indexOf('#');
        if (hash >= 0) {
            h = h.substring(0, hash);
        }
        int query = h.indexOf('?');
        if (query >= 0) {
            h = h.substring(0, query);
        }
        h = percentDecode(h).replace('\\', '/');
        if (h.startsWith("/")) {
            h = h.substring(1);
        }
        String combined = baseDir == null || baseDir.isEmpty() ? h : baseDir + "/" + h;
        return normalizeZipPath(combined);
    }

    /** 折叠 {@code .}/{@code ..}/空段（{@code ..} 越过根时丢弃，⛔ 不生成 {@code ../} 条目名）。 */
    private static String normalizeZipPath(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            parts.addLast(segment);
        }
        return String.join("/", parts);
    }

    /** 路径所在目录（无 {@code /} 时为空串 = zip 根）。 */
    private static String dirOf(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** 路径末段（给 Tika 当 RESOURCE_NAME_KEY，只为诊断与探测提示）。 */
    private static String baseName(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? (path == null ? "" : path) : path.substring(slash + 1);
    }

    /**
     * 百分号解码（href 里的非 ASCII 通常按 UTF-8 字节转义成 {@code %E7%AC%AC}）。
     *
     * <p>⛔ 不能逐字节当 Latin-1 转 char：中文文件名会变成乱码，zip 里根本找不到该条目。
     */
    private static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s == null ? "" : s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes.write(encoded, 0, encoded.length);
            i++;
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── 内部数据载体 ──

    /** spine 内一个 XHTML 章节（序号 1-based + zip 内路径 + 原始字节）。 */
    private record Chapter(int spineIndex, String path, byte[] bytes) {
    }

    /** 一章的转写结果（Markdown + 标题，标题可能为空串）。 */
    private record RenderedChapter(String markdown, String title) {
    }

    /** manifest 的一项：zip 内路径 + media-type。 */
    private record ManifestItem(String path, String mediaType) {
    }

    /** OPF 的目录结构（manifest 映射 + spine 顺序）。 */
    private record Package(Map<String, ManifestItem> manifest, List<String> spineIds) {
    }
}

package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * XMind 解析器 · {@code .xmind}（zip 容器）→ Markdown 大纲。
 *
 * <p><b>为什么必须有它</b>：上传白名单（{@code KnowledgeService.SUPPORTED_EXTENSIONS}）已收
 * {@code xmind}，但此前没有任何解析器认领该扩展名 —— 上传成功、解析必失败。
 *
 * <p><b>两种容器格式</b>（对齐源站 {@code docreader/parser/xmind_parser.py}）：
 * <ol>
 *   <li>{@code content.json} —— XMind Zen / 2020+：顶层是 sheet <b>数组</b>，每项
 *       {@code {title, rootTopic:{title, children:{attached:[…], detached:[…], summary:[…],
 *       callout:[…]}}}}，子主题递归同构。</li>
 *   <li>{@code content.xml} —— XMind 8：{@code <xmap-content><sheet><title/><topic><title/>
 *       <children><topics><topic>…</topic></topics></children></topic></sheet></xmap-content>}，
 *       {@code <topic>} 递归。</li>
 * </ol>
 * 优先 {@code content.json}（新版格式），没有才回落到 {@code content.xml}。
 *
 * <p><b>输出层级</b>：sheet 标题作 {@code #}，rootTopic 起逐层 {@code ##}、{@code ###}……
 * Markdown 标题最多 6 级（{@code ######}），第 7 级起改用「每级 2 空格缩进 + {@code -}」的列表
 * —— {@code #######} 在渲染器里只是一段普通文本，层级信息会静默丢失。
 * 无标题的 topic 不产出空标题行，其子主题顶到同一级（与源站 {@code _render_topic} 同口径）。
 *
 * <p><b>备注（notes）</b>：对齐源站 {@code xmind_parser.py} 的 {@code _render_topic} —— 备注挂在
 * <b>topic 自身</b>（XML：{@code <topic><notes><plain>…</plain></notes>}；JSON Zen：
 * {@code {notes:{plain:{content:"…"}}}}），渲染成每条一行、以 {@code > } 开头的 Markdown 引用块，
 * 位置在该主题标题行之后、子主题之前。备注行逐行加前缀（不是整体一块），空行只写 {@code >}
 * （无尾随空格）；整块比所在主题再深一级缩进。备注 {@code strip()} 后为空 ⇒ 一个字都不输出
 * （⛔ 不留空 {@code > }）；主题标题为空时备注也不输出（源站同样嵌在 {@code if topic.title:} 内）。
 *
 * <p><b>失败语义</b>：空文件 / 坏 zip / 缺 content 条目 / 无任何主题 ⇒ {@link KbParseException}
 * （⛔ 不返回空文本 —— 空文本会被上层当「解析成功但无内容」入库，用户永远查不到且无错误提示）。
 *
 * <p><b>⛔ 不伪造定位</b>：zip 内 JSON/XML 是整份结构化载荷，与最终 Markdown 的字符区间无法
 * 一一对应，故 {@code sourceBlocks} 恒为空列表。
 */
@Component
@Order(35)
public class KbXmindParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbXmindParser.class);

    /** 内容条目上限（对齐源站 {@code MAX_CONTENT_BYTES}）：防 zip 炸弹把堆打满。 */
    static final long MAX_CONTENT_BYTES = 32L * 1024 * 1024;

    /** 单文档文本上限（与其它解析器统一）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /** Markdown 标题最深层级（{@code ######}）。超过改用缩进列表。 */
    static final int MAX_HEADING_LEVEL = 6;

    /** 本解析器唯一支持的扩展名（小写、不带点）。 */
    private static final Set<String> EXTENSIONS = Set.of("xmind");

    /**
     * XMind JSON schema 里装着子主题的四组键。
     *
     * <p>不止 {@code attached}：{@code detached}（浮动主题）/ {@code summary}（摘要）/
     * {@code callout}（标注）同样承载用户写的文字，源站解析器也一并收（{@code _topic_from_json}）。
     */
    private static final List<String> CHILD_GROUPS =
        List.of("attached", "detached", "summary", "callout");

    // ── metadata 键（本解析器特有；其余用 KbParsedDocument 常量） ──

    /** 实际使用的容器格式：{@code json} / {@code xml}。 */
    static final String META_CONTENT_FORMAT = "xmindContentFormat";
    /** 画布（sheet）数量。 */
    static final String META_SHEET_COUNT = "sheetCount";
    /** 渲染出的主题数（不含无标题节点）。 */
    static final String META_TOPIC_COUNT = "topicCount";
    /** 最深层级（1 = 只有根主题；超过 6 时对应缩进列表）。 */
    static final String META_MAX_LEVEL = "maxLevel";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean supports(String fileName, String mimeType) {
        if ("xmind".equals(KbParsers.extensionOf(fileName))) {
            return true;
        }
        // 扩展名缺失/被改坏时的兜底：只认 MIME 里带 xmind 的那一类；
        // ⛔ 不能认 application/zip —— 那会把所有普通压缩包都抢过来。
        return KbParsers.normalizeMime(mimeType).contains("xmind");
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String displayName() {
        return "内置 XMind 解析";
    }

    /** 引擎族名别名 · 源站内置引擎为 {@code builtin}（另收 {@code xmind} 便于按类型点名）。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("builtin", "xmind");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString();
        Content content = readContent(file, fileName);
        List<Sheet> sheets = "json".equals(content.format())
            ? parseJson(content.payload(), fileName)
            : parseXml(content.payload(), fileName);

        RenderResult rendered = render(sheets);
        if (rendered.text().isEmpty()) {
            throw new KbParseException("XMind 文件不含任何可解析主题（sheet 数=" + sheets.size()
                + "，可能只有空白画布或主题标题全为空）: " + fileName);
        }

        KbParsers.Truncation trunc = KbParsers.truncate(rendered.text(), MAX_TEXT_CHARS);
        String text = trunc.text();

        Map<String, String> meta = KbParsers.newMeta();
        KbParsers.fillCommonMetadata(meta, fileName, null, this);
        meta.put(META_CONTENT_FORMAT, content.format());
        meta.put(META_SHEET_COUNT, String.valueOf(rendered.sheetCount()));
        meta.put(META_TOPIC_COUNT, String.valueOf(rendered.topicCount()));
        meta.put(META_MAX_LEVEL, String.valueOf(rendered.maxLevel()));
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(text.length()));
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, "0");
        if (trunc.truncated()) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }

        if (log.isInfoEnabled()) {
            log.info("KbXmindParser: 解析完成 file={} format={} sheets={} topics={}"
                    + " maxLevel={} chars={} truncated={}",
                fileName, content.format(), rendered.sheetCount(), rendered.topicCount(),
                rendered.maxLevel(), text.length(), trunc.truncated());
        }
        // images / sourceBlocks 恒空：XMind 的图片是内嵌附件，本解析器不导出；
        // 文本来自结构化载荷改写，无法映射回原字节区间。
        return new KbParsedDocument(text, List.of(), meta, List.of());
    }

    // ── zip 读取 ──

    /**
     * 打开 zip 并取出 {@code content.json}（优先）或 {@code content.xml}。
     *
     * @throws KbParseException 文件不是合法 zip / 缺两个 content 条目 / 条目为空或超上限
     */
    private static Content readContent(Path file, String fileName) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new KbParseException("XMind 文件不存在或不是普通文件: " + file);
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry jsonEntry = findEntry(zip, "content.json");
            ZipEntry entry = jsonEntry != null ? jsonEntry : findEntry(zip, "content.xml");
            if (entry == null) {
                throw new KbParseException("XMind 压缩包内缺少 content.json / content.xml"
                    + "（不是 XMind 文件或包已损坏）: " + fileName);
            }
            String format = jsonEntry != null ? "json" : "xml";
            return new Content(format, readEntry(zip, entry, fileName));
        } catch (ZipException e) {
            // 空文件（0 字节）与坏 zip 都在这里：ZipFile 构造即抛
            throw new KbParseException("不是合法的 XMind 压缩包（zip 结构损坏或文件为空）: "
                + fileName + " 原因=" + e.getMessage(), e);
        } catch (IOException e) {
            throw new KbParseException("读取 XMind 文件失败: " + fileName + " 原因=" + e.getMessage(), e);
        }
    }

    /** 精确名优先；再退化为大小写不敏感扫描（某些导出工具会写成 {@code Content.json}）。 */
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

    /**
     * 读条目内容（有界）· 声明大小与实际读取都卡 {@link #MAX_CONTENT_BYTES}。
     *
     * <p>⚠️ {@code ZipEntry.getSize()} 是 zip 头里的<b>声明值</b>，可以被伪造；故读的时候
     * 再多读 1 字节做二次判定，防止「声明 1KB、实际解出 1GB」的 zip 炸弹。
     */
    private static byte[] readEntry(ZipFile zip, ZipEntry entry, String fileName) throws IOException {
        if (entry.getSize() > MAX_CONTENT_BYTES) {
            throw new KbParseException("XMind 内容条目超过 " + MAX_CONTENT_BYTES
                + " 字节上限（实际声明=" + entry.getSize() + "）: " + fileName);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] payload = in.readNBytes((int) MAX_CONTENT_BYTES + 1);
            if (payload.length > MAX_CONTENT_BYTES) {
                throw new KbParseException("XMind 内容条目超过 " + MAX_CONTENT_BYTES
                    + " 字节上限: " + fileName);
            }
            if (payload.length == 0) {
                throw new KbParseException("XMind 内容条目为空（content 文件 0 字节）: " + fileName);
            }
            return payload;
        } catch (ZipException e) {
            throw new KbParseException("XMind 内容条目无法解压（可能被加密）: "
                + fileName + " 原因=" + e.getMessage(), e);
        }
    }

    // ── content.json（XMind Zen / 2020+） ──

    /**
     * 解析 {@code content.json}：顶层必须是 sheet 数组。
     *
     * <p>⛔ 顶层不是数组时直接失败，而不是「当 sheet 取一取看」：被解成对象的文件多半是
     * 别的东西（例如 {@code manifest.json}），继续往下走只会产出一个空大纲。
     */
    private static List<Sheet> parseJson(byte[] payload, String fileName) {
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (IOException e) {
            throw new KbParseException("XMind content.json 不是合法 JSON: "
                + fileName + " 原因=" + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new KbParseException("XMind content.json 结构非法（顶层应为 sheet 数组）: " + fileName);
        }
        List<Sheet> sheets = new ArrayList<>();
        for (JsonNode sheetNode : root) {
            if (sheetNode == null || !sheetNode.isObject()) {
                continue;
            }
            sheets.add(new Sheet(
                cleanTitle(textOf(sheetNode.get("title"))),
                topicFromJson(sheetNode.get("rootTopic"))));
        }
        return sheets;
    }

    /** JSON topic 递归：{@code title} + {@code notes.plain.content} + {@code children.*}。 */
    private static Topic topicFromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String title = cleanTitle(textOf(node.get("title")));
        String note = noteFromJson(node);
        List<Topic> children = new ArrayList<>();
        JsonNode groups = node.get("children");
        if (groups != null && groups.isObject()) {
            for (String groupName : CHILD_GROUPS) {
                JsonNode group = groups.get(groupName);
                if (group == null || !group.isArray()) {
                    continue;
                }
                for (JsonNode childNode : group) {
                    Topic child = topicFromJson(childNode);
                    if (child != null) {
                        children.add(child);
                    }
                }
            }
        }
        return new Topic(title, note, children);
    }

    /**
     * 取 JSON 备注（XMind Zen / 2020+）：{@code notes.plain.content}。
     *
     * <p>只认文本节点（源站 {@code _clean_text} 对非字符串返回空串）；{@code notes} / {@code plain}
     * 缺失或类型不对 ⇒ 空串。⛔ 不读 {@code notes.realHTML} —— 源站只读 plain。
     */
    private static String noteFromJson(JsonNode node) {
        JsonNode notes = node.get("notes");
        if (notes == null || !notes.isObject()) {
            return "";
        }
        JsonNode plain = notes.get("plain");
        if (plain == null || !plain.isObject()) {
            return "";
        }
        return cleanNote(textOf(plain.get("content")));
    }

    /** 取 JSON 文本节点（非文本 → 空串，⛔ 不用 {@code asText()} 把数字/布尔也当标题）。 */
    private static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    // ── content.xml（XMind 8） ──

    /**
     * 解析 {@code content.xml}：{@code <sheet><title/><topic>…</topic></sheet>}。
     *
     * <p><b>安全</b>：禁用 DOCTYPE 与外部实体（XMind 不需要 DTD；允许外部实体会被恶意文件
     * 用来读本机文件 / XXE）。按<b>本地名</b>匹配元素，兼容带 {@code urn:xmind} 命名空间与
     * 不带命名空间的两种导出。
     */
    private static List<Sheet> parseXml(byte[] payload, String fileName) {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            doc = builder.parse(new ByteArrayInputStream(payload));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new KbParseException("XMind content.xml 解析失败: "
                + fileName + " 原因=" + e.getMessage(), e);
        }

        List<Sheet> sheets = new ArrayList<>();
        Element root = doc == null ? null : doc.getDocumentElement();
        if (root != null) {
            for (Element sheetElement : childElements(root, "sheet")) {
                sheets.add(new Sheet(
                    cleanTitle(textOf(firstChild(sheetElement, "title"))),
                    topicFromXml(firstChild(sheetElement, "topic"))));
            }
        }
        return sheets;
    }

    /** XML topic 递归：{@code <title>} + {@code <notes><plain>…} + {@code <children><topics><topic>…}。 */
    private static Topic topicFromXml(Element element) {
        if (element == null) {
            return null;
        }
        String title = cleanTitle(textOf(firstChild(element, "title")));
        String note = noteFromXml(element);
        List<Topic> children = new ArrayList<>();
        for (Element childrenElement : childElements(element, "children")) {
            for (Element topicsElement : childElements(childrenElement, "topics")) {
                for (Element topicElement : childElements(topicsElement, "topic")) {
                    Topic child = topicFromXml(topicElement);
                    if (child != null) {
                        children.add(child);
                    }
                }
            }
        }
        return new Topic(title, note, children);
    }

    /**
     * 取 XML 备注（XMind 8）：{@code <topic><notes><plain>文本</plain></notes></topic>}。
     *
     * <p>只认直系子元素（与源站 {@code _first_child} 同口径）；{@code <notes>} 里另有
     * {@code <html>} 富文本分支，源站不读，这里也不读。{@code getTextContent()} 已含子节点文本。
     */
    private static String noteFromXml(Element element) {
        return cleanNote(textOf(firstChild(firstChild(element, "notes"), "plain")));
    }

    /** 直接子元素（按本地名过滤；只认元素节点）。 */
    private static List<Element> childElements(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(localNameOf(element))) {
                result.add(element);
            }
        }
        return result;
    }

    /** 第一个匹配的直系子元素；没有 → null。 */
    private static Element firstChild(Element parent, String localName) {
        List<Element> children = childElements(parent, localName);
        return children.isEmpty() ? null : children.get(0);
    }

    /** 元素的本地名（去命名空间前缀）；{@code getLocalName()} 在非命名空间模式下为 null。 */
    private static String localNameOf(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String name = element.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** 元素内全部文本（{@code getTextContent()} 已含子节点文本，⛔ 不逐层拼）。 */
    private static String textOf(Element element) {
        return element == null ? "" : element.getTextContent();
    }

    // ── 渲染 ──

    /**
     * 把 sheets 渲染成 Markdown 大纲。
     *
     * <p>sheet 标题（非空时）占 {@code #}，rootTopic 从 {@code ##} 开始；sheet 无标题时
     * rootTopic 直接占 {@code #}。sheet 内一个带标题的主题都没有时整块跳过 ——
     * 只剩一行画布标题的「空画布」对检索毫无价值，反而会挤占分块。
     */
    private static RenderResult render(List<Sheet> sheets) {
        List<String> sections = new ArrayList<>();
        int sheetCount = 0;
        int topicCount = 0;
        int maxLevel = 0;
        for (Sheet sheet : sheets) {
            if (sheet.root() == null) {
                continue;
            }
            Stats stats = new Stats();
            List<String> lines = new ArrayList<>();
            int startLevel = 1;
            if (!sheet.title().isEmpty()) {
                lines.add("# " + sheet.title());
                lines.add("");
                startLevel = 2;
            }
            renderTopic(sheet.root(), startLevel, lines, stats);
            if (stats.topics == 0) {
                continue;
            }
            sections.add(String.join("\n", lines).strip());
            sheetCount++;
            topicCount += stats.topics;
            maxLevel = Math.max(maxLevel, stats.maxLevel);
        }
        return new RenderResult(String.join("\n\n", sections), sheetCount, topicCount, maxLevel);
    }

    /**
     * 递归渲染一个主题。
     *
     * <p>无标题主题不产出空标题行，其子主题顶到同一级（源站 {@code _render_topic} 同口径）：
     * XMind 允许「只有 children 没有 title」的中间节点，若按层级照发标题，会多出一串空
     * {@code ##} 行。
     *
     * <p>备注块紧跟在标题行之后、子主题之前；主题无标题时备注也不输出（源站同样把 note 块
     * 嵌在 {@code if topic.title:} 内）。
     */
    private static void renderTopic(Topic topic, int level, List<String> lines, Stats stats) {
        if (topic == null) {
            return;
        }
        int childLevel = level;
        if (!topic.title().isEmpty()) {
            lines.add(outlineLine(level, topic.title()));
            stats.topics++;
            stats.maxLevel = Math.max(stats.maxLevel, level);
            childLevel = level + 1;
            addNoteLines(topic.note(), level, lines);
        }
        for (Topic child : topic.children()) {
            renderTopic(child, childLevel, lines, stats);
        }
    }

    /**
     * 备注 → 引用块（对齐源站 {@code _render_topic}）。
     *
     * <p>逐行输出（不是整体一块）：每条非空行 = 缩进 + {@code "> " + 该行去首尾空白}；空白行 =
     * 缩进 + {@code ">"}（保持源站的「无尾随空格」形态）。备注已 strip 过，故空备注在此直接
     * 返回，⛔ 不留空 {@code > }。
     */
    private static void addNoteLines(String note, int level, List<String> lines) {
        if (note == null || note.isEmpty()) {
            return;
        }
        String indent = noteIndent(level);
        for (String rawLine : note.split("\\R")) {
            String normalized = rawLine.strip();
            lines.add(normalized.isEmpty() ? indent + ">" : indent + "> " + normalized);
        }
    }

    /**
     * 备注块的缩进 = 该主题自身行的缩进再深一级（源站 {@code '  ' * child_depth}）。
     *
     * <p>本解析器标题行不带缩进，故标题层级下备注固定 2 空格；第 7 级起主题自身已是
     * {@code 2 空格 × 超出级数} 的列表项，备注再加 2 空格。（⛔ 不能对标题层级也按
     * {@code 2 空格 × level} 缩进：4 空格起在 Markdown 里会被当成缩进代码块。）
     */
    private static String noteIndent(int level) {
        if (level <= MAX_HEADING_LEVEL) {
            return "  ";
        }
        return "  ".repeat(level - MAX_HEADING_LEVEL + 1);
    }

    /**
     * 单个主题 → 一行大纲。
     *
     * <p>标题 ≤ {@link #MAX_HEADING_LEVEL} 用 {@code #}；更深的层级改成「2 空格 × 超出级数 +
     * {@code - }」的缩进列表项（Markdown 没有 7 级标题）。
     *
     * @param level 1 起的层级（1 = {@code #}）
     * @param title 已清洗的标题
     */
    static String outlineLine(int level, String title) {
        if (level <= MAX_HEADING_LEVEL) {
            return "#".repeat(level) + " " + title;
        }
        return "  ".repeat(level - MAX_HEADING_LEVEL) + "- " + title;
    }

    /**
     * 清洗主题标题：去首尾空白，内部换行/制表符压成单空格。
     *
     * <p>标题里的换行会把一行标题断成两行 —— 第一行变成标题、后半截变成正文段，层级错位；
     * 压成空格是最小侵入的修法（内容本身不改字）。
     */
    static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        return stripped.replaceAll("\\s+", " ");
    }

    /**
     * 清洗备注：只去首尾空白，⛔ 不压内部换行。
     *
     * <p>与 {@link #cleanTitle} 相反 —— 备注的多行结构正是要保留并逐行转成 {@code > } 行的；
     * 压成单空格会把用户写的条目挤成一整段。
     */
    static String cleanNote(String raw) {
        return raw == null ? "" : raw.strip();
    }

    // ── 内部数据载体 ──

    /** zip 里的内容条目（格式 + 原始字节）。 */
    private record Content(String format, byte[] payload) {
    }

    /** 一张画布：标题 + 根主题（可能没有）。 */
    private record Sheet(String title, Topic root) {
    }

    /** 一个主题：标题 + 备注 + 子主题（有序）。 */
    private record Topic(String title, String note, List<Topic> children) {

        Topic {
            title = title == null ? "" : title;
            note = note == null ? "" : note;
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /** 渲染统计（主题数 / 最深层级）。 */
    private static final class Stats {

        int topics;
        int maxLevel;
    }

    /** 渲染产物（正文 + 统计）。 */
    private record RenderResult(String text, int sheetCount, int topicCount, int maxLevel) {
    }
}

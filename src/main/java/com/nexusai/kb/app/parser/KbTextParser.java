package com.nexusai.kb.app.parser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.sax.ToTextContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 纯文本类解析器 · .txt / .md / .markdown / .html / .htm / .json / .csv / .log / .xml。
 *
 * <p><b>行为</b>：直接用 Tika {@code AutoDetectParser} 抽文本，普通文本<b>不做任何改写</b>，
 * 编码自动探测（Tika 带 juniversalchardet，GBK 文本也能出正确中文）。
 *
 * <p><b>两个例外：CSV/TSV → Markdown 表格，JSON → 围栏代码块</b>（对齐源站 {@code simple} 引擎的
 * {@code csvToMarkdown} / {@code jsonToMarkdown}）。理由：Tika 对 CSV<span>只有在嗅探出稳定列数时</span>
 * 才输出 {@code <table>}，常见的中文小表会被当纯文本拍成一行行逗号串 —— 表格结构丢失后，
 * 下游 {@code KbTextSplitter} 的「表格不作切分点」保护失效，检索「某标段金额」会跨块查不到。
 * 见 {@link #convertStructured}。
 *
 * <p><b>为什么其余格式用 {@link ToTextContentHandler} 而不是 {@link KbMarkdownHandler}</b>：
 * 这几类文件本身已经是「作者写的文本」，Markdown 语法（{@code #} 标题、{@code |} 表格）是内容本身。
 * 若再过一遍 HTML→Markdown 转换，{@code .md} 会被当成 HTML 转义、{@code .csv} 的逗号会丢结构。
 * 只有 Office / epub / mhtml 这类容器格式才需要「把 Tika 拍平的结构还原成 Markdown」。
 *
 * <p><b>回落</b>：本类是兜底解析器（{@link #supports} 对未知扩展名也返回 true），保证「任何上传都能
 * 至少拿到文本」；真正二进制的未知格式会被 Tika 抽出少量可见字符串，好过直接失败。
 */
@Component
@Order(30)
public class KbTextParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbTextParser.class);

    /** 单文档文本上限（派单书 §3.3 对所有解析器统一）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /** 结构化转换是否生效（{@code true} = CSV/TSV/JSON 已转成 Markdown 结构）。 */
    static final String META_STRUCTURED = "structured";

    /** 本解析器明确支持的扩展名（小写、不带点）。 */
    private static final Set<String> EXTENSIONS = Set.of(
        "txt", "text", "md", "markdown", "html", "htm", "xhtml",
        "json", "csv", "tsv", "log", "xml", "yaml", "yml", "properties", "sql", "ini", "conf"
    );

    /** 明确属于其它解析器的扩展名 · 兜底时必须让位（否则 PDF 会被当文本抽出乱码）。 */
    private static final Set<String> OTHER_PARSER_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "docm", "xlsm", "pptm", "rtf"
    );

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null && OTHER_PARSER_EXTENSIONS.contains(ext)) {
            return false;
        }
        if (ext != null && EXTENSIONS.contains(ext)) {
            return true;
        }
        // 兜底：已知是文本 MIME 但扩展名陌生 → 交给 Tika 判
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("text/");
    }

    /**
     * 能力声明 · 只报 {@code EXTENSIONS}，<b>不含</b> {@link #OTHER_PARSER_EXTENSIONS}。
     *
     * <p>后者是「让位列表」（属于 PDF/Office 的扩展名），若也当作本引擎能力对外暴露，
     * 前端会出现「选了文本解析却走到 Office 解析器」的假选项。
     */
    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    /** 展示名 · 前端「解析引擎」下拉用。 */
    @Override
    public String displayName() {
        return "内置文本解析";
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName().toString();
        String ext = KbParsers.extensionOf(fileName);
        Metadata md = KbTikaSupport.newMetadata(fileName);
        StringWriter writer = new StringWriter();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            KbTikaSupport.parser().parse(in, new ToTextContentHandler(writer), md, new org.apache.tika.parser.ParseContext());
        } catch (Exception e) {
            throw KbTikaSupport.wrap("纯文本解析", file, e);
        }

        String text = trimOuterBlankLines(writer.toString());
        Map<String, String> meta = new LinkedHashMap<>(KbTikaSupport.toMetadataMap(md));

        // CSV/TSV → Markdown 表格；JSON → 围栏代码块（转换失败就退回 Tika 原文，⛔ 不让解析失败）
        // ⚠️ CSV/TSV 必须读「原始字节」：Tika 的 ToTextContentHandler 把逗号分隔的行**转成制表符分隔**，
        //    拿它去解析 CSV 会把「引号里含逗号的字段」与「真的制表符分隔文件」混为一谈。
        String converted = convertStructured(file, ext, text, md);
        if (converted != null) {
            text = converted;
            meta.put(META_STRUCTURED, "true");
        }

        KbParsers.fillCommonMetadata(meta, fileName, md, this);
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(text.length()));

        // ⭐ 源位置块：只对「Tika 原样透传」的纯文本记账（源站 knowledge_source_locators.go:36-39）。
        //   ⛔ 不能对 CSV/TSV/JSON 用段落块：那几类已被本类改写（CSV → Markdown 表格、
        //   JSON → 围栏代码块），改写后的文本与原文不是一一对应，段落块会给错位置。
        List<KbSourceBlock> sourceBlocks = List.of();
        if (canMapToOriginal(ext, meta)) {
            sourceBlocks = KbSourceIndex.textBlocks(text);
            log.info("[溯源] {} 产出 {} 个 source block（类型={} 覆盖码点={}/{}）",
                fileName, sourceBlocks.size(), KbSourceLocator.TYPE_TEXT,
                sourceBlocks.stream().mapToInt(KbSourceBlock::length).sum(),
                text.codePointCount(0, text.length()));
            if (sourceBlocks.isEmpty() && !text.isBlank()) {
                log.warn("[溯源] {} 正文非空但未切出段落块（全文无换行？）chars={}", fileName, text.length());
            }
        } else {
            log.info("[溯源] {} 类型={} 已改写正文（structured={}），不产出 source block",
                fileName, ext, meta.get(META_STRUCTURED));
        }
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, String.valueOf(sourceBlocks.size()));

        if (log.isInfoEnabled()) {
            log.info("KbTextParser: 解析完成 file={} mime={} chars={} structured={} sourceBlocks={}", fileName,
                meta.get(KbParsedDocument.META_MIME_TYPE), text.length(), meta.get(META_STRUCTURED),
                sourceBlocks.size());
        }
        return new KbParsedDocument(text, List.of(), meta, sourceBlocks);
    }

    /**
     * 本文件能否「分块文本 = 原文件文本」直接对应（源站同口径）。
     *
     * <p><b>判据</b>：没有触发结构化改写（{@code structured} 未置位）且不是 HTML 系
     * （{@code html/htm/xhtml} 会被 Tika 去掉标签 ⇒ 字符区间与原文不对应）。
     *
     * <p>⚠️ 还有一层「截断」：超过 {@link #MAX_TEXT_CHARS} 时尾部被砍，但被砍之后的部分
     * 本来也没有块 —— 前面的偏移仍然正确，故不因此禁用。
     */
    static boolean canMapToOriginal(String ext, Map<String, String> meta) {
        if (meta != null && "true".equals(meta.get(META_STRUCTURED))) {
            return false;
        }
        if (ext == null) {
            return false;
        }
        return switch (ext) {
            case "html", "htm", "xhtml" -> false;
            default -> true;
        };
    }

    /**
     * CSV/TSV → Markdown 表格，JSON → 围栏 {@code ```json} 代码块；其余扩展名返回 null（不改写）。
     *
     * <p><b>失败即退回</b>：转换抛异常、结果为空、或文件读不出来时返回 null，调用方保留 Tika 原文 ——
     * 结构化的目的是「更好」而不是「必须」，⛔ 不该因为一张畸形 CSV 让整份文档解析失败。
     *
     * @param file 原始文件（CSV/TSV 要从这里读字节）
     * @param ext  小写扩展名（可 null）
     * @param tikaText Tika 抽出的原文（JSON 用它，CSV 只在读字节失败时兜底）
     * @param md   Tika 已探测的 Metadata（用它给的 charset，⛔ 不自己猜编码）
     * @return 转换后的 Markdown；不需要/无法转换时 null
     */
    static String convertStructured(Path file, String ext, String tikaText, Metadata md) {
        if (ext == null) {
            return null;
        }
        try {
            return switch (ext) {
                case "csv", "tsv" -> {
                    String raw = readTextWithDetectedCharset(file, md);
                    yield raw == null
                        ? null
                        : csvToMarkdown(raw, "tsv".equals(ext) ? '\t' : ',');
                }
                case "json" -> {
                    // 优先用原始字节（Tika 的纯文本输出对某些 JSON 会插空行/改空白）；
                    // 读不到再退回 Tika 文本。
                    String raw = readTextWithDetectedCharset(file, md);
                    yield jsonToMarkdown(raw == null ? tikaText : raw);
                }
                default -> null;
            };
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("KbTextParser: 结构化转换失败，保留 Tika 原文 ext={} 原因={}", ext, e.getMessage());
            }
            return null;
        }
    }

    /**
     * 按 Tika 探测到的 charset 读原始文本（BOM 去掉）。
     *
     * <p>charset 来源：Metadata 的 {@code Content-Encoding} → 默认 UTF-8。
     * Tika 对 GBK 的 CSV 会给出 {@code GB18030}（实测），故中文表格不会乱码。
     *
     * <p><b>安全网</b>：探测值偶发给出 {@code ISO-8859-1} 或 {@code US-ASCII}（对「纯 ASCII 头部 +
     * 中文正文」的小文件尤其常见）。这类<b>单字节 Latin 族</b>编码会把每个字节都映射成一个字符，
     * 解出来<b>不会</b>出现 {@code U+FFFD}（所以不能靠替换字符发现），却是一整片乱码。
     * 判据：Latin 族 + 字节是合法 UTF-8 多字节 ⇒ 以 UTF-8 为准。
     * 宁可纠正明显的误判，也不要把乱码写进知识库（乱码一旦入库，检索永远查不到）。
     *
     * @return 文本；读失败时 null（调用方退回 Tika 原文）
     */
    static String readTextWithDetectedCharset(Path file, Metadata md) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int offset = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
            String charsetName = md == null ? null : md.get("Content-Encoding");
            java.nio.charset.Charset cs;
            try {
                cs = charsetName == null || charsetName.isBlank()
                    ? java.nio.charset.StandardCharsets.UTF_8
                    : java.nio.charset.Charset.forName(charsetName);
            } catch (RuntimeException e) {
                cs = java.nio.charset.StandardCharsets.UTF_8;
            }

            if (isSingleByteLatin(cs) && hasNonAscii(bytes, offset)) {
                String asUtf8 = decodeStrictUtf8(bytes, offset);
                if (asUtf8 != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("KbTextParser: charset={} 是单字节 Latin 族且字节是合法 UTF-8，改用 UTF-8 file={}",
                            charsetName, file);
                    }
                    return asUtf8;
                }
            }

            String text = new String(bytes, offset, bytes.length - offset, cs);
            if (text.indexOf('\uFFFD') >= 0 && cs != java.nio.charset.StandardCharsets.UTF_8) {
                String asUtf8 = decodeStrictUtf8(bytes, offset);
                if (asUtf8 != null && asUtf8.indexOf('\uFFFD') < 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("KbTextParser: charset={} 解码出现替换字符，改用 UTF-8 file={}", charsetName, file);
                    }
                    return asUtf8;
                }
            }
            return text;
        } catch (java.io.IOException e) {
            log.debug("KbTextParser: 读取原始字节失败，退回 Tika 文本 file={} 原因={}", file, e.getMessage());
            return null;
        }
    }

    /** 单字节 Latin 族（ISO-8859-1 / US-ASCII / windows-1252 及其别名）。 */
    private static boolean isSingleByteLatin(java.nio.charset.Charset cs) {
        return cs.equals(java.nio.charset.StandardCharsets.ISO_8859_1)
            || cs.equals(java.nio.charset.StandardCharsets.US_ASCII)
            || cs.equals(java.nio.charset.Charset.forName("windows-1252"));
    }

    /** 正文里是否出现过 &ge; 0x80 的字节（全 ASCII 时无需任何编码纠正）。 */
    private static boolean hasNonAscii(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) >= 0x80) {
                return true;
            }
        }
        return false;
    }

    /** 严格按 UTF-8 解码；不是合法 UTF-8 时返回 null（不写替换字符、不吞异常）。 */
    private static String decodeStrictUtf8(byte[] bytes, int offset) {
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /**
     * CSV/TSV → Markdown 表格（首行作表头 + 分隔行）。
     *
     * <p><b>对齐源站 {@code csvToMarkdown} 的escaping</b>：单元格内的 {@code \} 与 {@code |} 加反斜杠；
     * 换行统一成 {@code <br />}（单元格里的原始换行会把 Markdown 表格断成两半）。
     * 行内列数不齐时按<b>最大列数</b>补齐空单元格（源站 {@code width = max(width, len(record))}）。
     *
     * @return Markdown 表格；没有数据行时 null
     */
    static String csvToMarkdown(String text, char delimiter) {
        java.util.List<java.util.List<String>> rows = parseDelimited(text, delimiter);
        if (rows.isEmpty()) {
            return null;
        }
        int width = 0;
        for (java.util.List<String> r : rows) {
            width = Math.max(width, r.size());
        }
        if (width == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(rows.size() * (width * 8 + 4));
        for (int r = 0; r < rows.size(); r++) {
            java.util.List<String> row = rows.get(r);
            out.append('|');
            for (int c = 0; c < width; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                out.append(' ').append(escapeCell(cell)).append(" |");
            }
            out.append('\n');
            if (r == 0) {
                out.append('|');
                for (int c = 0; c < width; c++) {
                    out.append(" --- |");
                }
                out.append('\n');
            }
        }
        return out.toString().strip();
    }

    /** 单元格转义 · 对齐源站 {@code csvCellReplacer}（{@code \} / {@code |} 加反斜杠，换行 → {@code <br />}）。 */
    static String escapeCell(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        return cell.replace("\r\n", "<br />")
            .replace("\r", "<br />")
            .replace("\n", "<br />")
            .replace("\\", "\\\\")
            .replace("|", "\\|");
    }

    /**
     * 按 RFC4180 解析分隔文本（支持双引号包裹、{@code ""} 转义、引号内的分隔符与换行）。
     *
     * <p>⛔ 不用 {@code String.split}：{@code "1,200 万元"} 这类带逗号的引号字段会被切碎，
     * 是本仓真实数据里最常见的一种（金额列）。
     */
    static java.util.List<java.util.List<String>> parseDelimited(String text, char delimiter) {
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        java.util.List<String> row = new java.util.ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cell.append(ch);
                i++;
                continue;
            }
            if (ch == '"' && cell.toString().isBlank()) {
                // 只有「单元格开头」的引号才是包裹引号（`a"b` 里的引号是普通字符）
                cell.setLength(0);
                inQuotes = true;
                i++;
                continue;
            }
            if (ch == delimiter) {
                row.add(cell.toString().strip());
                cell.setLength(0);
                i++;
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                row.add(cell.toString().strip());
                cell.setLength(0);
                addRow(rows, row);
                row = new java.util.ArrayList<>();
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                continue;
            }
            cell.append(ch);
            i++;
        }
        // 末尾未闭合的行
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().strip());
            addRow(rows, row);
        }
        return rows;
    }

    /** 只收有内容的行（全空行是 CSV 里的排版噪声，转成 Markdown 会变成一串空单元格）。 */
    private static void addRow(java.util.List<java.util.List<String>> rows, java.util.List<String> row) {
        boolean any = false;
        for (String c : row) {
            if (!c.isEmpty()) {
                any = true;
                break;
            }
        }
        if (any) {
            rows.add(row);
        }
    }

    /**
     * JSON → 围栏 {@code ```json} 代码块（缩进 2 空格）。
     *
     * <p><b>为什么是代码块而不是表格</b>：JSON 的键层级不固定，压成表格必然丢结构；源站
     * {@code jsonToMarkdown} 的做法也是「格式化 + 围栏块」，让下游分块器按块边界切。
     *
     * <p><b>⛔ 不转义 HTML 字符</b>（源站 {@code SetEscapeHTML(false)} 同义）：<code>&lt;</code>
     * 之类的转义只对 HTML 嵌入有意义，在这里只会把文字弄乱、让检索搜不到。
     *
     * @return 围栏代码块；不是合法 JSON 时 null（退回 Tika 原文，例如注释风格的 JSONC）
     */
    static String jsonToMarkdown(String text) {
        String body = text;
        // Excel / Windows 导出常带 BOM
        if (!body.isEmpty() && body.charAt(0) == '\uFEFF') {
            body = body.substring(1);
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (node == null) {
                return null;
            }
            String pretty = node.toPrettyString();
            return "```json\n" + pretty + "\n```";
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    /**
     * 去掉首尾空行（Tika 对 TXT/MD 会在开头补一批换行）。
     *
     * <p>只裁首尾空白，<b>不动正文内部任何字符</b> —— 派单书要求「不做任何改写」。
     */
    static String trimOuterBlankLines(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }
}

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 纯文本类解析器 · .txt / .md / .markdown / .html / .htm / .json / .csv / .log / .xml。
 *
 * <p><b>行为（派单书 §3.3 KbTextParser）</b>：直接用 Tika {@code AutoDetectParser} 抽文本，
 * <b>不做任何改写</b>，编码自动探测（Tika 带 juniversalchardet，GBK 文本也能出正确中文）。
 *
 * <p><b>为什么用 {@link ToTextContentHandler} 而不是 {@link KbMarkdownHandler}</b>：
 * 这几类文件本身已经是「作者写的文本」，Markdown 语法（{@code #} 标题、{@code |} 表格）是内容本身。
 * 若再过一遍 HTML→Markdown 转换，{@code .md} 会被当成 HTML 转义、{@code .csv} 的逗号会丢结构。
 * 只有 Office 二进制格式才需要「把 Tika 拍平的结构还原成 Markdown」。
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

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName().toString();
        Metadata md = KbTikaSupport.newMetadata(fileName);
        StringWriter writer = new StringWriter();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            KbTikaSupport.parser().parse(in, new ToTextContentHandler(writer), md, new org.apache.tika.parser.ParseContext());
        } catch (Exception e) {
            throw KbTikaSupport.wrap("纯文本解析", file, e);
        }

        String text = trimOuterBlankLines(writer.toString());
        Map<String, String> meta = new LinkedHashMap<>(KbTikaSupport.toMetadataMap(md));
        KbParsers.fillCommonMetadata(meta, fileName, md, this);
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(text.length()));

        if (log.isInfoEnabled()) {
            log.info("KbTextParser: 解析完成 file={} mime={} chars={}", fileName,
                meta.get(KbParsedDocument.META_MIME_TYPE), text.length());
        }
        return new KbParsedDocument(text, java.util.List.of(), meta);
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

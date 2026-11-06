package com.nexusai.kb.app.parser;

import org.apache.tika.metadata.Metadata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 解析器共用的小工具 · 扩展名解析、公共元数据填充、空值归一。
 *
 * <p><b>WHY 单独一类</b>：三个解析器都要做「文件名 → 扩展名」「Tika Metadata → 公共字段」这两件事；
 * 各自复制一份必然跑偏（例如只有一处把 {@code .DOCX} 转小写），故集中一处。
 */
final class KbParsers {

    private KbParsers() {
    }

    /**
     * 取小写扩展名（不含点）；无扩展名 → null。
     *
     * <p>⛔ 用 {@code lastIndexOf('.')} 而不是按 {@code .} 切分：文件名本身可能带点
     * （{@code 2026.11 报告v1.docx}），按最后一段取才是真扩展名。
     */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** MIME 主类型（去参数、转小写）；null/空 → 空串。 */
    static String normalizeMime(String mime) {
        if (mime == null) {
            return "";
        }
        String m = mime.trim();
        int semi = m.indexOf(';');
        if (semi >= 0) {
            m = m.substring(0, semi);
        }
        return m.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 填充三个解析器共有的元数据（文件名 / MIME / 标题 / 作者 / 解析器名）。
     *
     * <p>MIME 取 Tika 探测结果（{@code Metadata.CONTENT_TYPE}）；没探到就留空 —— ⛔ 不拿扩展名
     * 硬编一个假 MIME，否则上层按 MIME 路由会走进错的分支。
     */
    static void fillCommonMetadata(Map<String, String> meta, String fileName, Metadata tikaMd,
                                   KbDocumentParser parser) {
        meta.put(KbParsedDocument.META_FILE_NAME, fileName == null ? "" : fileName);
        String mime = tikaMd == null ? null : normalizeMime(tikaMd.get("Content-Type"));
        if (mime != null && !mime.isEmpty()) {
            meta.put(KbParsedDocument.META_MIME_TYPE, mime);
        }
        String title = KbTikaSupport.titleOf(tikaMd);
        if (title != null) {
            meta.put(KbParsedDocument.META_TITLE, title);
        }
        String author = KbTikaSupport.authorOf(tikaMd);
        if (author != null) {
            meta.put(KbParsedDocument.META_AUTHOR, author);
        }
        meta.put(KbParsedDocument.META_PARSER, parser.name());
    }

    /** 按上限截断文本，返回 [截断后文本, 是否截断]。 */
    static Truncation truncate(String text, int maxChars) {
        if (text == null) {
            return new Truncation("", false);
        }
        if (text.length() <= maxChars) {
            return new Truncation(text, false);
        }
        return new Truncation(text.substring(0, maxChars), true);
    }

    /** 截断结果载体（文本 + 是否命中上限）。 */
    record Truncation(String text, boolean truncated) {
    }

    /** 新metadata map（保持插入序，便于日志与排查）。 */
    static Map<String, String> newMeta() {
        return new LinkedHashMap<>();
    }
}

package com.nexusai.infra.util;

import java.text.Normalizer;
import java.util.Set;

/**
 * PdfUtils · 对齐 CC utils/pdfUtils.ts.
 *
 * <p>L1 语义: PDF 文档 page range parser + extension check.
 * <ul>
 *   <li>{@code parsePDFPageRange(pages)} — 支持 "5" / "1-10" / "3-"</li>
 *   <li>{@code isPDFExtension(ext)} — extension 严格匹配 "pdf"</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: DOCUMENT_EXTENSIONS Set + parsePDFPageRange + isPDFExtension 静态方法</li>
 *   <li><b>A2 Golden Trace</b>: "5" → {1,5};"1-10" → {1,10};"3-" → {3,Infinity};invalid → null</li>
 *   <li><b>A3 纯函数</b>: 无副作用</li>
 *   <li><b>A4 边界</b>: 空/空白 → null;inverted (last<first) → null;非数字 → null;@ ".pdf" / "PDF" 标准化</li>
 *   <li><b>A5 业务场景</b>: ReadFileTool 解析 pages param;ReadOnly PDF 校验</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS regex literal → Java Pattern.compile + parseInt;
 * TS Set → Java Set.of;TS toLowerCase → Java toLowerCase(Locale.ROOT)。
 */
public final class PdfUtils {

    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf");

    public record PageRange(int firstPage, int lastPage) {}

    private PdfUtils() {}

    public static PageRange parsePDFPageRange(String pages) {
        if (pages == null) return null;
        String trimmed = pages.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.endsWith("-")) {
            int first;
            try {
                first = Integer.parseInt(trimmed.substring(0, trimmed.length() - 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (first < 1) return null;
            return new PageRange(first, Integer.MAX_VALUE);
        }
        int dashIdx = trimmed.indexOf('-');
        if (dashIdx == -1) {
            int page;
            try {
                page = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
            if (page < 1) return null;
            return new PageRange(page, page);
        }
        int first, last;
        try {
            first = Integer.parseInt(trimmed.substring(0, dashIdx).trim());
            last = Integer.parseInt(trimmed.substring(dashIdx + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (first < 1 || last < 1 || last < first) return null;
        return new PageRange(first, last);
    }

    public static boolean isPDFExtension(String ext) {
        if (ext == null) return false;
        String normalized = ext.startsWith(".") ? ext.substring(1) : ext;
        return DOCUMENT_EXTENSIONS.contains(normalized.toLowerCase(java.util.Locale.ROOT));
    }
}

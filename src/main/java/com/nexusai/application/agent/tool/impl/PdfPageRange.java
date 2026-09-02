package com.nexusai.application.agent.tool.impl;

import java.util.Optional;

/**
 * PDF 页码范围解析 · 对齐 CC {@code utils/pdfUtils.ts parsePDFPageRange}。
 *
 * <p>L1 语义：纯字符串解析，无 I/O。
 * <ul>
 *   <li>{@code "5"}     → firstPage=5,  lastPage=5</li>
 *   <li>{@code "1-10"}  → firstPage=1,  lastPage=10</li>
 *   <li>{@code "3-"}    → firstPage=3,  lastPage=∞ (open-ended)</li>
 *   <li>{@code "" / 非数字 / 0 / 倒序} → Optional.empty()</li>
 * </ul>
 *
 * <p>WHY 单独成类而非 ReadFileTool 私有静态方法：保持 {@code ReadFileTool} 紧凑，
 * 让 pages 解析可独立单元测试。
 */
public final class PdfPageRange {

    /**
     * 解析结果。{@code lastPage == Integer.MAX_VALUE} 表示 open-ended（CC 用 Infinity）。
     *
     * @param firstPage 起始页（1-based）
     * @param lastPage  结束页（含）；open-ended 时 = {@link Integer#MAX_VALUE}
     */
    public record Range(int firstPage, int lastPage) {

        /**
         * 范围大小（open-ended 视作恰好比上限大 1 → CC 用 {@code +1} 让其必然 > 20）。
         */
        public int sizeOrOverLimit(int limit) {
            if (lastPage == Integer.MAX_VALUE) {
                return limit + 1;
            }
            return lastPage - firstPage + 1;
        }
    }

    private PdfPageRange() {
        // utility class，禁止实例化
    }

    /**
     * 解析 pages 字符串。
     *
     * @param pages pages 字段值（{@code null} → empty，调用方需先判断 pages 是否提供）
     * @return 解析成功返回 Range；空 / 非数字 / 倒序返回 empty
     */
    public static Optional<Range> parse(String pages) {
        if (pages == null) return Optional.empty();
        String trimmed = pages.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        // "N-" open-ended
        if (trimmed.endsWith("-")) {
            String head = trimmed.substring(0, trimmed.length() - 1);
            Integer first = parsePositiveInt(head);
            if (first == null) return Optional.empty();
            return Optional.of(new Range(first, Integer.MAX_VALUE));
        }

        int dashIdx = trimmed.indexOf('-');
        if (dashIdx < 0) {
            // 单页 "5"
            Integer page = parsePositiveInt(trimmed);
            if (page == null) return Optional.empty();
            return Optional.of(new Range(page, page));
        }

        // "1-10" 范围
        Integer first = parsePositiveInt(trimmed.substring(0, dashIdx));
        Integer last = parsePositiveInt(trimmed.substring(dashIdx + 1));
        if (first == null || last == null) return Optional.empty();
        if (last < first) return Optional.empty();
        return Optional.of(new Range(first, last));
    }

    /**
     * 把字符串解析为 ≥1 的正整数；非法 → null。
     */
    private static Integer parsePositiveInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            int n = Integer.parseInt(s.trim());
            return n < 1 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
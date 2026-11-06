package com.nexusai.kb.app.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 表头跟踪器 · 对齐 WeKnora {@code chunker.headerTracker}
 * （{@code header_tracker.go:43-290}）。
 *
 * <p><b>解决什么问题</b>：一张大 Markdown 表被切成多个分块后，第二块起会<b>丢失表头</b>
 * ⇒ 单独看一块时看不出每列是什么。本类检测表头，并让合并逻辑把它前置到后续分块。
 *
 * <p><b>状态</b>：
 * <ul>
 *   <li>{@code activeHeaders}：当前生效的表头（priority → 文本）</li>
 *   <li>{@code endedHeaders}：已结束的 hook 优先级（避免同一张表反复重新识别）</li>
 *   <li>{@code pendingExtend}：列名为空的表头（如 {@code ||}），等首个数据行到达后补成真表头</li>
 *   <li>{@code pendingTableBreak}：表格行以段落分隔结尾（表间空行被 {@code \n\n} 切走），
 *       需要看下一个单元才能判断是否新表</li>
 *   <li>{@code headerEndedThisUnit}：本单元开始时旧表结束 ⇒ 合并逻辑需在加入本单元<b>前</b>落盘</li>
 * </ul>
 *
 * <p>⚠️ 源侧只有<b>一个</b> hook（Markdown 表格，priority 15），本类保持同样结构，
 * 不擅自扩展成通用 hook 框架。
 */
final class KbHeaderTracker {

    /** Markdown 表格 hook 的优先级（源侧 {@code markdownTableHookPriority = 15}）。 */
    private static final int TABLE_HOOK_PRIORITY = 15;

    /**
     * 表头起始模式（源侧 {@code header_tracker.go:29-30}）：
     * 表头行 + 分隔行，如 {@code "| A | B |\n| --- | --- |\n"}。
     * <p>{@code (?si)} = DOTALL + CASE_INSENSITIVE。
     */
    private static final Pattern TABLE_HEADER_START = Pattern.compile(
        "^\\s*(?:\\|[^|\\n]*)+[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?[\\r\\n]+$",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 表头结束模式（源侧 {@code header_tracker.go:31}）：
     * 空行，或一行不以 {@code |} / 空白开头。
     */
    private static final Pattern TABLE_HEADER_END = Pattern.compile(
        "^\\s*$|^\\s*[^|\\s].*$",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 单行表格行模式（源侧 {@code header_tracker.go:37}）。 */
    private static final Pattern TABLE_ROW = Pattern.compile(
        "^\\s*(?:\\|[^|\\n]*)+\\|\\s*$", Pattern.MULTILINE);

    private final Map<Integer, String> activeHeaders = new HashMap<>();
    private final Map<Integer, Boolean> endedHeaders = new HashMap<>();
    private final Map<Integer, Boolean> pendingExtend = new HashMap<>();
    private boolean pendingTableBreak;
    private boolean headerEndedThisUnit;

    /** 本单元开始时旧表是否已结束（合并逻辑据此在加入前落盘）。 */
    boolean headerEndedThisUnit() {
        return headerEndedThisUnit;
    }

    /**
     * 喂一个切分单元，更新状态 · 对齐源侧 {@code headerTracker.update}
     * （{@code header_tracker.go:67-141}）。步骤编号与源侧注释一致。
     *
     * @param unit 切分单元文本
     */
    void update(String unit) {
        headerEndedThisUnit = false;
        if (unit == null) {
            unit = "";
        }

        // ── 0. 处理上一单元的「表格已结束」悬置判断 ──────────────────
        if (pendingTableBreak) {
            pendingTableBreak = false;
            if (activeHeaders.containsKey(TABLE_HOOK_PRIORITY)) {
                if (firstTableRowColumnCount(unit) > 0) {
                    // 新表开始
                    clearTableHeader();
                    headerEndedThisUnit = true;
                } else {
                    clearTableHeader();
                }
            }
        }

        // ── 1. 检查结束标记 ─────────────────────────────────────────
        if (activeHeaders.containsKey(TABLE_HOOK_PRIORITY)
            && TABLE_HEADER_END.matcher(unit).matches()) {
            endedHeaders.put(TABLE_HOOK_PRIORITY, true);
            activeHeaders.remove(TABLE_HOOK_PRIORITY);
            pendingExtend.remove(TABLE_HOOK_PRIORITY);
        }

        // ── 1b. 表间断行 / 列数不匹配 ───────────────────────────────
        if (activeHeaders.containsKey(TABLE_HOOK_PRIORITY)
            && !pendingExtend.containsKey(TABLE_HOOK_PRIORITY)) {
            if (splitEndsWithParagraphBreak(unit)) {
                pendingTableBreak = true;
            } else {
                endTableHeaderOnColumnMismatch(unit);
            }
        }

        // ── 2. 空列名表头补全（"||" + "| --- | --- |" 形态）──────────
        if (!pendingExtend.isEmpty() && TABLE_ROW.matcher(unit).find()) {
            List<Integer> toExtend = new ArrayList<>(pendingExtend.keySet());
            for (Integer p : toExtend) {
                String active = activeHeaders.get(p);
                if (active != null) {
                    String sep = extractSeparatorLine(active);
                    activeHeaders.put(p, unit + sep);
                }
            }
        }
        pendingExtend.clear();

        // ── 3. 检查新的表头起始（仅对既未激活也未结束的 hook）────────
        if (!activeHeaders.containsKey(TABLE_HOOK_PRIORITY)
            && !endedHeaders.containsKey(TABLE_HOOK_PRIORITY)) {
            Matcher m = TABLE_HEADER_START.matcher(unit);
            if (m.find()) {
                String header = m.group();
                activeHeaders.put(TABLE_HOOK_PRIORITY, header);
                if (isEmptyTableHeaderRow(header)) {
                    pendingExtend.put(TABLE_HOOK_PRIORITY, true);
                }
            }
        }

        // ── 4. 全部表头都结束了 ⇒ 清空 ended 集合，允许后续新表 ──────
        if (activeHeaders.isEmpty()) {
            endedHeaders.clear();
        }
    }

    /**
     * 当前生效的表头文本（按优先级降序拼接）· 对齐源侧 {@code getHeaders}。
     *
     * @return 拼接后的表头；无生效表头时返回空串
     */
    String getHeaders() {
        if (activeHeaders.isEmpty()) {
            return "";
        }
        List<Integer> priorities = new ArrayList<>(activeHeaders.keySet());
        priorities.sort((a, b) -> Integer.compare(b, a)); // 降序
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < priorities.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(activeHeaders.get(priorities.get(i)));
        }
        return sb.toString();
    }

    // ── 内部辅助（全部对齐源侧同名函数）───────────────────────────────

    private void clearTableHeader() {
        endedHeaders.put(TABLE_HOOK_PRIORITY, true);
        activeHeaders.remove(TABLE_HOOK_PRIORITY);
        pendingExtend.remove(TABLE_HOOK_PRIORITY);
    }

    private void endTableHeaderOnColumnMismatch(String unit) {
        String header = activeHeaders.get(TABLE_HOOK_PRIORITY);
        if (header == null) {
            return;
        }
        int rowCols = firstTableRowColumnCount(unit);
        int headerCols = headerTableColumnCount(header);
        if (rowCols > 0 && headerCols > 0 && rowCols != headerCols) {
            clearTableHeader();
            headerEndedThisUnit = true;
        }
    }

    /**
     * 判断切分单元是否以「段落分隔」结尾 · 源侧 {@code splitEndsWithParagraphBreak}
     * （{@code header_tracker.go:219-222}）。
     *
     * <p>先去掉尾部的空格/制表/回车，再判断是否以两个换行结尾。
     */
    static boolean splitEndsWithParagraphBreak(String unit) {
        int end = unit.length();
        while (end > 0) {
            char c = unit.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        String trimmed = unit.substring(0, end);
        return trimmed.endsWith("\n\n");
    }

    /**
     * 单行表格的列数 · 源侧 {@code tableRowColumnCount}（{@code :224-237}）。
     *
     * <p>不是以 {@code |} 开头 → 0 列；去掉首尾的空单元格后计数。
     */
    static int tableRowColumnCount(String line) {
        String t = line.strip();
        if (!t.startsWith("|")) {
            return 0;
        }
        List<String> parts = splitTableCells(t);
        if (!parts.isEmpty() && parts.get(0).strip().isEmpty()) {
            parts = parts.subList(1, parts.size());
        }
        if (!parts.isEmpty() && parts.get(parts.size() - 1).strip().isEmpty()) {
            parts = parts.subList(0, parts.size() - 1);
        }
        return parts.size();
    }

    /**
     * 按单元格分隔符切分表格行 · 源侧 {@code splitTableCells}（{@code :242-259}）。
     *
     * <p>{@code \|} 是单元格内的文本（GFM 语义），不算分隔符 —— 通过数前导反斜杠的
     * <b>奇偶</b>判断。
     */
    static List<String> splitTableCells(String line) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int backslashes = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '|') {
                if (backslashes % 2 == 0) {
                    parts.add(line.substring(start, i));
                    start = i + 1;
                }
            }
            backslashes = 0;
        }
        parts.add(line.substring(start));
        return parts;
    }

    /** 文本中首个表格行的列数 · 源侧 {@code firstTableRowColumnCount}（{@code :261-269}）。 */
    static int firstTableRowColumnCount(String text) {
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && TABLE_ROW.matcher(t).find()) {
                return tableRowColumnCount(t);
            }
        }
        return 0;
    }

    /** 表头的列数 · 源侧 {@code headerTableColumnCount}（{@code :271-282}）。 */
    static int headerTableColumnCount(String header) {
        for (String line : header.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.contains("---")) {
                continue;
            }
            int n = tableRowColumnCount(t);
            if (n > 0) {
                return n;
            }
        }
        return 0;
    }

    /**
     * 下一单元是否开启了<b>列数不同</b>的新表 · 源侧 {@code headerColumnMismatch}（{@code :286-290}）。
     */
    static boolean headerColumnMismatch(String headers, String nextUnit) {
        int headerCols = headerTableColumnCount(headers);
        int rowCols = firstTableRowColumnCount(nextUnit);
        return headerCols > 0 && rowCols > 0 && headerCols != rowCols;
    }

    /**
     * 表头是否为「空列名行」· 源侧 {@code isEmptyTableHeaderRow}（{@code :175-187}）。
     *
     * <p>常见于 MarkItDown 等转换器产出的：
     * <pre>
     *   ||
     *   | --- | --- |
     *   | real column A | real column B |
     * </pre>
     */
    static boolean isEmptyTableHeaderRow(String header) {
        int idx = header.indexOf('\n');
        if (idx < 0) {
            return false;
        }
        String row = header.substring(0, idx).strip();
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c != '|' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    /** 取表头里的分隔行（含 {@code ---} 的那一行）+ 换行 · 源侧 {@code extractSeparatorLine}。 */
    static String extractSeparatorLine(String header) {
        for (String line : header.split("\n", -1)) {
            if (line.contains("---")) {
                return line + "\n";
            }
        }
        return "";
    }
}

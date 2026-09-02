package com.nexusai.application.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 结构化 diff 生成器 · 对齐 CC {@code getPatchFromContents}
 * （Open-ClaudeCode/src/utils/diff.ts:81-114）。
 *
 * <p>手写 LCS（最长公共子序列）行级 diff，产出与 CC hunkSchema 一致的 hunk 数组：
 * {@code {oldStart, oldLines, newStart, newLines, lines[]}}，context=3（CC CONTEXT_LINES）。
 *
 * <p>& / $ 转义对齐 CC diff.ts:30-47：diff 前把 {@code &}→{@code <<:AMPERSAND_TOKEN:>>}、
 * {@code $}→{@code <<:DOLLAR_TOKEN:>>}，hunk 产出后再 unescape 回填 —— 防止特殊字符干扰 diff 计算，
 * 保证 hunk lines 与原文件文本一致。
 */
public final class StructuredPatchGenerator {

    private static final Logger log = LoggerFactory.getLogger(StructuredPatchGenerator.class);

    /** 上下文行数 · CC utils/diff.ts:9 {@code CONTEXT_LINES = 3}。 */
    public static final int CONTEXT_LINES = 3;

    /** & 转义 token · CC utils/diff.ts:35。 */
    public static final String AMPERSAND_TOKEN = "<<:AMPERSAND_TOKEN:>>";

    /** $ 转义 token · CC utils/diff.ts:38。 */
    public static final String DOLLAR_TOKEN = "<<:DOLLAR_TOKEN:>>";

    private StructuredPatchGenerator() {
    }

    /**
     * 计算 oldContent → newContent 的结构化 patch（hunk 数组）。
     *
     * @param oldContent 旧文件内容（Edit 的原文 / Write 的原文件内容）
     * @param newContent 新文件内容（Edit 替换后 / Write 的写入内容）
     * @return hunk 数组；无变更时返回空数组（对齐 CC getPatchFromContents 空 hunks）
     */
    public static List<StructuredPatchHunk> getPatch(String oldContent, String newContent) {
        // CC diff.ts:92-95 先对全文 escape，再交给 diff 库；此处逐行等价实现
        String oldEsc = escapeForDiff(normalizeCrlf(oldContent));
        String newEsc = escapeForDiff(normalizeCrlf(newContent));
        List<String> oldLines = splitLines(oldEsc);
        List<String> newLines = splitLines(newEsc);

        long start = System.nanoTime();
        List<Hunk> raw = computeHunks(oldLines, newLines, CONTEXT_LINES);
        long costMs = (System.nanoTime() - start) / 1_000_000L;
        if (log.isDebugEnabled()) {
            log.debug("StructuredPatchGenerator: 生成 hunk 数={} oldLines={} newLines={} 耗时={}ms",
                raw.size(), oldLines.size(), newLines.size(), costMs);
        }

        // CC diff.ts:99-104 hunk.lines 逐个 unescape 回填
        List<StructuredPatchHunk> result = raw.stream()
                .map(h -> new StructuredPatchHunk(
                        h.oldStart,
                        h.oldLines,
                        h.newStart,
                        h.newLines,
                        h.lines.stream().map(StructuredPatchGenerator::unescapeFromDiff)
                                .collect(Collectors.toUnmodifiableList())))
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("StructuredPatchGenerator: 结构化 patch 产出完成 hunk={} （消费方：structured_output attachment + countLinesChanged）",
                result.size());
        }
        return result;
    }

    // ── CC diff.ts:30-47 escape/unescape（& / $ → token） ──

    private static String escapeForDiff(String s) {
        return s.replace("&", AMPERSAND_TOKEN).replace("$", DOLLAR_TOKEN);
    }

    private static String unescapeFromDiff(String s) {
        return s.replace(AMPERSAND_TOKEN, "&").replace(DOLLAR_TOKEN, "$");
    }

    /** CRLF 归一化为 LF（对齐 CC Edit 读文件 replaceAll('\r\n','\n') 语义）。 */
    private static String normalizeCrlf(String s) {
        return s.replace("\r\n", "\n");
    }

    /** 按行拆分；尾随空行（内容以 \n 结尾产生）折叠 —— 与 npm diff 的行语义一致。 */
    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String[] parts = content.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String p : parts) {
            lines.add(p);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    // ── LCS 行级 diff ──

    private enum Kind { EQUAL, DELETE, INSERT }

    private record Op(Kind kind, int oldIdx, int newIdx) {
        boolean isChange() {
            return kind == Kind.DELETE || kind == Kind.INSERT;
        }
    }

    private record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<String> lines) {
    }

    /** LCS 回溯生成操作序列（对齐标准 Myers/LCS 语义，等价 npm diff 的 edit script）。 */
    private static List<Op> diffOps(List<String> oldLines, List<String> newLines) {
        int m = oldLines.size();
        int n = newLines.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                dp[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                ops.add(new Op(Kind.EQUAL, i, j));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new Op(Kind.DELETE, i, j));
                i++;
            } else {
                ops.add(new Op(Kind.INSERT, i, j));
                j++;
            }
        }
        while (i < m) {
            ops.add(new Op(Kind.DELETE, i, j));
            i++;
        }
        while (j < n) {
            ops.add(new Op(Kind.INSERT, i, j));
            j++;
        }
        return ops;
    }

    /** 把操作序列切成带 context 的 hunk（context=3，重叠自动合并）。 */
    private static List<Hunk> computeHunks(List<String> oldLines, List<String> newLines, int context) {
        List<Op> ops = diffOps(oldLines, newLines);
        // 1. 变更区段（连续 change op 聚合）
        List<int[]> ranges = new ArrayList<>();
        int idx = 0;
        int total = ops.size();
        while (idx < total) {
            if (ops.get(idx).isChange()) {
                int s = idx;
                while (idx < total && ops.get(idx).isChange()) {
                    idx++;
                }
                ranges.add(new int[]{s, idx});
            } else {
                idx++;
            }
        }
        if (ranges.isEmpty()) {
            return List.of();
        }
        // 2. 每个区段左右扩 context 行
        List<int[]> expanded = ranges.stream()
                .map(r -> new int[]{Math.max(0, r[0] - context), Math.min(total, r[1] + context)})
                .sorted(Comparator.comparingInt(a -> a[0]))
                .collect(Collectors.toList());
        // 3. 合并重叠/相邻 hunk（context 扩展后彼此接触则并为一段）
        List<int[]> merged = new ArrayList<>();
        for (int[] r : expanded) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(new int[]{r[0], r[1]});
            }
        }
        // 4. 构建 hunk（行号 1-based；lines 前缀 ' '/'+'/'−'）
        List<Hunk> result = new ArrayList<>();
        int oi = 0;
        int ni = 0;
        int opIdx = 0;
        for (int[] range : merged) {
            while (opIdx < range[0]) {
                Op op = ops.get(opIdx);
                if (op.kind == Kind.EQUAL || op.kind == Kind.DELETE) {
                    oi++;
                }
                if (op.kind == Kind.EQUAL || op.kind == Kind.INSERT) {
                    ni++;
                }
                opIdx++;
            }
            int oldStart = oi + 1;
            int newStart = ni + 1;
            int oldLinesCount = 0;
            int newLinesCount = 0;
            List<String> lines = new ArrayList<>();
            while (opIdx < range[1]) {
                Op op = ops.get(opIdx);
                switch (op.kind) {
                    case EQUAL -> {
                        lines.add(" " + oldLines.get(op.oldIdx));
                        oi++;
                        ni++;
                        oldLinesCount++;
                        newLinesCount++;
                    }
                    case DELETE -> {
                        lines.add("-" + oldLines.get(op.oldIdx));
                        oi++;
                        oldLinesCount++;
                    }
                    case INSERT -> {
                        lines.add("+" + newLines.get(op.newIdx));
                        ni++;
                        newLinesCount++;
                    }
                }
                opIdx++;
            }
            result.add(new Hunk(oldStart, oldLinesCount, newStart, newLinesCount, lines));
        }
        return result;
    }
}

package com.nexusai.kb.app.chunk;

import java.util.List;

/**
 * 语义重叠边界查找 · 对齐 WeKnora {@code chunker.findSemanticOverlapBoundaryEndingAtOrAfter}
 * （{@code splitter.go:735-830}）。
 *
 * <p><b>为什么需要它</b>：块间重叠不能随便按字符数截——切在半句中间会让重叠内容无意义。
 * 本类在重叠窗口里找<b>语义断点</b>，让下一块从「一整句/一整段之后」开始。
 *
 * <p><b>边界优先级</b>（源侧注释）：
 * <ol>
 *   <li><b>1</b> 段落断：{@code \n\n} 或 {@code \r\n\r\n}</li>
 *   <li><b>2</b> 行断：{@code \n} 或 {@code \r\n}</li>
 *   <li><b>3</b> 句末：中文 {@code 。？！}，或英文 {@code ". " / "? " / "! "}（点后必须有空格）</li>
 * </ol>
 * 优先级优先；同优先级取<b>最早</b>出现的位置（保留尽量多的有用上下文）。
 *
 * <p><b>两条排除规则</b>（源侧 {@code consider} 里）：
 * <ul>
 *   <li>边界起点落在<b>受保护片段内</b> ⇒ 不采纳（否则会在代码块/公式内断开）</li>
 *   <li>边界之后<b>全是空白</b> ⇒ 不采纳（保留一片空白没有意义）</li>
 * </ul>
 */
final class KbSemanticBoundary {

    private KbSemanticBoundary() {
    }

    /**
     * 找语义边界 · 只考虑<b>结束位置 &gt;= {@code minEnd}</b> 的候选。
     *
     * <p>为什么要有 {@code minEnd}：窗口前面多看了 4 个 rune 的 lookbehind，
     * 那些位置的分隔符<b>不在</b>允许保留的范围内（保留会超重叠上限）。
     * 先按 minEnd 过滤再比优先级，避免「更早但不可用」的候选把「更晚但可用」的压掉。
     *
     * @param text   窗口文本
     * @param minEnd 候选边界的最小结束位置（code point 偏移）
     * @return 选中的边界结束位置（code point）；无合法边界返回 -1
     */
    static int findBoundaryEndingAtOrAfter(String text, int minEnd) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int[] runes = text.codePoints().toArray();
        int len = runes.length;
        if (len == 0) {
            return -1;
        }
        int min = Math.max(minEnd, 0);
        if (min > len) {
            return -1;
        }

        // 受保护片段（char 偏移）→ code point 偏移
        List<KbProtectedSpans.Span> charSpans = KbProtectedSpans.find(text);
        int[] protStart = new int[charSpans.size()];
        int[] protEnd = new int[charSpans.size()];
        for (int i = 0; i < charSpans.size(); i++) {
            protStart[i] = KbTextUnits.codePointOffset(text, charSpans.get(i).start());
            protEnd[i] = KbTextUnits.codePointOffset(text, charSpans.get(i).end());
        }
        java.util.function.IntPredicate insideProtected = pos -> {
            for (int i = 0; i < protStart.length; i++) {
                if (pos < protStart[i]) {
                    return false;
                }
                if (pos >= protStart[i] && pos < protEnd[i]) {
                    return true;
                }
            }
            return false;
        };
        java.util.function.IntPredicate hasMeaningfulTail = end -> {
            if (end < 0 || end >= len) {
                return false;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = end; i < len; i++) {
                sb.appendCodePoint(runes[i]);
            }
            return !sb.toString().strip().isEmpty();
        };

        // 最优候选：[start, end, priority]
        int[] best = {0, 0, Integer.MAX_VALUE};
        boolean[] found = {false};

        // 用内部类承接源侧的 consider(start, end, priority) 闭包
        // （Java lambda 不便捕获可变状态，故用具名类）
        BoundaryCollector collector = new BoundaryCollector(
            best, found, insideProtected, hasMeaningfulTail, min, len);

        // ── 段落断（priority 1）──────────────────────────────────────
        boolean[] paragraphRune = new boolean[len];
        for (int i = 0; i < len; i++) {
            if (i + 3 < len && runes[i] == '\r' && runes[i + 1] == '\n'
                && runes[i + 2] == '\r' && runes[i + 3] == '\n') {
                collector.accept(new int[] {i, i + 4, 1});
                for (int j = i; j < i + 4 && j < len; j++) {
                    paragraphRune[j] = true;
                }
                i += 3;
            } else if (i + 1 < len && runes[i] == '\n' && runes[i + 1] == '\n') {
                collector.accept(new int[] {i, i + 2, 1});
                paragraphRune[i] = true;
                paragraphRune[i + 1] = true;
                i++;
            }
        }

        // ── 行断（priority 2）────────────────────────────────────────
        for (int i = 0; i < len; i++) {
            if (paragraphRune[i]) {
                continue;
            }
            if (runes[i] == '\r' && i + 1 < len && runes[i + 1] == '\n'
                && !paragraphRune[i + 1]) {
                collector.accept(new int[] {i, i + 2, 2});
                i++;
                continue;
            }
            if (runes[i] == '\n') {
                collector.accept(new int[] {i, i + 1, 2});
            }
        }

        // ── 句末（priority 3）────────────────────────────────────────
        for (int i = 0; i < len; i++) {
            int cp = runes[i];
            if (cp == '。' || cp == '？' || cp == '！') {
                collector.accept(new int[] {i, i + 1, 3});
            } else if (cp == '.' || cp == '?' || cp == '!') {
                if (i + 1 < len && runes[i + 1] == ' ') {
                    collector.accept(new int[] {i, i + 2, 3});
                }
            }
        }

        return found[0] ? best[1] : -1;
    }

    /** 候选收集器 · 对齐源侧 {@code consider} 闭包（{@code splitter.go:757-770}）。 */
    private static final class BoundaryCollector {
        private final int[] best;
        private final boolean[] found;
        private final java.util.function.IntPredicate insideProtected;
        private final java.util.function.IntPredicate hasMeaningfulTail;
        private final int minEnd;
        private final int len;

        BoundaryCollector(int[] best, boolean[] found,
                          java.util.function.IntPredicate insideProtected,
                          java.util.function.IntPredicate hasMeaningfulTail,
                          int minEnd, int len) {
            this.best = best;
            this.found = found;
            this.insideProtected = insideProtected;
            this.hasMeaningfulTail = hasMeaningfulTail;
            this.minEnd = minEnd;
            this.len = len;
        }

        /**
         * 采纳一个候选（{start, end, priority}）。
         *
         * <p>过滤条件（逐条对齐源侧）：
         * <ul>
         *   <li>{@code start < 0 || end <= start || end < minEnd || end > len} ⇒ 丢弃</li>
         *   <li>起点在受保护片段内 ⇒ 丢弃</li>
         *   <li>边界之后全是空白 ⇒ 丢弃</li>
         * </ul>
         */
        void accept(int[] candidate) {
            int start = candidate[0];
            int end = candidate[1];
            int priority = candidate[2];
            if (start < 0 || end <= start || end < minEnd || end > len
                || insideProtected.test(start) || !hasMeaningfulTail.test(end)) {
                return;
            }
            if (!found[0] || priority < best[2]
                || (priority == best[2] && start < best[0])) {
                best[0] = start;
                best[1] = end;
                best[2] = priority;
                found[0] = true;
            }
        }
    }
}

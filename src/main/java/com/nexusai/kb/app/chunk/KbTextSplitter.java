package com.nexusai.kb.app.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本切分器 · 对齐 WeKnora {@code chunker.SplitText}（{@code splitter.go:278-304}）。
 *
 * <p><b>三大步骤</b>（源侧注释原文）：
 * <ol>
 *   <li>找出受保护片段（公式/代码/链接/表格），它们是原子单元</li>
 *   <li>非保护区按分隔符优先级递归切分；受保护区整体保留</li>
 *   <li>把单元合并成分块，带重叠，并处理表头前置</li>
 * </ol>
 *
 * <p><b>⭐ 递归分隔符语义</b>（源侧 {@code splitter.go:215-223} 注释，照 Python 参考实现）：
 * 若 {@code \n\n} 切出的某片仍超长，则在该片内部继续用 {@code \n} 切 ——
 * <b>不是</b>对整段重新用低优先级切。
 *
 * <p><b>⭐ 绝对上限 7500</b>（源侧 {@code splitter.go:314} / {@code :407}）：
 * 受保护片段若超过 7500 字符会被强制切开；单块超过 7500 也会被强制切开。
 * 这是为下游 embedding API 的硬限制留余量。
 */
public final class KbTextSplitter {

    /** 受保护片段的最大尺寸（源侧 {@code splitter.go:314 maxProtectedSize}）。 */
    private static final int MAX_PROTECTED_SIZE = 7500;

    /** 单块的绝对最大尺寸（源侧 {@code splitter.go:407 absoluteMaxSize}）。 */
    private static final int ABSOLUTE_MAX_SIZE = 7500;

    /** 强制切分时向前找断点的最大回溯距离（源侧 {@code splitter.go:438} / {@code :366}）。 */
    private static final int FORCE_SPLIT_LOOKBACK = 200;

    private KbTextSplitter() {
    }

    /**
     * 切分文本 · 入口，对齐源侧 {@code SplitText}。
     *
     * @param text 原文
     * @param cfg  配置（会先规范化）
     * @return 分块列表；空文本返回空列表
     */
    public static List<KbChunk> split(String text, KbSplitterConfig cfg) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        KbSplitterConfig c = (cfg == null ? KbSplitterConfig.defaults() : cfg).normalized();

        // Step 1: 找受保护片段
        List<KbProtectedSpans.Span> protectedSpans = KbProtectedSpans.find(text);

        // Step 2: 切成单元（受保护区整体保留）
        List<KbTextUnits.SplitUnit> units =
            buildUnitsWithProtection(text, protectedSpans, c.separators(), c.chunkSize());

        // Step 3: 合并成块（带重叠 + 表头）
        return mergeUnits(units, c.chunkSize(), c.chunkOverlap());
    }

    // ── Step 2 ──────────────────────────────────────────────────────

    /**
     * 把文本切成单元，受保护片段保持原子 · 对齐源侧
     * {@code buildUnitsWithProtection}（{@code splitter.go:313-389}）。
     *
     * <p>位置用 <b>code point</b> 偏移（源侧用 rune），因为下游合并按 rune 切片。
     *
     * @param text      原文
     * @param spans     受保护片段（char 偏移，已排序不重叠）
     * @param separators 分隔符优先级
     * @param chunkSize 目标块大小（传给递归切分用）
     * @return 单元列表（code point 偏移）
     */
    private static List<KbTextUnits.SplitUnit> buildUnitsWithProtection(
        String text,
        List<KbProtectedSpans.Span> spans,
        List<String> separators,
        int chunkSize
    ) {
        List<KbTextUnits.SplitUnit> units = new ArrayList<>();
        int charPos = 0;
        int cpPos = 0;

        for (KbProtectedSpans.Span p : spans) {
            if (p.start() > charPos) {
                String pre = text.substring(charPos, p.start());
                List<String> parts = splitBySeparators(pre, separators, chunkSize);
                int runeOffset = cpPos;
                for (String part : parts) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    int partLen = KbTextUnits.codePointLength(part);
                    units.add(new KbTextUnits.SplitUnit(part, runeOffset, runeOffset + partLen));
                    runeOffset += partLen;
                }
                cpPos += KbTextUnits.codePointLength(pre);
            }

            String protText = text.substring(p.start(), p.end());
            int protLen = KbTextUnits.codePointLength(protText);

            if (protLen > MAX_PROTECTED_SIZE) {
                // 受保护片段过长 ⇒ 强制切成多段
                units.addAll(forceSplit(protText, cpPos));
            } else {
                units.add(new KbTextUnits.SplitUnit(protText, cpPos, cpPos + protLen));
            }
            cpPos += protLen;
            charPos = p.end();
        }

        if (charPos < text.length()) {
            String remaining = text.substring(charPos);
            List<String> parts = splitBySeparators(remaining, separators, chunkSize);
            int runeOffset = cpPos;
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                int partLen = KbTextUnits.codePointLength(part);
                units.add(new KbTextUnits.SplitUnit(part, runeOffset, runeOffset + partLen));
                runeOffset += partLen;
            }
        }

        return units;
    }

    /**
     * 强制切分超长文本 · 源侧 {@code splitter.go:331-368} 与 {@code :430-455} 同一算法。
     *
     * <p>每 {@value #MAX_PROTECTED_SIZE} 个 code point 切一刀；切点前在
     * {@value #FORCE_SPLIT_LOOKBACK} 个 code point 内向后找最近的换行或空格，
     * 找到就在其后断（避免切断单词/句子）。
     *
     * @param text        待切文本
     * @param baseOffset  该文本在原文中的起始 code point 偏移
     * @return 切出的单元
     */
    private static List<KbTextUnits.SplitUnit> forceSplit(String text, int baseOffset) {
        List<KbTextUnits.SplitUnit> out = new ArrayList<>();
        int totalCp = KbTextUnits.codePointLength(text);
        int offset = 0; // code point 偏移
        while (offset < totalCp) {
            int chunkEnd = Math.min(offset + MAX_PROTECTED_SIZE, totalCp);
            if (chunkEnd < totalCp) {
                // 在 [chunkEnd-256, chunkEnd) 之间回找换行/空格（源侧 i > chunkEnd-200）
                int searchFrom = Math.max(offset, chunkEnd - FORCE_SPLIT_LOOKBACK);
                for (int i = chunkEnd - 1; i > searchFrom; i--) {
                    char c = charAtCp(text, i);
                    if (c == '\n' || c == ' ') {
                        chunkEnd = i + 1;
                        break;
                    }
                }
            }
            int fromChar = text.offsetByCodePoints(0, offset);
            int toChar = text.offsetByCodePoints(0, chunkEnd);
            out.add(new KbTextUnits.SplitUnit(
                text.substring(fromChar, toChar),
                baseOffset + offset,
                baseOffset + chunkEnd));
            offset = chunkEnd;
        }
        return out;
    }

    /** 取第 {@code cpIndex} 个 code point 对应的起始 char 的字符（仅用于比较是否为换行/空格）。 */
    private static char charAtCp(String text, int cpIndex) {
        return text.charAt(text.offsetByCodePoints(0, cpIndex));
    }

    /**
     * 按分隔符优先级递归切分 · 对齐源侧 {@code splitBySeparators}
     * （{@code splitter.go:224-270}）。
     *
     * <p>语义（源侧注释）：
     * <ul>
     *   <li>依次尝试每个分隔符；某分隔符切不出多片就试下一个</li>
     *   <li>切分时<b>保留分隔符本身</b>（它作为独立片追加，因为中文「。」等本身有语义）</li>
     *   <li>仍超长的片用<b>剩余</b>分隔符继续递归切（不是从头）</li>
     * </ul>
     *
     * @param text       待切文本
     * @param separators 分隔符优先级
     * @param chunkSize  目标块大小（&lt;=0 表示不做尺寸约束）
     * @return 切出的片
     */
    static List<String> splitBySeparators(String text, List<String> separators, int chunkSize) {
        if (text == null || text.isEmpty() || separators == null || separators.isEmpty()) {
            return List.of(text == null ? "" : text);
        }
        if (chunkSize > 0 && KbTextUnits.codePointLength(text) <= chunkSize) {
            return List.of(text);
        }

        for (int i = 0; i < separators.size(); i++) {
            String sep = separators.get(i);
            if (sep == null || sep.isEmpty()) {
                continue;
            }
            Pattern pattern = Pattern.compile("(" + Pattern.quote(sep) + ")");
            String[] splits = pattern.split(text, -1);
            List<String> matches = new ArrayList<>();
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                matches.add(m.group());
            }
            if (matches.isEmpty()) {
                continue;
            }

            // 交替拼：split 片段 + 匹配到的分隔符
            List<String> pieces = new ArrayList<>();
            for (int j = 0; j < splits.length; j++) {
                if (!splits[j].isEmpty()) {
                    pieces.add(splits[j]);
                }
                if (j < matches.size() && !matches.get(j).isEmpty()) {
                    pieces.add(matches.get(j));
                }
            }
            if (pieces.size() <= 1) {
                continue;
            }

            // 仍超长的片用剩余分隔符递归
            List<String> out = new ArrayList<>();
            List<String> remaining = separators.subList(i + 1, separators.size());
            for (String piece : pieces) {
                if (chunkSize > 0 && KbTextUnits.codePointLength(piece) > chunkSize
                    && !remaining.isEmpty()) {
                    out.addAll(splitBySeparators(piece, remaining, chunkSize));
                } else {
                    out.add(piece);
                }
            }
            return out;
        }
        return List.of(text);
    }

    // ── Step 3 ──────────────────────────────────────────────────────

    /**
     * 合并单元成块（带重叠、表头前置、绝对上限）· 对齐源侧
     * {@code mergeUnits}（{@code splitter.go:402-529}）。
     *
     * @param units        单元列表
     * @param chunkSize    目标块大小
     * @param chunkOverlap 重叠大小
     * @return 分块列表
     */
    static List<KbChunk> mergeUnits(List<KbTextUnits.SplitUnit> units, int chunkSize, int chunkOverlap) {
        if (units.isEmpty()) {
            return List.of();
        }

        KbHeaderTracker tracker = new KbHeaderTracker();
        List<KbChunk> chunks = new ArrayList<>();
        List<KbTextUnits.SplitUnit> current = new ArrayList<>();
        int curLen = 0;

        for (KbTextUnits.SplitUnit u : units) {
            int uLen = u.length();

            // ── 单个单元超过绝对上限 ⇒ 强制切（先落盘当前块）──────────
            if (uLen > ABSOLUTE_MAX_SIZE) {
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, chunks.size()));
                    current = new ArrayList<>();
                    curLen = 0;
                }
                tracker.update(u.text());

                int offset = 0;
                int totalCp = uLen;
                while (offset < totalCp) {
                    int chunkEnd = Math.min(offset + ABSOLUTE_MAX_SIZE, totalCp);
                    if (chunkEnd < totalCp) {
                        int searchFrom = Math.max(offset, chunkEnd - FORCE_SPLIT_LOOKBACK);
                        for (int i = chunkEnd - 1; i > searchFrom; i--) {
                            char c = charAtCp(u.text(), i);
                            if (c == '\n' || c == ' ') {
                                chunkEnd = i + 1;
                                break;
                            }
                        }
                    }
                    int fromChar = u.text().offsetByCodePoints(0, offset);
                    int toChar = u.text().offsetByCodePoints(0, chunkEnd);
                    chunks.add(new KbChunk(
                        u.text().substring(fromChar, toChar), "", chunks.size(),
                        u.start() + offset, u.start() + chunkEnd));
                    offset = chunkEnd;
                }
                continue;
            }

            tracker.update(u.text());

            // 新表开始 ⇒ 先落盘（否则下一张表会继承上一张的表头上下文）
            if (tracker.headerEndedThisUnit() && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));
                current = new ArrayList<>();
                curLen = 0;
            }

            String headers = tracker.getHeaders();
            int headersLen = KbTextUnits.codePointLength(headers);
            if (headersLen > chunkSize) {
                headers = "";
                headersLen = 0;
            }

            // 加入本单元（含为下一块预留表头空间）会超限 ⇒ 落盘 + 保留重叠
            if (curLen + uLen + headersLen > chunkSize && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));

                // 保留尾部重叠（可能再因表头空间收缩）
                Overlap over = computeOverlap(current, chunkOverlap, chunkSize, uLen);
                List<KbTextUnits.SplitUnit> ovUnits = over.units();
                int ovLen = over.length();
                current = new ArrayList<>(ovUnits);
                curLen = ovLen;

                if (!headers.isEmpty() && headersLen + uLen <= chunkSize) {
                    // 收缩重叠直到能容纳表头
                    while (!current.isEmpty() && curLen + uLen + headersLen > chunkSize) {
                        curLen -= current.get(0).length();
                        current.remove(0);
                    }
                    String overlapText = KbTextUnits.joinText(current);
                    if (!headerAlreadyPresent(headers, overlapText, u.text())
                        && !KbHeaderTracker.headerColumnMismatch(headers, u.text())) {
                        int startPos = current.isEmpty() ? u.start() : current.get(0).start();
                        // 合成零宽单元：start == end，不占原文位置
                        current.add(0, new KbTextUnits.SplitUnit(headers, startPos, startPos));
                        curLen += headersLen;
                    }
                }
            }

            // 绝对上限保护
            if (curLen + uLen > ABSOLUTE_MAX_SIZE && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));
                current = new ArrayList<>();
                curLen = 0;
            }

            current.add(u);
            curLen += uLen;
        }

        if (!current.isEmpty()) {
            chunks.add(buildChunk(current, chunks.size()));
        }
        return chunks;
    }

    /** 单元列表 → 块 · 对齐源侧 {@code buildChunk}（{@code splitter.go:577-587}）。 */
    private static KbChunk buildChunk(List<KbTextUnits.SplitUnit> units, int seq) {
        StringBuilder sb = new StringBuilder();
        for (KbTextUnits.SplitUnit u : units) {
            sb.append(u.text());
        }
        return new KbChunk(
            sb.toString(),
            "",
            seq,
            units.get(0).start(),
            units.get(units.size() - 1).end());
    }

    /**
     * 计算重叠后缀 · 对齐源侧 {@code computeOverlap}（{@code splitter.go:613-660}）。
     *
     * <p><b>关键语义</b>（源侧长注释）：配置的重叠是<b>硬上限</b>，不是原始字符切片。
     * 边界检测可多看 4 个 rune 的 lookbehind，但<b>保留内容绝不超上限</b>。
     * 边界优先级：段落断 &gt; 行断 &gt; 句末；同优先级取<b>最早</b>（保留更多有用上下文）。
     * 窗口内无合法边界 ⇒ 不保留重叠（确定性且不越界）。
     *
     * @return {@code [List<SplitUnit> 重叠单元, Integer 重叠长度]}
     */
    private static Overlap computeOverlap(
        List<KbTextUnits.SplitUnit> current, int chunkOverlap, int chunkSize, int nextLen) {
        if (chunkOverlap <= 0) {
            return Overlap.EMPTY;
        }
        int maxOverlap = chunkOverlap;
        int remainingBudget = chunkSize - nextLen;
        if (remainingBudget < maxOverlap) {
            maxOverlap = remainingBudget;
        }
        if (maxOverlap <= 0) {
            return Overlap.EMPTY;
        }

        // 语义边界最长 2 个 rune（\r\n\r\n 是 4）—— 多看 4 个
        int lookbehind = 4;
        List<KbTextUnits.SplitUnit> window =
            KbTextUnits.semanticOverlapWindow(current, maxOverlap + lookbehind);
        if (window.isEmpty()) {
            return Overlap.EMPTY;
        }

        String windowText = KbTextUnits.joinText(window);
        int originalWindowStart = KbTextUnits.codePointLength(windowText) - maxOverlap;
        if (originalWindowStart < 0) {
            originalWindowStart = 0;
        }
        int boundaryEnd = KbSemanticBoundary.findBoundaryEndingAtOrAfter(
            windowText, originalWindowStart);
        if (boundaryEnd < 0) {
            return Overlap.EMPTY;
        }

        List<KbTextUnits.SplitUnit> overlap = KbTextUnits.trimUnitsPrefix(window, boundaryEnd);
        int overlapLen = 0;
        for (KbTextUnits.SplitUnit u : overlap) {
            overlapLen += u.length();
        }
        if (overlapLen <= 0 || overlapLen > maxOverlap
            || KbTextUnits.joinText(overlap).strip().isEmpty()) {
            return Overlap.EMPTY;
        }
        return new Overlap(overlap, overlapLen);
    }

    /**
     * 表头是否已存在于重叠或下一单元中 · 对齐源侧 {@code headerAlreadyPresent}（{@code :540-558}）。
     *
     * <p>先找完整表头；找不到则退一步只比「列名行」——
     * 因为重叠部分可能只含列名行而不含分隔行。
     */
    private static boolean headerAlreadyPresent(String headers, String overlapText, String unitText) {
        if (overlapText.contains(headers) || unitText.contains(headers)) {
            return true;
        }
        String colRow = headerColumnRow(headers);
        if (colRow.isEmpty()) {
            return false;
        }
        return overlapText.contains(colRow) || unitText.contains(colRow);
    }

    /** 取表头里的列名行（首个非空、非分隔行的行）· 对齐源侧 {@code headerColumnRow}（{@code :560-575}）。 */
    private static String headerColumnRow(String header) {
        for (String line : header.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.contains("---")) {
                continue;
            }
            boolean onlyPipes = true;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '|' && c != ' ' && c != '\t') {
                    onlyPipes = false;
                    break;
                }
            }
            if (!onlyPipes) {
                return t;
            }
        }
        return "";
    }

    /**
     * 重叠计算结果（替代原来的 {@code Object[]} 二元组）。
     *
     * <p><b>WHY 用 record 而不是 {@code Object[]}</b>：{@code Object[]} 的
     * {@code [0]=List, [1]=Integer} 约定只存在于注释里，调用方必须
     * {@code @SuppressWarnings("unchecked")} + 强转；类型错位只会在运行期炸。
     * record 让契约进编译器。
     *
     * @param units  重叠单元（空列表 = 不保留重叠）
     * @param length 重叠的 rune 长度（0 = 不保留重叠）
     */
    private record Overlap(List<KbTextUnits.SplitUnit> units, int length) {

        /** 不保留重叠。 */
        static final Overlap EMPTY = new Overlap(List.of(), 0);
    }
}

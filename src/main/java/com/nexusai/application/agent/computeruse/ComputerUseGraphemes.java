package com.nexusai.application.agent.computeruse;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * 字形簇（grapheme cluster）切分 · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2548-2565} 的
 * {@code segmentGraphemes}。
 *
 * <p><b>WHY 要切字形簇而不是码点</b>：CCB 原文注释（{@code toolCalls.ts:2545-2547}）：
 * <blockquote>
 * §6 item 7 — grapheme-cluster iteration. Prevents ZWJ emoji → �.
 * </blockquote>
 * ⇒ 一个 emoji（👨‍👩‍👧 = 7 个码点含 ZWJ）必须<b>整体</b>注入，
 * 否则组合序列会被拆散、渲染成豆腐块。
 *
 * <p><b>实现</b>：JDK 的 {@link BreakIterator#getCharacterInstance()} 实现了
 * UAX #29 的字形簇规则（GB1–GB999，含 GB3「CR × LF 不切」、GB9「× Extend/ZWJ」、
 * GB11「emoji ZWJ 序列」）。
 * <br>⚠️ <b>JDK Unicode 数据版本会漂</b>（GB11/GB12/GB13 的覆盖随版本变化）⇒
 * 额外补一条<b>显式 CRLF 合并</b>（UAX #29 GB3 是不变量，任何版本都必须成立），
 * 不依赖 JDK 的数据版本。
 *
 * <p><b>为什么不用 {@code java.text.BreakIterator} 的句子/词实例</b>：那两个是
 * 词边界（{@code getWordInstance}）/ 句边界（{@code getSentenceInstance}），
 * 与字形簇无关。
 */
public final class ComputerUseGraphemes {

    private ComputerUseGraphemes() {
    }

    /** U+200D ZERO WIDTH JOINER（UAX #29 GB9/GB11）。 */
    private static final String ZWJ = "\u200D";

    /**
     * 切分字形簇。
     *
     * @param text 原始文本（null / 空 ⇒ 空列表）
     * @return 字形簇列表（顺序与原文一致）
     */
    public static List<String> segment(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            out.add(text.substring(start, end));
        }
        return merge(out);
    }

    /**
     * 显式合并两处 Unicode 不变量（不依赖 JDK 的 Unicode 数据版本）。
     *
     * <p><b>WHY 显式做一遍</b>：JDK 的 BreakIterator 用的 Unicode 数据版本会随 JDK 升级变化
     * ⇒ 这两条规则在<b>本仓</b>层面固化成不变量：
     * <ol>
     *   <li><b>GB3</b>：{@code CR × LF} —— CRLF 是一个字形簇（换行必须走 Return 键，
     *       若被切成两簇就会打出一个多余的 Return）。</li>
     *   <li><b>GB9/GB11</b>：{@code × ZWJ} / emoji ZWJ 序列 —— 簇尾是 U+200D 时，
     *       下一个簇必须并进来（否则 👨‍👩‍👧 被拆成 3 簇 ⇒ 组合序列断裂 ⇒ 豆腐块）。</li>
     * </ol>
     *
     * @param graphemes 原始切分结果
     * @return 合并后的切分结果
     */
    private static List<String> merge(List<String> graphemes) {
        List<String> out = new ArrayList<>(graphemes.size());
        for (String current : graphemes) {
            if (!out.isEmpty()) {
                String previous = out.get(out.size() - 1);
                boolean crLf = "\r".equals(previous) && "\n".equals(current);
                boolean zwjContinuation = previous.endsWith(ZWJ);
                if (crLf || zwjContinuation) {
                    int last = out.size() - 1;
                    out.set(last, previous + current);
                    continue;
                }
            }
            out.add(current);
        }
        return out;
    }
}

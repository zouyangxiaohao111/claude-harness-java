package com.nexusai.kb.app.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分单元与坐标系工具。
 *
 * <p><b>⚠️ 三个坐标系（搞混会静默错位，本文件是唯一换算处）</b>：
 * <table>
 *   <tr><th>坐标系</th><th>单位</th><th>用途</th></tr>
 *   <tr><td><b>char 偏移</b></td><td>Java {@code String} 下标（UTF-16 code unit）</td>
 *       <td>正则匹配、{@code indexOf}、{@code substring}</td></tr>
 *   <tr><td><b>code point 偏移</b></td><td>Unicode 码点（≈ Go 的 rune）</td>
 *       <td><b>落库的 start_at / end_at</b>；跨语言对齐源侧</td></tr>
 *   <tr><td><b>byte 偏移</b></td><td>UTF-8 字节</td><td>本实现<b>不用</b></td></tr>
 * </table>
 *
 * <p><b>为什么落库用 code point 而不是 char</b>：源侧 Go 的 {@code start_at}/{@code end_at}
 * 是 <b>rune</b> 偏移；Java 的 char 遇到 emoji / 生僻字（代理对）会比 rune 多算 1。
 * 若直接用 char 落库，含 emoji 的文档长度会与源侧不一致。
 */
final class KbTextUnits {

    /**
     * 切分单元 · 对齐源侧 {@code chunker.splitUnit}（{@code splitter.go:210-213}）。
     *
     * <p>{@code start}/{@code end} 是 <b>code point</b> 偏移（源侧 Go rune）。
     * 合成的「零宽单元」（如表头）满足 {@code start == end}。
     *
     * @param text  单元文本
     * @param start 起始 code point 偏移
     * @param end   结束 code point 偏移（不含）
     */
    record SplitUnit(String text, int start, int end) {
        int length() {
            return codePointLength(text);
        }
    }

    private KbTextUnits() {
    }

    /** 计算字符串的 code point 数（≈ Go {@code utf8.RuneCountInString}）。 */
    static int codePointLength(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.codePointCount(0, s.length());
    }

    /**
     * 把 char 偏移换算成 code point 偏移。
     *
     * @param text 文本
     * @param charOffset char 偏移（0..text.length()）
     * @return code point 偏移
     */
    static int codePointOffset(String text, int charOffset) {
        if (text == null || text.isEmpty() || charOffset <= 0) {
            return 0;
        }
        int cap = Math.min(charOffset, text.length());
        return text.codePointCount(0, cap);
    }

    /** 拼接若干单元文本 · 对齐源侧 {@code unitsText}（{@code splitter.go:532-538}）。 */
    static String joinText(List<SplitUnit> units) {
        StringBuilder sb = new StringBuilder();
        for (SplitUnit u : units) {
            sb.append(u.text());
        }
        return sb.toString();
    }

    /**
     * 从尾部截取最多 {@code maxLen} 个 code point 的窗口 · 对齐源侧
     * {@code semanticOverlapWindow}（{@code splitter.go:662-705}）。
     *
     * <p>三条规则（源侧注释明文）：
     * <ol>
     *   <li>从后往前取；单个单元太长时<b>在单元中间切</b>（这样大段落内部的语义边界仍可被发现）</li>
     *   <li>空文本单元跳过</li>
     *   <li><b>合成的零宽单元（表头）形成硬屏障</b> —— 遇到就停，
     *       跨过去会破坏 {@code End - Start == 内容长度} 的原文位置不变式</li>
     * </ol>
     *
     * @param current 当前已累积的单元
     * @param maxLen  最多取多少 code point
     * @return 尾部窗口（保持原顺序）；无可用内容时返回空列表
     */
    static List<SplitUnit> semanticOverlapWindow(List<SplitUnit> current, int maxLen) {
        if (maxLen <= 0 || current == null || current.isEmpty()) {
            return List.of();
        }
        int remaining = maxLen;
        List<SplitUnit> reversed = new ArrayList<>();
        for (int i = current.size() - 1; i >= 0 && remaining > 0; i--) {
            SplitUnit u = current.get(i);
            int uLen = u.length();
            if (uLen == 0) {
                continue;
            }
            // 零宽单元（表头）：不取、也不跨过
            if (u.start() == u.end() || (u.end() - u.start()) != uLen) {
                break;
            }
            if (uLen <= remaining) {
                reversed.add(u);
                remaining -= uLen;
                continue;
            }
            // 单元本身超出剩余预算 ⇒ 从中间切开
            int startCp = uLen - remaining;
            int charStart = u.text().offsetByCodePoints(0, startCp);
            reversed.add(new SplitUnit(
                u.text().substring(charStart),
                u.start() + startCp,
                u.end()));
            remaining = 0;
        }
        if (reversed.isEmpty()) {
            return List.of();
        }
        List<SplitUnit> window = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            window.add(reversed.get(i));
        }
        return window;
    }

    /**
     * 从单元列表头部剥掉 {@code prefixLen} 个 code point · 对齐源侧
     * {@code trimUnitsPrefix}（{@code splitter.go:833-857}）。
     *
     * <p>位置（start/end）会随之平移 —— 保证剥完后仍指向原文正确区间。
     *
     * @param units     单元列表
     * @param prefixLen 要剥掉的 code point 数（相对 {@link #joinText} 的结果）
     * @return 剥完后的单元列表
     */
    static List<SplitUnit> trimUnitsPrefix(List<SplitUnit> units, int prefixLen) {
        if (prefixLen <= 0) {
            return new ArrayList<>(units);
        }
        int remaining = prefixLen;
        List<SplitUnit> out = new ArrayList<>(units.size());
        for (SplitUnit u : units) {
            int uLen = u.length();
            if (remaining >= uLen) {
                remaining -= uLen;
                continue;
            }
            SplitUnit next = u;
            if (remaining > 0) {
                int charStart = u.text().offsetByCodePoints(0, remaining);
                next = new SplitUnit(
                    u.text().substring(charStart),
                    u.start() + remaining,
                    u.end());
                remaining = 0;
            }
            out.add(next);
        }
        return out;
    }
}

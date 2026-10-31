package com.nexusai.application.agent.computeruse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统级快捷键黑名单 · <b>逐字对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/keyBlocklist.ts}</b>（Windows 分支）。
 *
 * <p><b>WHY 要做</b>：{@code key} 工具的描述（CCB {@code tools.ts:326-331}）明写
 * “System-level combos (quit app, switch app, lock screen) require the {@code systemKeyCombos}
 * grant — without it they return an error.” ⇒ 描述既然这么写，行为必须真的如此，
 * 否则等于对模型说谎（规则十二：显式失败）。
 *
 * <p><b>CCB 原形状（TS，逐字语义）</b>：
 * <pre>
 * const BLOCKED_WIN32 = new Set([
 *   'ctrl+alt+delete',  // Secure Attention Sequence
 *   'alt+f4',           // close window
 *   'alt+tab',          // window switcher
 *   'meta+l',           // Win+L — lock
 *   'meta+d'            // Win+D — show desktop
 * ])
 * </pre>
 *
 * <p><b>归一化（防绕过）</b>：CCB 把所有修饰键别名折叠成一个 canonical 名
 * （{@code meta|super|command|cmd|windows|win → meta}、
 * {@code ctrl|control|lctrl|lcontrol|rctrl|rcontrol → ctrl}、
 * {@code shift|lshift|rshift → shift}、{@code alt|option → alt}），
 * 修饰键按 {@code ctrl < alt < shift < meta} 排序去重 ⇒
 * {@code cmd+q} / {@code meta+q} / {@code command+q} 归一到同一条。
 *
 * <p><b>判定粒度</b>：CCB 检查「修饰键 + <b>每个</b>非修饰键」而不是整串精确匹配
 * ⇒ {@code cmd+q+a}（先触发 Cmd+Q 再触发 A）也会被拦。
 */
public final class ComputerUseKeyBlocklist {

    /** 修饰键别名 → canonical 名 · CCB {@code keyBlocklist.ts:25-47}。 */
    private static final Map<String, String> CANONICAL_MODIFIER = buildCanonicalModifier();

    /** canonical 修饰键排序 · CCB {@code keyBlocklist.ts:50}（{@code MODIFIER_ORDER}）。 */
    private static final List<String> MODIFIER_ORDER = List.of("ctrl", "alt", "shift", "meta");

    /** Windows 平台被拦的组合键 · CCB {@code keyBlocklist.ts:80-86}。 */
    private static final Set<String> BLOCKED_WIN32 = Set.of(
            "ctrl+alt+delete",  // Secure Attention Sequence
            "alt+f4",           // close window
            "alt+tab",          // window switcher
            "meta+l",           // Win+L — lock
            "meta+d"            // Win+D — show desktop
    );

    private ComputerUseKeyBlocklist() {
    }

    private static Map<String, String> buildCanonicalModifier() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("meta", "meta");
        m.put("super", "meta");
        m.put("command", "meta");
        m.put("cmd", "meta");
        m.put("windows", "meta");
        m.put("win", "meta");
        m.put("ctrl", "ctrl");
        m.put("control", "ctrl");
        m.put("lctrl", "ctrl");
        m.put("lcontrol", "ctrl");
        m.put("rctrl", "ctrl");
        m.put("rcontrol", "ctrl");
        m.put("shift", "shift");
        m.put("lshift", "shift");
        m.put("rshift", "shift");
        m.put("alt", "alt");
        m.put("option", "alt");
        return Map.copyOf(m);
    }

    /**
     * 分区：修饰键（canonical、去重、按 {@code MODIFIER_ORDER} 排序）与非修饰键。
     *
     * @param sequence 原始组合键串
     * @return 分区结果
     */
    private static Partition partition(String sequence) {
        List<String> mods = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String p : ComputerUseKeyCombo.splitParts(sequence)) {
            String canonical = CANONICAL_MODIFIER.get(p);
            if (canonical != null) {
                mods.add(canonical);
            } else {
                keys.add(p);
            }
        }
        Set<String> uniqueMods = new LinkedHashSet<>(mods);
        List<String> sortedMods = new ArrayList<>();
        for (String canonical : MODIFIER_ORDER) {
            if (uniqueMods.contains(canonical)) {
                sortedMods.add(canonical);
            }
        }
        return new Partition(sortedMods, keys);
    }

    /**
     * 归一化组合键串（{@code "Cmd + Shift + Q"} ⇒ {@code "shift+meta+q"}）。
     *
     * @param sequence 原始组合键串
     * @return 归一化后的串（小写、别名折叠、修饰键去重排序、非修饰键在后）
     */
    public static String normalizeKeySequence(String sequence) {
        Partition p = partition(sequence);
        List<String> out = new ArrayList<>(p.mods());
        out.addAll(p.keys());
        return String.join("+", out);
    }

    /**
     * 是否是「系统级快捷键」（Windows 平台）。
     *
     * @param sequence 组合键串
     * @return true = 命中 Windows 黑名单
     */
    public static boolean isSystemKeyCombo(String sequence) {
        Partition p = partition(sequence);
        String prefix = p.mods().isEmpty() ? "" : String.join("+", p.mods()) + "+";
        if (p.keys().isEmpty()) {
            // 只有修饰键（如 "ctrl+shift"）⇒ 整串查表（黑名单里没有纯修饰键条目 ⇒ 恒 false）
            return BLOCKED_WIN32.contains(String.join("+", p.mods()));
        }
        for (String key : p.keys()) {
            if (BLOCKED_WIN32.contains(prefix + key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 组合键被拦时的错误文案 · 逐字对齐 CCB
     * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2596-2599}。
     *
     * @param sequence 原始组合键串
     * @return 错误文案
     */
    public static String blockedMessage(String sequence) {
        return "\"" + sequence + "\" is a system-level shortcut. "
                + "Request the `systemKeyCombos` grant via request_access to use it.";
    }

    /**
     * <b>修饰键点击</b>被拦时的错误文案 · 逐字对齐 CCB
     * {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2414-2421}。
     *
     * <p>⚠️ 与 {@link #blockedMessage(String)}（{@code key} 工具的文案）<b>不同</b>：
     * 点击路径额外提示「只用修饰键」，因为点击的 {@code text} 参数语义就是「按住修饰键」。
     *
     * @param sequence 原始组合键串
     * @return 错误文案
     */
    public static String blockedModifierMessage(String sequence) {
        return "The modifier chord \"" + sequence + "\" would fire a system shortcut. "
                + "Request the systemKeyCombos grant flag via request_access, or use "
                + "only modifier keys (shift, ctrl, alt, cmd) in the text parameter.";
    }

    /**
     * 分区结果（修饰键 + 非修饰键）。
     *
     * @param mods canonical 修饰键（已去重排序）
     * @param keys 非修饰键（保持原顺序）
     */
    private record Partition(List<String> mods, List<String> keys) {
    }
}

package com.nexusai.application.agent.computeruse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 组合键解析 · <b>逐字对齐 CCB 2.8.4
 * {@code src/utils/computerUse/win32/windowMessage.ts:341-366} 的 {@code sendKeys}</b>。
 *
 * <p><b>CCB 原逻辑（TS，逐字语义）</b>：
 * <pre>
 * for (const key of combo) {
 *   const lower = key.toLowerCase()
 *   const vk = VK_MAP[lower]
 *   if (vk !== undefined) {
 *     if (MODIFIER_KEYS.has(lower)) {
 *       modifiers.push(vk)            // ← 修饰键：按<b>出现顺序</b>收集
 *     } else {
 *       mainKey = vk                  // ← 主键（最后一个非修饰键胜出）
 *     }
 *   } else if (lower.length === 1) {
 *     mainKey = lower.toUpperCase().charCodeAt(0)   // ← 单字符主键：取大写 ASCII 码
 *   } else {
 *     return false                    // ← 未知键名 ⇒ 失败
 *   }
 * }
 * if (mainKey === undefined) return false
 *
 * // Build script: modifiers down, key down, key up, modifiers up (reverse)
 * for (const mod of modifiers)            → KEYDOWN(mod)
 *                                        → KEYDOWN(mainKey)
 *                                        → KEYUP(mainKey)
 * for (const mk of modifiers.reverse())   → KEYUP(mk)
 * </pre>
 *
 * <p><b>派发顺序（本类的核心语义）</b>：修饰键<b>按下顺序 = 输入顺序</b>（不排序），
 * 主键 down → 主键 up，修饰键<b>逆序</b>释放（最后按下的最先释放）。
 *
 * <p><b>已知键名</b>：{@link ComputerUseVirtualKeys#VK_MAP}（CCB {@code shared.ts:31-86}）
 * + 单字符（取大写 ASCII 码作 VK）。修饰键名支持
 * {@code ctrl/control/shift/alt/option/win/meta/cmd/command/super} 及其左右变体
 * （CCB {@code shared.ts:88-107} 的 {@code MODIFIER_KEYS}）。
 */
public final class ComputerUseKeyCombo {

    private ComputerUseKeyCombo() {
    }

    /**
     * 解析组合键串 → 有序按键事件序列。
     *
     * @param sequence 组合键串（如 {@code "ctrl+shift+a"}）
     * @return 有序事件序列（按下/抬起混合）
     * @throws IllegalArgumentException 键名未知 / 无主键（CCB 对应 {@code return false}）
     */
    public static List<ComputerUseKeyEvent> parse(String sequence) {
        if (sequence == null || sequence.isBlank()) {
            throw new IllegalArgumentException("组合键不能为空");
        }
        // CCB executorCrossPlatform.ts:296 —— keySequence.split('+').filter(p => p.length > 0)
        List<String> parts = new ArrayList<>();
        for (String raw : sequence.split("\\+")) {
            String p = raw.trim();
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("组合键不能为空");
        }

        List<Integer> modifierVks = new ArrayList<>();
        List<String> modifierNames = new ArrayList<>();
        Integer mainVk = null;
        String mainName = null;

        for (String part : parts) {
            String lower = part.toLowerCase(Locale.ROOT);
            Integer vk = ComputerUseVirtualKeys.VK_MAP.get(lower);
            if (vk != null) {
                if (ComputerUseVirtualKeys.MODIFIER_KEYS.contains(lower)) {
                    modifierVks.add(vk);
                    modifierNames.add(lower);
                } else {
                    mainVk = vk;
                    mainName = lower;
                }
            } else if (lower.length() == 1) {
                // CCB windowMessage.ts:333 —— 单字符主键：取大写 ASCII 码作 VK
                mainVk = (int) lower.toUpperCase(Locale.ROOT).charAt(0);
                mainName = lower;
            } else {
                throw new IllegalArgumentException("未知键名: " + part);
            }
        }

        if (mainVk == null) {
            throw new IllegalArgumentException("组合键缺少主键: " + sequence);
        }

        List<ComputerUseKeyEvent> events = new ArrayList<>();
        // 修饰键：按输入顺序按下
        for (int i = 0; i < modifierVks.size(); i++) {
            events.add(ComputerUseKeyEvent.down(modifierNames.get(i), modifierVks.get(i)));
        }
        // 主键 down → up
        events.add(ComputerUseKeyEvent.down(mainName, mainVk));
        events.add(ComputerUseKeyEvent.up(mainName, mainVk));
        // 修饰键：逆序释放
        for (int i = modifierVks.size() - 1; i >= 0; i--) {
            events.add(ComputerUseKeyEvent.up(modifierNames.get(i), modifierVks.get(i)));
        }
        return List.copyOf(events);
    }

    /**
     * 组合键串 → 键名分段（{@code "ctrl+shift+a"} ⇒ {@code ["ctrl","shift","a"]}）。
     *
     * <p>用于系统级快捷键黑名单判定（对齐 CCB
     * {@code packages/@ant/computer-use-mcp/src/keyBlocklist.ts} 的 {@code partitionKeys}）。
     *
     * @param sequence 组合键串
     * @return 键名分段（小写、去空白、丢弃空段）
     */
    public static List<String> splitParts(String sequence) {
        List<String> parts = new ArrayList<>();
        if (sequence == null) {
            return parts;
        }
        for (String raw : sequence.toLowerCase(Locale.ROOT).split("\\+")) {
            String p = raw.trim();
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return parts;
    }

    /**
     * 键名 → 虚拟键码（{@link ComputerUseKeyEvent} 用的 {@code virtualKey}）。
     *
     * <p>解析规则与 {@link #parse(String)} <b>同款</b>：
     * <ul>
     *   <li>具名键（{@code "space"} / {@code "down"} / …）⇒ {@link ComputerUseVirtualKeys#VK_MAP}；</li>
     *   <li>单字符（{@code "a"}）⇒ <b>大写 ASCII 码</b>
     *       （CCB {@code windowMessage.ts:333-334}：“Single character — use its uppercase VK code”）；</li>
     *   <li>其余 ⇒ 未知键名（抛 {@link IllegalArgumentException}）。</li>
     * </ul>
     *
     * <p><b>WHY 单独暴露</b>：{@code hold_key} 工具（CCB {@code handleHoldKey}）要按住的是
     * chord 的<b>每一个</b>分段（CCB {@code parseKeyChord} 只切分、不区分修饰键/主键），
     * 而 {@link #parse(String)} 走的是「修饰键 + 唯一主键」的组合键语义 ⇒ 需要一个
     * 只做<b>键名 → VK</b> 解析的入口，避免两处各写一份 VK 映射规则。
     *
     * @param keyName 键名（大小写不敏感）
     * @return 虚拟键码
     * @throws IllegalArgumentException 未知键名
     */
    public static int resolveVirtualKey(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("键名不能为空");
        }
        String lower = keyName.trim().toLowerCase(Locale.ROOT);
        Integer vk = ComputerUseVirtualKeys.VK_MAP.get(lower);
        if (vk != null) {
            return vk;
        }
        if (lower.length() == 1) {
            return lower.toUpperCase(Locale.ROOT).charAt(0);
        }
        throw new IllegalArgumentException("未知键名: " + keyName);
    }
}

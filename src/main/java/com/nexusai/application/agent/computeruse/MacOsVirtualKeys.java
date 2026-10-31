package com.nexusai.application.agent.computeruse;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * macOS 虚拟键码表 · computer-use macOS 键盘注入底座。
 *
 * <h2>CCB 对照</h2>
 * <p>CCB 的 macOS 键名 → 键码表在
 * {@code packages/@ant/computer-use-input/src/backends/darwin.ts:14-43}（{@code KEY_MAP}）
 * 与 {@code :45-55}（{@code MODIFIER_MAP}）—— 本类<b>逐字搬运</b>：
 * <pre>
 * return/enter 36 · tab 48 · space 49 · delete/backspace 51 · escape/esc 53 ·
 * left 123 · right 124 · down 125 · up 126 · f1 122 · f2 120 · f3 99 · f4 118 ·
 * f5 96 · f6 97 · f7 98 · f8 100 · f9 101 · f10 109 · f11 103 · f12 111 ·
 * home 115 · end 119 · pageup 116 · pagedown 121
 * </pre>
 *
 * <h2>⚠️ 本仓相对 CCB 的补充（本仓事件模型需要）</h2>
 * <p>CCB 的 {@code MODIFIER_MAP} 把修饰键映射成 <b>AppleScript 的修饰键语法</b>
 * （{@code "command down"} / {@code "shift down"} / …），只在
 * <b>一次</b> {@code keys()} 调用里用（{@code … to keystroke "a" using {command down}}）。
 * <br>本仓的组合键是<b>逐事件</b>派发（{@link ComputerUseKeyCombo#parse(String)} 产出
 * 「修饰键 down → 主键 down → 主键 up → 修饰键 up」）⇒ 需要
 * <b>修饰键自己的虚拟键码</b>（按下 / 抬起）与<b>修饰键 flags</b>（写进后续按键事件）。
 * ⇒ 本类补齐：
 * <ul>
 *   <li>修饰键虚拟键码：command 55 / shift 56 / option 58 / control 59
 *       （及其左右变体）；</li>
 *   <li>修饰键 flags 常量名：{@code $.kCGEventFlagMaskShift / Control / Alternate / Command}。</li>
 *   <li>ASCII 字符 → macOS ANSI 布局键码（字母 / 数字 / 常用符号）——
 *       CCB 的 macOS 路径用 AppleScript {@code keystroke} 打字符，不需要键码；
 *       本仓走 CoreGraphics（见 {@link MacOsKeyboard}）⇒ 需要。</li>
 * </ul>
 *
 * <h2>Windows 专有键</h2>
 * <p>{@code scrolllock} / {@code printscreen} 在 macOS 上<b>没有对应键</b>
 * ⇒ 显式抛错（fail loud，不静默丢键）。
 */
public final class MacOsVirtualKeys {

    /**
     * macOS 修饰键 · 键码 + CoreGraphics flags 常量。
     *
     * @param name       规范名（与 CCB {@code MODIFIER_MAP} 的语义名一致）
     * @param keyCode    macOS 虚拟键码（按下 / 抬起该键用）
     * @param flagSymbol CoreGraphics flags 常量的 JS 符号（{@code $.kCGEventFlagMaskXxx}）
     */
    public enum Modifier {
        /** Shift（macOS 虚拟键码 56）。 */
        SHIFT("shift", 56, "$.kCGEventFlagMaskShift"),
        /** Control / Ctrl（macOS 虚拟键码 59）。 */
        CONTROL("control", 59, "$.kCGEventFlagMaskControl"),
        /** Option / Alt（macOS 虚拟键码 58）。 */
        OPTION("option", 58, "$.kCGEventFlagMaskAlternate"),
        /** Command / Cmd / Meta / Super（macOS 虚拟键码 55）。 */
        COMMAND("command", 55, "$.kCGEventFlagMaskCommand");

        private final String name;
        private final int keyCode;
        private final String flagSymbol;

        Modifier(String name, int keyCode, String flagSymbol) {
            this.name = name;
            this.keyCode = keyCode;
            this.flagSymbol = flagSymbol;
        }

        /** @return 规范名 */
        public String keyName() {
            return name;
        }

        /** @return macOS 虚拟键码 */
        public int keyCode() {
            return keyCode;
        }

        /** @return CoreGraphics flags 常量的 JS 符号 */
        public String flagSymbol() {
            return flagSymbol;
        }
    }

    /** 具名键 → 键码 · CCB {@code KEY_MAP} 逐字 + macOS 补充项。 */
    public static final Map<String, Integer> NAMED_KEYS = buildNamedKeys();

    /** 修饰键名 → {@link Modifier} · 别名逐字抄 CCB {@code MODIFIER_MAP}。 */
    public static final Map<String, Modifier> MODIFIER_KEYS = buildModifierKeys();

    /** 单字符 → 键码（macOS ANSI 布局）。 */
    public static final Map<Character, Integer> ASCII_KEYS = buildAsciiKeys();

    private MacOsVirtualKeys() {
        throw new AssertionError("utility class");
    }

    /**
     * 查键名对应的虚拟键码。
     *
     * @param keyName 键名（小写；本仓的 {@link ComputerUseKeyEvent#name()} 恒为小写）
     * @return macOS 虚拟键码
     * @throws IllegalArgumentException 键名在 macOS 上不存在 / 无对应键
     */
    public static int keyCodeOf(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("[computer-use] 键名不能为空");
        }
        String lower = keyName.trim().toLowerCase(Locale.ROOT);
        Modifier modifier = MODIFIER_KEYS.get(lower);
        if (modifier != null) {
            return modifier.keyCode();
        }
        Integer named = NAMED_KEYS.get(lower);
        if (named != null) {
            return named;
        }
        if (lower.length() == 1) {
            Integer ascii = ASCII_KEYS.get(lower.charAt(0));
            if (ascii != null) {
                return ascii;
            }
        }
        throw new IllegalArgumentException(
                "[computer-use] 键名在 macOS 上没有对应键: " + keyName
                        + "（scrolllock / printscreen 等为 Windows 专有键）");
    }

    /**
     * 该键名是否是修饰键。
     *
     * @param keyName 键名
     * @return true = 修饰键
     */
    public static boolean isModifier(String keyName) {
        return keyName != null && MODIFIER_KEYS.containsKey(keyName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 查键名对应的修饰键。
     *
     * @param keyName 键名
     * @return 修饰键；非修饰键 ⇒ null
     */
    public static Modifier modifierOf(String keyName) {
        if (keyName == null) {
            return null;
        }
        return MODIFIER_KEYS.get(keyName.trim().toLowerCase(Locale.ROOT));
    }

    /** CCB {@code KEY_MAP}（{@code input/backends/darwin.ts:14-43}）逐字 + macOS 补充。 */
    private static Map<String, Integer> buildNamedKeys() {
        Map<String, Integer> m = new LinkedHashMap<>();
        // ── CCB 原表（逐字）──
        m.put("return", 36);
        m.put("enter", 36);
        m.put("tab", 48);
        m.put("space", 49);
        m.put("delete", 51);
        m.put("backspace", 51);
        m.put("escape", 53);
        m.put("esc", 53);
        m.put("left", 123);
        m.put("right", 124);
        m.put("down", 125);
        m.put("up", 126);
        m.put("f1", 122);
        m.put("f2", 120);
        m.put("f3", 99);
        m.put("f4", 118);
        m.put("f5", 96);
        m.put("f6", 97);
        m.put("f7", 98);
        m.put("f8", 100);
        m.put("f9", 101);
        m.put("f10", 109);
        m.put("f11", 103);
        m.put("f12", 111);
        m.put("home", 115);
        m.put("end", 119);
        m.put("pageup", 116);
        m.put("pagedown", 121);
        // ── macOS 补充（本仓 Win32 键名表里存在、macOS 也有对应键的）──
        m.put("insert", 114);   // kVK_Help / kVK_Insert
        m.put("capslock", 57);  // kVK_CapsLock
        m.put("pause", 113);    // Pause 在 Apple 键盘上发 F15（kVK_F15）
        m.put("numlock", 71);   // 数字锁定键在 macOS 上发 Clear（kVK_Clear）
        return Map.copyOf(m);
    }

    /** 修饰键别名表 · CCB {@code MODIFIER_MAP}（{@code :45-55}）逐字 + 左右变体。 */
    private static Map<String, Modifier> buildModifierKeys() {
        Map<String, Modifier> m = new LinkedHashMap<>();
        m.put("command", Modifier.COMMAND);
        m.put("cmd", Modifier.COMMAND);
        m.put("meta", Modifier.COMMAND);
        m.put("super", Modifier.COMMAND);
        m.put("win", Modifier.COMMAND);      // Windows 键在 macOS 上对应 command
        m.put("shift", Modifier.SHIFT);
        m.put("lshift", Modifier.SHIFT);
        m.put("rshift", Modifier.SHIFT);
        m.put("option", Modifier.OPTION);
        m.put("alt", Modifier.OPTION);
        m.put("lalt", Modifier.OPTION);
        m.put("ralt", Modifier.OPTION);
        m.put("menu", Modifier.OPTION);      // Win32 的 VK_MENU 就是 Alt
        m.put("control", Modifier.CONTROL);
        m.put("ctrl", Modifier.CONTROL);
        m.put("lcontrol", Modifier.CONTROL);
        m.put("rcontrol", Modifier.CONTROL);
        return Map.copyOf(m);
    }

    /** 单字符 → macOS ANSI 布局虚拟键码。 */
    private static Map<Character, Integer> buildAsciiKeys() {
        Map<Character, Integer> m = new LinkedHashMap<>();
        // 字母（macOS ANSI 布局，非 ASCII 顺序）
        m.put('a', 0);
        m.put('s', 1);
        m.put('d', 2);
        m.put('f', 3);
        m.put('h', 4);
        m.put('g', 5);
        m.put('z', 6);
        m.put('x', 7);
        m.put('c', 8);
        m.put('v', 9);
        m.put('b', 11);
        m.put('q', 12);
        m.put('w', 13);
        m.put('e', 14);
        m.put('r', 15);
        m.put('y', 16);
        m.put('t', 17);
        m.put('o', 31);
        m.put('u', 32);
        m.put('i', 34);
        m.put('p', 35);
        m.put('l', 37);
        m.put('j', 38);
        m.put('k', 40);
        m.put('n', 45);
        m.put('m', 46);
        // 数字
        m.put('1', 18);
        m.put('2', 19);
        m.put('3', 20);
        m.put('4', 21);
        m.put('6', 22);
        m.put('5', 23);
        m.put('9', 25);
        m.put('7', 26);
        m.put('8', 28);
        m.put('0', 29);
        // 常用符号
        m.put('=', 24);
        m.put('-', 27);
        m.put(']', 30);
        m.put('[', 33);
        m.put('\'', 39);
        m.put(';', 41);
        m.put('\\', 42);
        m.put(',', 43);
        m.put('/', 44);
        m.put('.', 47);
        m.put('`', 50);
        m.put(' ', 49);
        return Map.copyOf(m);
    }
}

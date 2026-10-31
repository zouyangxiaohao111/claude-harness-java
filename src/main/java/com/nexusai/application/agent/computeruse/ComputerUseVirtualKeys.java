package com.nexusai.application.agent.computeruse;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Win32 虚拟键码表 · <b>逐字对齐 CCB 2.8.4
 * {@code src/utils/computerUse/win32/shared.ts:31-86} 的 {@code VK_MAP} 与
 * {@code :88-107} 的 {@code MODIFIER_KEYS}</b>。
 *
 * <p><b>WHY 要抄 CCB 的表</b>：组合键的语义（哪些名字是修饰键、哪些是主键、
 * 修饰键按下/释放的顺序）必须与 CCB <b>逐字一致</b>，否则同一个 {@code "ctrl+shift+a"}
 * 在两边触发不同的键序列。CCB 是判据（不是 CC 官方）。
 *
 * <p><b>单字符主键的 VK 取法</b>：CCB {@code windowMessage.ts:331-334}
 * “Single character — use its uppercase VK code” ⇒ {@code lower.toUpperCase().charCodeAt(0)}。
 * 这在 Win32 上成立：{@code VK_A..VK_Z = 0x41..0x5A}、{@code VK_0..VK_9 = 0x30..0x39}、
 * {@code VK_SPACE = 0x20}，恰是 ASCII 大写/数字/空格。
 */
public final class ComputerUseVirtualKeys {

    /** 虚拟键码表 · CCB {@code shared.ts:31-86}（{@code VK_MAP} 逐字搬运）。 */
    public static final Map<String, Integer> VK_MAP = buildVkMap();

    /** 修饰键名集合 · CCB {@code shared.ts:88-107}（{@code MODIFIER_KEYS} 逐字搬运）。 */
    public static final Set<String> MODIFIER_KEYS = buildModifierKeys();

    private ComputerUseVirtualKeys() {
    }

    private static Map<String, Integer> buildVkMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("backspace", 0x08);
        m.put("tab", 0x09);
        m.put("enter", 0x0d);
        m.put("return", 0x0d);
        m.put("shift", 0x10);
        m.put("lshift", 0xa0);
        m.put("rshift", 0xa1);
        m.put("ctrl", 0x11);
        m.put("control", 0x11);
        m.put("lcontrol", 0xa2);
        m.put("rcontrol", 0xa3);
        m.put("alt", 0x12);
        m.put("option", 0x12);
        m.put("menu", 0x12);
        m.put("lalt", 0xa4);
        m.put("ralt", 0xa5);
        m.put("pause", 0x13);
        m.put("capslock", 0x14);
        m.put("escape", 0x1b);
        m.put("esc", 0x1b);
        m.put("space", 0x20);
        m.put("pageup", 0x21);
        m.put("pagedown", 0x22);
        m.put("end", 0x23);
        m.put("home", 0x24);
        m.put("left", 0x25);
        m.put("up", 0x26);
        m.put("right", 0x27);
        m.put("down", 0x28);
        m.put("insert", 0x2d);
        m.put("delete", 0x2e);
        m.put("win", 0x5b);
        m.put("meta", 0x5b);
        m.put("command", 0x5b);
        m.put("cmd", 0x5b);
        m.put("super", 0x5b);
        m.put("numlock", 0x90);
        m.put("scrolllock", 0x91);
        m.put("printscreen", 0x2c);
        m.put("f1", 0x70);
        m.put("f2", 0x71);
        m.put("f3", 0x72);
        m.put("f4", 0x73);
        m.put("f5", 0x74);
        m.put("f6", 0x75);
        m.put("f7", 0x76);
        m.put("f8", 0x77);
        m.put("f9", 0x78);
        m.put("f10", 0x79);
        m.put("f11", 0x7a);
        m.put("f12", 0x7b);
        return Map.copyOf(m);
    }

    private static Set<String> buildModifierKeys() {
        Set<String> s = new LinkedHashSet<>();
        s.add("shift");
        s.add("lshift");
        s.add("rshift");
        s.add("control");
        s.add("ctrl");
        s.add("lcontrol");
        s.add("rcontrol");
        s.add("alt");
        s.add("option");
        s.add("lalt");
        s.add("ralt");
        s.add("win");
        s.add("meta");
        s.add("command");
        s.add("cmd");
        s.add("super");
        return Set.copyOf(s);
    }
}

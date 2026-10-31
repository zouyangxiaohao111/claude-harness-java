package com.nexusai.application.agent.computeruse;

/**
 * 一个按键事件（按下 / 抬起）· computer-use 键盘注入的最小单元。
 *
 * <p><b>WHY 事件而不是"组合键串"</b>：CCB 的组合键语义
 * （{@code src/utils/computerUse/win32/windowMessage.ts:341-366}）是
 * <b>修饰键依次按下 → 主键 down → 主键 up → 修饰键逆序释放</b>；
 * 把这段<b>纯逻辑</b>抽成"事件序列"，才能在没有真实桌面的情况下断言调用顺序
 * （单测注入假键盘即可）。
 *
 * @param name       键名（小写；修饰键或主键的原始名）
 * @param virtualKey Win32 虚拟键码
 * @param down       true = 按下；false = 抬起
 */
public record ComputerUseKeyEvent(String name, int virtualKey, boolean down) {

    /**
     * 构造一个按下事件。
     *
     * @param name       键名
     * @param virtualKey 虚拟键码
     * @return 按下事件
     */
    public static ComputerUseKeyEvent down(String name, int virtualKey) {
        return new ComputerUseKeyEvent(name, virtualKey, true);
    }

    /**
     * 构造一个抬起事件。
     *
     * @param name       键名
     * @param virtualKey 虚拟键码
     * @return 抬起事件
     */
    public static ComputerUseKeyEvent up(String name, int virtualKey) {
        return new ComputerUseKeyEvent(name, virtualKey, false);
    }

    /**
     * 按<b>具名键</b>构造事件（键名 → 虚拟键码查 {@link ComputerUseVirtualKeys#VK_MAP}）。
     *
     * @param name 键名（如 {@code "return"} / {@code "tab"} / {@code "escape"}）
     * @param down true = 按下；false = 抬起
     * @return 按键事件
     * @throws IllegalArgumentException 键名不在 {@link ComputerUseVirtualKeys#VK_MAP} 中
     */
    public static ComputerUseKeyEvent named(String name, boolean down) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Integer vk = ComputerUseVirtualKeys.VK_MAP.get(key);
        if (vk == null) {
            throw new IllegalArgumentException("未知键名: " + name);
        }
        return new ComputerUseKeyEvent(key, vk, down);
    }
}

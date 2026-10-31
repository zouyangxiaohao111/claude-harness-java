package com.nexusai.application.agent.computeruse;

/**
 * 一个可见窗口的标识信息 · 对齐 CCB 2.8.4
 * {@code src/utils/computerUse/win32/windowEnum.ts:5-9} 的 {@code WindowInfo}。
 *
 * <p><b>CCB 原形状（TS，逐字语义）</b>：
 * <pre>
 * export interface WindowInfo {
 *   hwnd: string
 *   pid: number
 *   title: string
 * }
 * </pre>
 *
 * <p><b>枚举口径（CCB {@code windowEnum.ts:34-49}）</b>：{@code EnumWindows} 遍历全部顶层窗口
 * ⇒ <b>只保留「可见」且「标题非空」的窗口</b>（{@code IsWindowVisible} + {@code GetWindowTextLength > 0}）。
 *
 * <p><b>⚠️ hwnd 的表示差异（如实标注）</b>：CCB 的 {@code hwnd} 是<b>十进制字符串</b>
 * （PowerShell {@code $hwnd.ToInt64()} 的结果），本仓用 {@code long} 承载
 * （JNA {@code HWND} 的指针数值）⇒ <b>数值相同、类型不同</b>。
 * 工具层（{@code bind_window}）仍按 CCB 的<b>字符串</b> schema 收发，
 * 转换在 {@code ComputerUseBindWindowTool} 内完成。
 *
 * @param hwnd 窗口句柄的数值（{@code HWND} 指针值）
 * @param pid  窗口所属进程 id
 * @param title 窗口标题（枚举口径下恒非空）
 */
public record ComputerUseWindowInfo(long hwnd, int pid, String title) {

    public ComputerUseWindowInfo {
        if (title == null) {
            title = "";
        }
    }

    /**
     * 窗口句柄的<b>十进制字符串</b>形态（CCB 的 {@code hwnd: string} 口径）。
     *
     * @return 十进制字符串
     */
    public String hwndText() {
        return Long.toString(hwnd);
    }
}

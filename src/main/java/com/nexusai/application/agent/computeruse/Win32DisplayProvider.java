package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.W32APIOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Win32 显示器枚举实现（JNA 直调 user32）· computer-use Phase 2a 的 {@code switch_display} 用。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li>CCB-Windows 走 PowerShell {@code System.Windows.Forms.Screen::AllScreens}
 *       （{@code src/utils/computerUse/platforms/win32.ts:452-479}）—— 每次调用都要起一个
 *       PowerShell 进程；</li>
 *   <li>本仓改用 JNA 直调 {@code EnumDisplayMonitors} + {@code GetMonitorInfo}
 *       ⇒ <b>同款语义、零子进程</b>（⛔ 不引入新依赖：JNA + jna-platform 已是既有依赖）。</li>
 * </ul>
 *
 * <h2>显示器名 = {@code "display N"}（与 CCB-Windows 一致）</h2>
 * <p>CCB-Windows 的 {@code listAll} <b>不产出 label</b>
 * ⇒ CCB 的 {@code uniqueDisplayLabels} 回落到 {@code `display ${d.displayId}`}
 * ⇒ 本仓同样产出 {@code "display N"}（{@link ComputerUseDisplay#label()}）。
 */
public final class Win32DisplayProvider implements ComputerUseDisplayProvider {

    private static final Logger log = LoggerFactory.getLogger(Win32DisplayProvider.class);

    /** {@code MONITORINFOF_PRIMARY} —— 主显示器标志。 */
    private static final int MONITORINFOF_PRIMARY = 1;

    /** user32 的显示器枚举 API。 */
    private interface DisplayUser32 extends Library {
        DisplayUser32 INSTANCE = Native.load("user32", DisplayUser32.class, W32APIOptions.UNICODE_OPTIONS);

        int EnumDisplayMonitors(WinDef.HDC hdc, WinDef.RECT lprcClip,
                                WinUser.MONITORENUMPROC lpfnEnum, Pointer dwData);

        boolean GetMonitorInfo(WinUser.HMONITOR hMonitor, WinUser.MONITORINFO lpmi);
    }

    /** {@code EnumDisplayMonitors} 的回调（把每块屏收集进列表）。 */
    private static final class MonitorCollector implements WinUser.MONITORENUMPROC {
        private final List<ComputerUseDisplay> displays = new ArrayList<>();

        @Override
        public int apply(WinUser.HMONITOR hmonitor, WinDef.HDC hdc, WinDef.RECT rect, WinDef.LPARAM data) {
            WinUser.MONITORINFO info = new WinUser.MONITORINFO();
            info.cbSize = info.size();
            if (DisplayUser32.INSTANCE.GetMonitorInfo(hmonitor, info)) {
                WinDef.RECT monitor = info.rcMonitor;
                int width = monitor.right - monitor.left;
                int height = monitor.bottom - monitor.top;
                if (width > 0 && height > 0) {
                    displays.add(new ComputerUseDisplay(
                            displays.size(),
                            "display " + displays.size(),
                            width,
                            height,
                            monitor.left,
                            monitor.top,
                            (info.dwFlags & MONITORINFOF_PRIMARY) != 0,
                            ""));
                }
            }
            // 返回 TRUE = 继续枚举
            return 1;
        }
    }

    private final DisplayUser32 user32 = DisplayUser32.INSTANCE;

    @Override
    public List<ComputerUseDisplay> listDisplays() {
        MonitorCollector collector = new MonitorCollector();
        try {
            user32.EnumDisplayMonitors(null, null, collector, null);
        } catch (Throwable t) {
            // 枚举失败 ⇒ 返回空列表（调用方据此报 "Failed to enumerate displays."）
            log.warn("[computer-use] 显示器枚举失败: {}", t.getMessage());
            return List.of();
        }
        List<ComputerUseDisplay> displays = List.copyOf(collector.displays);
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 显示器枚举结果: {}", displays);
        }
        return displays;
    }
}

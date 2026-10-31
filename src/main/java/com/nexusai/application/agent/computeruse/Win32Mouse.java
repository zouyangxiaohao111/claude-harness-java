package com.nexusai.application.agent.computeruse;

import com.sun.jna.Memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 鼠标实现（JNA 直调 user32）· computer-use Phase 0。
 *
 * <p><b>CCB 对照</b>：{@code packages/@ant/computer-use-input/src/backends/win32.ts}
 * <ul>
 *   <li>移动：{@code :170-174} {@code SetCursorPos(round(x), round(y))}</li>
 *   <li>点击：{@code :202-210} {@code SendInput} × 2（左键 down / left键 up，各一次 1 元素的调用）</li>
 *   <li>P/Invoke 声明：{@code :46-88}（{@code SetCursorPos} / {@code SendInput} / {@code MOUSEINPUT} / {@code INPUT}）</li>
 * </ul>
 * 本实现把 PowerShell 内联 C# 的 P/Invoke 换成 JNA 直调，<b>API 与标志位完全一致</b>。
 *
 * <p><b>settle 等待</b>：CCB 的 50ms {@code MOVE_SETTLE_MS}（{@code src/utils/computerUse/executor.ts:168-177}）
 * 只出现在 macOS 路径；Windows 路径是「{@code SetCursorPos} 与 {@code SendInput} 在同一条脚本里连续执行」。
 * 本实现在 Windows 路径上<b>显式补上 settle 等待</b>（移动后等 50ms 再点），
 * 与任务口径「点击前要有移动后 settle 等待」一致，理由是 hover 态/命中测试需要时间稳定
 * （CCB {@code executor.ts:160-167} 注释描述的正是「瞬移后系统往返尚未完成就读光标/派发点击」的抖动）。
 */
public final class Win32Mouse implements ComputerUseMouse {

    private static final Logger log = LoggerFactory.getLogger(Win32Mouse.class);

    /** CCB {@code src/utils/computerUse/executor.ts:168} {@code MOVE_SETTLE_MS = 50}。 */
    public static final long MOVE_SETTLE_MS = 50L;

    private final Win32Native.User32 user32 = Win32Native.User32.INSTANCE;

    public Win32Mouse() {
        Win32Native.assertStructLayout();
    }

    @Override
    public void moveAndSettle(int x, int y) throws Exception {
        if (!user32.SetCursorPos(x, y)) {
            throw new IllegalStateException("[computer-use] SetCursorPos 失败: (" + x + "," + y + ")");
        }
        // settle：等「移动 → 系统 → 应用」的往返完成（CCB MOVE_SETTLE_MS 语义）
        Thread.sleep(MOVE_SETTLE_MS);
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 光标已移动到 ({},{}) 并等待 {}ms settle", x, y, MOVE_SETTLE_MS);
        }
    }

    @Override
    public void leftClick(int x, int y) throws Exception {
        // CCB win32 click 语义：定位后立即 down/up（此处定位已在 moveAndSettle 完成，重复定位幂等）
        if (!user32.SetCursorPos(x, y)) {
            throw new IllegalStateException("[computer-use] SetCursorPos 失败: (" + x + "," + y + ")");
        }
        sendMouseEvent(Win32Native.MOUSEEVENTF_LEFTDOWN);
        sendMouseEvent(Win32Native.MOUSEEVENTF_LEFTUP);
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 左键点击完成: ({},{})", x, y);
        }
    }

    /** 派发一个鼠标事件（{@code SendInput} 单元素调用，与 CCB 的两次调用同款）。 */
    private void sendMouseEvent(int dwFlags) {
        Memory mem = Win32Native.buildMouseInput(dwFlags);
        int sent = user32.SendInput(1, mem, (int) mem.size());
        if (sent != 1) {
            throw new IllegalStateException(String.format(
                    "[computer-use] SendInput 未成功: sent=%d（期望 1）dwFlags=0x%X", sent, dwFlags));
        }
    }
}

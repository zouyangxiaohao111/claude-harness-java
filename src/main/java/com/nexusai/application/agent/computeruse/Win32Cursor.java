package com.nexusai.application.agent.computeruse;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.WinDef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 光标实现（JNA 直调 user32）· 鼠标缓动路径（{@code mouseAnimation}）的底座。
 *
 * <p>API 与 CCB 一致（{@code packages/@ant/computer-use-input/src/backends/win32.ts}）：
 * <ul>
 *   <li>移动：{@code :170-174} {@code SetCursorPos(round(x), round(y))}；</li>
 *   <li>读位置：{@code :196-199} {@code GetCursorPos}；</li>
 *   <li>按键：{@code :202-210} {@code SendInput}（左键 down / up 各一次单元素调用）。</li>
 * </ul>
 */
public final class Win32Cursor implements ComputerUseCursor {

    private static final Logger log = LoggerFactory.getLogger(Win32Cursor.class);

    private final Win32Native.User32 user32 = Win32Native.User32.INSTANCE;

    public Win32Cursor() {
        Win32Native.assertStructLayout();
    }

    @Override
    public void moveTo(int x, int y) throws Exception {
        if (!user32.SetCursorPos(x, y)) {
            throw new IllegalStateException("[computer-use] SetCursorPos 失败: (" + x + "," + y + ")");
        }
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 光标瞬移到 ({},{})", x, y);
        }
    }

    @Override
    public int[] location() throws Exception {
        WinDef.POINT point = new WinDef.POINT();
        if (!user32.GetCursorPos(point)) {
            throw new IllegalStateException("[computer-use] GetCursorPos 失败");
        }
        return new int[]{point.x, point.y};
    }

    @Override
    public void leftButtonDown() throws Exception {
        sendMouseEvent(Win32Native.MOUSEEVENTF_LEFTDOWN);
    }

    @Override
    public void leftButtonUp() throws Exception {
        sendMouseEvent(Win32Native.MOUSEEVENTF_LEFTUP);
    }

    /** 派发一个鼠标事件（{@code SendInput} 单元素调用，与 CCB 的两次调用同款）。 */
    private void sendMouseEvent(int dwFlags) throws Exception {
        Memory mem = Win32Native.buildMouseInput(dwFlags);
        int sent = user32.SendInput(1, mem, (int) mem.size());
        if (sent != 1) {
            throw new IllegalStateException(String.format(
                    "[computer-use] SendInput 未成功: sent=%d（期望 1）dwFlags=0x%X", sent, dwFlags));
        }
    }
}

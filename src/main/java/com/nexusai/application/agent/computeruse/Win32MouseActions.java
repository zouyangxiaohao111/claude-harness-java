package com.nexusai.application.agent.computeruse;

import com.sun.jna.Memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 鼠标动作实现（JNA 直调 user32）· computer-use Phase 2a 的点击变体 / 滚动共用。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li><b>点击</b>：CCB {@code src/utils/computerUse/platforms/win32.ts:259-296}
 *       —— {@code SetCursorPos} → {@code SendInput}（down + up）。
 *       <br>CCB 的<b>次数</b>语义在跨平台执行器
 *       （{@code src/utils/computerUse/executorCrossPlatform.ts:384-386}）：
 *       {@code for (let i = 0; i < count; i++) await platform.input.click(...)}
 *       ⇒ <b>count 组完整的 down+up</b>（双击 = 两组按下/抬起，不是系统级双击事件）。</li>
 *   <li><b>按键 flag</b>：CCB {@code platforms/win32.ts:281-292}：
 *       左键 {@code MOUSEEVENTF_LEFTDOWN/LEFTUP}、右键 {@code RIGHTDOWN/RIGHTUP}、
 *       中键 {@code MIDDLEDOWN/MIDDLEUP}。</li>
 *   <li><b>滚动</b>：CCB {@code platforms/win32.ts:367-374}：
 *       垂直 {@code MOUSEEVENTF_WHEEL}、水平 {@code MOUSEEVENTF_HWHEEL}，
 *       {@code mouseData = amount * 120}（{@code WHEEL_DELTA = 120}）。</li>
 * </ul>
 *
 * <p>⚠️ <b>Windows 的滚轮方向约定</b>：{@code MOUSEEVENTF_WHEEL} 的 {@code mouseData}
 * 为<b>正</b>表示「向上滚」（away from user）。而 CCB 的 {@code handleScroll}
 * （{@code toolCalls.ts:2647-2649}）把「上」映射成 {@code dy = -amount}
 * ⇒ CCB 传给执行器的是<b>负数</b>，执行器再 {@code mouseData = amount * 120}。
 * ⇒ 本仓保持<b>同款符号</b>（{@code dy<0} = 上），并在 Win32 层按 CCB 的
 * {@code mouseData = dy * 120} 投递 ⇒ 实际滚轮方向与 CCB-Windows 一致。
 */
public final class Win32MouseActions implements ComputerUseMouseActions {

    private static final Logger log = LoggerFactory.getLogger(Win32MouseActions.class);

    /** WHEEL_DELTA —— Windows 滚轮一格 = 120（{@code platforms/win32.ts:372} 的 {@code amount * 120}）。 */
    private static final int WHEEL_DELTA = 120;

    private final Win32Native.User32 user32 = Win32Native.User32.INSTANCE;

    public Win32MouseActions() {
        Win32Native.assertStructLayout();
    }

    @Override
    public void click(int x, int y, ComputerUseMouseButton button, int count) throws Exception {
        if (count < 1) {
            throw new IllegalArgumentException("点击次数必须 ≥ 1，实际=" + count);
        }
        // CCB platforms/win32.ts:281-283 —— 先定位
        if (!user32.SetCursorPos(x, y)) {
            throw new IllegalStateException("[computer-use] SetCursorPos 失败: (" + x + "," + y + ")");
        }
        // CCB executorCrossPlatform.ts:384-386 —— count 组完整的 down+up
        for (int i = 0; i < count; i++) {
            sendMouseEvent(downFlag(button));
            sendMouseEvent(upFlag(button));
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 点击完成: ({},{}) · 按键={} · 次数={}", x, y, button.wire(), count);
        }
    }

    @Override
    public void moveTo(int x, int y) throws Exception {
        if (!user32.SetCursorPos(x, y)) {
            throw new IllegalStateException("[computer-use] SetCursorPos 失败: (" + x + "," + y + ")");
        }
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 光标移动到 ({},{})", x, y);
        }
    }

    @Override
    public void scroll(int dx, int dy) throws Exception {
        // CCB platforms/win32.ts:369-373 —— 垂直先、水平后（executorCrossPlatform.ts:507-508 同款顺序）
        if (dy != 0) {
            sendWheelEvent(Win32Native.MOUSEEVENTF_WHEEL, dy * WHEEL_DELTA);
        }
        if (dx != 0) {
            sendWheelEvent(Win32Native.MOUSEEVENTF_HWHEEL, dx * WHEEL_DELTA);
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 滚动完成: dx={} dy={}", dx, dy);
        }
    }

    /** 按键对应的 down flag · CCB {@code platforms/win32.ts:281-292}。 */
    private static int downFlag(ComputerUseMouseButton button) {
        return switch (button) {
            case LEFT -> Win32Native.MOUSEEVENTF_LEFTDOWN;
            case RIGHT -> Win32Native.MOUSEEVENTF_RIGHTDOWN;
            case MIDDLE -> Win32Native.MOUSEEVENTF_MIDDLEDOWN;
        };
    }

    /** 按键对应的 up flag · CCB {@code platforms/win32.ts:281-292}。 */
    private static int upFlag(ComputerUseMouseButton button) {
        return switch (button) {
            case LEFT -> Win32Native.MOUSEEVENTF_LEFTUP;
            case RIGHT -> Win32Native.MOUSEEVENTF_RIGHTUP;
            case MIDDLE -> Win32Native.MOUSEEVENTF_MIDDLEUP;
        };
    }

    /** 派发一个鼠标事件（{@code SendInput} 单元素调用）。 */
    private void sendMouseEvent(int dwFlags) {
        Memory mem = Win32Native.buildMouseInput(dwFlags);
        int sent = user32.SendInput(1, mem, (int) mem.size());
        if (sent != 1) {
            throw new IllegalStateException(String.format(
                    "[computer-use] SendInput 未成功: sent=%d（期望 1）dwFlags=0x%X", sent, dwFlags));
        }
    }

    /** 派发一个滚轮事件（{@code SendInput} + {@code mouseData} 携带格数）。 */
    private void sendWheelEvent(int dwFlags, int mouseData) {
        Memory mem = Win32Native.buildWheelInput(dwFlags, mouseData);
        int sent = user32.SendInput(1, mem, (int) mem.size());
        if (sent != 1) {
            throw new IllegalStateException(String.format(
                    "[computer-use] SendInput 未成功（滚轮）: sent=%d（期望 1）dwFlags=0x%X mouseData=%d",
                    sent, dwFlags, mouseData));
        }
    }
}

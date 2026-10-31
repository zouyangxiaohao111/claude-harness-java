package com.nexusai.application.agent.computeruse;

import com.sun.jna.Memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 键盘实现（JNA 直调 user32 的 {@code SendInput}）· computer-use 的键盘注入底座。
 *
 * <h2>文本注入：{@code KEYEVENTF_UNICODE}（<b>修正 CCB 缺陷</b>）</h2>
 * <p>CCB 的 Windows 文本注入走
 * {@code [System.Windows.Forms.SendKeys]::SendWait('...')}
 * （{@code packages/@ant/computer-use-input/src/backends/win32.ts:284-289}），
 * 而 {@code SendKeys} 里 {@code + ^ % ~ ( ) { } [ ]} 都是<b>特殊键标记</b>
 * ⇒ 文本含 {@code +} 会被当成 Shift、{@code %} 会被当成 Alt。
 *
 * <p>本实现改用 {@code KEYEVENTF_UNICODE}：把字符的 UTF-16 码元塞进 {@code wScan}，
 * 由系统当作"这个字符"直接投递 ⇒ <b>任何字符都只是字符</b>，无特殊语义。
 *
 * <p>⚠️ 该缺陷修正带来的<b>已知差异</b>：{@code SendKeys} 走的是"键盘布局 + 修饰键"路径，
 * 而 {@code KEYEVENTF_UNICODE} 走的是"直接投递字符"路径 ⇒
 * <b>死键组合</b>（如某些布局上 {@code "e" + 组合字符}）在两条路径下的合成结果可能不同。
 * 本仓选择 Unicode 直投（字符语义确定）而非 SendKeys（布局语义确定）。
 *
 * <h2>按键注入：虚拟键码（{@code wVk}）</h2>
 * <p>组合键（{@code ctrl+shift+a}）的派发顺序逐字对齐 CCB
 * {@code src/utils/computerUse/win32/windowMessage.ts:341-366}：
 * <b>修饰键按下（按输入顺序）→ 主键 down → 主键 up → 修饰键逆序释放</b>。
 *
 * <p>CCB 的 Windows 组合键走 {@code keybd_event}（全局注入）
 * （{@code packages/@ant/computer-use-input/src/backends/win32.ts:253-282}），
 * 与本实现的 {@code SendInput} 同属全局注入 ⇒ 语义等价。
 *
 * <p><b>与 CCB 的路线差异（已在 {@link ComputerUseLeftClickTool} 类注释中记录）</b>：
 * CCB 的 CLI 路径走<b>窗口定向</b>（{@code PostMessage} 到已绑定 HWND），
 * 本仓走<b>全局注入</b>（与既有鼠标实现同款路线）。
 */
public final class Win32Keyboard implements ComputerUseKeyboard {

    private static final Logger log = LoggerFactory.getLogger(Win32Keyboard.class);

    private final Win32Native.User32 user32 = Win32Native.User32.INSTANCE;

    public Win32Keyboard() {
        Win32Native.assertStructLayout();
    }

    @Override
    public void typeChar(int codePoint) throws Exception {
        if (Character.isBmpCodePoint(codePoint)) {
            sendUnicode((char) codePoint);
            sendUnicode((char) codePoint, Win32Native.KEYEVENTF_KEYUP);
            return;
        }
        // 非 BMP（代理对）⇒ 拆成两个 UTF-16 码元分别投递（Windows 的标准做法）
        char[] surrogatePair = Character.toChars(codePoint);
        for (char unit : surrogatePair) {
            sendUnicode(unit);
            sendUnicode(unit, Win32Native.KEYEVENTF_KEYUP);
        }
    }

    @Override
    public void sendKeyEvent(ComputerUseKeyEvent event) throws Exception {
        int flags = event.down() ? 0 : Win32Native.KEYEVENTF_KEYUP;
        Memory mem = Win32Native.buildKeyboardInput(event.virtualKey(), 0, flags);
        int sent = user32.SendInput(1, mem, (int) mem.size());
        if (sent != 1) {
            throw new IllegalStateException(String.format(
                    "[computer-use] SendInput 未成功: sent=%d（期望 1）vk=0x%X", sent, event.virtualKey()));
        }
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 按键事件已注入: {} vk=0x{}", event.name(), event.virtualKey());
        }
    }

    /** Unicode 字符投递（down）。 */
    private void sendUnicode(char unit) {
        sendUnicode(unit, 0);
    }

    /** Unicode 字符投递（带标志位）。 */
    private void sendUnicode(char unit, int extraFlags) {
        Memory mem = Win32Native.buildKeyboardInput(0, unit, Win32Native.KEYEVENTF_UNICODE | extraFlags);
        int sent = user32.SendInput(1, mem, (int) mem.size());
        if (sent != 1) {
            throw new IllegalStateException(String.format(
                    "[computer-use] SendInput 未成功（Unicode）: sent=%d（期望 1）unit=0x%X", sent, (int) unit));
        }
    }
}

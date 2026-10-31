package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.W32APIOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Win32 窗口操作实现（JNA 直调 user32）· computer-use Phase 2b 的<b>窗口定向输入</b>底座。
 *
 * <p><b>WHY 用 JNA 而不是 CCB 的 PowerShell</b>：CCB 的 Windows 侧几乎全部走
 * PowerShell 子进程（{@code ps(script)}），本仓改用 <b>JNA 直调</b> ——
 * <b>调用的 Win32 API、参数、消息号逐字相同</b>，只是不起子进程
 * （无 PowerShell 转义陷阱、无 200–500ms 启动开销）。
 * 本仓既有 {@link Win32Clipboard} / {@link Win32ForegroundAppResolver} 已是同一处置方式。
 *
 * <h2>关键常量（与 CCB 逐字一致）</h2>
 * <pre>
 * WM_CLOSE        = 0x0010   windowMessage.ts:79
 * WM_KEYDOWN      = 0x0100   windowMessage.ts:79
 * WM_KEYUP        = 0x0101   windowMessage.ts:79
 * WM_CHAR         = 0x0102   windowMessage.ts:79
 * WM_MOUSEMOVE    = 0x0200   windowMessage.ts:595
 * WM_LBUTTONDOWN  = 0x0201   windowMessage.ts:80
 * WM_LBUTTONUP    = 0x0202   windowMessage.ts:81
 * WM_RBUTTONDOWN  = 0x0204   windowMessage.ts:82
 * WM_RBUTTONUP    = 0x0205   windowMessage.ts:83
 * WM_MOUSEWHEEL   = 0x020A   windowMessage.ts:621
 * WM_MOUSEHWHEEL  = 0x020E   windowMessage.ts:621
 * </pre>
 */
public final class Win32WindowOps implements ComputerUseWindowOps {

    private static final Logger log = LoggerFactory.getLogger(Win32WindowOps.class);

    /** {@code WM_CLOSE}。 */
    private static final int WM_CLOSE = 0x0010;
    /** {@code WM_KEYDOWN}。 */
    private static final int WM_KEYDOWN = 0x0100;
    /** {@code WM_KEYUP}。 */
    private static final int WM_KEYUP = 0x0101;
    /** {@code WM_CHAR}。 */
    private static final int WM_CHAR = 0x0102;
    /** {@code WM_MOUSEMOVE}。 */
    private static final int WM_MOUSEMOVE = 0x0200;
    /** {@code WM_LBUTTONDOWN}。 */
    private static final int WM_LBUTTONDOWN = 0x0201;
    /** {@code WM_LBUTTONUP}。 */
    private static final int WM_LBUTTONUP = 0x0202;
    /** {@code WM_MBUTTONDOWN}。 */
    private static final int WM_MBUTTONDOWN = 0x0203;
    /** {@code WM_MBUTTONUP}。 */
    private static final int WM_MBUTTONUP = 0x0206;
    /** {@code WM_RBUTTONDOWN}。 */
    private static final int WM_RBUTTONDOWN = 0x0204;
    /** {@code WM_RBUTTONUP}。 */
    private static final int WM_RBUTTONUP = 0x0205;
    /** {@code WM_MOUSEWHEEL}。 */
    private static final int WM_MOUSEWHEEL = 0x020A;
    /** {@code WM_MOUSEHWHEEL}。 */
    private static final int WM_MOUSEHWHEEL = 0x020E;

    /** {@code SetWindowPos} 的 {@code SWP_NOSIZE}。 */
    private static final int SWP_NOSIZE = 0x0001;
    /** {@code SetWindowPos} 的 {@code SWP_NOZORDER}。 */
    private static final int SWP_NOZORDER = 0x0004;
    /** {@code SetWindowPos} 的 {@code SWP_NOACTIVATE}。 */
    private static final int SWP_NOACTIVATE = 0x0010;

    /** {@code WHEEL_DELTA} —— 滚轮一格 = 120（{@code windowMessage.ts:625} 的 {@code delta * 120}）。 */
    private static final int WHEEL_DELTA = 120;

    /** {@code MapVirtualKey} 的 {@code MAPVK_VK_TO_VSC}（{@code windowMessage.ts:71}）。 */
    private static final int MAPVK_VK_TO_VSC = 0;

    /**
     * 可接收文本的子控件类名优先级 · 逐字对齐 CCB
     * {@code src/utils/computerUse/win32/windowMessage.ts:103-113} 的 {@code EDIT_CLASSES}。
     */
    private static final List<String> EDIT_CLASSES = List.of(
            "Windows.UI.Input.InputSite.WindowClass",
            "RichEditD2DPT",
            "RichEdit20W",
            "Edit",
            "Scintilla",
            "Chrome_RenderWidgetHostHWND",
            "TextBox",
            "RichTextBox",
            "Windows.UI.Core.CoreWindow");

    /** user32 子集（本仓自声明 ⇒ 不依赖 jna-platform 的 User32 覆盖面）。 */
    private interface User32Ext extends Library {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.UNICODE_OPTIONS);

        boolean EnumWindows(WndEnumProc lpEnumFunc, Pointer arg);

        boolean EnumChildWindows(WinDef.HWND hwndParent, WndEnumProc lpEnumFunc, Pointer arg);

        boolean IsWindowVisible(WinDef.HWND hwnd);

        boolean IsWindow(WinDef.HWND hwnd);

        boolean IsIconic(WinDef.HWND hwnd);

        int GetWindowTextLengthW(WinDef.HWND hwnd);

        int GetWindowTextW(WinDef.HWND hwnd, char[] lpString, int nMaxCount);

        int GetClassNameW(WinDef.HWND hwnd, char[] lpClassName, int nMaxCount);

        int GetWindowThreadProcessId(WinDef.HWND hwnd, com.sun.jna.ptr.IntByReference lpdwProcessId);

        boolean GetWindowRect(WinDef.HWND hwnd, WinDef.RECT lpRect);

        boolean GetClientRect(WinDef.HWND hwnd, WinDef.RECT lpRect);

        boolean ClientToScreen(WinDef.HWND hwnd, WinDef.POINT lpPoint);

        boolean ScreenToClient(WinDef.HWND hwnd, WinDef.POINT lpPoint);

        boolean ShowWindow(WinDef.HWND hwnd, int nCmdShow);

        boolean SetWindowPos(WinDef.HWND hwnd, WinDef.HWND hWndInsertAfter,
                            int x, int y, int cx, int cy, int uFlags);

        boolean SetForegroundWindow(WinDef.HWND hwnd);

        boolean BringWindowToTop(WinDef.HWND hwnd);

        WinDef.LRESULT SendMessageW(WinDef.HWND hwnd, int msg,
                                     WinDef.WPARAM wParam, WinDef.LPARAM lParam);

        boolean PostMessageW(WinDef.HWND hwnd, int msg,
                             WinDef.WPARAM wParam, WinDef.LPARAM lParam);

        int MapVirtualKeyW(int uCode, int uMapType);
    }

    /** {@code EnumWindows} / {@code EnumChildWindows} 的回调。 */
    public interface WndEnumProc extends com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        /**
         * @param hwnd 当前窗口
         * @param lParam 回调参数
         * @return true = 继续枚举
         */
        boolean callback(WinDef.HWND hwnd, Pointer lParam);
    }

    private final User32Ext user32 = User32Ext.INSTANCE;

    @Override
    public List<ComputerUseWindowInfo> listVisibleWindows() {
        List<ComputerUseWindowInfo> out = new ArrayList<>();
        // 回调对象必须在本方法返回前保持强引用（JNA 要求回调存活期间不被 GC）
        WndEnumProc proc = (hwnd, lParam) -> {
            try {
                if (!user32.IsWindowVisible(hwnd)) {
                    return true;
                }
                int len = user32.GetWindowTextLengthW(hwnd);
                if (len <= 0) {
                    return true;
                }
                char[] buf = new char[len + 1];
                int n = user32.GetWindowTextW(hwnd, buf, buf.length);
                if (n <= 0) {
                    return true;
                }
                String title = new String(buf, 0, n);
                if (title.isBlank()) {
                    return true;
                }
                com.sun.jna.ptr.IntByReference pidRef = new com.sun.jna.ptr.IntByReference();
                user32.GetWindowThreadProcessId(hwnd, pidRef);
                out.add(new ComputerUseWindowInfo(ptrValue(hwnd), pidRef.getValue(), title));
            } catch (Throwable t) {
                log.warn("[computer-use] 枚举窗口时跳过一个窗口: {}", t.toString());
            }
            return true;
        };
        user32.EnumWindows(proc, null);
        return out;
    }

    @Override
    public int[] getWindowRect(long hwnd) {
        WinDef.RECT rect = new WinDef.RECT();
        if (!user32.GetWindowRect(hwndHandle(hwnd), rect)) {
            return null;
        }
        return new int[]{rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top};
    }

    @Override
    public int[] getClientOffset(long hwnd) {
        int[] rect = getWindowRect(hwnd);
        if (rect == null) {
            return null;
        }
        WinDef.POINT origin = new WinDef.POINT();
        origin.x = 0;
        origin.y = 0;
        if (!user32.ClientToScreen(hwndHandle(hwnd), origin)) {
            return null;
        }
        // CCB executorCrossPlatform.ts:105 —— dx = ClientToScreen(0,0).x - GetWindowRect().left
        return new int[]{origin.x - rect[0], origin.y - rect[1]};
    }

    @Override
    public int[] clientToScreen(long hwnd, int x, int y) {
        WinDef.POINT p = new WinDef.POINT();
        p.x = x;
        p.y = y;
        if (!user32.ClientToScreen(hwndHandle(hwnd), p)) {
            return new int[]{x, y};
        }
        return new int[]{p.x, p.y};
    }

    @Override
    public int[] screenToClient(long hwnd, int x, int y) {
        WinDef.POINT p = new WinDef.POINT();
        p.x = x;
        p.y = y;
        if (!user32.ScreenToClient(hwndHandle(hwnd), p)) {
            return new int[]{x, y};
        }
        return new int[]{p.x, p.y};
    }

    @Override
    public void showWindow(long hwnd, int cmd) {
        user32.ShowWindow(hwndHandle(hwnd), cmd);
    }

    @Override
    public void moveResize(long hwnd, int x, int y, Integer width, Integer height) {
        boolean hasSize = width != null && width > 0 && height != null && height > 0;
        user32.SetWindowPos(hwndHandle(hwnd), null, x, y,
                hasSize ? width : 0, hasSize ? height : 0,
                SWP_NOZORDER | SWP_NOACTIVATE | (hasSize ? 0 : SWP_NOSIZE));
    }

    @Override
    public void moveOffscreen(long hwnd) {
        // CCB platforms/win32.ts:900 —— SetWindowPos(-32000,-32000, SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE)
        user32.SetWindowPos(hwndHandle(hwnd), null, -32000, -32000, 0, 0,
                SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    }

    @Override
    public void closeWindow(long hwnd) {
        user32.SendMessageW(hwndHandle(hwnd), WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
    }

    @Override
    public void focusWindow(long hwnd) {
        WinDef.HWND h = hwndHandle(hwnd);
        // CCB platforms/win32.ts:888-891 —— 最小化时先 SW_RESTORE
        if (user32.IsIconic(h)) {
            user32.ShowWindow(h, SW_RESTORE);
        }
        user32.SetForegroundWindow(h);
        user32.BringWindowToTop(h);
    }

    @Override
    public void sendClick(long hwnd, int x, int y, boolean right) {
        WinDef.HWND h = hwndHandle(hwnd);
        WinDef.LPARAM lp = new WinDef.LPARAM(makeLParam(x, y));
        int down = right ? WM_RBUTTONDOWN : WM_LBUTTONDOWN;
        int up = right ? WM_RBUTTONUP : WM_LBUTTONUP;
        user32.SendMessageW(h, down, new WinDef.WPARAM(0), lp);
        user32.SendMessageW(h, up, new WinDef.WPARAM(0), lp);
    }

    @Override
    public void sendMouseDown(long hwnd, int x, int y) {
        // CCB windowMessage.ts:565-573 —— SendMessage(WM_LBUTTONDOWN)，wParam=1（左键按下）
        user32.SendMessageW(hwndHandle(hwnd), WM_LBUTTONDOWN,
                new WinDef.WPARAM(1), new WinDef.LPARAM(makeLParam(x, y)));
    }

    @Override
    public void sendMouseUp(long hwnd, int x, int y) {
        // CCB windowMessage.ts:578-586 —— SendMessage(WM_LBUTTONUP)，wParam=0
        user32.SendMessageW(hwndHandle(hwnd), WM_LBUTTONUP,
                new WinDef.WPARAM(0), new WinDef.LPARAM(makeLParam(x, y)));
    }

    @Override
    public void sendMouseMove(long hwnd, int x, int y) {
        // CCB windowMessage.ts:591-598 —— SendMessage(WM_MOUSEMOVE)，wParam=1（左键按住状态）
        user32.SendMessageW(hwndHandle(hwnd), WM_MOUSEMOVE,
                new WinDef.WPARAM(1), new WinDef.LPARAM(makeLParam(x, y)));
    }

    @Override
    public void sendChar(long hwnd, int codePoint) {
        // CCB windowMessage.ts:252-288 —— 逐码点 SendMessageW(WM_CHAR)
        user32.SendMessageW(hwndHandle(hwnd), WM_CHAR,
                new WinDef.WPARAM(codePoint & 0xFFFFFFFFL), new WinDef.LPARAM(0));
    }

    @Override
    public void sendText(long hwnd, String text) {
        // CCB windowMessage.ts:275-288 —— 非 BMP 码点拆成 UTF-16 代理对逐个投递
        for (int i = 0; i < text.length(); i++) {
            sendChar(hwnd, text.charAt(i));
        }
    }

    @Override
    public void sendKeyDown(long hwnd, int vk) {
        user32.PostMessageW(hwndHandle(hwnd), WM_KEYDOWN,
                new WinDef.WPARAM(vk & 0xFFFFFFFFL), new WinDef.LPARAM(keyDownLParam(vk)));
    }

    @Override
    public void sendKeyUp(long hwnd, int vk) {
        user32.PostMessageW(hwndHandle(hwnd), WM_KEYUP,
                new WinDef.WPARAM(vk & 0xFFFFFFFFL), new WinDef.LPARAM(keyUpLParam(vk)));
    }

    @Override
    public void sendMouseWheel(long hwnd, int x, int y, int delta, boolean horizontal) {
        // CCB windowMessage.ts:626-646 —— WM_MOUSEWHEEL 的 lParam 必须是屏幕坐标
        int[] screen = clientToScreen(hwnd, x, y);
        int msg = horizontal ? WM_MOUSEHWHEEL : WM_MOUSEWHEEL;
        long wheelDelta = (long) delta * WHEEL_DELTA;
        long wParam = wheelDelta << 16;
        user32.SendMessageW(hwndHandle(hwnd), msg,
                new WinDef.WPARAM(wParam), new WinDef.LPARAM(makeLParam(screen[0], screen[1])));
    }

    @Override
    public long findEditChild(long hwnd) {
        Map<String, String> children = new LinkedHashMap<>();
        WndEnumProc proc = (child, lParam) -> {
            try {
                char[] buf = new char[256];
                int n = user32.GetClassNameW(child, buf, buf.length);
                if (n > 0) {
                    children.put(Long.toString(ptrValue(child)), new String(buf, 0, n));
                }
            } catch (Throwable t) {
                log.warn("[computer-use] 枚举子窗口时跳过一个: {}", t.toString());
            }
            return true;
        };
        user32.EnumChildWindows(hwndHandle(hwnd), proc, null);
        // CCB windowMessage.ts:180-190 —— 按 EDIT_CLASSES 优先级取第一个命中
        for (String editClass : EDIT_CLASSES) {
            for (Map.Entry<String, String> entry : children.entrySet()) {
                if (entry.getValue().equals(editClass)) {
                    return Long.parseLong(entry.getKey());
                }
            }
        }
        return 0L;
    }

    /**
     * 客户区坐标 → {@code MAKELPARAM(x, y)}（低 16 位 = x，高 16 位 = y）。
     *
     * <p>逐字对齐 CCB {@code windowMessage.ts:64-66} 的 {@code MakeLParam}。
     *
     * @param x 客户区 X
     * @param y 客户区 Y
     * @return 打包后的 lParam
     */
    static long makeLParam(int x, int y) {
        return ((long) (y & 0xFFFF) << 16) | (x & 0xFFFFL);
    }

    /**
     * {@code WM_KEYDOWN} 的 lParam：repeat=1，扫描码在 bits 16-23。
     *
     * <p>逐字对齐 CCB {@code windowMessage.ts:70-73} 的 {@code KeyDownLParam}。
     *
     * @param vk 虚拟键码
     * @return lParam
     */
    long keyDownLParam(int vk) {
        long scan = user32.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC) & 0xFFL;
        return 1L | (scan << 16);
    }

    /**
     * {@code WM_KEYUP} 的 lParam：prevState=1、transition=1。
     *
     * <p>逐字对齐 CCB {@code windowMessage.ts:74-77} 的 {@code KeyUpLParam}。
     *
     * @param vk 虚拟键码
     * @return lParam
     */
    long keyUpLParam(int vk) {
        long scan = user32.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC) & 0xFFL;
        return 1L | (scan << 16) | (1L << 30) | (1L << 31);
    }

    /** 数值句柄 → JNA {@code HWND}。 */
    private static WinDef.HWND hwndHandle(long hwnd) {
        return new WinDef.HWND(Pointer.createConstant(hwnd));
    }

    /** JNA {@code HWND} → 数值句柄。 */
    private static long ptrValue(WinDef.HWND hwnd) {
        Pointer p = hwnd.getPointer();
        return p == null ? 0L : Pointer.nativeValue(p);
    }
}

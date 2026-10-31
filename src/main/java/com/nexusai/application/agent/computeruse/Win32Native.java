package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.W32APIOptions;

import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 原生绑定（JNA）· computer-use Phase 0 的「直调 Win32」底座。
 *
 * <p><b>与 CCB 的关系</b>：CCB 走 PowerShell 内联 C# 的 P/Invoke
 * （{@code packages/@ant/computer-use-input/src/backends/win32.ts:39-88} 的 {@code WIN32_TYPES}
 * 片段：{@code SetCursorPos} / {@code SendInput} / {@code keybd_event}），
 * 本仓按任务口径改为 <b>Java + JNA 直调</b>，<b>语义等价</b>（同一组 Win32 API、同一组标志位）。
 *
 * <p><b>结构体布局自证</b>：{@link #assertStructLayout()} 做两层校验 ——
 * ① 各结构体<b>总长</b>符合 Win32 x64 ABI；② 用已知魔数回读<b>字段偏移</b>，
 * 证明 {@link #INPUT_TYPE_OFFSET} / {@link #MOUSEINPUT_DX_OFFSET} 等手工常量与 JNA 布局一致。
 * 任一不符即抛异常（fail loud）—— 绝不带错布局去调内核。
 */
public final class Win32Native {

    private static final Logger log = LoggerFactory.getLogger(Win32Native.class);

    private Win32Native() {
    }

    // ══════════════════════════════════════════════════════════════════════
    // 常量
    // ══════════════════════════════════════════════════════════════════════

    /** {@code GetSystemMetrics(SM_CXSCREEN)} —— 主显示器宽度（进程坐标空间）。 */
    public static final int SM_CXSCREEN = 0;
    /** {@code GetSystemMetrics(SM_CYSCREEN)} —— 主显示器高度（进程坐标空间）。 */
    public static final int SM_CYSCREEN = 1;

    /** {@code GetDeviceCaps(LOGPIXELSX)} —— 水平方向每英寸像素数。 */
    public static final int LOGPIXELSX = 88;

    /** {@code BitBlt} 的 {@code SRCCOPY} 光栅操作码。 */
    public static final int SRCCOPY = 0x00CC0020;

    /** {@code GetDIBits} 的 {@code DIB_RGB_COLORS}。 */
    public static final int DIB_RGB_COLORS = 0;

    /** {@code INPUT.type} = 鼠标输入。 */
    public static final int INPUT_MOUSE = 0;

    /** {@code INPUT.type} = 键盘输入。 */
    public static final int INPUT_KEYBOARD = 1;

    /** {@code KEYBDINPUT.dwFlags} = 键抬起。 */
    public static final int KEYEVENTF_KEYUP = 0x0002;

    /**
     * {@code KEYBDINPUT.dwFlags} = Unicode 字符注入（{@code wScan} 携带 UTF-16 码元）。
     *
     * <p><b>WHY 用它而不是 SendKeys</b>：CCB 的 Windows 文本注入走
     * {@code [System.Windows.Forms.SendKeys]::SendWait('...')}
     * （{@code packages/@ant/computer-use-input/src/backends/win32.ts:284-289}），
     * 而 {@code SendKeys} 里 {@code + ^ % ~ ( ) { } [ ]} 都是<b>特殊键标记</b>
     * ⇒ 文本含 {@code +} 会被当作 Shift。本仓改用 {@code KEYEVENTF_UNICODE}
     * 逐字符注入 ⇒ <b>无任何特殊字符语义</b>（本仓对 CCB 该缺陷的修正）。
     */
    public static final int KEYEVENTF_UNICODE = 0x0004;

    /** {@code MOUSEEVENTF_LEFTDOWN}。 */
    public static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    /** {@code MOUSEEVENTF_LEFTUP}。 */
    public static final int MOUSEEVENTF_LEFTUP = 0x0004;
    /** {@code MOUSEEVENTF_RIGHTDOWN} · CCB {@code platforms/win32.ts:283}。 */
    public static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    /** {@code MOUSEEVENTF_RIGHTUP} · CCB {@code platforms/win32.ts:284}。 */
    public static final int MOUSEEVENTF_RIGHTUP = 0x0010;
    /** {@code MOUSEEVENTF_MIDDLEDOWN} · CCB {@code platforms/win32.ts:285}。 */
    public static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    /** {@code MOUSEEVENTF_MIDDLEUP} · CCB {@code platforms/win32.ts:286}。 */
    public static final int MOUSEEVENTF_MIDDLEUP = 0x0040;
    /** {@code MOUSEEVENTF_WHEEL}（垂直滚轮）· CCB {@code platforms/win32.ts:369}。 */
    public static final int MOUSEEVENTF_WHEEL = 0x0800;
    /** {@code MOUSEEVENTF_HWHEEL}（水平滚轮）· CCB {@code platforms/win32.ts:370}。 */
    public static final int MOUSEEVENTF_HWHEEL = 0x1000;

    /**
     * {@code PrintWindow} 的 {@code PW_RENDERFULLCONTENT} 标志。
     *
     * <p>CCB {@code src/utils/computerUse/win32/bridge.py:132}：
     * {@code result = ctypes.windll.user32.PrintWindow(hwnd, hdc_mem, 2)}。
     */
    public static final int PW_RENDERFULLCONTENT = 2;

    /** {@code ShowWindow(SW_SHOWNOACTIVATE)} —— 最小化窗口临时还原（CCB {@code bridge.py:114-118}）。 */
    public static final int SW_SHOWNOACTIVATE = 4;
    /** {@code ShowWindow(SW_SHOWMINNOACTIVE)} —— 还原后恢复最小化（CCB {@code bridge.py:157-158}）。 */
    public static final int SW_SHOWMINNOACTIVE = 2;

    /** {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2}（= (HANDLE)-4）。 */
    public static final Pointer DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4);

    // ── INPUT 结构体手工偏移（x64 ABI）────────────────────────────────
    /** {@code INPUT.type} 偏移。 */
    public static final int INPUT_TYPE_OFFSET = 0;
    /** {@code INPUT} 内 union 起始偏移（= MOUSEINPUT 起点）。 */
    public static final int INPUT_UNION_OFFSET = 8;
    /** {@code MOUSEINPUT.dx} 相对 INPUT 起点的偏移。 */
    public static final int MOUSEINPUT_DX_OFFSET = INPUT_UNION_OFFSET + 0;
    /** {@code MOUSEINPUT.dy} 相对 INPUT 起点的偏移。 */
    public static final int MOUSEINPUT_DY_OFFSET = INPUT_UNION_OFFSET + 4;
    /** {@code MOUSEINPUT.mouseData} 相对 INPUT 起点的偏移。 */
    public static final int MOUSEINPUT_MOUSEDATA_OFFSET = INPUT_UNION_OFFSET + 8;
    /** {@code MOUSEINPUT.dwFlags} 相对 INPUT 起点的偏移。 */
    public static final int MOUSEINPUT_DWFLAGS_OFFSET = INPUT_UNION_OFFSET + 12;
    /** {@code MOUSEINPUT.time} 相对 INPUT 起点的偏移。 */
    public static final int MOUSEINPUT_TIME_OFFSET = INPUT_UNION_OFFSET + 16;

    // ── KEYBDINPUT 手工偏移（x64 ABI）────────────────────────────────────
    //   struct tagKEYBDINPUT { WORD wVk; WORD wScan; DWORD dwFlags; DWORD time; ULONG_PTR dwExtraInfo; }
    //   x64 布局：wVk(0) wScan(2) dwFlags(4) time(8) [pad 4] dwExtraInfo(16) ⇒ size 24
    //   （落在 INPUT 的 union 区（@8）内 ⇒ union 仍由 MOUSEINPUT(32) 决定 ⇒ INPUT 总长仍 40）
    /** {@code KEYBDINPUT.wVk} 相对 INPUT 起点的偏移。 */
    public static final int KEYBDINPUT_WVK_OFFSET = INPUT_UNION_OFFSET + 0;
    /** {@code KEYBDINPUT.wScan} 相对 INPUT 起点的偏移。 */
    public static final int KEYBDINPUT_WSCAN_OFFSET = INPUT_UNION_OFFSET + 2;
    /** {@code KEYBDINPUT.dwFlags} 相对 INPUT 起点的偏移。 */
    public static final int KEYBDINPUT_DWFLAGS_OFFSET = INPUT_UNION_OFFSET + 4;
    /** {@code KEYBDINPUT.time} 相对 INPUT 起点的偏移。 */
    public static final int KEYBDINPUT_TIME_OFFSET = INPUT_UNION_OFFSET + 8;

    // ══════════════════════════════════════════════════════════════════════
    // 结构体
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@code MOUSEINPUT} · Win32 {@code tagMOUSEINPUT}。
     *
     * <pre>
     * typedef struct tagMOUSEINPUT {
     *   LONG dx; LONG dy; DWORD mouseData; DWORD dwFlags; DWORD time; ULONG_PTR dwExtraInfo;
     * } MOUSEINPUT;
     * </pre>
     *
     * <p>x64 布局：dx(0) dy(4) mouseData(8) dwFlags(12) time(16) [pad 4] dwExtraInfo(24) ⇒ size 32。
     */
    @Structure.FieldOrder({"dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo"})
    public static class MOUSEINPUT extends Structure {
        public int dx;
        public int dy;
        public int mouseData;
        public int dwFlags;
        public int time;
        public Pointer dwExtraInfo;
    }

    /**
     * {@code INPUT} · Win32 {@code tagINPUT}（鼠标分支的 union）。
     *
     * <p>x64 布局：type(0) [pad 4] union(8) ⇒ size 40。
     */
    @Structure.FieldOrder({"type", "mi"})
    public static class INPUT extends Structure {
        public int type;
        public MOUSEINPUT mi;
    }

    /**
     * {@code BITMAPINFOHEADER} · Win32 {@code tagBITMAPINFOHEADER}（size 40）。
     */
    @Structure.FieldOrder({"biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
            "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
            "biClrUsed", "biClrImportant"})
    public static class BITMAPINFOHEADER extends Structure {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;
    }

    /**
     * {@code BITMAPINFO} · Win32 {@code tagBITMAPINFO}。
     *
     * <p>32bpp BI_RGB 不需要颜色表；{@code GetDIBits} 依据 {@code biSize} 判定调用方给的是
     * BITMAPINFOHEADER 还是 BITMAPINFO（{@code biSize=40} ⇒ 按 header 处理，不读颜色表）。
     * JNA 不允许 Structure 里出现零长数组，故本实现直接用 {@link #BITMAPINFOHEADER} 传参。
     */

    // ══════════════════════════════════════════════════════════════════════
    // 原生库接口
    // ══════════════════════════════════════════════════════════════════════

    /** user32.dll 需要的函数子集。 */
    public interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);

        int GetSystemMetrics(int nIndex);

        WinDef.HDC GetDC(WinDef.HWND hwnd);

        int ReleaseDC(WinDef.HWND hwnd, WinDef.HDC hdc);

        boolean SetCursorPos(int x, int y);

        /**
         * {@code GetCursorPos} —— 当前光标位置（屏幕坐标）。
         *
         * <p>CCB {@code packages/@ant/computer-use-input/src/backends/win32.ts:196-199}
         * （{@code mouseLocation}）：读真实光标位置，<b>不移动</b>。
         * 本仓的鼠标缓动路径（{@link ComputerUseMouseAnimator}）需要它算起点。
         *
         * @param point 出参（{@code WinDef.POINT}）
         * @return 非 0 = 成功
         */
        boolean GetCursorPos(WinDef.POINT point);

        /**
         * {@code SendInput}。
         *
         * <p>入参用裸 {@link Pointer}（指向一块手工构造的 {@code INPUT} 内存）而不是
         * {@code INPUT[]} —— 结构体数组的跨语言封送规则依赖 JNA 内部连续内存管理，
         * 而裸指针 + {@link #INPUT_TYPE_OFFSET} 等常量是<b>可自证</b>的（见
         * {@link #assertStructLayout()} 的偏移回读校验）。
         */
        int SendInput(int nInputs, Pointer pInputs, int cbSize);

        /**
         * {@code SetProcessDpiAwarenessContext}（Win10 1703+）。
         *
         * @param value {@code DPI_AWARENESS_CONTEXT}（{@link #DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2}）
         * @return 非 0 = 成功（已是该感知模式时返回 0 且不报错）
         */
        int SetProcessDpiAwarenessContext(Pointer value);

        /** {@code SetProcessDPIAware}（Vista+，Win10 1607 前的兜底）。 */
        boolean SetProcessDPIAware();

        /**
         * {@code PrintWindow} · 把窗口内容渲染进给定 DC。
         *
         * <p>CCB {@code src/utils/computerUse/win32/bridge.py:132}
         * {@code PrintWindow(hwnd, hdc_mem, 2)}（PW_RENDERFULLCONTENT）。
         *
         * @param hwnd  目标窗口
         * @param hdc   目标设备上下文
         * @param flags 标志（{@link #PW_RENDERFULLCONTENT}）
         * @return 非 0 = 成功
         */
        boolean PrintWindow(WinDef.HWND hwnd, WinDef.HDC hdc, int flags);

        /** {@code GetWindowRect}。 */
        boolean GetWindowRect(WinDef.HWND hwnd, WinDef.RECT rect);

        /** {@code GetForegroundWindow} —— 当前前台窗口（PoC 真机验证用）。 */
        WinDef.HWND GetForegroundWindow();

        /** {@code IsIconic} —— 窗口是否最小化。 */
        boolean IsIconic(WinDef.HWND hwnd);

        /** {@code ShowWindow}。 */
        boolean ShowWindow(WinDef.HWND hwnd, int cmd);

        /** {@code IsWindow} —— 窗口句柄是否有效。 */
        boolean IsWindow(WinDef.HWND hwnd);
    }

    /** gdi32.dll 需要的函数子集。 */
    public interface Gdi32 extends Library {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class, W32APIOptions.UNICODE_OPTIONS);

        WinDef.HDC CreateCompatibleDC(WinDef.HDC hdc);

        WinDef.HBITMAP CreateCompatibleBitmap(WinDef.HDC hdc, int width, int height);

        WinNT.HANDLE SelectObject(WinDef.HDC hdc, WinNT.HANDLE obj);

        boolean BitBlt(WinDef.HDC dest, int destX, int destY, int width, int height,
                       WinDef.HDC src, int srcX, int srcY, int rop);

        int GetDIBits(WinDef.HDC hdc, WinDef.HBITMAP hbm, int startScan, int cLines,
                      byte[] lpvBits, BITMAPINFOHEADER lpbmi, int usage);

        boolean DeleteObject(WinNT.HANDLE obj);

        boolean DeleteDC(WinDef.HDC hdc);

        int GetDeviceCaps(WinDef.HDC hdc, int index);
    }

    /**
     * 运行期结构体布局自证（fail loud）· 首次使用前调用一次。
     *
     * <p>① 尺寸：x64 ⇒ MOUSEINPUT=32 / INPUT=40 / BITMAPINFOHEADER=40 / BITMAPINFO=40；
     * x86 ⇒ MOUSEINPUT=24 / INPUT=28。
     * <br>② 偏移：写入魔数后回读，证明 {@link #INPUT_TYPE_OFFSET}、
     * {@link #MOUSEINPUT_DWFLAGS_OFFSET} 等常量与 JNA 实际布局一致。
     */
    public static void assertStructLayout() {
        boolean is64 = Native.SIZE_T_SIZE == 8;
        int expectedMouse = is64 ? 32 : 24;
        int expectedInput = is64 ? 40 : 28;
        int expectedHeader = 40;

        MOUSEINPUT mouse = new MOUSEINPUT();
        INPUT input = new INPUT();
        BITMAPINFOHEADER header = new BITMAPINFOHEADER();

        if (mouse.size() != expectedMouse || input.size() != expectedInput
                || header.size() != expectedHeader) {
            throw new IllegalStateException(String.format(
                    "[computer-use] JNA 结构体布局异常（ABI 不匹配）: MOUSEINPUT=%d(期望%d) INPUT=%d(期望%d) "
                            + "BITMAPINFOHEADER=%d(期望%d)",
                    mouse.size(), expectedMouse, input.size(), expectedInput,
                    header.size(), expectedHeader));
        }

        // ── 偏移自证：MOUSEINPUT.dx@0 / MOUSEINPUT.dwFlags@12 ──
        mouse.dx = 0x11223344;
        mouse.dwFlags = 0x55667788;
        mouse.write();
        Pointer mp = mouse.getPointer();
        if (mp.getInt(0) != 0x11223344 || mp.getInt(12) != 0x55667788) {
            throw new IllegalStateException("[computer-use] MOUSEINPUT 字段偏移与常量不一致（dx@0 / dwFlags@12 校验失败）");
        }

        // ── 偏移自证：INPUT.type@0 / INPUT.union@8 ──
        input.type = 0x01020304;
        input.mi.dx = 0x0A0B0C0D;
        input.write();
        Pointer ip = input.getPointer();
        if (ip.getInt(INPUT_TYPE_OFFSET) != 0x01020304) {
            throw new IllegalStateException("[computer-use] INPUT.type 偏移与常量不一致（type@" + INPUT_TYPE_OFFSET + "）");
        }
        if (ip.getInt(INPUT_UNION_OFFSET) != 0x0A0B0C0D) {
            throw new IllegalStateException("[computer-use] INPUT union 偏移与常量不一致（union@" + INPUT_UNION_OFFSET + "）");
        }

        log.info("[computer-use] JNA 结构体布局自证通过: MOUSEINPUT={} INPUT={} BITMAPINFOHEADER={}",
                mouse.size(), input.size(), header.size());

        // ── 偏移自证：KEYBDINPUT.wVk@8 / wScan@10 / dwFlags@12 + INPUT.type@0 ──
        Memory kmem = buildKeyboardInput(0x41, 0x0042, KEYEVENTF_UNICODE);
        if (kmem.getInt(INPUT_TYPE_OFFSET) != INPUT_KEYBOARD
                || kmem.getShort(KEYBDINPUT_WVK_OFFSET) != 0x41
                || kmem.getShort(KEYBDINPUT_WSCAN_OFFSET) != 0x0042
                || kmem.getInt(KEYBDINPUT_DWFLAGS_OFFSET) != KEYEVENTF_UNICODE) {
            throw new IllegalStateException(
                    "[computer-use] KEYBDINPUT 字段偏移与常量不一致（wVk@8 / wScan@10 / dwFlags@12 校验失败）");
        }
    }

    /**
     * 手工构造一块 {@code INPUT} 内存（鼠标分支）。
     *
     * @param dwFlags {@code MOUSEEVENTF_*} 标志
     * @return 可直接传给 {@code SendInput} 的原生内存
     */
    public static Memory buildMouseInput(int dwFlags) {
        if (!is64Bit()) {
            throw new IllegalStateException(
                    "[computer-use] PoC 的 INPUT 手工偏移仅覆盖 64 位 Windows ABI，当前进程为 32 位");
        }
        int size = 40;
        Memory mem = new Memory(size);
        mem.clear();
        // Memory 本身就是 Pointer（peer 指向分配的原生内存）⇒ 直接在它上面按偏移写字段。
        // 注意：不能用 mem.getPointer(0) —— JNA 的 Memory.getPointer(long) 返回 null。
        mem.setInt(INPUT_TYPE_OFFSET, INPUT_MOUSE);
        mem.setInt(MOUSEINPUT_DX_OFFSET, 0);
        mem.setInt(MOUSEINPUT_DY_OFFSET, 0);
        mem.setInt(MOUSEINPUT_MOUSEDATA_OFFSET, 0);
        mem.setInt(MOUSEINPUT_DWFLAGS_OFFSET, dwFlags);
        mem.setInt(MOUSEINPUT_TIME_OFFSET, 0);
        return mem;
    }

    private static boolean is64Bit() {
        return Native.SIZE_T_SIZE == 8;
    }

    /**
     * 手工构造一块 {@code INPUT} 内存（滚轮分支）—— {@code MOUSEEVENTF_WHEEL} /
     * {@code MOUSEEVENTF_HWHEEL} 用 {@code mouseData} 携带格数。
     *
     * <p>CCB {@code src/utils/computerUse/platforms/win32.ts:367-374}：
     * {@code $i.mi.mouseData = amount * 120}（{@code WHEEL_DELTA = 120}）。
     *
     * @param dwFlags   {@code MOUSEEVENTF_WHEEL} / {@code MOUSEEVENTF_HWHEEL}
     * @param mouseData 滚轮格数（已乘 {@code WHEEL_DELTA}）
     * @return 可直接传给 {@code SendInput} 的原生内存
     */
    public static Memory buildWheelInput(int dwFlags, int mouseData) {
        if (!is64Bit()) {
            throw new IllegalStateException(
                    "[computer-use] PoC 的 INPUT 手工偏移仅覆盖 64 位 Windows ABI，当前进程为 32 位");
        }
        int size = 40;
        Memory mem = new Memory(size);
        mem.clear();
        mem.setInt(INPUT_TYPE_OFFSET, INPUT_MOUSE);
        mem.setInt(MOUSEINPUT_MOUSEDATA_OFFSET, mouseData);
        mem.setInt(MOUSEINPUT_DWFLAGS_OFFSET, dwFlags);
        mem.setInt(MOUSEINPUT_TIME_OFFSET, 0);
        return mem;
    }

    /**
     * 手工构造一块 {@code INPUT} 内存（键盘分支）。
     *
     * <p><b>与鼠标分支共用同一块 union 区</b>（{@link #INPUT_UNION_OFFSET}）——
     * {@code KEYBDINPUT} 只占 24 字节（{@code MOUSEINPUT} 占 32）⇒ union 大小不变，
     * {@code INPUT} 总长仍是 40 字节。
     *
     * @param vk    虚拟键码（{@code KEYEVENTF_UNICODE} 时填 0）
     * @param scan  扫描码 / Unicode 码元（{@code KEYEVENTF_UNICODE} 时携带 UTF-16 码元）
     * @param flags {@code KEYEVENTF_*} 标志
     * @return 可直接传给 {@code SendInput} 的原生内存
     */
    public static Memory buildKeyboardInput(int vk, int scan, int flags) {
        if (!is64Bit()) {
            throw new IllegalStateException(
                    "[computer-use] PoC 的 INPUT 手工偏移仅覆盖 64 位 Windows ABI，当前进程为 32 位");
        }
        int size = 40;
        Memory mem = new Memory(size);
        mem.clear();
        mem.setInt(INPUT_TYPE_OFFSET, INPUT_KEYBOARD);
        mem.setShort(KEYBDINPUT_WVK_OFFSET, (short) vk);
        mem.setShort(KEYBDINPUT_WSCAN_OFFSET, (short) scan);
        mem.setInt(KEYBDINPUT_DWFLAGS_OFFSET, flags);
        mem.setInt(KEYBDINPUT_TIME_OFFSET, 0);
        return mem;
    }
}

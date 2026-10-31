package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows 前台应用解析器（JNA）· 取前台窗口所属进程的 exe 名。
 *
 * <h2>取法</h2>
 * <pre>
 * GetForegroundWindow()                      → HWND
 * GetWindowThreadProcessId(hwnd, &amp;pid)      → pid
 * OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid)
 * QueryFullProcessImageName(h, 0, buf, &amp;size) → exe 全路径
 * CloseHandle(h)
 * </pre>
 *
 * <h2>与 CCB 的差异</h2>
 * <p>CCB 在 Windows 上用 <b>PowerShell</b> 取同样的信息
 * （{@code src/utils/computerUse/platforms/win32.ts:757-771}：每次动作 spawn 一次
 * {@code powershell.exe}）—— 本仓改用 <b>JNA 直调</b>（同进程、零子进程、毫秒级）。
 * <b>取值语义完全相同</b>（前台窗口 → 进程 → exe 全路径）。
 *
 * <h2>懒加载</h2>
 * <p>native 库在<b>首次实际调用</b>时才加载（holder 惯用法）⇒ 单测构造本类不会触碰
 * native（非 Windows 机器上也不会炸）。
 */
public final class Win32ForegroundAppResolver implements ComputerUseForegroundAppResolver {

    private static final Logger log = LoggerFactory.getLogger(Win32ForegroundAppResolver.class);

    /** {@code PROCESS_QUERY_LIMITED_INFORMATION}（Vista+）。 */
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** exe 路径缓冲区长度（Windows 路径上限 32767，取 1024 足够）。 */
    private static final int PATH_BUFFER_SIZE = 1024;

    @Override
    public String currentAppId() {
        if (!Platform.isWindows()) {
            return null;
        }
        try {
            WinDef.HWND hwnd = NativeHolder.USER32.GetForegroundWindow();
            if (hwnd == null || hwnd.getPointer() == null || hwnd.getPointer().equals(Pointer.NULL)) {
                return null;
            }
            IntByReference pidRef = new IntByReference();
            NativeHolder.USER32.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();
            if (pid <= 0) {
                return null;
            }
            WinNT.HANDLE handle = NativeHolder.KERNEL32.OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (handle == null || handle.getPointer() == null || handle.getPointer().equals(Pointer.NULL)) {
                return null;
            }
            try {
                char[] buffer = new char[PATH_BUFFER_SIZE];
                IntByReference size = new IntByReference(PATH_BUFFER_SIZE);
                boolean ok = NativeHolder.KERNEL32.QueryFullProcessImageName(handle, 0, buffer, size);
                if (!ok) {
                    return null;
                }
                String exePath = new String(buffer, 0, size.getValue());
                return ComputerUseAppPolicy.normalizeAppKey(exePath);
            } finally {
                NativeHolder.KERNEL32.CloseHandle(handle);
            }
        } catch (Throwable t) {
            log.warn("[computer-use] 取前台应用失败（按 CCB 语义放行）: {}", t.toString());
            return null;
        }
    }

    /**
     * user32 子集。
     */
    public interface User32 extends Library {
        /** {@code GetForegroundWindow} —— 当前前台窗口。 */
        WinDef.HWND GetForegroundWindow();

        /** {@code GetWindowThreadProcessId} —— 取窗口所属进程 id。 */
        int GetWindowThreadProcessId(WinDef.HWND hwnd, IntByReference lpdwProcessId);
    }

    /**
     * kernel32 子集。
     */
    public interface Kernel32 extends Library {
        /** {@code OpenProcess}。 */
        WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        /** {@code QueryFullProcessImageName} —— 取进程 exe 全路径。 */
        boolean QueryFullProcessImageName(WinNT.HANDLE hProcess, int dwFlags,
                                          char[] lpExeName, IntByReference lpdwSize);

        /** {@code CloseHandle}。 */
        boolean CloseHandle(WinNT.HANDLE hObject);
    }

    /**
     * native 懒加载 holder（首次访问才 load ⇒ 单测构造本类不触碰 native）。
     */
    private static final class NativeHolder {
        private static final User32 USER32 =
                Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);
        private static final Kernel32 KERNEL32 =
                Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        private NativeHolder() {
        }
    }
}

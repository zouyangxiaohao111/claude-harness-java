package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程 → 应用授权键解析器（JNA）· computer-use Phase 2b 的<b>绑定窗口门控</b>底座。
 *
 * <p><b>取值链路</b>：{@code pid} ⇒ {@code OpenProcess} + {@code QueryFullProcessImageName}
 * ⇒ exe 全路径 ⇒ {@link ComputerUseAppPolicy#normalizeAppKey(String)} ⇒ 授权键
 * （与 {@link Win32ForegroundAppResolver} <b>完全同款</b>，只是入口从「前台窗口」换成「指定 pid」）。
 *
 * <p><b>懒加载</b>：native 库在<b>首次实际调用</b>时才加载（holder 惯用法）⇒ 单测构造本类不触碰 native。
 */
public final class Win32ProcessAppResolver implements ComputerUseProcessAppResolver {

    private static final Logger log = LoggerFactory.getLogger(Win32ProcessAppResolver.class);

    /** {@code PROCESS_QUERY_LIMITED_INFORMATION}（Vista+）。 */
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** exe 路径缓冲区长度（Windows 路径上限 32767，取 1024 足够）。 */
    private static final int PATH_BUFFER_SIZE = 1024;

    @Override
    public String appIdForPid(int pid) {
        if (pid <= 0) {
            return null;
        }
        try {
            WinNT.HANDLE handle = NativeHolder.KERNEL32.OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (handle == null || handle.getPointer() == null
                    || handle.getPointer().equals(com.sun.jna.Pointer.NULL)) {
                return null;
            }
            try {
                char[] buffer = new char[PATH_BUFFER_SIZE];
                com.sun.jna.ptr.IntByReference size =
                        new com.sun.jna.ptr.IntByReference(PATH_BUFFER_SIZE);
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
            log.warn("[computer-use] 取 pid={} 的应用失败（按未知放行）: {}", pid, t.toString());
            return null;
        }
    }

    /** kernel32 子集。 */
    public interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        boolean QueryFullProcessImageName(WinNT.HANDLE hProcess, int dwFlags,
                                          char[] lpExeName, com.sun.jna.ptr.IntByReference lpdwSize);

        boolean CloseHandle(WinNT.HANDLE hObject);
    }

    /** native 懒加载 holder。 */
    private static final class NativeHolder {
        private static final Kernel32 KERNEL32 = Native.load("kernel32", Kernel32.class);

        private NativeHolder() {
        }
    }
}

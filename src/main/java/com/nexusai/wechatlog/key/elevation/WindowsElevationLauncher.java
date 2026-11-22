package com.nexusai.wechatlog.key.elevation;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.time.Duration;
import java.util.List;

/**
 * Windows 实现：用 {@code ShellExecuteEx(lpVerb="runas")} 触发一次 UAC 提权。
 *
 * <p>为什么不用 {@code ProcessBuilder}：它无法请求提权，{@code runas} 只能经 Shell 触发。
 * 用 {@code SEE_MASK_NOCLOSEPROCESS} 拿到子进程句柄后，才能等待结束并取退出码
 * （{@code ShellExecute} 那个简化版只能拿到“是否启动成功”）。
 *
 * <p>用户取消 UAC 时 {@code ShellExecuteEx} 返回失败且
 * {@code GetLastError() == ERROR_CANCELLED(1223)} —— 这是唯一可靠的取消判据。
 */
public final class WindowsElevationLauncher implements ElevationLauncher {

    /** 保留子进程句柄，否则无法等待/取退出码。 */
    private static final int SEE_MASK_NOCLOSEPROCESS = 0x00000040;

    /** 不弹错误对话框（UAC 授权框不受影响）。 */
    private static final int SEE_MASK_FLAG_NO_UI = 0x00000400;

    /** 用户在 UAC 里点了取消。 */
    private static final int ERROR_CANCELLED = 1223;

    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int WAIT_FAILED = -1;

    @Override
    public LaunchResult launch(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return LaunchResult.unavailable("empty command");
        }
        try {
            ShellAPI.SHELLEXECUTEINFO info = new ShellAPI.SHELLEXECUTEINFO();
            info.cbSize = info.size();
            info.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI;
            info.lpVerb = "runas";
            info.lpFile = command.get(0);
            info.lpParameters = WindowsCommandLine.join(command.subList(1, command.size()));
            info.lpDirectory = System.getProperty("user.home");
            info.nShow = WinUser.SW_HIDE;

            if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
                int err = Native.getLastError();
                if (err == ERROR_CANCELLED) {
                    return LaunchResult.cancelled();
                }
                return LaunchResult.unavailable("ShellExecuteEx failed, GetLastError=" + err);
            }

            WinNT.HANDLE handle = info.hProcess;
            if (handle == null) {
                return LaunchResult.unavailable("ShellExecuteEx succeeded but hProcess is null");
            }
            try {
                long millis = timeout == null ? 300_000L : timeout.toMillis();
                int waitMillis = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, millis));
                int waited = Kernel32.INSTANCE.WaitForSingleObject(handle, waitMillis);
                if (waited == WAIT_TIMEOUT) {
                    // 超时的子进程要收掉，避免提权进程常驻
                    try {
                        Kernel32.INSTANCE.TerminateProcess(handle, 1);
                    } catch (Exception ignored) {
                        // 尽力而为
                    }
                    return LaunchResult.unavailable("timeout after " + millis + "ms");
                }
                if (waited == WAIT_FAILED || waited != WAIT_OBJECT_0) {
                    return LaunchResult.unavailable("WaitForSingleObject returned " + waited);
                }
                IntByReference code = new IntByReference();
                if (!Kernel32.INSTANCE.GetExitCodeProcess(handle, code)) {
                    return LaunchResult.unavailable("GetExitCodeProcess failed");
                }
                return LaunchResult.completed(code.getValue());
            } finally {
                try {
                    Kernel32.INSTANCE.CloseHandle(handle);
                } catch (Exception ignored) {
                    // 关闭失败无副作用
                }
            }
        } catch (Throwable t) {
            return LaunchResult.unavailable("launch error: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }
}

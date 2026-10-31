package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 应用启动实现（JNA 直调 shell32）· computer-use Phase 2a 的 {@code open_application}。
 *
 * <h2>WHY 用 {@code ShellExecute} 而不是 CCB 的 PowerShell</h2>
 * <p>CCB 的 Windows 实现（{@code src/utils/computerUse/platforms/win32.ts:565-756}）走
 * PowerShell {@code Start-Process} + 注册表查安装目录 + 找 HWND + <b>把窗口挪到屏幕外</b>
 * （{@code (-32000,-32000)}）并绑定 —— 那是 CCB <b>桌面端「窗口定向输入」</b>路线的配套动作。
 * <br>本仓<b>没有窗口绑定</b>语义（全局注入路线，见 {@code ComputerUseLeftClickTool} 类注释）
 * ⇒ <b>不能</b>照抄「挪到屏幕外」（那会把用户的应用藏起来，且本仓不用 SendMessage 定向输入）。
 * <br>⇒ 本仓用 {@code ShellExecute("open", "<app>.exe")}：<b>正常启动并置前</b>，
 * 语义上就是 {@code open_application} 描述里的「Bring an application to the front, launching it
 * if necessary」。
 *
 * <p>⛔ <b>不引入新依赖</b>：JNA + jna-platform 已是本仓既有依赖。
 */
public final class Win32AppLauncher implements ComputerUseAppLauncher {

    private static final Logger log = LoggerFactory.getLogger(Win32AppLauncher.class);

    /** SW_SHOWNORMAL —— 正常显示并置前。 */
    private static final int SW_SHOWNORMAL = 1;

    /** {@code ShellExecute} 的返回值上界（> 32 表示成功）。 */
    private static final int SE_SUCCESS_BOUND = 32;

    /** shell32 的 {@code ShellExecute}。 */
    private interface Shell32Lib extends Library {
        Shell32Lib INSTANCE = Native.load("shell32", Shell32Lib.class);

        int ShellExecute(WinDef.HWND hwnd, String lpOperation, String lpFile,
                         String lpParameters, String lpDirectory, int nShowCmd);
    }

    private final Shell32Lib shell32 = Shell32Lib.INSTANCE;

    @Override
    public void launch(String appKey) throws Exception {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("[computer-use] 应用标识不能为空");
        }
        // 授权键是「归一后的可执行文件名」（无 .exe）⇒ 补回扩展名再交给 ShellExecute
        String target = appKey.contains(".") ? appKey : appKey + ".exe";
        int result = shell32.ShellExecute(null, "open", target, null, null, SW_SHOWNORMAL);
        if (result <= SE_SUCCESS_BOUND) {
            throw new IllegalStateException(String.format(
                    "[computer-use] 启动应用失败: %s（ShellExecute 返回 %d）", target, result));
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 应用已启动: {}（ShellExecute={}）", target, result);
        }
    }
}

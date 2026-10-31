package com.nexusai.application.agent.computeruse;

/**
 * 「当前绑定的窗口」· computer-use Phase 2b 的窗口定向输入的<b>锚点</b>。
 *
 * <p><b>CCB 对应物</b>：CCB 把绑定状态放在<b>模块级变量</b>
 * （{@code src/utils/computerUse/platforms/win32.ts} 的 {@code boundHwnd} / {@code boundPid}）。
 * <br>本仓是<b>多会话</b>后端 ⇒ 绑定状态<b>按 sessionId 分桶</b>
 * （与 {@link ComputerUseMouseState} / {@link ComputerUseDisplayStore} 同款约定），
 * 避免 A 会话绑的窗口影响 B 会话。
 *
 * <p><b>绑定时冻结的授权信息</b>：{@code appId}（绑定窗口所属进程的归一 exe 名）与
 * {@code tier}（该应用在会话 allowlist 里的授权档位）在<b>绑定那一刻</b>取自
 * {@link ComputerUseGrantStore} ⇒ 之后所有窗口定向工具的档位门控都读这同一份快照
 * （不依赖「当前前台应用」—— 窗口定向输入的语义就是「打给这个窗口」，
 * 与前台是谁无关）。
 *
 * @param window 窗口标识（句柄 + pid + 标题）
 * @param appId  绑定窗口所属应用的授权键（归一后的 exe 名）；<b>未知 ⇒ null</b>
 * @param tier   该应用在会话 allowlist 里的授权档位 wire 串；<b>未授权 ⇒ null</b>
 */
public record ComputerUseWindowBinding(
        ComputerUseWindowInfo window,
        String appId,
        String tier) {

    public ComputerUseWindowBinding {
        if (window == null) {
            throw new IllegalArgumentException("ComputerUseWindowBinding.window 不能为空");
        }
    }

    /**
     * 绑定窗口的句柄数值。
     *
     * @return HWND 数值
     */
    public long hwnd() {
        return window.hwnd();
    }
}

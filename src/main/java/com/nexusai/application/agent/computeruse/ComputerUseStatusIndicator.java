package com.nexusai.application.agent.computeruse;

/**
 * 状态指示浮层（status indicator）· computer-use Phase 2b 的 {@code status_indicator} 底座。
 *
 * <p><b>CCB 对照</b>：{@code src/utils/computerUse/win32/inputIndicator.ts} 用一个
 * <b>独立的 PowerShell WinForms 进程</b>在绑定窗口底部显示一个小浮层标签
 * （{@code FADE_AFTER_MS = 2000} ⇒ 2 秒无更新自动淡出；{@code WS_EX_TRANSPARENT}
 * ⇒ 点击穿透；{@code TOPMOST} ⇒ 置顶）。
 *
 * <p><b>本仓的取舍</b>：同样走<b>独立 PowerShell 进程</b>画浮层（与 CCB 同款机制），
 * 但用<b>一次性</b>脚本（显示 → 2 秒后自动淡出并退出），而不是 CCB 的
 * “常驻进程 + 消息文件轮询” 模型 ——
 * 后者需要一个常驻后台进程与消息文件协议，收益仅在于「2 秒内连续更新不重启进程」。
 */
public interface ComputerUseStatusIndicator {

    /**
     * 在绑定窗口底部显示一条状态浮层（2 秒后自动淡出）。
     *
     * @param hwnd    绑定窗口句柄
     * @param message 浮层文字
     */
    void show(long hwnd, String message);

    /**
     * 隐藏（并结束）当前浮层。
     */
    void hide();

    /**
     * 浮层当前是否处于显示状态。
     *
     * @return true = 正在显示
     */
    boolean isActive();
}

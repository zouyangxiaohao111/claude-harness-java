package com.nexusai.application.agent.computeruse;

/**
 * 终端启动器 · computer-use Phase 2b 的 {@code open_terminal} 底座。
 *
 * <p><b>WHY 抽接口</b>：真启动终端会<b>真的弹出一个窗口</b> ⇒ 纯单测不能碰 ⇒ 能力必须可注入
 * （与 {@link ComputerUseAppLauncher} 同款约定）。
 */
public interface ComputerUseTerminalLauncher {

    /**
     * 启动一个终端窗口并在其中执行命令。
     *
     * <p>实现必须<b>不阻塞</b>：命令是在新终端里跑的，终端本身要保持存活。
     *
     * @param agent            要启动的 agent（{@code claude} / {@code codex} / {@code gemini} / {@code custom}）
     * @param command          要执行的命令（{@code custom} 时必填）
     * @param terminal         终端类型（{@code wt} / {@code powershell} / {@code cmd}）
     * @param workingDirectory 工作目录（null ⇒ 当前目录）
     * @throws Exception 启动失败（fail loud，由调用方转成工具错误）
     */
    void launch(String agent, String command, String terminal, String workingDirectory) throws Exception;
}

package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * macOS 外部命令调用封装 · computer-use macOS 执行端。
 *
 * <p><b>WHY 单独一层</b>：macOS 执行端所有实现都要「跑一条 {@code osascript} 并在失败时
 * fail loud」⇒ 把命令拼装 + 退出码判定 + 错误文案收敛到一处，
 * 避免每个实现类各写一遍（且写歪）。
 */
final class MacOsExec {

    private MacOsExec() {
        throw new AssertionError("utility class");
    }

    /**
     * 跑一条 JXA 脚本（{@code osascript -l JavaScript -e <script>}）。
     *
     * @param runner 执行器
     * @param script JXA 脚本
     * @return stdout（已去首尾空白）
     * @throws IllegalStateException 命令失败（带 stderr 原文）
     * @throws Exception 进程启动失败
     */
    static String jxa(ComputerUseProcessRunner runner, String script) throws Exception {
        ComputerUseProcessRunner.Result result =
                runner.run(List.of("osascript", "-l", "JavaScript", "-e", script));
        if (!result.ok()) {
            throw new IllegalStateException(
                    "[computer-use] osascript 执行失败（退出码 " + result.exitCode() + "）: "
                            + result.stderr());
        }
        return result.stdout().trim();
    }

    /**
     * 跑一条 JXA 脚本（只关心成功与否）。
     *
     * @param runner 执行器
     * @param script JXA 脚本
     * @throws IllegalStateException 命令失败
     * @throws Exception 进程启动失败
     */
    static void jxaVoid(ComputerUseProcessRunner runner, String script) throws Exception {
        jxa(runner, script);
    }
}

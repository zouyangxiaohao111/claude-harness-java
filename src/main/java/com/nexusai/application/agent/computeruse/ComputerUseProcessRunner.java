package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * 外部命令执行器（可注入）· computer-use <b>macOS 执行端</b>的底座。
 *
 * <h2>WHY 抽接口（可测性）</h2>
 * <p>macOS 执行端全部走<b>进程外调用</b>（{@code osascript -l JavaScript -e '…'} 与
 * {@code screencapture -x …}）—— 对齐 CCB 的 macOS 路线：
 * <ul>
 *   <li>输入注入：{@code packages/@ant/computer-use-input/src/backends/darwin.ts}
 *       （{@code jxa()} = {@code osascript -l JavaScript -e <script>}）；</li>
 *   <li>截屏：{@code packages/@ant/computer-use-swift/src/backends/darwin.ts:270-303}
 *       （{@code screencapture -x <file>}）。</li>
 * </ul>
 * <p>⇒ 若把 {@code ProcessBuilder} 直接写在实现类里，单测就<b>必须真起进程</b>
 * （Windows 上根本没有 {@code osascript} ⇒ 测试必红）。
 * <br>⇒ 本接口把「执行外部命令」从「拼什么命令」里剥出来：
 * 生产用 {@link #REAL}（真起进程），单测注入<b>记录型假实现</b>
 * ⇒ 单测可以<b>零副作用</b>断言「生成的 JXA 脚本含正确的 CoreGraphics 调用与参数」。
 *
 * <p>设计先例：{@link ComputerUseSleeper}（把「等多久」从「做什么」里剥出来）。
 *
 * <h2>WHY 返回 {@link Result} 而不是直接抛</h2>
 * <p>调用方需要区分「命令没跑起来」（{@code exitCode < 0}）与「命令跑了但失败」
 * （{@code exitCode > 0}）—— 后者要把 stderr 原文带进错误文案（fail loud）。
 * ⇒ 本接口<b>不吞异常</b>：启动失败（IOException）照常抛出。
 */
@FunctionalInterface
public interface ComputerUseProcessRunner {

    /**
     * 一次外部命令的执行结果。
     *
     * @param exitCode 退出码（{@code 0} = 成功；{@code > 0} = 命令自报失败；
     *                 {@code < 0} = 进程未被正常启动）
     * @param stdout   标准输出（UTF-8 解码；null ⇒ 空串）
     * @param stderr   标准错误（UTF-8 解码；null ⇒ 空串）
     */
    record Result(int exitCode, String stdout, String stderr) {

        public Result {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        /** @return true = 退出码 0 */
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * 执行一条命令（<b>不经过 shell</b>：参数逐个传，避免引号 / 转义地雷）。
     *
     * @param command 命令与参数（{@code [0]} = 可执行文件名）
     * @return 执行结果
     * @throws Exception 进程启动失败 / IO 异常
     */
    Result run(List<String> command) throws Exception;

    /**
     * 执行一条命令并把 {@code stdin} 写给它的标准输入。
     *
     * <p><b>默认实现 = 不支持</b>（抛 {@link UnsupportedOperationException}）——
     * 只有需要写 stdin 的场景（如 macOS 的 {@code pbcopy}）才需要覆写。
     *
     * @param command 命令与参数
     * @param stdin   要写入的标准输入（null / 空 ⇒ 不写）
     * @return 执行结果
     * @throws Exception 进程启动失败 / IO 异常
     */
    default Result runWithStdin(List<String> command, String stdin) throws Exception {
        throw new UnsupportedOperationException(
                "[computer-use] 该执行器不支持写入 stdin: " + RealComputerUseProcessRunner.preview(command));
    }

    /**
     * 生产实现 · 真起进程（{@link RealComputerUseProcessRunner}）。
     */
    ComputerUseProcessRunner REAL = new RealComputerUseProcessRunner();
}

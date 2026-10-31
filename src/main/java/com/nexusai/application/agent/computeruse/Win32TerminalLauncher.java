package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 终端启动器实现（Windows）· computer-use Phase 2b 的 {@code open_terminal} 底座。
 *
 * <p><b>机制</b>：{@code ProcessBuilder} 起一个<b>新终端进程</b>，把 agent 命令作为它的启动参数
 * ⇒ 终端窗口起来后命令已经在跑（等价于 CCB 的
 * {@code Start-Process powershell -ArgumentList '-NoExit','-Command','cd ''cwd''; cmd'}，
 * 见 {@code src/utils/computerUse/executorCrossPlatform.ts:606-614}）。
 *
 * <p><b>⚠️ 与 CCB 的差异（如实标注）</b>：CCB-Windows 的 {@code openTerminal} 实现
 * <b>完全没有读 {@code terminal} 参数</b> —— 无论模型传 {@code wt} / {@code powershell} /
 * {@code cmd}，实际都起 {@code powershell}（{@code executorCrossPlatform.ts:606-614} 只用了
 * {@code agentCmd} 与 {@code workingDirectory}）。
 * <br>本仓<b>按 schema 承诺实现 terminal 选择</b>（schema 逐字抄自 CCB，其中明写
 * “Which terminal to open. Default: 'wt'”）——
 * <b>照抄 CCB 的话我们的 schema 就是一句空话</b>（对模型说谎）。
 * ⇒ 这里<b>有意偏离 CCB</b>，以「schema 说的话必须真做」为准，在此如实标注。
 */
public final class Win32TerminalLauncher implements ComputerUseTerminalLauncher {

    private static final Logger log = LoggerFactory.getLogger(Win32TerminalLauncher.class);

    @Override
    public void launch(String agent, String command, String terminal, String workingDirectory)
            throws Exception {
        String cmd = resolveCommand(agent, command);
        String cwd = workingDirectory == null || workingDirectory.isBlank()
                ? System.getProperty("user.dir") : workingDirectory;
        List<String> argv = buildArgv(terminal, cmd, cwd);

        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(new File(cwd));
        // 终端自己负责显示与交互 ⇒ 不接管它的 stdin/stdout
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        process.onExit().thenAccept(p -> {
            if (log.isInfoEnabled()) {
                log.info("[computer-use] 终端进程已退出: type={} exit={}", terminal, p.exitValue());
            }
        });
        if (log.isInfoEnabled()) {
            log.info("[computer-use] 终端已启动: type={} cmd=\"{}\" cwd=\"{}\"", terminal, cmd, cwd);
        }
    }

    /**
     * agent → 命令 · CCB {@code executorCrossPlatform.ts:591-596} 的 {@code agentCmd}。
     *
     * @param agent   agent 名
     * @param command {@code custom} 时的自定义命令
     * @return 要执行的命令
     */
    static String resolveCommand(String agent, String command) {
        return switch (agent == null ? "" : agent) {
            case "claude" -> "claude";
            case "codex" -> "codex";
            case "gemini" -> "gemini";
            case "custom" -> command == null ? "" : command;
            default -> throw new IllegalArgumentException("不支持的 agent: " + agent);
        };
    }

    /**
     * 终端类型 → 启动命令行。
     *
     * @param terminal 终端类型（{@code wt} / {@code powershell} / {@code cmd}）
     * @param cmd      要执行的命令
     * @param cwd      工作目录
     * @return 命令行
     */
    static List<String> buildArgv(String terminal, String cmd, String cwd) {
        List<String> argv = new ArrayList<>();
        switch (terminal == null || terminal.isBlank() ? "wt" : terminal) {
            case "wt" -> {
                // Windows Terminal：wt.exe -d <dir> <commandline>
                argv.add("wt.exe");
                argv.add("-d");
                argv.add(cwd);
                argv.add(cmd);
            }
            case "powershell" -> {
                // PowerShell 窗口：-NoExit 保持窗口存活（CCB 同款）
                argv.add("powershell.exe");
                argv.add("-NoExit");
                argv.add("-Command");
                argv.add("Set-Location -LiteralPath " + quotePs(cwd) + "; " + cmd);
            }
            case "cmd" -> {
                // 命令提示符：/k 保持窗口存活
                argv.add("cmd.exe");
                argv.add("/k");
                argv.add("cd /d " + quoteCmd(cwd) + " && " + cmd);
            }
            default -> throw new IllegalArgumentException("不支持的终端类型: " + terminal);
        }
        return argv;
    }

    /** PowerShell 单引号串转义。 */
    static String quotePs(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** cmd.exe 的路径加引号。 */
    static String quoteCmd(String s) {
        return "\"" + s + "\"";
    }
}

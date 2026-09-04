package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Windows bash 引号安全 2026-09-03 方案A] 回归测试。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>：Windows 上 Java {@code ProcessBuilder} 构造命令行时恒按 CRT 规则转义
 * argv（含 {@code "} → {@code \"}），与 MSYS/Git Bash 的 argv 还原不兼容 → 含 {@code "} 的命令串被截断
 * （2026-09-03 事故：模型执行 {@code python -c "import docx; print('python-docx OK')"}，bash 还原只剩第一个词
 * → python 裸启无 {@code -c} → 挂起 120s）。修复 = {@link ShellExecutor#bash} 在 Windows 且命令含 {@code "}
 * 时写临时 {@code .sh} 脚本文件，改 {@code bash <脚本文件>} 执行（argv 只剩路径零引号转义）。
 *
 * <p>变异点：命令含 {@code "} 时仍走 {@code -c} argv → Windows 命令行转义截断 → python 无输出/挂 → 红。
 * 非 Windows（POSIX execve argv 数组，无命令行转义问题）不触发脚本文件路径，本测试仅验证 bash() 不坏。
 */
@DisplayName("BashQuoteFix 回归：Windows 含 \" 命令走脚本文件（方案A）")
class BashQuoteFixRegressionTest {

    /** Windows 平台判定 · 等价 ShellExecutor 内部 IS_WINDOWS。 */
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    /** 事故命令用的 python（仅本机 Windows 有效；CI/非 Windows 不存在 → python 用例跳过）。 */
    private static final String PYTHON = "C:/Python314/python.exe";

    /** 可用 shell 判定 · ShellResolver 找不到 → 跳过（同 ShellExecutorTest 惯例）。 */
    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Windows + python.exe 存在才跑 python 用例（bug 本身仅 Windows + Git Bash 触发）。 */
    private static boolean pythonAvailable() {
        return IS_WINDOWS && Files.isRegularFile(Path.of(PYTHON));
    }

    /**
     * 执行辅助：wrapForCwdTracking 包装 + {@code ShellExecutor.bash(wrapped, null, null)}（3 参无快照 →
     * 加 {@code -l}）+ 合并流 + 15s 超时。
     *
     * @return 是否自然退出 / 退出码 / 合并输出
     */
    private static ExecResult runWrapped(String rawCommand) throws Exception {
        Path track = Files.createTempFile("nexusai-bash-quote-track", ".tmp");
        String wrapped = ShellExecutor.wrapForCwdTracking(rawCommand, track);
        ProcessBuilder pb = ShellExecutor.bash(wrapped, null, null);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean exited = p.waitFor(15, TimeUnit.SECONDS);
        if (!exited) {
            // 修复前事故形态：python 裸启挂起 → 先杀再读（避免 readAllBytes 阻塞）
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new ExecResult(exited, exited ? p.exitValue() : -1, out);
    }

    // ════════════════════════════════════════════════════════════════
    // 事故复刻（Windows + python）：双引号 + 单引号
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("事故复刻：python -c \"import docx; print('python-docx OK', 123)\"（双引号+单引号）→ exit 0 且输出完整")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void pythonDoubleAndSingleQuotes_runsToCompletion() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(),
            "仅 Windows 且 C:/Python314/python.exe 存在时跑 python 事故复刻");
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        ExecResult r = runWrapped(PYTHON + " -c \"import docx; print('python-docx OK', 123)\"");
        assertThat(r.exited)
            .as("命令必须正常完成——修复前 Windows argv 截断使 python 裸启挂起 15s 超时")
            .isTrue();
        assertThat(r.exitCode).as("python 正常退出码 0").isEqualTo(0);
        assertThat(r.output).as("python 输出保留（修复前丢参截断无输出）")
            .contains("python-docx OK").contains("123");
    }

    @Test
    @DisplayName("纯双引号（python -c \"print(123)\"，无单引号）→ exit 0 且输出 123")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void pythonDoubleQuotesOnly_runsToCompletion() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "仅 Windows 且 C:/Python314/python.exe 存在时跑 python 用例");
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        ExecResult r = runWrapped(PYTHON + " -c \"print(123)\"");
        assertThat(r.exited).as("命令必须正常完成（不挂起）").isTrue();
        assertThat(r.exitCode).isEqualTo(0);
        assertThat(r.output).contains("123");
    }

    // ════════════════════════════════════════════════════════════════
    // 回归（所有平台可跑）：普通简单命令不回归 + 结构自检
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("普通简单命令不回归：echo hello（无引号走 -c）→ exit 0 且输出 hello")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void simpleCommand_stillRunsViaC() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        ExecResult r = runWrapped("echo hello");
        assertThat(r.exited).as("echo 必须正常完成").isTrue();
        assertThat(r.exitCode).isEqualTo(0);
        assertThat(r.output).contains("hello");
    }

    // ════════════════════════════════════════════════════════════════
    // 2026-09-04 事故复刻：grep 双引号模式串含竖线 + 管道
    //  （方案B quoteOne 元字符保护 + 方案A stdin 接空）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("事故复刻：grep -oE \"JS_A|JS_B|M_JS|TODO|PLACEHOLDER|占位\" f | sort | uniq -c → 快速完成 + 计数正确 + 无 command not found")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void grepDoubleQuotedPipePattern_noSplit_noHang() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        // 用临时文件模拟目标 HTML（含占位符痕迹），避免依赖 D: 盘演示目录。
        Path f = Files.createTempFile("nexusai-hang-repro-", ".txt");
        Files.writeString(f,
            "id=\"view-bear\"\nJS_A JS_A\nM_JS 占位\nJS_B TODO PLACEHOLDER\n", StandardCharsets.UTF_8);
        String target = f.toAbsolutePath().toString().replace("\\", "/");
        // 用户命令核心形态：双引号内竖线模式串 + 管道 sort/uniq（触发 rearrangePipeCommand 重组）。
        String raw = "grep -oE \"JS_A|JS_B|M_JS|TODO|PLACEHOLDER|占位\" " + target + " | sort | uniq -c";

        ExecResult r = runWrapped(raw);
        assertThat(r.exited)
            .as("命令必须快速完成——修复前竖线被 eval 拆管道 → 裸 grep 读 stdin → 挂到 15s 超时（Java 服务 stdin 非 EOF）")
            .isTrue();
        assertThat(r.exitCode).as("grep 正常退出码 0").isEqualTo(0);
        assertThat(r.output).as("占位符计数保留（修复前竖线裸拆 → 计数丢失 + command not found 噪音）")
            .contains("2 JS_A").contains("1 M_JS").contains("占位");
        assertThat(r.output).as("无 command not found 噪音（竖线未被拆成独立命令）")
            .doesNotContain("command not found");
        Files.deleteIfExists(f);
    }

    @Test
    @DisplayName("stdin 接空（方案A 兜底）：无参 cat 快速 EOF 退出，绝不挂起")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void noArgCat_stdinDiscarded_noHang() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        // WHY（方案A 意图）：Java 服务进程 stdin 是不关闭的句柄；无参 cat 读 stdin 若不接空会
        //   永久挂起。bashProcessBuilder 已 redirectInput(DISCARD) → cat 立即读到 EOF 退出。
        ExecResult r = runWrapped("cat");
        assertThat(r.exited).as("无参 cat 读 stdin 必须快速 EOF 退出（DISCARD 兜底，修复前可能挂满 15s 超时）").isTrue();
        assertThat(r.exitCode).as("cat 读 EOF 正常退出码 0").isEqualTo(0);
    }

    @Test
    @DisplayName("结构自检：bash ProcessBuilder stdin 已接空（方案A 兜底配置，平台无关）")
    void bashProcessBuilder_stdinDiscarded() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        ProcessBuilder pb = ShellExecutor.bash("echo hi", null);
        ProcessBuilder.Redirect stdin = pb.redirectInput();
        assertThat(stdin.type())
            .as("stdin 定向为 READ（从空设备读）——DISCARD 是 WRITE 仅合法用于丢弃输出，redirectInput 会抛 IllegalArgumentException")
            .isEqualTo(ProcessBuilder.Redirect.Type.READ);
        assertThat(stdin.file() == null ? "" : stdin.file().getName().toLowerCase())
            .as("stdin 空设备 = Windows NUL / POSIX /dev/null——Java 服务 stdin 非 EOF，命令意外落空读输入将永久挂起（方案A）")
            .isIn("nul", "dev", "null");
    }

    @Test
    @DisplayName("结构自检：Windows 且命令含 \" → argv=[bash, <脚本文件>] 无 -c，脚本内容含原命令")
    void windowsQuoteCommand_usesScriptFileArgv() throws Exception {
        Assumptions.assumeTrue(IS_WINDOWS, "仅 Windows 验证脚本文件路径（非 Windows 不触发）");
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash");

        ProcessBuilder pb = ShellExecutor.bash("echo \"hi\"", null);
        List<String> cmd = pb.command();
        // 2 参 skipLogin=true（历史无 -l）+ Windows 含 " → argv = [bashPath, <脚本文件>]，无 -c
        assertThat(cmd).as("argv 不得再含 -c（命令内容已入脚本文件）").doesNotContain("-c");
        String script = cmd.get(cmd.size() - 1);
        assertThat(script).as("末参为 .sh 脚本文件路径").endsWith(".sh");
        try {
            assertThat(Files.isRegularFile(Path.of(script))).as("脚本文件已写入系统 TEMP").isTrue();
            assertThat(Files.readString(Path.of(script), StandardCharsets.UTF_8))
                .as("脚本内容保留原始双引号命令（零引号转义直达 bash）")
                .contains("echo \"hi\"");
        } finally {
            Files.deleteIfExists(Path.of(script)); // 测试自身创建、未 start → 就地清理，不污染 TEMP
        }
    }

    /** 执行结果载体。 */
    private record ExecResult(boolean exited, int exitCode, String output) {
    }
}

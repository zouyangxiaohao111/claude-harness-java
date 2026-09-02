package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.memory.MemoryFileDetection;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShellExecutor} 测试 · 对齐 CC {@code utils/Shell.ts exec()} + {@code bashProvider.buildExecCommand}。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：BashTool / LocalBashTaskRunner 共用 ShellExecutor
 * 承载 CC {@code exec()} 的 spawn 核心（探测 + 命令包装 + env 注入 + cwd 跟踪读回）。若 E2 命令包装
 * 的单引号转义 / Windows POSIX 转换错误，cd 持久化会损坏（Windows 上 Git Bash 收到 C:\ 字面量路径、
 * 或尾随 {@code ; & | #} 截断 {@code && pwd -P} 链）；若 E3 env 键错，子进程 $SHELL / 编辑器 / 环境标识
 * 与 CC 不一致。本测试逐 E1/E2/E3/E5 验证这些 WHY。
 */
@DisplayName("ShellExecutor bash 执行器（对齐 CC Shell.ts exec + bashProvider.buildExecCommand）")
class ShellExecutorTest {

    @TempDir
    Path tempDir;

    /** 平台判定 · 等价 ShellExecutor 内部 IS_WINDOWS（用于构造跨平台一致的期望值）。 */
    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 是否有可用 bash/zsh（Windows 走 Git Bash）· ShellResolver 找不到 → 跳过（同既有测试惯例）。 */
    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 动态 disable extglob 前缀 · 与 ShellExecutor.wrapForCwdTracking 内部 resolveShell 同源（跨主机一致）。 */
    private static String disableExtglobPrefix() {
        try {
            String shell = ShellResolver.resolveShell();
            if (System.getenv("CLAUDE_CODE_SHELL_PREFIX") != null) {
                return "{ shopt -u extglob || setopt NO_EXTENDED_GLOB; } >/dev/null 2>&1 || true && ";
            }
            if (shell.contains("bash")) {
                return "shopt -u extglob 2>/dev/null || true && ";
            }
            if (shell.contains("zsh")) {
                return "setopt NO_EXTENDED_GLOB 2>/dev/null || true && ";
            }
            return "";
        } catch (IllegalStateException e) {
            return "";
        }
    }

    // ════════════════════════════════════════════════════════════════
    // E1 resolveShell 委托
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E1 resolveShell 委托 ShellResolver（同一探测链；找不到同样抛 CC 同款错）")
    void resolveShell_delegatesToShellResolver() {
        // WHY: ShellExecutor 与 BashTool/LocalBashTaskRunner 必须走同一 shell 探测链
        //       （CLAUDE_CODE_SHELL → SHELL → Git Bash → which → 常见路径 → 显式抛错），
        //       否则 Windows 上静默回退 cmd.exe，模型按 bash 语法写的命令报错。
        String viaResolver;
        try {
            viaResolver = ShellResolver.resolveShell();
        } catch (IllegalStateException e) {
            // 无 shell 主机：ShellExecutor 必须同样显式抛错（fail-loud），不能静默返回。
            assertThatThrownBy(ShellExecutor::resolveShell)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claude CLI requires a Posix shell environment");
            return;
        }
        assertThat(ShellExecutor.resolveShell()).isEqualTo(viaResolver);
    }

    // ════════════════════════════════════════════════════════════════
    // E2 wrapForCwdTracking 命令包装
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E2 wrapForCwdTracking：disable extglob + eval 隔离 + stdin /dev/null + pwd -P >| 写 track（bashProvider.ts:184-187）")
    void wrapForCwdTracking_buildsEvalPwdChain() {
        // WHY: CC 统一把命令包成 `[disable extglob] && eval <quoted> && pwd -P >| <cwdFile>`
        //       （bashProvider.ts:184-187 + :159-168）。quoted 对普通命令加 `< /dev/null`
        //       （防无 stdin 输入命令挂起，shellQuoting.ts:46-79）；trackFile 在 Windows 走 Git Bash
        //       先转 POSIX（/c/...，bashProvider.ts:118-121）；`>|` 为 clobber redirect（CC :186）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String track = tempDir.resolve("cwd-track").toString();
        String expectedTrack = isWindowsHost()
            ? MemoryFileDetection.windowsPathToPosixPath(track)
            : track;
        // track 无特殊字符 → shell-quote 原样（无引号），`>|` clobber
        assertThat(ShellExecutor.wrapForCwdTracking("echo hi", Path.of(track)))
            .isEqualTo(disableExtglobPrefix() + "eval 'echo hi' < /dev/null && pwd -P >| " + expectedTrack);
    }

    @Test
    @DisplayName("E2 wrapForCwdTracking：含单引号命令走 shell-quote 双引号（quote([cmd]) 语义）")
    void wrapForCwdTracking_escapesEmbeddedSingleQuote() {
        // WHY: CC 对普通命令用 shell-quote quote()（shellQuoting.ts:65-75）：含单引号的命令
        //       走双引号分支 `"a'b"`（内部 ' 字面量，eval 还原 a'b）；原 Java 简化用单引号
        //       `'a'\''b'`，形式不同——此处严格对齐 CC shell-quote 输出。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String wrapped = ShellExecutor.wrapForCwdTracking("a'b", Path.of("/tmp/t"));
        assertThat(wrapped).isEqualTo(disableExtglobPrefix() + "eval \"a'b\" < /dev/null && pwd -P >| /tmp/t");
    }

    @Test
    @DisplayName("E2 wrapForCwdTracking：含管道命令走 rearrange（单引号整体包裹不截断 pwd -P >| 链）")
    void wrapForCwdTracking_trailingOperatorsStayInsideQuotes() {
        // WHY: 含 `|` 且需 stdin → CC rearrangePipeCommand（bashPipeCommand.ts）：shell-quote parse
        //       重建 `echo hi ; & < /dev/null | #` 并 singleQuoteForEval 整体包裹 → eval 后 && pwd -P
        //       链不被尾随操作符截断（cd 追踪保持）。`>|` clobber redirect（bashProvider.ts:186）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String wrapped = ShellExecutor.wrapForCwdTracking("echo hi ; & | #", Path.of("/tmp/t"));
        assertThat(wrapped).startsWith(disableExtglobPrefix() + "eval 'echo hi ; &");
        assertThat(wrapped).contains("< /dev/null | #'");
        assertThat(wrapped).endsWith("&& pwd -P >| /tmp/t");
    }

    @Test
    @DisplayName("E2 wrapForCwdTracking：trackFile Windows native → POSIX 转换（/c/...）")
    void wrapForCwdTracking_windowsTrackToPosix() {
        // WHY: Git Bash 收到 C:\... 会当相对路径/字面量反斜杠处理，pwd 写错位置 → cd 损坏。
        //       期望值 = MemoryFileDetection.windowsPathToPosixPath(track)（跨主机一致判定）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String track = tempDir.resolve("bash-cwd-xyz.tmp").toString();
        String wrapped = ShellExecutor.wrapForCwdTracking("echo hi", Path.of(track));
        String expectedTrack = MemoryFileDetection.windowsPathToPosixPath(track);
        assertThat(wrapped).isEqualTo(disableExtglobPrefix() + "eval 'echo hi' < /dev/null && pwd -P >| " + expectedTrack);
    }

    @Test
    @DisplayName("E2 wrapForCwdTracking：2>nul Windows 重定向改写为 /dev/null（rewriteWindowsNullRedirect）")
    void wrapForCwdTracking_rewritesWindowsNullRedirect() {
        // WHY: 模型偶发输出 Windows CMD 风格 `2>nul`，Git Bash 会建字面量 nul 保留设备名文件
        //       （anthropics/claude-code#4928）。CC rewriteWindowsNullRedirect（shellQuoting.ts:126-134）
        //       改写为 /dev/null，防 git add/clone 被 nul 文件破坏。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String wrapped = ShellExecutor.wrapForCwdTracking("ls 2>nul", Path.of("/tmp/t"));
        assertThat(wrapped).contains("eval 'ls 2>/dev/null'");
        assertThat(wrapped).doesNotContain("2>nul");
    }

    @Test
    @DisplayName("E2 wrapForCwdTracking：heredoc 不加 stdin /dev/null（quoteShellCommand heredoc 分支）")
    void wrapForCwdTracking_heredoc_noStdinRedirect() {
        // WHY: heredoc 自带 stdin 输入（<<EOF），加 `< /dev/null` 会干扰 heredoc 终止符。
        //       CC quoteShellCommand 对 heredoc 返回单引号字面量、不加 stdin（shellQuoting.ts:52-60）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String wrapped = ShellExecutor.wrapForCwdTracking(
            "cat <<'EOF'\nhello\nEOF", Path.of("/tmp/t"));
        // heredoc → 单引号字面量（escape '"'"'），无 < /dev/null
        assertThat(wrapped).contains("eval 'cat <<'\"'\"'EOF'\"'\"'\nhello\nEOF'");
        assertThat(wrapped).doesNotContain("< /dev/null");
        assertThat(wrapped).endsWith("&& pwd -P >| /tmp/t");
    }

    // ════════════════════════════════════════════════════════════════
    // G2-1b wrapForBackground 后台命令包装（仅 source 快照、无 cwd 回写）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("wrapForBackground：快照存在 → source 前缀 + 原命令（无 pwd -P / 无 eval，bashProvider.ts:161-167）")
    void wrapForBackground_snapshotPresent_addsSourcePrefix_noPwd() {
        // WHY: 后台任务不需要 cwd 回写（对齐 CC 后台语义不更新 cwd），包装仅含
        //   `source <posix快照> 2>/dev/null || true && <原命令>`（bashProvider.ts:161-167），
        //   不得含 `pwd -P >|`（那是前台 wrapForExec 的 cwd 追踪段）也不得 eval/quote 隔离
        //   （保留既有 bash -c <原始命令> 语义）。
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");
        String snap = tempDir.resolve("snapshot-x.sh").toString();
        String expectedSnap = isWindowsHost()
            ? MemoryFileDetection.windowsPathToPosixPath(snap)
            : snap;
        String wrapped = ShellExecutor.wrapForBackground("echo bg", Path.of(snap));
        assertThat(wrapped)
            .isEqualTo("source " + ShellQuoteParser.quote(List.of(expectedSnap))
                + " 2>/dev/null || true && echo bg");
        assertThat(wrapped).as("后台包装不含 cwd 追踪段").doesNotContain("pwd -P");
        assertThat(wrapped).as("后台包装不做 eval/quote 隔离").doesNotContain("eval ");
    }

    @Test
    @DisplayName("wrapForBackground：快照为空/命令空白 → 原命令原样返回（不 source）")
    void wrapForBackground_nullSnapshotOrBlankCommand_returnsRaw() {
        // WHY: 快照缺失/命令空白时不得注入 source 前缀（空命令前置 `source ... && ` 会形成
        //   尾随 && 语法错误），原样返回由调用方回退 -l login shell（bashProvider.ts:200-206）。
        assertThat(ShellExecutor.wrapForBackground("echo hi", null)).isEqualTo("echo hi");
        assertThat(ShellExecutor.wrapForBackground("   ", Path.of("/tmp/snap.sh"))).isEqualTo("   ");
        assertThat(ShellExecutor.wrapForBackground("", Path.of("/tmp/snap.sh"))).isEqualTo("");
    }

    // ════════════════════════════════════════════════════════════════
    // E3 applyExecEnv env 注入
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E3 applyExecEnv：注入 SHELL/GIT_EDITOR/CLAUDECODE（Shell.ts:316-328，CC 真源键 CLAUDECODE 无下划线）")
    void applyExecEnv_injectsShellGitEditorClaudecode() {
        // WHY: 子进程必须继承 CC 同款 env——$SHELL 指向实际 shell、GIT_EDITOR=true 防 git 卡编辑器、
        //       CLAUDECODE=1 标识 Claude Code 环境（Shell.ts:319-321）。键名按 CC 真源用无下划线
        //       CLAUDECODE（Java 旧内联误用 CLAUDE_CODE，ShellExecutor 对齐修正）。
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "echo hi");
        ShellExecutor.applyExecEnv(pb, "/usr/bin/bash");
        assertThat(pb.environment())
            .containsEntry("SHELL", "/usr/bin/bash")
            .containsEntry("GIT_EDITOR", "true")
            .containsEntry("CLAUDECODE", "1");
    }

    // ════════════════════════════════════════════════════════════════
    // E5 readCwdTracked cwd 读回
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E5 readCwdTracked：非 Windows 原样返回（readFileSync().trim() 语义，Shell.ts:397-399）")
    void readCwdTracked_nonWindows_returnsTrimmedValue() throws Exception {
        Path f = tempDir.resolve("cwd-posix");
        Files.writeString(f, "  /home/user/proj\n");
        assertThat(ShellExecutor.readCwdTracked(f, false)).isEqualTo("/home/user/proj");
    }

    @Test
    @DisplayName("E5 readCwdTracked：Windows /c/ 盘符 POSIX → native C:\\（posixPathToWindowsPath）")
    void readCwdTracked_windowsDrivePosixToNative() throws Exception {
        // WHY: Git Bash pwd -P 输出 /c/...，setCwd 前必须转 native（Shell.ts:400-402），
        //       否则 Java 文件/权限链路拿到 POSIX 路径。
        Path f = tempDir.resolve("cwd-drive");
        Files.writeString(f, "/c/Users/foo");
        assertThat(ShellExecutor.readCwdTracked(f, true)).isEqualTo("C:\\Users\\foo");
    }

    @Test
    @DisplayName("E5 readCwdTracked：Windows /tmp 挂载点转 %TEMP%（MemoryFileDetection 增强）")
    void readCwdTracked_windowsTmpMountToNative() throws Exception {
        // WHY: MSYS 保留挂载点 /tmp → Windows %TEMP%（Git Bash /tmp = 用户 Temp），
        //       fallback 翻斜杠会误匹配 drive 模式得 \tmp（错误）。断言经 MemoryFileDetection 特判。
        Path f = tempDir.resolve("cwd-tmp");
        Files.writeString(f, "/tmp/xyz");
        assertThat(ShellExecutor.readCwdTracked(f, true))
            .isEqualTo(MemoryFileDetection.posixPathToWindowsPath("/tmp/xyz"));
    }

    // ════════════════════════════════════════════════════════════════
    // bash() 统一构造
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bash()：bash -c + env 注入 + directory（对齐 Shell.ts:316 spawn）")
    void bash_buildsProcessBuilderWithEnvAndDirectory() {
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        ProcessBuilder pb = ShellExecutor.bash("echo hi", tempDir.toString());
        List<String> cmd = pb.command();
        assertThat(cmd).as("spawn argv = [bashPath, -c, command]").hasSize(3);
        assertThat(cmd.get(1)).isEqualTo("-c");
        assertThat(cmd.get(2)).isEqualTo("echo hi");
        // 首参 = ShellResolver 探测出的实际 shell 路径（同一探测链）
        assertThat(cmd.get(0)).isEqualTo(ShellResolver.resolveShell());
        // env 注入（E3）
        assertThat(pb.environment())
            .containsEntry("SHELL", cmd.get(0))
            .containsEntry("GIT_EDITOR", "true")
            .containsEntry("CLAUDECODE", "1");
        // directory = 传入 cwd
        assertThat(pb.directory()).isEqualTo(tempDir.toFile());
    }

    @Test
    @DisplayName("bash()：blank cwd → 不设置 directory（后台路径回落 user.dir 由调用方处理）")
    void bash_blankCwd_doesNotSetDirectory() {
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        ProcessBuilder pb = ShellExecutor.bash("echo hi", "  ");
        assertThat(pb.directory()).as("blank cwd 不应设置 directory").isNull();
        assertThat(pb.command()).containsExactly(ShellResolver.resolveShell(), "-c", "echo hi");
    }

    // ════════════════════════════════════════════════════════════════
    // P2-9: 沙箱 TMPDIR 注入（CLAUDE_CODE_TMPDIR 同源沙箱 tmpdir）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("applySandboxExecEnv：TMPDIR 与 CLAUDE_CODE_TMPDIR 均指向沙箱 tmpdir（对齐 CC bashProvider.ts:241-243）")
    void applySandboxExecEnv_tmpdirAndClaudeCodeTmpdir_bothPointToSandboxTmpDir() {
        // WHY（P2-9）：CC 真源 bashProvider.ts:241-243 两个 env 都设 posixTmpDir（沙箱 tmpdir）。
        //   旧实现 CLAUDE_CODE_TMPDIR 注入 base（/tmp 或宿主 env）→ 沙箱命令读 CLAUDE_CODE_TMPDIR
        //   落到宿主 /tmp 而非沙箱可写目录，与 CC 语义漂移。本断言锁死两者同源。
        ProcessBuilder pb = new ProcessBuilder("true");
        ShellExecutor.applySandboxExecEnv(pb);

        String tmpdir = pb.environment().get("TMPDIR");
        String claudeCodeTmpdir = pb.environment().get("CLAUDE_CODE_TMPDIR");
        assertThat(tmpdir).as("TMPDIR 必须注入").isNotNull().isNotEmpty();
        assertThat(claudeCodeTmpdir).as("CLAUDE_CODE_TMPDIR 必须注入").isNotNull().isNotEmpty();
        assertThat(tmpdir).as("CLAUDE_CODE_TMPDIR 与 TMPDIR 同源（均沙箱 tmpdir，CC bashProvider.ts:241-243）")
            .isEqualTo(claudeCodeTmpdir);
        // 沙箱 tmpdir = base/claude-{username}（Shell.ts:203-207 + claudeTempDirName.ts 等价）
        assertThat(tmpdir).endsWith("claude-" + System.getProperty("user.name", "unknown"));
    }
}

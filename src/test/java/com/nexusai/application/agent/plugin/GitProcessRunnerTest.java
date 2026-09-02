package com.nexusai.application.agent.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL2] GitProcessRunner · 对齐 CC utils/execFileNoThrow.ts:89 execFileNoThrowWithCwd.
 *
 * <p>WHY（规则九）：CC 的 git 执行契约是 <b>args 数组</b>（非 sh -c 字符串）+ cwd + env +
 * timeout → destroyForcibly + 永不抛异常（execa reject:false）。旧 Java ExecSyncWrapper 用
 * {@code sh -c} 字符串（单引号/引号语义脆弱），DoctorController 用裸 ProcessBuilder 但无
 * timeout/超时 kill/错误字段。本测试锁定：args 数组透传、非零退出不抛、超时销毁且 error 含
 * timed out、Windows git 解析规避 .cmd/.bat。
 */
@DisplayName("[MPL2] GitProcessRunner（args 数组 + timeout + destroyForcibly）对齐 CC execFileNoThrowWithCwd")
class GitProcessRunnerTest {

    /** 记录型 executor · 断言 args 以数组原样透传（非 sh -c 拼接）。 */
    static final class RecordingExecutor implements GitProcessRunner.Executor {
        final List<List<String>> recorded = new ArrayList<>();
        GitProcessRunner.Result next = new GitProcessRunner.Result(0, "", "", null);

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, Map<String, String> env,
                                            long timeoutMs) {
            recorded.add(List.copyOf(args));
            return next;
        }
    }

    // 1. args 数组透传（含 git 可执行前缀）· CC execFileNoThrowWithCwd(file, args, ...)
    @Test
    void run_prependsGitExecutable_andPassesArgsAsArray() {
        RecordingExecutor ex = new RecordingExecutor();
        GitProcessRunner runner = new GitProcessRunner(ex, "C:/git/bin/git.exe");

        runner.run(List.of("clone", "--depth", "1", "https://example.com/r.git", "t"),
            "C:/work", Map.of("GIT_TERMINAL_PROMPT", "0"), 5000);

        assertThat(ex.recorded).hasSize(1);
        List<String> args = ex.recorded.get(0);
        // 契约：args 数组，第一个是 git 可执行，后续逐元素独立（绝不出现 "sh -c '...'" 单字符串）
        assertThat(args).containsExactly(
            "C:/git/bin/git.exe", "clone", "--depth", "1", "https://example.com/r.git", "t");
        assertThat(args.stream().anyMatch(a -> a.equals("-c") || a.equals("sh"))).isFalse();
    }

    // 2. 非零退出不抛 · CC reject:false（execFileNoThrow.ts:128）
    @Test
    void nonZeroExit_doesNotThrow_returnsResult() {
        GitProcessRunner runner = new GitProcessRunner();
        // git 未知命令 → exit 1，不应抛异常
        GitProcessRunner.Result r = runner.run(List.of("this-is-not-a-git-command"), null, null);
        assertThat(r.exitCode()).isNotZero();
        assertThat(r.stderr()).isNotNull();
    }

    // 3. 超时 → destroyForcibly + error 含 timed out（对齐 CC enhance :660/:910 error.includes('timed out')）
    @Test
    void timeout_forciblyDestroys_andErrorContainsTimedOut() {
        boolean win = File.separatorChar == '\\';
        // Windows: ping.exe（真实可执行，非 .cmd）阻塞 ~10s；unix: sleep
        List<String> args = win
            ? List.of("ping", "127.0.0.1", "-n", "10")
            : List.of("sleep", "10");

        GitProcessRunner.Result r =
            GitProcessRunner.ProcessExecutor.INSTANCE.exec(args, null, null, 1);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("timed out");
    }

    // 4. Windows git 解析规避 .cmd/.bat · CC gitExe = whichSync('git') || 'git'（git.ts:212-216）
    @Test
    void resolveGitExecutable_returnsRealExecutable_notCmd() {
        String exe = GitProcessRunner.resolveGitExecutable();
        assertThat(exe).isNotBlank();
        if (File.separatorChar == '\\') {
            assertThat(exe.toLowerCase()).doesNotEndWith(".cmd").doesNotEndWith(".bat");
        }
    }

    // 5. 默认 timeout 120s · CC DEFAULT_PLUGIN_GIT_TIMEOUT_MS = 120*1000（:515）
    @Test
    void defaultGitTimeout_is120s() {
        assertThat(GitProcessRunner.getPluginGitTimeoutMs()).isEqualTo(120_000L);
    }

    // 6. GIT_NO_PROMPT_ENV · CC :510-513
    @Test
    void gitNoPromptEnv_disablesPrompts() {
        assertThat(GitProcessRunner.gitNoPromptEnv())
            .containsEntry("GIT_TERMINAL_PROMPT", "0")
            .containsEntry("GIT_ASKPASS", "");
    }
}

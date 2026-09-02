package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShellResolver} 优先级链测试 · 对齐 CC {@code findSuitableShell}（Shell.ts:73-137）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：Bash 工具执行器的 shell 选择必须是 CC 同款
 * 优先级链（CLAUDE_CODE_SHELL → SHELL → Windows Git Bash → which → 常见路径 → 显式抛错），
 * 否则会出现本批次修复前的问题：Windows 上静默回退 cmd.exe，模型按 bash 语法写的命令报错。
 *
 * <p>测试全部经 {@link ShellResolver#doResolve} 注入 env/exists/windows（跨平台结果一致，
 * 语义同 CommandHookExecutor.resolveGitBashPath(envResolver, pathExists) 注入模式）。
 * 可执行 key 一律用 {@code File.getAbsolutePath()} 规范化（匹配 findExecutableOnPath 返回值）。
 */
@DisplayName("ShellResolver shell 探测优先级（对齐 CC findSuitableShell）")
class ShellResolverTest {

    private static Function<String, String> env(Map<String, String> m) {
        return k -> m.containsKey(k) ? m.get(k) : null;
    }

    private static Function<String, Boolean> exists(String... absPaths) {
        Set<String> set = new HashSet<>(Arrays.asList(absPaths));
        return set::contains;
    }

    /** 绝对路径规范化 · 匹配 findExecutableOnPath / getAbsolutePath 返回值。 */
    private static String abs(String p) {
        return new File(p).getAbsolutePath();
    }

    @Test
    @DisplayName("CC 优先级 1：CLAUDE_CODE_SHELL 覆盖最高（含 bash 且可执行）")
    void claudeCodeShell_override_takesPrecedence() {
        String shell = abs("/usr/local/bin/bash");
        Function<String, String> env = env(Map.of(
            "CLAUDE_CODE_SHELL", shell,
            "SHELL", abs("/usr/bin/zsh")));
        Function<String, Boolean> exists = exists(shell);
        assertThat(ShellResolver.resolveShell(env, exists, false)).isEqualTo(shell);
    }

    @Test
    @DisplayName("CC 优先级 1b：CLAUDE_CODE_SHELL 非 bash/zsh → 忽略回落 SHELL")
    void claudeCodeShell_ignored_whenNotBashZsh() {
        String bad = abs("/usr/bin/fish");
        String good = abs("/usr/bin/bash");
        Function<String, String> env = env(Map.of(
            "CLAUDE_CODE_SHELL", bad,
            "SHELL", good));
        Function<String, Boolean> exists = exists(bad, good);
        assertThat(ShellResolver.resolveShell(env, exists, false)).isEqualTo(good);
    }

    @Test
    @DisplayName("CC 优先级 1c：CLAUDE_CODE_SHELL 不可执行 → 忽略回落 SHELL")
    void claudeCodeShell_ignored_whenNotExecutable() {
        String shell = abs("/usr/local/bin/bash");
        String good = abs("/usr/bin/bash");
        Function<String, String> env = env(Map.of(
            "CLAUDE_CODE_SHELL", shell,
            "SHELL", good));
        Function<String, Boolean> exists = exists(good);
        assertThat(ShellResolver.resolveShell(env, exists, false)).isEqualTo(good);
    }

    @Test
    @DisplayName("CC 优先级 2+5：SHELL env 置顶优先于 which 结果")
    void shellEnv_hasPriority_overWhich() {
        String shellEnv = abs("/custom/bin/bash");
        String whichBash = abs("/usr/bin/bash");
        Function<String, String> env = env(Map.of(
            "SHELL", shellEnv,
            "PATH", "/usr/bin"));
        Function<String, Boolean> exists = exists(shellEnv, whichBash);
        assertThat(ShellResolver.resolveShell(env, exists, false)).isEqualTo(shellEnv);
    }

    @Test
    @DisplayName("CC 优先级 3：PATH 扫描 which bash 命中")
    void which_bash_found_viaPath() {
        String whichBash = abs("/usr/bin/bash");
        Function<String, String> env = env(Map.of("PATH", "/usr/bin"));
        Function<String, Boolean> exists = exists(whichBash);
        assertThat(ShellResolver.resolveShell(env, exists, false)).isEqualTo(whichBash);
    }

    @Test
    @DisplayName("CC 优先级 4：常见路径 fallback（/bin /usr/bin /usr/local/bin /opt/homebrew/bin）")
    void commonPath_fallback() {
        Function<String, String> env = env(Map.of());
        Function<String, Boolean> exists = exists("/usr/bin/bash");
        assertThat(ShellResolver.resolveShell(env, exists, false)).isEqualTo("/usr/bin/bash");
    }

    @Test
    @DisplayName("Windows：CLAUDE_CODE_GIT_BASH_PATH 直接命中（对齐 CommandHookExecutor:1449-1452）")
    void windows_gitBashEnvPath_precedence() {
        String gitBash = abs("C:\\Program Files\\Git\\bin\\bash.exe");
        Function<String, String> env = env(Map.of("CLAUDE_CODE_GIT_BASH_PATH", gitBash));
        Function<String, Boolean> exists = exists(gitBash);
        assertThat(ShellResolver.resolveShell(env, exists, true)).isEqualTo(gitBash);
    }

    @Test
    @DisplayName("Windows：PATH 中 git 推导 <gitRoot>/bin/bash.exe（对齐 CommandHookExecutor:1453-1459）")
    void windows_gitBash_derivedFromGit() {
        String gitCmd = abs("C:\\Program Files\\Git\\cmd");
        String gitExe = new File(gitCmd, "git.exe").getAbsolutePath();
        // derive：gitExe 的 parent(parent)=Git 根 → <root>/bin/bash.exe
        String gitRoot = new File(gitExe).getParentFile().getParentFile().getAbsolutePath();
        String bashExe = new File(gitRoot, "bin" + File.separator + "bash.exe").getAbsolutePath();
        Function<String, String> env = env(Map.of("PATH", gitCmd));
        Function<String, Boolean> exists = exists(gitExe, bashExe);
        assertThat(ShellResolver.resolveShell(env, exists, true)).isEqualTo(bashExe);
    }

    @Test
    @DisplayName("Windows：无 Git Bash → PATH 扫描 bash.exe")
    void windows_noGitBash_fallsThroughToWhichBash() {
        String gitBin = abs("C:\\Program Files\\Git\\bin");
        String bashExe = new File(gitBin, "bash.exe").getAbsolutePath();
        Function<String, String> env = env(Map.of("PATH", gitBin));
        Function<String, Boolean> exists = exists(bashExe);
        assertThat(ShellResolver.resolveShell(env, exists, true)).isEqualTo(bashExe);
    }

    @Test
    @DisplayName("CC 优先级 6：找不到 → 抛 CC 同款显式错误（fail-loud，非 cmd.exe 兜底）")
    void noShell_throwsCcError() {
        Function<String, String> env = env(Map.of());
        Function<String, Boolean> exists = exists();
        assertThatThrownBy(() -> ShellResolver.resolveShell(env, exists, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Claude CLI requires a Posix shell environment");
    }
}

package com.nexusai.application.terminal;

import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 默认 shell 探测 + 白名单校验单测（纯 JUnit，无 Spring）。
 *
 * <p><b>WHY（规则九）</b>：探测与白名单是终端功能的<b>安全边界</b> —— 探测错了表现为「用户点了没反应」，
 * 白名单错了表现为<b>后端以自身权限执行调用方给出的任意命令</b>。故这里钉死：
 * <ul>
 *   <li><b>按「文件真的存在」决策</b>，⛔ 不按 OS 名猜（{@link #windowsWithoutAnyExecutableFailsLoud}）；</li>
 *   <li><b>顺序</b>就是需求给的顺序（pwsh → powershell → cmd → Git Bash → WSL）；</li>
 *   <li><b>白名单外一律 400</b>（{@link #rejectsShellOutsideWhitelist}），不存在的路径也不放行。</li>
 * </ul>
 *
 * <p>全部通过构造注入的 {@code exists} 谓词与固定环境变量驱动 ⇒ 不依赖本机装了哪些 shell。
 */
class TerminalShellResolverTest {

    private static TerminalShellResolver windows(Set<String> existing, List<String> wslDistros) {
        return new TerminalShellResolver("Windows 11", existing::contains, Map.of("PATH", "C:\\Windows"), () -> wslDistros);
    }

    private static TerminalShellResolver unix(Set<String> existing, String shellEnv) {
        return new TerminalShellResolver("Linux", existing::contains, Map.of("SHELL", shellEnv), List::of);
    }

    @Test
    @DisplayName("Windows：装了 PowerShell 7 就用 pwsh.exe（最高优先级）")
    void windowsPrefersPwsh() {
        TerminalShellResolver resolver = windows(
            Set.of("pwsh.exe", "powershell.exe", "cmd.exe"), List.of());

        assertThat(resolver.resolveDefault()).isEqualTo("pwsh.exe");
    }

    @Test
    @DisplayName("Windows：没有 pwsh 时回落到 powershell.exe（不是直接跳到 cmd）")
    void windowsFallsBackToPowershell() {
        TerminalShellResolver resolver = windows(Set.of("powershell.exe", "cmd.exe"), List.of());

        assertThat(resolver.resolveDefault()).isEqualTo("powershell.exe");
    }

    @Test
    @DisplayName("Windows：只有 cmd.exe 时才用 cmd")
    void windowsFallsBackToCmd() {
        TerminalShellResolver resolver = windows(Set.of("cmd.exe"), List.of());

        assertThat(resolver.resolveDefault()).isEqualTo("cmd.exe");
    }

    @Test
    @DisplayName("Windows：只有 Git Bash 可用时选中 bash.exe（探测的是真实路径）")
    void windowsPicksGitBashWhenOnlyBashExists() {
        String bash = "C:\\Program Files\\Git\\bin\\bash.exe";
        TerminalShellResolver resolver = windows(Set.of(bash), List.of());

        assertThat(resolver.resolveDefault()).isEqualTo(bash);
        assertThat(resolver.commandFor(bash)).containsExactly(bash, "-i");
    }

    @Test
    @DisplayName("Windows：wsl.exe 存在且有发行版 ⇒ 进入候选白名单")
    void windowsIncludesWslWhenDistributionsExist() {
        TerminalShellResolver resolver = windows(Set.of("cmd.exe", TerminalShellResolver.WSL), List.of("Ubuntu"));

        assertThat(resolver.availableShells()).containsExactly("cmd.exe", TerminalShellResolver.WSL);
        assertThat(resolver.commandFor(TerminalShellResolver.WSL))
            .containsExactly(TerminalShellResolver.WSL);
    }

    @Test
    @DisplayName("Windows：wsl.exe 存在但无发行版 ⇒ 不纳入白名单（否则点了只报错）")
    void windowsExcludesWslWithoutDistributions() {
        TerminalShellResolver resolver = windows(Set.of("cmd.exe", TerminalShellResolver.WSL), List.of());

        assertThat(resolver.availableShells()).containsExactly("cmd.exe");
    }

    @Test
    @DisplayName("⛔ 按文件存在决策：Windows 上什么都不存在 ⇒ fail loud，而不是猜一个返回")
    void windowsWithoutAnyExecutableFailsLoud() {
        TerminalShellResolver resolver = windows(Set.of(), List.of());

        assertThatThrownBy(resolver::resolveDefault)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未探测到任何可用 shell");
    }

    @Test
    @DisplayName("Unix：优先使用 $SHELL（用户自定义的登录 shell）")
    void unixPrefersEnvShell() {
        TerminalShellResolver resolver = unix(Set.of("/usr/bin/fish", "/bin/zsh", "/bin/sh"), "/usr/bin/fish");

        assertThat(resolver.resolveDefault()).isEqualTo("/usr/bin/fish");
    }

    @Test
    @DisplayName("Unix：$SHELL 指向不存在的路径 ⇒ 回落到 /bin/zsh（不拿坏路径去起进程）")
    void unixFallsBackWhenEnvShellMissing() {
        TerminalShellResolver resolver = unix(Set.of("/bin/zsh", "/bin/sh"), "/usr/bin/fish");

        assertThat(resolver.resolveDefault()).isEqualTo("/bin/zsh");
    }

    @Test
    @DisplayName("Unix：依次回落 /bin/bash → /bin/sh")
    void unixFallsBackToBashThenSh() {
        assertThat(unix(Set.of("/bin/bash", "/bin/sh"), "").resolveDefault()).isEqualTo("/bin/bash");
        assertThat(unix(Set.of("/bin/sh"), "").resolveDefault()).isEqualTo("/bin/sh");
    }

    @Test
    @DisplayName("白名单校验：白名单外的任意串一律 400（含参数拼接形态）")
    void rejectsShellOutsideWhitelist() {
        TerminalShellResolver resolver = unix(Set.of("/bin/bash"), "/bin/bash");

        assertThatThrownBy(() -> resolver.validate("/bin/sh -c rm -rf /"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("不支持的终端 shell");
        assertThatThrownBy(() -> resolver.validate("/tmp/does-not-exist"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("白名单校验：Windows 下大小写不敏感，返回白名单里的规范值")
    void acceptsWhitelistedShellCaseInsensitivelyOnWindows() {
        TerminalShellResolver resolver = windows(Set.of("pwsh.exe", "cmd.exe"), List.of());

        assertThat(resolver.validate("PWSH.EXE")).isEqualTo("pwsh.exe");
    }

    @Test
    @DisplayName("白名单校验：null/空白 ⇒ 默认 shell（前端不传也用得上）")
    void blankFallsBackToDefault() {
        TerminalShellResolver resolver = unix(Set.of("/bin/bash"), "/bin/bash");

        assertThat(resolver.validate(null)).isEqualTo("/bin/bash");
        assertThat(resolver.validate("  ")).isEqualTo("/bin/bash");
    }

    @Test
    @DisplayName("下拉展示名：已知 shell 映射友好名，未知值回落原文件名（⛔ 不编造）")
    void labelsKnownShellsAndFallsBackToFileName() {
        // 友好名只用于展示；白名单原值（value）仍由 availableShells() 给出
        assertThat(TerminalShellResolver.labelFor("pwsh.exe")).isEqualTo("PowerShell 7");
        assertThat(TerminalShellResolver.labelFor("powershell.exe")).isEqualTo("Windows PowerShell");
        assertThat(TerminalShellResolver.labelFor("cmd.exe")).isEqualTo("Command Prompt");
        assertThat(TerminalShellResolver.labelFor("C:\\Program Files\\Git\\bin\\bash.exe")).isEqualTo("Git Bash");
        assertThat(TerminalShellResolver.labelFor("wsl.exe")).isEqualTo("WSL");
        // 未知值（Unix 绝对路径 / 非 Git 的 bash）⇒ 只给文件名，不猜成某个产品的名字
        assertThat(TerminalShellResolver.labelFor("/usr/bin/fish")).isEqualTo("fish");
        assertThat(TerminalShellResolver.labelFor("C:\\Other\\bash.exe")).isEqualTo("bash.exe");
    }

    @Test
    @DisplayName("wsl -l -q 输出按 UTF-16LE 解析；无发行版时的提示语被过滤")
    void parsesWslDistributionsUtf16() {
        byte[] utf16 = "docker-desktop\r\nUbuntu-22.04\r\n".getBytes(StandardCharsets.UTF_16LE);

        assertThat(TerminalShellResolver.parseWslDistributions(utf16))
            .containsExactly("docker-desktop", "Ubuntu-22.04");
        assertThat(TerminalShellResolver.parseWslDistributions(
            "适用于 Linux 的 Windows 子系统没有已安装的分发版。\r\n".getBytes(StandardCharsets.UTF_16LE)))
            .isEmpty();
    }

    @Test
    @DisplayName("wsl -l -q 输出按 UTF-8 也能解析（历史版本）")
    void parsesWslDistributionsUtf8() {
        byte[] utf8 = "Ubuntu\n".getBytes(StandardCharsets.UTF_8);

        assertThat(TerminalShellResolver.parseWslDistributions(utf8)).containsExactly("Ubuntu");
    }
}

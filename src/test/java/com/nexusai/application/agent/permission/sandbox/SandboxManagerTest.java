package com.nexusai.application.agent.permission.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IMP-5 · SandboxManager 三闸（isSandboxingEnabled）测试。
 *
 * <p>对齐 CC {@code sandbox-adapter.ts:532-547}：
 * {@code isSandboxingEnabled() = isSupportedPlatform && checkDependencies().errors 空 &&
 * isPlatformInEnabledList && getSandboxEnabledSetting}。Java 端三闸全部落到
 * {@link SandboxManager#isEnabled()}，本测试逐闸验证纯函数 + 组合语义。
 *
 * <p>WHY（测试验证意图）：沙箱 auto-allow 只有在<b>实际能够运行沙箱</b>时才能激活
 * （平台支持 + 依赖就绪 + 平台在白名单 + 用户开启）。若平台不支持/依赖缺失仍把命令当作
 * "沙箱内执行"而 auto-allow，则是 CC 明确要修的 fail-open 安全脚枪
 * （sandbox-adapter.ts:554-563 #34044 Fix）。
 */
@DisplayName("SandboxManager isEnabled 三闸（IMP-5）")
class SandboxManagerTest {

    // ──────────────────────────────────────────────
    // 1. 平台门（CC isSupportedPlatform —— macOS / Linux / WSL2+，WSL1 不支持）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("平台门: macOS 支持")
    void platform_macosSupported() {
        assertThat(SandboxManager.isSupportedPlatform("mac os x", null)).isTrue();
        assertThat(SandboxManager.isSupportedPlatform("darwin", null)).isTrue();
    }

    @Test
    @DisplayName("平台门: Windows 不支持")
    void platform_windowsNotSupported() {
        assertThat(SandboxManager.isSupportedPlatform("windows 11", null)).isFalse();
        assertThat(SandboxManager.isSupportedPlatform("win10", null)).isFalse();
    }

    @Test
    @DisplayName("平台门: 常规 Linux 支持")
    void platform_linuxSupported() {
        assertThat(SandboxManager.isSupportedPlatform("linux", "5.15.0-91-generic"))
            .isTrue();
    }

    @Test
    @DisplayName("平台门: WSL2 支持（/proc/version 含 WSL2 标记）")
    void platform_wsl2Supported() {
        assertThat(SandboxManager.isSupportedPlatform("linux",
            "5.15.153.1-microsoft-standard-WSL2")).isTrue();
    }

    @Test
    @DisplayName("平台门: WSL1 不支持（含 microsoft 但无 WSL 版本标记 → WSL1）")
    void platform_wsl1NotSupported() {
        // CC platform.ts getWslVersion：含 microsoft 无 WSL(\d) 标记 → 视为 WSL1
        assertThat(SandboxManager.isSupportedPlatform("linux",
            "4.4.0-19041-Microsoft")).isFalse();
    }

    @Test
    @DisplayName("平台门: 未知平台不支持")
    void platform_unknownNotSupported() {
        assertThat(SandboxManager.isSupportedPlatform("weirdos", null)).isFalse();
    }

    // ──────────────────────────────────────────────
    // 2. 依赖门（CC checkDependencies —— Linux/WSL 需 bwrap+socat；macOS 需 sandbox-exec）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("依赖门: Linux 依赖齐全（bwrap+socat）→ 通过")
    void deps_linuxAllPresent() {
        Predicate<String> exists = cmd -> cmd.equals("bwrap") || cmd.equals("socat");
        assertThat(SandboxManager.checkDependenciesFor("linux", exists)).isTrue();
    }

    @Test
    @DisplayName("依赖门: Linux 缺 bwrap → 不通过")
    void deps_linuxMissingBwrap() {
        Predicate<String> exists = cmd -> cmd.equals("socat");
        assertThat(SandboxManager.checkDependenciesFor("linux", exists)).isFalse();
    }

    @Test
    @DisplayName("依赖门: Linux 缺 socat → 不通过")
    void deps_linuxMissingSocat() {
        Predicate<String> exists = cmd -> cmd.equals("bwrap");
        assertThat(SandboxManager.checkDependenciesFor("linux", exists)).isFalse();
    }

    @Test
    @DisplayName("依赖门: macOS 需 sandbox-exec → 通过")
    void deps_macosSandboxExecPresent() {
        Predicate<String> exists = cmd -> cmd.equals("sandbox-exec");
        assertThat(SandboxManager.checkDependenciesFor("mac os x", exists)).isTrue();
    }

    @Test
    @DisplayName("依赖门: macOS 缺 sandbox-exec → 不通过")
    void deps_macosSandboxExecMissing() {
        assertThat(SandboxManager.checkDependenciesFor("mac os x", cmd -> false)).isFalse();
    }

    @Test
    @DisplayName("依赖门: Windows 恒不通过（平台门已挡，双保险）")
    void deps_windowsNotSupported() {
        assertThat(SandboxManager.checkDependenciesFor("windows 11", cmd -> true)).isFalse();
    }

    // ──────────────────────────────────────────────
    // 3. 白名单门（CC isPlatformInEnabledList —— settings.sandbox.enabledPlatforms）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("白名单门: 未配置（空）→ 允许全部平台（CC undefined → true）")
    void whitelist_emptyAllowsAll() {
        assertThat(SandboxManager.isPlatformInEnabledList(List.of(), "linux")).isTrue();
        assertThat(SandboxManager.isPlatformInEnabledList(null, "linux")).isTrue();
    }

    @Test
    @DisplayName("白名单门: 当前平台在白名单 → 通过")
    void whitelist_containsPlatform() {
        assertThat(SandboxManager.isPlatformInEnabledList(List.of("linux", "macos"), "linux"))
            .isTrue();
    }

    @Test
    @DisplayName("白名单门: 当前平台不在白名单 → 拒绝（NVIDIA 仅 macos 场景）")
    void whitelist_excludesPlatform() {
        assertThat(SandboxManager.isPlatformInEnabledList(List.of("macos"), "windows"))
            .isFalse();
    }

    // ──────────────────────────────────────────────
    // 4. 平台识别纯函数（CC platform.ts getPlatform）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("平台识别: darwin→macos / win→windows / linux→linux / WSL→wsl / 其他→unknown")
    void detectPlatformMapping() {
        assertThat(SandboxManager.detectPlatform("mac os x", null)).isEqualTo("macos");
        assertThat(SandboxManager.detectPlatform("windows 11", null)).isEqualTo("windows");
        assertThat(SandboxManager.detectPlatform("linux", "5.15.0-91-generic")).isEqualTo("linux");
        assertThat(SandboxManager.detectPlatform("linux", "5.15.0-microsoft-standard-WSL2"))
            .isEqualTo("wsl");
        assertThat(SandboxManager.detectPlatform("weirdos", null)).isEqualTo("unknown");
    }

    // ──────────────────────────────────────────────
    // 5. isEnabled() 四门组合（探针注入全参构造器）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("组合: 四门全过 → true")
    void isEnabled_allGatesPass() {
        SandboxManager sm = new SandboxManager(true, true, true, List.of(),
            () -> true, () -> true);
        assertThat(sm.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("组合: 平台不支持 → false（fail-closed）")
    void isEnabled_platformUnsupported() {
        SandboxManager sm = new SandboxManager(true, true, true, List.of(),
            () -> false, () -> true);
        assertThat(sm.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("组合: 依赖缺失 → false（fail-closed）")
    void isEnabled_dependenciesMissing() {
        SandboxManager sm = new SandboxManager(true, true, true, List.of(),
            () -> true, () -> false);
        assertThat(sm.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("组合: 平台不在白名单 → false（fail-closed）")
    void isEnabled_platformNotInWhitelist() {
        // 白名单排除当前实际平台 → isPlatformInEnabledList 返回 false
        String cur = SandboxManager.currentPlatform();
        List<String> whitelist = List.of("linux", "macos", "windows", "wsl", "unknown")
            .stream().filter(p -> !p.equals(cur)).toList();
        assertThat(whitelist).isNotEmpty();
        SandboxManager sm = new SandboxManager(true, true, true, whitelist,
            () -> true, () -> true);
        assertThat(sm.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("组合: 用户设置关闭 → false（getSandboxEnabledSetting）")
    void isEnabled_settingDisabled() {
        SandboxManager sm = new SandboxManager(false, true, true, List.of(),
            () -> true, () -> true);
        assertThat(sm.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("组合: shouldUseSandbox 复用 isEnabled 三闸（平台不支持 → false）")
    void shouldUseSandbox_platformUnsupported_false() {
        SandboxManager sm = new SandboxManager(true, true, true, List.of(),
            () -> false, () -> true);
        // CC shouldUseSandbox.ts:131-133 —— 先查 isSandboxingEnabled()，三闸不过即 false
        assertThat(sm.shouldUseSandbox("Bash",
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode().put("command", "ls"))).isFalse();
    }
}

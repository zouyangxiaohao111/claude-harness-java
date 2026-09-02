package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BypassPermissionsKillswitch 测试 · 对齐 CC {@code bypassPermissionsKillswitch.ts:17-55} +
 * {@code permissionSetup.ts:1265-1406}。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：钉死「bypass 禁用门」—— RV-11 核心缺口：Java 端
 * {@code checkAndDisableBypass / shouldDisableBypass / createDisabledBypass / isBypassPermissionsModeDisabled}
 * 全仓 0 命中，且 {@code isBypassPermissionsModeAvailable} 硬编码 true。每条用例锁定一个 CC 实际 TS 行为。
 */
class BypassPermissionsKillswitchTest {

    private static ToolPermissionContext bypassCtx(PermissionMode mode) {
        return ToolPermissionContext.of(mode, Map.of(), Map.of(), Map.of(), Map.of());
    }

    // ── isBypassPermissionsModeDisabled（同步，CC permissionSetup.ts:1371-1384） ──

    @Test
    @DisplayName("Statsig cached 门开 → 禁用（CC :1376-1379）")
    void isDisabledWhenStatsigGateOn() {
        assertThat(BypassPermissionsKillswitch.isBypassPermissionsModeDisabled(() -> true, false))
            .isTrue();
    }

    @Test
    @DisplayName("settings.disableBypassPermissionsMode='disable' → 禁用（CC :1377-1380）")
    void isDisabledWhenSettingsDisable() {
        assertThat(BypassPermissionsKillswitch.isBypassPermissionsModeDisabled(() -> false, true))
            .isTrue();
    }

    @Test
    @DisplayName("双源都关 → 不禁用")
    void notDisabledWhenBothOff() {
        assertThat(BypassPermissionsKillswitch.isBypassPermissionsModeDisabled(null, false))
            .isFalse();
    }

    // ── createDisabledBypassPermissionsContext（CC permissionSetup.ts:1389-1406） ──

    @Test
    @DisplayName("mode=bypass → 降级 default + isBypassPermissionsModeAvailable=false（CC :1393-1405）")
    void createDisabledDowngradesBypassModeToDefault() {
        ToolPermissionContext out = BypassPermissionsKillswitch
            .createDisabledBypassPermissionsContext(bypassCtx(PermissionMode.BYPASS_PERMISSIONS));
        assertThat(out.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(out.isBypassPermissionsModeAvailable()).isFalse();
    }

    @Test
    @DisplayName("mode=default（非 bypass）→ 保持 default，仅关闭 bypass 可用性（CC :1393 短路）")
    void createDisabledKeepsNonBypassMode() {
        ToolPermissionContext out = BypassPermissionsKillswitch
            .createDisabledBypassPermissionsContext(bypassCtx(PermissionMode.DEFAULT));
        assertThat(out.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(out.isBypassPermissionsModeAvailable()).isFalse();
    }

    // ── shouldDisableBypassPermissions（异步门，CC permissionSetup.ts:1265-1267） ──

    @Test
    @DisplayName("securityRestrictionGate true → 应禁用；null → 不禁用")
    void shouldDisableBypassMirrorsGate() {
        assertThat(BypassPermissionsKillswitch.shouldDisableBypassPermissions(() -> true)).isTrue();
        assertThat(BypassPermissionsKillswitch.shouldDisableBypassPermissions(null)).isFalse();
    }

    // ── checkAndDisableBypassPermissionsIfNeeded（run-once，CC bypassPermissionsKillswitch.ts:17-41） ──

    @Test
    @DisplayName("run-once：首次禁用 → 二次调用 no-op；reset 后重跑（CC :17-25 + :53）")
    void runOnceFlagShortCircuitsAndResets() {
        BypassPermissionsKillswitch ks = new BypassPermissionsKillswitch();
        ToolPermissionContext ctx = bypassCtx(PermissionMode.BYPASS_PERMISSIONS);

        ToolPermissionContext first = ks.checkAndDisableBypassPermissionsIfNeeded(ctx, () -> true);
        assertThat(first.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(first.isBypassPermissionsModeAvailable()).isFalse();

        // run-once：第二次调用不应再触发降级（返回原引用）
        ToolPermissionContext second = ks.checkAndDisableBypassPermissionsIfNeeded(ctx, () -> true);
        assertThat(second).isSameAs(ctx);

        // reset 后应重跑
        ks.resetBypassPermissionsCheck();
        ToolPermissionContext third = ks.checkAndDisableBypassPermissionsIfNeeded(ctx, () -> true);
        assertThat(third.mode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("isBypassPermissionsModeAvailable=false → 短路不检查门（CC :27-29）")
    void shortCircuitsWhenBypassUnavailable() {
        BypassPermissionsKillswitch ks = new BypassPermissionsKillswitch();
        ToolPermissionContext strict = ToolPermissionContext.strict(PermissionMode.DEFAULT);
        ToolPermissionContext out = ks.checkAndDisableBypassPermissionsIfNeeded(strict, () -> true);
        assertThat(out).isSameAs(strict);
    }

    @Test
    @DisplayName("门未开 → 原样返回（CC :31-33）")
    void noOpWhenGateOff() {
        BypassPermissionsKillswitch ks = new BypassPermissionsKillswitch();
        ToolPermissionContext ctx = bypassCtx(PermissionMode.BYPASS_PERMISSIONS);
        ToolPermissionContext out = ks.checkAndDisableBypassPermissionsIfNeeded(ctx, () -> false);
        assertThat(out).isSameAs(ctx);
    }
}

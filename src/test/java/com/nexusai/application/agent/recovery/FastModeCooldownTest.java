package com.nexusai.application.agent.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FastModeRuntimeState 测试 · 对齐 CC fastMode.ts:183-317（cooldown + org 级禁用）。
 *
 * <p><b>WHY (意图验证, 规则九)</b>: fast-mode fallback 依赖 cooldown 状态机——429/529 长
 * retry-after 后触发冷却（withRetry.ts:291-304），下轮 wasFastModeActive=false 切标准速度；
 * handleFastModeRejectedByAPI / overage 拒绝需永久禁用 fast mode（fastMode.ts:244-255/:295-313）。
 * 状态机偏差会导致 fast-mode 抖动（频繁开关）或永不退出 fast mode（持续打满缓存）。
 *
 * <p><b>RED teeth</b>: 若 triggerFastModeCooldown 不置 cooldown / 过期不复位 / 拒绝不置
 * orgDisabled / out-of-credits 也禁用 → 本测试必须 fail。
 *
 * <p><b>F3 恒关（2026-08-22）</b>: 用户拍板 fast mode 恒关（非 Anthropic 无 fast-mode 服务端），
 * isFastModeEnabled() 恒 false → triggerFastModeCooldown 经 CC:218-220 守卫恒 no-op（cooldown 永不置位）。
 * org 级拒绝/overage 状态机（handleFastModeRejectedByAPI / handleFastModeOverageRejection）保留为
 * CC 镜像（fastMode.ts:244-313），直接调用仍按 CC 语义验证。
 */
class FastModeCooldownTest {

    @BeforeEach
    void resetState() {
        FastModeRuntimeState.reset();
        FastModeRuntimeState.ENV_READER = name -> null; // 未设 NEXUSAI_DISABLE_FAST_MODE → fast mode 启用
    }

    @AfterEach
    void restore() {
        FastModeRuntimeState.reset();
        FastModeRuntimeState.ENV_READER = System::getenv;
    }

    @Test
    @DisplayName("初始 active，无冷却 / 未被拒绝（CC fastMode.ts:187 runtimeState 初始 active）")
    void initiallyActive() {
        assertThat(FastModeRuntimeState.isFastModeCooldown()).isFalse();
        assertThat(FastModeRuntimeState.isOrgDisabled()).isFalse();
        assertThat(FastModeRuntimeState.isFastModeActive()).isTrue();
    }

    @Test
    @DisplayName("F3 恒关：fast mode 恒关 → trigger 冷却经 isFastModeEnabled 守卫 no-op（cooldown 永不置位）")
    void triggerCooldownNoopWhenFastModeAlwaysOff() {
        // 用户拍板恒关（非 Anthropic 无 fast-mode 服务端）：isFastModeEnabled() 恒 false，
        // triggerFastModeCooldown 的 CC:218-220 守卫恒拦截 → cooldown 永不置位。
        FastModeRuntimeState.triggerFastModeCooldown(
            System.currentTimeMillis() + 60_000, FastModeRuntimeState.CooldownReason.RATE_LIMIT);
        assertThat(FastModeRuntimeState.isFastModeCooldown()).isFalse();
        assertThat(FastModeRuntimeState.isFastModeActive()).isTrue();
    }

    @Test
    @DisplayName("过期冷却自动回 active（CC:199-212 getFastModeRuntimeState 到期复位）")
    void expiredCooldownResetsToActive() {
        FastModeRuntimeState.triggerFastModeCooldown(
            System.currentTimeMillis() - 1000, FastModeRuntimeState.CooldownReason.OVERLOADED);
        assertThat(FastModeRuntimeState.isFastModeCooldown()).isFalse();
        assertThat(FastModeRuntimeState.isFastModeActive()).isTrue();
    }

    @Test
    @DisplayName("clearFastModeCooldown → active（CC:235-237）")
    void clearCooldownResets() {
        FastModeRuntimeState.triggerFastModeCooldown(
            System.currentTimeMillis() + 60_000, FastModeRuntimeState.CooldownReason.RATE_LIMIT);
        FastModeRuntimeState.clearFastModeCooldown();
        assertThat(FastModeRuntimeState.isFastModeCooldown()).isFalse();
    }

    @Test
    @DisplayName("fast mode 被 env 禁用 → trigger 冷却 no-op（CC:218-220 isFastModeEnabled 守卫）")
    void triggerNoopWhenFastModeDisabled() {
        FastModeRuntimeState.ENV_READER =
            name -> "NEXUSAI_DISABLE_FAST_MODE".equals(name) ? "true" : null;
        FastModeRuntimeState.triggerFastModeCooldown(
            System.currentTimeMillis() + 60_000, FastModeRuntimeState.CooldownReason.RATE_LIMIT);
        assertThat(FastModeRuntimeState.isFastModeCooldown()).isFalse();
    }

    @Test
    @DisplayName("handleFastModeRejectedByAPI → orgDisabled true + 幂等（CC:244-255）")
    void rejectedByApiDisablesFastMode() {
        FastModeRuntimeState.handleFastModeRejectedByAPI();
        assertThat(FastModeRuntimeState.isOrgDisabled()).isTrue();
        assertThat(FastModeRuntimeState.isFastModeActive()).isFalse();
        // 幂等：orgStatus 已 disabled → 再次调用不改变
        FastModeRuntimeState.handleFastModeRejectedByAPI();
        assertThat(FastModeRuntimeState.isOrgDisabled()).isTrue();
    }

    @Test
    @DisplayName("overage 拒绝（非 out-of-credits）→ orgDisabled true + 消息映射（CC:295-313 + :263-284）")
    void overageRejectionDisablesFastMode() {
        FastModeRuntimeState.handleFastModeOverageRejection("org_level_disabled");
        assertThat(FastModeRuntimeState.isOrgDisabled()).isTrue();
        assertThat(FastModeRuntimeState.getOverageDisabledMessage("org_level_disabled"))
            .contains("disabled by your organization");
        assertThat(FastModeRuntimeState.getOverageDisabledMessage(null))
            .contains("extra usage not available");
        assertThat(FastModeRuntimeState.getOverageDisabledMessage("out_of_credits"))
            .contains("credits exhausted");
    }

    @Test
    @DisplayName("overage 拒绝（out_of_credits）→ 不永久禁用（CC:286-288 isOutOfCreditsReason）")
    void outOfCreditsKeepsFastMode() {
        FastModeRuntimeState.handleFastModeOverageRejection("out_of_credits");
        assertThat(FastModeRuntimeState.isOrgDisabled()).isFalse();
        assertThat(FastModeRuntimeState.isFastModeActive()).isTrue();
    }
}

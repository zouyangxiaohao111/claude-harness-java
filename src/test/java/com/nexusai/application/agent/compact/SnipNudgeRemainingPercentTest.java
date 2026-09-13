package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnipCompactor.resolveSnipNudgeRemainingPercent 测试（snip-nudge-percent 2026-09-13）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：nudge 判据由「消息条数」改为「上下文剩余百分比」后，阈值语义
 * 从「攒够 N 条消息」变为「上下文只剩 N% 才提示」。DB settings.snip_nudge_threshold 承载该值，
 * 必须与设置页写入侧校验范围（1..100）一致；越界必须 fail-loud 回落默认而非静默取用。
 */
class SnipNudgeRemainingPercentTest {

    @Test
    @DisplayName("DB 值在 1..100 内 → 直接取用（用户显式配置优先）")
    void dbValueInRange_used() {
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(30)).isEqualTo(30);
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(1)).isEqualTo(1);
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(100)).isEqualTo(100);
    }

    @Test
    @DisplayName("DB 值 0 → 按「未配置」处理 → 回落默认 30（旧语义 ≤0 = 未配置，不得静默翻转为关闭提示）")
    void dbValueZero_treatedAsUnconfigured() {
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(0)).isEqualTo(30);
    }

    @Test
    @DisplayName("DB 值为 null（未配置）→ 回落默认 30")
    void dbValueNull_fallsBackToDefault() {
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(null))
            .isEqualTo(SnipCompactor.SNIP_NUDGE_DEFAULT_REMAINING_PERCENT)
            .isEqualTo(30);
    }

    @Test
    @DisplayName("DB 值越界（<1 / >100）→ 回落默认 30（fail-loud，不静默取用）")
    void dbValueOutOfRange_fallsBackToDefault() {
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(-1)).isEqualTo(30);
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(101)).isEqualTo(30);
        assertThat(SnipCompactor.resolveSnipNudgeRemainingPercent(900))
            .as("旧「消息数」语义的存量值 900 越界 → 必须回落默认，不得当作 900% 使用")
            .isEqualTo(30);
    }
}

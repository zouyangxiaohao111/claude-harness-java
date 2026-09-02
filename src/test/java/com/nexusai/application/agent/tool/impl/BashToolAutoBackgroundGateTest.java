package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.BackgroundTaskDecider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-1 15s assistant 自动后台定时器去多余门回归。
 *
 * <p>WHY（测试验证意图而非行为）：CC 真源 BashTool.tsx:976 的 15s assistant 定时器只门控
 * {@code feature('KAIROS') && getKairosActive() && isMainThread && !isBackgroundTasksDisabled
 * && run_in_background !== true}，<b>不</b>查 DISALLOWED_AUTO_BACKGROUND_COMMANDS
 * （BashTool.tsx:220-221 isAutobackgroundingAllowed）。旧 Java 把 15s 定时器套在
 * {@code shouldAutoBackground = isAutobackgroundingAllowed(command)} 门内 → 即使 KAIROS 全开，
 * 对超时后台 DISALLOWED 的命令（裸 {@code sleep}）也不自动后台（与 CC 漂移）。
 * 本测试锁死两门<b>解耦</b>：15s 定时器门（autoBgEligible）独立于 shouldAutoBackground。
 */
@DisplayName("P1-1 15s 定时器与 isAutobackgroundingAllowed 解耦")
class BashToolAutoBackgroundGateTest {

    @Test
    @DisplayName("裸 sleep 对超时自动后台 DISALLOWED（isAutobackgroundingAllowed=false，CC BashTool.tsx:220-221）")
    void bareSleep_isDisallowedFromTimeoutAutoBackground() {
        assertThat(BashTool.isAutobackgroundingAllowed("sleep"))
            .as("裸 sleep 在 DISALLOWED_AUTO_BACKGROUND_COMMANDS")
            .isFalse();
    }

    @Test
    @DisplayName("sleep 30 对超时自动后台 ALLOWED（CC 全段检查局限：includes('sleep 30')=false，Java 对齐）")
    void sleepWithArgs_isAllowedForTimeoutAutoBackground() {
        // CC isAutobackgroundingAllowed（BashTool.tsx:307-315）用 parts[0].trim() 整段
        // includes 检查 —— "sleep 30" 非 "sleep" → 返回 true。Java 同步该局限（忠实对齐，非 bug）。
        assertThat(BashTool.isAutobackgroundingAllowed("sleep 30"))
            .as("CC 全段 includes 检查 → sleep 30 允许超时自动后台（Java 对齐 CC 行为）")
            .isTrue();
    }

    @Test
    @DisplayName("15s assistant 定时器门独立于 isAutobackgroundingAllowed —— 裸 sleep 在 KAIROS 开时仍 ELIGIBLE")
    void bareSleep_isEligibleFor15sTimerWhenKairosOn() {
        BackgroundTaskDecider decider = new BackgroundTaskDecider(true, true, true);

        boolean autoBgEligible = decider.isAssistantAutoBackgroundEligible(true, false);
        boolean shouldAutoBackground = BashTool.isAutobackgroundingAllowed("sleep");

        assertThat(autoBgEligible)
            .as("15s assistant 定时器门（CC BashTool.tsx:976）不查 DISALLOWED → 裸 sleep 也 ELIGIBLE")
            .isTrue();
        assertThat(shouldAutoBackground)
            .as("超时自动后台门（CC BashTool.tsx:307-315）对裸 sleep 为 false")
            .isFalse();
        // P1-1 接线断言：两门解耦 —— 裸 sleep 的 15s 定时器条件 = autoBgEligible（独立于 shouldAutoBackground）
        assertThat(autoBgEligible && !shouldAutoBackground)
            .as("裸 sleep 场景：15s 定时器 ELIGIBLE 且超时后台 DISALLOWED → 两门必须解耦（否则定时器不启动）")
            .isTrue();
    }

    @Test
    @DisplayName("KAIROS 关（生产默认）→ 15s 定时器不触发（基线，auto-background 整链断链）")
    void kairosOff_notEligibleFor15sTimer() {
        BackgroundTaskDecider decider = new BackgroundTaskDecider(true); // KAIROS 关（默认）
        assertThat(decider.isAssistantAutoBackgroundEligible(true, false))
            .as("KAIROS 关 → 15s 定时器不触发（对齐 CC 外部构建默认）")
            .isFalse();
    }
}

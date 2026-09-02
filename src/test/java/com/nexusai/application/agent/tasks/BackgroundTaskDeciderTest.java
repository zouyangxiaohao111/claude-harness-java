package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1-3 assistant 主线程 15s 自动后台化判定 · 对齐 CC BashTool.tsx:976-982。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>：CC assistant 模式（KAIROS）下主 agent 应保持响应，阻塞命令
 * 跑满 ASSISTANT_BLOCKING_BUDGET_MS（15s）自动转后台（不杀进程）。判定条件 = {@code feature('KAIROS')
 * && getKairosActive() && isMainThread && !isBackgroundTasksDisabled && run_in_background !== true}
 * （BashTool.tsx:976）。任一条件不满足则不应触发自动后台化：
 * <ul>
 *   <li>KAIROS 关（外部构建默认）→ 恒不触发（auto-background 整链断链，对齐 CC 发行默认）</li>
 *   <li>getKairosActive()=false（非 assistant 模式）→ 不触发</li>
 *   <li>非主线程（subagent/异步）→ 不触发</li>
 *   <li>后台任务禁用（CLAUDE_CODE_DISABLE_BACKGROUND_TASKS）→ 不触发</li>
 *   <li>run_in_background=true（显式请求）→ 不自动后台（走显式路径）</li>
 * </ul>
 */
@DisplayName("[G1-3] BackgroundTaskDecider.isAssistantAutoBackgroundEligible（CC BashTool.tsx:976 条件）")
class BackgroundTaskDeciderTest {

    @Test
    @DisplayName("全条件满足 → true；任一条件不满足 → false（CC && 条件矩阵）")
    void isAssistantAutoBackgroundEligible_matchesCCAndCondition() {
        // 全开：后台任务启用 + KAIROS 编译门 + KAIROS 运行时激活
        BackgroundTaskDecider decider = new BackgroundTaskDecider(true, true, true);

        assertThat(decider.isAssistantAutoBackgroundEligible(true, false))
            .as("主线程 + 非显式后台 → 可自动后台化（CC BashTool.tsx:976 全条件满足）")
            .isTrue();
        assertThat(decider.isAssistantAutoBackgroundEligible(false, false))
            .as("非主线程 → 不触发（:976 isMainThread）")
            .isFalse();
        assertThat(decider.isAssistantAutoBackgroundEligible(true, true))
            .as("run_in_background=true → 不自动后台（走显式路径 :989-995）")
            .isFalse();
    }

    @Test
    @DisplayName("KAIROS 关（外部构建默认构造）→ 恒 false（对齐 CC 发行默认 auto-background 断链）")
    void defaultConstructor_kairosOff_neverEligible() {
        // 仅传 backgroundTasksEnabled（KAIROS 编译门 + 运行时均默认 false）
        BackgroundTaskDecider ext = new BackgroundTaskDecider(true);
        assertThat(ext.isAssistantAutoBackgroundEligible(true, false))
            .as("KAIROS 关 → 即使主线程 + 未显式后台也不自动后台化（对齐 CC 外部构建）")
            .isFalse();
    }

    @Test
    @DisplayName("后台任务禁用 → false（!isBackgroundTasksDisabled 不满足）")
    void backgroundTasksDisabled_notEligible() {
        BackgroundTaskDecider disabled = new BackgroundTaskDecider(false, true, true);
        assertThat(disabled.isAssistantAutoBackgroundEligible(true, false))
            .as("后台任务禁用（CLAUDE_CODE_DISABLE_BACKGROUND_TASKS）→ 不自动后台化")
            .isFalse();
    }

    @Test
    @DisplayName("ASSISTANT_BLOCKING_BUDGET_MS = 15000（对齐 CC BashTool.tsx:57）")
    void assistantBlockingBudgetMs_matchesCC() {
        assertThat(BackgroundTaskDecider.ASSISTANT_BLOCKING_BUDGET_MS)
            .as("CC ASSISTANT_BLOCKING_BUDGET_MS = 15_000（BashTool.tsx:57）")
            .isEqualTo(15_000L);
    }
}

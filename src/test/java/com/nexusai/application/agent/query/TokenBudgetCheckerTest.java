package com.nexusai.application.agent.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ER-IMP-2026-04 P-34] TokenBudgetChecker.checkTokenBudget 直接单测 · 对齐 CC
 * query/tokenBudget.ts:45-93（真源逐行复核，非计划转述）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: checkTokenBudget 是 token-budget 门控的
 * 核心纯函数——agentId/budget 门、90% 阈值、diminishing-returns 判定、tracker 原地 mutate、
 * durationMs 时钟。任何一处回归都会让主循环 90% 阈值/diminishing 判定漂移（V-TOK 系列
 * 修复的回归护栏）。RED tooth：改错阈值或判定顺序 → 本测试 fail。
 *
 * <p>用例映射 CC 真源：
 * <ul>
 *   <li>{@code agentId || budget === null || budget <= 0 → stop(null)}（tokenBudget.ts:51-53）</li>
 *   <li>{@code pct = Math.round((turnTokens / budget) * 100)}（:56）</li>
 *   <li>{@code !isDiminishing && turnTokens < budget*0.9 → continue + mutate}（:59-76）</li>
 *   <li>{@code isDiminishing || continuationCount > 0 → stop + completionEvent}（:78-90）</li>
 * </ul>
 */
class TokenBudgetCheckerTest {

    private final TokenBudgetChecker checker = new TokenBudgetChecker();

    // ── 门控：agentId / budget null / 0 / 负 → stop(null)（CC tokenBudget.ts:51-53）──

    @Test
    @DisplayName("agentId 非空 → stop(null)（subagent 不参与预算门控 · CC tokenBudget.ts:51 if(agentId)）")
    void agentIdNonNull_stopsWithNullEvent() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        TokenBudgetChecker.TokenBudgetDecision decision =
            checker.checkTokenBudget(tracker, "subagent-1", 200_000, 10_000);

        assertThat(decision).isInstanceOf(TokenBudgetChecker.StopDecision.class);
        assertThat(((TokenBudgetChecker.StopDecision) decision).completionEvent())
            .as("agentId 非空 → completionEvent=null（no-op）")
            .isNull();
    }

    @Test
    @DisplayName("budget null → stop(null)（无预算目标 · CC tokenBudget.ts:51 budget===null）")
    void budgetNull_stopsWithNullEvent() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        TokenBudgetChecker.TokenBudgetDecision decision =
            checker.checkTokenBudget(tracker, null, null, 10_000);

        assertThat(decision).isInstanceOf(TokenBudgetChecker.StopDecision.class);
        assertThat(((TokenBudgetChecker.StopDecision) decision).completionEvent()).isNull();
    }

    @Test
    @DisplayName("budget 0 / 负 → stop(null)（CC tokenBudget.ts:51 budget<=0）")
    void budgetZeroOrNegative_stopsWithNullEvent() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        assertThat(((TokenBudgetChecker.StopDecision) checker.checkTokenBudget(tracker, null, 0, 10_000)).completionEvent())
            .as("budget=0 → stop(null)")
            .isNull();
        assertThat(((TokenBudgetChecker.StopDecision) checker.checkTokenBudget(tracker, null, -1, 10_000)).completionEvent())
            .as("budget=-1 → stop(null)")
            .isNull();
    }

    // ── continue 分支（<90% 且非 diminishing）──

    @Test
    @DisplayName("turnTokens < 90% budget → continue + nudgeMessage + tracker 原地 mutate（CC tokenBudget.ts:64-76）")
    void belowThreshold_continuesAndMutatesTracker() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        TokenBudgetChecker.TokenBudgetDecision decision =
            checker.checkTokenBudget(tracker, null, 200_000, 10_000);

        assertThat(decision).isInstanceOf(TokenBudgetChecker.ContinueDecision.class);
        TokenBudgetChecker.ContinueDecision cont = (TokenBudgetChecker.ContinueDecision) decision;
        assertThat(cont.action()).isEqualTo("continue");
        assertThat(cont.pct()).as("10_000/200_000 = 5%").isEqualTo(5);
        assertThat(cont.turnTokens()).isEqualTo(10_000);
        assertThat(cont.budget()).isEqualTo(200_000);
        assertThat(cont.nudgeMessage())
            .as("nudge 含 pct/turn/budget（getBudgetContinuationMessage 单源）")
            .contains("Stopped at 5% of token target (10,000 / 200,000)");
        assertThat(tracker.continuationCount()).as("tracker.continuationCount 原地 +1").isEqualTo(1);
        assertThat(tracker.lastDeltaTokens()).isEqualTo(10_000);
        assertThat(tracker.lastGlobalTurnTokens()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("pct 取整：149_999/200_000 = 74.9995% → Math.round = 75（CC tokenBudget.ts:56）")
    void pct_roundsToNearestInt() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        TokenBudgetChecker.ContinueDecision cont =
            (TokenBudgetChecker.ContinueDecision) checker.checkTokenBudget(tracker, null, 200_000, 149_999);
        assertThat(cont.pct()).as("Math.round(74.9995) = 75").isEqualTo(75);
    }

    // ── 90% 边界（CC tokenBudget.ts:64 turnTokens < budget*0.9 严格小于）──

    @Test
    @DisplayName("=90% 边界（turnTokens == budget*0.9）→ 不 continue；全新 tracker → stop(null)（CC :64 严格 <）")
    void exactly90Percent_notContinueFreshTrackerStopNull() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        TokenBudgetChecker.TokenBudgetDecision decision =
            checker.checkTokenBudget(tracker, null, 200_000, 180_000);

        assertThat(decision).isInstanceOf(TokenBudgetChecker.StopDecision.class);
        assertThat(((TokenBudgetChecker.StopDecision) decision).completionEvent())
            .as("全新 tracker（continuationCount=0）→ stop(null)（CC :78 条件不满足 → :92 兜底）")
            .isNull();
        assertThat(tracker.continuationCount()).as("stop 不 mutate tracker").isZero();
    }

    @Test
    @DisplayName("90%+ 且 continuationCount>0（此前 continue 过）→ stop + completionEvent（CC :78-90）")
    void overThresholdAfterContinuations_stopsWithEvent() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        checker.checkTokenBudget(tracker, null, 200_000, 10_000); // continue（cc=1）
        checker.checkTokenBudget(tracker, null, 200_000, 20_000); // continue（cc=2）

        TokenBudgetChecker.StopDecision stop =
            (TokenBudgetChecker.StopDecision) checker.checkTokenBudget(tracker, null, 200_000, 190_000);
        TokenBudgetChecker.CompletionEvent event = stop.completionEvent();
        assertThat(event).as("continuationCount>0 → stop 带 completionEvent").isNotNull();
        assertThat(event.continuationCount()).isEqualTo(2);
        assertThat(event.pct()).as("190_000/200_000 = 95%").isEqualTo(95);
        assertThat(event.diminishingReturns()).as("delta=170_000 非小增量 → 非 diminishing").isFalse();
    }

    // ── diminishing returns（连续 3 步小增量，CC tokenBudget.ts:59-62）──

    @Test
    @DisplayName("diminishing：3 连增量 <500 后第 4 步 → stop + completionEvent.diminishingReturns=true（CC :59-62/:78-90）")
    void diminishingReturns_stopsWithDiminishingEvent() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        checker.checkTokenBudget(tracker, null, 200_000, 1_000);  // continue（cc=1, delta=1000）
        checker.checkTokenBudget(tracker, null, 200_000, 1_500);  // continue（cc=2, delta=500）
        checker.checkTokenBudget(tracker, null, 200_000, 1_990);  // continue（cc=3, delta=490<500）
        // 此时 continuationCount=3 && lastDelta=490<500；第 4 步 delta=480<500 → diminishing
        TokenBudgetChecker.StopDecision stop =
            (TokenBudgetChecker.StopDecision) checker.checkTokenBudget(tracker, null, 200_000, 2_470);
        TokenBudgetChecker.CompletionEvent event = stop.completionEvent();
        assertThat(event).isNotNull();
        assertThat(event.diminishingReturns()).as("3 连小增量后继续小步 → diminishingReturns=true").isTrue();
        assertThat(event.continuationCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("diminishing 需要 cc>=3 且连续两 delta<500：不足 3 连 → 仍 continue（CC :60-62 AND 条件）")
    void diminishingRequiresThreeConsecutiveSmallDeltas() {
        TokenBudgetChecker.BudgetTracker tracker = checker.createBudgetTracker();
        TokenBudgetChecker.TokenBudgetDecision d1 =
            checker.checkTokenBudget(tracker, null, 200_000, 1_000);   // continue（cc=1）
        checker.checkTokenBudget(tracker, null, 200_000, 1_200);       // continue（cc=2, delta=200<500）
        // cc=2 < 3 → 即使 delta 小也不 diminishing → continue（cc=3）
        TokenBudgetChecker.TokenBudgetDecision d3 =
            checker.checkTokenBudget(tracker, null, 200_000, 1_400);
        assertThat(d3).as("cc=2 < 3 → 不判定 diminishing → 继续 continue").isInstanceOf(TokenBudgetChecker.ContinueDecision.class);
        assertThat(((TokenBudgetChecker.ContinueDecision) d1).action()).isEqualTo("continue");
    }

    // ── durationMs 经注入 clock（CC tokenBudget.ts:87 Date.now() - startedAt）──

    @Test
    @DisplayName("durationMs 由注入 clock 计算：startedAt=1000 → check 时 3000 → 2000ms（CC :87）")
    void durationMs_usesInjectedClock() {
        long[] now = {1_000L};
        TokenBudgetChecker clocked = new TokenBudgetChecker(() -> now[0]);
        TokenBudgetChecker.BudgetTracker tracker = clocked.createBudgetTracker();
        clocked.checkTokenBudget(tracker, null, 200_000, 10_000); // continue
        now[0] = 3_000L;
        TokenBudgetChecker.StopDecision stop =
            (TokenBudgetChecker.StopDecision) clocked.checkTokenBudget(tracker, null, 200_000, 190_000);
        assertThat(stop.completionEvent().durationMs())
            .as("3000 - 1000 = 2000ms")
            .isEqualTo(2_000L);
    }
}

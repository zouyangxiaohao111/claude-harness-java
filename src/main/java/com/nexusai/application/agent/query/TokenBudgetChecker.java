package com.nexusai.application.agent.query;

import com.nexusai.infra.util.TokenBudgetParser;

/**
 * TokenBudgetChecker · 对齐 CC query/tokenBudget.ts.
 *
 * <p>L1 语义: 单步 LLM 调用是否继续推进的预算判定 (持续返回 continue
 * 让 LLM 完成;否则 stop 给完整的 completion event 含 diminishingReturns 警告)。
 * 两个阈值:
 * <ul>
 *   <li>{@link #COMPLETION_THRESHOLD} = 0.9 (turnTokens ≥ 90% budget → 完成可标记)</li>
 *   <li>{@link #DIMINISHING_THRESHOLD} = 500 tokens (连续 3 步增量 < 500 → diminishing returns)</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #createBudgetTracker()} () → {@link BudgetTracker};
 *       {@link #checkTokenBudget(BudgetTracker, String, Integer, int)} (tracker, agentId, budget, globalTurnTokens) → {@link TokenBudgetDecision}</li>
 *   <li><b>A2 Golden Trace</b>: agentId != null OR budget null/0 → 'stop', completionEvent=null;
 *       turnTokens < 90% budget AND not diminishing → 'continue' + nudgeMessage;
 *       diminishing OR continuationCount > 0 → 'stop' + completionEvent 含 durationMs/diminishingReturns</li>
 *   <li><b>A3 纯函数</b>: 内部 mutates tracker (in-place continuationCount++);读取 Date.now() (test 可注入 clock)</li>
 *   <li><b>A4 边界</b>: budget <= 0 → stop null event;null budget → stop</li>
 *   <li><b>A5 业务场景</b>: agentId=null,budget=200K,turnTokens=180K (90%)→ stop;tracker 创建后 turn=10K (5%) → continue</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS discriminated union ({@code 'continue' | 'stop'}) →
 * Java sealed interface with 2 records;TS type alias → Java record;
 * TS Date.now() → Java Supplier<Long> clock (test 注入)。
 */
public final class TokenBudgetChecker {

    public static final double COMPLETION_THRESHOLD = 0.9;
    public static final int DIMINISHING_THRESHOLD = 500;

    private final java.util.function.LongSupplier clock;

    public TokenBudgetChecker() { this(System::currentTimeMillis); }
    public TokenBudgetChecker(java.util.function.LongSupplier clock) { this.clock = clock; }

    /** Mutable tracker matching CC in-place increment semantics. */
    public static final class BudgetTracker {
        private int continuationCount;
        private int lastDeltaTokens;
        private int lastGlobalTurnTokens;
        private long startedAt;

        public BudgetTracker(int continuationCount, int lastDeltaTokens,
                             int lastGlobalTurnTokens, long startedAt) {
            this.continuationCount = continuationCount;
            this.lastDeltaTokens = lastDeltaTokens;
            this.lastGlobalTurnTokens = lastGlobalTurnTokens;
            this.startedAt = startedAt;
        }

        public int continuationCount() { return continuationCount; }
        public int lastDeltaTokens() { return lastDeltaTokens; }
        public int lastGlobalTurnTokens() { return lastGlobalTurnTokens; }
        public long startedAt() { return startedAt; }

        public void increment(int newDelta, int newGlobal) {
            this.continuationCount += 1;
            this.lastDeltaTokens = newDelta;
            this.lastGlobalTurnTokens = newGlobal;
        }
    }

    // CC query/tokenBudget.ts:22-43 决策联合类型仅 'continue' | 'stop'（tokenBudget.ts:43）
    public sealed interface TokenBudgetDecision permits ContinueDecision, StopDecision {}

    public record ContinueDecision(
        String action,
        String nudgeMessage,
        int continuationCount,
        int pct,
        int turnTokens,
        int budget) implements TokenBudgetDecision {

        public ContinueDecision {
            if (!"continue".equals(action)) {
                throw new IllegalArgumentException("action must be 'continue'");
            }
        }
    }

    public record StopDecision(
        String action,
        CompletionEvent completionEvent) implements TokenBudgetDecision {

        public StopDecision {
            if (!"stop".equals(action)) {
                throw new IllegalArgumentException("action must be 'stop'");
            }
        }
    }

    public record CompletionEvent(
        int continuationCount,
        int pct,
        int turnTokens,
        int budget,
        boolean diminishingReturns,
        long durationMs) {}

    public BudgetTracker createBudgetTracker() {
        return new BudgetTracker(0, 0, 0, clock.getAsLong());
    }

    public TokenBudgetDecision checkTokenBudget(
        BudgetTracker tracker,
        String agentId,
        Integer budget,
        int globalTurnTokens) {
        if (agentId != null || budget == null || budget <= 0) {
            return new StopDecision("stop", null);
        }

        int turnTokens = globalTurnTokens;
        int pct = (int) Math.round(((double) turnTokens / budget) * 100);
        int deltaSinceLastCheck = globalTurnTokens - tracker.lastGlobalTurnTokens();

        boolean isDiminishing =
            tracker.continuationCount() >= 3
            && deltaSinceLastCheck < DIMINISHING_THRESHOLD
            && tracker.lastDeltaTokens() < DIMINISHING_THRESHOLD;

        if (!isDiminishing && turnTokens < budget * COMPLETION_THRESHOLD) {
            tracker.increment(deltaSinceLastCheck, globalTurnTokens);
            return new ContinueDecision(
                "continue",
                // [ER-IMP-13] 单源化 → TokenBudgetParser.getBudgetContinuationMessage
                //（CC utils/tokenBudget.ts:66-73；曾在此文件有私有副本造成双实现漂移，已删）
                TokenBudgetParser.getBudgetContinuationMessage(pct, turnTokens, budget),
                tracker.continuationCount(),
                pct,
                turnTokens,
                budget);
        }

        if (isDiminishing || tracker.continuationCount() > 0) {
            return new StopDecision("stop",
                new CompletionEvent(
                    tracker.continuationCount(),
                    pct,
                    turnTokens,
                    budget,
                    isDiminishing,
                    clock.getAsLong() - tracker.startedAt()));
        }
        return new StopDecision("stop", null);
    }
}

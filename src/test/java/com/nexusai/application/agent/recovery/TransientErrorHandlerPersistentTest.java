package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久重试分支测试 · 对齐 CC withRetry.ts:433-463（持久延迟公式）+ :368-371（持久绕过耗尽）。
 *
 * <p><b>WHY (意图验证, 规则九)</b>: 持久重试（UNATTENDED_RETRY）延迟公式与普通退避不同——
 * 429 优先等 reset header（anthropic-ratelimit-unified-reset，窗口型限额）、持久计数
 * persistentAttempt 独立于 attempt 持续增长到 5min 指数 cap、6h reset-cap 兜底病态 header。
 * 任一偏差都会导致无人值守会话退避过短（gateway 放大 5-10×）或过长（超 reset 空等）。
 *
 * <p><b>RED teeth</b>: 若 handle 持久分支退回普通 32s 退避 / 不读 reset header / 不递增
 * persistentAttempt → 本测试必须 fail。
 */
class TransientErrorHandlerPersistentTest {

    private static final ThinkingConfig TC = ThinkingConfig.disabled();

    private final TransientErrorHandler handler = new TransientErrorHandler();
    private final RecoveryState state = new RecoveryState("claude-sonnet-4");

    @AfterEach
    void restoreEnv() {
        ErrorClassifier.ENV_READER = System::getenv;
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER = () -> ApiErrors.FALLBACK_MODEL_ID;
    }

    private RetryContext ctx() {
        return new RetryContext(null, state.getCurrentModel(), TC, null);
    }

    @Test
    @DisplayName("持久 429 + reset header → 延迟用 reset 时间戳（CC:433-447 getRateLimitResetDelayMs）")
    void persistent429UsesResetHeader() {
        long futureSec = System.currentTimeMillis() / 1000 + 3600; // 1h 后 reset
        int[] persistentAttempt = { 0 };
        RecoveryResult r = handler.handle(
            new LlmApiException(429,
                Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(futureSec))), "rate"),
            state, 1, 10, 0, ctx(), true, persistentAttempt, null);
        assertThat(r.recoverable()).isTrue();
        assertThat(persistentAttempt[0]).isEqualTo(1);
        // reset 延迟 ≈ 1h（毫秒级时钟偏移容忍），cap 6h 内保持原值
        assertThat(state.getLastBackoffMs()).isBetween(3_590_000L, 3_610_000L);
    }

    @Test
    @DisplayName("持久 429 + retry-after → min(getRetryDelay(persistentAttempt,5min),6h)（CC:433-447 回退）")
    void persistent429UsesRetryAfter() {
        int[] persistentAttempt = { 0 };
        RecoveryResult r = handler.handle(
            new LlmApiException(429, Map.of("retry-after", List.of("60")), "rate"),
            state, 1, 10, 0, ctx(), true, persistentAttempt, null);
        assertThat(r.recoverable()).isTrue();
        assertThat(persistentAttempt[0]).isEqualTo(1);
        // retry-after=60s 是服务端指令，绕过 maxDelay 内部封顶 → 60_000ms（6h cap 兜底）
        assertThat(state.getLastBackoffMs()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("持久 529 无 header → min(指数退避 persistentAttempt, 5min), 6h)（CC:448-460）")
    void persistent529UsesExponential() {
        int[] persistentAttempt = { 0 };
        RecoveryResult r = handler.handle(
            new LlmApiException(529, Map.of(), "overloaded"),
            state, 1, 10, 0, ctx(), true, persistentAttempt, null);
        assertThat(r.recoverable()).isTrue();
        assertThat(persistentAttempt[0]).isEqualTo(1);
        // base = min(500*2^0, 5min)=500 + jitter(0..124)；cap 6h 内
        assertThat(state.getLastBackoffMs()).isBetween(500L, 624L);
    }

    @Test
    @DisplayName("持久计数独立递增：连续两次 handle → persistentAttempt 1→2（CC:188/:433 persistentAttempt++）")
    void persistentAttemptIncrements() {
        int[] persistentAttempt = { 0 };
        handler.handle(new LlmApiException(429, Map.of(), "rate"),
            state, 1, 10, 0, ctx(), true, persistentAttempt, null);
        handler.handle(new LlmApiException(429, Map.of(), "rate"),
            state, 2, 10, 0, ctx(), true, persistentAttempt, null);
        assertThat(persistentAttempt[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("非持久路径（persistent=false）→ 32s 指数退避 + persistentAttempt 不递增（CC:462 普通 getRetryDelay）")
    void nonPersistentUsesStandardBackoff() {
        int[] persistentAttempt = { 0 };
        RecoveryResult r = handler.handle(
            new LlmApiException(529, Map.of(), "overloaded"),
            state, 5, 10, 0, ctx(), false, persistentAttempt, null);
        assertThat(r.recoverable()).isTrue();
        assertThat(persistentAttempt[0]).isZero();
        // attempt=5 → base = min(500*2^4, 32s) = 8000 + jitter(0..1999)
        assertThat(state.getLastBackoffMs()).isBetween(8000L, 9999L);
    }

    @Test
    @DisplayName("持久 429 超 6h reset → cap 6h（CC:446/:459 PERSISTENT_RESET_CAP_MS）")
    void persistentResetCappedAtSixHours() {
        long farFutureSec = System.currentTimeMillis() / 1000 + 10 * 3600; // 10h 后 reset
        int[] persistentAttempt = { 0 };
        handler.handle(
            new LlmApiException(429,
                Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(farFutureSec))), "rate"),
            state, 1, 10, 0, ctx(), true, persistentAttempt, null);
        assertThat(state.getLastBackoffMs())
            .isLessThanOrEqualTo(ApiErrors.PERSISTENT_RESET_CAP_MS);
    }

    @Test
    @DisplayName("持久 3×529 无 fallback → 不抛 CannotRetryException，落空退避继续重试（V-EC-1 · CC withRetry.ts:353-357 !isPersistentRetryEnabled 门控）")
    void persistentThree529NoFallbackContinuesBackoff() {
        // V-EC-1: CC withRetry.ts:353-363 REPEATED_529 快速失败仅在
        //   USER_TYPE==='external' && !IS_SANDBOX && !isPersistentRetryEnabled() 时触发。
        //   persistent=true → 无 fallback 也落空退避（继续无限重试），不终止循环。
        // 旧实现（R-FALLBACK）无 persistent 检查 → 3×529 无 fallback 即抛 CannotRetryException(REPEATED_529)，
        //   无人值守持久会话被 3 连 529 杀死（本测试必须 fail 捕获此回归）。
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER = () -> null; // 无 fallback 模型
        RecoveryState opusState = new RecoveryState("claude-opus-4-6");
        int[] persistentAttempt = { 0 };
        RecoveryResult r = handler.handle(
            new LlmApiException(529, Map.of(), "overloaded"),
            opusState, 3, 10, 3, ctx(), true, persistentAttempt, null);
        assertThat(r.recoverable()).isTrue();
        assertThat(opusState.getCurrentModel()).isEqualTo("claude-opus-4-6"); // 未降级
        assertThat(persistentAttempt[0]).isEqualTo(1); // 走持久退避
        assertThat(opusState.getLastBackoffMs()).isGreaterThan(0);
    }
}

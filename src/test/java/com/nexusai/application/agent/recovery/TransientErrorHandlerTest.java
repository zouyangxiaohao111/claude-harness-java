package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import com.nexusai.infra.llm.LlmApiException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TransientErrorHandler 恢复处理器测试 · 对齐 CC withRetry.ts:170-517 分类 + 529 阈值处置。
 *
 * <p><b>WHY (意图验证)</b>: 恢复处理器是 429/529 临时错误的重试裁决器——分类闸（shouldRetry）、
 * 529 计数阈值、fallback 触发、REPEATED_529 快速失败（决策 4 统一阈值）任一偏差都会导致
 * 「不可重试错误被反复重试 / 连续 529 不降级 / 无 fallback 时死循环重试」。断言锁定 CC
 * withRetry.ts 的分支语义。
 */
class TransientErrorHandlerTest {

    private static final ThinkingConfig TC = ThinkingConfig.disabled();

    private final TransientErrorHandler handler = new TransientErrorHandler();
    // [ER-IMP-10] 资格闸（CC withRetry.ts:330-335）：仅非自定义 Opus 主模型计入 529 连续计数。
    // 阈值测试用 firstParty Opus（opus40 = claude-opus-4-20250514，configs.ts:52）保证过闸。
    private final RecoveryState state = new RecoveryState("claude-opus-4-20250514");
    private final RetryContext retryContext = new RetryContext(null, "claude-opus-4-20250514", TC, null);

    @AfterEach
    void restoreEnv() {
        ErrorClassifier.ENV_READER = System::getenv;
    }

    private RetryContext ctx() {
        return new RetryContext(null, state.getCurrentModel(), TC, null);
    }

    @Test
    @DisplayName("非可重试错误（400 非 overflow）→ recoverable=false 不重试（CC:377-382）")
    void nonRetryableErrorReturnsNonRecoverable() {
        RecoveryResult r = handler.handle(new LlmApiException(400, Map.of(), "bad request"),
            state, 1, 10, 0, ctx());
        assertThat(r.recoverable()).isFalse();
        assertThat(state.getLastReason()).isNull();
    }

    @Test
    @DisplayName("429 → recoverable=true 指数退避（CC:108 + :530-548）")
    void rateLimitReturnsRecoverableWithBackoff() {
        RecoveryResult r = handler.handle(new LlmApiException(429, Map.of(), "rate limited"),
            state, 1, 10, 0, ctx());
        assertThat(r.recoverable()).isTrue();
        assertThat(state.getLastBackoffMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("529 未达阈值 → recoverable=true 继续重试（CC:610-621 + 指数退避）")
    void five29NotAtThresholdKeepsRetrying() {
        RecoveryResult r = handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 2, 10, 1, ctx());
        assertThat(r.recoverable()).isTrue();
        assertThat(state.getLastBackoffMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("连续 3 次 529 达阈值 + 有 fallback → 抛 FallbackTriggeredError（CC:337-351）")
    void consecutive529AtThresholdThrowsFallback() {
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER = () -> "claude-haiku";
        try {
            assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
                state, 3, 10, 3, ctx()))
                .isInstanceOf(FallbackTriggeredError.class)
                .satisfies(e -> {
                    FallbackTriggeredError fte = (FallbackTriggeredError) e;
                    assertThat(fte.originalModel()).isEqualTo("claude-opus-4-20250514");
                    assertThat(fte.fallbackModel()).isEqualTo("claude-haiku");
                });
        } finally {
            TransientErrorHandler.FALLBACK_MODEL_SUPPLIER =
                () -> ApiErrors.FALLBACK_MODEL_ID;
        }
    }

    @Test
    @DisplayName("连续 3 次 529 达阈值 + 无 fallback → CannotRetryException(REPEATED_529)（决策 4 统一阈值）")
    void consecutive529AtThresholdNoFallbackThrowsCannotRetry() {
        assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx()))
            .isInstanceOf(CannotRetryException.class)
            .satisfies(e -> {
                CannotRetryException cre = (CannotRetryException) e;
                assertThat(cre.getOriginalError().getMessage())
                    .isEqualTo(ApiErrors.REPEATED_529_ERROR_MESSAGE);
                assertThat(cre.getRetryContext().model()).isEqualTo("claude-opus-4-20250514");
            });
    }

    @Test
    @DisplayName("凭证自愈（Bedrock 403）→ recoverable=true 可重试（CC:375-376 handleAwsCredentialError 过闸）")
    void credentialErrorIsRetryable() {
        ErrorClassifier.ENV_READER = name -> "CLAUDE_CODE_USE_BEDROCK".equals(name) ? "true" : null;
        RecoveryResult r = handler.handle(new LlmApiException(403, Map.of(),
            "The security token included in the request is invalid"),
            state, 1, 10, 0, ctx());
        assertThat(r.recoverable()).isTrue();
    }

    @Test
    @DisplayName("Retry-After header 优先：429 + header → 退避用 header 秒数（CC:519-528）")
    void retryAfterHeaderRespected() {
        RecoveryResult r = handler.handle(new LlmApiException(429,
            Map.of("retry-after", java.util.List.of("120")), "rate limited"),
            state, 1, 10, 0, ctx());
        assertThat(r.recoverable()).isTrue();
        assertThat(state.getLastBackoffMs()).isEqualTo(120_000L);
    }
}

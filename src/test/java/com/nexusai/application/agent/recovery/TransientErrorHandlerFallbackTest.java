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
 * fallback 资格闸测试 · 对齐 CC withRetry.ts:327-363（ER-IMP-10）。
 *
 * <p><b>WHY (意图验证, 规则九)</b>: CC 的 529 fallback 不是任意模型 3×529 即降级——
 * 资格闸（withRetry.ts:330-335）限定<b>仅非自定义 Opus 主模型</b>（或 env
 * FALLBACK_FOR_ALL_PRIMARY_MODELS 全开）才计入 consecutive529Errors 并触发 fallback。
 * 旧 Java 实现（R-FALLBACK）无闸：Sonnet 等任意模型 3×529 也降级，属行为偏差。
 * 本测试锁定：
 * <ol>
 *   <li>Opus 主模型 3×529 → FallbackTriggeredError（CC:337-351）</li>
 *   <li>Sonnet 主模型 3×529 → <b>不降级</b>，仅退避重试（CC:329-333 fall through）</li>
 *   <li>fallback 模型按调用传入优先，env 仅默认值（DC-18 · CC withRetry.ts:337 options.fallbackModel）</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert 资格闸（任意模型 529 计数）或 revert 按调用传入（直读 env）
 * → 对应用例必须 fail。
 */
class TransientErrorHandlerFallbackTest {

    private static final ThinkingConfig TC = ThinkingConfig.disabled();

    private final TransientErrorHandler handler = new TransientErrorHandler();

    @AfterEach
    void restoreEnvAndSupplier() {
        ErrorClassifier.ENV_READER = System::getenv;
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER =
            () -> ApiErrors.FALLBACK_MODEL_ID;
    }

    private RetryContext ctx(String model) {
        return new RetryContext(null, model, TC, null);
    }

    @Test
    @DisplayName("Opus 主模型 3×529 + 按调用传入 fallback → FallbackTriggeredError（CC:337-351）")
    void opusThree529ThrowsFallbackWithPerCallModel() {
        RecoveryState state = new RecoveryState("claude-opus-4-20250514");
        assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-opus-4-20250514"), false, null,
            "claude-haiku-4-5-20251001"))
            .isInstanceOf(FallbackTriggeredError.class)
            .satisfies(e -> {
                FallbackTriggeredError fte = (FallbackTriggeredError) e;
                assertThat(fte.originalModel()).isEqualTo("claude-opus-4-20250514");
                assertThat(fte.fallbackModel()).isEqualTo("claude-haiku-4-5-20251001");
            });
    }

    @Test
    @DisplayName("Opus 3×529 + 未传 fallback + env 默认 → FallbackTriggeredError（DC-18 env 仅默认值）")
    void opusThree529UsesEnvDefaultWhenNoPerCall() {
        RecoveryState state = new RecoveryState("claude-opus-4-1-20250805");
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER = () -> "claude-3-5-haiku-20241022";
        assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-opus-4-1-20250805"), false, null, null))
            .isInstanceOf(FallbackTriggeredError.class)
            .satisfies(e -> {
                FallbackTriggeredError fte = (FallbackTriggeredError) e;
                assertThat(fte.fallbackModel()).isEqualTo("claude-3-5-haiku-20241022");
            });
    }

    @Test
    @DisplayName("按调用传入优先：env 已配 haiku 但显式传 sonnet → 用显式值（DC-18 优先序）")
    void perCallFallbackWinsOverEnv() {
        RecoveryState state = new RecoveryState("claude-opus-4-5-20251101");
        TransientErrorHandler.FALLBACK_MODEL_SUPPLIER = () -> "claude-3-5-haiku-20241022";
        assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-opus-4-5-20251101"), false, null,
            "claude-sonnet-4-5-20250929"))
            .isInstanceOf(FallbackTriggeredError.class)
            .satisfies(e -> {
                FallbackTriggeredError fte = (FallbackTriggeredError) e;
                assertThat(fte.fallbackModel()).isEqualTo("claude-sonnet-4-5-20250929");
            });
    }

    @Test
    @DisplayName("Sonnet 主模型 3×529 → 不降级，仅退避重试（CC:329-333 资格闸 fall through）")
    void sonnetThree529DoesNotFallback() {
        RecoveryState state = new RecoveryState("claude-sonnet-4-20250514");
        RecoveryResult r = handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-sonnet-4-20250514"), false, null,
            "claude-haiku-4-5-20251001");
        // 资格闸阻断：Sonnet 不降级，返回可恢复退避（CC 非 Opus 529 不累计 → 无 fallback 分支）
        assertThat(r.recoverable()).isTrue();
        assertThat(state.getLastBackoffMs()).isGreaterThan(0);
        assertThat(state.getCurrentModel()).isEqualTo("claude-sonnet-4-20250514");
    }

    @Test
    @DisplayName("Opus 3×529 + 无任何 fallback → CannotRetryException(REPEATED_529)（决策 4 统一阈值）")
    void opusThree529NoFallbackThrowsCannotRetry() {
        RecoveryState state = new RecoveryState("claude-opus-4-6");
        assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-opus-4-6"), false, null, null))
            .isInstanceOf(CannotRetryException.class)
            .satisfies(e -> assertThat(((CannotRetryException) e).getOriginalError().getMessage())
                .isEqualTo(ApiErrors.REPEATED_529_ERROR_MESSAGE));
    }

    @Test
    @DisplayName("FALLBACK_FOR_ALL_PRIMARY_MODELS 全开 → Sonnet 3×529 也降级（CC:331 开关 bypass 资格闸）")
    void allPrimaryModelsEnvBypassesOpusGate() {
        ErrorClassifier.ENV_READER = name ->
            ApiErrors.ENV_FALLBACK_FOR_ALL_PRIMARY_MODELS.equals(name) ? "true" : null;
        RecoveryState state = new RecoveryState("claude-sonnet-4-20250514");
        assertThatThrownBy(() -> handler.handle(new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-sonnet-4-20250514"), false, null,
            "claude-haiku-4-5-20251001"))
            .isInstanceOf(FallbackTriggeredError.class)
            .satisfies(e -> {
                FallbackTriggeredError fte = (FallbackTriggeredError) e;
                assertThat(fte.originalModel()).isEqualTo("claude-sonnet-4-20250514");
            });
    }
}

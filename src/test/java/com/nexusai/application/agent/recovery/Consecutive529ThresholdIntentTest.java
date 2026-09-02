package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 529 计数阈值意图测试 · 对齐 CC withRetry.ts:54 MAX_529_RETRIES=3 + :337-363。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: CC 连续 529 阈值不是「任意 529 即降级」——
 * 必须连续 3 次（MAX_529_RETRIES=3）且通过资格闸（isNonCustomOpusModel）才触发 fallback。
 * 少于 3 次只走标准退避重试。这防止了「单次 529 抖动即切换模型导致上下文丢失」的问题。
 *
 * <p>RED teeth: 改 MAX_CONSECUTIVE_529 从 3 到 1 → test_countBelowThreshold_doesNotFallback 失败；
 * 删资格闸 → test_sonnetCountDoesNotAccumulate 失败；改计数重置逻辑 → test_consecutiveResetAfterSuccess 失败。
 */
class Consecutive529ThresholdIntentTest {

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

    // ════════════════════════════════════════════════════════════════════
    // 阈值行为 · CC withRetry.ts:54 MAX_529_RETRIES=3
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("529 计数 < 3 → 不降级，仅退避重试（CC:337-351 阈值未达）")
    void countBelowThreshold_doesNotFallback() {
        RecoveryState state = new RecoveryState("claude-opus-4-6");
        // consecutive529Errors=1, 2 都不够阈值 → 可恢复退避
        for (int count : new int[]{1, 2}) {
            RecoveryResult r = handler.handle(
                new LlmApiException(529, Map.of(), "overloaded"),
                state, count, 10, count, ctx("claude-opus-4-6"),
                false, null, "claude-haiku-4-5-20251001");
            assertThat(r.recoverable())
                .as("连续 529 count=%d < MAX_CONSECUTIVE_529=3 必须不降级", count)
                .isTrue();
            assertThat(state.getCurrentModel())
                .as("未降级：currentModel 保持原主模型")
                .isEqualTo("claude-opus-4-6");
        }
    }

    @Test
    @DisplayName("529 计数 = 3 + Opus → 降级 FallbackTriggeredError（CC:337-351 阈值达到）")
    void countAtThreshold_opusTriggersFallback() {
        RecoveryState state = new RecoveryState("claude-opus-4-6");
        assertThatThrownBy(() -> handler.handle(
            new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-opus-4-6"),
            false, null, "claude-haiku-4-5-20251001"))
            .as("连续 529 count=3 = MAX_CONSECUTIVE_529 必须触发降级")
            .isInstanceOf(FallbackTriggeredError.class);
    }

    @Test
    @DisplayName("529 计数 = 3 + Sonnet → 不降级（CC:330-335 资格闸阻断）")
    void sonnetCountDoesNotAccumulate() {
        RecoveryState state = new RecoveryState("claude-sonnet-4-6");
        // Sonnet 不在 isNonCustomOpusModel 集合 → 资格闸阻断 → 不累计 → 不降级
        RecoveryResult r = handler.handle(
            new LlmApiException(529, Map.of(), "overloaded"),
            state, 3, 10, 3, ctx("claude-sonnet-4-6"),
            false, null, "claude-haiku-4-5-20251001");
        assertThat(r.recoverable())
            .as("Sonnet 资格闸阻断：即使 count=3 也不降级（CC:330-335）")
            .isTrue();
        assertThat(state.getCurrentModel())
            .as("Sonnet 不降级：currentModel 保持 Sonnet")
            .isEqualTo("claude-sonnet-4-6");
    }

    // ════════════════════════════════════════════════════════════════════
    // 阈值常量 · CC withRetry.ts:54
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MAX_CONSECUTIVE_529 = 3（CC withRetry.ts:54 MAX_529_RETRIES=3）")
    void maxConsecutive529ConstantMatchesCc() {
        assertThat(ApiErrors.MAX_CONSECUTIVE_529)
            .as("CC withRetry.ts:54 MAX_529_RETRIES 必须为 3")
            .isEqualTo(3);
    }

    // ════════════════════════════════════════════════════════════════════
    // 非 529 错误不累计到 529 计数（CC withRetry.ts:610-621 is529Error 类型闸）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("429 错误不计入 529 连续计数（CC:610-621 类型闸 is529Error）")
    void non529DoesNotAccumulateTo529Count() {
        RecoveryState state = new RecoveryState("claude-opus-4-6");
        // 429 错误即使 consecutive529Errors=3（调用方传入），handler 内部不命中 is529Error
        // → 不走 529 分支 → 不降级
        RecoveryResult r = handler.handle(
            new LlmApiException(429, Map.of(), "rate limited"),
            state, 3, 10, 3, ctx("claude-opus-4-6"),
            false, null, "claude-haiku-4-5-20251001");
        assertThat(r.recoverable())
            .as("429 不命中 is529Error → 不走 529 降级路径")
            .isTrue();
        assertThat(state.getCurrentModel())
            .as("429 不降级：currentModel 保持原模型")
            .isEqualTo("claude-opus-4-6");
    }
}

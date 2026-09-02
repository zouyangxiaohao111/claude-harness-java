package com.nexusai.infra.llm;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [DEC-RV-09] 非流式 max_tokens cap 64000 对齐 CC 测试。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC 非流式请求（executeNonStreamingRequest）有 10 分钟硬上限，
 * SDK 默认 21333-token cap 被 client 级 timeout 绕过后 CC 自行 cap 到 MAX_NON_STREAMING_TOKENS=64_000
 * （claude.ts:3354），并同步钳制 thinking.budget_tokens（claude.ts:3364-3392）。Java 非流式入口
 * （chatWithRaw / chatWithOptions）在 buildMessageParams 之后、create 之前无条件应用该 cap。本测试验证：
 * <ul>
 *   <li>cap 无条件：min(max_tokens, 64000)，仅超限时生效（10min 上限保护）</li>
 *   <li>thinking budget 同步：min(budget, capped-1)，满足 API 约束 max_tokens &gt; thinking.budget_tokens</li>
 *   <li>toBuilder 保留其余字段（temperature 等），等价 CC {...params, max_tokens: capped} spread</li>
 * </ul>
 */
class AnthropicSdkProviderNonStreamingCapTest {

    private static final String MODEL = "claude-sonnet-4-20250514";

    @Test
    @DisplayName("超限 cap：max_tokens=100_000 → adjust 后 = 64_000（非流式 10min 上限保护）")
    void overCap_clampsTo64000() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(100_000)
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(64_000);
    }

    @Test
    @DisplayName("未超限不动：max_tokens=8_000 → adjust 后仍 = 8_000（Math.min 恒执行但仅超限生效）")
    void underCap_unchanged() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(8_000)
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("thinking enabled + budget 超限：budget=64_000 → capped-1 = 63_999（max_tokens > budget 约束）")
    void thinkingBudgetOverCap_clampedToCappedMinus1() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(64_000)
            .thinking(ThinkingConfigEnabled.builder().budgetTokens(64_000).build())
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(64_000);
        assertThat(adjusted.thinking()).isPresent();
        ThinkingConfigEnabled enabled = adjusted.thinking().get().asEnabled();
        assertThat(enabled.budgetTokens()).isEqualTo(63_999);
    }

    @Test
    @DisplayName("thinking enabled + budget 超限：additionalProperties 保留（CC {...thinking, budget_tokens:X} spread 等价）")
    void thinkingBudgetOverCap_additionalPropertiesPreserved() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(64_000)
            .thinking(ThinkingConfigEnabled.builder()
                .budgetTokens(64_000)
                .putAdditionalProperty("custom", com.anthropic.core.JsonValue.from("v"))
                .build())
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        ThinkingConfigEnabled enabled = adjusted.thinking().get().asEnabled();
        assertThat(enabled.budgetTokens()).isEqualTo(63_999);
        // CC spread {...thinking, budget_tokens: X} 保留全部字段 —— toBuilder 复制 additionalProperties
        assertThat(enabled._additionalProperties()).containsKey("custom");
    }

    @Test
    @DisplayName("thinking enabled + budget 未超限：budget=10_000 → 不动（仅超限钳制）")
    void thinkingBudgetUnderCap_unchanged() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(64_000)
            .thinking(ThinkingConfigEnabled.builder().budgetTokens(10_000).build())
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(64_000);
        assertThat(adjusted.thinking()).isPresent();
        ThinkingConfigEnabled enabled = adjusted.thinking().get().asEnabled();
        assertThat(enabled.budgetTokens()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("thinking disabled：type 不变、无 budget 钳制（分支守卫 type==='enabled'）")
    void thinkingDisabled_unchanged() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(100_000)
            .thinking(ThinkingConfigDisabled.builder().build())
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(64_000);
        assertThat(adjusted.thinking()).isPresent();
        assertThat(adjusted.thinking().get().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("cap 应用链路：buildMessageParams(override=100_000) → adjust → max_tokens=64_000（非流式请求体最终 cap）")
    void capAppliedOnBuildMessageParamsChain() {
        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            MODEL, null, List.of(), null, 100_000, null, null, null, null);

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(64_000);
    }

    @Test
    @DisplayName("temperature 保留：adjust 不改 temperature 字段（toBuilder 等价 CC spread）")
    void temperaturePreserved() {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(MODEL)
            .addUserMessage("test")
            .maxTokens(100_000)
            .temperature(0.5)
            .build();

        MessageCreateParams adjusted = AnthropicSdkProvider.adjustParamsForNonStreaming(params);

        assertThat(adjusted.maxTokens()).isEqualTo(64_000);
        assertThat(adjusted.temperature()).isPresent();
        assertThat(adjusted.temperature().get()).isEqualTo(0.5);
    }
}

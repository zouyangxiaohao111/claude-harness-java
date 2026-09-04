package com.nexusai.application.agent.compact.fork;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * fork 查询结果 · 对齐 CC {@code ForkedAgentResult}
 * (Open-ClaudeCode/src/utils/forkedAgent.ts:115-120)。
 *
 * @param messages  query loop 产出的全部消息 · CC original:
 *                  {@code messages: Message[]} (forkedAgent.ts:117)
 * @param totalUsage loop 内所有 API 调用累计 usage · CC original:
 *                  {@code totalUsage: NonNullableUsage} (forkedAgent.ts:119)
 * @param providerType provider type（LlmProvider.type()：'anthropic' | 'openai_compatible' |
 *                     'openai_sdk' ...）· A 命中率口径协议分派载荷；未知/便捷构造 → null
 *                     （{@link #isAnthropic()}=false → 非 anthropic read/input 语义）
 */
public record ForkedAgentResult(
        List<ChatMessageDto> messages,
        ForkUsage totalUsage,
        String providerType) {

    public ForkedAgentResult {
        if (messages == null) {
            messages = List.of();
        }
        if (totalUsage == null) {
            totalUsage = ForkUsage.empty();
        }
    }

    /**
     * 便捷构造 · providerType 未知 → null（{@link #isAnthropic()}=false）。
     * 兼容既有 2 参调用点（测试 fake ForkedQuery 构造 / 语义不变）。
     */
    public ForkedAgentResult(List<ChatMessageDto> messages, ForkUsage totalUsage) {
        this(messages, totalUsage, null);
    }

    /**
     * 是否 Anthropic provider · {@code "anthropic".equals(providerType)}
     * （null / 'openai_compatible' / 'openai_sdk' → false，非 anthropic read/input 语义）。
     */
    public boolean isAnthropic() {
        return "anthropic".equals(providerType);
    }

    /**
     * fork usage 累计 · 对齐 CC {@code NonNullableUsage} 关键字段
     * (services/api/logging.ts EMPTY_USAGE；forkedAgent.ts:504/564-565)。
     *
     * @param inputTokens           输入 token · CC original: input_tokens
     * @param outputTokens          输出 token · CC original: output_tokens
     * @param cacheReadInputTokens  cache 读 token · CC original: cache_read_input_tokens
     * @param cacheCreationInputTokens cache 写 token · CC original: cache_creation_input_tokens
     */
    public record ForkUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadInputTokens,
            long cacheCreationInputTokens) {

        /** 空 usage（对齐 CC EMPTY_USAGE）。 */
        public static ForkUsage empty() {
            return new ForkUsage(0, 0, 0, 0);
        }

        /** 累加（对齐 CC accumulateUsage）。 */
        public ForkUsage accumulate(ForkUsage other) {
            if (other == null) {
                return this;
            }
            return new ForkUsage(
                this.inputTokens + other.inputTokens,
                this.outputTokens + other.outputTokens,
                this.cacheReadInputTokens + other.cacheReadInputTokens,
                this.cacheCreationInputTokens + other.cacheCreationInputTokens);
        }
    }
}

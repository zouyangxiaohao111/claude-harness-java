package com.nexusai.application.agent.recovery;

import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;

/**
 * 重试上下文 · 对齐 CC withRetry.ts:120-125 {@code interface RetryContext}。
 *
 * <p>在 withRetry 重试循环中随每次 attempt 传给 operation 的只读快照；其中
 * {@code maxTokensOverride} 由 max_tokens 上下文溢出调整写入（CC withRetry.ts:416
 * {@code retryContext.maxTokensOverride = adjustedMaxTokens}），驱动下一次尝试的
 * max_tokens 覆盖（Java record 不可变，溢出调整留 ER-IMP-08 以 wither 表达）。
 *
 * <p>字段清单 grep 自验（Open-ClaudeCode/src/services/api/withRetry.ts）：
 * <table>
 *   <tr><th>本字段</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>maxTokensOverride</td><td>maxTokensOverride?</td><td>121</td></tr>
 *   <tr><td>model</td><td>model</td><td>122</td></tr>
 *   <tr><td>thinkingConfig</td><td>thinkingConfig</td><td>123</td></tr>
 *   <tr><td>fastMode</td><td>fastMode?</td><td>124</td></tr>
 * </table>
 *
 * <p>thinkingConfig 类型复用 {@link ThinkingConfig}
 * （LlmProvider.ChatRequestOptions.ThinkingConfig，LlmProvider.java:526
 * {@code record ThinkingConfig(String type, Integer budgetTokens)}），
 * budgetTokens 语义对齐 CC thinkingConfig.budgetTokens（utils/thinking.ts:12，
 * 仅 {@code type=='enabled'} 时有值；withRetry.ts:409 消费）。
 */
public record RetryContext(
    /** CC original: maxTokensOverride (withRetry.ts:121) — 溢出调整写入，null=未调整 */
    Integer maxTokensOverride,
    /** CC original: model (withRetry.ts:122) — 当前生效模型 ID */
    String model,
    /** CC original: thinkingConfig (withRetry.ts:123) — 思考配置（Java 复用 LlmProvider.ChatRequestOptions.ThinkingConfig） */
    ThinkingConfig thinkingConfig,
    /** CC original: fastMode (withRetry.ts:124) — 快速模式（null=未启用/未知） */
    Boolean fastMode
) {
    /**
     * 便捷构造：maxTokensOverride 缺省 null。
     *
     * @param model         当前模型 ID
     * @param thinkingConfig 思考配置
     * @param fastMode      快速模式（null=未启用）
     */
    public static RetryContext of(String model, ThinkingConfig thinkingConfig, Boolean fastMode) {
        return new RetryContext(null, model, thinkingConfig, fastMode);
    }
}

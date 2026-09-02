package com.nexusai.application.agent.recovery;

import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;

import java.util.function.Supplier;

/**
 * 重试参数 · 对齐 CC withRetry.ts:127-142 {@code interface RetryOptions}。
 *
 * <p>withRetry 单次调用的全量入参契约。CC 中 signal 为 {@code AbortSignal}，Java 无该
 * 类型，以 {@link Supplier}{@code <Boolean>} 等价映射 {@code signal.aborted} 语义
 * （返回 true = 已中止；LlmAgentLoop 接 {@code state::cancelled}）。
 *
 * <p>字段清单 grep 自验（Open-ClaudeCode/src/services/api/withRetry.ts）：
 * <table>
 *   <tr><th>本字段</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>maxRetries</td><td>maxRetries?</td><td>128</td></tr>
 *   <tr><td>model</td><td>model</td><td>129</td></tr>
 *   <tr><td>fallbackModel</td><td>fallbackModel?</td><td>130</td></tr>
 *   <tr><td>thinkingConfig</td><td>thinkingConfig</td><td>131</td></tr>
 *   <tr><td>fastMode</td><td>fastMode?</td><td>132</td></tr>
 *   <tr><td>aborted</td><td>signal?: AbortSignal</td><td>133</td></tr>
 *   <tr><td>querySource</td><td>querySource?</td><td>134</td></tr>
 *   <tr><td>initialConsecutive529Errors</td><td>initialConsecutive529Errors?</td><td>141</td></tr>
 * </table>
 *
 * <p>{@code querySource} CC 类型为 string union（QuerySource 类型别名），Java 以
 * {@code QuerySource.name()} 字符串承载（与 LlmAgentLoop 传 LLM 侧同一口径）。
 * {@code initialConsecutive529Errors} 用于非流式 fallback 预置流式 529 计数
 * （CC withRetry.ts:135-141 javadoc + claude.ts:2559 is529Error(streamingError) ? 1 : 0）。
 * <b>RV-03-02 接线</b>（DEC-RV-03 · 对齐 CC claude.ts:830/:903 + withRetry.ts:186）：消费方 =
 * {@code AnthropicSdkProvider.nonStreamingFallback}（流式→非流式回退，迁移自旧 AnthropicProvider），
 * 以流式失败是否 529 预置该值（claude.ts:2559），非流式重试循环初始计数 {@code ?? 0} 消费（withRetry.ts:186）。
 * 旧 V-WR-04 "Java 非流式降级在 provider 内部 -> 无预置点" 已失实：provider 现做非流式回退，
 * 预置点即回退分支。
 */
public record RetryOptions(
    /** CC original: maxRetries? (withRetry.ts:128) — null=走 env CLAUDE_CODE_MAX_RETRIES ?? 10 */
    Integer maxRetries,
    /** CC original: model (withRetry.ts:129) — 请求模型 ID */
    String model,
    /** CC original: fallbackModel? (withRetry.ts:130) — 连续 529 达阈值后的降级模型 */
    String fallbackModel,
    /** CC original: thinkingConfig (withRetry.ts:131) — 思考配置（Java 复用 LlmProvider.ChatRequestOptions.ThinkingConfig） */
    ThinkingConfig thinkingConfig,
    /** CC original: fastMode? (withRetry.ts:132) — 快速模式 */
    Boolean fastMode,
    /**
     * 中止信号 · CC original: signal?: AbortSignal (withRetry.ts:133)。
     *
     * <p>Java 无 AbortSignal，以 Supplier&lt;Boolean&gt; 等价映射 {@code signal.aborted}
     * 语义：返回 true = 已中止（调用方接 {@code state::cancelled}）。null = 无中止信号。
     */
    Supplier<Boolean> aborted,
    /** CC original: querySource? (withRetry.ts:134) — 查询来源（IMP2-01 起 Java 传 QuerySource.canonical() 小写值，ErrorClassifier.shouldRetry529 兼容 name/canonical 双形态） */
    String querySource,
    /** CC original: initialConsecutive529Errors? (withRetry.ts:141) - RV-03-02 已接线：AnthropicSdkProvider 非流式回退预置流式 529 计数（claude.ts:2559），非流式重试循环消费（withRetry.ts:186 ?? 0） */
    Integer initialConsecutive529Errors
) {
    /**
     * 便捷构造：仅模型 + thinkingConfig + 中止信号，其余缺省 null。
     *
     * @param model         请求模型 ID
     * @param thinkingConfig 思考配置
     * @param aborted       中止信号（null=无）
     */
    public static RetryOptions of(String model, ThinkingConfig thinkingConfig, Supplier<Boolean> aborted) {
        return new RetryOptions(null, model, null, thinkingConfig, null, aborted, null, null);
    }
}

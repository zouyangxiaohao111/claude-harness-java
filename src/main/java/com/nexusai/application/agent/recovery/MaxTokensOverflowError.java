package com.nexusai.application.agent.recovery;

/**
 * max_tokens 上下文溢出解析结果 · 对齐 CC withRetry.ts:550-555
 * {@code parseMaxTokensContextOverflowError} 返回值 {@code {inputTokens, maxTokens, contextLimit}}。
 *
 * <pre>
 * // CC withRetry.ts:550-555
 * export function parseMaxTokensContextOverflowError(error: APIError): {
 *   inputTokens: number
 *   maxTokens: number
 *   contextLimit: number
 * } | undefined
 * </pre>
 *
 * <p>消息示例：{@code "input length and `max_tokens` exceed context limit: 188059 + 20000 > 200000"}
 * （withRetry.ts:569）。解析由 {@link ErrorClassifier#parseMaxTokensContextOverflowError} 承担；
 * retryContext.maxTokensOverride 调整（safetyBuffer=1000 / FLOOR_OUTPUT_TOKENS=3000 /
 * minRequired=thinking+1）属 ER-IMP-08。
 *
 * @param inputTokens  input tokens 数 · CC original: inputTokens (withRetry.ts:552)
 * @param maxTokens    本次请求 max_tokens · CC original: maxTokens (withRetry.ts:553)
 * @param contextLimit 模型上下文上限 · CC original: contextLimit (withRetry.ts:554)
 */
public record MaxTokensOverflowError(
    int inputTokens,
    int maxTokens,
    int contextLimit
) {}

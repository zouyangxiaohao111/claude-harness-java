package com.nexusai.application.agent.recovery;

import java.time.Instant;
import java.util.UUID;

/**
 * CC createSystemAPIErrorMessage 结构等价 · messages.ts:4585-4599。
 *
 * <p>CC（grep 自验 4585）：
 * <pre>
 * export function createSystemAPIErrorMessage(error, retryInMs, retryAttempt, maxRetries): SystemAPIErrorMessage {
 *   return {
 *     type: 'system',
 *     subtype: 'api_error',
 *     level: 'error',
 *     cause: error.cause instanceof Error ? error.cause : undefined,
 *     error,
 *     retryInMs,
 *     retryAttempt,
 *     maxRetries,
 *     timestamp: new Date().toISOString(),
 *     uuid: randomUUID(),
 *   }
 * }
 * </pre>
 *
 * <p>该消息在重试 backoff 期间 yield（withRetry.ts:493-503 持久分片 / :510 非持久），经
 * QueryEngine.ts:943-955 转换为 {@code subtype: 'api_retry'} 的 SDK 事件载荷：
 * {@code attempt/max_retries/retry_delay_ms/error_status/error(categorizeRetryableAPIError)/session_id/uuid}。
 *
 * @param type        恒 "system"（CC messages.ts:4588）
 * @param subtype     恒 "api_error"（CC messages.ts:4589）
 * @param level       恒 "error"（CC messages.ts:4590）
 * @param cause       CC original: error.cause instanceof Error ? error.cause : undefined（messages.ts:4591）
 * @param error       原始 API 错误（CC original: error · messages.ts:4592）
 * @param retryInMs   待等待毫秒数（持久模式为分片 remaining · withRetry.ts:495）
 * @param retryAttempt 本次重试尝试号（CC messages.ts:4596）
 * @param maxRetries  重试上限（CC messages.ts:4597）
 * @param timestamp   ISO-8601 时间戳（CC messages.ts:4598）
 * @param uuid        唯一 ID（CC messages.ts:4599）
 */
public record SystemApiErrorMessage(
    String type,
    String subtype,
    String level,
    Throwable cause,
    Throwable error,
    long retryInMs,
    int retryAttempt,
    int maxRetries,
    String timestamp,
    String uuid
) {

    /**
     * 工厂 · 对齐 CC createSystemAPIErrorMessage（messages.ts:4585-4599）。
     *
     * @param error        原始 API 错误（CC error 参数）
     * @param retryInMs    待等待毫秒数（CC retryInMs；持久模式为分片 remaining）
     * @param retryAttempt 本次重试尝试号（CC retryAttempt）
     * @param maxRetries   重试上限（CC maxRetries）
     * @return SystemAPIErrorMessage 结构等价实例
     */
    public static SystemApiErrorMessage createSystemApiErrorMessage(
            Throwable error, long retryInMs, int retryAttempt, int maxRetries) {
        return new SystemApiErrorMessage(
            "system",
            "api_error",
            "error",
            error != null ? error.getCause() : null,
            error,
            retryInMs,
            retryAttempt,
            maxRetries,
            Instant.now().toString(),
            UUID.randomUUID().toString()
        );
    }
}

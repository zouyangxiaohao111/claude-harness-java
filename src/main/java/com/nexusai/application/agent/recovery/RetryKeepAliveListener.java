package com.nexusai.application.agent.recovery;

/**
 * 持久重试 keep-alive 回调 · 对齐 CC withRetry.ts:477-506 分片 sleep 的 yield。
 *
 * <p>CC 在持久分片 sleep 的每个 30s 心跳片前 yield {@code createSystemAPIErrorMessage(...)}，
 * 让宿主环境看到周期性的 stdout 活动、不把 session 标记为 idle（withRetry.ts:486-489 注释意图：
 * "Chunk long sleeps so the host sees periodic stdout activity and does not mark the session idle"）。
 *
 * <p>Java 端以函数式接口承载回调点：每分片 sleep 前调用 {@link #onKeepAlive(long, int, int)}。
 * api_retry 消息面（DTO/事件流载荷）留 ER-IMP-11 填充；本 session 只落回调点 + 默认 no-op
 * （未接线时不产生任何副作用）。
 */
@FunctionalInterface
public interface RetryKeepAliveListener {

    /**
     * keep-alive 回调 · 每个 30s 心跳分片 sleep 前触发。
     *
     * @param retryInMs   当前分片剩余待等待毫秒数（CC yield 的 remaining · withRetry.ts:495）
     * @param retryAttempt 本次重试尝试号（持久模式用 persistentAttempt，CC reportedAttempt · withRetry.ts:467）
     * @param maxRetries  本次 withRetry 的 maxRetries（CC maxRetries · withRetry.ts:498）
     */
    void onKeepAlive(long retryInMs, int retryAttempt, int maxRetries);

    /** 默认 no-op 实现 · 未接线（ER-IMP-11 前）时调用不产生副作用。 */
    static RetryKeepAliveListener noop() {
        return (retryInMs, retryAttempt, maxRetries) -> {
            // no-op：api_retry 消息面载荷由 ER-IMP-11 填充
        };
    }
}

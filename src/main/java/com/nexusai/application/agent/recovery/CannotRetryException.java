package com.nexusai.application.agent.recovery;

/**
 * 重试耗尽 / 不可重试异常 · 对齐 CC withRetry.ts:144-158 {@code class CannotRetryError}。
 *
 * <p>withRetry 重试循环在「耗尽（attempt &gt; maxRetries）」或「分类闸判定不可重试」时抛出，
 * 携带 {@link #getOriginalError()}（原始错误）与 {@link #getRetryContext()}（重试上下文），
 * 使上层（对齐 CC query.ts:996 {@code catch withRetry throw}）能按本类型区分
 * 「重试彻底失败」与「普通流错误」，并据 originalError 降级。
 *
 * <p>行为逐条对齐（grep 自验 Open-ClaudeCode/src/services/api/withRetry.ts）：
 * <ul>
 *   <li>:149 {@code message = errorMessage(originalError)} — message 取原错误消息</li>
 *   <li>:151 {@code this.name = 'RetryError'} — name 显式 {@code RetryError}（与类名不同）</li>
 *   <li>:153-156 {@code if (originalError instanceof Error && originalError.stack)
 *       this.stack = originalError.stack} — 保留原始 stack（整体替换自身 stack）</li>
 *   <li>:146-147 持有 {@code originalError} + {@code retryContext}</li>
 * </ul>
 *
 * <p>Java 的 {@code name='RetryError'} 以 {@link #RETRY_ERROR_NAME} 常量 + {@link #retryErrorName()}
 * 表达（Java 异常无 Error.name 属性）。CC 类继承 Error，Java 用 RuntimeException（可被普通异常捕获）。
 */
public class CannotRetryException extends RuntimeException {

    /** CC original: this.name = 'RetryError' (withRetry.ts:151) */
    public static final String RETRY_ERROR_NAME = "RetryError";

    /** CC original: public readonly originalError (withRetry.ts:146) — 触发耗尽/不可重试的原始错误 */
    private final Throwable originalError;

    /** CC original: public readonly retryContext (withRetry.ts:147) — 耗尽时的重试上下文快照 */
    private final RetryContext retryContext;

    /**
     * @param originalError 原始错误（可为 null，CC errorMessage 对非 Error 有兜底）
     * @param retryContext  重试上下文快照
     */
    public CannotRetryException(Throwable originalError, RetryContext retryContext) {
        // CC withRetry.ts:149 message = errorMessage(originalError)
        super(originalError != null ? originalError.getMessage() : "CannotRetryError");
        this.originalError = originalError;
        this.retryContext = retryContext;
        // CC withRetry.ts:153-156 保留原始 stack trace（整体替换自身）
        if (originalError != null && originalError.getStackTrace() != null) {
            setStackTrace(originalError.getStackTrace());
        }
    }

    /** 原始错误 · CC withRetry.ts:146 originalError */
    public Throwable getOriginalError() {
        return originalError;
    }

    /** 重试上下文快照 · CC withRetry.ts:147 retryContext */
    public RetryContext getRetryContext() {
        return retryContext;
    }

    /**
     * Error.name 等价 · CC withRetry.ts:151 {@code this.name = 'RetryError'}。
     *
     * <p>Java 异常无 name 属性，以方法暴露 CC 的显式 name 契约。
     */
    public String retryErrorName() {
        return RETRY_ERROR_NAME;
    }
}

package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.application.agent.recovery.SystemApiErrorMessage;
import com.nexusai.infra.llm.LlmApiException;

/**
 * api_retry 事件 · 对齐 CC QueryEngine.ts:943-955 SDK 载荷。
 *
 * <p>CC（grep 自验 946-955）：SystemAPIErrorMessage（subtype 'api_error'）经 headless SDK 转换为：
 * <pre>
 * yield {
 *   type: 'system',
 *   subtype: 'api_retry',
 *   attempt: message.retryAttempt,
 *   max_retries: message.maxRetries,
 *   retry_delay_ms: message.retryInMs,
 *   error_status: message.error.status ?? null,
 *   error: categorizeRetryableAPIError(message.error),
 *   session_id: getSessionId(),
 *   uuid: message.uuid,
 * }
 * </pre>
 *
 * <p>Java 端经 STOMP wsTemplate 推送（{@code /topic/sessions/{sessionId}/stream}，会话级单 topic），
 * 前端据此展示「重试中（第 N 次）」。后端事件生成优先，前端展示登记 TODO（决策 #7）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiRetryEvent extends StreamEvent {

    /** 恒 'api_retry'（CC QueryEngine.ts:947 subtype） */
    private final String subtype;
    /** 本次重试尝试号（CC attempt · QueryEngine.ts:948） */
    private final int attempt;
    /** 重试上限（CC max_retries · QueryEngine.ts:949） */
    private final int maxRetries;
    /** 重试间隔毫秒（CC retry_delay_ms · QueryEngine.ts:950） */
    private final long retryDelayMs;
    /** 错误 HTTP 状态（CC error_status ?? null · QueryEngine.ts:951） */
    private final Integer errorStatus;
    /** categorizeRetryableAPIError 分类（CC error · QueryEngine.ts:952） */
    private final String error;
    /** CC uuid · QueryEngine.ts:954 */
    private final String uuid;

    public ApiRetryEvent(String sessionId, String userMessageId, String subtype, int attempt,
                         int maxRetries, long retryDelayMs, Integer errorStatus, String error, String uuid) {
        super("api_retry", sessionId, userMessageId);
        this.subtype = subtype;
        this.attempt = attempt;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.errorStatus = errorStatus;
        this.error = error;
        this.uuid = uuid;
    }

    /**
     * 工厂 · 从 SystemAPIErrorMessage 构建 ApiRetryEvent（等价 QueryEngine.ts:943-955 转换）。
     *
     * @param sessionId     会话 ID（CC getSessionId · QueryEngine.ts:953）
     * @param userMessageId 用户消息 ID（stream topic 路由）
     * @param sys           已 yield 的 SystemAPIErrorMessage（retryAttempt/maxRetries/retryInMs/error/uuid）
     * @return api_retry 事件
     */
    public static ApiRetryEvent of(String sessionId, String userMessageId, SystemApiErrorMessage sys) {
        Integer status = null;
        if (sys.error() instanceof LlmApiException ex) {
            status = ex.status();
        }
        return new ApiRetryEvent(
            sessionId, userMessageId, "api_retry",
            sys.retryAttempt(), sys.maxRetries(), sys.retryInMs(), status,
            ApiErrors.categorizeRetryableApiError(sys.error()), sys.uuid());
    }

    public String getSubtype() { return subtype; }
    public int getAttempt() { return attempt; }
    public int getMaxRetries() { return maxRetries; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public Integer getErrorStatus() { return errorStatus; }
    public String getError() { return error; }
    public String getUuid() { return uuid; }
}

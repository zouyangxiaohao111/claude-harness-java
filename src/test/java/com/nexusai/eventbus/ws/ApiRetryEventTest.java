package com.nexusai.eventbus.ws;

import com.nexusai.application.agent.recovery.SystemApiErrorMessage;
import com.nexusai.infra.llm.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiRetryEvent 定向测试 · 对齐 CC QueryEngine.ts:943-955 api_retry SDK 载荷。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: withRetry.ts:493/510 yield 的 SystemAPIErrorMessage
 * 经 QueryEngine 转换为 {@code {type:'system', subtype:'api_retry', attempt, max_retries, retry_delay_ms,
 * error_status, error: categorizeRetryableAPIError(...), session_id, uuid}}。Java 端 ApiRetryEvent 是
 * 该载荷的 eventbus/ws 载体（前端「重试中（第N次）」据此展示）；错映射会让前端拿不到 retry 状态。
 */
class ApiRetryEventTest {

    private LlmApiException api(int status, String body) {
        return new LlmApiException(status, Collections.emptyMap(), body);
    }

    @Test
    @DisplayName("of()：SystemAPIErrorMessage → api_retry 载荷（attempt/max_retries/retry_delay_ms/error_status/error/uuid）")
    void fromSystemApiErrorMessage() {
        SystemApiErrorMessage sys = SystemApiErrorMessage.createSystemApiErrorMessage(
            api(429, "rate limit"), 5000L, 2, 10);
        ApiRetryEvent evt = ApiRetryEvent.of("sess-1", "um-1", sys);

        assertThat(evt.getType()).isEqualTo("api_retry");
        assertThat(evt.getSubtype()).isEqualTo("api_retry");
        assertThat(evt.getAttempt()).isEqualTo(2);
        assertThat(evt.getMaxRetries()).isEqualTo(10);
        assertThat(evt.getRetryDelayMs()).isEqualTo(5000L);
        assertThat(evt.getErrorStatus()).isEqualTo(429);
        assertThat(evt.getError()).isEqualTo("rate_limit"); // categorizeRetryableAPIError(429) → rate_limit
        assertThat(evt.getUuid()).isEqualTo(sys.uuid());
        assertThat(evt.getSessionId()).isEqualTo("sess-1");
        assertThat(evt.getUserMessageId()).isEqualTo("um-1");
    }

    @Test
    @DisplayName("of()：非 LlmApiException → error_status=null，error=unknown")
    void unknownStatusWhenNotApiException() {
        SystemApiErrorMessage sys = SystemApiErrorMessage.createSystemApiErrorMessage(
            new RuntimeException("plain"), 1000L, 1, 10);
        ApiRetryEvent evt = ApiRetryEvent.of("sess-1", "um-1", sys);

        assertThat(evt.getErrorStatus()).isNull();
        assertThat(evt.getError()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("createSystemApiErrorMessage 结构：type=system/subtype=api_error/level=error（messages.ts:4588-4590）")
    void systemApiErrorMessageStructure() {
        LlmApiException err = api(529, "overloaded");
        SystemApiErrorMessage sys = SystemApiErrorMessage.createSystemApiErrorMessage(err, 30_000L, 3, 10);

        assertThat(sys.type()).isEqualTo("system");
        assertThat(sys.subtype()).isEqualTo("api_error");
        assertThat(sys.level()).isEqualTo("error");
        assertThat(sys.error()).isSameAs(err);
        assertThat(sys.retryInMs()).isEqualTo(30_000L);
        assertThat(sys.retryAttempt()).isEqualTo(3);
        assertThat(sys.maxRetries()).isEqualTo(10);
        assertThat(sys.timestamp()).isNotBlank();
        assertThat(sys.uuid()).isNotBlank();
    }
}

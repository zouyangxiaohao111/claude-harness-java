package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.recovery.ErrorClassifier;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.eventbus.ws.ApiRetryEvent;
import com.nexusai.infra.llm.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FIX-14 / FIX-20 定向测试 · 对齐 CC withRetry.ts:492/508 + :519-528。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>:
 * <ol>
 *   <li><b>FIX-20</b> — CC withRetry.ts:492/508 {@code if (error instanceof APIError) { yield createSystemAPIErrorMessage(...) }}。
 *       CC {@code APIError}（Anthropic SDK 错误基类）含<b>两类</b>（读 CC 源码自验：withRetry.ts:753
 *       {@code if (error instanceof APIConnectionError) return true} 位于 {@code shouldRetry(error: APIError)}
 *       内证明 APIConnectionError extends APIError）：
 *       <ul>
 *         <li>HTTP status 错误（429/500/529…）→ Java {@link LlmApiException} → <b>推送</b>；</li>
 *         <li>连接错误（CC APIConnectionError）→ Java IOException/SocketException
 *             （{@link ErrorClassifier#isConnectionError} 等价）→ <b>推送</b>
 *             （CC 对连接错误同样 yield，error_status=null、error="unknown"）。</li>
 *       </ul>
 *       仅"真非 API 错误"（任意 RuntimeException/逻辑错误 —— CC :379 抛 CannotRetryError 根本不进退避）
 *       → <b>跳过</b>。本测试锁定 {@code LlmApiException/连接错误 → 推送 / 真非 API 错误 → 跳过}。</li>
 *   <li><b>FIX-14</b> — CC withRetry.ts:519-528 getRetryAfter 从 {@code APIError.headers['retry-after']}
 *       提取 header。Java 侧 streamError 必须保留原始 {@link LlmApiException} 对象（含 headers），
 *       否则下游 {@link ErrorClassifier#extractRetryAfterSeconds} 拿不到 retry-after，429/529
 *       退避无法按 header 精确等待。本测试锁定 {@link LlmAgentLoop#selectStreamError} 在
 *       capturedError[0] 非空时返回同一 LlmApiException（headers 可提取）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 若 yieldApiRetryMessage 去掉 instanceof gate → test_apiErrorPushes /
 * test_connectionErrorPushes 必须红（LlmApiException 分支）；若 gate 只认 LlmApiException 不认连接错误
 * → test_connectionErrorPushes 必须红；若 selectStreamError 用 {@code new RuntimeException(String)}
 * 包装原始异常 → test_retryAfterHeaderExtractable 必须红。
 */
class LlmAgentLoopFix1420Test {

    private AgentLoopContext ctxWithWs(SimpMessagingTemplate ws) {
        // wsTemplate(16) / streamTopic(17) / streamSessionId(18) / streamUserMessageId(19) / featureFlags(20)
        return new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            ws, "topic", "sess", "um",
            FeatureFlags.ALL_DISABLED,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // FIX-20: api_retry instanceof gate · CC withRetry.ts:492/508
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("FIX-20: LlmApiException → 推送 api_retry（CC withRetry.ts:492/508 if(error instanceof APIError)）")
    void apiErrorPushesApiRetry() {
        SimpMessagingTemplate ws = Mockito.mock(SimpMessagingTemplate.class);
        AgentLoopContext ctx = ctxWithWs(ws);

        LlmAgentLoop.yieldApiRetryMessage(ctx, 5000L, 2, 10,
            new LlmApiException(429, Map.of("retry-after", List.of("120")), "rate"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(eq("topic"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(ApiRetryEvent.class);
    }

    @Test
    @DisplayName("FIX-20: 连接错误（IOException=CC APIConnectionError 等价）→ 推送 api_retry；真非 API 错误（逻辑 RuntimeException）→ 跳过")
    void connectionErrorPushesButNonApiErrorSkips() {
        SimpMessagingTemplate ws = Mockito.mock(SimpMessagingTemplate.class);
        AgentLoopContext ctx = ctxWithWs(ws);

        // 第 1 次：HTTP API 错误 LlmApiException → 推送
        LlmAgentLoop.yieldApiRetryMessage(ctx, 5000L, 2, 10,
            new LlmApiException(429, Map.of(), "rate"));
        // 第 2 次：连接错误（CC APIConnectionError instanceof APIError 等价）→ 也推送
        //   （CC withRetry.ts:753 APIConnectionError→shouldRetry true，:492/508 instanceof APIError 通过）
        LlmAgentLoop.yieldApiRetryMessage(ctx, 5000L, 2, 10,
            new java.io.IOException("connection refused"));
        // 第 3 次：真非 API 错误（纯逻辑 RuntimeException，无连接特征）→ gate 跳过，不推送
        LlmAgentLoop.yieldApiRetryMessage(ctx, 5000L, 2, 10,
            new RuntimeException("some internal logic error"));

        // 全程 2 次推送（API 错误 + 连接错误）；真非 API 错误被 gate 拦截
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws, times(2)).convertAndSend(eq("topic"), payload.capture());
        assertThat(payload.getAllValues())
            .allSatisfy(p -> assertThat(p).isInstanceOf(ApiRetryEvent.class));
    }

    // ════════════════════════════════════════════════════════════════════
    // FIX-14: streamError 保留 LlmApiException headers · CC withRetry.ts:519-528
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("FIX-14: capturedError[0] 为 LlmApiException → streamError 同实例返回，retry-after header 可提取")
    void retryAfterHeaderExtractable() {
        LlmApiException original = new LlmApiException(429,
            Map.of("retry-after", List.of("120")), "rate");

        // err 回调已捕获原始异常（capturedError[0]）
        Throwable streamError = LlmAgentLoop.selectStreamError(original, "rate");

        assertThat(streamError)
            .as("capturedError[0] 非空时必须返回原始异常对象（保留 LlmApiException headers）")
            .isSameAs(original);
        assertThat(ErrorClassifier.extractRetryAfterSeconds((LlmApiException) streamError))
            .as("streamError 保留 headers → Retry-After=120s 可提取（CC withRetry.ts:519-528）")
            .isEqualTo(120L);
    }

    @Test
    @DisplayName("FIX-14: capturedError[0]==null（合成错误）→ 仅包装 message，无 headers 可取（非 API 错误路径）")
    void syntheticErrorWrapsMessageOnly() {
        Throwable streamError = LlmAgentLoop.selectStreamError(null, "stream timeout");

        assertThat(streamError).isInstanceOf(RuntimeException.class);
        assertThat(streamError.getMessage()).isEqualTo("stream timeout");
    }
}

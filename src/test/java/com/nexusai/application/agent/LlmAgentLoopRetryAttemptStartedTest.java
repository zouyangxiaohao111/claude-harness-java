package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.eventbus.ws.ApiRetryEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * [retry-visible] 「第 N 次尝试已发起」事件守门测试 · {@link LlmAgentLoop#yieldRetryAttemptStarted}。
 *
 * <p><b>WHY（用户实测）</b>：「长期没有回应、指数退避没显示」。真凶是**单次上游调用本身**静默挂了几分钟
 * （实测一次 3m45s 无字节），而原来的 {@code api_retry} 只在**失败后、退避 sleep 前**推 ⇒ 横幅只活
 * 0.6~17 秒，长静默期反而什么都不显示。本事件在**每次尝试发起前**推一条 {@code retryDelayMs=0}，
 * 让前端从发起到出结果全程显示「第 N/10 次 · 已等待 Xs」。
 *
 * <p><b>判据</b>：
 * <ul>
 *   <li>推送的载荷是 {@link ApiRetryEvent}，{@code attempt/maxRetries} 忠实透传，
 *       {@code retryDelayMs == 0}（前端据此区分「退避倒计时」与「尝试进行中」）；</li>
 *   <li>⛔ <b>不做</b> APIError 资格闸：本事件是尝试起点标记，与错误类型无关
 *       （对照 {@code yieldApiRetryMessage} 的 {@code isApiRetryEligible} 闸）；</li>
 *   <li>ws / topic 缺失 → 静默跳过（不得抛）。</li>
 * </ul>
 */
class LlmAgentLoopRetryAttemptStartedTest {

    private AgentLoopContext ctxWithWs(SimpMessagingTemplate ws) {
        return new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            ws, "topic", "sess", "um",
            FeatureFlags.ALL_DISABLED,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("推送 api_retry 起点事件：attempt/maxRetries 透传且 retryDelayMs=0（否则前端会误判成倒计时）")
    void pushesAttemptStartedWithZeroDelay() {
        SimpMessagingTemplate ws = Mockito.mock(SimpMessagingTemplate.class);

        LlmAgentLoop.yieldRetryAttemptStarted(ctxWithWs(ws), 3, 10);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(eq("topic"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(ApiRetryEvent.class);
        ApiRetryEvent evt = (ApiRetryEvent) payload.getValue();
        assertThat(evt.getAttempt()).isEqualTo(3);
        assertThat(evt.getMaxRetries()).isEqualTo(10);
        assertThat(evt.getRetryDelayMs()).isZero();
        assertThat(evt.getErrorStatus()).isNull();   // 起点标记无错误面
    }

    @Test
    @DisplayName("ws 缺失 → 静默跳过（非流式/单测路径不得抛）")
    void silentlySkipsWithoutWs() {
        LlmAgentLoop.yieldRetryAttemptStarted(ctxWithWs(null), 1, 10);
        // 无 ws 时 Context 内部 wsTemplate 为 null；此处仅断言不抛
    }

    @Test
    @DisplayName("ctx 为 null → 不抛（防御）")
    void nullCtxSafe() {
        LlmAgentLoop.yieldRetryAttemptStarted(null, 1, 10);
        SimpMessagingTemplate ws = Mockito.mock(SimpMessagingTemplate.class);
        verifyNoInteractions(ws);
    }
}

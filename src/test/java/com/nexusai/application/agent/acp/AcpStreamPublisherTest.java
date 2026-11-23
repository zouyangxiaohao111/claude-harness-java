package com.nexusai.application.agent.acp;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.eventbus.ws.MessageToolCallEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时推送器 · 取不到 assistantMessageId 时不推（不伪造锚点）；推到会话 stream topic 且盖 seq。
 */
@DisplayName("[acp] AcpStreamPublisher")
class AcpStreamPublisherTest {

    @Test
    @DisplayName("wsTemplate 未注入 ⇒ 静默跳过（fail-soft）")
    void noTemplateSkips() {
        AcpStreamPublisher publisher = new AcpStreamPublisher();
        publisher.toolCall("sess-x", "msg-1", "t-1", "Bash", Map.of("command", "ls"));
    }

    @Test
    @DisplayName("assistantMessageId 为空 ⇒ 不推送（不伪造锚点）")
    void missingAssistantIdSkips() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        AcpStreamPublisher publisher = new AcpStreamPublisher();
        publisher.setWsTemplate(ws);

        publisher.toolCall("sess-x", null, "t-1", "Bash", Map.of("command", "ls"));

        verify(ws, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    @DisplayName("resolveAssistantMessageId：注册表未注入 / 无在飞 state ⇒ null")
    void resolveReturnsNullWhenUnavailable() {
        AcpStreamPublisher publisher = new AcpStreamPublisher();
        assertThat(publisher.resolveAssistantMessageId("sess-x")).isNull();

        SessionAgentStateRegistry registry = mock(SessionAgentStateRegistry.class);
        when(registry.get("sess-x")).thenReturn(null);
        publisher.setStateRegistry(registry);
        assertThat(publisher.resolveAssistantMessageId("sess-x")).isNull();
    }

    @Test
    @DisplayName("assistantMessageId 可用 ⇒ 推到 /topic/sessions/{sid}/stream 且盖 seq")
    void publishesWithSeq() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SessionAgentStateRegistry registry = mock(SessionAgentStateRegistry.class);
        AgentState state = mock(AgentState.class);
        String sessionId = "sess-" + UUID.randomUUID();
        when(registry.get(sessionId)).thenReturn(state);
        when(state.currentAssistantMessageId()).thenReturn("msg-1");
        AcpStreamPublisher publisher = new AcpStreamPublisher();
        publisher.setWsTemplate(ws);
        publisher.setStateRegistry(registry);

        publisher.toolCall(sessionId, "msg-1", "t-1", "Bash", Map.of("command", "ls"));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/sessions/" + sessionId + "/stream"),
                event.capture());
        MessageToolCallEvent sent = (MessageToolCallEvent) event.getValue();
        assertThat(sent.getToolCallId()).isEqualTo("t-1");
        assertThat(sent.getAssistantMessageId()).isEqualTo("msg-1");
        assertThat(sent.getSeq()).isGreaterThan(0L);
    }
}

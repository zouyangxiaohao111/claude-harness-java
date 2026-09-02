package com.nexusai.application.agent.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.explainer.PermissionExplanation;
import com.nexusai.application.agent.permission.explainer.PermissionExplainer;
import com.nexusai.application.agent.permission.explainer.RiskLevel;
import com.nexusai.domain.session.MessageService;
import com.nexusai.eventbus.ws.PermissionExplanationEvent;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * [REV-FIX-5 缝隙3] 权限解释经 STOMP 通道生产可达 · 对齐 CC PermissionExplanation.tsx
 * Ctrl+E 惰性触发（generatePermissionExplanation，permissionExplainer.ts:147-250）。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: WF3-04 曾将 {@code explainPermissionRequest} 建成
 * 接线点占位（零生产调用点）。本测试证明前端弹窗"解释"→ {@code explainAndSend} → STOMP
 * 推送 {@code /topic/sessions/{sessionId}/permission-explanations} 四字段事件的完整链路，
 * 且 null（解释器未注入 / 门控关闭 / 生成失败）→ {@code unavailable} 事件
 * （CC「Explanation unavailable」:161-166，无降级文案）。
 *
 * @see WebSocketPermissionPrompter#explainAndSend
 * @see PermissionExplanationEvent
 * @since REV-FIX-5 缝隙3
 */
class WebSocketPermissionPrompterExplainStompTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "session-1";
    private static final String TOPIC =
        "/topic/sessions/" + SESSION_ID + "/permission-explanations";

    private static JsonNode bashInput() {
        return JSON.createObjectNode().put("command", "rm -rf /tmp/x");
    }

    private static ChatMessageDto assistant(String content) {
        return new ChatMessageDto(null, null, Role.assistant, null, content,
            null, null, null, null, null, null, null, null, null, null,
            List.of(), List.of());
    }

    private static PermissionExplainer enabledExplainerReturning(PermissionExplanation result) {
        PermissionExplainer explainer = mock(PermissionExplainer.class);
        when(explainer.isPermissionExplainerEnabled()).thenReturn(true);
        // [session-id-short] explainPermissionRequest 直传 short sessionId（原 parseSessionUuid(null)
        // 恒失效使 mock isNull() 匹配；现在恒非 null → eq(SESSION_ID)）
        when(explainer.generatePermissionExplanation(eq(SESSION_ID), anyString(), any(), any(), any(), any()))
            .thenReturn(result);
        return explainer;
    }

    /** 捕获 convertAndSend 的 payload（异步执行 → Mockito timeout 轮询等待）。 */
    private static PermissionExplanationEvent capturePushedEvent(SimpMessagingTemplate ws) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws, timeout(5_000)).convertAndSend(eq(TOPIC), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(PermissionExplanationEvent.class);
        return (PermissionExplanationEvent) payload.getValue();
    }

    // ── N1: explainPermissionRequest 经 STOMP 可调用（四字段全推送）──

    @Test
    @DisplayName("解释成功 → 推 /topic/.../permission-explanations 四字段事件")
    void explanationReachableViaStompPushesFourFields() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        PermissionExplanation generated = new PermissionExplanation(
            RiskLevel.HIGH, "Deletes the file permanently",
            "I need to remove an obsolete file", "Data loss is irreversible");
        prompter.setPermissionExplainerForTesting(enabledExplainerReturning(generated));

        prompter.explainAndSend(SESSION_ID, "req-1", "Bash", bashInput(), "Delete files");

        PermissionExplanationEvent event = capturePushedEvent(ws);
        assertThat(event.getRequestId()).isEqualTo("req-1");
        assertThat(event.isAvailable()).isTrue();
        assertThat(event.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(event.getExplanation()).isEqualTo("Deletes the file permanently");
        assertThat(event.getReasoning()).isEqualTo("I need to remove an obsolete file");
        assertThat(event.getRisk()).isEqualTo("Data loss is irreversible");
        // topic 由 explanationTopicFor 构造（静态方法暴露供断言）
        assertThat(WebSocketPermissionPrompter.explanationTopicFor(SESSION_ID)).isEqualTo(TOPIC);
    }

    // ── 消息源：后端 MessageService 取会话消息（不从前端透传防伪造上下文）──

    @Test
    @DisplayName("MessageService 注入 → 会话消息传给 explainer（后端取上下文）")
    void sessionMessagesFromMessageServiceReachExplainer() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        PermissionExplainer explainer = enabledExplainerReturning(new PermissionExplanation(
            RiskLevel.LOW, "Lists files", "I need to inspect", "None"));
        prompter.setPermissionExplainerForTesting(explainer);
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_ID))
            .thenReturn(List.of(assistant("recent assistant reasoning")));
        prompter.setMessageServiceForTesting(messageService);

        prompter.explainAndSend(SESSION_ID, "req-2", "Bash", bashInput(), null);

        // 等异步生成完成后捕获消息列表（消息从 MessageService 后端取，非前端透传）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessageDto>> msgs = ArgumentCaptor.forClass(List.class);
        // [session-id-short] explainer 收到 short sessionId（原 parseSessionUuid(null) 恒失效 → isNull()）
        verify(explainer, timeout(5_000)).generatePermissionExplanation(
            eq(SESSION_ID), eq("Bash"), any(JsonNode.class), isNull(), msgs.capture(), isNull());
        assertThat(msgs.getValue()).hasSize(1);
        assertThat(msgs.getValue().get(0).content()).contains("recent assistant reasoning");
        capturePushedEvent(ws);
    }

    // ── null 语义：解释器返回 null → unavailable 事件（CC「Explanation unavailable」）──

    @Test
    @DisplayName("生成失败（null）→ available=false 事件，无四字段")
    void nullExplanationSendsUnavailableEvent() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        prompter.setPermissionExplainerForTesting(enabledExplainerReturning(null));

        prompter.explainAndSend(SESSION_ID, "req-3", "Bash", bashInput(), null);

        PermissionExplanationEvent event = capturePushedEvent(ws);
        assertThat(event.getRequestId()).isEqualTo("req-3");
        assertThat(event.isAvailable()).isFalse();
        assertThat(event.getRiskLevel()).isNull();
        assertThat(event.getExplanation()).isNull();
        assertThat(event.getReasoning()).isNull();
        assertThat(event.getRisk()).isNull();
    }

    // ── null 语义：explainer 未注入（生产未接线/测试直构）→ unavailable 事件 ──

    @Test
    @DisplayName("explainer 未注入 → available=false 事件（无降级文案）")
    void explainerNotInjectedSendsUnavailableEvent() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        // 不注入 permissionExplainer → explainPermissionRequest 恒 null → unavailable

        prompter.explainAndSend(SESSION_ID, "req-4", "Bash", bashInput(), null);

        PermissionExplanationEvent event = capturePushedEvent(ws);
        assertThat(event.isAvailable()).isFalse();
        assertThat(event.getRequestId()).isEqualTo("req-4");
    }
}

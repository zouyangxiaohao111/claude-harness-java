package com.nexusai.apis.session;

import com.nexusai.application.agent.compact.PartialCompactService;
import com.nexusai.application.agent.tasks.MainSessionBackgroundService;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [gap28] ChatController.trimAfter 意图测试 · DELETE /api/v1/sessions/{sessionId}/messages/after/{messageId}。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: 对话裁剪的 REST 载体 —— 前端「裁剪到某点」需
 * ① 收到裁剪后消息列表（前端 setMessages）+ ② 新 conversationId（前端 row key 刷新，对齐 CC
 * REPL.tsx:3673 setConversationId(randomUUID())）+ ③ in-flight 流先 cancel（对齐 CC REPL.tsx:3777-3780
 * 「cancel first (idempotent)」，防本 turn 消息追加与裁剪竞态）。
 * 变异点：
 * <ul>
 *   <li>不 cancel in-flight → 流式追加与裁剪竞态（被删消息事后回流）→ 红</li>
 *   <li>不旋转 conversationId → 前端 row key 不变 → 消息列表不刷新 → 红</li>
 *   <li>pivot/session 不存在静默 200 → 前端无法区分 → 红</li>
 * </ul>
 */
@DisplayName("[gap28] ChatController 对话裁剪端点（trimAfter）")
class ChatControllerTrimAfterTest {

    private ChatController controller;
    private MessageService messageService;
    private ChatService chatService;
    private SessionService sessionService;
    private SimpMessagingTemplate wsTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        messageService = mock(MessageService.class);
        chatService = mock(ChatService.class);
        sessionService = mock(SessionService.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(controller, "messageService", messageService);
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        ReflectionTestUtils.setField(controller, "wsTemplate", wsTemplate);
        ReflectionTestUtils.setField(controller, "partialCompactService", mock(PartialCompactService.class));
        ReflectionTestUtils.setField(controller, "mainSessionBackgroundService", mock(MainSessionBackgroundService.class));
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static ChatMessageDto message(String id) {
        return new ChatMessageDto(
            id, "sess-1", Role.user, null, "内容", null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of(),
            null, false, false);
    }

    @Test
    @DisplayName("DELETE .../messages/after/{pivot} → 200 + 裁剪后消息列表 + 新 conversationId")
    void trim_returnsKeptAndNewConversationId() throws Exception {
        // WHY: 响应结构对齐 PartialCompactResponse（messages + conversationId，REPL.tsx:4964/4971），
        //   前端 setMessages + row key 刷新。变异点：不返回新 conversationId → 前端 row key 不变 → 红。
        ChatMessageDto kept = message("m1").withReasoningDurationMs(1200L);
        when(messageService.trimSessionAfter("sess-1", "m2")).thenReturn(List.of(kept));
        when(chatService.cancelSession("sess-1", wsTemplate)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/sessions/sess-1/messages/after/m2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages.length()").value(1))
            .andExpect(jsonPath("$.messages[0].id").value("m1"))
            .andExpect(jsonPath("$.messages[0].reasoningDurationMs").value(1200))
            .andExpect(jsonPath("$.conversationId").isNotEmpty());

        // conversationId 旋转（对齐 CC REPL.tsx:3673 setConversationId(randomUUID())）
        ArgumentCaptor<String> cidCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionService).updateConversationId(org.mockito.ArgumentMatchers.eq("sess-1"), cidCaptor.capture());
        assertThat(cidCaptor.getValue()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("裁剪前先 cancel in-flight 流（cancel-first，对齐 CC REPL.tsx:3777-3780）")
    void trim_cancelsInflightBeforeTrim() throws Exception {
        // WHY: CC messageActionCaps.edit 先 onCancel() 再 rewind——rewindConversationTo 的 setMessages
        //   与流式追加竞态，cancel-first（幂等）防被删消息事后回流。变异点：不 cancel → 竞态 → 红。
        when(messageService.trimSessionAfter("sess-1", "m2")).thenReturn(List.of(message("m1")));
        when(chatService.cancelSession("sess-1", wsTemplate)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/sessions/sess-1/messages/after/m2"))
            .andExpect(status().isOk());

        verify(chatService).cancelSession("sess-1", wsTemplate);
    }

    @Test
    @DisplayName("pivot 不存在 → 404（NotFound 透传）")
    void trim_pivotMissing_returns404() throws Exception {
        when(chatService.cancelSession("sess-1", wsTemplate)).thenReturn(true);
        when(messageService.trimSessionAfter("sess-1", "ghost"))
            .thenThrow(new NotFoundException("Message ghost not found"));

        mockMvc.perform(delete("/api/v1/sessions/sess-1/messages/after/ghost"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("user 消息 reasoningDurationMs 为 null（无 reasoning 容错，出站字段存在但 null）")
    void userMessage_reasoningDurationMsNull() throws Exception {
        // WHY (CLAUDE.md 规则 9): 无 reasoning 的消息 reasoningDurationMs 恒 null（前端 null=无数据）。
        //   user 消息落库时恒 null（createUserMessage），出站 JSON 字段存在但值为 null。变异点：
        //   user 消息误写非 null / 字段缺失 → 红。
        when(messageService.trimSessionAfter("sess-1", "m2")).thenReturn(List.of(message("m1")));
        when(chatService.cancelSession("sess-1", wsTemplate)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/sessions/sess-1/messages/after/m2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].reasoningDurationMs")
                .value(org.hamcrest.Matchers.nullValue()));
    }
}

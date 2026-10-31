package com.nexusai.apis.session;

import com.nexusai.application.agent.compact.PartialCompactService;
import com.nexusai.application.agent.tasks.MainSessionBackgroundService;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [gap28 · 性能改造] ChatController.trimAfter 意图测试 · DELETE /api/v1/sessions/{sessionId}/messages/after/{messageId}。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: 对话裁剪的 REST 载体 —— 前端「裁剪到某点」需
 * ① 拿到新 conversationId（前端 row key 刷新，对齐 CC REPL.tsx:3673 setConversationId(randomUUID())）
 * + ② in-flight 流先 cancel（对齐 CC REPL.tsx:3777-3780「cancel first (idempotent)」，
 * 防本 turn 消息追加与裁剪竞态）+ ③ <b>轻量响应</b>（不再回传全量 messages DTO ——
 * 会话万级消息时几万条 JSON 的序列化/传输本身就是裁剪卡顿的一环；前端改为裁剪后重拉窗口）。
 * 变异点：
 * <ul>
 *   <li>不 cancel in-flight → 流式追加与裁剪竞态（被删消息事后回流）→ 红</li>
 *   <li>不旋转 conversationId → 前端 row key 不变 → 消息列表不刷新 → 红</li>
 *   <li>pivot/session 不存在静默 200 → 前端无法区分 → 红</li>
 *   <li>响应仍回传全量 messages → 裁剪卡顿依旧 → 红</li>
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

    @Test
    @DisplayName("DELETE .../messages/after/{pivot} → 200 + 新 conversationId + 裁掉的条数（轻量响应）")
    void trim_returnsConversationIdAndDeletedCount() throws Exception {
        // WHY: 响应结构 = { conversationId, deletedCount }（轻量）。变异点：仍回传全量 messages
        //   → 几万条 JSON 序列化 = 裁剪卡顿的一环 → 红（本用例断言 messages 字段不存在）。
        when(messageService.trimSessionAfter("sess-1", "m2")).thenReturn(7);
        when(chatService.cancelSession("sess-1", wsTemplate)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/sessions/sess-1/messages/after/m2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversationId").isNotEmpty())
            .andExpect(jsonPath("$.deletedCount").value(7))
            .andExpect(jsonPath("$.messages").doesNotExist());

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
        when(messageService.trimSessionAfter("sess-1", "m2")).thenReturn(1);
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
    @DisplayName("裁剪到首条 → 200 且 deletedCount = 全部条数（全删，不报错）")
    void trim_pivotFirst_returnsAllDeleted() throws Exception {
        // WHY: pivot 是首条 = 全删（CC slice(0,0)）——端点必须 200 且如实报告删了多少条。
        when(messageService.trimSessionAfter(anyString(), anyString())).thenReturn(42);
        when(chatService.cancelSession("sess-1", wsTemplate)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/sessions/sess-1/messages/after/m1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedCount").value(42));
    }
}

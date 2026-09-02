package com.nexusai.apis.session;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P5-②] ChatController.send busy 分支 immediate local-jsx 命令 busy 优先测试 ·
 * 对齐 CC handlePromptSubmit.ts:239-252（queryGuard.isActive 优先语义）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>busy + immediate local-jsx 命令不得入队</b>——CC 对 immediate 命令在
 *       {@code queryGuard.isActive} 时直接 load+call（清除输入 + 通知），不 enqueue。
 *       Java 旧行为把一切 busy 输入（含 immediate 命令）入队 → immediate 命令被错误排队等待，
 *       违背 CC「busy 优先」语义。若本测试通过则证明 controller busy 分支命中 immediate →
 *       调 {@code chatService.dispatchImmediateLocalJsx} 且<b>不</b>调 {@code enqueueBusyPrompt}。</li>
 *   <li><b>未注册命名 handler 的 immediate 命令回落原 busy 排队</b>——fail loud（ChatService 内
 *       log.warn），controller 不吞掉排队路径（回归防线）。</li>
 * </ol>
 */
@DisplayName("[P5-②] immediate 命令 busy 优先（ChatController.send busy 分支）")
class ChatControllerImmediateBusyTest {

    private ChatController controller;
    private ChatService chatService;
    private SimpMessagingTemplate wsTemplate;
    private NotificationQueue notificationQueue;
    private QueueEventPublisher queueEventPublisher;
    private String sid;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        chatService = mock(ChatService.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
        notificationQueue = mock(NotificationQueue.class);
        queueEventPublisher = mock(QueueEventPublisher.class);
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        ReflectionTestUtils.setField(controller, "wsTemplate", wsTemplate);
        ReflectionTestUtils.setField(controller, "messageService", mock(MessageService.class));
        ReflectionTestUtils.setField(controller, "sessionService", mock(SessionService.class));
        ReflectionTestUtils.setField(controller, "notificationQueue", notificationQueue);
        ReflectionTestUtils.setField(controller, "queueEventPublisher", queueEventPublisher);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(sid);
    }

    @Test
    @DisplayName("busy + immediate local-jsx → dispatch 立即执行，不 enqueue，queued=false")
    void send_busyImmediate_dispatchBypassesQueue() {
        LlmAgentLoop.markRunning(sid);
        SendMessageRequest req = new SendMessageRequest("/btw info", null, null, null, null, null, null, null, null);
        when(chatService.isImmediateLocalJsxCommand("/btw info")).thenReturn(true);
        when(chatService.dispatchImmediateLocalJsx(anyString(), anyString(), eq("/btw info"), eq(true), eq(wsTemplate)))
            .thenReturn(true);

        MessageCreatedResponse resp = controller.send(sid, req);

        assertThat(resp.queued())
            .as("P5-②: immediate 命令不排队 → queued=false（前端不显示排队框）")
            .isFalse();
        verify(chatService).dispatchImmediateLocalJsx(eq(sid), anyString(), eq("/btw info"), eq(true), eq(wsTemplate));
        verify(chatService, never()).enqueueBusyPrompt(eq(sid), anyString(), eq(req));
        verify(notificationQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("busy + immediate 但无命名 handler（dispatch 返回 false）→ 回落原 busy 排队（fail loud 不吞）")
    void send_busyImmediate_noHandler_fallsBackToEnqueue() {
        LlmAgentLoop.markRunning(sid);
        SendMessageRequest req = new SendMessageRequest("/plugin x", null, null, null, null, null, null, null, null);
        when(chatService.isImmediateLocalJsxCommand("/plugin x")).thenReturn(true);
        when(chatService.dispatchImmediateLocalJsx(anyString(), anyString(), eq("/plugin x"), eq(true), eq(wsTemplate)))
            .thenReturn(false);

        MessageCreatedResponse resp = controller.send(sid, req);

        assertThat(resp.queued())
            .as("P5-②: 无命名 handler → 回落排队（queued=true，前端排队框）")
            .isTrue();
        verify(chatService).enqueueBusyPrompt(eq(sid), anyString(), eq(req));
    }

    @Test
    @DisplayName("busy + 非 immediate → 原排队行为不变（回归防线）")
    void send_busyNonImmediate_enqueuesAsBefore() {
        LlmAgentLoop.markRunning(sid);
        SendMessageRequest req = new SendMessageRequest("normal question", null, null, null, null, null, null, null, null);
        when(chatService.isImmediateLocalJsxCommand("normal question")).thenReturn(false);

        MessageCreatedResponse resp = controller.send(sid, req);

        assertThat(resp.queued()).isTrue();
        verify(chatService).enqueueBusyPrompt(eq(sid), anyString(), eq(req));
    }
}

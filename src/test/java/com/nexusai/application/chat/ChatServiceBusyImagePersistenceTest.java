package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [OD-D5/OD-D13] busy 带图消息落库守卫 + enqueue 携附件 测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>:
 * <ol>
 *   <li><b>AM 回写守卫（reflector MAJOR-3）</b>：busy 带图消息 append 时 lastUserMessageId 仍指向
 *       原 turn user 行（controller 预落库），若无守卫会把 busy 图 imagePasteIds <b>脏写上一 user 行</b>
 *       （图片归属错乱）；busy 图行由 createQueuedUserMessage 8 参 overload 直接落自身行（非 AM 回写）。</li>
 *   <li><b>enqueueBusyPrompt 携图</b>：busy 排队从 req.attachments() 提取 ≤5MB base64 image 随
 *       QueueItem 携带（drain 消费点逐项注册），非 image / 超限大图不携带（端后兜底）。</li>
 * </ol>
 */
@DisplayName("[OD-D5/OD-D13] busy 带图消息落库守卫 + enqueue 携附件")
class ChatServiceBusyImagePersistenceTest {

    private static final String SESSION = "sess-busy-img";
    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private MessageService messageService;
    private SimpMessagingTemplate wsTemplate;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        messageService = mock(MessageService.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "messageService", messageService);
    }

    @Test
    @DisplayName("AM 回写守卫：busy 带图 append 不脏写上一 user 行；busy 图行由 createQueuedUserMessage 8 参 overload 落自身 imagePasteIds")
    void busyImageAppend_amGuardSkipsPrevRow_overloadWritesOwnRow() {
        // GIVEN: 上一轮 user 行 = "msg-user-original"（lastUserMessageId 初值，controller 预落库）；
        //   忙时注入 busy 带图排队消息 msg-queued-b1（登记 injected + imagePasteIds=["1"]）。
        AgentState state = new AgentState("sys", SESSION, null);
        state.addInjectedQueuedMessage("msg-queued-b1", "排队带图消息", "busy-queued");
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user-original");

        ChatMessageDto busyImg = LlmAgentLoop.toMessage(Role.user, "排队带图消息", null, "msg-queued-b1",
            List.of(), List.of("1"), false).withQueuedOrigin("busy-queued");

        // WHEN: append 触发实时落库 user 分支
        state.appendMessage(busyImg);

        // THEN: AM 回写绝不打到上一 user 行（img 行自身由 overload 写入）
        verify(messageService, never()).updateUserImagePasteIds(eq("msg-user-original"), anyList());
        // busy 图行落自身：createQueuedUserMessage 8 参 overload（content=原文，imagePasteIds=["1"]）
        verify(messageService).createQueuedUserMessage(
            eq(SESSION), eq("msg-queued-b1"), eq("排队带图消息"), any(OffsetDateTime.class),
            eq(false), eq("busy-queued"), eq(List.of("1")), isNull());
    }

    @Test
    @DisplayName("enqueueBusyPrompt：req 含 ≤5MB base64 image → QueueItem.attachments 携带；非 image/无附件 → 空")
    void enqueueBusyPrompt_carriesImageAttachments_only() {
        // GIVEN: 空闲 turn 已跑 → busy 排队；req 混合 image(≤5MB base64) + video + path 大图(无 base64)
        NotificationQueue queue = mock(NotificationQueue.class);
        QueueEventPublisher qep = mock(QueueEventPublisher.class);
        ReflectionTestUtils.setField(service, "notificationQueue", queue);
        ReflectionTestUtils.setField(service, "queueEventPublisher", qep);

        List<AttachmentRequest> attachments = List.of(
            new AttachmentRequest("image", "1", "photo.png", "image/png", PNG_BASE64, null),
            new AttachmentRequest("video", "2", "clip.mp4", "video/mp4", "AA==", null),
            new AttachmentRequest("image", "3", "big.png", "image/png", null, "/tmp/big.png"));
        SendMessageRequest req = new SendMessageRequest("忙时带图消息", null, null, attachments,
            null, null, null, null, null);

        service.enqueueBusyPrompt(SESSION, "msg-q-1", req);

        // THEN: enqueue 的 QueueItem.attachments 只含 image base64 项
        ArgumentCaptor<NotificationQueue.QueueItem> captor = ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        NotificationQueue.QueueItem item = captor.getValue();
        assertThat(item.value()).isEqualTo("忙时带图消息");
        assertThat(item.workload()).isEqualTo("busy-queued");
        assertThat(item.sessionId()).isEqualTo(SESSION);
        assertThat(item.uuid()).isEqualTo("msg-q-1");
        assertThat(item.attachments()).hasSize(1);
        assertThat(item.attachments().get(0).contentId()).isEqualTo("1");
        assertThat(item.attachments().get(0).base64()).isEqualTo(PNG_BASE64);
    }

    @Test
    @DisplayName("enqueueBusyPrompt：无附件/空 req → QueueItem.attachments 空（纯文本零变化）")
    void enqueueBusyPrompt_noAttachments_emptyList() {
        NotificationQueue queue = mock(NotificationQueue.class);
        QueueEventPublisher qep = mock(QueueEventPublisher.class);
        ReflectionTestUtils.setField(service, "notificationQueue", queue);
        ReflectionTestUtils.setField(service, "queueEventPublisher", qep);

        SendMessageRequest req = new SendMessageRequest("纯文本忙时消息", null, null, List.of(),
            null, null, null, null, null);
        service.enqueueBusyPrompt(SESSION, "msg-q-2", req);

        ArgumentCaptor<NotificationQueue.QueueItem> captor = ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().attachments()).as("无附件 → 空列表（纯文本行为零变化）").isEmpty();
    }
}

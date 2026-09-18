package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto.UserAttachmentInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [busy 附件快照 · 端后兜底] CronIdleExecutor 消费残留 busy-queued 时把 {@code QueueItem.userAttachments}
 * 透传 {@code MessageService.createQueuedUserMessage(8 参)} → 落 V63 {@code user_attachments}。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：busy 消息有两条落库路径 —— ① mid-turn drain
 * 实时落库（{@code ChatService.persistAppendedMessage}），② 本执行器的端后兜底（当前轮不再调工具、
 * turn 结束才消费，注释自述「仅当前轮不再调工具时才留到 turn 结束」—— <b>常见路径</b>）。
 * 只修 ① 时，②这条路径落出的 user 行 user_attachments 仍恒 NULL ⇒ 用户看到的现象（F5 后气泡附件
 * 胶囊消失）在这条路径上<b>原样复现</b>。本测试锚定「QueueItem.userAttachments → 落库 8 参透传」。
 *
 * <p>同时钉住零变化：无快照（cron / 纯文本 busy）仍走 5 参重载（invoked overload 不变）。
 */
@DisplayName("[busy 附件快照] CronIdleExecutor 端后兜底透传附件快照 → createQueuedUserMessage 8 参")
class CronIdleExecutorBusyAttachmentSnapshotTest {

    private static final String DOCX_MEDIA =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private NotificationQueue queue;
    private CronIdleExecutor executor;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        ReflectionTestUtils.setField(executor, "queueEventPublisher", mock(QueueEventPublisher.class));
        // 同步执行器：executeQueuedInput 的 cronExecutor.execute(...) 直接在当前线程跑
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @Test
    @DisplayName("残留 busy-queued 携非图片附件快照 → 落库 8 参透传快照（端后兜底路径的 F5 气泡附件）")
    void executeQueuedInput_busyQueuedWithSnapshot_persistsSnapshotViaEightArgOverload() {
        com.nexusai.domain.session.MessageService messageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", messageService);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        // 队列项第 13 参 = busy 图片（模型侧通道）· 第 14 参 = 非图片附件快照（落库通道）
        List<AttachmentRequest> busyImages = List.of(
            new AttachmentRequest("image", "5", "shot.png", "image/png", "AAAA", null));
        List<UserAttachmentInfo> snapshot = List.of(
            new UserAttachmentInfo("file", "季度报表.docx", DOCX_MEDIA, null, null),
            new UserAttachmentInfo("video", "clip.mp4", "video/mp4", "77", null));

        String sessionId = "sess-busy-att-snapshot";
        QueueItem cmd = new QueueItem("忙时带附件追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-att", false, "busy-queued", false, null, sessionId,
            null, null, busyImages, snapshot);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        // 落库：content 仍 = cmd.value() 原文（模型侧说明不落库）；快照经 8 参透传
        verify(messageService).createQueuedUserMessage(
            eq(sessionId), eq("msg-queued-att"), eq("忙时带附件追问"), any(OffsetDateTime.class),
            eq(false), isNull() /* queuedOrigin：端后兜底不标 busy-queued */,
            isNull() /* imagePasteIds：busy 图走 AM 回写/doRun 注册链，本处不重复 */,
            eq(snapshot));
        verify(loop).run(any());
    }

    @Test
    @DisplayName("无快照（纯文本 busy / cron）→ 仍走 5 参重载（invoked overload 零变化）")
    void executeQueuedInput_withoutSnapshot_keepsFiveArgOverload() {
        com.nexusai.domain.session.MessageService messageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", messageService);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        String sessionId = "sess-busy-plain";
        QueueItem cmd = new QueueItem("忙时纯文本追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-plain", false, "busy-queued", false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(messageService).createQueuedUserMessage(
            eq(sessionId), eq("msg-queued-plain"), eq("忙时纯文本追问"), any(OffsetDateTime.class), eq(false));
        verify(loop).run(any());
    }
}

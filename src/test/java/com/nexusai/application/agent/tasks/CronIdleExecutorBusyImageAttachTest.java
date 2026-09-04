package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.common.RequestContext;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [OD-D5] CronIdleExecutor 端后兜底单条携图（reflector MAJOR-5）测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: turn 末残留带图 busy-queued 由 CronIdleExecutor
 * 端后兜底起新轮消费。若 runAgentLoop 单条路径仍用 9 参 RunRequest.session（不带附件），
 * QueueItem.attachments（enqueueBusyPrompt 携带的 ≤5MB base64 image）到 doRun 即丢（图静默丢失）。
 * 修复：单条 RunRequest.session 改 10 参附件重载 → doRun registerRunPromptImages 单次注册。
 * 本测试锚定「QueueItem 附件 → RunRequest.attachments 透传」链路；doRun 单次注册无双图由
 * LlmAgentLoopBusyImageDrainTest 的 per-item 断言覆盖。
 */
@DisplayName("[OD-D5] CronIdleExecutor 端后兜底单条携图 → RunRequest.attachments 透传")
class CronIdleExecutorBusyImageAttachTest {

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private NotificationQueue queue;
    private CronIdleExecutor executor;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
        RequestContext.clear();
    }

    @Test
    @DisplayName("busy-queued QueueItem 携图 → 单条 runAgentLoop 的 RunRequest.session.attachments 透传（端后 doRun 单次注册）")
    void runOneAgentLoop_busyImage_carriesAttachmentsIntoRunRequest() throws Exception {
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        AttachmentRequest img = new AttachmentRequest("image", "1", "a.png", "image/png", PNG_BASE64, null);
        // 13 参 canonical：value/mode/priority/agentId/uuid/isMeta/workload/skipSlash/origin/sessionId/boundProject/scheduleId/attachments
        QueueItem cmd = new QueueItem("忙时带图消息", "prompt", Priority.NEXT, null,
            "msg-queued-b1", false, "busy-queued", false, null, "sess-end-busy",
            null, null, List.of(img));

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        RunRequest req = captor.getValue();
        assertThat(req.userPrompt()).isEqualTo("忙时带图消息");
        assertThat(req.attachments())
            .as("端后兜底单条必须把 QueueItem.attachments 透传 RunRequest（doRun registerRunPromptImages 单次注册；否则残留忙时图在端后轮丢失）")
            .hasSize(1);
        assertThat(req.attachments().get(0).contentId()).isEqualTo("1");
        assertThat(req.attachments().get(0).base64()).isEqualTo(PNG_BASE64);
    }

    @Test
    @DisplayName("busy-queued 无附件 → RunRequest.attachments 空（纯文本端后兜底零变化）")
    void runOneAgentLoop_busyNoAttachments_emptyAttachments() throws Exception {
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        QueueItem cmd = new QueueItem("忙时纯文本", "prompt", Priority.NEXT, null,
            "msg-queued-b2", false, "busy-queued", false, null, "sess-end-busy",
            null, null, List.of());

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().attachments()).isEmpty();
    }
}

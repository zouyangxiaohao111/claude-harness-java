package com.nexusai.apis.session;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.repository.session.entity.QueueOperationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [queue-audit OD-D11 · 批 A5] ChatController /queue/pop → popForEdit 意图测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: /queue/pop 是 CC 排队条 Esc/↑ 拉回编辑
 * （{@code popAllEditable}，utils/messageQueueManager.ts:428-484）的 REST 载体，必须逐条对齐：
 * <ol>
 *   <li><b>谓词 = 可编辑</b>：只取 {@code isPromptInputModeEditable(mode) && !isMeta}
 *       （CC :359-361）。本仓 {@code mode=prompt && isMeta=true} 的 cron / channel 系统项
 *       <b>不得</b>被弹出（否则原始 XML 灌进用户输入框）。</li>
 *   <li><b>不可编辑项留在队列</b>（CC :480-481 {@code push(...nonEditable)}）—— 不是删掉。</li>
 *   <li><b>回填 = 全部可编辑项 + 当前输入，{@code \n} join</b>（CC :455-456）。</li>
 *   <li><b>附件一起还</b>（CC :463-478）。</li>
 * </ol>
 *
 * <p><b>鉴别力（为什么用 ≥2 条不同文本）</b>：旧实现「移除全部、只回最旧一条」在只有 1 条排队项时
 * 与「全部拼接」结果完全相同 —— 必须 ≥2 条才能把两种实现分开。本类每条断言都建立在
 * <b>2 条可编辑项</b>（`first-pop` / `second-pop`）之上。
 *
 * <p>变异点（每处应使哪些用例变红）：
 * <ul>
 *   <li>把 join 改回「只取第一条」→ {@code pop_joinsAllEditableTexts...} /
 *       {@code pop_appendsCurrentInput...} 红</li>
 *   <li>谓词丢掉 {@code !isMeta}（回到 {@code mode=prompt}）→
 *       {@code pop_keepsNonEditableSystemItemsInQueue} 红（cron/channel 项被错误弹出）</li>
 *   <li>谓词丢掉 mode 判据（无差别全弹）→ 同上用例红（task-notification 也被弹出）</li>
 *   <li>不回传 attachments → {@code pop_returnsQueuedAttachments} 红</li>
 * </ul>
 */
@DisplayName("[queue-audit] ChatController /queue/pop → popForEdit（批 A5 全部拼接 + 还图片）")
class ChatControllerPopForEditTest {

    private ChatController controller;
    private NotificationQueue queue;
    private QueueEventPublisher queueEventPublisher;
    private MockMvc mockMvc;
    private List<QueueOperationRecord> auditRecords;

    @BeforeEach
    void setUp() {
        controller = new ChatController();
        queue = new NotificationQueue();
        auditRecords = Collections.synchronizedList(new ArrayList<>());
        queue.registerAuditSink(auditRecords::add);
        queueEventPublisher = mock(QueueEventPublisher.class);
        ReflectionTestUtils.setField(controller, "notificationQueue", queue);
        ReflectionTestUtils.setField(controller, "queueEventPublisher", queueEventPublisher);
        // pop 端点不触碰以下字段，但 ChatController 其余端点需要 —— 用 mock 填满防误触
        ReflectionTestUtils.setField(controller, "chatService", mock(com.nexusai.application.chat.ChatService.class));
        ReflectionTestUtils.setField(controller, "messageService", mock(com.nexusai.domain.session.MessageService.class));
        ReflectionTestUtils.setField(controller, "wsTemplate", mock(SimpMessagingTemplate.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 可编辑用户输入项（mode=prompt, isMeta=false, workload=null）—— 同 ChatService.enqueueBusyPrompt 形态。 */
    private static QueueItem prompt(String value, String sessionId) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.NEXT, null, null,
            false, null, false, null, sessionId);
    }

    /** 可编辑用户输入项 + 附件（13 参构造，第 13 参 = 已解析附件列表）—— busy-queued 真实形态。 */
    private static QueueItem promptWithAttachments(String value, String sessionId, List<AttachmentRequest> atts) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.NEXT, null, null,
            false, "busy-queued", false, null, sessionId, null, null, atts);
    }

    /** 不可编辑系统项：mode=prompt 但 isMeta=true（cron 命令 TestJob:373 / cron missed 通知 CronIdleExecutor:235 / channel ChannelNotification:154）。 */
    private static QueueItem systemPrompt(String value, String sessionId) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.NEXT, null, null,
            true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);
    }

    @Test
    @DisplayName("⭐ 回填【全部】可编辑项、按原序 \\n join —— 用 2 条不同文本把「全部拼接」与「只回最旧一条」分开")
    void pop_joinsAllEditableTexts() throws Exception {
        queue.enqueue(prompt("first-pop", "sess-1"));
        queue.enqueue(prompt("second-pop", "sess-1"));

        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(status().isOk())
            // WHY 逐字：CC messageQueueManager.ts:456 queuedTexts.join('\n')。
            //   旧实现返回 "first-pop"（N-1 条静默消失）⇒ 本断言即把两种实现分开的鉴別点。
            .andExpect(jsonPath("$.text").value("first-pop\nsecond-pop"));

        assertThat(queue.dequeueAll()).as("两条都被弹出").isEmpty();
        verify(queueEventPublisher).emitChanged("sess-1");
        awaitPopAll(auditRecords, 2);
    }

    @Test
    @DisplayName("⭐ 回填 = 全部可编辑项 + 当前输入草稿（CC :455 [...queuedTexts, currentInput]）")
    void pop_appendsCurrentInputDraft() throws Exception {
        queue.enqueue(prompt("first-pop", "sess-1"));
        queue.enqueue(prompt("second-pop", "sess-1"));

        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentInput\":\"我的草稿\"}"))
            .andExpect(status().isOk())
            // WHY：草稿必须接在排队项之后、参与同一个 \n join —— 否则用户按 Esc 会被静默清掉草稿
            .andExpect(jsonPath("$.text").value("first-pop\nsecond-pop\n我的草稿"));
    }

    @Test
    @DisplayName("⭐ 谓词 = 可编辑（非 task-notification && !isMeta）：不可编辑系统项【留在队列】、不拼进输入框")
    void pop_keepsNonEditableSystemItemsInQueue() throws Exception {
        queue.enqueue(prompt("first-pop", "sess-1"));
        queue.enqueue(systemPrompt("cron-raw-prompt", "sess-1"));                     // mode=prompt + isMeta=true
        queue.enqueue(new QueueItem("channel-xml", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, null, true, null, true, new NotificationQueue.MessageOrigin("channel", "weixin"), "sess-1"));
        queue.enqueuePendingNotification(new QueueItem("note", NotificationQueue.MODE_TASK_NOTIFICATION,
            Priority.LATER, null, null, false, null, false, null, "sess-1"));
        queue.enqueue(prompt("second-pop", "sess-1"));
        queue.enqueue(prompt("other-session", "sess-2"));                              // 别的会话不捞

        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(status().isOk())
            // 只有两条可编辑项进输入框；cron/channel/task-notification 一个字都不许出现
            .andExpect(jsonPath("$.text").value("first-pop\nsecond-pop"));

        // WHY 逐字：CC :480-481 不可编辑项 push 回队列（不是删掉）—— 留队后仍由各自通道消费
        assertThat(queue.dequeueAll()).extracting(QueueItem::value)
            .containsExactlyInAnyOrder("cron-raw-prompt", "channel-xml", "note", "other-session");
    }

    @Test
    @DisplayName("⭐ 附件一起还：弹出项的 attachments 原序回传（图片连 base64 / 上传腿连 contentId）")
    void pop_returnsQueuedAttachments() throws Exception {
        AttachmentRequest img = new AttachmentRequest("image", null, "shot.png", "image/png",
            "aGVsbG8=", null);
        AttachmentRequest doc = new AttachmentRequest("file", "42", "report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null, null);
        queue.enqueue(promptWithAttachments("带图消息", "sess-1", List.of(img)));
        queue.enqueue(promptWithAttachments("第二个消息", "sess-1", List.of(doc)));

        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("带图消息\n第二个消息"))
            // WHY 逐字：CC :463-478 把弹出项的图片一并还给调用方（本仓是附件不是 pastedContents）
            .andExpect(jsonPath("$.attachments.length()").value(2))
            .andExpect(jsonPath("$.attachments[0].filename").value("shot.png"))
            .andExpect(jsonPath("$.attachments[0].type").value("image"))
            .andExpect(jsonPath("$.attachments[0].base64").value("aGVsbG8="))
            .andExpect(jsonPath("$.attachments[1].filename").value("report.docx"))
            .andExpect(jsonPath("$.attachments[1].contentId").value("42"));
    }

    @Test
    @DisplayName("无附件 / 无排队命令 → attachments 恒空数组、text 恒空串（不返回 null/空体）")
    void pop_emptyQueue_returnsEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value(""))
            .andExpect(jsonPath("$.attachments.length()").value(0));

        verify(queueEventPublisher, org.mockito.Mockito.never()).emitChanged("sess-1");
        Thread.sleep(200);
        assertThat(auditRecords).as("空弹出不触发 popAll 审计").isEmpty();

        // 纯文本可编辑项：text 正常、attachments 空数组（前端「无附件」判别靠 length）
        queue.enqueue(prompt("only-text", "sess-1"));
        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(jsonPath("$.text").value("only-text"))
            .andExpect(jsonPath("$.attachments.length()").value(0));
    }

    @Test
    @DisplayName("popAll 审计：只覆盖被弹出的可编辑项（带 content），留在队列的系统项不产生记录")
    void pop_auditsOnlyPoppedEditable() throws Exception {
        queue.enqueue(prompt("first-pop", "sess-1"));
        queue.enqueue(systemPrompt("cron-raw-prompt", "sess-1"));
        queue.enqueue(prompt("second-pop", "sess-1"));

        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop")).andExpect(status().isOk());

        awaitPopAll(auditRecords, 2);
        assertThat(auditRecords).filteredOn(r -> "popAll".equals(r.getOperation()))
            .extracting(QueueOperationRecord::getContent)
            .containsExactly("first-pop", "second-pop");
        assertThat(auditRecords).filteredOn(r -> "popAll".equals(r.getOperation()))
            .extracting(QueueOperationRecord::getSessionId)
            .containsOnly("sess-1");
    }

    private static void awaitPopAll(List<QueueOperationRecord> records, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (records.stream().filter(r -> "popAll".equals(r.getOperation())).count() < expected
            && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        long popAllCount = records.stream().filter(r -> "popAll".equals(r.getOperation())).count();
        assertThat(popAllCount).as("popAll 审计记录数必须达到 %d（异步分发需等待）", expected).isEqualTo(expected);
    }
}

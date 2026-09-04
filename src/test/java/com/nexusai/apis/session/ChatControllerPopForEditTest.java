package com.nexusai.apis.session;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.repository.session.entity.QueueOperationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * [queue-audit OD-D11] ChatController /queue/pop → popForEdit 意图测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: /queue/pop 是 CC 排队条 Esc/↑ 拉回编辑（popAllEditable）的
 * REST 载体 —— 必须<b>从会话队列移除全部 mode=prompt 命令、仅回填最旧一条</b>（语义不变，
 * 红线 4），并审计 op=<b>'popAll'（带 content）</b>（reflector MAJOR：拉回编辑 vs 模型消费
 * removeByFilter→'remove' 可区分）。变异点：
 * <ul>
 *   <li>改回 removeByFilter（'remove' 无 content）→ 拉回编辑审计语义漂移 → 红</li>
 *   <li>谓词变化（误捞别的会话/task-notification）→ 跨会话泄漏 / 系统消息漏进输入框 → 红</li>
 *   <li>回填非最旧一条 → 拉回内容错乱（CC popAllEditable 拼 queuedTexts 保序） → 红</li>
 * </ul>
 */
@DisplayName("[queue-audit] ChatController /queue/pop → popForEdit")
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

    private static QueueItem prompt(String value, String sessionId) {
        // 10-arg：value/mode/priority/agentId/uuid/isMeta/workload/skipSlashCommands/origin/sessionId
        return new QueueItem(value, "prompt", Priority.NEXT, null, null, false, null, false, null, sessionId);
    }

    @Test
    @DisplayName("pop 经 popForEdit：移除全部本会话 mode=prompt、仅回填最旧一条、审计 'popAll' 带 content")
    void pop_pullsOldestPromptAndAuditsPopAll() throws Exception {
        queue.enqueue(prompt("first-pop", "sess-1"));
        queue.enqueue(prompt("second-pop", "sess-1"));
        queue.enqueue(prompt("other-session", "sess-2"));           // 别的会话 prompt 不捞
        queue.enqueuePendingNotification(new QueueItem("note", "task-notification", Priority.LATER, null, null, false, null, false, null, "sess-1"));

        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(status().isOk())
            // 只回填最旧一条（CC popAllEditable 拼 queuedTexts[0] + 保序语义）
            .andExpect(jsonPath("$.content").value("first-pop"));

        // WHY: 复刻旧 removeByFilter 行为 —— 移除全部 prompt 命令（本会话），仅回填最旧一条；
        //   popForEdit 谓词 = sessionId + mode=prompt（MINOR 1 谓词一致）
        assertThat(queue.dequeueAll()).extracting(QueueItem::value)
            .containsExactlyInAnyOrder("other-session", "note");

        // WHY: /queue/pop 走 'popAll'（带 content，reflector MAJOR）—— 拉回编辑与模型消费可区分
        awaitPopAll(auditRecords, 2);
        assertThat(auditRecords).filteredOn(r -> "popAll".equals(r.getOperation()))
            .extracting(QueueOperationRecord::getContent)
            .containsExactly("first-pop", "second-pop");
        assertThat(auditRecords).filteredOn(r -> "popAll".equals(r.getOperation()))
            .extracting(QueueOperationRecord::getSessionId)
            .containsOnly("sess-1");
    }

    @Test
    @DisplayName("pop 无匹配排队命令 → 200 空 Map、不审计、emitChanged 不调")
    void pop_emptyQueue_returnsEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/sess-1/queue/pop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").doesNotExist());

        verify(queueEventPublisher, org.mockito.Mockito.never())
            .emitChanged("sess-1");
        Thread.sleep(200);
        assertThat(auditRecords).as("空弹出不触发 popAll 审计").isEmpty();
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

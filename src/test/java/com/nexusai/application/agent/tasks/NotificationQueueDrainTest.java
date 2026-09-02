package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mid-turn drain 语义测试 — 对齐 CC query.ts:1566-1643。
 *
 * <p>WHY（规则九）：drain 的验收点是"阈值+排除 slash+仅移除被消费的 prompt/task-notification"。
 * 若 drain 把 slash 当普通通知注入、或移除未消费的项、或忽略 'later' 阈值，后台通知会错序/泄漏
 * 到下一轮，模型看不到正确顺序的任务结果。
 */
class NotificationQueueDrainTest {

    private static QueueItem qi(String value, String mode, Priority priority) {
        return new QueueItem(value, mode, priority, null);
    }

    @Test
    @DisplayName("mid-turn drain: 阈值 next 排除 slash + 仅移除 prompt/task-notification — 对齐 CC query.ts:1570-1643")
    void drainKeepsSlashAndNonConsumed() {
        NotificationQueue queue = new NotificationQueue();
        // 阈值 'next'（未 sleep）：now+next 可见，later 留队
        queue.enqueue(qi("/status", "prompt", Priority.NOW));            // slash → 排除，不移除
        queue.enqueue(qi("task完成", "task-notification", Priority.NEXT)); // 消费
        queue.enqueue(qi("后台prompt", "prompt", Priority.NEXT));          // 消费
        queue.enqueue(qi("later通知", "task-notification", Priority.LATER)); // 阈值外，留队

        boolean sleepRan = false;
        Priority threshold = sleepRan ? Priority.LATER : Priority.NEXT;
        // 与 LlmAgentLoop drain 完全相同的过滤链（CC query.ts:1570-1578）
        List<QueueItem> queued = queue.getCommandsByMaxPriority(threshold).stream()
            .filter(cmd -> !NotificationQueue.isSlashCommand(cmd))
            .filter(cmd -> cmd.agentId() == null)
            .toList();
        assertThat(queued).extracting(QueueItem::value)
            .containsExactly("task完成", "后台prompt");

        // CC :1632-1643 — 仅移除被消费的 prompt/task-notification
        List<QueueItem> consumed = queued.stream()
            .filter(cmd -> "prompt".equals(cmd.mode()) || "task-notification".equals(cmd.mode()))
            .toList();
        queue.remove(consumed);

        // slash 项与 later 项留队（slash 走 command 队列路径；later 留到 Sleep 唤醒/下一轮）
        assertThat(queue.dequeueAll()).extracting(QueueItem::value)
            .containsExactly("/status", "later通知");
    }

    @Test
    @DisplayName("Sleep 已运行 → 阈值 'later' 全量 drain — 对齐 CC query.ts:1566/1571")
    void sleepRanDrainsLaterToo() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(qi("next项", "task-notification", Priority.NEXT));
        queue.enqueue(qi("later项", "task-notification", Priority.LATER));

        boolean sleepRan = true; // 本轮 toolUseBlocks 含 SleepTool → 'later'
        Priority threshold = sleepRan ? Priority.LATER : Priority.NEXT;

        List<QueueItem> queued = queue.getCommandsByMaxPriority(threshold).stream()
            .filter(cmd -> !NotificationQueue.isSlashCommand(cmd))
            .filter(cmd -> cmd.agentId() == null)
            .toList();
        assertThat(queued).extracting(QueueItem::value).containsExactly("next项", "later项");
    }

    @Test
    @DisplayName("每命令独立注入：drain 结果逐条为独立 user 消息（非合并单条）")
    void perCommandIndependentInjection() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(qi("通知A", "task-notification", Priority.NEXT));
        queue.enqueue(qi("通知B", "task-notification", Priority.NEXT));

        // 模拟 drain：每命令独立成一条 user 消息（toMessage(Role.user, cmd.value(), null)）
        List<QueueItem> queued = queue.getCommandsByMaxPriority(Priority.NEXT).stream()
            .filter(cmd -> !NotificationQueue.isSlashCommand(cmd))
            .filter(cmd -> cmd.agentId() == null)
            .toList();
        List<String> userMessages = queued.stream().map(QueueItem::value).toList();

        // WHY: 独立注入保证每条通知是完整独立上下文；合并单条会让两条结果拼在一个 user 消息里
        assertThat(userMessages).containsExactly("通知A", "通知B");
    }

    @Test
    @DisplayName("subagent 仅 drain 自身 task-notification，主线程 prompt 不外泄")
    void subagentDrainScopedToOwnAgent() {
        NotificationQueue queue = new NotificationQueue();
        java.util.UUID subAgentId = java.util.UUID.randomUUID();
        queue.enqueue(new QueueItem("主线程prompt", "prompt", Priority.NEXT, null));
        queue.enqueue(new QueueItem("子agent通知", "task-notification", Priority.NEXT,
            subAgentId.toString()));
        queue.enqueue(new QueueItem("他agent通知", "task-notification", Priority.NEXT,
            java.util.UUID.randomUUID().toString()));

        List<QueueItem> queued = queue.getCommandsByMaxPriority(Priority.NEXT).stream()
            .filter(cmd -> !NotificationQueue.isSlashCommand(cmd))
            // subagent: 仅 task-notification 且 agentId 匹配自身（CC query.ts:1577-1578）
            .filter(cmd -> "task-notification".equals(cmd.mode())
                && cmd.agentId() != null && cmd.agentId().equals(subAgentId.toString()))
            .toList();

        assertThat(queued).extracting(QueueItem::value).containsExactly("子agent通知");
    }
}

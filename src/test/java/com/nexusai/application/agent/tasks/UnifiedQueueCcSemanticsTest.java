package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [OPD-TS-27 / WF3-03] 统一优先队列 CC 语义测试 · 对齐 CC utils/messageQueueManager.ts
 *
 * <p>意图 (WHY): CC 单一 module 级 commandQueue 承载用户输入 / task 通知 / cron 通知
 * (messageQueueManager.ts:40-53)，优先级 now(0)>next(1)>later(2)，同级 FIFO (:175-185)。
 * 旧 Java 实现 (PriorityBlockingQueue 堆序 + enqueue 2-arg ctor 硬编码 LATER +
 * dequeueAllMatching 首个不匹配 break) 不满足上述语义 —— 本测试在重构前应失败 (RED)。
 */
class UnifiedQueueCcSemanticsTest {

    @Test
    void enqueue_defaultsPriorityToNext_processedBeforeLaterNotifications() {
        // WHY: CC messageQueueManager.ts:128-135 enqueue() 默认 'next' —— 用户输入 (prompt)
        //      必须优先于 later 后台通知处理, 否则模型先看到通知而非用户输入 (仲裁失败).
        NotificationQueue q = new NotificationQueue();
        q.enqueuePendingNotification(new NotificationQueue.QueueItem("notif", "task-notification")); // 默认 later
        q.enqueue(new NotificationQueue.QueueItem("prompt", "prompt")); // 默认 next

        var first = q.dequeue();
        var second = q.dequeue();

        assertTrue(first.isPresent(), "应优先取出 next 优先级的 prompt");
        assertEquals("prompt", first.get().value());
        assertEquals("notif", second.get().value());
    }

    @Test
    void dequeue_preservesFifoWithinSamePriority() {
        // WHY: CC dequeue (:175-185) 数组线性扫描取严格更小优先级 → 同级按插入序 FIFO.
        //      PriorityBlockingQueue 仅按 priority 比较, 同级堆序不保证插入序.
        NotificationQueue q = new NotificationQueue();
        q.enqueue(new NotificationQueue.QueueItem("a", "prompt", NotificationQueue.Priority.NEXT, null));
        q.enqueue(new NotificationQueue.QueueItem("b", "prompt", NotificationQueue.Priority.NEXT, null));
        q.enqueue(new NotificationQueue.QueueItem("c", "prompt", NotificationQueue.Priority.NEXT, null));

        List<String> order = new ArrayList<>();
        Optional<NotificationQueue.QueueItem> item;
        while ((item = q.dequeue()).isPresent()) {
            order.add(item.get().value());
        }

        assertEquals(List.of("a", "b", "c"), order, "同级必须 FIFO 保序");
    }

    @Test
    void dequeueAllMatching_scansWholeQueue_keepsNonMatching() {
        // WHY: CC dequeueAllMatching (:244-266) 全队列扫描, 匹配项全取, 非匹配项保留.
        //      旧实现首个不匹配 break → 匹配项丢失 (EV 证据: NotificationQueue.java:186-191).
        NotificationQueue q = new NotificationQueue();
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "match1", "task-notification", NotificationQueue.Priority.NEXT, null));
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "other", "prompt", NotificationQueue.Priority.NEXT, "agent-9"));
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "match2", "task-notification", NotificationQueue.Priority.NEXT, null));

        var matched = q.dequeueAllMatching(i ->
            "task-notification".equals(i.mode()) && i.agentId() == null);

        assertEquals(List.of("match1", "match2"),
            matched.stream().map(NotificationQueue.QueueItem::value).toList(),
            "必须跨非匹配项继续扫描");
        assertEquals(1, q.size(), "非匹配项保留在队列");
    }

    @Test
    void getCommandsByMaxPriority_respectsThreshold() {
        // WHY: CC getCommandsByMaxPriority (:525-532) — query.ts:1570 drain 阈值语义:
        //      sleepRan ? 'later' : 'next'; 'next' 阈值只含 now(0)+next(1), 不含 later(2).
        NotificationQueue q = new NotificationQueue();
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "now", "task-notification", NotificationQueue.Priority.NOW, null));
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "next", "task-notification", NotificationQueue.Priority.NEXT, null));
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "later", "task-notification", NotificationQueue.Priority.LATER, null));

        var nextAndAbove = q.getCommandsByMaxPriority(NotificationQueue.Priority.NEXT);
        assertEquals(2, nextAndAbove.size(), "'next' 阈值应含 now+next");
        var all = q.getCommandsByMaxPriority(NotificationQueue.Priority.LATER);
        assertEquals(3, all.size(), "'later' 阈值应含全部");
    }

    @Test
    void drainForQuery_excludesSlash_AppliesThreshold_AndMainThreadScoping() {
        // WHY: CC query.ts:1570-1578 — drain 排除 slash command, 未 sleep 时阈值 'next'
        //      (later 任务通知留待 Sleep flush), 主线程只消费 agentId===undefined.
        NotificationQueue q = new NotificationQueue();
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "/help", "prompt", NotificationQueue.Priority.NEXT, null)); // slash → 排除
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "later-notif", "task-notification", NotificationQueue.Priority.LATER, null)); // 阈值外
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "sub-notif", "task-notification", NotificationQueue.Priority.NEXT, "agent-2")); // scoping 外
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "now-notif", "task-notification", NotificationQueue.Priority.NEXT, null));

        // [3a] 无会话主线程（forTest/headless 等价全局执行器）：只捞 sessionId==null 全局命令
        var drained = q.drainForQuery(false, null, null); // 主线程, 未 sleep → 阈值 next, 无会话上下文

        assertEquals(List.of("now-notif"),
            drained.stream().map(NotificationQueue.QueueItem::value).toList());
        // slash 仍留队列 (CC 排除而非消费), later/子agent 项留待后续
        assertEquals(3, q.size());
    }

    @Test
    void drainForQuery_sessionTurnDrainsOnlyOwnSession_slashAndThresholdPreserved() {
        // WHY（规则九 · 验证 3a 意图）：具体会话 turn 只捞本会话命令（canonicalUuid 归一化），
        // slash 排除 + 阈值仲裁（query.ts:1573/1570-1571）与 agentId scoping（:1574）语义不变，
        // 只是额外叠加 sessionId 归属过滤 —— 会话 B 的 Sleep 回合不得捞会话 A 的 cron。
        NotificationQueue q = new NotificationQueue();
        String sessionA = java.util.UUID.randomUUID().toString();
        String sessionB = java.util.UUID.randomUUID().toString();
        // 10-arg 构造 (value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, origin, sessionId)
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "/help", "prompt", NotificationQueue.Priority.NEXT, null, null, false, null, false, null, sessionA)); // slash → 排除
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "later-notif", "task-notification", NotificationQueue.Priority.LATER, null, null, false, null, false, null, sessionA)); // 阈值外
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "A的cron", "prompt", NotificationQueue.Priority.NEXT, null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionA)); // 本会话 cron
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "B的cron", "prompt", NotificationQueue.Priority.NEXT, null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionB)); // 别的会话

        var drained = q.drainForQuery(false, null, sessionA); // 会话 A 的回合, 未 sleep → 阈值 next

        assertEquals(List.of("A的cron"),
            drained.stream().map(NotificationQueue.QueueItem::value).toList(),
            "会话 A 的回合只捞 A 的 cron；slash/later/B 的 cron 均不消费");
        // slash 留队列 + later 留队列 + B 的 cron 留队列 = 3
        assertEquals(3, q.size());
    }
}

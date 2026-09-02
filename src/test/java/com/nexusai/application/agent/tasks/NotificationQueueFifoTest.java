package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationQueue 队列族对齐 CC messageQueueManager.ts 行为测试。
 *
 * <p>WHY（规则九）：CC 队列是同优先级 FIFO + dequeueAllMatching 全数组分区 + remove 引用身份 +
 * getCommandsByMaxPriority 阈值过滤。若用堆序（旧 PriorityBlockingQueue）或 peek-break 分区（旧实现），
 * mid-turn drain 顺序会错乱、Sleep 唤醒阈值失效 → 模型看不到正确顺序的通知。
 */
class NotificationQueueFifoTest {

    private static QueueItem item(String value, Priority priority) {
        return new QueueItem(value, "task-notification", priority, null);
    }

    @Test
    @DisplayName("同优先级多个 LATER 按入队序出队（FIFO）— 对齐 CC Array+splice messageQueueManager.ts:167-193")
    void samePriorityFifoOrder() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(item("first", Priority.LATER));
        queue.enqueue(item("second", Priority.LATER));
        queue.enqueue(item("third", Priority.LATER));

        Optional<QueueItem> d1 = queue.dequeue(cmd -> cmd.agentId() == null);
        Optional<QueueItem> d2 = queue.dequeue(cmd -> cmd.agentId() == null);
        Optional<QueueItem> d3 = queue.dequeue(cmd -> cmd.agentId() == null);

        // WHY: 同级 FIFO 保证先入队的后台通知先被模型看到，任务完成时序不被堆序打乱
        assertThat(d1).get().extracting(QueueItem::value).isEqualTo("first");
        assertThat(d2).get().extracting(QueueItem::value).isEqualTo("second");
        assertThat(d3).get().extracting(QueueItem::value).isEqualTo("third");
        assertThat(queue.hasCommandsInQueue()).isFalse();
    }

    @Test
    @DisplayName("dequeueAllMatching 任意位置匹配抽走、不匹配保序 — 对齐 CC messageQueueManager.ts:244-266")
    void dequeueAllMatchingPartitionsFullArray() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(item("keep1", Priority.LATER));
        queue.enqueue(item("match1", Priority.LATER));
        queue.enqueue(item("keep2", Priority.LATER));
        queue.enqueue(item("match2", Priority.LATER));

        List<QueueItem> matched = queue.dequeueAllMatching(
            cmd -> cmd.value().startsWith("match"));

        // WHY: 全数组分区保证中间匹配项也能被抽走；旧 peek-break 实现遇首个不匹配即停，
        // keep1 开头的"首个不匹配 break"会让 match1/match2 永远留队
        assertThat(matched).extracting(QueueItem::value)
            .containsExactly("match1", "match2");
        assertThat(queue.dequeueAll()).extracting(QueueItem::value)
            .containsExactly("keep1", "keep2");
    }

    @Test
    @DisplayName("getCommandsByMaxPriority 阈值边界 — 对齐 CC messageQueueManager.ts:525-532")
    void getCommandsByMaxPriorityThreshold() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(item("now", Priority.NOW));
        queue.enqueue(item("next", Priority.NEXT));
        queue.enqueue(item("later", Priority.LATER));

        // 'later' 返回全部（now+next+later）
        assertThat(queue.getCommandsByMaxPriority(Priority.LATER))
            .extracting(QueueItem::value).containsExactlyInAnyOrder("now", "next", "later");
        // 'next' 仅返回 now+next（later 留给 Sleep 唤醒）
        assertThat(queue.getCommandsByMaxPriority(Priority.NEXT))
            .extracting(QueueItem::value).containsExactlyInAnyOrder("now", "next");
        // 'now' 仅返回 now
        assertThat(queue.getCommandsByMaxPriority(Priority.NOW))
            .extracting(QueueItem::value).containsExactly("now");
    }

    @Test
    @DisplayName("remove 按引用身份移除 — 对齐 CC messageQueueManager.ts:273-292")
    void removeByReferenceIdentity() {
        NotificationQueue queue = new NotificationQueue();
        QueueItem keep = item("keep", Priority.LATER);
        QueueItem drop = item("drop", Priority.LATER);
        queue.enqueue(keep);
        queue.enqueue(drop);

        queue.remove(List.of(drop));

        assertThat(queue.dequeueAll()).extracting(QueueItem::value).containsExactly("keep");
    }

    @Test
    @DisplayName("isSlashCommand：trim 后 '/' 开头 + skipSlashCommands 豁免 — 对齐 CC messageQueueManager.ts:541-547")
    void isSlashCommand() {
        assertThat(NotificationQueue.isSlashCommand(new QueueItem("/status", "prompt"))).isTrue();
        assertThat(NotificationQueue.isSlashCommand(new QueueItem("  /context  ", "prompt"))).isTrue();
        assertThat(NotificationQueue.isSlashCommand(new QueueItem("normal text", "prompt"))).isFalse();
        // skipSlashCommands=true → bridge/CCR 消息按纯文本送模型
        assertThat(NotificationQueue.isSlashCommand(new QueueItem("/bridge-cmd", "prompt",
            Priority.NEXT, null, false, null, true))).isFalse();
    }

    @Test
    @DisplayName("CRON-D5 改1: QueueItem.sessionId 经 enqueue/normalizePriority 重建透传不丢弃")
    void sessionIdSurvivesEnqueueNormalize() {
        // WHY: normalizePriority 在入队时重建 record（补默认 priority），若漏透传 sessionId，则
        // 消费线程（CronIdleExecutor.runOneAgentLoop）拿不到创建会话 → cwd/MDC 归组失效
        // （cron fire 的 SESSION scope 任务跨线程丢失会话归属）。CRON-D5 必须保证入队重建不丢弃。
        //
        // 返工前（假绿）：item 显式 priority=LATER 时 normalizePriority 首行短路返回原引用，
        // 断言通过是"引用原样"，即使删掉重建中的 sessionId 透传该测试仍绿（不捕获目标缺陷）。
        // 返工后：canonical 10-arg 且 priority=null + sessionId 非空，强制 enqueue 走重建分支；
        // 同时断言 priority 由 null 补默认 NEXT + 出队项非原引用 —— 证明重建确实发生，
        // sessionId 存活断言才验证到真实意图（规则九）。
        NotificationQueue queue = new NotificationQueue();
        String sessionId = "sess-d5-001";
        QueueItem item = new QueueItem("cron 任务", "prompt", null, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        queue.enqueue(item);                    // enqueue → normalizePriority(item, NEXT)：priority==null → 重建新 record

        Optional<QueueItem> got = queue.dequeue(cmd -> cmd.mode().equals("prompt"));
        assertThat(got).get().extracting(QueueItem::sessionId).isEqualTo(sessionId);
        // 重建确实发生（非 priority 非 null 短路返回原引用）：priority 由 null 补默认 NEXT
        assertThat(got).get().extracting(QueueItem::priority).isEqualTo(Priority.NEXT);
        // 出队项是重建出的新 record，而非入队原引用（identity 不相等）
        assertThat(got).get().isNotSameAs(item);
    }

    @Test
    @DisplayName("CRON-D5 改1: sessionId=null 项经 normalizePriority 重建后仍为 null（零回归）")
    void nullSessionIdSurvivesEnqueueNormalize() {
        // WHY: DURABLE/普通 prompt 路径 sessionId=null，入队重建不得凭空注入非 null（回落语义保持）。
        NotificationQueue queue = new NotificationQueue();
        queue.enqueuePendingNotification(new QueueItem("持久化任务", "prompt"));  // 2-arg → default later 重建

        Optional<QueueItem> got = queue.dequeue(cmd -> cmd.mode().equals("prompt"));
        assertThat(got).get().extracting(QueueItem::sessionId).isNull();
    }

    @Test
    @DisplayName("enqueue 默认 priority='next' / enqueuePendingNotification 默认 'later' — 对齐 CC messageQueueManager.ts:128/142")
    void defaultPriorities() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(new QueueItem("cmd", "prompt"));
        queue.enqueuePendingNotification(new QueueItem("note", "task-notification"));

        assertThat(queue.getCommandsByMaxPriority(Priority.NEXT))
            .extracting(QueueItem::value).contains("cmd");
        assertThat(queue.getCommandsByMaxPriority(Priority.NEXT))
            .extracting(QueueItem::value).doesNotContain("note");
        // 'later' 阈值 → 两者都可见
        assertThat(queue.getCommandsByMaxPriority(Priority.LATER))
            .extracting(QueueItem::value).containsExactlyInAnyOrder("cmd", "note");
    }
}

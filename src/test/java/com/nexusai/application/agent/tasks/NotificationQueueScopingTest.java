package com.nexusai.application.agent.tasks;

import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [OPD-TP-03] NotificationQueue drainForQuery agentId scoping · 对齐 CC query.ts:1570-1578.
 *
 * <p>意图 (WHY): 通知队列是进程级单例, 协调者与所有 in-process subagent 共享。
 * 主线程仅消费 {@code agentId==undefined} 的通知 (CC query.ts:1574 {@code cmd.agentId === undefined})；
 * subagent 仅消费 {@code mode==='task-notification' && agentId==自己} 的通知 (CC query.ts:1577)，
 * 绝不消费 user prompt / 其他 agent 的通知。
 * 若缺少 scoping (旧 dequeueAll 全量出队), 主线程会把子 agent 通知也吞掉 / 子 agent 拿不到自己的通知 (EV-7)。
 *
 * <p>[3a] sessionId 归属过滤 · CC query.ts:1574 主线程（agentId===undefined）的多会话展开：
 * CC 单进程单主会话，cron 无 agentId 恒归唯一主会话（结构性无歧义）；Java 每会话的 turn 都被归一成
 * "主线程"，若只滤 agentId，会话 B 的回合会把会话 A 的 cron / prompt 捞走（A-queue-ownership-probe §2.2）。
 * 归属不变式：具体会话 turn 只捞本会话命令（sessionId 归一化相等）；sessionId==null 全局命令一律不捞
 * （交 CronIdleExecutor）；无会话主线程（currentSessionId==null）只捞 sessionId==null 全局命令。
 *
 * <p>[3e] sessionId 归一化铁律：equals 两侧都走 {@link SessionKeys#canonicalUuid}
 * （QueueItem.sessionId 是原始键 "sess-xxx" 或派生 UUID 串 vs AgentState.sessionId 派生 UUID ——
 * 裸 equals 必 MISS，CRON-D5 F2 同教训）。
 *
 * <p>用例直调 {@link NotificationQueue#drainForQuery(boolean, String, UUID)} —— CC query.ts:1570-1578
 * 消费点内联 scoping filter 的 Java 对齐路径；sleepRan 恒传 true：阈值=later 全量快照 (CC query.ts:1571
 * {@code sleepRan ? 'later' : 'next'})，使 LATER 项进入快照，不影响 scoping 分支语义。
 */
class NotificationQueueScopingTest {

    @Test
    void mainThread_drainsOnlyAgentIdNull_notSubagentTargeted() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "sub-agent-notification", "task-notification", NotificationQueue.Priority.LATER, "agent-2"));
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "main-thread-notification", "task-notification", NotificationQueue.Priority.LATER, null));

        // 无会话主线程（forTest/headless 等价全局执行器）：只捞 sessionId==null 全局命令（[3a]）
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, null, null); // 主线程: sleepRan=true 阈值 later (CC query.ts:1571), agentId 归一为 null

        // 主线程只消费 agentId==null 项 (CC query.ts:1574 cmd.agentId===undefined)
        assertEquals(1, drained.size());
        assertEquals("main-thread-notification", drained.get(0).value());
        // 子 agent 定向通知不丢失, 留在队列下次 drain 可见
        assertEquals(1, queue.size());
    }

    @Test
    void subagent_drainsOnlyTaskNotificationForItself() {
        NotificationQueue queue = new NotificationQueue();
        // 自己的 task-notification → 应出队
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "mine", "task-notification", NotificationQueue.Priority.LATER, "agent-1"));
        // 其他 agent 的 task-notification → 不出队
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "other-agent", "task-notification", NotificationQueue.Priority.LATER, "agent-2"));
        // user prompt 模式 → 即使 agentId 匹配也绝不出队 (CC query.ts:1576-1577 subagent 绝不消费 prompt)
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "user-prompt", "prompt", NotificationQueue.Priority.LATER, "agent-1"));
        // 主线程通知 (agentId==null) → subagent 不消费
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "main-thread", "task-notification", NotificationQueue.Priority.LATER, null));

        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, "agent-1", null); // subagent（currentSessionId 不参与 subagent 分支）

        // subagent 只消费 mode==='task-notification' && agentId==自己 (CC query.ts:1577)
        assertEquals(1, drained.size());
        assertEquals("mine", drained.get(0).value());
        // 非匹配项不丢失 (留在队列, 下次 drain 可见)
        assertEquals(3, queue.size());
    }

    @Test
    void scopingPreservesPriorityFifoAmongMatches() {
        NotificationQueue queue = new NotificationQueue();
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "now-item", "task-notification", NotificationQueue.Priority.NOW, "agent-1"));
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "later-item", "task-notification", NotificationQueue.Priority.LATER, "agent-1"));
        queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "other-now", "task-notification", NotificationQueue.Priority.NOW, "agent-2"));

        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, "agent-1", null);

        // 优先级 NOW > LATER (CC messageQueueManager.ts:151-155), 匹配项按优先级 FIFO
        assertEquals(2, drained.size());
        assertEquals("now-item", drained.get(0).value());
        assertEquals("later-item", drained.get(1).value());
        // 非匹配项 (agent-2) 不丢失
        assertEquals(1, queue.size());
    }

    // ─────────────────── [3a] 跨会话归属：会话 turn 只捞本会话命令 ───────────────────

    /**
     * WHY（规则九 · 验证 3a 意图）：会话 B 用 Sleep 回合（sleepRan=true 阈值放宽到 later）不得捞走
     * 会话 A 的 cron（A-queue-ownership-probe §2.2 场景 A）。若 drainForQuery 只滤 agentId 不滤
     * sessionId，B 的回合会把 A 的 cron 当"用户消息"注入自己回合（幻影消息 + A 的上下文恢复被绕过）。
     */
    @Test
    void sessionTurn_drainsOnlyOwnSessionCommand_notOthersCron() {
        NotificationQueue queue = new NotificationQueue();
        String sessionA = "sess-aaa11111";
        String sessionB = "sess-bbb22222";
        // 会话 A 的 cron（SESSION scope，入队携带创建会话 sessionId）
        queue.enqueuePendingNotification(qi("A的cron", "prompt", NotificationQueue.Priority.LATER, null, sessionA));
        // 会话 B 的 cron
        queue.enqueuePendingNotification(qi("B的cron", "prompt", NotificationQueue.Priority.LATER, null, sessionB));

        // 会话 B 的回合（currentSessionId=B），sleepRan=true 阈值 later → 全量快照
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, null, sessionB);

        // B 的回合只捞 B 的 cron，A 的 cron 留在队列等 A 的回合 / CronIdleExecutor 代跑
        assertEquals(1, drained.size());
        assertEquals("B的cron", drained.get(0).value());
        assertEquals(1, queue.size());
        assertEquals("A的cron", queue.peek(q -> true).orElseThrow().value());
    }

    /**
     * WHY（规则九 · 验证 3a+3c 意图）：sessionId==null 全局命令（DURABLE 无会话 cron / missed 启动
     * 表面）只允许全局执行器（CronIdleExecutor + GLOBAL_SESSION_UUID）消费，任何具体会话 turn 一律不捞
     * —— 否则某会话正在跑回合时会无主命令被误捞进该回合（归属错位）。
     */
    @Test
    void sessionTurn_doesNotDrainGlobalCommands() {
        NotificationQueue queue = new NotificationQueue();
        String sessionA = "sess-ccc33333";
        queue.enqueuePendingNotification(qi("A自己的prompt", "prompt", NotificationQueue.Priority.NEXT, null, sessionA));
        queue.enqueuePendingNotification(qi("全局cron", "prompt", NotificationQueue.Priority.LATER, null, null));

        // 会话 A 的回合（currentSessionId=A）
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, null, sessionA);

        // 只捞 A 自己的命令；全局 cron 不捞（留 CronIdleExecutor）
        assertEquals(1, drained.size());
        assertEquals("A自己的prompt", drained.get(0).value());
        assertEquals(1, queue.size());
        assertEquals("全局cron", queue.peek(q -> true).orElseThrow().value());
    }

    /**
     * WHY（规则九 · 验证 3e 意图）：[session-id-short] QueueItem.sessionId 与 AgentState.sessionId
     * 已统一 short 直键 —— 裸 equals 必中（原 canonicalUuid 归一化铁律失去双形态前提，CRON-D5 F2 根因消除）。
     */
    @Test
    void sessionKeyNormalization_rawSessKeyMatchesDerivedUuid() {
        NotificationQueue queue = new NotificationQueue();
        String rawKey = "sess-abc12345";
        // 会话 A 的 cron 以 short 直键入队（工具/HTTP 创建路径形态统一）
        queue.enqueuePendingNotification(qi("A的cron", "prompt", NotificationQueue.Priority.LATER, null, rawKey));

        // 会话 A 的回合以同 short 为 currentSessionId → 裸 equals 精确命中
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, null, rawKey);

        assertEquals(1, drained.size());
        assertEquals("A的cron", drained.get(0).value());
    }

    /** 便捷构造：sessionId 显式（原始键或派生 UUID 串）。 */
    private static NotificationQueue.QueueItem qi(String value, String mode, NotificationQueue.Priority priority,
                                                  String agentId, String sessionId) {
        return new NotificationQueue.QueueItem(value, mode, priority, agentId,
            null, false, null, false, null, sessionId, null);
    }
}

package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [DEL-13 / OPD-TS-28] 入队不发 Notification hook · 对齐 CC messageQueueManager.ts
 *
 * <p>意图 (WHY): CC messageQueueManager.ts 全部 import 无任何 hooks 引用 (E-TS07-08) ——
 * 入队 (enqueue / enqueuePendingNotification) 是纯队列操作，不触发任何 hook。
 * Notification hook 仅由系统通知路径触发 (LlmAgentLoop:5303 A11 / ElicitationHandler /
 * AgentLoopContext:1094)。旧实现 enqueue() 内联 emitNotificationHook 会在每次入队
 * (TestJob / 用户 prompt 等) 重复触发 NOTIFICATION hook —— 与 CC 背离，会造成重复通知。
 *
 * <p>守护契约 (RED→GREEN)：队列类不得暴露任何 hook 接线入口 (setHookRegistry / hookRegistry
 * 字段)。若未来有人重新把 hook 塞回队列入队路径，本测试失败即报警 (回归护栏)。
 */
class NotificationQueueNoEnqueueHookTest {

    @Test
    void queue_hasNoHookWiringSurface() throws Exception {
        // WHY: 入队是纯队列操作 (CC messageQueueManager.ts:128-135)，队列不应耦合 HookRegistry。
        //      setHookRegistry 删除后队列无 hook 接线入口 —— 防止回归重新引入入队 hook。
        assertFalse(
            java.util.Arrays.stream(NotificationQueue.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("setHookRegistry")),
            "NotificationQueue 不应暴露 setHookRegistry：入队是纯队列操作，不触发 hook (CC messageQueueManager.ts 零 hook import)");
        assertFalse(
            java.util.Arrays.stream(NotificationQueue.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("hookRegistry")),
            "NotificationQueue 不应持有 hookRegistry 字段");
    }

    @Test
    void enqueue_worksAsPureQueueOperation_withoutAnyHook() {
        // WHY: 删除 emitNotificationHook 后 enqueue 仅做队列写入 —— 行为对齐 CC：入队不产生
        //      hook 副作用，drain 侧 (LlmAgentLoop 统一队列) 仍可消费。空 hookRegistry 场景
        //      (旧代码 hookRegistry==null 直接 return) 语义不变。
        NotificationQueue q = new NotificationQueue();
        q.enqueue(new NotificationQueue.QueueItem("prompt", "prompt"));
        q.enqueuePendingNotification(new NotificationQueue.QueueItem(
            "<task-notification/>", "task-notification"));

        Optional<NotificationQueue.QueueItem> first = q.dequeue();
        assertFalse(first.isEmpty());
    }
}

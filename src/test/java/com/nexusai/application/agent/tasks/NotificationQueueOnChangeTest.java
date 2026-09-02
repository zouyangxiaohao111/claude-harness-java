package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [queue-full-align P3] NotificationQueue onChange 事件驱动机制测试。
 *
 * <p>WHY（规则九 · 验证意图）: 对齐 CC useQueueProcessor.ts:35-67 useSyncExternalStore 订阅队列快照
 * —— CC 队列变化 → isQueryActive=false 立即消费（0 延迟替代轮询）。Java 以 onChange 监听机制承载：
 * 任意写方法（enqueue/dequeue/remove/clear/reset...）末尾 fireOnChange → NOTIFY_EXECUTOR 异步
 * dispatch（不入队线程同步跑，防嵌套 synchronized 死锁）。listener 幂等（poll 语义天然幂等）。
 * RED: 若 fireOnChange 缺失 / dispatch 同步 / unregister 失效 → 断言变红。
 */
class NotificationQueueOnChangeTest {

    @Test
    @DisplayName("enqueue 异步触发 onChange listener（对齐 CC 队列变化即订阅通知）")
    void enqueue_firesOnChangeListener() throws Exception {
        NotificationQueue q = new NotificationQueue();
        CountDownLatch latch = new CountDownLatch(1);
        q.registerOnChange(latch::countDown);

        q.enqueue(new QueueItem("x", "prompt"));

        assertThat(latch.await(3, TimeUnit.SECONDS))
            .as("enqueue 必须异步触发 onChange listener（listener 在 NOTIFY_EXECUTOR 独立线程跑）")
            .isTrue();
    }

    @Test
    @DisplayName("notifyChanged 公开 re-fire（turn 结束 LlmAgentLoop 显式触发，0 延迟兜底消费）")
    void notifyChanged_reFiresPublicly() throws Exception {
        NotificationQueue q = new NotificationQueue();
        CountDownLatch latch = new CountDownLatch(1);
        q.registerOnChange(latch::countDown);

        q.notifyChanged();

        assertThat(latch.await(3, TimeUnit.SECONDS))
            .as("notifyChanged 必须重新触发 dispatch（turn 结束事件驱动兜底消费入口）")
            .isTrue();
    }

    @Test
    @DisplayName("unregisterOnChange 注销后不再触发（防跨 run/跨 bean 泄漏）")
    void unregisterOnChange_stopsNotifications() throws Exception {
        NotificationQueue q = new NotificationQueue();
        CountDownLatch latch = new CountDownLatch(1);
        Runnable l = latch::countDown;
        q.registerOnChange(l);
        q.unregisterOnChange(l);

        q.enqueue(new QueueItem("x", "prompt"));

        assertThat(latch.await(300, TimeUnit.MILLISECONDS))
            .as("注销后 listener 不得再被触发（防泄漏：LlmAgentLoop run() finally 注销 nowAbortListener）")
            .isFalse();
    }

    @Test
    @DisplayName("drainForQuery 消费（经 remove）触发 onChange——mid-turn drain 变化点覆盖")
    void drainForQuery_removalFiresOnChange() throws Exception {
        NotificationQueue q = new NotificationQueue();
        CountDownLatch first = new CountDownLatch(1);
        q.registerOnChange(first::countDown);
        q.enqueue(new QueueItem("x", "prompt", Priority.NEXT, null));
        assertThat(first.await(3, TimeUnit.SECONDS)).as("enqueue 触发").isTrue();

        // 换 listener 吞掉 enqueue 触发后，单独验证 drainForQuery（内部 remove）再次触发
        CountDownLatch drain = new CountDownLatch(1);
        q.unregisterOnChange(first::countDown);
        q.registerOnChange(drain::countDown);
        q.enqueue(new QueueItem("y", "prompt", Priority.NEXT, null));
        assertThat(drain.await(3, TimeUnit.SECONDS)).as("第二次 enqueue 触发").isTrue();

        CountDownLatch drainLatch = new CountDownLatch(1);
        q.unregisterOnChange(drain::countDown);
        q.registerOnChange(drainLatch::countDown);
        q.drainForQuery(false, null, null);

        assertThat(drainLatch.await(3, TimeUnit.SECONDS))
            .as("drainForQuery 消费（remove）必须触发 onChange——mid-turn drain 变化点覆盖（CronIdleExecutor.poll spurious 无害）")
            .isTrue();
    }

    @Test
    @DisplayName("dequeueAllMatching 批量出队触发 onChange（CronIdleExecutor.poll 消费路径）")
    void dequeueAllMatching_firesOnChange() throws Exception {
        NotificationQueue q = new NotificationQueue();
        CountDownLatch latch = new CountDownLatch(1);
        q.registerOnChange(latch::countDown);

        q.enqueue(new QueueItem("a", "prompt", Priority.NEXT, null));
        assertThat(latch.await(3, TimeUnit.SECONDS)).as("enqueue 触发").isTrue();

        CountDownLatch batch = new CountDownLatch(1);
        q.unregisterOnChange(latch::countDown);
        q.registerOnChange(batch::countDown);
        q.dequeueAllMatching(i -> true);

        assertThat(batch.await(3, TimeUnit.SECONDS))
            .as("dequeueAllMatching 批量出队必须触发 onChange（poll 消费路径）")
            .isTrue();
    }
}

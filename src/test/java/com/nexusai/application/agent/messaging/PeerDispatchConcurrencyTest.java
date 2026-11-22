package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.CronIdleExecutor;
import com.nexusai.application.agent.tasks.NotificationQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [sendmessage-cross-session · B2] 空闲消费的<b>共享起轮权</b>契约：
 * 同一会话并发派发最多一个真正起轮，失 claim 的批次<b>整批回队不丢件</b>。
 *
 * <p><b>RED tooth（变异 ①）</b>：把 {@code CronIdleExecutor.executeQueuedInput} 里的
 * 「claim == null → 回队 + return」改回「失败仍执行」⇒ 第 1 例红（runs &gt; 1）；
 * 把回队删掉⇒ 第 1 例红（queue.size &lt; N-1 = 丢件）。第 2 例在「claim 已持有」时直接
 * 断言不起轮（不依赖竞态，稳定复现该变异）。
 *
 * <p>纯对象、无 Spring：真实队列 + 真实 registry + mock {@link LlmAgentLoop}（run 调用计数 + 拉长窗口）。
 */
@DisplayName("[B2] 空闲派发并发：最多一次模型调用，失 claim 回队不丢")
class PeerDispatchConcurrencyTest {

    private NotificationQueue queue;
    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerRuntimeAdapter adapter;
    private CronIdleExecutor executor;
    private ExecutorService tasks;
    private LlmAgentLoop loop;
    private AtomicInteger runs;
    private CountDownLatch firstRunDone;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        registry = new PeerSessionRegistry();
        registry.clear();
        policy = new PeerInboundPolicy();
        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "peerIdleSubscriptions", new PeerIdleSubscriptions());
        ReflectionTestUtils.invokeMethod(adapter, "wire");
        // 显式 accept（wire() 之后安装，覆盖其 SettingsService 源）：本用例只验证 claim 串行，
        // 不验证模式匹配（模式矩阵见 PeerModeParityTest）。
        policy.setSettingsSource(receiverSessionId -> "accept");

        runs = new AtomicInteger();
        firstRunDone = new CountDownLatch(1);
        loop = Mockito.mock(LlmAgentLoop.class);
        when(loop.run(any())).thenAnswer(inv -> {
            runs.incrementAndGet();
            try {
                Thread.sleep(250);   // 拉宽窗口：若并发未被 claim 挡住，第二个 run 必然进来
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            firstRunDone.countDown();
            return null;
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);

        tasks = Executors.newVirtualThreadPerTaskExecutor();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        ReflectionTestUtils.setField(executor, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(executor, "peerRuntimeAdapter", adapter);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        ReflectionTestUtils.setField(executor, "cronExecutor", tasks);
    }

    @AfterEach
    void tearDown() {
        tasks.shutdownNow();
    }

    @Test
    @DisplayName("N 路并发派发同一会话 ⇒ 只有 1 次 run；N-1 批回队（不丢件）；结束后 claim 可再取")
    void concurrentDispatchYieldsSingleRunAndRequeuesLosers() throws Exception {
        String target = "sess-conc" + UUID.randomUUID().toString().substring(0, 4).replace('-', 'c');
        registry.register(new PeerSessionRegistry.Registration(
            target, "conc-target", null, PermissionMode.DEFAULT, 0L));
        NotificationQueue.QueueItem item = PeerIdleWakeTest.peerItem(target, "peer-conc-1");

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Thread[] dispatchers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            dispatchers[i] = Thread.ofVirtual().unstarted(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    return;
                }
                ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(item));
            });
            dispatchers[i].start();
        }
        for (Thread t : dispatchers) {
            t.join(5_000);
        }
        assertThat(firstRunDone.await(5, TimeUnit.SECONDS)).as("至少一个批次真的起轮").isTrue();
        // 等赢家 run 结束并释放 claim（run 体内无 DB，仅计数 + sleep）
        for (int i = 0; i < 100 && LlmAgentLoop.isSessionClaimed(target); i++) {
            Thread.sleep(20);
        }

        assertThat(runs.get()).as("★ 并发派发下最多一个主模型调用").isEqualTo(1);
        assertThat(queue.size()).as("★ 失 claim 的 N-1 批整批回队（不丢件）").isEqualTo(threads - 1);
        assertThat(LlmAgentLoop.isSessionClaimed(target)).as("结束后 claim 必须已释放").isFalse();
        LlmAgentLoop.SessionClaim again = LlmAgentLoop.tryClaim(target);
        assertThat(again).as("释放后可再次起轮（无永久占位）").isNotNull();
        LlmAgentLoop.releaseClaim(again);
    }

    @Test
    @DisplayName("claim 已被他人持有 ⇒ 执行器整批回队且一次 run 都不发生")
    void heldClaimBlocksDispatchAndKeepsBatch() {
        String target = "sess-hold" + UUID.randomUUID().toString().substring(0, 4).replace('-', 'd');
        registry.register(new PeerSessionRegistry.Registration(
            target, "hold-target", null, PermissionMode.DEFAULT, 0L));
        NotificationQueue.QueueItem item = PeerIdleWakeTest.peerItem(target, "peer-conc-2");

        LlmAgentLoop.SessionClaim blocking = LlmAgentLoop.tryClaim(target);
        assertThat(blocking).as("前提：会话空闲，可取得 claim").isNotNull();
        try {
            ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(item));
            verify(loop, never()).run(any());
            assertThat(queue.size()).as("整批回队（不丢件）").isEqualTo(1);
        } finally {
            LlmAgentLoop.releaseClaim(blocking);
        }
        assertThat(LlmAgentLoop.isSessionClaimed(target)).as("他人的 claim 未被误清").isFalse();
    }

    /** 让断言消息更可读（AgentState 仅用于 mock 返回类型推断）。 */
    @SuppressWarnings("unused")
    private static AgentState unusedTypeAnchor() {
        return null;
    }
}

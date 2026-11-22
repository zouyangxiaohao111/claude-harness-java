package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.CronIdleExecutor;
import com.nexusai.application.agent.tasks.NotificationQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sendmessage-cross-session · B2] 空闲路径唤醒契约：peer 项只在<b>目标 live 且 idle</b> 时可消费，
 * 且一次 poll 只取一次（不重复唤醒 / 不丢件）。
 *
 * <p><b>RED tooth</b>：把 {@code CronIdleExecutor.mainThreadConsumable} 的 peer 分支删掉 ⇒
 * ① 关闭中的目标仍被消费（第 3 例红）；把 peer 分支写成 {@code return true} ⇒ 第 3 例红
 * （beginClosing 后仍唤醒）。把「isSessionActive 跳过」删掉 ⇒ 第 2 例红（运行中会话被代跑）。
 *
 * <p>纯对象、无 Spring：真实 {@link NotificationQueue} + 真实 {@link PeerSessionRegistry}，
 * {@link CronIdleExecutor} 直构后按字段注入。
 */
@DisplayName("[B2] 空闲路径 peer 唤醒（live+idle 才消费，一次）")
class PeerIdleWakeTest {

    private NotificationQueue queue;
    private PeerSessionRegistry registry;
    private CronIdleExecutor executor;
    private String target;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        registry = new PeerSessionRegistry();
        registry.clear();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        ReflectionTestUtils.setField(executor, "peerSessionRegistry", registry);
        target = "sess-wake" + UUID.randomUUID().toString().substring(0, 4).replace('-', 'a');
    }

    @Test
    @DisplayName("目标 live + idle ⇒ poll 取走该 peer 项一次；第二次 poll 为空（不重复唤醒）")
    void pollConsumesPeerItemOnceWhenTargetIdleAndLive() {
        registry.register(new PeerSessionRegistry.Registration(
            target, "wake-target", null, PermissionMode.DEFAULT, 0L));
        queue.enqueue(peerItem(target, "peer-wake-1"));

        List<NotificationQueue.QueueItem> first = new ArrayList<>();
        assertThat(executor.poll(first::addAll)).as("目标 live+idle ⇒ 本执行器应消费").isTrue();
        assertThat(first).as("取走 1 条").hasSize(1);
        assertThat(first.get(0).peerEnvelope()).as("携带服务端信封（不丢 peer 语义）").isNotNull();
        assertThat(first.get(0).uuid()).as("uuid = messageId（落库主键同源）").isEqualTo("peer-wake-1");

        List<NotificationQueue.QueueItem> second = new ArrayList<>();
        assertThat(executor.poll(second::addAll)).as("队列已空 ⇒ 不再消费").isFalse();
        assertThat(second).as("无第二次唤醒").isEmpty();
        assertThat(queue.size()).as("队列已空（一次消费）").isZero();
    }

    @Test
    @DisplayName("目标运行中（claim 持有）⇒ poll 不取，条目留在队列；释放后可取")
    void pollSkipsPeerItemWhileTargetActive() {
        registry.register(new PeerSessionRegistry.Registration(
            target, "busy-target", null, PermissionMode.DEFAULT, 0L));
        queue.enqueue(peerItem(target, "peer-wake-2"));

        LlmAgentLoop.SessionClaim claim = LlmAgentLoop.tryClaim(target);
        assertThat(claim).as("前提：claim 可取得（会话空闲）").isNotNull();
        try {
            List<NotificationQueue.QueueItem> captured = new ArrayList<>();
            assertThat(executor.poll(captured::addAll)).as("运行中 ⇒ 不消费").isFalse();
            assertThat(captured).isEmpty();
            assertThat(queue.size()).as("⛔ 不丢件：条目留在队列").isEqualTo(1);
        } finally {
            LlmAgentLoop.releaseClaim(claim);
        }

        List<NotificationQueue.QueueItem> after = new ArrayList<>();
        assertThat(executor.poll(after::addAll)).as("释放 claim 后可消费").isTrue();
        assertThat(after).hasSize(1);
    }

    @Test
    @DisplayName("目标 beginClosing ⇒ poll 不取（DB 可见 ≠ 可投递），条目留队列")
    void pollSkipsPeerItemWhenTargetClosing() {
        PeerSessionRegistry.EndpointHandle handle = registry.register(
            new PeerSessionRegistry.Registration(target, "closing-target", null, PermissionMode.DEFAULT, 0L));
        queue.enqueue(peerItem(target, "peer-wake-3"));
        registry.beginClosing(target, handle.endpointEpoch());

        List<NotificationQueue.QueueItem> captured = new ArrayList<>();
        assertThat(executor.poll(captured::addAll)).as("closing ⇒ 不可投递 ⇒ 不消费").isFalse();
        assertThat(captured).isEmpty();
        assertThat(queue.size()).as("条目仍留在队列").isEqualTo(1);
    }

    /** peer 队列项（生产同构：mode=prompt + workload=peer-message + agentId=null + peerEnvelope）。 */
    static NotificationQueue.QueueItem peerItem(String targetSessionId, String messageId) {
        String source = "sess-src" + UUID.randomUUID().toString().substring(0, 6).replace('-', 'b');
        PeerEnvelope envelope = new PeerEnvelope(
            messageId, "alpha [abcdef]", source, "alpha", "default",
            List.of(PeerEnvelope.hopToken(source)), 7L, "summary", false);
        return new NotificationQueue.QueueItem(
            "hello from peer", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null /* agentId=null：主线程 */, messageId, true /* isMeta */, "peer-message",
            true /* skipSlashCommands */, new NotificationQueue.MessageOrigin("peer", null),
            targetSessionId, null, null, null, null, null, null, envelope);
    }
}

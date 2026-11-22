package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sendmessage-cross-session · B2] idle 通知：<b>至多一次</b>，且只在源仍 live 时投回。
 *
 * <p><b>RED tooth</b>：把 {@code PeerRuntimeAdapter.onNotice} 的
 * {@code registry.isLive(source, epoch)} 守卫删掉 ⇒ 第 2 例红（已退出源被复活）；
 * 把 {@code PeerIdleSubscriptions.fire} 的「取出即删」改回「取出后保留」⇒ 第 1 例红（收到两次）。
 */
@DisplayName("[B2] idle 通知至多一次 + 源 live 才投回")
class PeerIdleNoticeTest {

    private NotificationQueue queue;
    private PeerSessionRegistry registry;
    private PeerIdleSubscriptions subscriptions;
    private PeerRuntimeAdapter adapter;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        registry = new PeerSessionRegistry();
        registry.clear();
        subscriptions = new PeerIdleSubscriptions();
        subscriptions.clear();

        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerIdleSubscriptions", subscriptions);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", new PeerInboundPolicy());
        ReflectionTestUtils.invokeMethod(adapter, "wire");
    }

    @Test
    @DisplayName("目标 idle ⇒ 通知投回源一次；再次 onTargetIdle 不再产生（至多一次）")
    void idleNoticeDeliveredOnce() {
        String source = "sess-notis" + tail();
        String target = "sess-notit" + tail();
        registry.register(new PeerSessionRegistry.Registration(source, "src", null, PermissionMode.DEFAULT, 0L));
        PeerSessionRegistry.EndpointHandle targetHandle = registry.register(
            new PeerSessionRegistry.Registration(target, "tgt", null, PermissionMode.DEFAULT, 0L));

        PeerEnvelope envelope = PeerEnvelope.create("peer-notice-1", "src [abcdef]", source, "src",
            PermissionMode.DEFAULT, List.of(), 1L, "summary", true);
        assertThat(subscriptions.subscribe(envelope, target, targetHandle.endpointEpoch())).isTrue();

        List<PeerIdleSubscriptions.IdleNotice> first =
            subscriptions.onTargetIdle(target, targetHandle.endpointEpoch());
        assertThat(first).as("订阅命中 1 次").hasSize(1);
        assertThat(queue.size()).as("通知已入源会话队列").isEqualTo(1);
        NotificationQueue.QueueItem notice = queue.peek(null).orElseThrow();
        assertThat(notice.sessionId()).as("投回源会话").isEqualTo(source);
        assertThat(notice.value()).as("通知文案声明自动、非指令")
            .startsWith("[Cross-session idle notice]")
            .contains("not a user instruction");
        assertThat(notice.peerEnvelope()).as("通知项也是 peer 项（同一投影）").isNotNull();
        assertThat(notice.isMeta()).isTrue();

        List<PeerIdleSubscriptions.IdleNotice> second =
            subscriptions.onTargetIdle(target, targetHandle.endpointEpoch());
        assertThat(second).as("取出即删 ⇒ 第二次为空").isEmpty();
        assertThat(queue.size()).as("★ 至多一次：队列仍只有 1 条").isEqualTo(1);
    }

    @Test
    @DisplayName("源已退出（unregister/代次失效）⇒ 通知静默丢弃，不复活")
    void idleNoticeDroppedWhenSourceNoLongerLive() {
        String source = "sess-gones" + tail();
        String target = "sess-gonet" + tail();
        PeerSessionRegistry.EndpointHandle sourceHandle = registry.register(
            new PeerSessionRegistry.Registration(source, "src2", null, PermissionMode.DEFAULT, 0L));
        PeerSessionRegistry.EndpointHandle targetHandle = registry.register(
            new PeerSessionRegistry.Registration(target, "tgt2", null, PermissionMode.DEFAULT, 0L));

        PeerEnvelope envelope = PeerEnvelope.create("peer-notice-2", "src2 [abcdef]", source, "src2",
            PermissionMode.DEFAULT, List.of(), sourceHandle.endpointEpoch(), "summary", true);
        assertThat(subscriptions.subscribe(envelope, target, targetHandle.endpointEpoch())).isTrue();

        registry.unregister(source, sourceHandle.endpointEpoch());

        List<PeerIdleSubscriptions.IdleNotice> notices =
            subscriptions.onTargetIdle(target, targetHandle.endpointEpoch());
        assertThat(notices).as("订阅本身已触发（一次性取出）").hasSize(1);
        assertThat(queue.size()).as("★ 源不 live ⇒ 不投回（不复活）").isZero();
    }

    private static String tail() {
        return UUID.randomUUID().toString().substring(0, 4).replace('-', 'e').replaceAll("[^A-Za-z0-9]", "e");
    }
}

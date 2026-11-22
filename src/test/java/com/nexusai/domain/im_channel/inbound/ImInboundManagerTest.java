package com.nexusai.domain.im_channel.inbound;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站监听的生命周期管理（{@link ImInboundManager}）。
 *
 * <h2>WHY</h2>
 * 这一层管的是**长连接线程**，行为错了几种方式都很难查：
 * <ul>
 *   <li>重复 {@code start} 起了两条连接 ⇒ 微信那边会丢消息（单消费者）、企微会双收；</li>
 *   <li>{@code stop} 没真停 ⇒ 用户点了「停止接收」，消息还在进来；</li>
 *   <li>实例不存在/平台没实现时**抛异常** ⇒ 前端只看到 500，不知道该去建实例。</li>
 * </ul>
 */
class ImInboundManagerTest {

    /** 可观测的假监听器：记录 start/stop 次数与运行态。 */
    private static final class FakeListener implements ImInboundListener {
        final String platform;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean running;
        volatile boolean explodeOnStart;

        FakeListener(String platform) {
            this.platform = platform;
        }

        @Override public String platform() { return platform; }

        @Override public void start(String instanceId, InboundHandler handler) {
            starts.incrementAndGet();
            if (explodeOnStart) {
                throw new IllegalStateException("凭证无效");
            }
            running = true;
        }

        @Override public void stop() {
            stops.incrementAndGet();
            running = false;
        }

        @Override public boolean isRunning() { return running; }
    }

    private static ImInstanceEntry inst(String id, String platform) {
        return new ImInstanceEntry(id, platform, "name-" + id, "disconnected",
                null, null, null, "t", "t");
    }

    @SuppressWarnings("unchecked")
    private static ImInboundManager manager(List<ImInstanceEntry> instances, ImInboundListener... listeners) {
        ImChannelService channelService = mock(ImChannelService.class);
        when(channelService.list()).thenReturn(instances);

        ObjectProvider<ImInboundListener> provider = mock(ObjectProvider.class);
        // 每次都返回同一个假监听器（模拟 prototype 被 new 出来的效果）
        when(provider.orderedStream()).thenAnswer(inv -> java.util.Arrays.stream(listeners));

        return manager(instances, java.util.List.of(), listeners);
    }

    @SuppressWarnings("unchecked")
    private static ImInboundManager manager(List<ImInstanceEntry> instances,
                                            List<ImChatEntry> chats,
                                            ImInboundListener... listeners) {
        ImChannelService channelService = mock(ImChannelService.class);
        when(channelService.list()).thenReturn(instances);

        ImChatService chatService = mock(ImChatService.class);
        when(chatService.list()).thenReturn(chats);

        ObjectProvider<ImInboundListener> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> java.util.Arrays.stream(listeners));

        ImInboundManager m = new ImInboundManager();
        ReflectionTestUtils.setField(m, "channelService", channelService);
        ReflectionTestUtils.setField(m, "chatService", chatService);
        ReflectionTestUtils.setField(m, "router", mock(ImInboundRouter.class));
        ReflectionTestUtils.setField(m, "listeners", provider);
        return m;
    }

    /** 造一条聊天目录记录；bound = 是否接线到任务。 */
    private static ImChatEntry chat(String instanceId, boolean bound) {
        return new ImChatEntry("imch-" + instanceId + (bound ? "-b" : "-u"), instanceId, "wecom",
                "peer", "peer", "p2p", null, null, null,
                bound ? "sess-1" : null, bound ? "manual" : null, null, "t", "t");
    }

    @Test
    @DisplayName("实例存在 + 平台有实现 ⇒ 启动成功，监听器真的 start 了一次")
    void startSucceeds() {
        FakeListener l = new FakeListener("wecom");
        ImInboundManager m = manager(List.of(inst("i1", "wecom")), l);

        assertThat(m.start("i1")).isTrue();
        assertThat(l.starts.get()).isEqualTo(1);
        assertThat(l.running).isTrue();
        assertThat(m.status()).hasSize(1);
    }

    @Test
    @DisplayName("⭐ 重复 start ⇒ 幂等，不会起第二条连接（微信单消费者会丢消息）")
    void startIsIdempotent() {
        FakeListener l = new FakeListener("wecom");
        ImInboundManager m = manager(List.of(inst("i1", "wecom")), l);

        m.start("i1");
        m.start("i1");
        m.start("i1");

        assertThat(l.starts.get()).as("只该 start 一次").isEqualTo(1);
    }

    @Test
    @DisplayName("实例不存在 ⇒ false（不抛异常，前端据此提示去建实例）")
    void unknownInstanceReturnsFalse() {
        ImInboundManager m = manager(List.of(), new FakeListener("wecom"));

        assertThat(m.start("nope")).isFalse();
    }

    @Test
    @DisplayName("平台暂无实现（如钉钉）⇒ false + 记日志，不抛")
    void noListenerForPlatform() {
        ImInboundManager m = manager(List.of(inst("i1", "dingtalk")), new FakeListener("wecom"));

        assertThat(m.start("i1")).isFalse();
    }

    @Test
    @DisplayName("启动时抛异常 ⇒ false 且不留残留状态（否则后续 stop 会拿到半个对象）")
    void startFailureCleansUp() {
        FakeListener l = new FakeListener("wecom");
        l.explodeOnStart = true;
        ImInboundManager m = manager(List.of(inst("i1", "wecom")), l);

        assertThat(m.start("i1")).isFalse();
        assertThat(m.status()).isEmpty();
    }

    @Test
    @DisplayName("stop 幂等：没启动过也返回 false，重复 stop 不炸")
    void stopIsIdempotent() {
        FakeListener l = new FakeListener("wecom");
        ImInboundManager m = manager(List.of(inst("i1", "wecom")), l);

        assertThat(m.stop("i1")).isFalse();   // 没启动过
        m.start("i1");
        assertThat(m.stop("i1")).isTrue();
        assertThat(m.stop("i1")).as("已经停了").isFalse();
        assertThat(l.stops.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("null / 空白实例 id ⇒ false，不 NPE")
    void blankIdSafe() {
        ImInboundManager m = manager(List.of(), new FakeListener("wecom"));

        assertThat(m.start(null)).isFalse();
        assertThat(m.start("  ")).isFalse();
        assertThat(m.stop(null)).isFalse();
    }

    // ───────────── 接线变化 ⇒ 监听收敛 ─────────────

    @Test
    @DisplayName("reconcile：该实例仍有接线 ⇒ 保持/开始收消息")
    void reconcileStartsWhenStillBound() {
        FakeListener l = new FakeListener("wecom");
        ImInboundManager m = manager(List.of(inst("imc-1", "wecom")),
                List.of(chat("imc-1", true)), l);

        m.reconcile("imc-1");

        assertThat(l.running).isTrue();
        assertThat(l.stops.get()).isZero();   // 不能因为 reconcile 就断掉
    }

    @Test
    @DisplayName("reconcile：该实例已无任何接线 ⇒ 断开连接（别白收）")
    void reconcileStopsWhenNoBindingLeft() {
        FakeListener l = new FakeListener("wecom");
        // 库里只剩「未接线」的记录（用户刚解绑了最后一个聊天）
        ImInboundManager m = manager(List.of(inst("imc-1", "wecom")),
                List.of(chat("imc-1", false)), l);
        // 模拟：先连上了，之后用户把最后一个聊天解绑
        m.start("imc-1");
        assertThat(l.running).isTrue();

        m.reconcile("imc-1");

        assertThat(l.running).isFalse();          // 连接真断了
        assertThat(l.stops.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("reconcile：别的实例有接线不算 —— 只看本实例")
    void reconcileIgnoresOtherInstances() {
        FakeListener l = new FakeListener("wecom");
        ImInboundManager m = manager(List.of(inst("imc-1", "wecom")),
                List.of(chat("imc-2", true)), l);   // 接线在 imc-2，不是 imc-1
        m.start("imc-1");

        m.reconcile("imc-1");

        assertThat(l.running).isFalse();   // 本实例没接线 ⇒ 断开
    }

    @Test
    @DisplayName("新建实例事件 ⇒ 自动启动长连接（用户要求：连接后默认启动）")
    void instanceCreatedAutoStarts() {
        FakeListener l = new FakeListener("feishu");
        ImInboundManager m = manager(List.of(inst("imc-new", "feishu")), l);

        m.onInstanceCreated(new com.nexusai.domain.im_channel.ImInstanceCreatedEvent("imc-new"));

        assertThat(l.starts.get()).isEqualTo(1);
        assertThat(l.running).isTrue();
    }

    @Test
    @DisplayName("自动启动失败不抛给创建方（实例已建好，可手动启动）")
    void instanceCreatedFailureIsSwallowed() {
        FakeListener l = new FakeListener("feishu");
        l.explodeOnStart = true;
        ImInboundManager m = manager(List.of(inst("imc-new", "feishu")), l);

        // 不抛异常 = 创建接口不会因为连不上而失败
        m.onInstanceCreated(new com.nexusai.domain.im_channel.ImInstanceCreatedEvent("imc-new"));

        assertThat(l.running).isFalse();
    }

    @Test
    @DisplayName("启动当场抛异常 ⇒ 状态回写 error（否则卡片一直显示「未连接」看不出失败）")
    void startFailureWritesErrorStatus() {
        ImChannelService channelService = mock(ImChannelService.class);
        when(channelService.list()).thenReturn(List.of(inst("i1", "wecom")));
        FakeListener l = new FakeListener("wecom");
        l.explodeOnStart = true;

        @SuppressWarnings("unchecked")
        ObjectProvider<ImInboundListener> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(l));

        ImInboundManager m = new ImInboundManager();
        ReflectionTestUtils.setField(m, "channelService", channelService);
        ReflectionTestUtils.setField(m, "chatService", mock(ImChatService.class));
        ReflectionTestUtils.setField(m, "router", mock(ImInboundRouter.class));
        ReflectionTestUtils.setField(m, "listeners", provider);

        assertThat(m.start("i1")).isFalse();
        verify(channelService).markStatus("i1", "error", "入站启动失败（见后端日志）");
    }
}

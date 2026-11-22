package com.nexusai.application.agent;

import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerRuntimeAdapter;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sendmessage-cross-session · B2-fix] 运行中 drain 的<b>消费前复核</b>（revalidate）窄测试。
 *
 * <p>纯对象、无 Spring 容器、无 DB：真实 {@link AgentState} + 真实 {@link NotificationQueue.QueueItem}
 * + 真实 {@link PeerRuntimeAdapter}（字段直注）+ 真实 {@link PeerInboundPolicy}（设置源可变）。
 *
 * <p><b>RED tooth</b>：把 {@code LlmAgentLoop.injectPeerQueuedItem} 里的
 * {@code adapter.revalidateForConsumption(...)} 及其非 ACCEPT 分支删掉（恢复成「无条件投影」）⇒
 * {@code holdDoesNotReachState} 变红（append 发生 + held 为空），其余用例保持绿。
 *
 * <p>「不落库」的判据 = {@link AgentState#setAppendListener} 武装（= 生产上
 * {@code ChatService.persistAppendedMessage} 的实时落库通道）后零次回调。
 */
class PeerDrainRevalidateTest {

    private static final String RECEIVER = "sess-recv-reval";
    private static final String SENDER = "sess-src-reval";

    private AtomicReference<String> setting;
    private PeerRuntimeAdapter adapter;
    private PeerMessageRouter.InMemoryHeldPeerStore heldStore;
    private AtomicInteger persistCalls;

    @BeforeEach
    void setUp() {
        PeerInboundPolicy policy = new PeerInboundPolicy();
        setting = new AtomicReference<>("accept");
        // 设置源每次现读（生产 = SettingsService.get().crossSessionInbound()，不缓存）
        policy.setSettingsSource(receiverSessionId -> setting.get());
        heldStore = new PeerMessageRouter.InMemoryHeldPeerStore();
        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "heldStore", heldStore);
        persistCalls = new AtomicInteger();
    }

    private AgentState newState() {
        AgentState state = new AgentState("sys", RECEIVER, com.nexusai.application.agent.subagent.AgentContext.createAgentId());
        // 落库通道探针（生产 = ChatService.persistAppendedMessage）
        state.setAppendListener(m -> persistCalls.incrementAndGet());
        return state;
    }

    /** peer 队列项（生产同构：mode=prompt + workload=peer-message + agentId=null + peerEnvelope）。 */
    private static NotificationQueue.QueueItem peerItem(String messageId) {
        PeerEnvelope env = PeerEnvelope.create(messageId, "alpha", SENDER, "alpha",
            PermissionMode.DEFAULT, List.of(), 7L, "摘要", false);
        return new NotificationQueue.QueueItem(
            "hello from peer", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null /* agentId=null：主线程 */, messageId, true /* isMeta */, "peer-message",
            true /* skipSlashCommands */, new NotificationQueue.MessageOrigin("peer", null),
            RECEIVER, null, null, null, null, null,
            null /* [V306] notifyChats：peer 项无通知目标 */, env);
    }

    @Test
    @DisplayName("ACCEPT：投影 + 登记 + append（进 state，触发实时落库通道一次）")
    void acceptInjectsIntoState() {
        AgentState state = newState();
        List<AgentState.InjectedQueuedMessage> injected = new ArrayList<>();

        boolean consumed = LlmAgentLoop.injectPeerQueuedItem(
            state, peerItem("peer-reval-1"), injected, adapter);

        assertThat(consumed).as("ACCEPT ⇒ 消费").isTrue();
        assertThat(state.rawMessages()).hasSize(1);
        ChatMessageDto row = state.rawMessages().get(0);
        assertThat(row.id()).isEqualTo("peer-reval-1");
        assertThat(row.queuedOrigin()).isEqualTo("peer");
        assertThat(row.isMeta()).isTrue();
        assertThat(row.content()).isEqualTo("hello from peer");
        assertThat(injected).as("登记 1 条（author/subtype 同源）").hasSize(1);
        assertThat(injected.get(0).author()).isEqualTo(SENDER);
        assertThat(injected.get(0).subtype()).isEqualTo("agent_message");
        assertThat(persistCalls).as("ACCEPT ⇒ append 触发落库通道一次").hasValue(1);
        assertThat(heldStore.list(RECEIVER)).as("ACCEPT 不进 held").isEmpty();
    }

    @Test
    @DisplayName("策略变为 hold ⇒ 不进 state / 不落库，只写注入的 held store")
    void holdDoesNotReachState() {
        setting.set("hold");
        AgentState state = newState();
        List<AgentState.InjectedQueuedMessage> injected = new ArrayList<>();

        boolean consumed = LlmAgentLoop.injectPeerQueuedItem(
            state, peerItem("peer-reval-2"), injected, adapter);

        assertThat(consumed).as("HOLD ⇒ 不消费").isFalse();
        assertThat(state.rawMessages()).as("⛔ 不进 state（无 append）").isEmpty();
        assertThat(injected).as("⛔ 不登记").isEmpty();
        assertThat(persistCalls).as("⛔ 不落库（append 通道零回调）").hasValue(0);
        assertThat(state.peerExecutionOrigin()).as("⛔ 不盖章来源").isNull();
        List<PeerMessageRouter.HeldMessage> held = heldStore.list(RECEIVER);
        assertThat(held).as("写注入的 held store（未审批不得入历史）").hasSize(1);
        assertThat(held.get(0).messageId()).isEqualTo("peer-reval-2");
        assertThat(held.get(0).body()).isEqualTo("hello from peer");
        assertThat(held.get(0).reason()).isEqualTo(PeerInboundPolicy.Reason.EXPLICIT_HOLD);
    }

    @Test
    @DisplayName("refuse ⇒ 丢弃：不进 state，也不写 held（无任何副作用）")
    void refuseDropsWithoutSideEffects() {
        setting.set("refuse");
        AgentState state = newState();
        List<AgentState.InjectedQueuedMessage> injected = new ArrayList<>();

        boolean consumed = LlmAgentLoop.injectPeerQueuedItem(
            state, peerItem("peer-reval-3"), injected, adapter);

        assertThat(consumed).isFalse();
        assertThat(state.rawMessages()).isEmpty();
        assertThat(injected).isEmpty();
        assertThat(persistCalls).hasValue(0);
        assertThat(heldStore.list(RECEIVER)).as("REFUSE 不写 held").isEmpty();
    }

    @Test
    @DisplayName("未接线（adapter=null）⇒ 不消费（绝不放行未经复核的 peer 正文）")
    void unwiredDoesNotConsume() {
        AgentState state = newState();
        List<AgentState.InjectedQueuedMessage> injected = new ArrayList<>();

        boolean consumed = LlmAgentLoop.injectPeerQueuedItem(
            state, peerItem("peer-reval-4"), injected, null);

        assertThat(consumed).isFalse();
        assertThat(state.rawMessages()).isEmpty();
        assertThat(injected).isEmpty();
        assertThat(persistCalls).hasValue(0);
    }
}

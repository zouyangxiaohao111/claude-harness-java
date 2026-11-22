package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.CronIdleExecutor;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [sendmessage-cross-session · B2] 消费前复核（revalidate）与模式类一致性：
 * <ul>
 *   <li>设置「auto」时按发送方/接收方模式类判定（{@code MODE_MATCH} → ACCEPT，`MODE_MISMATCH` → HOLD）；</li>
 *   <li>设置/模式在投递后变化 ⇒ <b>消费前</b> revalidate 生效（旧 accept 不会被直接塞进模型）；</li>
 *   <li>HOLD / REFUSE 都不落库、不起轮（HOLD 回写 held 存储）。</li>
 * </ul>
 *
 * <p><b>RED tooth</b>：把 {@code PeerInboundPolicy.modeClass} 的 DONT_ASK 归到 NON_PROMPTING
 * ⇒ 第 1 例红；把 {@code CronIdleExecutor.handlePeerQueuedItem} 的 revalidate 删掉（直接起轮）
 * ⇒ 第 2/3 例红（HOLD/REFUSE 仍 run 且落库）。
 */
@DisplayName("[B2] 消费前 revalidate + 模式类一致性（HOLD/REFUSE 不入历史）")
class PeerModeParityTest {

    private NotificationQueue queue;
    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerRuntimeAdapter adapter;
    private CronIdleExecutor executor;
    private MessageService messageService;
    private String setting;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        registry = new PeerSessionRegistry();
        registry.clear();
        policy = new PeerInboundPolicy();
        setting = "auto";
        policy.setSettingsSource(receiverSessionId -> setting);

        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "peerIdleSubscriptions", new PeerIdleSubscriptions());
        ReflectionTestUtils.setField(adapter, "heldPeerStore",
            new PeerMessageRouter.InMemoryHeldPeerStore());
        ReflectionTestUtils.invokeMethod(adapter, "wire");
        // ⚠️ 必须在 wire() 之后安装：wire() 会用 SettingsService 源覆盖 policy 的设置源
        //   （本用例无 Spring/SettingsService，覆盖后即为「本测试的可变设置」）。
        policy.setSettingsSource(receiverSessionId -> setting);

        messageService = Mockito.mock(MessageService.class);
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        ReflectionTestUtils.setField(executor, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(executor, "peerRuntimeAdapter", adapter);
        ReflectionTestUtils.setField(executor, "messageService", messageService);
        // 通道就绪（canDispatch 三闸通过）；同步执行 Executor ⇒ 断言时任务体已跑完。
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.nexusai.application.agent.LlmAgentLoop> provider =
            Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        Mockito.when(provider.getObject()).thenReturn(Mockito.mock(com.nexusai.application.agent.LlmAgentLoop.class));
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        ReflectionTestUtils.setField(executor, "cronExecutor", (java.util.concurrent.Executor) Runnable::run);
    }

    @Test
    @DisplayName("auto：模式类匹配 → ACCEPT；接收方切到非弹窗类后 → HOLD（消费前重新判定生效）")
    void revalidateReflectsModeChangeBeforeConsumption() {
        String source = "sess-srcm" + tail();
        String target = "sess-tgtm" + tail();
        registry.register(new PeerSessionRegistry.Registration(source, "src", null, PermissionMode.DEFAULT, 0L));
        PeerSessionRegistry.EndpointHandle targetHandle = registry.register(
            new PeerSessionRegistry.Registration(target, "tgt", null, PermissionMode.DEFAULT, 0L));

        PeerEnvelope envelope = PeerEnvelope.create("peer-mode-1", "src [abcdef]", source, "src",
            PermissionMode.DEFAULT, List.of(), 1L, "summary", false);

        PeerInboundPolicy.Outcome before = adapter.revalidateForConsumption(envelope, target);
        assertThat(before.decision()).as("PROMPTING vs PROMPTING ⇒ ACCEPT")
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(before.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_MATCH);

        // 接收方模式在投递后变化：BYPASS_PERMISSIONS = NON_PROMPTING ≠ 发送方 PROMPTING
        assertThat(registry.updateMode(target, targetHandle.endpointEpoch(),
            PermissionMode.BYPASS_PERMISSIONS, 1L)).isTrue();

        PeerInboundPolicy.Outcome after = adapter.revalidateForConsumption(envelope, target);
        assertThat(after.decision()).as("★ 消费前重新判定：旧 accept 不得沿用")
            .isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(after.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_MISMATCH);
    }

    @Test
    @DisplayName("HOLD：不落库、不起轮，回写 held 存储（待审批）")
    void holdConsumptionDoesNotPersistOrRun() {
        setting = "hold";
        String target = "sess-tgth" + tail();
        registry.register(new PeerSessionRegistry.Registration(target, "tgt-h", null, PermissionMode.DEFAULT, 0L));
        NotificationQueue.QueueItem item = PeerIdleWakeTest.peerItem(target, "peer-mode-hold");

        // 直接以「已出队的一批」传入（poll 的等价物）；HOLD 不得把条目重新入队/入历史。
        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(item));

        verify(messageService, never()).createQueuedUserMessage(
            anyString(), anyString(), any(), any(), anyBoolean(), anyString(), any(), any(), anyString(), anyString(), any());
        verify(messageService, never()).existsById(anyString());
        assertThat(heldOf(target)).as("★ HOLD → 回写 held（未审批不入历史）").isEqualTo(1);
        assertThat(queue.size()).as("不留在可消费队列").isZero();
    }

    @Test
    @DisplayName("REFUSE：丢弃且不落 held、不落库、不起轮")
    void refuseConsumptionDiscards() {
        setting = "refuse";
        String target = "sess-tgtr" + tail();
        registry.register(new PeerSessionRegistry.Registration(target, "tgt-r", null, PermissionMode.DEFAULT, 0L));
        NotificationQueue.QueueItem item = PeerIdleWakeTest.peerItem(target, "peer-mode-refuse");

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(item));

        verify(messageService, never()).createQueuedUserMessage(
            anyString(), anyString(), any(), any(), anyBoolean(), anyString(), any(), any(), anyString(), anyString(), any());
        assertThat(heldOf(target)).as("REFUSE 不写 held").isZero();
        assertThat(queue.size()).as("不留在队列").isZero();
    }

    private int heldOf(String target) {
        return ((PeerMessageRouter.InMemoryHeldPeerStore) ReflectionTestUtils.getField(adapter, "heldStore"))
            .list(target).size();
    }

    private static String tail() {
        return UUID.randomUUID().toString().substring(0, 4).replace('-', 'f').replaceAll("[^A-Za-z0-9]", "f");
    }
}

package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.chat.ChatPeerEndpointActivator;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 会话解析「DB 优先」：<b>会话记录（存在且未删除）判可达</b>，内存注册表只提供
 * 运行状态 / endpoint 代次；内存残留不得让已删除会话复活。
 *
 * <h2>口径</h2>
 * <p>产品口径（用户裁定）：会话记录在即可投递并唤醒，只有不存在 / 已删除才拒。
 * 解析顺序 = DB 判存在 → 内存取代次/状态（缺 endpoint 时按需登记）；⛔ 不是「内存空才回落 DB」。
 *
 * <h2>RED tooth（旧实现）</h2>
 * <ul>
 *   <li>「仅内存」实现（无 DB 通道）⇒ {@link #registryWithoutTargetResolvesByIdAndName()} 红；</li>
 *   <li>预先查内存再查 DB（旧顺序）⇒ {@link #deletedSessionResidueIsRejectedByIdAndName()} 红
 *       （残留 endpoint 会命中并投递）。</li>
 * </ul>
 */
@DisplayName("[DB 优先] 会话可达性：记录在即可投递；内存残留不复活")
class PeerRestartReachabilityTest {

    private static final String SENDER = "sess-5e4d0001";
    private static final String EXISTING = "sess-e1a2b3c4";
    private static final String RUNNING = "sess-c0ffee01";
    private static final String DELETED = "sess-deadbeef";
    private static final String MISSING = "sess-1010dead";

    private PeerSessionRegistry registry;
    private MessageRecipientResolver resolver;
    private PeerMessageRouter router;
    private ChatService chat;
    private final AtomicInteger deliveries = new AtomicInteger();
    private final List<PeerEnvelope> envelopes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        // 发送方正在跑这一轮（唯一必然 live 的 endpoint）；目标会话都不在运行期注册表里
        registry.register(new PeerSessionRegistry.Registration(
            SENDER, "alpha-peer", "/p", PermissionMode.DEFAULT, 0L));
        // 运行态真源 = PeerRuntimeAdapter（生产）。这里用固定映射：只有 RUNNING 会话在跑。
        registry.setStatusSource(id -> RUNNING.equals(id)
            ? PeerSessionRegistry.Status.RUNNING : PeerSessionRegistry.Status.IDLE);

        chat = new ChatService();
        ReflectionTestUtils.setField(chat, "sessionMapper", sessionMapper());
        ReflectionTestUtils.setField(chat, "peerSessionRegistry", registry);

        resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(registry);
        resolver.setAgentNameRegistry(new AgentNameRegistry());
        // 生产接线：解析侧按接口取 ChatPeerEndpointActivator（ChatService 自己不再实现接口，
        // 否则 @Async + JDK 代理会让按具体类型注入 ChatService 的调用方启动失败）。
        resolver.setEndpointActivator(new ChatPeerEndpointActivator(providerOf(chat)));

        PeerInboundPolicy policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> "auto");
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setIdleSubscriptions(new PeerIdleSubscriptions());
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            deliveries.incrementAndGet();
            envelopes.add(envelope);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "stub", null);
        });
    }

    /** 测试用 ObjectProvider：单测没有 Spring 容器，直接把给定实例交出去。 */
    private static org.springframework.beans.factory.ObjectProvider<ChatService> providerOf(ChatService chat) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public ChatService getObject() {
                return chat;
            }
        };
    }

    /**
     * 会话表：EXISTING / RUNNING 在，MISSING / DELETED 不在。
     * <p>会话表无软删除列 ⇒ 删除即消失（行不存在 = 从未创建 或 已删除）。
     */
    private static SessionMapper sessionMapper() {
        Map<String, SessionRecord> rows = new LinkedHashMap<>();
        rows.put(EXISTING, session(EXISTING, "beta-peer"));
        rows.put(RUNNING, session(RUNNING, "gamma-peer"));
        SessionMapper mapper = Mockito.mock(SessionMapper.class);
        Mockito.when(mapper.selectOneById(anyString()))
            .thenAnswer(inv -> rows.get(inv.getArgument(0)));
        Mockito.when(mapper.selectListByQuery(ArgumentMatchers.any()))
            .thenAnswer(inv -> new ArrayList<>(rows.values()));
        return mapper;
    }

    private static SessionRecord session(String id, String title) {
        SessionRecord record = new SessionRecord();
        record.setId(id);
        record.setTitle(title);
        return record;
    }

    private MessageRecipientResolver.Target resolve(String to) {
        return resolver.resolve(new MessageRecipientResolver.Request(to, SENDER, null, false));
    }

    private PeerMessageRouter.Receipt deliver(MessageRecipientResolver.Target target) {
        return router.send(new PeerMessageRouter.SendRequest(
                target.sessionId(), target.endpointEpoch(), target.displayName(), SENDER, "alpha-peer",
                "alpha-peer [aaaaaa]", "hi", "s", false, false, List.of()),
            PermissionMode.DEFAULT);
    }

    // ══════════════════════════ a) 内存没有目标 endpoint ══════════════════════════

    @Test
    @DisplayName("a) 内存注册表没有目标 endpoint ⇒ 按 sess- id 与名字都解析并投递")
    void registryWithoutTargetResolvesByIdAndName() {
        // 前提：注册表只有发送方（重启后目标会话都未登记）
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.resolve(EXISTING)).isEmpty();

        MessageRecipientResolver.Target byId = resolve(EXISTING);
        assertThat(byId.kind())
            .as("★ DB 记录在 ⇒ 必须解析成 SESSION（仅内存实现 = UNRESOLVED）")
            .isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(byId.sessionId()).isEqualTo(EXISTING);
        assertThat(byId.endpointEpoch()).isPositive();
        assertThat(deliver(byId).status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(deliveries).as("按 id 投递成功（入队，由空闲执行器唤醒目标）").hasValue(1);

        MessageRecipientResolver.Target byName = resolve("beta-peer");
        assertThat(byName.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(byName.sessionId()).isEqualTo(EXISTING);
        assertThat(deliver(byName).status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(deliveries).hasValue(2);

        // 名字 [ref] 形态同样按 DB 记录补登记后可投递
        MessageRecipientResolver.Target byRef = resolve("beta-peer [" + byName.ref() + "]");
        assertThat(byRef.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(byRef.sessionId()).isEqualTo(EXISTING);
    }

    @Test
    @DisplayName("a2) 注册表完全为空 ⇒ 仍按 DB 把 id 与名字都解析成会话地址")
    void emptyRegistryStillResolvesByIdAndName() {
        registry.clear();
        assertThat(registry.size()).isZero();

        MessageRecipientResolver.Target byId = resolve(EXISTING);
        assertThat(byId.kind()).isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(byId.sessionId()).isEqualTo(EXISTING);

        MessageRecipientResolver.Target byName = resolve("beta-peer");
        assertThat(byName.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(byName.sessionId()).isEqualTo(EXISTING);
    }

    // ══════════════════════════ b) DB 已删除 + 内存残留 ══════════════════════════

    @Test
    @DisplayName("b) DB 已删除但内存有残留 endpoint ⇒ 直接拒绝，残留不得复活、零投递")
    void deletedSessionResidueIsRejectedByIdAndName() {
        // 残留：会话行已删，但注册表里仍有一个旧 endpoint（例如删除后未注销的幽灵）
        registry.register(new PeerSessionRegistry.Registration(
            DELETED, "ghost-peer", "/p", PermissionMode.DEFAULT, 0L));
        assertThat(registry.resolve(DELETED)).as("前提：内存残留存在").isPresent();

        MessageRecipientResolver.Target byId = resolve(DELETED);
        assertThat(byId.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(byId.errorMessage())
            .as("★ DB 判已删除：文案必须说「不存在/已删除」，⛔ 不说暂时不在线")
            .contains("does not exist or was deleted")
            .doesNotContain("no live session");

        MessageRecipientResolver.Target byName = resolve("ghost-peer");
        assertThat(byName.kind())
            .as("★ DB 没有该标题的会话 ⇒ 内存残留按旧标题也不得命中")
            .isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);

        assertThat(deliveries).as("零投递").hasValue(0);
    }

    // ══════════════════════════ c) DB 有记录但未运行 ══════════════════════════

    @Test
    @DisplayName("c) DB 有记录但未运行 ⇒ 可投递并按需登记 endpoint（唤醒）")
    void dbRecordNotRunningIsDeliverableAndWakes() {
        assertThat(registry.status(EXISTING)).isEqualTo(PeerSessionRegistry.Status.IDLE);
        assertThat(registry.resolve(EXISTING)).isEmpty();

        MessageRecipientResolver.Target target = resolve(EXISTING);
        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(registry.resolve(EXISTING)).as("按需登记后注册表有该 endpoint").isPresent();
        assertThat(registry.status(EXISTING)).as("状态仍取自运行态真源 = IDLE（不伪造 running）")
            .isEqualTo(PeerSessionRegistry.Status.IDLE);

        assertThat(deliver(target).status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(deliveries).hasValue(1);
        assertThat(envelopes.get(0).fromSessionId()).isEqualTo(SENDER);
    }

    // ══════════════════════════ d) DB 与内存都在 ══════════════════════════

    @Test
    @DisplayName("d) DB 与内存都在 ⇒ 正常解析；代次/状态取自内存（不换代次）")
    void dbAndMemoryBothPresentStatusFromMemory() {
        // 生产登记链：先登记（写入 ChatService 句柄表），保证后续解析是幂等更新而非重登记
        chat.ensurePeerEndpointLive(RUNNING);
        long epochBefore = registry.resolve(RUNNING).orElseThrow().endpointEpoch();
        assertThat(registry.status(RUNNING)).isEqualTo(PeerSessionRegistry.Status.RUNNING);

        MessageRecipientResolver.Target byId = resolve(RUNNING);
        assertThat(byId.kind()).isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(byId.endpointEpoch())
            .as("★ 代次来自内存；已登记的会话不得因解析而换代次（旧句柄会失效）")
            .isEqualTo(epochBefore);
        assertThat(registry.resolve(RUNNING).orElseThrow().endpointEpoch()).isEqualTo(epochBefore);
        assertThat(registry.status(RUNNING)).isEqualTo(PeerSessionRegistry.Status.RUNNING);

        MessageRecipientResolver.Target byName = resolve("gamma-peer");
        assertThat(byName.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(byName.sessionId()).isEqualTo(RUNNING);
        assertThat(byName.endpointEpoch()).isEqualTo(epochBefore);

        assertThat(deliver(byId).status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(deliveries).hasValue(1);
    }

    // ══════════════════════════ 不存在（无残留） ══════════════════════════

    @Test
    @DisplayName("会话不存在（已删除）⇒ 明确「不存在或已删除」文案，零投递")
    void missingSessionIsNotFoundWithDistinctMessage() {
        MessageRecipientResolver.Target target = resolve(MISSING);

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(target.errorMessage())
            .as("必须与「暂不可投递」区分：不存在/已删除")
            .contains("does not exist or was deleted")
            .doesNotContain("no live session");
        assertThat(registry.resolve(MISSING)).isEmpty();
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("标题未命中也同样走 not-found（DB 无该标题 ⇒ 不伪造、不回落）")
    void missingTitleIsNotFound() {
        MessageRecipientResolver.Target target = resolve("ghost-session");
        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(deliveries).hasValue(0);
    }
}

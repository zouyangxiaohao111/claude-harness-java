package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-20 真实 E2E 缺陷 3] 信封 from-name 与实际展示名必须同一来源。
 *
 * <h2>缺陷</h2>
 * <p>接收方看到的信封是 {@code from="team-lead [c1c93c]" from-name="alpha-peer"}：发送侧
 * from/from-name 用 {@code SendMessageTool.resolveSenderName}（主会话恒 {@code team-lead}），
 * 而 turn-0 重建信封（{@link PeerExecutionOrigin#toEnvelope}）把 from-name 换成了会话标题。
 *
 * <h2>修复</h2>
 * <p>统一来源（服务端数据，非模型自报）：<b>ListAgents 展示名</b>（registry addressName）
 * → 会话标题 → 来源 session id。信封 from 与 from-name 由同一个名字派生；重建信封时
 * from-name 从 from 里取名字部分（同一解析器 {@link PeerAddressing#parseNameWithRef}）。
 *
 * <h2>RED tooth（旧实现）</h2>
 * <ul>
 *   <li>发送侧改回 {@code request.sourceName()} ⇒ 第 1 例红（from-name=team-lead，展示名=alpha-peer）；</li>
 *   <li>{@code toEnvelope} 改回「有标题就用标题」⇒ 第 2 例红（from-name=alpha-peer，from=team-lead）。</li>
 * </ul>
 */
@DisplayName("[E2E D3] 信封 from-name 与展示名同源")
class PeerDisplayNameConsistencyTest {

    private static final String SELF = "sess-name01";
    private static final String TARGET = "sess-name02";

    private PeerSessionRegistry registry;
    private PeerMessageRouter router;
    private final List<PeerEnvelope> envelopes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            SELF, "alpha-peer", "/p", PermissionMode.DEFAULT, 0L));
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "beta-peer", "/p", PermissionMode.DEFAULT, 0L));

        PeerInboundPolicy policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> "auto");
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setIdleSubscriptions(new PeerIdleSubscriptions());
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            envelopes.add(envelope);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "stub", null);
        });
    }

    private PeerEnvelope sendFromMainSession(String toolSourceName) {
        PeerMessageRouter.Receipt receipt = router.send(new PeerMessageRouter.SendRequest(
                TARGET, registry.resolve(TARGET).orElseThrow().endpointEpoch(), "beta-peer",
                SELF, toolSourceName, toolSourceName + " [c1c93c]", "hi", "s",
                false, false, List.of()),
            PermissionMode.DEFAULT);
        assertThat(receipt.status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        return envelopes.get(envelopes.size() - 1);
    }

    @Test
    @DisplayName("发送侧：from-name = ListAgents 展示名（不是工具传入的 team-lead）")
    void envelopeFromNameMatchesListedAddressName() {
        PeerEnvelope envelope = sendFromMainSession("team-lead");
        String listedName = registry.resolve(SELF).orElseThrow().addressName();

        assertThat(listedName).isEqualTo("alpha-peer");
        assertThat(envelope.fromName())
            .as("★ from-name 必须等于 ListAgents 展示名（旧实现 = team-lead）")
            .isEqualTo(listedName);
        assertThat(envelope.fromAddress())
            .as("from 与 from-name 同一来源")
            .isEqualTo(PeerAddressing.formatAddress(listedName, SELF));

        String payload = envelope.renderModelPayload("hi");
        assertThat(payload).contains("from=\"" + envelope.fromAddress() + "\"");
        assertThat(payload).contains("from-name=\"" + listedName + "\"");
    }

    @Test
    @DisplayName("重建信封（turn-0）：from-name 取 from 的名字部分，不被会话标题覆盖")
    void rebuiltEnvelopeKeepsAddressName() {
        // 旧实现的信封形状：from 用发送方名、from-name 用会话标题（两者不一致）
        PeerExecutionOrigin origin = new PeerExecutionOrigin(
            SELF, "alpha-peer", PeerExecutionOrigin.SOURCE_KIND_PEER, "peer-d3-1", 1L,
            "default", "team-lead [c1c93c]", List.of(PeerEnvelope.hopToken(SELF)));

        PeerEnvelope rebuilt = origin.toEnvelope("s", false);

        assertThat(rebuilt.fromName())
            .as("★ from-name 必须与 from 同源（旧实现 = 会话标题 alpha-peer）")
            .isEqualTo("team-lead");
        assertThat(rebuilt.fromAddress()).isEqualTo("team-lead [c1c93c]");
        assertThat(rebuilt.sourceTitle()).as("标题仍独立保留（展示快照）").isEqualTo("alpha-peer");
    }

    @Test
    @DisplayName("会话无标题 ⇒ 展示名回落 session id（不伪造）")
    void blankTitleFallsBackToSessionId() {
        registry.register(new PeerSessionRegistry.Registration(
            SELF, null, "/p", PermissionMode.DEFAULT, 0L));
        PeerEnvelope envelope = sendFromMainSession("team-lead");
        assertThat(envelope.fromName()).isEqualTo(SELF);
        assertThat(envelope.fromAddress()).startsWith(SELF + " [");
    }
}

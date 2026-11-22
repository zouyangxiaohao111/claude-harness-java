package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.domain.session.SessionService;
import com.nexusai.repository.session.entity.SessionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PeerMessageRouter#sendSessionCreateLaunch} 的 {@code derived} 窄豁免。
 *
 * <p>语义：创建者向它刚创建的<b>平级</b>会话投递首条任务时，只把入站 HOLD 转 accept；
 * 普通 SendMessage、显式 REFUSE、创建来源不匹配一律保持原行为。
 *
 * <p>信任根 = 数据库 {@code sessions.derived_from_session_id}，不读正文/工具输入。
 */
@DisplayName("PeerMessageRouter · derived 窄豁免")
class PeerMessageRouterDerivedExemptionTest {

    private static final String CREATOR = "sess-creator1";
    private static final String PEER = "sess-peer0001";

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private SessionService sessionService;
    private String setting = "hold";
    private long targetEpoch;
    private final List<PeerEnvelope> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            CREATOR, "Creator", "/p", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            PEER, "Peer", "/p", PermissionMode.DEFAULT, 1L));
        targetEpoch = registry.resolve(PEER).orElseThrow().endpointEpoch();

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> setting);

        sessionService = mock(SessionService.class);
        when(sessionService.getRecord(PEER)).thenReturn(record(PEER, CREATOR));

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(new PeerMessageRouter.InMemoryHeldPeerStore());
        router.setSessionService(sessionService);
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            delivered.add(envelope);
            return new PeerMessageRouter.Receipt(
                PeerMessageRouter.Status.ACCEPTED, envelope.messageId(), "stub", null);
        });
    }

    private static SessionRecord record(String id, String createdBy) {
        SessionRecord r = new SessionRecord();
        r.setId(id);
        r.setDerivedFromSessionId(createdBy);
        r.setDerivedDepth(1);
        return r;
    }

    private PeerMessageRouter.SendRequest request() {
        return new PeerMessageRouter.SendRequest(PEER, targetEpoch, "Peer", CREATOR, "Creator",
            "Creator", "first task", "summary", false, false, List.of());
    }

    @Test
    @DisplayName("创建者→刚创建的平级会话：hold 下首条任务自动通过（admission=auto/derived）")
    void derivedLaunchOverridesHold() {
        PeerMessageRouter.Receipt r = router.sendSessionCreateLaunch(
            request(), PermissionMode.DEFAULT, "peer-launch-1");

        assertEquals(PeerMessageRouter.Status.ACCEPTED, r.status());
        assertEquals(1, delivered.size());
        assertEquals(PeerEnvelope.ADMISSION_AUTO, delivered.get(0).admission());
        assertEquals(PeerEnvelope.REASON_DERIVED, delivered.get(0).admissionReason());
    }

    @Test
    @DisplayName("普通 SendMessage 仍遵守 hold")
    void plainSendStillHeld() {
        PeerMessageRouter.Receipt r = router.send(request(), PermissionMode.DEFAULT, "peer-plain-1");

        assertEquals(PeerMessageRouter.Status.HELD, r.status());
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("创建来源不匹配 ⇒ 仍 HELD（豁免不可伪造）")
    void notCreatedBySourceStillHeld() {
        when(sessionService.getRecord(PEER)).thenReturn(record(PEER, "sess-someone-else"));

        PeerMessageRouter.Receipt r = router.sendSessionCreateLaunch(
            request(), PermissionMode.DEFAULT, "peer-launch-2");

        assertEquals(PeerMessageRouter.Status.HELD, r.status());
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("显式 refuse 不被 derived 豁免绕过")
    void explicitRefuseNotOverridden() {
        setting = "refuse";

        PeerMessageRouter.Receipt r = router.sendSessionCreateLaunch(
            request(), PermissionMode.DEFAULT, "peer-launch-3");

        assertEquals(PeerMessageRouter.Status.REFUSED, r.status());
        assertTrue(delivered.isEmpty());
    }
}

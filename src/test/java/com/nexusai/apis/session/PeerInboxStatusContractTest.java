package com.nexusai.apis.session;

import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.infra.exception.ForbiddenException;
import com.zjky.pro.framework.common.pojo.CommonResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 2 回归：审批端点的<b>响应契约 = HTTP 200 + 业务码</b>（用户 2026-11-20 裁定）。
 *
 * <h2>WHY</h2>
 * <p>改造前：approve 不论成败都 200 且 body 只有 {@code status}；reject 的「不存在」却抛 403。
 * 同一组动作两套口径 ⇒ 前端按 HTTP 状态判成功会误判（把「没投递」当成功）。
 *
 * <p>现在的契约（控制器方法直接返回 body ⇒ Spring 200）：
 * <ul>
 *   <li>200 + {@code {code, messageId, status, detail}}；{@code code} 闭集
 *       {@code ACCEPTED | HELD | REFUSED | NO_LIVE_SESSION | SOURCE_NOT_LIVE | NOT_DELIVERED |
 *       NOT_WIRED | APPROVAL_REJECTED | REJECTED | NOT_FOUND}；</li>
 *   <li>reject-all → 200 + {@code {code:"REJECTED", rejected:N}}（无 pending → 0）；</li>
 *   <li>403 只留给「审批主体不可信 / 未接线」这类鉴权与装配问题。</li>
 * </ul>
 *
 * <h2>RED teeth</h2>
 * <p>去掉 {@code code} 字段 / 让 approve 失败抛 403 ⇒ 第 1~4 条红（前端无法只按 code 判断）。
 */
@DisplayName("PeerInboxController · 200 + 业务码契约")
class PeerInboxStatusContractTest {

    private static final String OWNER = "sess-cont0001";
    private static final String SOURCE = "sess-cont0002";
    private static final String MESSAGE_ID = "msg-contract-1";

    private PeerSessionRegistry registry;
    private PeerMessageRouter router;
    private PeerMessageRouter.InMemoryHeldPeerStore store;
    private PeerInboxController controller;
    private String setting = "hold";
    private long ownerEpoch;

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            OWNER, "Owner", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source", "/tmp", PermissionMode.DEFAULT, 1L));
        ownerEpoch = registry.resolve(OWNER).orElseThrow().endpointEpoch();

        PeerInboundPolicy policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> setting);

        store = new PeerMessageRouter.InMemoryHeldPeerStore();
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        controller = new PeerInboxController();
        controller.setRouter(router);

        PeerEnvelope envelope = new PeerEnvelope(MESSAGE_ID, "peer-a [abcdef]", SOURCE,
            "Peer A", "default", List.of(PeerEnvelope.hopToken(SOURCE)), ownerEpoch, "summary", false);
        store.hold(new PeerMessageRouter.HeldMessage(MESSAGE_ID, OWNER, ownerEpoch, envelope,
            "body text", PeerInboundPolicy.Reason.EXPLICIT_HOLD, Instant.now()));
    }

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
    }

    @Test
    @DisplayName("hold 设置下 approve → code=ACCEPTED（不再返回 HELD）")
    void approveUnderHoldReturnsAcceptedCode() {
        CommonResult<PeerInboxController.HeldActionResult> result =
            controller.approve(OWNER, MESSAGE_ID);
        assertEquals(0, result.getCode());
        assertTrue(result.isSuccess(), "ACCEPTED ⇒ success=true");
        assertEquals("ACCEPTED", result.getData().code());
        assertEquals("ACCEPTED", result.getData().status());
        assertEquals(MESSAGE_ID, result.getData().messageId());
        assertTrue(controller.list(OWNER).getData().isEmpty(), "投递成功后行被消费");
    }

    @Test
    @DisplayName("目标退出 → code=NO_LIVE_SESSION；行直接终态（不可再次批准）")
    void approveNotAchievedUsesBusinessCode() {
        assertTrue(registry.unregister(OWNER, ownerEpoch), "前置：目标退出");

        CommonResult<PeerInboxController.HeldActionResult> result =
            controller.approve(OWNER, MESSAGE_ID);
        assertEquals(404, result.getCode(), "NO_LIVE_SESSION ⇒ 404");
        assertFalse(result.isSuccess());
        assertEquals("NO_LIVE_SESSION", result.getData().code());
        assertTrue(controller.list(OWNER).getData().isEmpty(),
            "批准即结算：失败行直接终态（expired），不再出现在列表");

        // 目标回来后也不能再批（严格 CC：批准不可撤销、不可二次批准）。
        registry.register(new PeerSessionRegistry.Registration(
            OWNER, "Owner", "/tmp", PermissionMode.DEFAULT, 2L));
        assertEquals("APPROVAL_REJECTED",
            controller.approve(OWNER, MESSAGE_ID).getData().code(),
            "已结算的 messageId 只能返回 900");
    }

    @Test
    @DisplayName("reject 不存在 → code=404；reject-all 无 pending → 0 + 0")
    void rejectContractIsUniform() {
        CommonResult<PeerInboxController.HeldActionResult> missing =
            controller.reject(OWNER, "peer-missing");
        assertEquals(404, missing.getCode());
        assertFalse(missing.isSuccess());
        assertEquals("NOT_FOUND", missing.getData().code());

        CommonResult<PeerInboxController.RejectAllResult> all = controller.rejectAll(OWNER);
        assertEquals(0, all.getCode());
        assertTrue(all.isSuccess());
        assertEquals("REJECTED", all.getData().code());
        assertEquals(1, all.getData().rejected(), "前置那条 pending 被批拒");

        CommonResult<PeerInboxController.RejectAllResult> again = controller.rejectAll(OWNER);
        assertEquals(0, again.getCode());
        assertTrue(again.isSuccess());
        assertEquals("REJECTED", again.getData().code());
        assertEquals(0, again.getData().rejected(), "幂等：无 pending 仍是 200 + rejected=0");
    }

    @Test
    @DisplayName("只有鉴权不可信才 403（业务失败一律 200 + code）")
    void onlyAuthFailuresAre403() {
        PeerUiApprovalAuthority.installVerifier(claim -> false);
        assertThrows(ForbiddenException.class, () -> controller.approve(OWNER, MESSAGE_ID));
        assertThrows(ForbiddenException.class, () -> controller.reject(OWNER, MESSAGE_ID));
        assertThrows(ForbiddenException.class, () -> controller.rejectAll(OWNER));
    }
}

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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 批 · 待收件（held）端点归属测试（非 Spring）· 2026-11-19 语义重排后。
 *
 * <p>验证意图：
 * <ul>
 *   <li>默认 local-user（userId 缺省 "default"）下端点正常工作：列表 / 接受一次 / 拒绝；</li>
 *   <li>仍要求 sessionId 归属 + requestId 匹配：错归属不 remove、不批准；</li>
 *   <li>安装更严验证器后拒绝全部操作，且 held 项原样保留（不替攻击者取消）；</li>
 *   <li>接受走 {@code PeerMessageRouter.approveHeld}（重查 live/epoch/策略），拒绝走 {@code rejectHeld}。</li>
 * </ul>
 */
class PeerInboxOwnershipTest {

    private static final String OWNER = "sess-aaaaaaa1";
    private static final String OTHER = "sess-bbbbbbb2";
    private static final String SOURCE = "sess-ccccccc3";
    private static final String MESSAGE_ID = "msg-held-1";

    private PeerSessionRegistry registry;
    private PeerMessageRouter router;
    private PeerInboxController controller;
    private long targetEpoch;

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(new PeerInboundPolicy());
        PeerMessageRouter.InMemoryHeldPeerStore store = new PeerMessageRouter.InMemoryHeldPeerStore();
        router.setHeldStore(store);

        registry.register(new PeerSessionRegistry.Registration(
            OWNER, "Owner", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source", "/tmp", PermissionMode.DEFAULT, 1L));
        targetEpoch = registry.resolve(OWNER).orElseThrow().endpointEpoch();

        PeerEnvelope envelope = new PeerEnvelope(MESSAGE_ID, "peer-a [abcdef]", SOURCE,
            "Peer A", "default", List.of(), targetEpoch, "summary", true);
        store.hold(new PeerMessageRouter.HeldMessage(MESSAGE_ID, OWNER, targetEpoch, envelope, "body text",
            PeerInboundPolicy.Reason.EXPLICIT_HOLD, Instant.now()));

        controller = new PeerInboxController();
        controller.setRouter(router);
    }

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
    }

    @Test
    void defaultLocalUserCanListAndReject() {
        assertEquals("default", PeerUiApprovalAuthority.getUserId());
        assertEquals(List.of(MESSAGE_ID), ids(controller.list(OWNER).getData()));

        CommonResult<PeerInboxController.HeldActionResult> result =
            controller.reject(OWNER, MESSAGE_ID);
        assertEquals(0, result.getCode(), "成功 code=0（HTTP 200 + success=true）");
        assertTrue(result.isSuccess());
        assertEquals("REJECTED", result.getData().code());
        assertEquals("REJECTED", result.getData().status());
        assertTrue(controller.list(OWNER).getData().isEmpty(), "本会话默认用户拒绝后 held 项应被移除");
    }

    @Test
    void strictVerifierRejectsAllAndKeepsHeld() {
        // 更严验证器（模拟后期真实认证未通过）：拒绝全部操作，但 held 项原样保留。
        PeerUiApprovalAuthority.installVerifier(claim -> false);

        assertThrows(ForbiddenException.class, () -> controller.list(OWNER));
        assertThrows(ForbiddenException.class, () -> controller.approve(OWNER, MESSAGE_ID));
        assertThrows(ForbiddenException.class, () -> controller.reject(OWNER, MESSAGE_ID));

        PeerUiApprovalAuthority.installVerifier(null); // 恢复默认 local-user
        assertEquals(List.of(MESSAGE_ID), ids(controller.list(OWNER).getData()), "被拒操作不得消耗 held 项");
    }

    @Test
    void wrongSessionCannotApproveOrRemove() {
        // 错会话：列表看不到别人的 held 项
        assertTrue(controller.list(OTHER).getData().isEmpty(), "错归属不得看到他人 held 项");

        // 错会话批准：code=900 / data.code=APPROVAL_REJECTED（HTTP 200，按 success 判断），且不 remove
        CommonResult<PeerInboxController.HeldActionResult> approve =
            controller.approve(OTHER, MESSAGE_ID);
        assertEquals(900, approve.getCode(), "错归属不得批准（业务失败用数字码表达）");
        assertEquals("APPROVAL_REJECTED", approve.getData().code());
        assertEquals(List.of(MESSAGE_ID), ids(controller.list(OWNER).getData()), "错归属批准不得消耗 held 项");

        // 错会话拒绝：code=404 / data.code=NOT_FOUND（对外不区分「别人的」与「不存在」），且不删除
        CommonResult<PeerInboxController.HeldActionResult> reject =
            controller.reject(OTHER, MESSAGE_ID);
        assertEquals(404, reject.getCode(), "错归属拒绝不得删他人 held 项");
        assertEquals("NOT_FOUND", reject.getData().code());
        assertEquals(List.of(MESSAGE_ID), ids(controller.list(OWNER).getData()), "错归属拒绝不得删除 held 项");
    }

    @Test
    void ownerApproveGoesThroughRouterRevalidation() {
        CommonResult<PeerInboxController.HeldActionResult> result =
            controller.approve(OWNER, MESSAGE_ID);
        // 未接线 DeliveryPort → 接受流程走完重验后返回 NOT_WIRED（不是 APPROVAL_REJECTED）。
        assertEquals(500, result.getCode(), "未列明投递状态兜底 500");
        assertEquals(PeerMessageRouter.Status.NOT_WIRED.name(), result.getData().code());
        assertEquals(PeerMessageRouter.Status.NOT_WIRED.name(), result.getData().status());
        // [批准即结算 · 严格 CC] 投递失败 ⇒ 行直接终态：不再出现在列表、不可再次批准。
        assertTrue(controller.list(OWNER).getData().isEmpty(),
            "投递未达成时行已结算为终态（⛔ 不再保留待重试的 approved）");
        assertEquals(900, controller.approve(OWNER, MESSAGE_ID).getCode(),
            "已结算的行不能二次批准");
        assertTrue(targetEpoch > 0, "endpoint epoch 由 registry 服务端生成");
    }

    private static List<String> ids(List<PeerInboxController.HeldView> views) {
        return views.stream().map(PeerInboxController.HeldView::messageId).toList();
    }
}

package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 1 回归：<b>hold 设置下 approve 必须能投出去</b>（显式批准 = 一次性放行）。
 *
 * <h2>WHY</h2>
 * <p>2026-11-20 跨会话 E2E 实测：{@code cross_session_inbound=hold} 时列表里点「接受一次」
 * 返回 HTTP 200 {@code {"status":"HELD"}}、消息永远投不出去 —— 根因是批准路径
 * （{@code revalidateForApproval}）又拿<b>当前设置</b>重判了一遍，而当前设置仍是 hold。
 *
 * <p>现在的契约：批准只重查四件事 —— 目标仍 live / 代次可解析（不符只记日志）/ 设置不是
 * refuse / 消息仍 pending；⛔ 不再按 auto/hold 重新判定。一次性语义不变，且<b>批准即结算</b>
 * （严格 CC 语义：失败不回到 pending，直接终态并通知发送方）。
 *
 * <h2>RED teeth</h2>
 * <p>把 {@code decideExplicitApproval} 换回 {@code revalidate}（当前设置重判）⇒ 第 1、4 条红
 * （hold/auto-mismatch 下批准返回 HELD、未投递）。
 */
@DisplayName("PeerMessageRouter · hold 设置下 approve 一次性放行")
class PeerApproveUnderHoldTest {

    private static final String TARGET = "sess-hold0001";
    private static final String SOURCE = "sess-hold0002";

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private String setting = "hold";
    private long targetEpoch;
    private final List<String> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target", "/p", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source", "/p", PermissionMode.DEFAULT, 1L));
        targetEpoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> setting);

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(new PeerMessageRouter.InMemoryHeldPeerStore());
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            delivered.add(envelope.messageId());
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "stub", null);
        });
    }

    private String holdOne() {
        PeerMessageRouter.Receipt receipt = router.send(new PeerMessageRouter.SendRequest(
            TARGET, targetEpoch, "Target", SOURCE, "Source", "Source [abcdef]",
            "hello from source", "summary", false, false, List.of()),
            PermissionMode.DEFAULT);
        assertEquals(PeerMessageRouter.Status.HELD, receipt.status(), "前置：hold 设置 → 入 hold");
        return receipt.messageId();
    }

    private List<String> heldIds() {
        return router.listHeld(TARGET, principal()).stream()
            .map(PeerMessageRouter.HeldMessage::messageId).toList();
    }

    private static PeerMessageRouter.ApprovalPrincipal principal() {
        return new PeerMessageRouter.ApprovalPrincipal() {
            @Override
            public String sessionId() {
                return TARGET;
            }

            @Override
            public String requestId() {
                return "req-approve-under-hold";
            }

            @Override
            public boolean trustedUi() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("hold 设置下批准 → ACCEPTED 且只投递一次；再次批准 → APPROVAL_REJECTED（一次性）")
    void approveUnderHoldDelivers() {
        String messageId = holdOne();
        assertEquals("hold", setting, "前置：设置仍是 hold（审批前不得先改设置）");

        PeerMessageRouter.Receipt first = router.approveHeld(messageId, principal());
        assertEquals(PeerMessageRouter.Status.ACCEPTED, first.status(),
            "hold 设置下的显式批准必须投递成功（不得再判 HELD）");
        assertEquals(List.of(messageId), delivered, "应投递一次");
        assertTrue(heldIds().isEmpty(), "接受后 held 项被消费");

        PeerMessageRouter.Receipt second = router.approveHeld(messageId, principal());
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED, second.status(),
            "同 messageId 只能成功一次");
        assertEquals(1, delivered.size(), "不得投递两次");
    }

    @Test
    @DisplayName("设置改为 refuse → 批准失败（REFUSED）且行直接终态（不可重试）")
    void refuseStillBlocks() {
        String messageId = holdOne();
        setting = "refuse";

        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal());
        assertEquals(PeerMessageRouter.Status.REFUSED, receipt.status());
        assertTrue(heldIds().isEmpty(), "批准即结算：失败不再回 pending（严格 CC 语义）");
        assertTrue(delivered.isEmpty(), "不得投递");
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld(messageId, principal()).status(), "失败后不可再次批准");
    }

    @Test
    @DisplayName("目标已退出 → NO_LIVE_SESSION 且行直接终态（不可重试）")
    void targetExitedStillBlocks() {
        String messageId = holdOne();
        assertTrue(registry.unregister(TARGET, targetEpoch), "前置：目标退出");

        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal());
        assertEquals(PeerMessageRouter.Status.NO_LIVE_SESSION, receipt.status());
        assertTrue(heldIds().isEmpty(), "批准即结算：目标离线时行直接终态");
        assertTrue(delivered.isEmpty());
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld(messageId, principal()).status(), "失败后不可再次批准");
    }

    @Test
    @DisplayName("auto + 模式不匹配（发送时 HOLD）→ 显式批准仍投递（不重判 auto）")
    void autoMismatchHeldThenApproveDelivers() {
        // 发送方 dontAsk（UNKNOWN 类），接收方 DEFAULT（PROMPTING）⇒ auto 判 MODE_MISMATCH → HOLD
        setting = "auto";
        PeerMessageRouter.Receipt sent = router.send(new PeerMessageRouter.SendRequest(
            TARGET, targetEpoch, "Target", SOURCE, "Source", "Source [abcdef]",
            "mismatch body", "summary", false, false, List.of()),
            PermissionMode.DONT_ASK);
        assertEquals(PeerMessageRouter.Status.HELD, sent.status(), "前置：auto 模式不匹配 → hold");

        PeerMessageRouter.Receipt receipt = router.approveHeld(sent.messageId(), principal());
        assertEquals(PeerMessageRouter.Status.ACCEPTED, receipt.status(),
            "auto 判据不得在批准路径再判一次");
        assertEquals(List.of(sent.messageId()), delivered);
    }
}

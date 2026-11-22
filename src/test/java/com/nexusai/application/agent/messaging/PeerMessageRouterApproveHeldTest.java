package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PeerMessageRouter#approveHeld} 的<b>批准即结算</b>语义（非 Spring）。
 *
 * <h2>WHY（严格 CC 2.1.328）</h2>
 * <p>CC 没有 approved 中间态：批准 = 从 hold 缓冲一次性取出 + 同步投递；成功回执 delivered，
 * 失败<b>立即结算</b>（策略 refuse→refused；限速/队满→dropped；目标关闭→receiver-closed），
 * 并回执发送方。不能撤销、不能二次批准。本测试钉死四条不变量：
 * <ul>
 *   <li>批准成功 ⇒ 投递一次、行离开待收件列表、状态 accepted（内存实现 statusOf）；</li>
 *   <li>批准失败（目标退出 / 策略 refuse / 投递失败）⇒ 行<b>直接终态</b>，不可再次批准；</li>
 *   <li>错归属 / 不存在的 id ⇒ 900 且不动任何行；</li>
 *   <li>已 accepted 再批 ⇒ 900，不重复投递。</li>
 * </ul>
 *
 * <h2>RED teeth（变异自检）</h2>
 * <p>① 失败仍回 pending（旧 approved 语义）⇒ {@link #targetExitedSettlesTerminally()} /
 * {@link #policyRefuseSettlesTerminally()} / {@link #deliveryFailureIsTerminal()} 红。
 */
@DisplayName("PeerMessageRouter · approveHeld 批准即结算")
class PeerMessageRouterApproveHeldTest {

    private static final String TARGET = "sess-aaaaaaa1";
    private static final String SOURCE = "sess-bbbbbbb2";

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter.InMemoryHeldPeerStore store;
    private PeerMessageRouter router;
    private String setting = "hold";
    private long targetEpoch;
    private PeerMessageRouter.Status deliveryStatus = PeerMessageRouter.Status.ACCEPTED;
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

        store = new PeerMessageRouter.InMemoryHeldPeerStore();
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            delivered.add(envelope.messageId());
            return new PeerMessageRouter.Receipt(deliveryStatus, envelope.messageId(), "stub", null);
        });
    }

    private String holdOne() {
        setting = "hold";
        PeerMessageRouter.Receipt receipt = router.send(new PeerMessageRouter.SendRequest(
            TARGET, targetEpoch, "Target", SOURCE, "Source", "Source [abcdef]",
            "hello from source", "summary", false, false, List.of()),
            PermissionMode.DEFAULT);
        assertEquals(PeerMessageRouter.Status.HELD, receipt.status(), "前置：消息应被 hold");
        return receipt.messageId();
    }

    private List<String> heldIds() {
        return router.listHeld(TARGET, principal(TARGET, true)).stream()
            .map(PeerMessageRouter.HeldMessage::messageId).toList();
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId, boolean trusted) {
        return new PeerMessageRouter.ApprovalPrincipal() {
            @Override
            public String sessionId() {
                return sessionId;
            }

            @Override
            public String requestId() {
                return "req-1";
            }

            @Override
            public boolean trustedUi() {
                return trusted;
            }
        };
    }

    @Test
    @DisplayName("目标已退出 → NO_LIVE_SESSION；行直接终态（expired），不可再次批准")
    void targetExitedSettlesTerminally() {
        String messageId = holdOne();

        assertTrue(registry.unregister(TARGET, targetEpoch), "前置：目标退出");

        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal(TARGET, true));
        assertEquals(PeerMessageRouter.Status.NO_LIVE_SESSION, receipt.status());
        assertTrue(heldIds().isEmpty(), "批准失败必须结算：行离开待收件列表");
        assertEquals(PeerMessageRouter.HeldMessage.STATUS_EXPIRED,
            store.statusOf(messageId), "失败 → 终态 expired（⛔ 不保留 approved/ pending）");
        assertTrue(delivered.isEmpty(), "校验失败不得投递");

        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld(messageId, principal(TARGET, true)).status(),
            "已结算的行不可再次批准");
    }

    @Test
    @DisplayName("策略已改 refuse → REFUSED；行直接终态，不可再次批准")
    void policyRefuseSettlesTerminally() {
        String messageId = holdOne();
        setting = "refuse";

        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal(TARGET, true));
        assertEquals(PeerMessageRouter.Status.REFUSED, receipt.status());
        assertTrue(heldIds().isEmpty(), "策略拒绝同样立即结算（不留可重试行）");
        assertEquals(PeerMessageRouter.HeldMessage.STATUS_EXPIRED, store.statusOf(messageId));
        assertTrue(delivered.isEmpty());
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld(messageId, principal(TARGET, true)).status());
    }

    @Test
    @DisplayName("错归属批准 → APPROVAL_REJECTED，pending 保留（不替别人消费）")
    void wrongSessionKeepsHeld() {
        String messageId = holdOne();

        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal(SOURCE, true));
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED, receipt.status());
        assertEquals(List.of(messageId), heldIds(), "错归属不得取出/结算他人行");
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("正常批准 → 只投递一次、行 accepted；第二次批准 APPROVAL_REJECTED")
    void approveOnceThenRejects() {
        String messageId = holdOne();
        setting = "accept";

        PeerMessageRouter.Receipt first = router.approveHeld(messageId, principal(TARGET, true));
        assertEquals(PeerMessageRouter.Status.ACCEPTED, first.status());
        assertEquals(1, delivered.size(), "应投递一次");
        assertTrue(heldIds().isEmpty(), "批准后 held 项被消费");
        assertEquals(PeerMessageRouter.HeldMessage.STATUS_ACCEPTED, store.statusOf(messageId),
            "批准即结算 → accepted（不等消费落地）");

        PeerMessageRouter.Receipt second = router.approveHeld(messageId, principal(TARGET, true));
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED, second.status());
        assertEquals(1, delivered.size(), "同一 messageId 不得投递两次");
    }

    @Test
    @DisplayName("投递失败 → 直接终态 + 不可重试（严格 CC：失败即结算）")
    void deliveryFailureIsTerminal() {
        String messageId = holdOne();
        setting = "accept";
        deliveryStatus = PeerMessageRouter.Status.NOT_DELIVERED;

        PeerMessageRouter.Receipt failed = router.approveHeld(messageId, principal(TARGET, true));
        assertEquals(PeerMessageRouter.Status.NOT_DELIVERED, failed.status());
        assertEquals(1, delivered.size(), "只尝试一次");
        assertTrue(heldIds().isEmpty(), "失败即结算：不得回写待收件");
        assertEquals(PeerMessageRouter.HeldMessage.STATUS_EXPIRED, store.statusOf(messageId),
            "失败 → 终态 expired（dropped 语义由失败通知 reason 表达）");

        deliveryStatus = PeerMessageRouter.Status.ACCEPTED;
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld(messageId, principal(TARGET, true)).status(),
            "失败后不可再次批准");
        assertEquals(1, delivered.size(), "不得重试投递");
    }

    @Test
    @DisplayName("不在该会话的 held 列表里的 id → APPROVAL_REJECTED（不越权消费）")
    void unknownIdRejected() {
        holdOne();
        PeerMessageRouter.Receipt receipt = router.approveHeld("peer-missing", principal(TARGET, true));
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED, receipt.status());
        assertNotNull(receipt.detail());
        assertEquals(1, heldIds().size());
    }
}

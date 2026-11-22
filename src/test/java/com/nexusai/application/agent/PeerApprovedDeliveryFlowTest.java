package com.nexusai.application.agent;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.session.PeerInboxController;
import com.nexusai.application.agent.messaging.DbHeldPeerStore;
import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerFailureNotifier;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerRuntimeAdapter;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「批准即结算」全链回归（严格 CC 2.1.328 语义 · 2026-11-21 用户裁定）。
 *
 * <h2>WHY</h2>
 * <p>CC 实测没有 approved 中间态：批准 = 从 hold 缓冲 splice 出来 + 同步投递；成功回执
 * <b>delivered</b>，失败<b>立即结算</b>并回执发送方。不能撤销、不能二次批准。旧实现把
 * 「已批准未消费」持久化为 approved，允许失败后再次批准 —— 与 CC 冲突。
 *
 * <h2>不变量（本测试逐条钉死）</h2>
 * <ol>
 *   <li><b>批准成功 ⇒ 行立即 accepted</b>（不等消费落地）+ 入队信封带一次性批准标记；消费期
 *       复核不再按 hold 重判，消息真的进入会话历史；</li>
 *   <li><b>批准失败 ⇒ 行立即终态 expired</b>（refused/dropped/receiver-closed 由 {@link
 *       PeerFailureNotifier} 的 reason 表达）+ 发送方收到失败通知 + 不可再次批准；</li>
 *   <li><b>已 accepted 再批 ⇒ 900</b>，不重复投递；</li>
 *   <li>待收件列表<b>永不返回 approved</b>（只 pending / rejected）。</li>
 * </ol>
 *
 * <h2>RED teeth（变异自检）</h2>
 * <ul>
 *   <li>变异①：批准失败仍回写 pending/approved ⇒ {@link #targetOfflineSettlesAndNotifies()}
 *       与 {@link #deliveryFailureSettlesAndNotifies()} 红；</li>
 *   <li>变异②：迁移不把历史 approved 转 pending ⇒ {@code PeerHeldApprovedMigrationTest} 红。</li>
 * </ul>
 */
@DisplayName("跨会话 · 批准即结算（成功 accepted / 失败终态 + 通知）")
class PeerApprovedDeliveryFlowTest {

    private static final String TARGET = "sess-appr0001";
    private static final String SOURCE = "sess-appr0002";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper mapper;

    static {
        System.out.println("[adf] PeerHeldMessageMapper 来源 = "
            + PeerHeldMessageMapper.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[adf] PeerMessageRouter 来源 = "
            + PeerMessageRouter.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[adf] PeerRuntimeAdapter 来源 = "
            + PeerRuntimeAdapter.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private NotificationQueue queue;
    private PeerMessageRouter router;
    private PeerRuntimeAdapter adapter;
    private DbHeldPeerStore store;
    private Recorder recorder;
    private AtomicReference<String> setting;
    private long targetEpoch;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("peer-approved-");
        Path db = dir.resolve("approved.db");
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?journal_mode=WAL&busy_timeout=5000");
        Flyway.configure()
            .dataSource(ds)
            .locations(migrationsLocation())
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexBootstrap.getInstance().addMapper(PeerHeldMessageMapper.class);
        MybatisFlexBootstrap.getInstance().setDataSource(ds).start();
        mapper = MybatisFlexBootstrap.getInstance().getMapper(PeerHeldMessageMapper.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        deleteAllRows();
        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source", "/tmp", PermissionMode.DEFAULT, 1L));
        targetEpoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();

        setting = new AtomicReference<>("hold");
        policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> setting.get());
        queue = new NotificationQueue();
        recorder = new Recorder();

        wireStack(new DbHeldPeerStore(mapper));
    }

    /** 真实装配（非 Spring）：router → adapter（DeliveryPort → NotificationQueue）+ 失败通知记录器。 */
    private void wireStack(DbHeldPeerStore heldStore) {
        this.store = heldStore;
        heldStore.setFailureNotifier(recorder);
        this.router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(heldStore);

        this.adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "heldStore", heldStore);
        router.setDeliveryPort(adapter);
    }

    // ───────────────────────── ① 批准成功：accepted + 真的进历史 ─────────────────────────

    @Test
    @DisplayName("hold 下 accept → 行立即 accepted + 入队带批准标记 + 消费前复核 ACCEPT + 进入会话历史")
    void holdAcceptSettlesAcceptedAndLandsInHistory() {
        String messageId = holdOne("sc4-hold-body");
        assertThat(setting.get()).as("前置：设置仍是 hold（审批后也不得先改设置）").isEqualTo("hold");

        PeerMessageRouter.Receipt approved = router.approveHeld(messageId, principal(TARGET));
        assertThat(approved.status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(statusOf(messageId)).as("批准即结算 → accepted（不等消费落地）")
            .isEqualTo("accepted");

        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow(
            () -> new AssertionError("approve 成功后必须入队一条 peer 项"));
        assertThat(item.peerEnvelope()).as("服务端信封").isNotNull();
        assertThat(item.peerEnvelope().preApproved())
            .as("只有 approveHeld 成功路径能设置的一次性批准标记").isTrue();
        assertThat(item.peerEnvelope().preApprovedTargetEpoch()).isEqualTo(targetEpoch);
        assertThat(item.peerEnvelope().messageId()).isEqualTo(messageId);

        // 设置仍是 hold —— 旧实现这里判 HOLD（丢件根因）；现在带标记必须放行。
        PeerInboundPolicy.Outcome outcome =
            adapter.revalidateForConsumption(item.peerEnvelope(), TARGET);
        assertThat(outcome.decision()).as("已批准消息不按 hold 重判")
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(outcome.reason()).isEqualTo(PeerInboundPolicy.Reason.PRE_APPROVED);

        // 真实消费路径（运行中 drain）：写 state → 触发唯一落库通道。
        AgentState state = new AgentState("sys", TARGET,
            com.nexusai.application.agent.subagent.AgentContext.createAgentId());
        AtomicInteger persistCalls = new AtomicInteger();
        state.setAppendListener(m -> persistCalls.incrementAndGet());
        boolean consumed = LlmAgentLoop.injectPeerQueuedItem(
            state, item, new ArrayList<>(), adapter);

        assertThat(consumed).as("消费前复核 ACCEPT ⇒ 消费").isTrue();
        assertThat(state.rawMessages()).as("真的进入会话历史（state）").hasSize(1);
        assertThat(state.rawMessages().get(0).id()).isEqualTo(messageId);
        assertThat(state.rawMessages().get(0).content()).isEqualTo("sc4-hold-body");
        assertThat(persistCalls).as("append 触发落库通道一次").hasValue(1);

        assertThat(statusOf(messageId)).as("消费落地后仍是 accepted（幂等，不回退）")
            .isEqualTo("accepted");
        assertThat(router.listHeldViews(TARGET, principal(TARGET)))
            .as("已结算的项不再出现在待收件列表").isEmpty();
    }

    // ───────────────────────── ② 批准失败：终态 + 通知 + 不可再批 ─────────────────────────

    @Test
    @DisplayName("批准时目标离线 → NO_LIVE_SESSION + 行终态 + 发送方收到失败通知 + 不可再批")
    void targetOfflineSettlesAndNotifies() {
        String messageId = holdOne("target-offline-body");
        assertThat(registry.unregister(TARGET, targetEpoch)).as("前置：目标退出").isTrue();

        PeerMessageRouter.Receipt receipt = router.approveHeld(messageId, principal(TARGET));
        assertThat(receipt.status()).isEqualTo(PeerMessageRouter.Status.NO_LIVE_SESSION);
        assertThat(statusOf(messageId)).as("失败直接终态").isEqualTo("expired");
        assertThat(router.listHeldViews(TARGET, principal(TARGET)))
            .as("终态不出现在待收件列表").isEmpty();
        assertThat(queue.dequeue()).as("失败不得入队").isEmpty();

        assertThat(recorder.notices).as("发送方必须收到一条失败通知").hasSize(1);
        assertThat(recorder.notices.get(0).messageId()).isEqualTo(messageId);
        assertThat(recorder.notices.get(0).sourceSessionId()).isEqualTo(SOURCE);
        assertThat(recorder.notices.get(0).reason())
            .as("目标会话关闭 → receiver-closed（CC 回执语义）")
            .isEqualTo(PeerFailureNotifier.REASON_RECEIVER_CLOSED);

        assertThat(router.approveHeld(messageId, principal(TARGET)).status())
            .as("失败后不可再次批准")
            .isEqualTo(PeerMessageRouter.Status.APPROVAL_REJECTED);
    }

    @Test
    @DisplayName("投递未接线（失败）→ 行终态 + dropped 通知 + 不可重试")
    void deliveryFailureSettlesAndNotifies() {
        String messageId = holdOne("delivery-failure-body");
        ReflectionTestUtils.setField(adapter, "notificationQueue", null);

        PeerMessageRouter.Receipt failed = router.approveHeld(messageId, principal(TARGET));
        assertThat(failed.status()).isEqualTo(PeerMessageRouter.Status.NOT_WIRED);
        assertThat(statusOf(messageId)).as("投递失败直接终态（⛔ 不留 approved）")
            .isEqualTo("expired");
        assertThat(recorder.notices).hasSize(1);
        assertThat(recorder.notices.get(0).reason())
            .as("限速/队满/未接线统一 dropped 语义").isEqualTo(PeerFailureNotifier.REASON_DROPPED);

        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        assertThat(router.approveHeld(messageId, principal(TARGET)).status())
            .as("失败后不可再次批准").isEqualTo(PeerMessageRouter.Status.APPROVAL_REJECTED);
        assertThat(queue.size()).as("不得补投").isZero();
        assertThat(recorder.notices).as("同一 messageId 只通知一次").hasSize(1);
    }

    // ───────────────────────── ③ 已 accepted 再批 = 900 ─────────────────────────

    @Test
    @DisplayName("成功 accept 后第二次 accept → APPROVAL_REJECTED（前端码 900），且只入队一次")
    void secondAcceptReturns900() {
        String messageId = holdOne("double-click-body");
        assertThat(router.approveHeld(messageId, principal(TARGET)).status())
            .isEqualTo(PeerMessageRouter.Status.ACCEPTED);

        PeerInboxController controller = new PeerInboxController();
        controller.setRouter(router);
        com.zjky.pro.framework.common.pojo.CommonResult<PeerInboxController.HeldActionResult> second =
            controller.approve(TARGET, messageId);

        assertThat(second.getCode()).as("APPROVAL_REJECTED → 900").isEqualTo(900);
        assertThat(second.isSuccess()).isFalse();
        assertThat(second.getData().code()).isEqualTo("APPROVAL_REJECTED");
        assertThat(queue.size()).as("不得重复入队").isEqualTo(1);
        assertThat(statusOf(messageId)).isEqualTo("accepted");
    }

    // ───────────────────────── ④ 列表永不返回 approved ─────────────────────────

    @Test
    @DisplayName("待收件列表只有 pending/rejected：批准成功/失败的行都不在列表里")
    void listNeverExposesApprovedStatus() {
        String first = holdOne("list-a");
        String second = holdOne("list-b");

        List<PeerMessageRouter.HeldView> pendingViews = router.listHeldViews(TARGET, principal(TARGET));
        assertThat(pendingViews).hasSize(2);
        assertThat(pendingViews).extracting(PeerMessageRouter.HeldView::status)
            .containsOnly("pending");

        assertThat(router.approveHeld(first, principal(TARGET)).status())
            .isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        ReflectionTestUtils.setField(adapter, "notificationQueue", null);
        assertThat(router.approveHeld(second, principal(TARGET)).status())
            .isEqualTo(PeerMessageRouter.Status.NOT_WIRED);

        assertThat(router.listHeldViews(TARGET, principal(TARGET)))
            .as("accepted/expired 都不在列表，更不可能是 approved").isEmpty();
        assertThat(router.listHeld(TARGET, principal(TARGET))).isEmpty();
        assertThat(router.pendingCounts()).as("两者的 pending 计数都归零").isEmpty();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 记录失败通知（不落库：本测试只验「结算时调用了通知出口 + reason 正确」）。 */
    private static final class Recorder extends PeerFailureNotifier {
        private final List<FailureNotice> notices = new ArrayList<>();

        @Override
        public boolean notifyFailure(FailureNotice notice) {
            notices.add(notice);
            return true;
        }
    }

    private String holdOne(String body) {
        PeerMessageRouter.Receipt receipt = router.send(new PeerMessageRouter.SendRequest(
            TARGET, targetEpoch, "Target", SOURCE, "Source", "Source [appr01]",
            body, "summary", false, false, List.of()), PermissionMode.DEFAULT);
        assertThat(receipt.status()).as("前置：hold 设置 → 入 hold").isEqualTo(PeerMessageRouter.Status.HELD);
        return receipt.messageId();
    }

    private static String migrationsLocation() {
        String override = System.getProperty("adf.migrations");
        return override == null || override.isBlank()
            ? "filesystem:src/main/resources/db/migration" : override;
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return new Principal(sessionId);
    }

    private static void deleteAllRows() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM peer_held_message")) {
            ps.executeUpdate();
        }
    }

    private static String statusOf(String messageId) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM peer_held_message WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new AssertionError("statusOf failed", e);
        }
    }

    private record Principal(String sessionId) implements PeerMessageRouter.ApprovalPrincipal {
        @Override
        public String requestId() {
            return "req-approved-flow";
        }

        @Override
        public boolean trustedUi() {
            return true;
        }
    }
}

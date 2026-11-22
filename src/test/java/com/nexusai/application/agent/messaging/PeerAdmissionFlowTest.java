package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.session.PeerInboxController;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.eventbus.ws.PeerHeldNotifier;
import com.nexusai.eventbus.ws.PeerInboxHoldEvent;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [peer-admission] 「入站准入方式」记录与透传（非 Spring · 真实临时 SQLite + Flyway V1..V325）。
 *
 * <p>判据链：策略 ACCEPT（auto / selfSent / 显式 accept）⇒ 投递信封 {@code admission=auto}；
 * hold 命中后用户批准 ⇒ {@code admission=approved}；落库列 / {@code structuredOutput.peer_source}
 * 同源；批准即结算（严格 CC：行直接 accepted），重启后审计列仍是 {@code approved}（⛔ 不回落 auto）；
 * 字段缺失处处为 null 且不抛。
 *
 * <p><b>RED tooth（变异）</b>：把 {@code PeerMessageRouter.approveHeld} 的
 * {@code withAdmission(ADMISSION_APPROVED, REASON_USER_APPROVED)} 改成
 * {@code withAdmission(ADMISSION_AUTO, ...)}（或删掉）⇒ {@link #holdThenUserApprovalMarksApproved()}
 * 与 {@link #approvedAdmissionSurvivesRestartAsAudit()} 必红。
 */
@DisplayName("[peer-admission] 入站准入方式 auto / approved 全链贯通")
class PeerAdmissionFlowTest {

    private static final String TARGET = "sess-adm-tgt1";
    private static final String SOURCE = "sess-adm-src1";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper mapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private RecordingNotifier notifier;
    private PeerInboxController controller;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("peer-adm-");
        Path db = dir.resolve("admission.db");
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?journal_mode=WAL&busy_timeout=5000");
        // 只读 src/main/resources（不覆盖共享 target/classes）；含本次 V325。
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:src/main/resources/db/migration")
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

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> "auto");

        store = new DbHeldPeerStore(mapper);
        notifier = new RecordingNotifier();
        store.setNotifier(notifier);

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        controller = new PeerInboxController();
        controller.setRouter(router);
    }

    // ───────────────── ① 自动通过：模式类一致 / 显式 accept ─────────────────

    @Test
    @DisplayName("auto 设置 + 双方同模式类：admission=auto、reason=mode-match，且投影同源")
    void autoDeliveryMarksModeMatch() {
        AtomicReference<PeerEnvelope> delivered = new AtomicReference<>();
        router.setDeliveryPort(captureInto(delivered));

        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(sendRequest(SOURCE, false), PermissionMode.DEFAULT, "peer-adm-auto").status());
        assertNotNull(delivered.get());
        assertEquals(PeerEnvelope.ADMISSION_AUTO, delivered.get().admission());
        assertEquals(PeerEnvelope.REASON_MODE_MATCH, delivered.get().admissionReason());
        assertTrue(delivered.get().autoAdmitted());
        assertFalse(delivered.get().approvedAdmission());

        // peer_source 投影（前端卡片数据源）
        Map<String, Object> peerSource = peerSourceOf(
            PeerRuntimeAdapter.structuredOutput(PeerRuntimeAdapter.originOf(delivered.get()), "sum"));
        assertEquals("auto", peerSource.get("admission"));
        assertEquals("mode-match", peerSource.get("admissionReason"));
        // 发送方声明了模式 ⇒ fromMode 是 CC 字面量
        assertEquals("default", peerSource.get("fromMode"));
    }

    @Test
    @DisplayName("显式 accept 设置：admission=auto、reason=explicit-accept")
    void explicitAcceptMarksAutoExplicitAccept() {
        policy.setSettingsSource(sessionId -> "accept");
        AtomicReference<PeerEnvelope> delivered = new AtomicReference<>();
        router.setDeliveryPort(captureInto(delivered));

        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(sendRequest(SOURCE, false), PermissionMode.DEFAULT, "peer-adm-accept").status());
        assertEquals(PeerEnvelope.ADMISSION_AUTO, delivered.get().admission());
        assertEquals(PeerEnvelope.REASON_EXPLICIT_ACCEPT, delivered.get().admissionReason());
    }

    @Test
    @DisplayName("发送方未声明模式：admission=auto/mode-match，fromMode 不得被补成 default")
    void undeclaredSenderModeIsNotDefaulted() {
        AtomicReference<PeerEnvelope> delivered = new AtomicReference<>();
        router.setDeliveryPort(captureInto(delivered));

        // sourceMode=null + registry 上也没有模式 ⇒ 未声明；接收方 PROMPTING ⇒ 仍 ACCEPT
        PeerSessionRegistry bare = new PeerSessionRegistry();
        bare.register(new PeerSessionRegistry.Registration(TARGET, "Target", "/tmp",
            PermissionMode.DEFAULT, 1L));
        bare.register(new PeerSessionRegistry.Registration(SOURCE, "Source", "/tmp", null, 1L));
        router.setPeerSessionRegistry(bare);

        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(sendRequest(SOURCE, false), null, "peer-adm-nodecl").status());
        assertEquals(PeerEnvelope.ADMISSION_AUTO, delivered.get().admission());
        assertEquals(PeerEnvelope.REASON_MODE_MATCH, delivered.get().admissionReason(),
            "未声明模式但接收方仍会提示 ⇒ 模式类一致，理由仍是 mode-match");
        assertNotEquals("default", delivered.get().fromMode(),
            "未声明模式不得回落成 default");
        Map<String, Object> peerSource = peerSourceOf(
            PeerRuntimeAdapter.structuredOutput(PeerRuntimeAdapter.originOf(delivered.get()), null));
        assertNotEquals("default", peerSource.get("fromMode"),
            "peer_source.fromMode 也不得默认成 default（空/缺失 = 未声明）");
    }

    // ───────────────── ② 同会话记录投递（子代理 / teammate → 主会话） ─────────────────

    @Test
    @DisplayName("同会话记录投递（子代理→主会话）：admission=auto、reason=self-sent")
    void sameSessionDeliveryMarksSelfSent() {
        AtomicReference<PeerEnvelope> delivered = new AtomicReference<>();
        router.setDeliveryPort(captureInto(delivered));

        // 主会话 → 自己的地址已在 SendMessageTool 直接拒绝（CC 原文），永不进路由；
        // 这里只锁定「子代理/teammate 发回主会话」的传输语义（两 sessionId 相同）。
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(sendRequest(TARGET, true), PermissionMode.DEFAULT, "peer-adm-self").status());
        assertEquals(PeerEnvelope.ADMISSION_AUTO, delivered.get().admission());
        assertEquals(PeerEnvelope.REASON_SELF_SENT, delivered.get().admissionReason());
    }

    // ───────────────── ③ hold 后手动批准 ─────────────────

    @Test
    @DisplayName("hold 命中时待收件行未准入；用户批准后 admission=approved、reason=user-approved（信封/DB/列表/事件同源）")
    void holdThenUserApprovalMarksApproved() {
        policy.setSettingsSource(sessionId -> "hold");
        AtomicReference<PeerEnvelope> delivered = new AtomicReference<>();
        router.setDeliveryPort(captureInto(delivered));

        assertEquals(PeerMessageRouter.Status.HELD,
            router.send(sendRequest(SOURCE, false), PermissionMode.DEFAULT, "peer-adm-hold").status());
        assertNull(delivered.get(), "hold 不得投递");
        assertNull(columnOf("admission", "peer-adm-hold"), "pending 行 = 未准入（null）");
        assertNull(notifier.events.get(0).getAdmission(), "hold 事件里准入方式 = null（未准入）");
        assertEquals(TARGET, notifier.events.get(0).getTargetSessionId());
        assertNull(controller.list(TARGET).getData().get(0).admission());

        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.approveHeld("peer-adm-hold", principal()).status());
        assertNotNull(delivered.get());
        assertEquals(PeerEnvelope.ADMISSION_APPROVED, delivered.get().admission());
        assertEquals(PeerEnvelope.REASON_USER_APPROVED, delivered.get().admissionReason());
        assertTrue(delivered.get().approvedAdmission());
        assertTrue(delivered.get().preApproved(), "批准信封仍带一次性标记");

        assertEquals("approved", columnOf("admission", "peer-adm-hold"));
        assertEquals("user-approved", columnOf("admission_reason", "peer-adm-hold"));
        // 批准即结算（严格 CC 语义）：行直接 accepted，不进待收件列表（⛔ 不再有 approved 中间态）。
        assertEquals("accepted", columnOf("status", "peer-adm-hold"));
        assertTrue(controller.list(TARGET).getData().isEmpty(),
            "已结算的行不出现在待收件列表");

        Map<String, Object> peerSource = peerSourceOf(
            PeerRuntimeAdapter.structuredOutput(PeerRuntimeAdapter.originOf(delivered.get()), null));
        assertEquals("approved", peerSource.get("admission"));
        assertEquals("user-approved", peerSource.get("admissionReason"));
    }

    // ───────────────── ④ 重启后 accepted 行保留批准审计值 ─────────────────

    @Test
    @DisplayName("重启（新 store/router 实例）后：accepted 行仍保留 admission=approved（审计），不可再次批准")
    void approvedAdmissionSurvivesRestartAsAudit() {
        policy.setSettingsSource(sessionId -> "hold");
        assertEquals(PeerMessageRouter.Status.HELD,
            router.send(sendRequest(SOURCE, false), PermissionMode.DEFAULT, "peer-adm-restart").status());
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.approveHeld("peer-adm-restart", principal()).status());

        // 「重启」：全新的 store / router（内存集合全清），同一 SQLite 文件。
        DbHeldPeerStore restartedStore = new DbHeldPeerStore(mapper);
        restartedStore.setNotifier(notifier);
        PeerMessageRouter restarted = new PeerMessageRouter();
        restarted.setPeerSessionRegistry(registry);
        restarted.setPeerInboundPolicy(policy);
        restarted.setHeldStore(restartedStore);
        AtomicReference<PeerEnvelope> redelivered = new AtomicReference<>();
        restarted.setDeliveryPort(captureInto(redelivered));

        // 行是终态 accepted（审批即结算），⛔ 不可再批准、不出现在列表。
        List<PeerMessageRouter.HeldMessage> held = restartedStore.list(TARGET);
        assertTrue(held.isEmpty(), "accepted 行不进待收件列表");
        assertEquals("accepted", columnOf("status", "peer-adm-restart"));
        assertEquals(PeerEnvelope.ADMISSION_APPROVED, columnOf("admission", "peer-adm-restart"),
            "审计值：这条是用户手动放行的（⛔ 不回落 auto）");
        assertEquals(PeerEnvelope.REASON_USER_APPROVED,
            columnOf("admission_reason", "peer-adm-restart"));

        PeerInboxController restartedController = new PeerInboxController();
        restartedController.setRouter(restarted);
        assertTrue(restartedController.list(TARGET).getData().isEmpty(), "列表接口同样不返回它");

        // 重启后再次批准 → 900（不能二次批准/重投）。
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            restarted.approveHeld("peer-adm-restart", principal()).status());
        assertNull(redelivered.get(), "不得重投");
    }

    // ───────────────── ⑤ 字段缺失：null 且不抛 ─────────────────

    @Test
    @DisplayName("旧行（admission 列为 null）/ 空 origin：各处为 null、不抛异常、不伪造")
    void missingAdmissionStaysNullEverywhere() throws Exception {
        insertLegacyRow("peer-adm-legacy", TARGET, SOURCE);

        List<PeerMessageRouter.HeldView> views = store.listViews(TARGET);
        assertEquals(1, views.size());
        assertNull(views.get(0).admission());
        assertNull(views.get(0).admissionReason());

        PeerEnvelope rebuilt = store.list(TARGET).get(0).envelope();
        assertNull(rebuilt.admission());
        assertNull(rebuilt.admissionReason());
        assertFalse(rebuilt.autoAdmitted(), "缺失不得被当成自动通过");
        assertFalse(rebuilt.approvedAdmission());

        PeerInboxController.HeldView listed = controller.list(TARGET).getData().get(0);
        assertNull(listed.admission());
        assertNull(listed.admissionReason());

        Map<String, Object> peerSource = peerSourceOf(
            PeerRuntimeAdapter.structuredOutput(PeerRuntimeAdapter.originOf(rebuilt), null));
        assertTrue(peerSource.containsKey("admission"), "键必须存在（值可为 null）");
        assertNull(peerSource.get("admission"));
        assertNull(peerSource.get("admissionReason"));

        // 伪造值（不在闭集内）一律判未知 ⇒ null，不抛、不被当成 auto。
        PeerEnvelope forged = rebuilt.withAdmission("definitely-not-a-real-value", "x y!");
        assertNull(forged.admission());
        assertNull(forged.admissionReason());
        assertFalse(forged.autoAdmitted());
    }

    // ───────────────── helpers ─────────────────

    private static PeerMessageRouter.DeliveryPort captureInto(AtomicReference<PeerEnvelope> sink) {
        return (sid, epoch, envelope, body) -> {
            sink.set(envelope);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null);
        };
    }

    private static PeerMessageRouter.SendRequest sendRequest(String sourceSessionId, boolean selfSent) {
        return new PeerMessageRouter.SendRequest(
            TARGET, -1L, "Target", sourceSessionId, "Source", "source [abcdef]",
            "hello", "sum", false, selfSent, List.of());
    }

    private static PeerMessageRouter.ApprovalPrincipal principal() {
        return new Principal(TARGET, null, true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> peerSourceOf(Map<String, Object> structuredOutput) {
        return (Map<String, Object>) structuredOutput.get("peer_source");
    }

    /** 旧行：admission / admission_reason 列为 NULL（V325 之前的行）。 */
    private static void insertLegacyRow(String messageId, String target, String source) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO peer_held_message (message_id, target_session_id, source_session_id, "
                     + "source_title, source_kind, from_name, from_mode, from_address, hop_chain, "
                     + "summary, body, reason, status, source_endpoint_epoch, target_endpoint_epoch, "
                     + "notify_when_idle, created_at, seen_at) "
                     + "VALUES (?, ?, ?, NULL, 'peer', 'Source', 'default', 'source [abcdef]', NULL, "
                     + "NULL, 'legacy body', 'EXPLICIT_HOLD', 'pending', 1, -1, 0, "
                     + "'2026-11-19T00:00:00Z', NULL)")) {
            ps.setString(1, messageId);
            ps.setString(2, target);
            ps.setString(3, source);
            ps.executeUpdate();
        }
    }

    private static String columnOf(String column, String messageId) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM peer_held_message WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new AssertionError("columnOf failed", e);
        }
    }

    private static void deleteAllRows() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM peer_held_message")) {
            ps.executeUpdate();
        }
    }

    private record Principal(String sessionId, String requestId, boolean trustedUi)
        implements PeerMessageRouter.ApprovalPrincipal { }

    private static final class RecordingNotifier implements PeerHeldNotifier {

        private final List<PeerInboxHoldEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public boolean publish(PeerInboxHoldEvent event) {
            events.add(event);
            return true;
        }
    }
}

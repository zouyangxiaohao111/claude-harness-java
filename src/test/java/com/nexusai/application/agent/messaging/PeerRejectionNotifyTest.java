package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.session.PeerInboxController;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「拒绝待收件时告知发送方」+「列表正文规模」· 非 Spring、真实临时 SQLite + Flyway。
 *
 * <p><b>WHY 真 SQLite</b>：拒绝是一次性终态（{@code pending→rejected} 条件 UPDATE），通知必须
 * 只在<b>本次真的改到</b>的那一次发生；内存假实现验不出与 approve 的抢占，也验不出重复拒绝。
 *
 * <p>覆盖：① 拒绝成功 → 发送方收到恰好一条通知（字段齐全）；② 同一 messageId 不重复通知；
 * ③ 发送方已退出 ⇒ 不投递且拒绝仍成功；④ 批量拒绝逐条通知（不合并）且幂等；
 * ⑤ 被 approve 抢走的行不得误报被拒；⑥ 通知出口抛异常不影响拒绝结果；
 * ⑦ 列表返回正文总行数 / 总字数；⑧ 内存 store 同口径（statusOf 实现）。
 */
class PeerRejectionNotifyTest {

    private static final String TARGET = "sess-dn-target1";
    private static final String SOURCE = "sess-dn-source1";
    private static final String OTHER = "sess-dn-other01";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper mapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private RecordingRejectionNotifier notifier;
    private PeerInboxController controller;

    static {
        // 隔离产物证据：确认加载的是本轮编译输出（D:/tmp/dn-classes），不是共享 target/classes。
        System.out.println("[dn] PeerMessageRouter 来源 = "
            + PeerMessageRouter.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[dn] PeerMessageRejectedEvent 来源 = "
            + PeerMessageRejectedEvent.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[dn] DbHeldPeerStore 来源 = "
            + DbHeldPeerStore.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[dn] PeerInboxController 来源 = "
            + PeerInboxController.class.getProtectionDomain().getCodeSource().getLocation());
    }

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("peer-rejection-");
        Path db = dir.resolve("dn.db");
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
        registry.register(new PeerSessionRegistry.Registration(
            OTHER, "Other", "/tmp", PermissionMode.DEFAULT, 1L));

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> "accept");

        store = new DbHeldPeerStore(mapper);
        notifier = new RecordingRejectionNotifier();
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setRejectionNotifier(notifier);
        router.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        controller = new PeerInboxController();
        controller.setRouter(router);
    }

    // ───────────────────────── ① 单条拒绝通知 ─────────────────────────

    @Test
    @DisplayName("拒绝一条 → 发送方收到恰好一条通知（messageId/接收方/原因/时间齐全）")
    void rejectNotifiesSenderOnce() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-dn-1", TARGET, epoch, "body-1"));

        assertTrue(router.rejectHeld("peer-dn-1", principal(TARGET)));

        assertEquals("rejected", statusOf("peer-dn-1"), "拒绝本身必须生效");
        assertEquals(1, notifier.notices.size(), "必须且只发一条拒绝通知");
        PeerMessageRouter.RejectionNotice notice = notifier.notices.get(0);
        assertEquals("peer-dn-1", notice.messageId());
        assertEquals(TARGET, notice.targetSessionId(), "通知要写明接收方会话");
        assertEquals(SOURCE, notice.sourceSessionId(), "通知投给发送方会话");
        assertEquals(PeerMessageRouter.REJECT_REASON_USER_DECLINED, notice.reason());
        assertNotNull(notice.rejectedAt(), "通知要带时间");
    }

    @Test
    @DisplayName("同一 messageId 第二次拒绝 → false 且不重复通知（一次性）")
    void secondRejectDoesNotNotifyAgain() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-dn-1", TARGET, epoch, "body-1"));

        assertTrue(router.rejectHeld("peer-dn-1", principal(TARGET)));
        assertFalse(router.rejectHeld("peer-dn-1", principal(TARGET)), "终态不可再拒");

        assertEquals(1, notifier.notices.size(), "同一 messageId 只通知一次");
    }

    // ───────────────────────── ③ 发送方已退出 ─────────────────────────

    @Test
    @DisplayName("发送方已退出 → 不投递，但拒绝仍成功且状态落库")
    void exitedSenderGetsNoNoticeButRejectSucceeds() {
        long epoch = epoch(TARGET);
        long sourceEpoch = epoch(SOURCE);
        store.hold(held("peer-dn-1", TARGET, epoch, "body-1"));

        assertTrue(registry.unregister(SOURCE, sourceEpoch), "前置：发送方退出");

        assertTrue(router.rejectHeld("peer-dn-1", principal(TARGET)), "发送方不可达不得影响拒绝成功");
        assertEquals("rejected", statusOf("peer-dn-1"));
        assertTrue(notifier.notices.isEmpty(), "发送方不可达 ⇒ 只记日志，不投递");
    }

    // ───────────────────────── ④ 批量拒绝 ─────────────────────────

    @Test
    @DisplayName("批量拒绝逐条通知（不合并）；重复调用不再通知")
    void rejectAllNotifiesEachRejectedMessage() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-dn-1", TARGET, epoch, "b1"));
        store.hold(held("peer-dn-2", TARGET, epoch, "b2"));
        store.hold(held("peer-dn-3", TARGET, epoch, "b3"));

        assertEquals(3, router.rejectAll(TARGET, principal(TARGET)));
        assertEquals(3, notifier.notices.size(), "每条被拒消息各一条通知");
        List<String> notifiedIds = notifier.notices.stream()
            .map(PeerMessageRouter.RejectionNotice::messageId).sorted().toList();
        assertEquals(List.of("peer-dn-1", "peer-dn-2", "peer-dn-3"), notifiedIds);

        assertEquals(0, router.rejectAll(TARGET, principal(TARGET)), "幂等：无 pending → 0");
        assertEquals(3, notifier.notices.size(), "重复批拒不得重复通知");
    }

    @Test
    @DisplayName("并发批准已结算的行（accepted）不得被误报为「已拒绝」")
    void acceptedRowIsNotReportedAsRejected() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-dn-approved", TARGET, epoch, "b1"));
        store.hold(held("peer-dn-pending", TARGET, epoch, "b2"));

        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.approveHeld("peer-dn-approved", principal(TARGET)).status());
        assertEquals("accepted", statusOf("peer-dn-approved"));

        assertEquals(1, router.rejectAll(TARGET, principal(TARGET)), "只拒绝仍为 pending 的那条");
        assertEquals(1, notifier.notices.size());
        assertEquals("peer-dn-pending", notifier.notices.get(0).messageId(),
            "已结算的行不得出现在拒绝通知里");
    }

    // ───────────────────────── ⑥ 通知失败不影响拒绝 ─────────────────────────

    @Test
    @DisplayName("通知出口抛异常 → 拒绝仍成功（不反向影响结果）")
    void notifierFailureDoesNotBlockReject() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-dn-1", TARGET, epoch, "body-1"));
        notifier.fail = true;

        assertTrue(router.rejectHeld("peer-dn-1", principal(TARGET)));
        assertEquals("rejected", statusOf("peer-dn-1"));
        assertTrue(notifier.notices.isEmpty());
    }

    // ───────────────────────── ⑦ 列表正文规模 ─────────────────────────

    @Test
    @DisplayName("列表返回正文总行数 / 总字数（含 CRLF 只算一次换行）")
    void listReturnsBodyLineAndCharCounts() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-dn-lines", TARGET, epoch, "aaa\nbb\ncccc"));
        store.hold(held("peer-dn-crlf", TARGET, epoch, "a\r\nb"));

        PeerMessageRouter.HeldView multiline = viewOf(TARGET, "peer-dn-lines");
        assertEquals(3, multiline.bodyLines(), "3 行正文");
        assertEquals(11, multiline.bodyChars(), "字数含换行符（码点计数）");

        PeerMessageRouter.HeldView crlf = viewOf(TARGET, "peer-dn-crlf");
        assertEquals(2, crlf.bodyLines(), "CRLF 只算一次换行");
        assertEquals(4, crlf.bodyChars());

        PeerInboxController.HeldView dto = controller.list(TARGET).getData().stream()
            .filter(v -> "peer-dn-lines".equals(v.messageId())).findFirst().orElseThrow();
        assertEquals(3, dto.bodyLines());
        assertEquals(11, dto.bodyChars());
        assertEquals("aaa\nbb\ncccc", dto.body(), "全文仍随列表返回（前端可自行截断显示）");
    }

    // ───────────────────────── ⑧ 内存 store 同口径 ─────────────────────────

    @Test
    @DisplayName("内存 store：批量拒绝同样逐条通知（statusOf 实现与 DB 同判据）")
    void inMemoryStoreAlsoNotifiesEachRejectedMessage() {
        PeerMessageRouter inMemoryRouter = new PeerMessageRouter();
        inMemoryRouter.setPeerSessionRegistry(registry);
        inMemoryRouter.setPeerInboundPolicy(policy);
        inMemoryRouter.setHeldStore(new PeerMessageRouter.InMemoryHeldPeerStore());
        RecordingRejectionNotifier capture = new RecordingRejectionNotifier();
        inMemoryRouter.setRejectionNotifier(capture);
        inMemoryRouter.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        long epoch = epoch(TARGET);
        PeerMessageRouter.InMemoryHeldPeerStore memStore =
            new PeerMessageRouter.InMemoryHeldPeerStore();
        inMemoryRouter.setHeldStore(memStore);
        memStore.hold(held("peer-dn-mem-1", TARGET, epoch, "m1"));
        memStore.hold(held("peer-dn-mem-2", TARGET, epoch, "m2"));

        assertEquals(2, inMemoryRouter.rejectAll(TARGET, principal(TARGET)));
        assertEquals(2, capture.notices.size(), "内存 store 也必须逐条通知");
        assertEquals(0, inMemoryRouter.rejectAll(TARGET, principal(TARGET)));
        assertEquals(2, capture.notices.size(), "幂等");
    }

    // ───────────────────────── ⑨ B 适配器（STOMP 出口） ─────────────────────────

    @Test
    @DisplayName("B 适配器：拒绝通知推到发送方会话 stream topic，载荷含四条必需信息")
    void adapterPublishesRejectionToSenderStreamTopic() {
        SimpMessagingTemplate template = Mockito.mock(SimpMessagingTemplate.class);
        PeerRuntimeAdapter adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        adapter.setMessagingTemplate(template);

        PeerMessageRouter.RejectionNotice notice = new PeerMessageRouter.RejectionNotice(
            "peer-dn-x", TARGET, "Target", SOURCE, 1L,
            PeerMessageRouter.REJECT_REASON_USER_DECLINED, Instant.now());

        assertTrue(adapter.notifyRejected(notice), "发送方仍 live ⇒ 已交给通道");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(template).convertAndSend(
            Mockito.eq("/topic/sessions/" + SOURCE + "/stream"), payload.capture());
        PeerMessageRejectedEvent event = (PeerMessageRejectedEvent) payload.getValue();
        assertEquals(PeerMessageRejectedEvent.TYPE, event.getType());
        assertEquals(SOURCE, event.getSessionId(), "topic 属于发送方会话");
        assertEquals(SOURCE, event.getSourceSessionId());
        assertEquals("peer-dn-x", event.getMessageId());
        assertEquals(TARGET, event.getTargetSessionId(), "接收方会话标识");
        assertEquals(PeerMessageRouter.REJECT_REASON_USER_DECLINED, event.getReason());
        assertNotNull(event.getRejectedAt(), "时间");
        assertNotNull(event.getText());
    }

    @Test
    @DisplayName("B 适配器：发送方已退出 → 不推送（不唤醒、不伪造送达）")
    void adapterSkipsExitedSender() {
        SimpMessagingTemplate template = Mockito.mock(SimpMessagingTemplate.class);
        PeerRuntimeAdapter adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        adapter.setMessagingTemplate(template);
        assertTrue(registry.unregister(SOURCE, epoch(SOURCE)), "前置：发送方退出");

        PeerMessageRouter.RejectionNotice notice = new PeerMessageRouter.RejectionNotice(
            "peer-dn-x", TARGET, "Target", SOURCE, 1L,
            PeerMessageRouter.REJECT_REASON_USER_DECLINED, Instant.now());

        assertFalse(adapter.notifyRejected(notice));
        Mockito.verifyNoInteractions(template);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String migrationsLocation() {
        String override = System.getProperty("dn.migrations");
        return override == null || override.isBlank()
            ? "filesystem:src/main/resources/db/migration" : override;
    }

    private long epoch(String sessionId) {
        return registry.resolve(sessionId).orElseThrow().endpointEpoch();
    }

    private static PeerMessageRouter.HeldMessage held(String messageId, String targetSessionId,
                                                      long targetEpoch, String body) {
        PeerEnvelope envelope = new PeerEnvelope(
            messageId,
            "source [aaaaaa]",
            SOURCE,
            "Source",
            "dontAsk",
            List.of(PeerEnvelope.hopToken(SOURCE)),
            1L,
            "summary-" + messageId,
            false);
        return new PeerMessageRouter.HeldMessage(
            messageId, targetSessionId, targetEpoch, envelope, body,
            PeerInboundPolicy.Reason.EXPLICIT_HOLD, Instant.parse("2026-11-20T00:00:00Z"));
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return new Principal(sessionId, null, true);
    }

    private PeerMessageRouter.HeldView viewOf(String sessionId, String messageId) {
        return store.listViews(sessionId).stream()
            .filter(v -> messageId.equals(v.messageId()))
            .findFirst().orElseThrow();
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

    private record Principal(String sessionId, String requestId, boolean trustedUi)
        implements PeerMessageRouter.ApprovalPrincipal { }

    private static final class RecordingRejectionNotifier
            implements PeerMessageRouter.RejectionNotifier {

        private final List<PeerMessageRouter.RejectionNotice> notices = new CopyOnWriteArrayList<>();
        private volatile boolean fail;

        @Override
        public boolean notifyRejected(PeerMessageRouter.RejectionNotice notice) {
            if (fail) {
                throw new IllegalStateException("stomp down");
            }
            notices.add(notice);
            return true;
        }
    }
}

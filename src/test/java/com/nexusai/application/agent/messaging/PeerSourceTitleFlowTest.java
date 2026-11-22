package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.session.PeerInboxController;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.session.SessionService;
import com.nexusai.eventbus.ws.PeerHeldNotifier;
import com.nexusai.eventbus.ws.PeerInboxHoldEvent;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * [peer-source-title] 跨会话消息的《来源会话标题》全链贯通（非 Spring · 真实临时 SQLite + Flyway）。
 *
 * <p>链路判据：发送（会话记录解析）→ 信封 → 队列 / 待收件表 source_title / 列表接口 /
 * STOMP 事件 / structuredOutput.peer_source.sourceTitle / 空闲通知 / 批准后重投。
 *
 * <p><b>RED tooth</b>：把 {@code PeerMessageRouter.send} 里的标题透传删掉（传 null）⇒
 * {@link #titleFlowsThroughSendHoldListAndEvent()} 红；恢复后绿。
 */
@DisplayName("[peer-source-title] 来源会话标题全链贯通")
class PeerSourceTitleFlowTest {

    private static final String TARGET = "sess-tgt-0001";
    private static final String SOURCE = "sess-src-0001";
    private static final String SOURCE2 = "sess-src-0002";
    private static final String TITLE = "支付排查会话";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper mapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private RecordingNotifier notifier;
    private PeerInboxController controller;
    private SessionService sessionService;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("peer-title-");
        Path db = dir.resolve("title.db");
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?journal_mode=WAL&busy_timeout=5000");
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
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE2, "Source2", "/tmp", PermissionMode.DEFAULT, 1L));

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> "hold");

        sessionService = Mockito.mock(SessionService.class);
        store = new DbHeldPeerStore(mapper);
        notifier = new RecordingNotifier();
        store.setNotifier(notifier);
        store.setSessionService(sessionService);

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setSessionService(sessionService);
        router.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        controller = new PeerInboxController();
        controller.setRouter(router);
    }

    // ───────────────── ① 标题：队列/落库/列表/事件/投影 ─────────────────

    @Test
    @DisplayName("有标题的会话：队列信封 / 待收件表 / 列表 / 事件读到的都是同一标题")
    void titleFlowsThroughSendHoldListAndEvent() {
        stubTitle(SOURCE, TITLE);

        // 队列：直投路径捕获投递端口拿到的信封（这就是入队的那一份）。
        AtomicReference<PeerEnvelope> queued = new AtomicReference<>();
        router.setDeliveryPort((sid, epoch, envelope, body) -> {
            queued.set(envelope);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null);
        });
        policy.setSettingsSource(sessionId -> "accept");
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(sendRequest(SOURCE), PermissionMode.DEFAULT, "peer-title-queued").status());
        assertNotNull(queued.get());
        assertEquals(TITLE, queued.get().sourceTitle(), "队列信封必须携带标题");

        // hold 路径：同一标题进待收件表 / 列表 / 事件。
        policy.setSettingsSource(sessionId -> "hold");
        PeerMessageRouter.Receipt receipt = router.send(sendRequest(SOURCE), PermissionMode.DEFAULT,
            "peer-title-1");
        assertEquals(PeerMessageRouter.Status.HELD, receipt.status());

        // 队列/内存态：hold 的 HeldMessage 信封
        PeerEnvelope heldEnvelope = store.list(TARGET).get(0).envelope();
        assertEquals(TITLE, heldEnvelope.sourceTitle(), "信封必须携带标题");

        // 待收件表 source_title
        assertEquals(TITLE, columnOf("source_title", "peer-title-1"), "source_title 必须落库");
        // [peer-name-parity · 2026-11-20 E2E 缺陷 3] from_name = ListAgents 展示名（标题归一化后的
        //   地址名 "source"），不再是工具传入的 "Source"；from 与 from-name 同一来源。
        assertEquals("source", columnOf("from_name", "peer-title-1"),
            "from_name 必须等于 ListAgents 展示名（与信封 from 同源）");
        assertEquals(heldEnvelope.fromName(), columnOf("from_name", "peer-title-1"),
            "落库 from_name 与内存信封一致");

        // 列表接口（DbHeldPeerStore.backfillTitle 也读得到）
        List<PeerInboxController.HeldView> listed = controller.list(TARGET).getData();
        assertEquals(1, listed.size());
        assertEquals(TITLE, listed.get(0).sourceTitle());
        assertEquals(SOURCE, listed.get(0).fromSessionId());

        // STOMP 事件
        assertEquals(1, notifier.events.size());
        assertEquals(TITLE, notifier.events.get(0).getSourceTitle());

        // structuredOutput 投影
        PeerExecutionOrigin origin = PeerRuntimeAdapter.originOf(heldEnvelope);
        assertEquals(TITLE, origin.sourceTitle());
        assertEquals(TITLE, peerSourceOf(PeerRuntimeAdapter.structuredOutput(origin, "sum"))
            .get("sourceTitle"));
    }

    // ───────────────── ② 投递 + 批准重投 ─────────────────

    @Test
    @DisplayName("直投 / 用户批准后重投：投递信封仍带同一标题（withPreApproval 不丢）")
    void deliveryAndApprovedRedeliveryKeepTitle() {
        stubTitle(SOURCE, TITLE);
        AtomicReference<PeerEnvelope> delivered = new AtomicReference<>();
        router.setDeliveryPort((sid, epoch, envelope, body) -> {
            delivered.set(envelope);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null);
        });

        // 直投（策略 accept）
        policy.setSettingsSource(sessionId -> "accept");
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(sendRequest(SOURCE), PermissionMode.DEFAULT, "peer-title-direct").status());
        assertNotNull(delivered.get());
        assertEquals(TITLE, delivered.get().sourceTitle(), "直投信封必须带标题");
        assertEquals(TITLE, PeerRuntimeAdapter.originOf(delivered.get()).sourceTitle());

        // hold → 用户批准 → 重投（store.toHeld 从 DB 行重建信封 + withPreApproval）
        policy.setSettingsSource(sessionId -> "hold");
        assertEquals(PeerMessageRouter.Status.HELD,
            router.send(sendRequest(SOURCE), PermissionMode.DEFAULT, "peer-title-hold").status());
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.approveHeld("peer-title-hold", principal()).status());
        assertNotNull(delivered.get());
        assertEquals("peer-title-hold", delivered.get().messageId());
        assertTrue(delivered.get().preApproved(), "前置：批准信封带一次性标记");
        assertEquals(TITLE, delivered.get().sourceTitle(), "批准重投不得丢标题");
    }

    // ───────────────── ③ 无标题：null 且不抛 ─────────────────

    @Test
    @DisplayName("无标题会话（读不到）：各处为 null、不抛异常、不伪造会话 id")
    void missingTitleStaysNullEverywhere() {
        when(sessionService.getById(SOURCE)).thenThrow(new NotFoundException("Session gone"));

        PeerMessageRouter.Receipt receipt = router.send(sendRequest(SOURCE), PermissionMode.DEFAULT,
            "peer-title-none");
        assertEquals(PeerMessageRouter.Status.HELD, receipt.status(), "标题读失败不得阻断发送");

        PeerEnvelope heldEnvelope = store.list(TARGET).get(0).envelope();
        assertNull(heldEnvelope.sourceTitle());
        assertNull(columnOf("source_title", "peer-title-none"));
        assertNull(controller.list(TARGET).getData().get(0).sourceTitle());
        assertNull(notifier.events.get(0).getSourceTitle());
        PeerExecutionOrigin origin = PeerRuntimeAdapter.originOf(heldEnvelope);
        assertNull(origin.sourceTitle());
        assertNull(peerSourceOf(PeerRuntimeAdapter.structuredOutput(origin, null)).get("sourceTitle"));
    }

    // ───────────────── ④ 历史行：列表补解析 ─────────────────

    @Test
    @DisplayName("历史待收件行 source_title 为空：列表查询按会话记录补解析；读不到保持 null")
    void historicalRowsBackfilledOnList() throws Exception {
        insertLegacyRow("peer-legacy-1", TARGET, SOURCE, null);
        insertLegacyRow("peer-legacy-2", TARGET, "sess-deleted-9", null);
        stubTitle(SOURCE, TITLE);
        when(sessionService.getById("sess-deleted-9")).thenThrow(new NotFoundException("Session gone"));

        List<PeerMessageRouter.HeldView> views = store.listViews(TARGET);
        assertEquals(2, views.size(), "补解析失败不得丢行");
        PeerMessageRouter.HeldView backfilled = viewOf(views, "peer-legacy-1");
        assertEquals(TITLE, backfilled.sourceTitle(), "空标题行必须按会话记录补解析");
        assertNull(viewOf(views, "peer-legacy-2").sourceTitle(), "会话已删 ⇒ 保持 null（不伪造）");

        // 列表接口同样拿到补解析后的标题。
        assertEquals(TITLE, controller.list(TARGET).getData().stream()
            .filter(v -> "peer-legacy-1".equals(v.messageId())).findFirst().orElseThrow().sourceTitle());
        // 只读补解析：不得改写 DB 行。
        assertNull(columnOf("source_title", "peer-legacy-1"));
    }

    // ───────────────── ⑤ 同名不同会话 ─────────────────

    @Test
    @DisplayName("同名不同会话：标题相同但 id 不同，两条均能区分")
    void sameTitleDifferentSessionsStayDistinct() {
        stubTitle(SOURCE, TITLE);
        stubTitle(SOURCE2, TITLE);

        router.send(sendRequest(SOURCE), PermissionMode.DEFAULT, "peer-same-1");
        router.send(sendRequest(SOURCE2), PermissionMode.DEFAULT, "peer-same-2");

        List<PeerMessageRouter.HeldView> views = store.listViews(TARGET);
        assertEquals(2, views.size());
        PeerMessageRouter.HeldView first = viewOf(views, "peer-same-1");
        PeerMessageRouter.HeldView second = viewOf(views, "peer-same-2");
        assertEquals(TITLE, first.sourceTitle());
        assertEquals(TITLE, second.sourceTitle());
        assertNotEquals(first.sourceSessionId(), second.sourceSessionId(), "标题不是唯一键");
        assertNotEquals(first.messageId(), second.messageId());
    }

    // ───────────────── ⑥ 空闲通知 ─────────────────

    @Test
    @DisplayName("空闲通知：投回源会话的通知信封携带「刚空闲会话」的标题")
    void idleNoticeCarriesTargetTitle() {
        stubTitle(TARGET, "目标空闲会话");
        NotificationQueue queue = new NotificationQueue();
        PeerIdleSubscriptions subscriptions = new PeerIdleSubscriptions();
        PeerRuntimeAdapter adapter = new PeerRuntimeAdapter();
        adapter.setSessionService(sessionService);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "peerIdleSubscriptions",
            subscriptions);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter, "wire");

        long targetEpoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        long sourceEpoch = registry.resolve(SOURCE).orElseThrow().endpointEpoch();
        PeerEnvelope envelope = PeerEnvelope.create("peer-idle-src-1", "source [abcdef]", SOURCE,
            "Source", PermissionMode.DEFAULT, List.of(), sourceEpoch, null, true, TITLE);
        assertEquals(TITLE, envelope.sourceTitle());
        assertTrue(subscriptions.subscribe(envelope, TARGET, targetEpoch));

        assertTrue(subscriptions.onTargetIdle(TARGET, targetEpoch).size() == 1);
        NotificationQueue.QueueItem notice = queue.dequeue().orElseThrow();
        assertEquals("目标空闲会话", notice.peerEnvelope().sourceTitle(),
            "空闲通知行的来源标题 = 刚空闲会话的标题");
        assertEquals("目标空闲会话",
            PeerRuntimeAdapter.originOf(notice.peerEnvelope()).sourceTitle());
    }

    // ───────────────── helpers ─────────────────

    private void stubTitle(String sessionId, String title) {
        SessionDto dto = Mockito.mock(SessionDto.class);
        when(dto.title()).thenReturn(title);
        when(sessionService.getById(sessionId)).thenReturn(dto);
    }

    private static PeerMessageRouter.SendRequest sendRequest(String sourceSessionId) {
        return new PeerMessageRouter.SendRequest(
            TARGET, -1L, "Target", sourceSessionId, "Source", "source [abcdef]",
            "hello", "sum", false, false, List.of());
    }

    private static PeerMessageRouter.ApprovalPrincipal principal() {
        return new Principal(TARGET, null, true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> peerSourceOf(Map<String, Object> structuredOutput) {
        return (Map<String, Object>) structuredOutput.get("peer_source");
    }

    private static PeerMessageRouter.HeldView viewOf(
            List<PeerMessageRouter.HeldView> views, String messageId) {
        return views.stream().filter(v -> messageId.equals(v.messageId())).findFirst().orElseThrow();
    }

    private static void insertLegacyRow(String messageId, String target, String source, String title)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO peer_held_message (message_id, target_session_id, source_session_id, "
                     + "source_title, source_kind, from_name, from_mode, from_address, hop_chain, "
                     + "summary, body, reason, status, source_endpoint_epoch, target_endpoint_epoch, "
                     + "notify_when_idle, created_at, seen_at) "
                     + "VALUES (?, ?, ?, ?, 'peer', 'Source', 'default', 'source [abcdef]', NULL, "
                     + "NULL, 'legacy body', 'EXPLICIT_HOLD', 'pending', 1, -1, 0, "
                     + "'2026-11-19T00:00:00Z', NULL)")) {
            ps.setString(1, messageId);
            ps.setString(2, target);
            ps.setString(3, source);
            ps.setString(4, title);
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

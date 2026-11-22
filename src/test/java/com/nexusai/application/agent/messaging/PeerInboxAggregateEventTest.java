package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.session.PeerInboxCountsController;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.eventbus.ws.PeerInboxAggregateEvent;
import com.nexusai.eventbus.ws.PeerInboxEventPublisher;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨会话待收件 · <b>聚合推送 + 计数基线</b>（非 Spring）· 真实临时 SQLite + Flyway。
 *
 * <p><b>WHY 必须真 SQLite</b>：计数口径的判据是「status 过滤 + GROUP BY 聚合 + 在途扣除」，
 * 假实现（内存 map）验不出 SQL 条件写错（例如把 rejected 算进待处理）。
 *
 * <p>覆盖契约：
 * <ul>
 *   <li>a. hold 后事件 seq 自增、pendingCount 正确；</li>
 *   <li>b. accept 后发 updated/removed 且计数减一；</li>
 *   <li>c. 多个会话各自计数正确（且 rejected 不计入 —— 变异 ① 的判别点）；</li>
 *   <li>d. counts 接口与事件一致（seq + 每会话条数）；</li>
 *   <li>e. 重启后 seq 重置但 counts 仍正确。</li>
 * </ul>
 */
class PeerInboxAggregateEventTest {

    private static final String TARGET = "sess-aggr0001";
    private static final String OTHER = "sess-aggr0002";
    private static final String SOURCE = "sess-aggr0003";

    static {
        // 隔离产物证据：确认加载的是本轮编译输出（D:/tmp/prt-classes）而不是共享 target/classes。
        System.out.println("[prt] DbHeldPeerStore 来源 = "
            + DbHeldPeerStore.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[prt] PeerInboxEventPublisher 来源 = "
            + PeerInboxEventPublisher.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[prt] PeerInboxCountsController 来源 = "
            + PeerInboxCountsController.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper mapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private PeerInboxEventPublisher publisher;
    private final List<PeerInboxAggregateEvent> events = new CopyOnWriteArrayList<>();
    private PeerInboxCountsController countsController;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("peer-inbox-aggregate-");
        Path db = dir.resolve("aggregate.db");
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
        events.clear();

        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            OTHER, "Other", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source", "/tmp", PermissionMode.DEFAULT, 1L));

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> "accept");

        store = new DbHeldPeerStore(mapper);
        publisher = PeerInboxEventPublisher.forTest(events::add);
        store.setInboxPublisher(publisher);

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        countsController = new PeerInboxCountsController();
        countsController.setRouter(router);
        countsController.setPublisher(publisher);
    }

    // ───────────────────────── a. hold：seq 自增 + 计数 ─────────────────────────

    @Test
    @DisplayName("a. hold 后发 peer.inbox.hold：seq 严格递增、pendingCount 1→2、item 带条目字段")
    void holdPublishesIncrementingSeqAndCount() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-agg-a1", TARGET, epoch));
        store.hold(held("peer-agg-a2", TARGET, epoch));

        assertEquals(2, events.size(), "每次新 hold 一条聚合事件");
        PeerInboxAggregateEvent first = events.get(0);
        PeerInboxAggregateEvent second = events.get(1);

        assertEquals(PeerInboxAggregateEvent.TYPE_HOLD, first.getType());
        assertEquals(PeerInboxAggregateEvent.TYPE_HOLD, second.getType());
        assertEquals(TARGET, first.getTargetSessionId());
        assertEquals(1L, first.getPendingCount(), "第一条 hold 后该会话待处理 = 1");
        assertEquals(2L, second.getPendingCount(), "第二条 hold 后 = 2");
        assertEquals(1L, first.getSeq(), "seq 从 1 起");
        assertTrue(second.getSeq() > first.getSeq(), "seq 必须严格递增（变异②的判别点）");
        assertEquals(2L, second.getSeq());

        assertNotNull(first.getItem(), "hold 事件带条目");
        assertEquals("peer-agg-a1", first.getItem().messageId());
        assertEquals("pending", first.getItem().status());
        assertEquals("body-peer-agg-a1", first.getItem().body(), "条目正文与旧通道同源");
        assertEquals(2L, publisher.seq(), "发布器 seq 锚点 = 已发事件数");
    }

    // ───────────────────────── b. accept：计数减一 ─────────────────────────

    @Test
    @DisplayName("b. accept 后发 removed 且 pendingCount 减一；已 accepted 再批 → 900 且不再发事件")
    void acceptPublishesRemovalAndDecrementsCount() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-agg-b1", TARGET, epoch));
        assertEquals(1L, lastFor(TARGET).getPendingCount());
        int eventsBefore = events.size();

        PeerMessageRouter.Receipt receipt =
            router.approveHeld("peer-agg-b1", principal(TARGET));
        assertEquals(PeerMessageRouter.Status.ACCEPTED, receipt.status(), "前置：接受成功");

        assertEquals(eventsBefore + 1, events.size(), "accept 必须发一条聚合事件");
        PeerInboxAggregateEvent accepted = events.get(events.size() - 1);
        assertEquals(PeerInboxAggregateEvent.TYPE_REMOVED, accepted.getType(),
            "批准即结算 ⇒ 行离开待办（removed）");
        assertEquals(0L, accepted.getPendingCount(), "accept 后该会话待处理 = 0（减一）");
        assertEquals(0L, countsController.counts().getData().counts()
            .getOrDefault(TARGET, 0L), "counts 接口同口径");

        // 严格 CC 语义：批准即结算，失败也直接终态 ⇒ 不存在「失败回涨」分支；
        // 已 accepted 再批只能 900，且不得再发聚合事件。
        int beforeSecond = events.size();
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld("peer-agg-b1", principal(TARGET)).status());
        assertEquals(beforeSecond, events.size(), "重复批准不得再发聚合事件");
    }

    // ───────────────────────── c. 多会话各自计数（不含 rejected） ─────────────────────────

    @Test
    @DisplayName("c. 多会话事件各自计数正确；rejected 不计入待处理（变异①的判别点）")
    void multipleSessionsCountIndependentlyExcludingRejected() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-agg-c1", TARGET, epoch));
        store.hold(held("peer-agg-c2", TARGET, epoch));
        store.hold(held("peer-agg-c3", OTHER, epoch));

        // TARGET 再拒掉一条：rejected 是终态，⛔ 不进 pendingCount。
        store.hold(held("peer-agg-c4", TARGET, epoch));
        assertTrue(router.rejectHeld("peer-agg-c4", principal(TARGET)), "前置：拒绝成功");

        assertEquals(1L, lastFor(OTHER).getPendingCount());
        assertEquals(2L, lastFor(TARGET).getPendingCount(),
            "TARGET 仍有 2 条待处理（rejected 不算）");

        Map<String, Long> counts = countsController.counts().getData().counts();
        assertEquals(2, counts.size(), "只返回有待处理项的会话");
        assertEquals(2L, counts.get(TARGET));
        assertEquals(1L, counts.get(OTHER));
        assertTrue(!counts.containsKey(SOURCE), "无待处理项的会话不出现（不是 0）");
    }

    // ───────────────────────── d. counts 接口与事件一致 ─────────────────────────

    @Test
    @DisplayName("d. counts 接口：seq = 发布器当前值，counts 与最后一次事件逐会话一致")
    void countsEndpointAgreesWithEvents() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-agg-d1", TARGET, epoch));
        store.hold(held("peer-agg-d2", OTHER, epoch));
        store.hold(held("peer-agg-d3", OTHER, epoch));
        store.hold(held("peer-agg-d4", TARGET, epoch));

        PeerInboxCountsController.CountsView view = countsController.counts().getData();
        assertEquals(0, countsController.counts().getCode(), "沿用 CommonResult code=0");
        assertTrue(countsController.counts().isSuccess());
        assertEquals(publisher.seq(), view.seq(), "seq 是对账锚点");
        assertEquals(4L, view.seq());

        // 与「每个会话最后一次事件」逐项一致。
        assertEquals(lastFor(TARGET).getPendingCount(), view.counts().get(TARGET));
        assertEquals(lastFor(OTHER).getPendingCount(), view.counts().get(OTHER));
        assertEquals(Map.of(TARGET, 2L, OTHER, 2L), view.counts());

        // 与存储层聚合同源。
        assertEquals(store.pendingCounts(), view.counts());

        // FIFO 附加字段：每会话最早一条（按 rowid，而不是 created_at 文本字典序）。
        assertNotNull(view.oldestRowId().get(TARGET), "TARGET 必须带最早 rowid");
        assertNotNull(view.oldestRowId().get(OTHER), "OTHER 必须带最早 rowid");
        assertTrue(view.oldestRowId().get(TARGET) < view.oldestRowId().get(OTHER),
            "TARGET 的第一条先插入 ⇒ 更小的 rowid");
        assertEquals("2026-11-20T00:00:00Z", view.oldestCreatedAt().get(TARGET),
            "最早一条的 created_at 必须与插入值一致");
    }

    // ───────────────────────── e. 重启：seq 重置、counts 仍正确 ─────────────────────────

    @Test
    @DisplayName("e. 重启（新 store + 新 publisher）后 seq 归零重发、counts 仍正确")
    void restartResetsSeqButKeepsCounts() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-agg-e1", TARGET, epoch));
        store.hold(held("peer-agg-e2", TARGET, epoch));
        store.hold(held("peer-agg-e3", OTHER, epoch));
        assertEquals(3L, publisher.seq());

        // 模拟重启：全新 store 与全新 publisher（seq 从 0 起）。
        List<PeerInboxAggregateEvent> restartedEvents = new CopyOnWriteArrayList<>();
        PeerInboxEventPublisher restartedPublisher =
            PeerInboxEventPublisher.forTest(restartedEvents::add);
        DbHeldPeerStore restarted = new DbHeldPeerStore(mapper);
        restarted.setInboxPublisher(restartedPublisher);

        assertEquals(0L, restartedPublisher.seq(), "重启后 seq 归零（没有历史事件）");
        assertEquals(Map.of(TARGET, 2L, OTHER, 1L), restarted.pendingCounts(),
            "重启后计数仍从 DB 正确聚合");
        assertEquals(0L, restarted.pendingCount(TARGET) - 2L,
            "单会话计数同样正确");

        restarted.hold(held("peer-agg-e4", TARGET, epoch));
        assertEquals(1, restartedEvents.size());
        assertEquals(1L, restartedEvents.get(0).getSeq(), "重启后第一条事件 seq = 1");
        assertEquals(3L, restartedEvents.get(0).getPendingCount());
    }

    // ───────────────────────── helpers ─────────────────────────

    private PeerInboxAggregateEvent lastFor(String sessionId) {
        PeerInboxAggregateEvent found = null;
        for (PeerInboxAggregateEvent event : events) {
            if (sessionId.equals(event.getTargetSessionId())) {
                found = event;
            }
        }
        assertNotNull(found, "该会话必须至少有一条聚合事件 session=" + sessionId);
        return found;
    }

    private long epoch(String sessionId) {
        return registry.resolve(sessionId).orElseThrow().endpointEpoch();
    }

    private static PeerMessageRouter.HeldMessage held(String messageId, String targetSessionId,
                                                      long targetEpoch) {
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
            messageId, targetSessionId, targetEpoch, envelope, "body-" + messageId,
            PeerInboundPolicy.Reason.EXPLICIT_HOLD, Instant.parse("2026-11-20T00:00:00Z"));
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return new Principal(sessionId, null, true);
    }

    private static void deleteAllRows() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM peer_held_message")) {
            ps.executeUpdate();
        }
    }

    private record Principal(String sessionId, String requestId, boolean trustedUi)
        implements PeerMessageRouter.ApprovalPrincipal { }
}

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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待收件持久化 + 已读（非 Spring）· 真实临时 SQLite + Flyway V1..V322。
 *
 * <p><b>WHY 必须真 SQLite</b>：幂等/竞态/重启的判据都是「affected rows + 新建实例后重新查」，
 * 假实现验不了（内存 map 永远验不出 INSERT OR IGNORE 与条件 UPDATE）。
 *
 * <p>覆盖：① 同 messageId 重复 hold 只一行且只发一次事件；② approve/reject 并发只有一个生效；
 * ③ 重启（新 store 实例）后 pending 与 seen 保留；④ 重注册/重启后 epoch 过期仍可 approve；
 * ⑤ seen 端点幂等；⑥ 事件发布失败不伪装成功（落库仍成功）。
 */
class PeerInboxPersistenceTest {

    private static final String TARGET = "sess-aaaaaaa1";
    private static final String SOURCE = "sess-ccccccc3";
    private static final String OTHER = "sess-bbbbbbb2";

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
        Path dir = Files.createTempDirectory("peer-held-");
        Path db = dir.resolve("held.db");
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?journal_mode=WAL&busy_timeout=5000");
        // 只读 src/main/resources（不覆盖共享 target/classes）；含本次 V322。
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
        policy.setSettingsSource(sessionId -> "accept");

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

    // ───────────────────────── ① 幂等 ─────────────────────────

    @Test
    @DisplayName("同 messageId 重复 hold：只有一行、只发一次事件、seen_at 不被重置")
    void duplicateHoldIsIdempotent() {
        long epoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        PeerMessageRouter.HeldMessage held = held("peer-dup-1", epoch);

        store.hold(held);
        store.hold(held);
        store.hold(held);

        assertEquals(1, countRows(), "同 messageId 只能有一行");
        assertEquals(List.of("peer-dup-1"), ids(store.list(TARGET)));
        assertEquals(1, notifier.events.size(), "重复 hold 不得重复发事件");
        assertEquals("peer-dup-1", notifier.events.get(0).getMessageId());
        assertEquals("pending", notifier.events.get(0).getStatus());

        // 已读后再重复 hold：行数与 seen_at 都不变。
        assertEquals(1, store.markSeen(TARGET, List.of("peer-dup-1")));
        store.hold(held);
        assertEquals(1, countRows());
        assertNotNull(store.listViews(TARGET).get(0).seenAt(), "重复 hold 不得把已读行重置为未读");
    }

    @Test
    @DisplayName("路由级同一 messageId 重发：HOLD 两次仍只有一行、只发一次事件")
    void routerRepeatedSendWithSameIdHoldsOnce() {
        policy.setSettingsSource(sessionId -> "hold");
        PeerMessageRouter.SendRequest request = new PeerMessageRouter.SendRequest(
            TARGET, -1L, "Target", SOURCE, "Source", "source [aaaaaa]",
            "hello", "sum", false, false, List.of());

        PeerMessageRouter.Receipt first =
            router.send(request, PermissionMode.DEFAULT, "peer-route-1");
        PeerMessageRouter.Receipt second =
            router.send(request, PermissionMode.DEFAULT, "peer-route-1");

        assertEquals(PeerMessageRouter.Status.HELD, first.status());
        assertEquals(PeerMessageRouter.Status.HELD, second.status());
        assertEquals(1, countRows(), "同一 messageId 的两次 send 只落一行");
        assertEquals(1, notifier.events.size(), "幂等 hold 不重复发事件");
    }

    // ───────────────────────── ② 并发 ─────────────────────────

    @Test
    @DisplayName("approve 与 reject 并发：恰好一个生效，终态唯一")
    void approveAndRejectRaceOnlyOneWins() throws Exception {
        long epoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        store.hold(held("peer-race-1", epoch));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PeerMessageRouter.Receipt> approveFuture = pool.submit(() -> {
                start.await();
                return router.approveHeld("peer-race-1", principal(TARGET));
            });
            Future<Boolean> rejectFuture = pool.submit(() -> {
                start.await();
                return router.rejectHeld("peer-race-1", principal(TARGET));
            });
            start.countDown();

            PeerMessageRouter.Receipt approve = approveFuture.get(10, TimeUnit.SECONDS);
            boolean rejected = rejectFuture.get(10, TimeUnit.SECONDS);

            boolean approveWon = approve.status() == PeerMessageRouter.Status.ACCEPTED;
            assertTrue(approveWon ^ rejected,
                "approve/reject 必须恰好一个生效 approve=" + approve.status() + " reject=" + rejected);
            if (rejected) {
                assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED, approve.status());
                assertEquals("rejected", statusOf("peer-race-1"));
            } else {
                // approve 成功 = 批准即结算 → accepted（严格 CC：无 approved 中间态）。
                assertEquals("accepted", statusOf("peer-race-1"));
            }
            assertTrue(store.list(TARGET).isEmpty(), "终态不再出现在 pending 列表");
        } finally {
            pool.shutdownNow();
        }
    }

    // ───────────────────────── ③ 重启保留 ─────────────────────────

    @Test
    @DisplayName("新 store 实例（重启）后 pending 仍在、seen 状态保留")
    void restartKeepsPendingAndSeen() {
        long epoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        store.hold(held("peer-restart-1", epoch));
        store.hold(held("peer-restart-2", epoch));
        assertEquals(1, store.markSeen(TARGET, List.of("peer-restart-1")));

        DbHeldPeerStore restarted = new DbHeldPeerStore(mapper);
        List<PeerMessageRouter.HeldMessage> pending = restarted.list(TARGET);
        assertEquals(2, pending.size(), "重启后 pending 不得丢");

        List<PeerMessageRouter.HeldView> views = restarted.listViews(TARGET);
        assertEquals(2, views.size());
        PeerMessageRouter.HeldView seen = views.stream()
            .filter(v -> "peer-restart-1".equals(v.messageId())).findFirst().orElseThrow();
        PeerMessageRouter.HeldView unseen = views.stream()
            .filter(v -> "peer-restart-2".equals(v.messageId())).findFirst().orElseThrow();
        assertNotNull(seen.seenAt(), "已读状态必须持久化（重启后不重复提醒）");
        assertNull(unseen.seenAt(), "未读状态必须保留");
        assertEquals("pending", seen.status());
    }

    // ───────────────────────── ④ 过期 epoch ─────────────────────────

    @Test
    @DisplayName("重注册导致 epoch 过期：按 sessionId 重新 resolve 后仍可 approve")
    void staleEpochStillApprovableAfterRestart() {
        long oldEpoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        store.hold(held("peer-epoch-1", oldEpoch));

        // 模拟重启后重注册：同 sessionId 产生新 epoch（旧行里的代次已失效）。
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target", "/tmp", PermissionMode.DEFAULT, 2L));
        long currentEpoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        assertTrue(currentEpoch != oldEpoch, "前置：epoch 必须已变化");

        AtomicLong deliveredEpoch = new AtomicLong(-1L);
        router.setDeliveryPort((sid, epoch, envelope, body) -> {
            deliveredEpoch.set(epoch);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null);
        });

        // 新 store 实例 = 重启。
        DbHeldPeerStore restarted = new DbHeldPeerStore(mapper);
        router.setHeldStore(restarted);

        PeerMessageRouter.Receipt receipt =
            router.approveHeld("peer-epoch-1", principal(TARGET));

        assertEquals(PeerMessageRouter.Status.ACCEPTED, receipt.status(),
            "epoch 快照过期不得判死（应按 sessionId 重新 resolve）detail=" + receipt.detail());
        assertEquals(currentEpoch, deliveredEpoch.get(), "投递必须使用当前 resolve 到的 epoch");
        // 批准即结算：投递成功 ⇒ accepted（⛔ 不再有 approved 中间态，也不需要「暂隐」）。
        assertEquals("accepted", statusOf("peer-epoch-1"));
        assertEquals(0, restarted.list(TARGET).size(), "已结算的行不在待收件列表");
    }

    // ───────────────────────── ⑤ seen 端点 ─────────────────────────

    @Test
    @DisplayName("seen 端点：空数组标记全部未读；重复调用幂等；他人会话改不到")
    void seenEndpointIsIdempotent() {
        long epoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        store.hold(held("peer-seen-1", epoch));
        store.hold(held("peer-seen-2", epoch));

        assertEquals(2, controller.seen(TARGET, null).getData().updated(),
            "空 body = 该会话全部 pending");
        assertEquals(0, controller.seen(TARGET, null).getData().updated(), "第二次幂等 → 0");

        List<PeerInboxController.HeldView> listed = controller.list(TARGET).getData();
        assertEquals(2, listed.size(), "标记已读不改变 pending 列表");
        assertTrue(listed.stream().allMatch(v -> v.seenAt() != null), "两行都应已读");
        assertTrue(listed.stream().allMatch(v -> "pending".equals(v.status())));

        // 显式 messageIds：只改指定行；重复调用 0。
        store.hold(held("peer-seen-3", epoch));
        store.hold(held("peer-seen-4", epoch));
        assertEquals(1, controller.seen(TARGET,
            new PeerInboxController.SeenRequest(List.of("peer-seen-3"))).getData().updated());
        assertEquals(0, controller.seen(TARGET,
            new PeerInboxController.SeenRequest(List.of("peer-seen-3"))).getData().updated());

        // 他人会话：路径 id 是归属真源，传入他人 messageIds 不会改到 TARGET 的行。
        assertEquals(0, controller.seen(OTHER,
            new PeerInboxController.SeenRequest(List.of("peer-seen-4"))).getData().updated());
        assertNull(viewOf(TARGET, "peer-seen-4").seenAt(), "跨会话 seen 不得改他人行");
    }

    // ───────────────────────── ⑥ 发布失败不伪装 ─────────────────────────

    @Test
    @DisplayName("事件发布抛异常/返回 false：hold 仍落库，且不声称已发布")
    void notifierFailureDoesNotFakePublish() {
        store.setNotifier(event -> {
            throw new IllegalStateException("stomp down");
        });
        long epoch = registry.resolve(TARGET).orElseThrow().endpointEpoch();
        store.hold(held("peer-notify-1", epoch));
        assertEquals(1, countRows(), "发布异常不得回滚已落库行");

        store.setNotifier(event -> false);
        store.hold(held("peer-notify-2", epoch));
        assertEquals(2, countRows());
        assertEquals(List.of("peer-notify-1", "peer-notify-2"), ids(store.list(TARGET)));
    }

    // ───────────────────────── helpers ─────────────────────────

    private static PeerMessageRouter.HeldMessage held(String messageId, long targetEpoch) {
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
            messageId, TARGET, targetEpoch, envelope, "body-" + messageId,
            PeerInboundPolicy.Reason.EXPLICIT_HOLD, Instant.parse("2026-11-20T00:00:00Z"));
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return new Principal(sessionId, null, true);
    }

    private static List<String> ids(List<PeerMessageRouter.HeldMessage> held) {
        return held.stream().map(PeerMessageRouter.HeldMessage::messageId).toList();
    }

    private static PeerMessageRouter.HeldView viewOf(String sessionId, String messageId) {
        return new DbHeldPeerStore(mapper).listViews(sessionId).stream()
            .filter(v -> messageId.equals(v.messageId()))
            .findFirst().orElseThrow();
    }

    private static void deleteAllRows() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM peer_held_message")) {
            ps.executeUpdate();
        }
    }

    private static int countRows() {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM peer_held_message");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            throw new AssertionError("countRows failed", e);
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

    private static final class RecordingNotifier implements PeerHeldNotifier {

        private final List<PeerInboxHoldEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public boolean publish(PeerInboxHoldEvent event) {
            events.add(event);
            return true;
        }
    }
}

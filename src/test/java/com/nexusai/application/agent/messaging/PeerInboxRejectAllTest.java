package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.session.PeerInboxController;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.infra.exception.ForbiddenException;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待收件批量拒绝（非 Spring）· 真实临时 SQLite + Flyway V1..V322。
 *
 * <p><b>WHY 必须真 SQLite</b>：判据是「单语句条件 UPDATE 的 affected rows」与「终态行不被误伤」，
 * 假实现（内存 map）验不出 {@code WHERE status='pending'} 与 approve 的抢占竞争。
 *
 * <p>覆盖：① 3 条 pending 批拒 = 3 且列表清空；② 再调 = 0（幂等）；③ 他人会话 pending 不动；
 * ④ 已 accepted 不被批拒影响；⑤ 错 session / 不可信主体 → 0 且数据不变（HTTP 层 = 403）；
 * ⑥ rejectAll 与 approve 并发只允许一个成功。
 */
class PeerInboxRejectAllTest {

    private static final String TARGET = "sess-rabe0001";
    private static final String SOURCE = "sess-rabe0003";
    private static final String OTHER = "sess-rabe0002";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper mapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private PeerInboxController controller;

    static {
        // 隔离产物证据：确认加载的是本轮编译输出而不是共享 target/classes。
        System.out.println("[rabe] PeerHeldMessageMapper 来源 = "
            + PeerHeldMessageMapper.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[rabe] DbHeldPeerStore 来源 = "
            + DbHeldPeerStore.class.getProtectionDomain().getCodeSource().getLocation());
    }

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("peer-reject-all-");
        Path db = dir.resolve("rabe.db");
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

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
    }

    // ───────────────────────── ① 基本批拒 ─────────────────────────

    @Test
    @DisplayName("同会话 3 条 pending → rejectAll=3，可操作列表清空，展示列表保留 rejected")
    void rejectsAllPendingOfOwnSession() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-rabe-1", TARGET, epoch, EXPLICIT_HOLD));
        store.hold(held("peer-rabe-2", TARGET, epoch, EXPLICIT_HOLD));
        store.hold(held("peer-rabe-3", TARGET, epoch, EXPLICIT_HOLD));
        assertEquals(3, store.list(TARGET).size(), "前置：3 条 pending");

        int rejected = router.rejectAll(TARGET, principal(TARGET));

        assertEquals(3, rejected);
        // 2026-11-20 用户裁定：列表要透出已拒绝行（前端渲染不可操作卡片），
        // 但「可操作/待处理」口径必须清空（不可再批准/拒绝/续期）。
        assertTrue(store.list(TARGET).isEmpty(), "批拒后可操作列表必须为空");
        List<PeerMessageRouter.HeldView> views = router.listHeldViews(TARGET, principal(TARGET));
        assertEquals(3, views.size(), "已拒绝行仍可回看");
        assertTrue(views.stream().allMatch(v -> "rejected".equals(v.status())));
        assertEquals("rejected", statusOf("peer-rabe-1"));
        assertEquals("rejected", statusOf("peer-rabe-2"));
        assertEquals("rejected", statusOf("peer-rabe-3"));
    }

    @Test
    @DisplayName("再调一次 rejectAll → 0（幂等，不改任何行）")
    void secondCallRejectsNothing() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-rabe-1", TARGET, epoch, EXPLICIT_HOLD));
        assertEquals(1, router.rejectAll(TARGET, principal(TARGET)));

        assertEquals(0, router.rejectAll(TARGET, principal(TARGET)), "无 pending → 0");
        assertEquals("rejected", statusOf("peer-rabe-1"));
    }

    @Test
    @DisplayName("另一会话的 pending 不受影响（不跨会话）")
    void otherSessionIsUntouched() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-rabe-1", TARGET, epoch, EXPLICIT_HOLD));
        store.hold(held("peer-rabe-other", OTHER, epoch, EXPLICIT_HOLD));

        assertEquals(1, router.rejectAll(TARGET, principal(TARGET)));

        assertEquals("rejected", statusOf("peer-rabe-1"));
        assertEquals("pending", statusOf("peer-rabe-other"), "他人会话必须保持 pending");
        assertEquals(List.of("peer-rabe-other"),
            router.listHeldViews(OTHER, principal(OTHER)).stream()
                .map(PeerMessageRouter.HeldView::messageId).toList());
    }

    @Test
    @DisplayName("已 accepted（批准即结算）的消息不被批量拒绝影响（只动 pending）")
    void acceptedRowSurvivesRejectAll() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-rabe-accepted", TARGET, epoch, EXPLICIT_HOLD));
        store.hold(held("peer-rabe-pending", TARGET, epoch, EXPLICIT_HOLD));

        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.approveHeld("peer-rabe-accepted", principal(TARGET)).status(),
            "前置：approve 成功（批准即结算 → accepted）");
        assertEquals("accepted", statusOf("peer-rabe-accepted"));

        assertEquals(1, router.rejectAll(TARGET, principal(TARGET)),
            "只应拒绝仍为 pending 的那 1 条");

        assertEquals("accepted", statusOf("peer-rabe-accepted"),
            "已批准（accepted）的行不受批拒影响（批拒只动 pending）");
        assertEquals("rejected", statusOf("peer-rabe-pending"));
    }

    // ───────────────────────── ⑤ 错归属/不可信 ─────────────────────────

    @Test
    @DisplayName("错 session / 不可信主体 → 0 且数据不变；HTTP 层 → 403 且不改数据")
    void wrongOwnerOrUntrustedChangesNothing() {
        long epoch = epoch(TARGET);
        store.hold(held("peer-rabe-1", TARGET, epoch, EXPLICIT_HOLD));
        store.hold(held("peer-rabe-other", OTHER, epoch, EXPLICIT_HOLD));

        // 主体会话 = TARGET，却要求拒绝 OTHER → 0，两行都不变。
        assertEquals(0, router.rejectAll(OTHER, principal(TARGET)), "错归属必须 0");
        assertEquals(0, store.rejectAll(TARGET, principal(OTHER)), "store 层错归属必须 0");
        // 不可信主体（未经准入）→ 0。
        assertEquals(0, router.rejectAll(TARGET, principal(TARGET, false)), "不可信主体必须 0");
        assertEquals(0, store.rejectAll(TARGET, principal(TARGET, false)), "store 层不可信必须 0");
        // null 主体 → 0。
        assertEquals(0, router.rejectAll(TARGET, null));

        assertEquals("pending", statusOf("peer-rabe-1"), "拒绝失败不得改任何行");
        assertEquals("pending", statusOf("peer-rabe-other"));

        // HTTP 层：安装「拒绝一切」验证器 ⇒ 403，且数据仍不变。
        PeerUiApprovalAuthority.installVerifier(claim -> false);
        ForbiddenException forbidden = assertThrows(ForbiddenException.class,
            () -> controller.rejectAll(TARGET));
        assertNotNull(forbidden.getMessage());
        assertEquals("pending", statusOf("peer-rabe-1"), "403 不得改数据");

        // 恢复可信任证器后正常可用（证明 403 来自准入而不是端点损坏）。
        PeerUiApprovalAuthority.clearForTest();
        assertEquals(1, controller.rejectAll(TARGET).getData().rejected());
    }

    // ───────────────────────── ⑥ 并发 ─────────────────────────

    @Test
    @DisplayName("rejectAll 与 approve 并发：只允许一个成功，终态唯一")
    void rejectAllRacesApprove() throws Exception {
        long epoch = epoch(TARGET);
        store.hold(held("peer-rabe-race", TARGET, epoch, EXPLICIT_HOLD));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PeerMessageRouter.Receipt> approveFuture = pool.submit(() -> {
                start.await();
                return router.approveHeld("peer-rabe-race", principal(TARGET));
            });
            Future<Integer> rejectAllFuture = pool.submit(() -> {
                start.await();
                return router.rejectAll(TARGET, principal(TARGET));
            });
            start.countDown();

            PeerMessageRouter.Receipt approve = approveFuture.get(10, TimeUnit.SECONDS);
            int rejected = rejectAllFuture.get(10, TimeUnit.SECONDS);

            boolean approveWon = approve.status() == PeerMessageRouter.Status.ACCEPTED;
            assertEquals(approveWon, rejected == 0,
                "同一行只能被一个操作改到 approve=" + approve.status() + " rejected=" + rejected);
            String status = statusOf("peer-rabe-race");
            // approve 成功 ⇒ accepted（批准即结算）；rejectAll 成功 ⇒ rejected。终态唯一。
            assertTrue("accepted".equals(status) || "rejected".equals(status),
                "终态必须唯一，实际=" + status);
            assertEquals(approveWon ? "accepted" : "rejected", status);
            if (!approveWon) {
                assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED, approve.status());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * 迁移目录：默认仓库真源；可用 {@code -Drabe.migrations=filesystem:...} 指向等价副本。
     *
     * <p>用途：仓库里若同时存在两批互相撞号的迁移（不同会话并行演进），Flyway 会以
     * 「Found more than one migration with version」直接拒绝启动；此时验证用一份
     * 内容不变、只调整版本号的临时副本，⛔ 不改仓库里别人的迁移文件。
     */
    private static String migrationsLocation() {
        String override = System.getProperty("rabe.migrations");
        return override == null || override.isBlank()
            ? "filesystem:src/main/resources/db/migration" : override;
    }

    private static final PeerInboundPolicy.Reason EXPLICIT_HOLD =
        PeerInboundPolicy.Reason.EXPLICIT_HOLD;

    private long epoch(String sessionId) {
        return registry.resolve(sessionId).orElseThrow().endpointEpoch();
    }

    private static PeerMessageRouter.HeldMessage held(String messageId, String targetSessionId,
                                                      long targetEpoch,
                                                      PeerInboundPolicy.Reason reason) {
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
            reason, Instant.parse("2026-11-20T00:00:00Z"));
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return principal(sessionId, true);
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId, boolean trusted) {
        return new Principal(sessionId, null, trusted);
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
}

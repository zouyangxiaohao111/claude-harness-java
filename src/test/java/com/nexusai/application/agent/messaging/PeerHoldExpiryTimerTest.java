package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.apis.session.PeerInboxController;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.MessageService;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.zjky.pro.framework.common.pojo.CommonResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待确认（hold）确认卡的过期计时口径 · 真实临时 SQLite + 全量 Flyway。
 *
 * <p><b>用户更正（2026-11-20）</b>：待确认卡片就嵌在<b>对话流里</b>，流被卡住直到用户处理
 * ⇒ 不存在「卡片被藏起来再重新可见」的模型。<b>没有续期入口</b>。
 *
 * <h2>口径</h2>
 * <ol>
 *   <li>默认 TTL = <b>600000 ms（10 分钟）</b>，环境变量 {@code NEXUSAI_USER_DIALOG_TIMEOUT_MS}
 *       可覆盖，{@code <= 0} 禁用；</li>
 *   <li>到期时刻只在<b>首次被展示</b>时写一次；轮询、切会话、重进页面都<b>不延长</b>；</li>
 *   <li>懒结算：列表 / 审批 / 拒绝前先把已到期行转 expired 并通知发送方（只通知一次）；</li>
 *   <li>接收方关闭会话 ⇒ 全部未到终态行立即结转 expired；100 条上限淘汰最旧；</li>
 *   <li>列表返回 pending/<b>rejected</b>（已拒绝卡片可回看、不可操作；⛔ 无 approved 中间态），
 *       ⛔ 不含 expired；计数由前端按 {@code status==='pending'} 算，后端无 total/unread 字段。</li>
 * </ol>
 *
 * <h2>RED teeth（变异）</h2>
 * <ul>
 *   <li>① 列表每次刷新到期时刻（轮询续期）⇒ {@link #expiryIsSetOnceAndNeverExtended()} 红；</li>
 *   <li>② 列表不返回 rejected ⇒ {@link #listExposesDeclinedRowsWithoutCountingThemAsPending()} 红。</li>
 * </ul>
 */
@DisplayName("[peer-hold-expiry-timer] 首次展示起算 / 任何拉取不续期 / 过期懒结算 / 列表含已拒绝")
class PeerHoldExpiryTimerTest {

    private static final String TARGET = "sess-etb-target1";
    private static final String SOURCE = "sess-etb-source2";
    private static final String OTHER = "sess-etb-other3";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper heldMapper;
    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;

    private PeerSessionRegistry registry;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private PeerInboxController controller;
    private MessageService messageService;

    static {
        // 隔离产物证据：确认加载的是本轮编译输出（D:/tmp/etb-classes），不是共享 target/classes。
        System.out.println("[etb] DbHeldPeerStore 来源 = "
            + DbHeldPeerStore.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[etb] PeerMessageRouter 来源 = "
            + PeerMessageRouter.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[etb] PeerHeldMessageMapper 来源 = "
            + PeerHeldMessageMapper.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[etb] PeerInboxController 来源 = "
            + PeerInboxController.class.getProtectionDomain().getCodeSource().getLocation());
    }

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "peer-expiry-timer-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("timer.db").toAbsolutePath().toString()
            .replace('\\', '/') + "?journal_mode=WAL&busy_timeout=5000");
        ds.setBusyTimeout(10_000);
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration")
                .toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds,
            PeerHeldMessageMapper.class, MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        heldMapper = MybatisFlexBootstrap.getInstance().getMapper(PeerHeldMessageMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
    }

    @BeforeEach
    void setUp() {
        cleanTables();
        insertSession(TARGET, "Target session");
        insertSession(SOURCE, "Source session");
        insertSession(OTHER, "Other session");
        String tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString();
        SessionProjectRoot.setForSession(TARGET, tmp);
        SessionProjectRoot.setForSession(SOURCE, tmp);

        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target session", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source session", "/tmp", PermissionMode.DEFAULT, 1L));

        PeerInboundPolicy policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> "hold");

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper",
            MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class));

        SimpMessagingTemplate stomp = Mockito.mock(SimpMessagingTemplate.class);
        PeerFailureNotifier failureNotifier = new PeerFailureNotifier();
        failureNotifier.setMessageService(messageService);
        failureNotifier.setMessagingTemplate(stomp);

        store = new DbHeldPeerStore(heldMapper);
        store.setNotifier(event -> true);
        store.setFailureNotifier(failureNotifier);

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(store);
        router.setFailureNotifier(failureNotifier);
        router.setDeliveryPort((sid, epoch, envelope, body) ->
            new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "ok", null));

        controller = new PeerInboxController();
        controller.setRouter(router);
    }

    @AfterEach
    void tearDown() {
        PeerUiApprovalAuthority.clearForTest();
        cleanTables();
        SessionProjectRoot.reset();
    }

    // ─────────────── a. 首次展示设一次；重复拉取/切会话不延长 ───────────────

    @Test
    @DisplayName("a. 首次展示写入到期时刻（默认 10 分钟）；重复拉取 / 新实例 / 切会话都不改变")
    void expiryIsSetOnceAndNeverExtended() {
        store.setDialogTimeoutMsForTest(DbHeldPeerStore.DEFAULT_DIALOG_TIMEOUT_MS);
        store.hold(held("peer-etb-a-1", 1));

        long before = System.currentTimeMillis();
        store.listViews(TARGET);
        Long first = expiresOf("peer-etb-a-1");
        assertNotNull(first, "首次展示必须写下到期时刻");
        assertTrue(first >= before + 600_000L && first <= System.currentTimeMillis() + 600_000L,
            "到期时刻应为 now + 600000（默认 10 分钟），实际=" + first);
        assertNotNull(lastShownOf("peer-etb-a-1"), "首次展示也要记录 last_shown_at");

        // 轮询：把 TTL 换成 60s —— 若「每次拉取都续期」，到期时刻会变成 now+60s。
        store.setDialogTimeoutMsForTest(60_000L);
        store.listViews(TARGET);
        assertEquals(first, expiresOf("peer-etb-a-1"), "第二次拉取不得改变到期时刻（轮询不续期）");

        // 模拟「切走再回来」：另一会话的列表 + 新 store 实例（重启/重连）都不得续期。
        store.listViews(OTHER);
        DbHeldPeerStore reconnected = new DbHeldPeerStore(heldMapper);
        reconnected.setDialogTimeoutMsForTest(60_000L);
        reconnected.listViews(TARGET);
        assertEquals(first, expiresOf("peer-etb-a-1"), "切会话/重连后重新拉取同样不得续期");

        // 连续轮询亦同。
        store.listViews(TARGET);
        store.listViews(TARGET);
        assertEquals(first, expiresOf("peer-etb-a-1"), "连续轮询不得续期");
    }

    // ─────────────── b. 到期懒结算 + 只通知一次 ───────────────

    @Test
    @DisplayName("b. 到期懒结算为 expired、发送方只收到一条通知（再次拉取不重复发）")
    void expirySettlesLazilyAndNotifiesOnce() {
        store.setDialogTimeoutMsForTest(DbHeldPeerStore.DEFAULT_DIALOG_TIMEOUT_MS);
        store.hold(held("peer-etb-b-1", 1));
        store.listViews(TARGET); // 首次展示 → 计时
        // 模拟「已经超时」：把到期时刻推到过去（不依赖真实等待）。
        heldMapper.setPendingExpiresAt("peer-etb-b-1", System.currentTimeMillis() - 1);

        List<PeerMessageRouter.HeldView> views = store.listViews(TARGET);
        assertEquals(0, views.size(), "过期后不再出现在待收件列表");
        assertEquals("expired", statusOf("peer-etb-b-1"));

        // 再次读列表：不得重复通知。
        store.listViews(TARGET);
        assertEquals(1, notices(SOURCE).size(), "过期通知只发一条");
        assertTrue(notices(SOURCE).get(0).getStructuredOutput().contains("peer_failure"));

        // 过期是终态：不可批准。
        assertEquals(PeerMessageRouter.Status.APPROVAL_REJECTED,
            router.approveHeld("peer-etb-b-1", principal(TARGET)).status(),
            "过期行不得再被批准");
        assertEquals("expired", statusOf("peer-etb-b-1"));
    }

    // ─────────────── c. 关闭接收方会话 ⇒ 立即结算 ───────────────

    @Test
    @DisplayName("c. 关闭接收方会话 → 全部 pending 立即结转 expired 并逐条通知")
    void closingSessionSettlesImmediately() {
        store.setDialogTimeoutMsForTest(DbHeldPeerStore.DEFAULT_DIALOG_TIMEOUT_MS);
        store.hold(held("peer-etb-c-1", 1));
        store.hold(held("peer-etb-c-2", 2));

        int expired = router.expireAllHeld(TARGET, PeerFailureNotifier.REASON_RECEIVER_CLOSED);

        assertEquals(2, expired);
        assertEquals("expired", statusOf("peer-etb-c-1"));
        assertEquals("expired", statusOf("peer-etb-c-2"));
        assertEquals(2, notices(SOURCE).size(), "逐条通知（每条被结转消息各一条）");
        assertTrue(store.list(TARGET).isEmpty());
        assertTrue(store.listViews(TARGET).isEmpty(), "expired 不出现在展示列表");
    }

    // ─────────────── d. 列表返回 declined，且不计入 pending ───────────────

    @Test
    @DisplayName("d. 列表返回 rejected（已拒绝）行；待处理口径仍只算 pending，且拒绝行不参与过期计时")
    void listExposesDeclinedRowsWithoutCountingThemAsPending() {
        store.setDialogTimeoutMsForTest(DbHeldPeerStore.DEFAULT_DIALOG_TIMEOUT_MS);
        store.hold(held("peer-etb-d-1", 1));
        store.hold(held("peer-etb-d-2", 2));

        assertTrue(router.rejectHeld("peer-etb-d-1", principal(TARGET)));

        List<PeerMessageRouter.HeldView> views = store.listViews(TARGET);
        assertEquals(2, views.size(), "已拒绝行必须透出（前端渲染不可操作卡片）");
        assertEquals(1, views.stream().filter(v -> "rejected".equals(v.status())).count());
        assertEquals(1, views.stream().filter(v -> "pending".equals(v.status())).count(),
            "待处理口径（前端 status==='pending'）只有 1 条");

        // 内部可操作列表不含已拒绝行（不可再批准/拒绝）。
        assertEquals(List.of("peer-etb-d-2"),
            store.list(TARGET).stream().map(PeerMessageRouter.HeldMessage::messageId).toList());

        // HTTP 列表同样透出（body/status 完整，供卡片回看）。
        CommonResult<List<PeerInboxController.HeldView>> listed = controller.list(TARGET);
        assertEquals(0, listed.getCode());
        assertEquals(2, listed.getData().size());
        PeerInboxController.HeldView declined = listed.getData().stream()
            .filter(v -> "rejected".equals(v.status())).findFirst().orElseThrow();
        assertEquals("peer-etb-d-1", declined.messageId());
        assertEquals("body-peer-etb-d-1", declined.body(), "已拒绝卡片仍可回看正文");
        assertFalse(declined.bodyLines() < 1, "正文规模字段仍有效");

        // 已拒绝行不参与过期计时（列表多次拉取也不写到期时刻）。
        assertNull(expiresOf("peer-etb-d-1"), "已拒绝行不得写到期时刻");
        store.listViews(TARGET);
        assertNull(expiresOf("peer-etb-d-1"));
        assertNotNull(expiresOf("peer-etb-d-2"));
    }

    // ─────────────── e. TTL 覆盖 + <=0 禁用 ───────────────

    @Test
    @DisplayName("e. 默认 600000；环境变量覆盖生效；<=0 禁用（不写到期时刻、不结算）")
    void timeoutResolutionAndDisabledMode() {
        assertEquals(600_000L, DbHeldPeerStore.DEFAULT_DIALOG_TIMEOUT_MS, "默认 TTL = 10 分钟");
        assertEquals(60_000L, DbHeldPeerStore.resolveTimeoutMs(null, "60000"), "环境变量覆盖生效");
        assertEquals(0L, DbHeldPeerStore.resolveTimeoutMs(null, "0"), "0 = 禁用");
        assertEquals(-1L, DbHeldPeerStore.resolveTimeoutMs(null, " -1 "), "负数 = 禁用（空白容忍）");
        assertEquals(600_000L, DbHeldPeerStore.resolveTimeoutMs(null, "abc"), "非法值回落默认");
        assertEquals(600_000L, DbHeldPeerStore.resolveTimeoutMs(null, null), "未设置回落默认");
        assertEquals(1_234L, DbHeldPeerStore.resolveTimeoutMs(1_234L, "60000"),
            "测试覆盖值优先于环境变量");

        // <=0 禁用：不设到期时刻。
        store.setDialogTimeoutMsForTest(0L);
        store.hold(held("peer-etb-e-1", 1));
        store.listViews(TARGET);
        assertNull(expiresOf("peer-etb-e-1"), "禁用过期时不得写 pending_expires_at");

        // 即便库里已有过去的到期时刻（例如先启用后禁用），禁用期间也不结算。
        heldMapper.setPendingExpiresAt("peer-etb-e-1", System.currentTimeMillis() - 1);
        store.listViews(TARGET);
        assertEquals("pending", statusOf("peer-etb-e-1"), "禁用期间不得懒结算");
        assertTrue(notices(SOURCE).isEmpty());
    }

    // ───────────────────────── helpers ─────────────────────────

    private static PeerMessageRouter.HeldMessage held(String messageId, int order) {
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
        Instant heldAt = Instant.parse("2026-11-20T00:00:00Z").plusMillis(order);
        return new PeerMessageRouter.HeldMessage(
            messageId, TARGET, 1L, envelope, "body-" + messageId,
            PeerInboundPolicy.Reason.EXPLICIT_HOLD, heldAt);
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return new TestPrincipal(sessionId);
    }

    private static Long expiresOf(String messageId) {
        var record = heldMapper.selectByMessageId(messageId);
        return record == null ? null : record.getPendingExpiresAt();
    }

    private static Long lastShownOf(String messageId) {
        var record = heldMapper.selectByMessageId(messageId);
        return record == null ? null : record.getLastShownAt();
    }

    private static String statusOf(String messageId) {
        var record = heldMapper.selectByMessageId(messageId);
        return record == null ? null : record.getStatus();
    }

    private static List<MessageRecord> notices(String sessionId) {
        return messageMapper.selectListByQuery(
            com.mybatisflex.core.query.QueryWrapper.create().eq("session_id", sessionId));
    }

    private static void insertSession(String id, String title) {
        SessionRecord record = new SessionRecord();
        record.setId(id);
        record.setModelTag("DS");
        record.setModelName("test-model");
        record.setTitle(title);
        record.setTime("刚刚");
        record.setSessionGroup("default");
        sessionMapper.insertSelective(record);
    }

    private static void cleanTables() {
        List<String> ids = List.of(TARGET, SOURCE, OTHER);
        messageMapper.deleteByQuery(com.mybatisflex.core.query.QueryWrapper.create().in("session_id", ids));
        sessionMapper.deleteByQuery(com.mybatisflex.core.query.QueryWrapper.create().in("id", ids));
        heldMapper.deleteByQuery(com.mybatisflex.core.query.QueryWrapper.create().in("target_session_id", ids));
    }

    private record TestPrincipal(String sessionId) implements PeerMessageRouter.ApprovalPrincipal {
        @Override
        public String requestId() {
            return null;
        }

        @Override
        public boolean trustedUi() {
            return true;
        }
    }
}

package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.chat.ChatService;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.MessageService;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 待确认消息的过期规则 + 失败通知（对齐 CC 2.1.328 实测语义）· 真实临时 SQLite + 全量 Flyway。
 *
 * <p>CC 没有 hold 专用 TTL；结算只有三种：用户批准/拒绝、接收方会话关闭全部结转 expired、
 * pending 满 100 淘汰最旧。用户看到的「过期」= 确认卡通用对话框超时（默认 300000 ms，
 * 环境变量 NEXUSAI_USER_DIALOG_TIMEOUT_MS 可覆盖），从「被展示」开始计时。
 *
 * <p>用户裁定（2026-11-20 改判）：hold 消息<b>不</b>写 messages 表，拒绝也不回写 held_state；
 * 待确认消息只存在 peer_held_message。失败<b>通知</b>仍必须落进发送方会话记录。
 */
@DisplayName("[peer-hold-expiry] 过期规则 + 失败通知（真 sqlite）")
class PeerHoldExpirySettlementTest {

    private static final String TARGET = "sess-exp-target1";
    private static final String SOURCE = "sess-exp-source2";
    private static final String DELETED_SOURCE = "sess-exp-deleted3";

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper heldMapper;
    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private DbHeldPeerStore store;
    private PeerFailureNotifier failureNotifier;
    private SimpMessagingTemplate stomp;
    private MessageService messageService;

    static {
        // 隔离产物证据：确认加载的是本轮编译输出（/tmp/hei-classes），不是共享 target/classes。
        System.out.println("[hei] DbHeldPeerStore 来源 = "
            + DbHeldPeerStore.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[hei] ChatService 来源 = "
            + ChatService.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[hei] PeerFailureNotifier 来源 = "
            + PeerFailureNotifier.class.getProtectionDomain().getCodeSource().getLocation());
    }

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "peer-expiry-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("expiry.db").toAbsolutePath().toString()
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
        String tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString();
        SessionProjectRoot.setForSession(TARGET, tmp);
        SessionProjectRoot.setForSession(SOURCE, tmp);

        registry = new PeerSessionRegistry();
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target session", "/tmp", PermissionMode.DEFAULT, 1L));
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Source session", "/tmp", PermissionMode.DEFAULT, 1L));

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(sessionId -> "hold");

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper",
            MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class));

        stomp = mock(SimpMessagingTemplate.class);
        failureNotifier = new PeerFailureNotifier();
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

        PeerRuntimeAdapter adapter = new PeerRuntimeAdapter();
        adapter.setMessagingTemplate(stomp);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        router.setRejectionNotifier(adapter);
    }

    @AfterEach
    void tearDown() {
        cleanTables();
        SessionProjectRoot.reset();
    }

    // ───────────────────────── a. 到期未处理 ─────────────────────────

    @Test
    @DisplayName("a. 到期未处理 → expired，发送方会话出现一条可读通知并推 STOMP")
    void expiredWithoutApprovalSettlesAndNotifiesSender() {
        store.hold(held("peer-exp-a-1", 1, SOURCE));
        // 模拟「上一轮展示已是 5 分钟前」：到期时刻已在过去（不依赖真实等待）。
        assertEquals(1, heldMapper.setPendingExpiresAt("peer-exp-a-1",
            System.currentTimeMillis() - 1));

        List<PeerMessageRouter.HeldView> views = store.listViews(TARGET);

        assertTrue(views.isEmpty(), "到期后不再出现在待收件列表");
        assertEquals("expired", statusOf("peer-exp-a-1"));

        List<MessageRecord> notices = notices(SOURCE);
        assertEquals(1, notices.size(), "发送方会话必须有且只有一条失败通知");
        MessageRecord notice = notices.get(0);
        assertEquals("peer-failure-peer-exp-a-1", notice.getId());
        assertEquals("agent_message", notice.getSubtype());
        assertEquals(Boolean.TRUE, notice.getIsMeta());
        assertEquals(TARGET, notice.getAuthor(), "author = 接收方会话");
        assertNotNull(notice.getStructuredOutput());
        assertTrue(notice.getStructuredOutput().contains("peer_failure"),
            "structured_output 必须带 peer_failure：" + notice.getStructuredOutput());
        assertTrue(notice.getStructuredOutput().contains("peer-exp-a-1"));
        assertTrue(notice.getContent().contains(
                "Your held message expired without approval and was not delivered to"),
            "必须使用 CC 原文：" + notice.getContent());
        assertTrue(notice.getContent().contains("Do not resend"),
            "模型可见版本必须说明不要在未获提示时重发");
        verify(stomp).convertAndSend(eq("/topic/sessions/" + SOURCE + "/stream"), any(Object.class));
    }

    // ───────────────────── b. 会话关闭全部结转 ─────────────────────

    @Test
    @DisplayName("b. 关闭接收方会话 → 全部 pending 转 expired 并逐条通知")
    void closingReceiverSettlesAllPending() {
        store.hold(held("peer-exp-b-1", 1, SOURCE));
        store.hold(held("peer-exp-b-2", 2, SOURCE));

        ChatService chatService = new ChatService();
        @SuppressWarnings("unchecked")
        ObjectProvider<PeerMessageRouter> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(router);
        ReflectionTestUtils.setField(chatService, "peerMessageRouterProvider", provider);

        chatService.closeSession(TARGET);

        assertEquals("expired", statusOf("peer-exp-b-1"));
        assertEquals("expired", statusOf("peer-exp-b-2"));
        assertEquals(2, notices(SOURCE).size(), "逐条通知（每条被结转消息各一条）");
        assertTrue(store.list(TARGET).isEmpty());
    }

    // ───────────────────── c. 100 条上限淘汰最旧 ─────────────────────

    @Test
    @DisplayName("c. pending 超过 100 → 淘汰最旧为 expired 并通知")
    void queueCapEvictsOldestAsExpired() {
        for (int i = 0; i <= DbHeldPeerStore.MAX_PENDING_PER_RECEIVER; i++) {
            store.hold(held(String.format("peer-exp-c-%03d", i), i, SOURCE));
        }

        assertEquals("expired", statusOf("peer-exp-c-000"), "最旧一条必须被淘汰");
        assertEquals("pending", statusOf("peer-exp-c-001"));
        assertEquals("pending", statusOf("peer-exp-c-100"));
        assertEquals(DbHeldPeerStore.MAX_PENDING_PER_RECEIVER, store.list(TARGET).size(),
            "淘汰后 pending 恰好保留上限条数");

        List<MessageRecord> notices = notices(SOURCE);
        assertEquals(1, notices.size(), "淘汰只通知被淘汰的那一条");
        assertTrue(notices.get(0).getStructuredOutput().contains("queue-full"));
    }

    // ───────────────────── d. 拒绝 → 通知 + 去重 ─────────────────────

    @Test
    @DisplayName("d. 拒绝 → rejected 且发送方收到通知；同一 messageId 不重复通知")
    void rejectNotifiesOnce() {
        store.hold(held("peer-exp-d-1", 1, SOURCE));

        assertTrue(router.rejectHeld("peer-exp-d-1", principal(TARGET)));

        assertEquals("rejected", statusOf("peer-exp-d-1"));
        List<MessageRecord> notices = notices(SOURCE);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).getStructuredOutput().contains("user-declined"));

        // 同一 messageId 再通知一次：进程内去重 + 落库 id 幂等 ⇒ 仍只有一行。
        boolean second = failureNotifier.notifyFailure(new PeerFailureNotifier.FailureNotice(
            "peer-exp-d-1", SOURCE, TARGET, "Target session",
            PeerFailureNotifier.REASON_USER_DECLINED));
        assertFalse(second, "同一 messageId 不得重复通知");
        assertEquals(1, notices(SOURCE).size());

        // 再次拒绝也不产生新通知（行已是终态）。
        assertFalse(router.rejectHeld("peer-exp-d-1", principal(TARGET)));
        assertEquals(1, notices(SOURCE).size());
    }

    // ───────────────────── e. 批准不产生 expired 通知 ─────────────────────

    @Test
    @DisplayName("e. 批准 → 立即 accepted（批准即结算），不产生 expired 通知")
    void approveDoesNotExpire() {
        store.hold(held("peer-exp-e-1", 1, SOURCE));

        PeerMessageRouter.Receipt receipt =
            router.approveHeld("peer-exp-e-1", principal(TARGET));

        assertEquals(PeerMessageRouter.Status.ACCEPTED, receipt.status(), receipt.detail());
        assertEquals("accepted", statusOf("peer-exp-e-1"),
            "批准即结算：⛔ 不再有 approved 中间态");
        assertTrue(notices(SOURCE).isEmpty(), "批准不得产生任何失败/过期通知");
        assertNull(heldMapper.selectByMessageId("peer-exp-e-1").getPendingExpiresAt(),
            "未展示过的行不应有到期时刻");
    }

    // ──────────── f. 不写 messages 表 + pending 不进模型上下文 ────────────

    @Test
    @DisplayName("f. hold 不写 messages 表；pending 不进模型上下文；收件列表重启后仍读回")
    void holdStaysOutOfMessagesAndModelContext() {
        store.hold(held("peer-exp-f-1", 1, SOURCE));

        assertTrue(notices(TARGET).isEmpty(), "hold 不得把待确认消息写进接收方 messages 表");
        assertTrue(notices(SOURCE).isEmpty(), "hold 本身不是失败，不得提前通知发送方");
        assertFalse(ids(messageService.listForModel(TARGET)).contains("peer-exp-f-1"),
            "pending 不得进入模型上下文");
        assertFalse(ids(messageService.listForResume(TARGET)).contains("peer-exp-f-1"),
            "pending 不得进入恢复上下文");

        // 重启（新 store 实例）：pending 仍能从待收件列表读回（持久化真源 = peer_held_message）。
        DbHeldPeerStore restarted = new DbHeldPeerStore(heldMapper);
        List<PeerMessageRouter.HeldView> views = restarted.listViews(TARGET);
        assertEquals(1, views.size());
        assertEquals("pending", views.get(0).status());
        assertEquals("peer-exp-f-1", views.get(0).messageId());
    }

    // ───────────────────── g. 发送方已删除 ─────────────────────

    @Test
    @DisplayName("g. 发送方会话已删除 → 不写通知，但结算照常成功")
    void deletedSenderGetsNoNoticeButSettlementWorks() {
        store.hold(held("peer-exp-g-1", 1, DELETED_SOURCE));

        int expired = store.expireAll(TARGET, PeerFailureNotifier.REASON_RECEIVER_CLOSED);

        assertEquals(1, expired, "结算不能因为发送方被删而失败");
        assertEquals("expired", statusOf("peer-exp-g-1"));
        assertTrue(notices(DELETED_SOURCE).isEmpty(), "发送方已删除 ⇒ 不写通知");
    }

    // ───────────────────────── helpers ─────────────────────────

    private static PeerMessageRouter.HeldMessage held(String messageId, int order, String source) {
        PeerEnvelope envelope = new PeerEnvelope(
            messageId,
            "source [aaaaaa]",
            source,
            "Source",
            "dontAsk",
            List.of(PeerEnvelope.hopToken(source)),
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

    private static String statusOf(String messageId) {
        var record = heldMapper.selectByMessageId(messageId);
        return record == null ? null : record.getStatus();
    }

    private static List<MessageRecord> notices(String sessionId) {
        return messageMapper.selectListByQuery(
            com.mybatisflex.core.query.QueryWrapper.create().eq("session_id", sessionId));
    }

    private static java.util.Set<String> ids(List<com.nexusai.model.session.dto.ChatMessageDto> messages) {
        return messages.stream().filter(m -> m != null && m.id() != null)
            .map(com.nexusai.model.session.dto.ChatMessageDto::id)
            .collect(java.util.stream.Collectors.toSet());
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
        List<String> ids = List.of(TARGET, SOURCE, DELETED_SOURCE);
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

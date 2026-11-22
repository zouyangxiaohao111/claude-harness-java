package com.nexusai.application.agent.messaging;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接收侧准入丢弃 → 发送方失败通知（<b>真实临时 SQLite</b>）。
 *
 * <p><b>WHY 必须真 SQLite</b>：判据是「发送方会话里真的多了一行 meta 通知，且
 * structured_output.peer_failure.reason = CC 的原因串」——假 MessageService 验不出落库形状
 * 与 id 幂等。
 *
 * <p><b>CC 语义</b>（{@code claude.exe} 偏移 325454500 / 324034215）：接收侧准入在<b>入队前</b>
 * 判定，发送方先拿到成功 + message_id，随后异步 {@code dropped} 回执告知原因
 * （{@code rate-limited} / {@code duplicate} / {@code queue-full}）。本仓把该回执落成发送方
 * 会话里的一条 meta 通知（{@link PeerFailureNotifier}），离线/重启后仍可见。
 */
@DisplayName("[rlim] 准入丢弃 → 发送方收到落库的失败通知（rate-limited / duplicate / queue-full）")
class PeerAdmissionDropNotifyTest {

    private static final String SOURCE = "sess-rlim-src";
    private static final String TARGET = "sess-rlim-tgt";

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private static final class MutableClock implements LongSupplier {
        private long now = 1_700_000_000_000L;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private MessageService messageService;
    private NotificationQueue queue;
    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private PeerRuntimeAdapter adapter;
    private PeerFailureNotifier notifier;
    private MutableClock clock;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "rlim-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("rlim.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration")
                .toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds,
            MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        com.mybatisflex.core.MybatisFlexBootstrap bootstrap =
            com.mybatisflex.core.MybatisFlexBootstrap.getInstance();
        messageMapper = bootstrap.getMapper(MessageMapper.class);
        sessionMapper = bootstrap.getMapper(SessionMapper.class);
        toolCallMapper = bootstrap.getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanRows();
        insertSession(SOURCE, "Sender");
        insertSession(TARGET, "Target");

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        clock = new MutableClock();
        queue = new NotificationQueue();
        registry = new PeerSessionRegistry();
        registry.clear();
        registry.register(new PeerSessionRegistry.Registration(
            SOURCE, "Sender", null, PermissionMode.DEFAULT, 0L));
        registry.register(new PeerSessionRegistry.Registration(
            TARGET, "Target", null, PermissionMode.DEFAULT, 0L));

        policy = new PeerInboundPolicy();

        notifier = new PeerFailureNotifier();
        notifier.setMessageService(messageService);

        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "heldPeerStore", new PeerMessageRouter.InMemoryHeldPeerStore());
        ReflectionTestUtils.setField(adapter, "peerFailureNotifier", notifier);
        ReflectionTestUtils.invokeMethod(adapter, "wire");
        adapter.setAdmissionClock(clock);

        policy.setSettingsSource(sessionId -> "accept");

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        router.setHeldStore(new PeerMessageRouter.InMemoryHeldPeerStore());
        router.setDeliveryPort(adapter);
        router.setFailureNotifier(notifier);
    }

    private PeerMessageRouter.SendRequest request(String body) {
        return new PeerMessageRouter.SendRequest(
            TARGET, -1L, "Target", SOURCE, "Sender", "Sender [aaaaaa]",
            body, "summary", false, false, List.of());
    }

    private static PeerEnvelope envelope(String messageId) {
        return PeerEnvelope.create(messageId, "Sender [aaaaaa]", SOURCE, "Sender",
            PermissionMode.DEFAULT, List.of(), 0L, "summary", false);
    }

    @Test
    @DisplayName("a. 31 条连续发送：第 31 条 dropped(rate-limited)，发送方会话落一条失败通知")
    void thirtyFirstSendDropsAndNotifiesRateLimited() {
        PeerMessageRouter.Receipt last = null;
        for (int i = 1; i <= 30; i++) {
            PeerMessageRouter.Receipt receipt = router.send(request("body-" + i),
                PermissionMode.DEFAULT, "peer-rlim-" + i);
            assertEquals(PeerMessageRouter.Status.ACCEPTED, receipt.status(),
                "第 " + i + " 条必须入队，实际=" + receipt.status() + " detail=" + receipt.detail());
        }
        last = router.send(request("body-31"), PermissionMode.DEFAULT, "peer-rlim-31");
        assertEquals(PeerMessageRouter.Status.DROPPED, last.status(),
            "第 31 条必须被接收侧准入丢弃");
        assertEquals(PeerRateLimits.textOf(PeerRateLimits.Reason.RATE_LIMITED), last.detail());
        assertEquals(30, queue.countPeerQueued(TARGET), "被丢弃的新消息不得入队");

        ChatMessageDto notice = noticeOf("peer-failure-peer-rlim-31");
        assertNotNull(notice, "发送方会话必须收到一条失败通知（CC 异步 dropped 回执）");
        assertTrue(notice.isMeta(), "通知是 meta 行（UI 隐藏、模型可见）");
        assertEquals(SOURCE, notice.sessionId());
        assertTrue(notice.content().contains(
                "sender exceeded the peer message rate limit"),
            "正文必须含 CC 原文原因，实际=" + notice.content());
        assertEquals("rate-limited", failureReason(notice));
        assertEquals("peer-rlim-31", failureMessageId(notice));
    }

    @Test
    @DisplayName("b. 30s 内同文重复：第 2 条 dropped(duplicate)，发送方收到 duplicate 通知")
    void identicalBodyDropsAndNotifiesDuplicate() {
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(request("same body"), PermissionMode.DEFAULT, "peer-dup-1").status());
        PeerMessageRouter.Receipt second =
            router.send(request("same body"), PermissionMode.DEFAULT, "peer-dup-2");
        assertEquals(PeerMessageRouter.Status.DROPPED, second.status());
        assertEquals(PeerRateLimits.textOf(PeerRateLimits.Reason.DUPLICATE), second.detail());

        ChatMessageDto notice = noticeOf("peer-failure-peer-dup-2");
        assertNotNull(notice);
        assertEquals("duplicate", failureReason(notice));
        assertTrue(notice.content().contains("identical to the previous message from this sender"),
            "实际=" + notice.content());
        assertEquals(1, queue.countPeerQueued(TARGET), "重复消息不得入队");

        // 窗口外（时钟推进 30s）同文可再发。
        clock.advance(30_000);
        assertEquals(PeerMessageRouter.Status.ACCEPTED,
            router.send(request("same body"), PermissionMode.DEFAULT, "peer-dup-3").status());
    }

    @Test
    @DisplayName("c. 队列已排 50 条：新消息被拒(queue-full)、旧的仍在")
    void queueFullRejectsNewKeepsOld() {
        for (int i = 0; i < PeerRateLimits.MAX_QUEUED_PEER_MESSAGES; i++) {
            String messageId = "peer-pre-" + i;
            boolean enqueued = queue.enqueuePeer(new NotificationQueue.QueueItem(
                "prefill-" + i, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
                null, messageId, true, PeerRuntimeAdapter.WORKLOAD_PEER_MESSAGE, true,
                new NotificationQueue.MessageOrigin("peer", null),
                TARGET, null, null, null, null, null, null, envelope(messageId)),
                PeerRateLimits.MAX_QUEUED_PEER_MESSAGES);
            assertTrue(enqueued, "预填第 " + i + " 条必须成功");
        }
        assertEquals(PeerRateLimits.MAX_QUEUED_PEER_MESSAGES, queue.countPeerQueued(TARGET));

        PeerMessageRouter.Receipt rejected =
            router.send(request("overflow body"), PermissionMode.DEFAULT, "peer-overflow");
        assertEquals(PeerMessageRouter.Status.DROPPED, rejected.status());
        assertEquals(PeerRateLimits.textOf(PeerRateLimits.Reason.QUEUE_FULL), rejected.detail());
        assertEquals(PeerRateLimits.MAX_QUEUED_PEER_MESSAGES, queue.countPeerQueued(TARGET),
            "拒新不淘汰旧：队列仍是 50");
        assertTrue(queue.peek(item -> "peer-pre-0".equals(item.uuid())).isPresent(),
            "最早的旧项必须仍在队列（不是淘汰最旧）");

        ChatMessageDto notice = noticeOf("peer-failure-peer-overflow");
        assertNotNull(notice);
        assertEquals("queue-full", failureReason(notice));
        assertTrue(notice.content().contains(
                "this session has too many undelivered peer messages queued"),
            "实际=" + notice.content());
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 按落库 id 找发送方会话里的通知行。 */
    private ChatMessageDto noticeOf(String noticeId) {
        return messageService.listRawForTranscript(SOURCE).stream()
            .filter(m -> noticeId.equals(m.id()))
            .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String failureReason(ChatMessageDto notice) {
        Map<String, Object> out = notice.structuredOutput();
        assertNotNull(out, "通知必须带 structured_output.peer_failure");
        Object failure = out.get("peer_failure");
        assertNotNull(failure, "缺 peer_failure 键，实际=" + out);
        return String.valueOf(((Map<String, Object>) failure).get("reason"));
    }

    @SuppressWarnings("unchecked")
    private static String failureMessageId(ChatMessageDto notice) {
        Map<String, Object> out = notice.structuredOutput();
        Object failure = out == null ? null : out.get("peer_failure");
        return failure == null ? null : String.valueOf(((Map<String, Object>) failure).get("messageId"));
    }

    private void insertSession(String id, String title) {
        if (sessionMapper.selectOneById(id) != null) {
            return;
        }
        SessionRecord record = new SessionRecord();
        record.setId(id);
        record.setModelTag("DS");
        record.setModelName("test-model");
        record.setTitle(title);
        record.setTime("刚刚");
        record.setSessionGroup("default");
        sessionMapper.insertSelective(record);
        SessionProjectRoot.setForSession(id,
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
    }

    private void cleanRows() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", SOURCE));
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", TARGET));
        sessionMapper.deleteById(SOURCE);
        sessionMapper.deleteById(TARGET);
        SessionProjectRoot.reset();
    }
}

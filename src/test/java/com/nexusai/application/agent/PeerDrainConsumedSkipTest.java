package com.nexusai.application.agent;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.messaging.DbHeldPeerStore;
import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerRuntimeAdapter;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.repository.peer.mapper.PeerHeldMessageMapper;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [idle-then-drain 去重 · 2026-11-20 E2E 第三轮缺陷] 空闲路径消费后，运行中 drain
 * <b>不得再判策略、不得产生 ERROR、不得重复落库</b>；未被消费的消息仍正常走 drain。
 *
 * <h2>缺陷链路（本测试的前置事实）</h2>
 * <pre>
 *   CronIdleExecutor 空闲消费 peer 项：落库 → markConsumed（held 行 accepted）→ 起轮
 *   → LlmAgentLoop.doRun 把同一条消息重建为本回合首条 prompt 重新入队
 *     （PeerExecutionOrigin 不含一次性批准标记 ⇒ 重入队信封 preApproved=false）
 *   → turn-0 drain 再按策略复核：hold 设置下判 HOLD
 *   → holdRevalidated 命中 accepted 终态行 → 误报 ERROR + 消息进不了本轮上下文
 * </pre>
 *
 * <h2>修复判据</h2>
 * drain 侧先查<b>权威落库状态</b>（held 行 accepted）⇒ 跳过策略复核与 markConsumed，
 * 仍按同一投影 append（同 id，落库 11 参重载按 id 幂等）。
 *
 * <h2>RED tooth（变异自检）</h2>
 * 去掉 {@code LlmAgentLoop.injectPeerQueuedItem} 里的
 * {@code adapter.isMessageConsumed(...)} 跳过分支 ⇒ {@link #idleConsumedMessageIsNotReJudged()}
 * 变红（state 为空 / 落库 0 行 / 适配器 ERROR 日志）。
 */
@DisplayName("[idle-then-drain] 空闲消费后 drain 不得重判策略（真 sqlite）")
class PeerDrainConsumedSkipTest {

    private static final String TARGET = "sess-skip0001";
    private static final String SOURCE = "sess-skip0002";

    static {
        System.out.println("[ddf] LlmAgentLoop 来源 = "
            + LlmAgentLoop.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[ddf] PeerRuntimeAdapter 来源 = "
            + PeerRuntimeAdapter.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("[ddf] DbHeldPeerStore 来源 = "
            + DbHeldPeerStore.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static SQLiteDataSource ds;
    private static PeerHeldMessageMapper heldMapper;
    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private PeerSessionRegistry registry;
    private PeerInboundPolicy policy;
    private NotificationQueue queue;
    private PeerMessageRouter router;
    private PeerRuntimeAdapter adapter;
    private DbHeldPeerStore heldStore;
    private MessageService messageService;
    private ChatService chatService;
    private AtomicReference<String> setting;
    private long targetEpoch;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest");
        Files.createDirectories(dir);
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + MybatisFlexDbTestSupport.sharedDbPath().toAbsolutePath()
            + "?journal_mode=WAL&busy_timeout=5000");
        ds.setBusyTimeout(10_000);
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration")
                .toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, PeerHeldMessageMapper.class,
            MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        heldMapper = MybatisFlexBootstrap.getInstance().getMapper(PeerHeldMessageMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        deleteAllHeldRows();
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

        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(registry);
        router.setPeerInboundPolicy(policy);
        heldStore = new DbHeldPeerStore(heldMapper);
        router.setHeldStore(heldStore);

        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "heldStore", heldStore);
        router.setDeliveryPort(adapter);

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);
        ReflectionTestUtils.setField(chatService, "notificationQueue", new NotificationQueue());
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", TARGET));
        sessionMapper.deleteById(TARGET);
        com.nexusai.eventbus.ws.StreamEventSeq.reset(TARGET);
    }

    // ───────────────────── ① 主缺陷：空闲已消费 ⇒ drain 不重判 ─────────────────────

    @Test
    @DisplayName("空闲路径消费落库后，drain 见同一 messageId：不判策略 / 无 ERROR / 不重复落库 / 仍进本轮上下文")
    void idleConsumedMessageIsNotReJudged() {
        ensureSession();
        String messageId = "peer-skip-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "已由空闲路径消费的正文";

        // 前置：hold 设置 → 发送进 held → 用户批准 → 入队（带一次性批准标记）。
        String heldId = holdOne(body);
        assertThat(router.approveHeld(heldId, principal(TARGET)).status())
            .isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        NotificationQueue.QueueItem idleItem = queue.dequeue().orElseThrow(
            () -> new AssertionError("approve 成功后必须入队一条 peer 项"));
        assertThat(idleItem.peerEnvelope().preApproved())
            .as("前置：这是已批准消息（preApproved=true）").isTrue();
        String approvedId = idleItem.peerEnvelope().messageId();

        // 模拟 CronIdleExecutor.handlePeerQueuedItem 的消费顺序：先落库，再 markConsumed。
        PeerExecutionOrigin origin = PeerRuntimeAdapter.originOf(idleItem.peerEnvelope());
        messageService.createQueuedUserMessage(TARGET, approvedId, body, OffsetDateTime.now(),
            true /* isMeta */, "peer", null, null,
            origin.sourceSessionId(), "agent_message",
            PeerRuntimeAdapter.structuredOutput(origin, idleItem.peerEnvelope().summary()));
        adapter.markConsumed(idleItem.peerEnvelope());
        assertThat(heldStatus(approvedId)).as("前置：消费落地 → accepted").isEqualTo("accepted");
        assertThat(messageRowCount(approvedId)).as("前置：空闲路径已落库一行").isEqualTo(1L);

        // 复现 doRun 重新入队的信封形态：由 PeerExecutionOrigin 重建 ⇒ 丢失批准标记。
        PeerEnvelope rebuilt = origin.toEnvelope(null, false);
        assertThat(rebuilt.preApproved()).as("缺陷前提：重入队信封没有批准标记").isFalse();
        NotificationQueue.QueueItem drainItem = peerItem(approvedId, body, rebuilt);

        // 探针：捕获适配器日志（ERROR 是该缺陷的可观测症状）。
        ch.qos.logback.classic.Logger adapterLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PeerRuntimeAdapter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        adapterLogger.addAppender(app);
        AgentState state = new AgentState("sys", TARGET, com.nexusai.application.agent.subagent.AgentContext.createAgentId());
        chatService.armRealTimePersist(state, TARGET,
            "/topic/sessions/" + TARGET + "/stream", null, approvedId);
        List<AgentState.InjectedQueuedMessage> injected = new ArrayList<>();
        boolean consumed;
        try {
            consumed = LlmAgentLoop.injectPeerQueuedItem(state, drainItem, injected, adapter);
        } finally {
            adapterLogger.detachAppender(app);
        }

        assertThat(consumed).as("已消费项仍要投影进本轮（否则模型看不到这条消息）").isTrue();
        assertThat(state.rawMessages()).as("同一 messageId 只注入一次").hasSize(1);
        assertThat(state.rawMessages().get(0).id()).isEqualTo(approvedId);
        assertThat(injected).hasSize(1);

        assertThat(heldStatus(approvedId)).as("不得新建/回退 held 行").isEqualTo("accepted");
        assertThat(heldRowCount(approvedId)).as("held 行恒一行").isEqualTo(1L);
        assertThat(messageRowCount(approvedId)).as("不得重复落库（同 id 恒一行）").isEqualTo(1L);

        String errors = app.list.stream()
            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .collect(Collectors.joining(" | "));
        assertThat(errors).as("已消费项不得触发策略误判 ERROR").isEmpty();
    }

    // ───────────────────── ② 未被消费 ⇒ 仍正常走 drain ─────────────────────

    @Test
    @DisplayName("未被消费的 peer 项（直投）仍按策略复核并正常注入")
    void unconsumedMessageStillGoesThroughDrain() {
        ensureSession();
        setting.set("accept");
        String messageId = "peer-plain-" + UUID.randomUUID().toString().substring(0, 8);
        PeerMessageRouter.Receipt sent = router.send(new PeerMessageRouter.SendRequest(
            TARGET, targetEpoch, "Target", SOURCE, "Source", "Source [plain]",
            "直投正文", "summary", false, false, List.of()), PermissionMode.DEFAULT);
        assertThat(sent.status()).isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow(
            () -> new AssertionError("直投必须入队一条 peer 项"));
        assertThat(adapter.isMessageConsumed(item.peerEnvelope().messageId()))
            .as("直投没有 held 行 ⇒ 未消费").isFalse();

        AgentState state = new AgentState("sys", TARGET, com.nexusai.application.agent.subagent.AgentContext.createAgentId());
        chatService.armRealTimePersist(state, TARGET,
            "/topic/sessions/" + TARGET + "/stream", null, messageId);
        boolean consumed = LlmAgentLoop.injectPeerQueuedItem(
            state, item, new ArrayList<>(), adapter);

        assertThat(consumed).as("未消费项照常消费").isTrue();
        assertThat(state.rawMessages()).hasSize(1);
        assertThat(state.rawMessages().get(0).id()).isEqualTo(item.peerEnvelope().messageId());
        assertThat(messageRowCount(item.peerEnvelope().messageId())).isEqualTo(1L);
    }

    // ───────────────────── ③ 批准失败即终态（严格 CC 语义） ─────────────────────

    @Test
    @DisplayName("批准时投递失败 ⇒ 行直接终态 expired，不可再次批准/补投")
    void approvalFailureIsTerminal() {
        ensureSession();
        String messageId = holdOne("批准失败的正文");
        // 断掉投递端口 → 投递 NOT_WIRED ⇒ 立即结算（⛔ 不再回 pending/approved）。
        ReflectionTestUtils.setField(adapter, "notificationQueue", null);
        assertThat(router.approveHeld(messageId, principal(TARGET)).status())
            .isEqualTo(PeerMessageRouter.Status.NOT_WIRED);
        assertThat(heldStatus(messageId)).isEqualTo("expired");
        assertThat(adapter.isMessageConsumed(messageId)).as("未投递 ≠ 已消费").isFalse();

        ReflectionTestUtils.setField(adapter, "notificationQueue", queue);
        assertThat(router.approveHeld(messageId, principal(TARGET)).status())
            .as("失败即终态：不能重试批准").isEqualTo(PeerMessageRouter.Status.APPROVAL_REJECTED);
        assertThat(queue.size()).as("不得补投").isZero();
    }

    // ───────────────────────── helpers ─────────────────────────

    private void ensureSession() {
        SessionRecord s = new SessionRecord();
        s.setId(TARGET);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("peer drain consumed skip");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        if (sessionMapper.selectOneById(TARGET) == null) {
            sessionMapper.insertSelective(s);
        }
    }

    private String holdOne(String body) {
        PeerMessageRouter.Receipt receipt = router.send(new PeerMessageRouter.SendRequest(
            TARGET, targetEpoch, "Target", SOURCE, "Source", "Source [skip]",
            body, "summary", false, false, List.of()), PermissionMode.DEFAULT);
        assertThat(receipt.status()).as("前置：hold 设置 → 入 hold")
            .isEqualTo(PeerMessageRouter.Status.HELD);
        return receipt.messageId();
    }

    /** drain 侧队列项（生产同构：uuid=messageId + mode=prompt + workload=peer-message + 信封）。 */
    private static NotificationQueue.QueueItem peerItem(String messageId, String body,
                                                        PeerEnvelope envelope) {
        return new NotificationQueue.QueueItem(
            body, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null /* agentId=null：主线程 */, messageId, true /* isMeta */, "peer-message",
            true /* skipSlashCommands */, new NotificationQueue.MessageOrigin("peer", null),
            TARGET, null, null, null, null, null, null /* notifyChats */, envelope);
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId) {
        return new Principal(sessionId);
    }

    private static void deleteAllHeldRows() {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM peer_held_message")) {
            ps.executeUpdate();
        } catch (Exception e) {
            throw new AssertionError("deleteAllHeldRows failed", e);
        }
    }

    private static String heldStatus(String messageId) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM peer_held_message WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new AssertionError("heldStatus failed", e);
        }
    }

    private static long heldRowCount(String messageId) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM peer_held_message WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new AssertionError("heldRowCount failed", e);
        }
    }

    private static long messageRowCount(String messageId) {
        return messageMapper.selectCountByQuery(QueryWrapper.create().eq("id", messageId));
    }

    private record Principal(String sessionId) implements PeerMessageRouter.ApprovalPrincipal {
        @Override
        public String requestId() {
            return "req-drain-consumed-skip";
        }

        @Override
        public boolean trustedUi() {
            return true;
        }
    }
}

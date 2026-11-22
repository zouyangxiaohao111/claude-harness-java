package com.nexusai.domain.session;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [held-state V326] 待确认（hold）来信持久化进 messages 表 · 真实临时 SQLite + 全量 Flyway。
 *
 * <p><b>WHY 必须真 SQLite</b>：判据是「条件 UPDATE 的 affected rows / 重启后新实例读回 /
 * 模型面过滤后的真实数组」，内存假实现验不出（无法证明「同 id 原地更新、不插第二行」）。
 *
 * <p>覆盖（对应派单 a–e）：
 * <ol>
 *   <li>a. hold 后 messages 有一行且 held_state=pending，GET（listRawForTranscript / getById）可见；</li>
 *   <li>b. 模型上下文构建（listForResumeExcluding / listForResume / listForModel）排除 pending 与 declined；</li>
 *   <li>c. 批准后<b>同一行</b>转 delivered（同 id 幂等，仍只有一行）；</li>
 *   <li>d. 拒绝后转 declined、行保留、不进入模型上下文；</li>
 *   <li>e. 新建 MessageService 实例（模拟重启）后三种状态都能读回。</li>
 * </ol>
 */
@DisplayName("[held-state V326] 待确认来信进 messages 表（真 sqlite）")
class HeldPeerMessagePersistenceTest {

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private String session;
    private String source;
    private MessageService messageService;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "held-msg-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("held.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        // 全量迁移：直接读源目录（真文件，非编译产物），保证含最新 V326。
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration")
                .toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() {
        session = "sess-held-" + UUID.randomUUID().toString().substring(0, 8).replace('-', '0');
        source = "sess-from-" + UUID.randomUUID().toString().substring(0, 8).replace('-', '1');
        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("held message target");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);
        // 生产会话都绑定项目根（CwdResolution.getCwd 对「有会话却解析不出」fail-loud）；
        // 测试用 java.io.tmpdir 真实目录绑定，避免 CwdResolution 抛 UnresolvedProjectRootException。
        SessionProjectRoot.setForSession(session,
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        messageService = newMessageService();
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
        SessionProjectRoot.reset();
    }

    /** 新建 MessageService 实例（模拟进程重启：所有内存分配器/缓存归零，DB 为唯一真源）。 */
    private MessageService newMessageService() {
        MessageService svc = new MessageService();
        ReflectionTestUtils.setField(svc, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(svc, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(svc, "toolCallMapper", toolCallMapper);
        return svc;
    }

    // ───────────────────────── a. hold 写入 + GET 可见 ─────────────────────────

    @Test
    @DisplayName("a. hold 后 messages 有一行 held_state=pending，GET 可见且形状与投递行一致")
    void holdWritesPendingRow() {
        String messageId = "peer-held-a-1";
        assertTrue(messageService.insertHeldPeerMessage(
            session, messageId, "please review the diff", source, peerSource(messageId), null));

        // GET 通道（listRawForTranscript = ChatController GET /messages 的取数方法）
        List<ChatMessageDto> all = messageService.listRawForTranscript(session);
        ChatMessageDto row = all.stream().filter(m -> messageId.equals(m.id())).findFirst().orElse(null);
        assertNotNull(row, "GET 必须能取回 hold 行");
        assertEquals(MessageService.HELD_STATE_PENDING, row.heldState());
        assertEquals(Role.user, row.role());
        assertEquals(source, row.author());
        assertEquals("agent_message", row.subtype());
        assertEquals("peer", row.queuedOrigin());
        assertTrue(row.isMeta());
        assertEquals("please review the diff", row.content());
        assertNotNull(row.structuredOutput(), "pending 行必须带 peer_source，前端才能渲染成 peer 卡片");

        // getById 单条通道同样带状态
        assertEquals(MessageService.HELD_STATE_PENDING, messageService.getById(messageId).heldState());

        // 幂等：同 id 再 hold 一次不产生第二行、不覆盖
        assertFalse(messageService.insertHeldPeerMessage(
            session, messageId, "changed body", source, peerSource(messageId), null));
        assertEquals(1, countRows(messageId));
        assertEquals("please review the diff", messageService.getById(messageId).content());
    }

    // ───────────────────── b. 模型上下文排除 pending / declined ─────────────────────

    @Test
    @DisplayName("b. 模型上下文（listForResumeExcluding / listForResume / listForModel）排除 pending 与 declined")
    void modelContextExcludesPendingAndDeclined() {
        // 一条普通历史消息（必须在上下文里）
        String normalId = appendNormalUserMessage("normal question");
        // pending + declined 两条 held 行（都不得进上下文）
        String pendingId = "peer-held-b-pending";
        String declinedId = "peer-held-b-declined";
        assertTrue(messageService.insertHeldPeerMessage(
            session, pendingId, "pending body", source, peerSource(pendingId), null));
        assertTrue(messageService.insertHeldPeerMessage(
            session, declinedId, "declined body", source, peerSource(declinedId), null));
        assertTrue(messageService.markHeldDeclined(session, declinedId));

        // 真实模型上下文构建方法（run 起始注入块消费的重载）
        List<ChatMessageDto> resume = messageService.listForResumeExcluding(session, null);
        Set<String> resumeIds = ids(resume);
        assertTrue(resumeIds.contains(normalId), "普通消息必须进入上下文");
        assertFalse(resumeIds.contains(pendingId), "pending 不得进入模型上下文");
        assertFalse(resumeIds.contains(declinedId), "declined 不得进入模型上下文");

        // 恢复漏斗（ChatController background / PartialCompactService 消费）
        Set<String> resumeBySession = ids(messageService.listForResume(session));
        assertTrue(resumeBySession.contains(normalId));
        assertFalse(resumeBySession.contains(pendingId));
        assertFalse(resumeBySession.contains(declinedId));

        // DB → 模型视图通道（KB 线消费）
        Set<String> modelView = ids(messageService.listForModel(session));
        assertTrue(modelView.contains(normalId));
        assertFalse(modelView.contains(pendingId));
        assertFalse(modelView.contains(declinedId));

        // 展示通道不受影响：pending / declined 仍在会话记录里
        Set<String> rawIds = ids(messageService.listRawForTranscript(session));
        assertTrue(rawIds.contains(pendingId));
        assertTrue(rawIds.contains(declinedId));
    }

    // ───────────────────── c. 批准 → 同一行 delivered（不插第二行）─────────────────────

    @Test
    @DisplayName("c. 批准后同一行转 delivered（同 id 幂等、只有一行）且进入模型上下文")
    void approveUpgradesSameRowToDelivered() {
        String messageId = "peer-held-c-1";
        assertTrue(messageService.insertHeldPeerMessage(
            session, messageId, "approved body", source, peerSource(messageId), null));
        assertEquals(1, countRows(messageId));

        // 真实投递落库出口（CronIdleExecutor.handlePeerQueuedItem / ChatService peer 分支消费的 11 参重载）
        messageService.createQueuedUserMessage(session, messageId, "approved body",
            null, true /* isMeta */, "peer", null, null,
            source /* author */, "agent_message", peerSource(messageId));

        assertEquals(1, countRows(messageId), "批准只更新同一行，不得插入第二行");
        assertEquals(1, countSessionRows(), "批准后会话内仍只有这一条 agent_message（⛔ 不新插一行）");
        ChatMessageDto row = messageService.getById(messageId);
        assertEquals(MessageService.HELD_STATE_DELIVERED, row.heldState());
        assertEquals("approved body", row.content());

        // 二次投递（turn-0 drain 同 id 再来一次）仍是一行、状态不回退
        messageService.createQueuedUserMessage(session, messageId, "approved body",
            null, true, "peer", null, null, source, "agent_message", peerSource(messageId));
        assertEquals(1, countRows(messageId));
        assertEquals(MessageService.HELD_STATE_DELIVERED, messageService.getById(messageId).heldState());

        // delivered 进入模型上下文
        assertTrue(ids(messageService.listForResumeExcluding(session, null)).contains(messageId));
    }

    // ───────────────────── d. 拒绝 → declined 且不进上下文 ─────────────────────

    @Test
    @DisplayName("d. 拒绝后转 declined（行保留、可渲染）且不进入模型上下文")
    void declineMarksRowAndExcludesFromContext() {
        String messageId = "peer-held-d-1";
        assertTrue(messageService.insertHeldPeerMessage(
            session, messageId, "declined body", source, peerSource(messageId), null));
        assertTrue(messageService.markHeldDeclined(session, messageId));
        // 幂等：再次拒绝不改（本次不是 pending）
        assertFalse(messageService.markHeldDeclined(session, messageId));

        assertEquals(1, countRows(messageId));
        ChatMessageDto row = messageService.getById(messageId);
        assertEquals(MessageService.HELD_STATE_DECLINED, row.heldState());
        assertEquals("declined body", row.content(), "拒绝后正文保留，供「已拒绝」卡片渲染");
        assertFalse(ids(messageService.listForResumeExcluding(session, null)).contains(messageId));

        // 已拒绝的行不得被投递链改成 delivered（条件 UPDATE 只认 pending）
        messageService.createQueuedUserMessage(session, messageId, "declined body",
            null, true, "peer", null, null, source, "agent_message", peerSource(messageId));
        assertEquals(MessageService.HELD_STATE_DECLINED, messageService.getById(messageId).heldState());
    }

    // ───────────────────── e. 重启（新实例）三态可读回 ─────────────────────

    @Test
    @DisplayName("e. 新建 MessageService 实例（模拟重启）后 pending/delivered/declined 三态都能读回")
    void restartReadsAllThreeStates() {
        String pendingId = "peer-held-e-pending";
        String deliveredId = "peer-held-e-delivered";
        String declinedId = "peer-held-e-declined";
        assertTrue(messageService.insertHeldPeerMessage(
            session, pendingId, "p1", source, peerSource(pendingId), null));
        assertTrue(messageService.insertHeldPeerMessage(
            session, deliveredId, "p2", source, peerSource(deliveredId), null));
        assertTrue(messageService.insertHeldPeerMessage(
            session, declinedId, "p3", source, peerSource(declinedId), null));
        messageService.markHeldDelivered(session, deliveredId);
        messageService.markHeldDeclined(session, declinedId);

        // 模拟重启：新实例（内存分配器/缓存全空，DB 为唯一真源）
        MessageService restarted = newMessageService();
        List<ChatMessageDto> all = restarted.listRawForTranscript(session);
        Map<String, String> stateById = all.stream()
            .filter(m -> m.id() != null && m.id().startsWith("peer-held-e-"))
            .collect(Collectors.toMap(ChatMessageDto::id, m -> String.valueOf(m.heldState())));
        assertEquals(MessageService.HELD_STATE_PENDING, stateById.get(pendingId));
        assertEquals(MessageService.HELD_STATE_DELIVERED, stateById.get(deliveredId));
        assertEquals(MessageService.HELD_STATE_DECLINED, stateById.get(declinedId));

        // 重启后模型上下文仍只放 delivered
        Set<String> ctx = ids(restarted.listForResumeExcluding(session, null));
        assertFalse(ctx.contains(pendingId));
        assertTrue(ctx.contains(deliveredId));
        assertFalse(ctx.contains(declinedId));
    }

    // ───────────────────────── helpers ─────────────────────────

    private String appendNormalUserMessage(String content) {
        ChatMessageDto dto = new ChatMessageDto(
            "msg-normal-" + UUID.randomUUID().toString().substring(0, 8), session, Role.user,
            "user", content, null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of(), null, false, false);
        ChatMessageDto saved = messageService.appendMessage(dto);
        assertNotNull(saved);
        return saved.id();
    }

    private Map<String, Object> peerSource(String messageId) {
        Map<String, Object> peerSource = new LinkedHashMap<>();
        peerSource.put("sourceSessionId", source);
        peerSource.put("sourceTitle", "来源会话");
        peerSource.put("sourceKind", "peer");
        peerSource.put("messageId", messageId);
        peerSource.put("fromMode", "default");
        Map<String, Object> so = new LinkedHashMap<>();
        so.put("peer_source", peerSource);
        so.put("agent_summary", "请确认");
        return so;
    }

    private int countRows(String messageId) {
        return messageMapper.selectListByQuery(
            QueryWrapper.create().eq("id", messageId)).size();
    }

    /** 本会话消息总行数（用于证明「批准后没有新插一行」）。 */
    private int countSessionRows() {
        return messageMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", session)).size();
    }

    private static Set<String> ids(List<ChatMessageDto> messages) {
        assertNotNull(messages);
        return messages.stream().filter(m -> m != null && m.id() != null)
            .map(ChatMessageDto::id).collect(Collectors.toSet());
    }
}

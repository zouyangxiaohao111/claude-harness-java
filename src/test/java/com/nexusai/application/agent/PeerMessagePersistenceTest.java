package com.nexusai.application.agent;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.eventbus.ws.MessageInsertEvent;
import com.nexusai.model.session.dto.ChatMessageDto;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [sendmessage-cross-session · B2] peer 行<b>真实落库</b>与 {@code message.insert} 同源契约。
 *
 * <p>取证点（真 SQLite + 全量 Flyway，⛔ 不 mock mapper）：
 * <ol>
 *   <li>落库列真实写入：{@code author=来源 sessionId}、{@code subtype=agent_message}、
 *       {@code queued_origin=peer}、{@code is_meta=true}、{@code structured_output.peer_source}，
 *       {@code content} 是<b>原文</b>（壳不落库）；</li>
 *   <li>{@code message.insert} 的完整行与 <b>GET</b>（{@code getById}/{@code listRawForTranscript}）
 *       同 id / 同 author / 同 subtype / 同 structuredOutput / 同时间；</li>
 *   <li>轮末补落幂等（同 id 恒一行）；</li>
 *   <li>落库失败 ⇒ <b>不推送成功行</b>（不得把失败伪装成成功）。</li>
 * </ol>
 *
 * <p><b>RED tooth（变异 ②）</b>：把 {@code MessageService.insertQueuedUserMessage} 的
 * {@code m.setSubtype(...)} / {@code m.setStructuredOutput(...)} 删掉 ⇒ 用例 1 的 subtype /
 * peer_source 断言红；把 {@code ChatService} peer 分支退回 8 参重载 ⇒ author/subtype 断言红。
 */
@DisplayName("[B2] peer 行落库 + message.insert 与 GET 同源（真 sqlite）")
class PeerMessagePersistenceTest {

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private MessageService messageService;
    private ChatService chatService;
    private SimpMessagingTemplate ws;
    private String session;
    private String topic;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "peer-persist-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("flex.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        // 全量迁移：直接读源目录（真文件，非编译产物），保证含最新 V3xx 迁移。
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
        session = "sess-peer-" + UUID.randomUUID().toString().substring(0, 8).replace('-', '0');
        topic = "/topic/sessions/" + session + "/stream";

        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("peer persist target");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);
        ReflectionTestUtils.setField(chatService, "notificationQueue", new NotificationQueue());

        ws = Mockito.mock(SimpMessagingTemplate.class);
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
        StreamEventSeqReset.reset(session);
    }

    @Test
    @DisplayName("落库真实写 author/subtype/structured_output；message.insert 完整行与 GET 同 id 同来源同 subtype")
    void peerRowPersistedAndEventMatchesGet() {
        String messageId = "peer-persist-1";
        PeerExecutionOrigin origin = originOf(messageId);

        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin, "peer summary");
        ChatMessageDto dto = LlmAgentLoop.peerMessageDto(
            messageId, "hello peer raw", origin.sourceSessionId(), so);

        AgentState state = new AgentState("sys", session, null);
        state.addInjectedQueuedMessage(messageId, "hello peer raw", "peer", null, true,
            origin.sourceSessionId(), "agent_message", so);
        chatService.armRealTimePersist(state, session, topic, ws, messageId);
        state.appendMessage(dto);

        // ── 落库列真实写入 ──
        MessageRecord row = messageMapper.selectOneById(messageId);
        assertThat(row).as("peer 行必须真实落库").isNotNull();
        assertThat(row.getSessionId()).isEqualTo(session);
        assertThat(row.getRole()).isEqualTo("user");
        assertThat(row.getAuthor()).as("author = 来源 sessionId").isEqualTo(origin.sourceSessionId());
        assertThat(row.getSubtype()).as("subtype = agent_message").isEqualTo("agent_message");
        assertThat(row.getQueuedOrigin()).as("queued_origin = peer").isEqualTo("peer");
        assertThat(Boolean.TRUE.equals(row.getIsMeta())).as("is_meta=true（UI 隐藏、模型可见）").isTrue();
        assertThat(row.getContent()).as("content = 原文（壳不落库）").isEqualTo("hello peer raw");
        assertThat(row.getStructuredOutput()).as("structured_output 落 V6 列").contains("peer_source");

        // ── message.insert 完整行 == GET 同源 ──
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(eq(topic), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(MessageInsertEvent.class);
        MessageInsertEvent evt = (MessageInsertEvent) payload.getValue();
        assertThat(evt.getMessages()).hasSize(1);
        ChatMessageDto fromEvent = evt.getMessages().get(0);
        ChatMessageDto fromGet = messageService.getById(messageId);

        assertThat(fromEvent.id()).as("同 id").isEqualTo(fromGet.id());
        assertThat(fromEvent.author()).as("同 author").isEqualTo(fromGet.author())
            .isEqualTo(origin.sourceSessionId());
        assertThat(fromEvent.subtype()).as("同 subtype").isEqualTo(fromGet.subtype())
            .isEqualTo("agent_message");
        assertThat(fromEvent.queuedOrigin()).isEqualTo("peer");
        assertThat(fromEvent.isMeta()).isTrue();
        assertThat(fromEvent.content()).isEqualTo("hello peer raw");
        assertThat(fromEvent.structuredOutput()).as("同 structuredOutput（peer_source 投影）")
            .isEqualTo(fromGet.structuredOutput());
        assertThat(fromEvent.time()).as("同时间").isEqualTo(fromGet.time());

        // GET 列表同源
        ChatMessageDto listed = messageService.listRawForTranscript(session).stream()
            .filter(m -> messageId.equals(m.id())).findFirst().orElse(null);
        assertThat(listed).as("GET /messages 同 id 可见").isNotNull();
        assertThat(listed.author()).isEqualTo(origin.sourceSessionId());
        assertThat(listed.subtype()).isEqualTo("agent_message");

        // ── 轮末补落幂等：同 id 恒一行 ──
        ReflectionTestUtils.invokeMethod(chatService, "persistInjectedQueuedMessages", state, session);
        assertThat(messageMapper.selectCountByQuery(QueryWrapper.create().eq("id", messageId)))
            .as("补落不得产生第二行（同 id 幂等）").isEqualTo(1L);
        // 事件不重复：首次推送后补落不再推（existsById 判定）
        verify(ws, Mockito.times(1)).convertAndSend(eq(topic), any(Object.class));
    }

    @Test
    @DisplayName("落库失败（会话不存在）⇒ 不推送成功行、DB 无行")
    void failedPersistDoesNotPushSuccessRow() {
        String missing = "sess-missing-" + UUID.randomUUID().toString().substring(0, 8).replace('-', '1');
        String messageId = "peer-persist-missing";
        PeerExecutionOrigin origin = originOf(messageId);
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin, null);
        ChatMessageDto dto = LlmAgentLoop.peerMessageDto(
            messageId, "raw", origin.sourceSessionId(), so);

        AgentState state = new AgentState("sys", missing, null);
        state.addInjectedQueuedMessage(messageId, "raw", "peer", null, true,
            origin.sourceSessionId(), "agent_message", so);
        String missingTopic = "/topic/sessions/" + missing + "/stream";
        chatService.armRealTimePersist(state, missing, missingTopic, ws, messageId);
        state.appendMessage(dto);

        assertThat(messageMapper.selectOneById(messageId)).as("落库失败 ⇒ DB 无行").isNull();
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
    }

    private static PeerExecutionOrigin originOf(String messageId) {
        String source = "sess-src" + UUID.randomUUID().toString().substring(0, 8).replace('-', '2');
        PeerEnvelope envelope = PeerEnvelope.create(messageId, "alpha [abcdef]", source, "alpha",
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            List.of(), 7L, "peer summary", false);
        return PeerExecutionOrigin.fromEnvelope(envelope, "alpha", PeerExecutionOrigin.SOURCE_KIND_PEER);
    }

    /** 静态序号表清理（防跨用例 sessionId 复用时的 seq 断言干扰）。 */
    static final class StreamEventSeqReset {
        private StreamEventSeqReset() {}

        static void reset(String sessionId) {
            com.nexusai.eventbus.ws.StreamEventSeq.reset(sessionId);
        }
    }
}

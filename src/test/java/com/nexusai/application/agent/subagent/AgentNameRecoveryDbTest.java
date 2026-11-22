package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.session.MessageService;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
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
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重启后按名恢复子代理：名字来自<b>主会话 DB 消息</b>的 tool_use / tool_result 配对
 * （{@link AgentNameRecovery}），不依赖子代理 sidecar meta.name。
 *
 * <h2>WHY</h2>
 * <p>历史子代理 meta 只有 agentType，没有 name ⇒ 旧重建逻辑直接跳过，重启后按名不可寻址。
 * CC 的名字真源是主会话 transcript 里的 Agent/Task 调用链：assistant tool_use（入参 name）+
 * tool_result（{@code status:"async_launched", agentId}）。本组用例用<b>真实临时 SQLite
 * （Flyway 迁移）</b>锁死配对规则、优先级与回落，以及「已删除会话不复活」。
 */
@DisplayName("[name-recovery] 从主会话 DB tool_use/tool_result 配对恢复子代理名字")
class AgentNameRecoveryDbTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEX_1 = "a1234567890abcdef";
    /** 故意排序在 HEX_1 之后（文件名序 ≠ 消息序）⇒ 判别「以最后一次 spawn 为准」。 */
    private static final String HEX_2 = "a999999999999999";

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    @TempDir
    Path tempDir;

    private String session;
    private MessageService messageService;
    private AgentNameRegistry registry;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "name-recovery-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("flex.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds,
            MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() {
        session = "sess-nrd-" + UUID.randomUUID().toString().substring(0, 8);
        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("name recovery");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        registry = new AgentNameRegistry();
        registry.setSessionDirResolver(x -> tempDir);
        registry.setMessageService(messageService);
    }

    @AfterEach
    void tearDown() {
        com.nexusai.application.agent.agent.SessionCwdHolder.reset();
        if (session == null) {
            return;
        }
        List<MessageRecord> rows = messageMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", session));
        for (MessageRecord r : rows) {
            toolCallMapper.deleteByQuery(QueryWrapper.create().eq("message_id", r.getId()));
        }
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private static Map<String, Object> row(String role, String content) {
        Map<String, Object> row = new HashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private void writeTranscript(String agentId) {
        AgentTranscript.recordSidechainTranscript(tempDir, session, agentId,
            new ArrayList<>(List.of(row("assistant", "finished earlier"))), null);
    }

    private void writeMetaOnly(String agentId) throws Exception {
        AgentTranscript.writeMetadata(tempDir, session, agentId,
            new AgentTranscript.AgentMetadata("general-purpose", null, "work", null));
    }

    private void writeMetaWithName(String agentId, String name) throws Exception {
        writeMetaOnly(agentId);
        AgentTranscript.writeMetaName(tempDir, session, agentId, name);
    }

    /** 生产同构：assistant 消息 + tool_calls 行（tool_use 入参 JSON）。 */
    private void insertToolUse(String toolUseId, String name, String subagentType, long seq) {
        String messageId = "msg-use-" + toolUseId;
        MessageRecord rec = new MessageRecord();
        rec.setId(messageId);
        rec.setSessionId(session);
        rec.setRole("assistant");
        rec.setContent("");
        rec.setFinishReason("tool_calls");
        rec.setCreatedAt(OffsetDateTime.now().toString());
        rec.setSeq(seq);
        messageMapper.insert(rec);

        ObjectNode args = JSON.createObjectNode();
        args.put("description", "d");
        args.put("prompt", "p");
        if (subagentType != null) {
            args.put("subagent_type", subagentType);
        }
        if (name != null) {
            args.put("name", name);
        }
        ToolCallRecord tc = new ToolCallRecord();
        tc.setId(toolUseId);
        tc.setMessageId(messageId);
        tc.setToolName("Agent");
        tc.setArguments(args.toString());
        tc.setIsError(false);
        tc.setCreatedAt(OffsetDateTime.now().toString());
        toolCallMapper.insert(tc);
    }

    /** 生产同构：role=tool 消息（tool_result）+ 回写 tool_calls.result。 */
    private void insertToolResult(String toolUseId, String status, String agentId, long seq) {
        ObjectNode data = JSON.createObjectNode();
        data.put("status", status);
        if (agentId != null) {
            data.put("agentId", agentId);
        }
        data.put("description", "d");
        String content = data.toString();

        MessageRecord rec = new MessageRecord();
        rec.setId("msg-res-" + toolUseId);
        rec.setSessionId(session);
        rec.setRole("tool");
        rec.setContent(content);
        rec.setToolCallId(toolUseId);
        rec.setCreatedAt(OffsetDateTime.now().toString());
        rec.setSeq(seq);
        messageMapper.insert(rec);

        ToolCallRecord tc = toolCallMapper.selectOneById(toolUseId);
        if (tc != null) {
            tc.setResult(content);
            toolCallMapper.update(tc);
        }
    }

    private void insertAsyncPair(String toolUseId, String name, String agentId, long toolUseSeq) {
        insertToolUse(toolUseId, name, "general-purpose", toolUseSeq);
        insertToolResult(toolUseId, "async_launched", agentId, toolUseSeq + 1);
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private AgentNameRecovery.Result recover() {
        return AgentNameRecovery.recover(messageService::listRawForTranscript, session);
    }

    // ───────────────────────── a. DB 配对优先于 meta.name ─────────────────────────

    @Test
    @DisplayName("a) tool_use(name=researcher)+tool_result(agentId) → 重建后按 researcher 可寻址（优先于 meta.name）")
    void rebuildAddressesByDbPairingOverMetaName() throws Exception {
        writeTranscript(HEX_1);
        writeMetaWithName(HEX_1, "oldname");
        insertAsyncPair("toolu-a", "researcher", HEX_1, 1L);

        AgentNameRecovery.Result recovered = recover();
        assertThat(recovered.pairs()).isEqualTo(1);
        assertThat(recovered.nameOf(HEX_1)).isEqualTo("researcher");

        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(session);

        assertThat(result.nameFromDb()).isEqualTo(1);
        assertThat(result.nameFromMeta()).isZero();
        assertThat(result.rebuilt()).isEqualTo(1);
        AgentNameRegistry.AgentNameEntry entry = registry.resolveEntry(session, "researcher");
        assertThat(entry).isNotNull();
        assertThat(entry.agentId()).isEqualTo(HEX_1);
        assertThat(registry.resolveEntry(session, "oldname")).as("DB 配对优先，meta 旧名不可寻址").isNull();
        assertThat(registry.size()).as("⛔ 不注册为活跃").isZero();
        assertThat(registry.retiredEntryById(HEX_1).name()).isEqualTo("researcher");
    }

    // ───────────────────────── b. 无 tool_result 不产生映射 ─────────────────────────

    @Test
    @DisplayName("b) 只有 tool_use 没有 tool_result → 不产生映射（回落 meta.name）")
    void toolUseWithoutResultProducesNoMapping() throws Exception {
        writeTranscript(HEX_1);
        writeMetaWithName(HEX_1, "fallback");
        insertToolUse("toolu-b", "researcher", "general-purpose", 1L);

        AgentNameRecovery.Result recovered = recover();
        assertThat(recovered.pairs()).isZero();
        assertThat(recovered.toolUses()).isEqualTo(1);
        assertThat(recovered.unpairedToolUses()).isEqualTo(1);
        assertThat(recovered.nameOf(HEX_1)).isNull();

        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(session);
        assertThat(result.nameFromDb()).isZero();
        assertThat(result.nameFromMeta()).isEqualTo(1);
        assertThat(registry.retiredEntryById(HEX_1).name()).isEqualTo("fallback");
        assertThat(registry.resolveEntry(session, "researcher")).isNull();
    }

    // ───────────────────────── c. 同名两次 → 最后一次为准 ─────────────────────────

    @Test
    @DisplayName("c) 同名两次 spawn 不同 agentId → 以最后一次为准（记录 dup），两个 agent 都在退役表")
    void sameNameTwiceLatestSpawnWins() throws Exception {
        writeTranscript(HEX_1);
        writeTranscript(HEX_2);
        // meta 名字故意与 DB 不同，证明赢家来自 DB 且按消息时序而非文件序
        writeMetaWithName(HEX_1, "meta-first");
        writeMetaWithName(HEX_2, "meta-second");

        insertAsyncPair("toolu-c1", "researcher", HEX_2, 1L); // 先 spawn（文件名序更大）
        insertAsyncPair("toolu-c2", "researcher", HEX_1, 3L); // 后 spawn（文件名序更小）

        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(session);

        assertThat(result.nameFromDb()).isEqualTo(2);
        assertThat(result.rebuilt()).isEqualTo(2);
        assertThat(registry.retiredSize()).isEqualTo(2);
        assertThat(registry.resolveEntry(session, "researcher").agentId())
            .as("同名以最后一次 spawn 为准（消息时序，不按 meta 文件序）").isEqualTo(HEX_1);
        assertThat(registry.retiredEntryById(HEX_2).name()).isEqualTo("researcher");
        assertThat(registry.retiredEntryById(HEX_1).name()).isEqualTo("researcher");
        assertThat(registry.resolveEntry(session, "meta-first")).isNull();
        assertThat(registry.resolveEntry(session, "meta-second")).isNull();
    }

    @Test
    @DisplayName("c2) 同一 agentId 多次出现（不同 name）→ 以最后一次为准并记录 dup")
    void sameAgentIdLatestPairWins() throws Exception {
        writeTranscript(HEX_1);
        writeMetaOnly(HEX_1); // 无 meta.name ⇒ 名字只能来自 DB

        insertAsyncPair("toolu-c3", "first", HEX_1, 1L);
        insertAsyncPair("toolu-c4", "second", HEX_1, 3L);

        AgentNameRecovery.Result recovered = recover();
        assertThat(recovered.pairs()).isEqualTo(1);
        assertThat(recovered.duplicateAgentIds()).isEqualTo(1);
        assertThat(recovered.nameOf(HEX_1)).isEqualTo("second");

        registry.rebuildRetiredFromTranscripts(session);
        assertThat(registry.retiredEntryById(HEX_1).name()).isEqualTo("second");
        assertThat(registry.resolveEntry(session, "second")).isNotNull();
        assertThat(registry.resolveEntry(session, "first")).isNull();
    }

    // ───────────────────────── d. meta 无 name，DB 有配对 ─────────────────────────

    @Test
    @DisplayName("d) meta 无 name 但 DB 有配对 → 仍能恢复（覆盖历史数据）")
    void metaWithoutNameRecoveredFromDb() throws Exception {
        writeTranscript(HEX_1);
        writeMetaOnly(HEX_1);
        insertAsyncPair("toolu-d", "researcher", HEX_1, 1L);

        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(session);

        assertThat(result.nameFromDb()).isEqualTo(1);
        assertThat(result.skippedNoName()).isZero();
        assertThat(result.rebuilt()).isEqualTo(1);
        assertThat(registry.resolveEntry(session, "researcher")).isNotNull();
        assertThat(registry.retiredEntryById(HEX_1).name()).isEqualTo("researcher");
    }

    // ───────────────────────── e. 配对缺失回落 meta.name ─────────────────────────

    @Test
    @DisplayName("e) tool_result 非 async_launched（同步完成）→ 不产生映射，回落 meta.name")
    void syncResultDoesNotMapAndFallsBackToMetaName() throws Exception {
        writeTranscript(HEX_1);
        writeMetaWithName(HEX_1, "metaname");
        insertToolUse("toolu-e", "researcher", "general-purpose", 1L);
        insertToolResult("toolu-e", "completed", HEX_1, 2L);

        AgentNameRecovery.Result recovered = recover();
        assertThat(recovered.pairs()).isZero();
        assertThat(recovered.unpairedResults()).isEqualTo(1);

        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(session);
        assertThat(result.nameFromDb()).isZero();
        assertThat(result.nameFromMeta()).isEqualTo(1);
        assertThat(registry.retiredEntryById(HEX_1).name()).isEqualTo("metaname");
        assertThat(registry.resolveEntry(session, "researcher")).isNull();
    }

    // ───────────────────────── f. 已删除会话不重建 ─────────────────────────

    @Test
    @DisplayName("f) 会话已删除（<sessionId>/ 目录不存在）→ 不重建、不复活（即使 DB 有配对）")
    void deletedSessionIsNotRebuilt() throws Exception {
        writeTranscript(HEX_1);
        writeMetaOnly(HEX_1);
        insertAsyncPair("toolu-f", "researcher", HEX_1, 1L);
        deleteRecursively(tempDir.resolve(session));

        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(session);

        assertThat(result).isEqualTo(RetiredAgentRebuilder.ScanResult.EMPTY);
        assertThat(registry.retiredSize()).isZero();
        assertThat(registry.resolveEntry(session, "researcher")).isNull();
    }
}

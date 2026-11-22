package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.messaging.PeerRefPins;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.SessionService;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ListAgents · Peer sessions 目录 = <b>DB 会话全集 ∪ 内存注册表</b>（真实临时 SQLite + Flyway）。
 *
 * <p><b>WHY 必须真 SQLite</b>：本类钉住的判据是「注册表为空（重启后）时 DB 里的会话仍出现」，
 * 以及「只有 DB 记录的行不得标成 running」—— 前者需要一条能验证「记录存在」的查询，后者需要
 * 「有 endpoint / 没有 endpoint」两种真实输入；mock 掉 mapper 会让「列选择 / 是否带消息」这类
 * SQL 层事实无法被检出。
 *
 * <p>覆盖（对应用户裁定 a..f）：a 重启后 DB 会话仍列出；b 有 endpoint=running、仅 DB=idle；
 * c 已删除不出现；d 排除自身；e 与 Subagents 跨节去重；f >100 行截断并写 note；
 * g DB 超过 200 行只取最近 200（updated_at 倒序）+ peer_sessions_capped note；
 * h 取满 200 后展示层仍按 100 行截断；i 200 以内不受上限影响。
 */
@DisplayName("ListAgents · Peer 段 DB∪内存目录")
class ListAgentsPeerDirectoryTest {

    private static final String SELF = "sess-aaaaaaa1";
    private static final String LIVE = "sess-bbbbbbb2";
    private static final String LIVE_IDLE = "sess-bbbbbbb3";
    private static final String DB_ONLY = "sess-ccccccc3";
    private static final String GONE = "sess-ddddddd4";

    private static SQLiteDataSource ds;
    private static SessionService sessionService;

    private PeerSessionRegistry sessions;
    private AgentNameRegistry registry;
    private PeerRefPins pins;
    private ListAgentsTool tool;

    @BeforeAll
    static void initDatabase() throws Exception {
        Path dir = Files.createTempDirectory("listagents-db-");
        Path db = dir.resolve("peer-dir.db");
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?journal_mode=WAL&busy_timeout=5000");
        // 只读 src/main/resources（不覆盖共享 target/classes）。
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:src/main/resources/db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexBootstrap.getInstance().addMapper(SessionMapper.class);
        MybatisFlexBootstrap.getInstance().setDataSource(ds).start();
        SessionMapper mapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        sessionService = new SessionService();
        Field field = SessionService.class.getDeclaredField("sessionMapper");
        field.setAccessible(true);
        field.set(sessionService, mapper);
    }

    @BeforeEach
    void setUp() throws Exception {
        deleteAllSessions();
        // ToolUseContext 构造期经 CwdResolution 解析会话项目根（fail-loud）：合成会话须显式绑定
        // 一个真实存在的目录，否则纯 JUnit 环境（回源器未接线）会抛 UnresolvedProjectRootException。
        SessionProjectRoot.setForSession(SELF, System.getProperty("user.dir"));
        sessions = new PeerSessionRegistry();
        registry = new AgentNameRegistry();
        pins = new PeerRefPins();
        tool = new ListAgentsTool();
        tool.setPeerSessionRegistry(sessions);
        tool.setAgentNameRegistry(registry);
        tool.setTeamHelpers(new TeamHelpers());
        tool.setRefPins(pins);
        tool.setSessionService(sessionService);
    }

    @AfterEach
    void tearDown() {
        com.nexusai.application.agent.tasks.TaskSystemConfig.clearForTest();
    }

    // ───────────────────────── a ─────────────────────────

    @Test
    @DisplayName("a：注册表为空（模拟重启）时，DB 里的会话仍出现在 Peer sessions")
    void dbSessionsListedWhenRegistryEmpty() {
        insertSession(DB_ONLY, "beta peer", "2026-11-20T10:00:00+08:00");
        insertSession(GONE, "gamma peer", "2026-11-20T09:00:00+08:00");
        sessions.clear();

        String listing = listing();
        assertThat(listing).contains("Peer sessions");
        assertThat(listing).contains("beta-peer [");
        assertThat(listing).contains("gamma-peer [");
    }

    // ───────────────────────── b ─────────────────────────

    @Test
    @DisplayName("b：有 endpoint 的会话按运行态标 running/idle，只有 DB 记录的标 idle（不伪造在线）")
    void liveEndpointRunningDbOnlyIdle() {
        insertSession(LIVE, "live peer", "2026-11-20T10:00:00+08:00");
        insertSession(LIVE_IDLE, "idle peer", "2026-11-20T10:00:00+08:00");
        insertSession(DB_ONLY, "sleeping peer", "2026-11-20T09:00:00+08:00");
        sessions.register(new PeerSessionRegistry.Registration(
            LIVE, "live peer", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            LIVE_IDLE, "idle peer", "/p", PermissionMode.DEFAULT, 1L));
        // 生产里由 PeerRuntimeAdapter 安装 StatusSource；未装 = UNKNOWN ⇒ 这里显式给两种运行态。
        sessions.setStatusSource(sessionId -> LIVE.equals(sessionId)
            ? PeerSessionRegistry.Status.RUNNING : PeerSessionRegistry.Status.IDLE);

        String peers = section(listing(), "Peer sessions", null);
        assertThat(peers).containsPattern(
            "live-peer \\[[0-9a-f]+]  \u00b7  session  \u00b7  running");
        assertThat(peers).containsPattern(
            "idle-peer \\[[0-9a-f]+]  \u00b7  session  \u00b7  idle");
        assertThat(peers).containsPattern(
            "sleeping-peer \\[[0-9a-f]+]  \u00b7  session  \u00b7  idle");
    }

    // ───────────────────────── c ─────────────────────────

    @Test
    @DisplayName("c：已删除会话（DB 行已删）不出现在列表")
    void deletedSessionNotListed() throws Exception {
        insertSession(GONE, "deleted peer", "2026-11-20T10:00:00+08:00");
        deleteSession(GONE);

        assertThat(listing()).doesNotContain("deleted-peer");
    }

    // ───────────────────────── d ─────────────────────────

    @Test
    @DisplayName("d：当前会话自身不出现（DB 行与 endpoint 两种来源都排除）")
    void selfExcluded() {
        insertSession(SELF, "self peer", "2026-11-20T10:00:00+08:00");
        sessions.register(new PeerSessionRegistry.Registration(
            SELF, "self peer", "/p", PermissionMode.DEFAULT, 1L));
        insertSession(DB_ONLY, "other peer", "2026-11-20T09:00:00+08:00");

        String listing = listing();
        assertThat(listing).doesNotContain("self-peer");
        assertThat(listing).contains("other-peer [");
    }

    // ───────────────────────── e ─────────────────────────

    @Test
    @DisplayName("e：DB∪内存按 sessionId 去重；同名不同实体不被误合并，Subagents/Teammates 去重不回归")
    void dedupeAcrossSourcesAndSections() {
        // ① 同一 session 既在 DB 又在注册表 → 只列一行（联合去重）。
        insertSession(LIVE, "live peer", "2026-11-20T10:00:00+08:00");
        sessions.register(new PeerSessionRegistry.Registration(
            LIVE, "live peer", "/p", PermissionMode.DEFAULT, 1L));
        // ② 同名不同实体（teammate 与 DB 会话）不得被伪造去重：两行各自出现、ref 不同；
        //    同时 Subagents 仍按既有规则剔除 teammate（B4 语义不因并上 DB 而变）。
        insertSession(DB_ONLY, "alice", "2026-11-20T09:00:00+08:00");
        registry.register(SELF, "alice", "alice@team");

        Map<String, Object> data = data();
        assertThat(peerRowIds(data)).as("DB 与注册表同一 session 只列一行（另一个 DB 会话独立成行）")
            .containsExactlyInAnyOrder(LIVE, DB_ONLY);
        assertThat(sectionRows(data, "teammates")).extracting(r -> r.get("name"))
            .containsExactly("alice");
        assertThat(sectionRows(data, "peer_sessions")).extracting(r -> r.get("name"))
            .containsExactlyInAnyOrder("live-peer", "alice");
        assertThat(sectionRows(data, "subagents")).isEmpty();
        // 不同实体不得共用地址：teammate alice 与 peer session alice 的 ref 必须不同。
        assertThat(refOf(data, "teammates", "alice"))
            .isNotEqualTo(refOf(data, "peer_sessions", "alice"));
    }

    // ───────────────────────── f ─────────────────────────

    @Test
    @DisplayName("f：DB 行超过 100 时仍按每节 100 行截断并写 note")
    void moreThanHundredDbRowsTruncated() {
        for (int i = 0; i < 120; i++) {
            insertSession(String.format("sess-cc%06x", i), "dbpeer-" + i,
                String.format("2026-11-20T10:%02d:%02d+08:00", i / 60, i % 60));
        }

        Map<String, Object> data = data();
        String listing = (String) data.get("listing");
        assertThat(listing).contains("more not shown");
        assertThat(listing.length()).isLessThanOrEqualTo(10_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notes = (List<Map<String, Object>>) data.get("notes");
        assertThat(notes).extracting(n -> n.get("kind")).contains("list_truncated");
        assertThat(pins.snapshot(SELF)).hasSize(100);
    }

    // ───────────────────────── g ─────────────────────────

    @Test
    @DisplayName("g：DB 超过 200 行时只取最近 200 个（updated_at 倒序）并写 peer_sessions_capped note")
    void dbRowsCappedAtTwoHundredNewestFirst() {
        for (int i = 0; i < 250; i++) {
            insertSession(bulkId(i), "bulk-" + i, bulkTime(i));
        }

        List<SessionRecord> summaries = sessionService.listPeerSessionSummaries();
        assertThat(summaries).hasSize(SessionService.PEER_SESSION_SUMMARY_LIMIT);
        assertThat(summaries.get(0).getId()).as("最新一行排最前").isEqualTo(bulkId(249));
        assertThat(summaries.get(199).getId()).as("第 200 行 = 倒数第 200 新").isEqualTo(bulkId(50));
        assertThat(summaries).extracting(SessionRecord::getId)
            .as("更旧的行未被读入").doesNotContain(bulkId(0), bulkId(49));

        assertThat(noteKinds()).contains("peer_sessions_capped");
    }

    // ───────────────────────── h ─────────────────────────

    @Test
    @DisplayName("h：DB 取满 200 行后展示层仍按 100 行截断并写 list_truncated note")
    void cappedRowsStillTruncatedForDisplay() {
        for (int i = 0; i < 250; i++) {
            insertSession(bulkId(i), "bulk-" + i, bulkTime(i));
        }

        Map<String, Object> data = data();
        String listing = (String) data.get("listing");
        assertThat(listing).contains("more not shown");
        assertThat(listing.length()).isLessThanOrEqualTo(10_000);
        assertThat(noteKinds()).contains("list_truncated", "peer_sessions_capped");
        assertThat(pins.snapshot(SELF)).hasSize(100);
    }

    // ───────────────────────── i ─────────────────────────

    @Test
    @DisplayName("i：200 以内不受上限影响（全部列出，不写 peer_sessions_capped）")
    void underCapUnaffected() {
        for (int i = 0; i < 60; i++) {
            insertSession(bulkId(i), "bulk-" + i, bulkTime(i));
        }

        assertThat(sessionService.listPeerSessionSummaries()).hasSize(60);
        Map<String, Object> data = data();
        assertThat(sectionRows(data, "peer_sessions")).hasSize(60);
        assertThat(noteKinds()).doesNotContain("peer_sessions_capped");
        String listing = (String) data.get("listing");
        assertThat(listing).contains("bulk-59 [", "bulk-0 [");
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String bulkId(int i) {
        return String.format("sess-cc%06x", i);
    }

    /** 每条唯一且互不相同的 ISO 时刻（i 越大越新）。 */
    private static String bulkTime(int i) {
        return String.format("2026-11-20T10:%02d:%02d+08:00", i / 60, i % 60);
    }

    @SuppressWarnings("unchecked")
    private List<String> noteKinds() {
        List<Map<String, Object>> notes = (List<Map<String, Object>>) data().get("notes");
        return notes.stream().map(n -> (String) n.get("kind")).toList();
    }

    private static ToolUseContext tuc() {
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SELF, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data() {
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(),
            ListAgentsTool.NAME, JsonNodeFactory.instance.objectNode());
        return (Map<String, Object>) ((ToolResult<?>) tool.execute(call, tuc())).data();
    }

    private String listing() {
        return (String) data().get("listing");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String section(String listing, String startTitle, String nextTitle) {
        int a = listing.indexOf(startTitle);
        if (a < 0) {
            return "";
        }
        int b = nextTitle == null ? -1 : listing.indexOf(nextTitle, a);
        return b < 0 ? listing.substring(a) : listing.substring(a, b);
    }

    /** 结构化 sections 里某一节的行（kind 小写：subagents / teammates / peer_sessions）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sectionRows(Map<String, Object> data, String kind) {
        Object sections = data.get("sections");
        if (!(sections instanceof List<?> list)) {
            return List.of();
        }
        for (Object item : list) {
            Map<String, Object> section = (Map<String, Object>) item;
            if (kind.equals(section.get("kind"))) {
                Object rows = section.get("rows");
                return rows instanceof List<?> r ? (List<Map<String, Object>>) r : List.of();
            }
        }
        return List.of();
    }

    private static List<String> peerRowIds(Map<String, Object> data) {
        List<String> ids = new java.util.ArrayList<>();
        for (Map<String, Object> row : sectionRows(data, "peer_sessions")) {
            ids.add((String) row.get("id"));
        }
        return ids;
    }

    private static String refOf(Map<String, Object> data, String kind, String name) {
        for (Map<String, Object> row : sectionRows(data, kind)) {
            if (name.equals(row.get("name"))) {
                return (String) row.get("ref");
            }
        }
        return null;
    }

    /** 最小可落库会话行（sessions 表 NOT NULL 列齐全）。 */
    private static void insertSession(String id, String title, String updatedAt) {
        String sql = "INSERT INTO sessions (id, model_tag, model_name, title, time,"
            + " session_group, message_count, created_at, updated_at)"
            + " VALUES (?, 'tag', 'model', ?, 'time', 'group', 0, ?, ?)";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, title);
            ps.setString(3, updatedAt);
            ps.setString(4, updatedAt);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("insert session failed: " + id, e);
        }
    }

    private static void deleteSession(String id) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("delete session failed: " + id, e);
        }
    }

    private static void deleteAllSessions() {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM sessions");
        } catch (Exception e) {
            throw new IllegalStateException("clear sessions failed", e);
        }
    }
}

package com.nexusai.domain.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [seq NULL 兜底 · 真引擎] 读侧排序片段 {@link MessageService#SEQ_ASC_NULLS_LAST_ORDER} /
 * {@link MessageService#SEQ_DESC_NULLS_LAST_ORDER} 在 <b>真 SQLite</b>（生产同一驱动 sqlite-jdbc 3.46）
 * 上的<b>排序后果</b>与<b>查询计划</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 + 「真源」纪律）</b>：本类钉两件在 mock 层看不到的事：
 * <ol>
 *   <li><b>NULL 的落地位置</b>——NULL 在 SQL 里的排序位置是实现定义细节（SQLite 视 NULL 为最小 →
 *       裸 ASC 排最前、裸 DESC 排最后）。「NULL 不冒充真实位置」的整个论证依赖这一点；</li>
 *   <li><b>查询计划不退化</b>——旧写法 {@code ORDER BY seq IS NULL, seq <方向>} 的 {@code seq IS NULL}
 *       是<b>表达式、不是索引列</b> → SQLite 必须额外做一次全量临时排序
 *       （{@code USE TEMP B-TREE FOR ORDER BY}），把 {@code idx_messages_session_seq} 的免排序收益
 *       全部废掉；分页那条更亏（本可 {@code ORDER BY seq DESC LIMIT 51} 只取 51 行即停，加前缀后
 *       要先全量排序再取 51 行）。新写法 {@code seq <方向> NULLS LAST} 的排序键仍是索引列
 *       → 只有 index SEARCH、无 TEMP B-TREE。<b>本类的核心断言就是「无 TEMP B-TREE」</b>：
 *       它是「不许废索引」这条意图的唯一守卫。</li>
 * </ol>
 *
 * <p><b>契约边界（有意不钉）</b>：多个 NULL 行<b>之间</b>的相对顺序不属契约（SQLite 不保证、业务也不
 * 依赖）——本类只断言「非 NULL 段逐位正确 + NULL 全部落在该侧末尾」，用
 * {@link #assertNullsLastOrder} / {@link #assertNullsFirstOrder} 表达，避免把实现细节钉成契约。
 *
 * <p><b>测试与生产同源</b>：SQL 片段直接用 {@link MessageService} 的两个 public 常量拼装
 * （不是在测试里另写一份字面量），保证「测试断言的口径」不会与「生产发出的片段」漂移。
 * 唯一的例外是<被淘汰的旧写法>与<反例>，它们刻意写字面量并注明来由。
 *
 * <p><b>RED 条件（变异验证，两条都实测过）</b>：
 * <ul>
 *   <li>把常量改回 {@code "seq IS NULL, seq ASC"}（表达式前置键）→
 *       {@code orderByNullsLastKeepsIndexUsableWithoutTempBTree} 红（TEMP B-TREE 回来了）；</li>
 *   <li>把 {@code NULLS LAST} 去掉（改成裸 {@code "seq ASC"}）→ {@code nullPlacement...} 红
 *       （NULL 冒到最前）。</li>
 * </ul>
 */
@DisplayName("[seq NULL 兜底·真 SQLite] ORDER BY seq <方向> NULLS LAST 的实测排序 + 查询计划")
class MessageSeqNullOrderSqlTest {

    /** 生产会话键（与 V70 索引列同名同形）。 */
    private static final String SESSION = "s1";

    /** 另一个会话（验证排序片段不串会话）。 */
    private static final String OTHER_SESSION = "s2";

    /** 被本批淘汰的旧写法（表达式前置键）——刻意写字面量：它已不是生产常量，此处作对照固化为反例。 */
    private static final String REJECTED_EXPRESSION_PREFIX = "seq IS NULL, seq ASC";

    /** 被否决策略：把 NULL 顶成「最新」（DESC 结果最前），生产禁止使用。 */
    private static final String REJECTED_NULLS_FIRST = "seq DESC NULLS FIRST";

    /** NULL 行 id 前缀（尾段断言用：只校验「是 NULL 行」，不校验 NULL 之间的序）。 */
    private static final String NULL_ROW_PREFIX = "dirty";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ASC：修前 NULL 排最前（缺陷）→ 修后 NULL 排最后；DESC：NULL 落末尾（绝不冒充最新）")
    void nullPlacementInAscAndDescOnRealSqlite() throws Exception {
        try (Connection conn = openDb()) {

            // ① 修前（裸 seq ASC）：NULL 冒到最前 —— 这就是缺陷「被送进模型上下文顶部」的机制
            assertThat(ids(conn, "SELECT id FROM messages WHERE session_id='" + SESSION + "' ORDER BY seq ASC"))
                .as("裸 seq ASC：NULL 排最前（缺陷现状，本批要修掉的）")
                .startsWith(NULL_ROW_PREFIX + "1", NULL_ROW_PREFIX + "2")
                .endsWith("a", "b");

            // ② 生产 ASC 片段：NULL 稳定推到末尾
            assertNullsLastOrder(
                ids(conn, "SELECT id FROM messages WHERE session_id='" + SESSION
                    + "' ORDER BY " + MessageService.SEQ_ASC_NULLS_LAST_ORDER),
                "listRawForTranscript 的实测排序：非 NULL 升序在前、NULL 全在末尾（不遮挡真实首条、不被 boundary 静默剪掉）",
                List.of("a", "b"), 2);

            // ③ 生产 DESC 片段：NULL 落在 DESC 结果末尾（= 最旧那头），不进尾页冒充「最新」
            assertNullsLastOrder(
                ids(conn, "SELECT id FROM messages WHERE session_id='" + SESSION
                    + "' ORDER BY " + MessageService.SEQ_DESC_NULLS_LAST_ORDER),
                "listPageBySession(DESC) 的实测排序：非 NULL 降序在前、NULL 全在末尾（= 最旧那头）",
                List.of("b", "a"), 2);

            // ④ 尾页实际形态（取最新 2 条）：NULL 行排在「最旧」那头，绝不挤掉刚写入的真实行
            //   （这是 LIMIT 语义的要害：若 NULL 冒充「最新」，这里拿到的会是 dirty*）
            assertThat(ids(conn, "SELECT id FROM messages WHERE session_id='" + SESSION
                    + "' ORDER BY " + MessageService.SEQ_DESC_NULLS_LAST_ORDER + " LIMIT 2"))
                .as("尾页取最新 2 条：是真实的 b/a，不是位置未知的 dirty*（NULL 不得冒充刚写入的那条）")
                .containsExactly("b", "a");

            // ⑤ 被否决的替代方案：NULLS FIRST 会把 NULL 顶成「最新」（固化为反例，防后来者改回）
            assertNullsFirstOrder(
                ids(conn, "SELECT id FROM messages WHERE session_id='" + SESSION + "' ORDER BY " + REJECTED_NULLS_FIRST),
                "已否决方案（NULLS FIRST）：NULL 冒充最新（生产禁止使用）",
                List.of("b", "a"), 2);

            // ⑥ 跨会话不串行（生产恒带 session_id 条件；防「片段被误接到无 WHERE 的查询上」）
            assertThat(ids(conn, "SELECT id FROM messages WHERE session_id='" + OTHER_SESSION
                    + "' ORDER BY " + MessageService.SEQ_ASC_NULLS_LAST_ORDER))
                .as("另一会话的行不被本会话排序片段牵动")
                .containsExactly("other");
        }
    }

    @Test
    @DisplayName("查询计划：新片段走 idx_messages_session_seq 且【无 USE TEMP B-TREE】；旧表达式前置键有")
    void orderByNullsLastKeepsIndexUsableWithoutTempBTree() throws Exception {
        try (Connection conn = openDb()) {

            // ── 核心断言：两条生产通道（含 DESC+LIMIT 的分页形态）都必须无临时排序 ──────────────
            String[] productionSql = {
                ascSql(),
                descSql(),
                descSql() + " LIMIT 3",     // listPageBySession 的真实形态：DESC 取 pageSize+1
            };
            for (String sql : productionSql) {
                List<String> plan = explainQueryPlan(conn, sql);
                assertThat(plan)
                    .as("生产排序片段必须能由 idx_messages_session_seq 直接出序（否则全量临时排序把索引收益废掉）"
                        + "；SQL=" + sql + "；实际计划=" + plan)
                    .anySatisfy(line -> assertThat(line).contains("USING INDEX idx_messages_session_seq"));
                assertThat(plan)
                    .as("【核心】排序键必须是索引列（seq），不得退化为全量临时排序；SQL=" + sql + "；实际计划=" + plan)
                    .noneSatisfy(line -> assertThat(line).contains("TEMP B-TREE"));
            }

            // ── 对照（固化「旧写法为何被淘汰」）：表达式前置键必然引入 TEMP B-TREE ────────────────
            List<String> legacyPlan = explainQueryPlan(conn, "SELECT id FROM messages WHERE session_id='"
                + SESSION + "' ORDER BY " + REJECTED_EXPRESSION_PREFIX);
            assertThat(legacyPlan)
                .as("旧写法 `seq IS NULL, seq ASC` 的排序键是表达式 → 实测必然多一次全量临时排序"
                    + "（这正是本批换成 NULLS LAST 的全部理由）；实际计划=" + legacyPlan)
                .anySatisfy(line -> assertThat(line).contains("TEMP B-TREE"));

            // ── 驱动必须支持 NULLS LAST（SQLite 3.30+ 语法；生产 sqlite-jdbc 3.46）─────────────
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select sqlite_version()")) {
                rs.next();
                String version = rs.getString(1);
                String[] v = version.split("\\.");
                int major = Integer.parseInt(v[0]);
                int minor = Integer.parseInt(v[1]);
                assertThat(major > 3 || (major == 3 && minor >= 30))
                    .as("NULLS LAST 需要 SQLite 3.30+，实测驱动引擎版本 " + version)
                    .isTrue();
            }
        }
    }

    @Test
    @DisplayName("在真表 + 真索引上跑生产形态查询：ASC 全量序与 DESC 尾页序均与期望逐位一致")
    void productionShapedQueriesMatchExpectedOrder() throws Exception {
        try (Connection conn = openDb()) {
            assertThat(ids(conn, ascSql()))
                .as("ASC：真实行升序在前，NULL 行尾巴（列表渲染不会被脏行插到最前）")
                .containsExactly("a", "b", NULL_ROW_PREFIX + "1", NULL_ROW_PREFIX + "2");
            assertThat(ids(conn, descSql() + " LIMIT 2"))
                .as("DESC 尾页：取到的必须是最新的真实行")
                .containsExactly("b", "a");
            assertNullsLastOrder(ids(conn, descSql() + " LIMIT 4"),
                "DESC 全量：真实行降序在前，NULL 行在最后（= 最旧那头）", List.of("b", "a"), 2);
        }
    }

    // ─────────────────────────── 断言助手 ───────────────────────────

    /**
     * 断言契约形态：<b>非 NULL 段逐位正确 + 其后的 n 个位置全是 NULL 行</b>。
     * NULL 行<b>之间</b>的顺序刻意不校验（不属契约，SQLite 不保证）。
     */
    private static void assertNullsLastOrder(List<String> actual, String what, List<String> expectNonNull, int nullCount) {
        assertThat(actual).as(what + "：总条数").hasSize(expectNonNull.size() + nullCount);
        assertThat(actual.subList(0, expectNonNull.size()))
            .as(what + "：非 NULL 段（前 " + expectNonNull.size() + " 位）")
            .containsExactlyElementsOf(expectNonNull);
        assertThat(actual.subList(expectNonNull.size(), actual.size()))
            .as(what + "：尾段必须全是 NULL 行")
            .allSatisfy(id -> assertThat(id).startsWith(NULL_ROW_PREFIX));
    }

    /** 反例断言：NULL 全部冒到<b>最前</b>（这是被否决的 NULLS FIRST 的后果）。 */
    private static void assertNullsFirstOrder(List<String> actual, String what, List<String> expectNonNull, int nullCount) {
        assertThat(actual).as(what + "：总条数").hasSize(expectNonNull.size() + nullCount);
        assertThat(actual.subList(0, nullCount))
            .as(what + "：头段必须全是 NULL 行（冒充最新）")
            .allSatisfy(id -> assertThat(id).startsWith(NULL_ROW_PREFIX));
        assertThat(actual.subList(nullCount, actual.size()))
            .as(what + "：非 NULL 段")
            .containsExactlyElementsOf(expectNonNull);
    }

    // ─────────────────────────── 基建 ───────────────────────────

    private static String ascSql() {
        return "SELECT id FROM messages WHERE session_id='" + SESSION + "' ORDER BY "
            + MessageService.SEQ_ASC_NULLS_LAST_ORDER;
    }

    private static String descSql() {
        return "SELECT id FROM messages WHERE session_id='" + SESSION + "' ORDER BY "
            + MessageService.SEQ_DESC_NULLS_LAST_ORDER;
    }

    /** 真表 + <b>真索引</b>（形状同 V70:49）；无索引则 EXPLAIN QUERY PLAN 的断言全是空的。 */
    private Connection openDb() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbFile = tempDir.resolve("seq-null-order.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, seq INTEGER)");
            st.executeUpdate("CREATE INDEX idx_messages_session_seq ON messages(session_id, seq)");
        }
        insert(conn, "a", SESSION, 10L);
        insert(conn, "b", SESSION, 11L);
        insert(conn, NULL_ROW_PREFIX + "1", SESSION, null);
        insert(conn, NULL_ROW_PREFIX + "2", SESSION, null);
        insert(conn, "other", OTHER_SESSION, 5L);
        return conn;
    }

    private static void insert(Connection conn, String id, String sessionId, Long seq) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO messages(id, session_id, seq) VALUES(?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            if (seq == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, seq);
            }
            ps.executeUpdate();
        }
    }

    private static List<String> ids(Connection conn, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** {@code EXPLAIN QUERY PLAN} 的 detail 列（第 4 列）逐行。 */
    private static List<String> explainQueryPlan(Connection conn, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                out.add(rs.getString(4));
            }
        }
        return out;
    }
}

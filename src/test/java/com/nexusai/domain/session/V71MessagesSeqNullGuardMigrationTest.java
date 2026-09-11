package com.nexusai.domain.session;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V71（{@code messages.seq} 重入安全回填守卫 + NULL 拒绝触发器）· <b>真 sqlite 跑真迁移</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：V71 的存在理由就是「可重入 / 只补空 / 挡住 NULL」，
 * 这三条不验证等于没修：
 * <ol>
 *   <li><b>重入幂等</b>：V70 的裸 {@code ALTER TABLE ADD COLUMN}（无 IF NOT EXISTS）与无守卫回填在
 *       「人工/repair 重跑」时会把<b>已写入的雪花号重算成 1..N</b> → kept 行掉回 boundary 之前 →
 *       下轮 boundary 切片把 kept 段整段剪掉（模型静默丢近期上下文）。V71 的回填带
 *       {@code WHERE seq IS NULL} → 重跑只补空行、<b>绝不重算已有号</b>；触发器带 IF NOT EXISTS。</li>
 *   <li><b>NULL 根因入口封堵</b>：{@code BEFORE INSERT / BEFORE UPDATE ... WHEN NEW.seq IS NULL}
 *       → {@code RAISE(ABORT)}。NULL 在 {@code ORDER BY seq ASC} 排最前（冒充会话最旧）、
 *       在 DESC 尾页查询排最后（被挤出尾页）→ 同一份数据两条通道顺序相反。</li>
 * </ol>
 *
 * <p><b>RED 条件（mutation 自证）</b>：
 * <ul>
 *   <li>V71 回填去掉 {@code WHERE seq IS NULL} → {@link #reentryBackfillNeverRecomputesExistingSnowflakeSeq()}
 *       的「已有雪花号保持原值」断言红；</li>
 *   <li>删掉两个触发器（或去掉 WHEN NEW.seq IS NULL）→
 *       {@link #triggersRejectNullSeqOnInsertAndUpdate()} 红；</li>
 *   <li>触发器去掉 IF NOT EXISTS → 重跑（本类多处二次执行 V71 语句）抛
 *       {@code trigger ... already exists} → {@link #v71AppliedThenReentrant()} 红。</li>
 * </ul>
 *
 * <p><b>与 V70 测试的分工</b>：{@code V70MessagesSeqMigrationTest} 钉「V70 回填本身的正确/性能」；
 * 本类钉「V71 建立在 V70 之上的可重入与 NULL 守卫」（真 Flyway 全量迁移 → V1..V71 顺序应用）。
 */
@DisplayName("[V71] messages.seq 可重入回填守卫 + NULL 拒绝触发器（真 sqlite 真迁移）")
class V71MessagesSeqNullGuardMigrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V71__messages_seq_null_guard.sql";

    private static final String SESSION = "sess-v71";

    /** 「全 NULL 会话」（base=0 → 回填 1..N，与 V70 回填同结果）。 */
    private static final String ALL_NULL_SESSION = "sess-v71-allnull";

    /** 触发器名（sqlite_master.type='trigger'）。 */
    private static final List<String> TRIGGERS =
        List.of("trg_messages_seq_not_null_insert", "trg_messages_seq_not_null_update");

    /** 触发器错误信息前缀（生产 RAISE(ABORT, ...) 的原文，便于日志检索）。 */
    private static final String ABORT_MARKER = "messages.seq must not be NULL";

    /** 模拟「V70 之后已写入的雪花号」（约 9e18，远大于 V70 回填的 1..N）。 */
    private static final long EXISTING_SNOWFLAKE = 9_000_000_000_000_000_000L;

    @TempDir
    Path tempDir;

    private Path dbFile;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("v71.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        // 真 Flyway 全量迁移（classpath:db/migration → V1..V71 顺序应用，与生产启动同路径）
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").baselineOnMigrate(true)
            .load().migrate();
    }

    @Test
    @DisplayName("V71 已应用（历史表 success=1 + 两个触发器存在）→ 语句集再跑两遍仍不报错（重入幂等）")
    void v71AppliedThenReentrant() throws Exception {
        try (Connection conn = open()) {
            assertThat(historySuccess(conn, "71")).as("flyway_schema_history 记录 V71 且 success=1").isEqualTo(1);
            assertThat(triggerNames(conn)).as("V71 建立的两个 NULL 守卫触发器").containsExactlyInAnyOrderElementsOf(TRIGGERS);

            // 重入：把 V71 的全部语句原样再执行两遍（等价于「人工重跑」「repair 后重跑」）
            for (int round = 1; round <= 2; round++) {
                for (String sql : statements(readMigrationSql())) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate(sql);
                    }
                }
            }
            assertThat(triggerNames(conn)).as("重跑后触发器仍在（IF NOT EXISTS 幂等）")
                .containsExactlyInAnyOrderElementsOf(TRIGGERS);
        }
    }

    @Test
    @DisplayName("回填只填 NULL 行（已有号保持原值，重跑不重算）+ NULL 行补到已定位行之后（验真实混合态形状）")
    void reentryBackfillNeverRecomputesExistingSnowflakeSeq() throws Exception {
        try (Connection conn = open()) {
            seedSession(conn, SESSION);
            // 混合态 = 真实库实测形状（sess-c72a825a：239 行已定位 + 548 行 NULL 且 NULL 行更晚）
            insertMessage(conn, "m-pos", SESSION, EXISTING_SNOWFLAKE, "2026-09-10T20:48:00+08:00");
            // 「存量坏行」：触发器上线前的库 / 人工修复窗口 —— 先摘掉触发器才能塞进 NULL 行
            dropTriggers(conn);
            insertMessage(conn, "m-null-old", SESSION, null, "2026-09-10T21:00:00+08:00");
            insertMessage(conn, "m-null-new", SESSION, null, "2026-09-10T22:52:00+08:00");
            // 另造「全 NULL 会话」：base=0 → 回填为 1..N（与 V70 回填同结果）
            seedSession(conn, ALL_NULL_SESSION);
            insertMessage(conn, "a1", ALL_NULL_SESSION, null, "2026-09-10T10:00:00+08:00");
            insertMessage(conn, "a2", ALL_NULL_SESSION, null, "2026-09-10T10:00:01+08:00");
            assertThat(seqOf(conn, "m-null-new")).as("前提：脏行 seq 为 NULL").isNull();

            // WHEN: 执行 V71（回填守卫 + 触发器重建）
            runV71(conn);

            // THEN ① 空行被补位（不再有 NULL），且值 = base + rn（base=已有 max(seq)=9e18；rn 按
            //   (created_at,id) 全表排名：m-pos=1 / m-null-old=2 / m-null-new=3 → 9e18+2 / 9e18+3）。
            //   [为什么敢钉死具体值] 本条钉的是 SQLite 对「相关子查询里带窗口函数的派生表」的求值语义：
            //   实测（sqlite-jdbc 3.46 真库副本 548 行 + CLI 3.51 合成 1000 行）该派生表被**整体物化一次**
            //   → base 恒等于「更新前」的该会话 max(seq)，不随 UPDATE 逐行推进而漂移。
            //   若将来引擎改成逐行重算（base 会看到已写入的新值 → 值远大于 base+rn），本条会红 ——
            //   那不是「无关紧要的脆断言」，而是**语义变了**的信号（届时应改成先把快照落临时表再 UPDATE）。
            Long seqOld = seqOf(conn, "m-null-old");
            Long seqNew = seqOf(conn, "m-null-new");
            assertThat(seqOld).as("NULL 行补位 = base(已有 max seq) + rn（实测：单次物化，不漂移）")
                .isEqualTo(EXISTING_SNOWFLAKE + 2);
            assertThat(seqNew).isEqualTo(EXISTING_SNOWFLAKE + 3);
            // THEN ② 已有号**一点没动**（核心断言：去掉 WHERE seq IS NULL → 这里会被重算成 1..3 → 红）
            assertThat(seqOf(conn, "m-pos"))
                .as("已有号必须保持原值 —— 被重算 = kept 行掉回 boundary 之前 = 下轮切片静默丢近期上下文")
                .isEqualTo(EXISTING_SNOWFLAKE);
            // THEN ③ NULL 行补到已定位行**之后**（base+rn），且彼此保持 (created_at,id) 序
            //   （反例：若按 V70 的 1..k 编号 → 更晚的脏行会落到会话最前 → 将来被 boundary 切片整段剪掉）
            assertThat(seqOld).as("NULL 行必须排在已定位行之后（它们是最晚写入的）").isGreaterThan(EXISTING_SNOWFLAKE);
            assertThat(seqNew).as("多个 NULL 行之间保持时间序").isGreaterThan(seqOld);
            assertThat(idsAsc(conn, SESSION)).as("会话内最终位置序 = [已定位行, NULL-old, NULL-new]")
                .containsExactly("m-pos", "m-null-old", "m-null-new");
            // THEN ④ 全 NULL 会话 → base=0 → 1..N
            assertThat(seqOf(conn, "a1")).isEqualTo(1L);
            assertThat(seqOf(conn, "a2")).isEqualTo(2L);
            // THEN ⑤ 触发器已重建
            assertThat(triggerNames(conn)).containsExactlyInAnyOrderElementsOf(TRIGGERS);

            // WHEN: 再跑一遍（重入）
            runV71(conn);
            // THEN ⑥ 重跑不报错且所有 seq 都不变（幂等：只补空行，空行已补满 → no-op）
            assertThat(seqOf(conn, "m-pos")).isEqualTo(EXISTING_SNOWFLAKE);
            assertThat(seqOf(conn, "m-null-old")).as("已补位的行不被重算（WHERE seq IS NULL 的幂等性）")
                .isEqualTo(seqOld);
            assertThat(seqOf(conn, "m-null-new")).isEqualTo(seqNew);
            assertThat(idsAsc(conn, SESSION)).containsExactly("m-pos", "m-null-old", "m-null-new");
        }
    }

    @Test
    @DisplayName("触发器：INSERT/UPDATE seq=NULL 被 ABORT；不动 seq 的 UPDATE 与带 seq 的 INSERT 不受影响")
    void triggersRejectNullSeqOnInsertAndUpdate() throws Exception {
        try (Connection conn = open()) {
            seedSession(conn, SESSION);
            insertMessage(conn, "m-ok", SESSION, 42L, "2026-09-11T10:00:00+08:00");

            // ① INSERT 不带 seq（= 位置键未落）→ ABORT
            assertThatThrownBy(() -> insertMessage(conn, "m-null", SESSION, null, "2026-09-11T11:00:00+08:00"))
                .as("BEFORE INSERT ... WHEN NEW.seq IS NULL → RAISE(ABORT)（NULL 根因入口被封堵）")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(ABORT_MARKER);
            assertThat(seqOf(conn, "m-null")).as("被拒的行不得落库").isNull();

            // ② UPDATE 把 seq 改回 NULL → ABORT（compact 重挂只允许写非 NULL 的 seq）
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE messages SET seq = NULL WHERE id = ?")) {
                    ps.setString(1, "m-ok");
                    ps.executeUpdate();
                }
            })
                .as("BEFORE UPDATE ... WHEN NEW.seq IS NULL → RAISE(ABORT)")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(ABORT_MARKER);
            assertThat(seqOf(conn, "m-ok")).as("被拒的 UPDATE 不得生效").isEqualTo(42L);

            // ③ 反例守卫：不动 seq 的 UPDATE（image_paste_ids / user_attachments 回写同类）必须照常成功
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE messages SET content = ? WHERE id = ?")) {
                ps.setString(1, "改内容");
                ps.setString(2, "m-ok");
                assertThat(ps.executeUpdate()).as("不触碰 seq 的 UPDATE 不得被触发器误伤").isEqualTo(1);
            }
            // ④ 反例守卫：带显式非 NULL seq 的 UPDATE / INSERT 照常成功
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE messages SET seq = ? WHERE id = ?")) {
                ps.setLong(1, 43L);
                ps.setString(2, "m-ok");
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            assertThat(seqOf(conn, "m-ok")).isEqualTo(43L);
            assertThat(seqOf(conn, "m-ok")).isNotNull();
        }
    }

    // ══════════════════════════ helpers ══════════════════════════

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private static void seedSession(Connection conn, String sessionId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sessions(id, model_tag, model_name, title, time, session_group) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, "DS");
            ps.setString(3, "test-model");
            ps.setString(4, "v71 测试");
            ps.setString(5, "刚刚");
            ps.setString(6, "default");
            ps.executeUpdate();
        }
    }

    /** 执行 V71 的全部语句（重入验证：同一份语句集可反复执行）。 */
    private static void runV71(Connection conn) throws Exception {
        for (String sql : statements(readMigrationSql())) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(sql);
            }
        }
    }

    /** 该会话按 ***位置键 seq*** 的行 id 序列（复现读侧 ORDER BY seq ASC 的顺序）。 */
    private static List<String> idsAsc(Connection conn, String sessionId) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM messages WHERE session_id = ? ORDER BY " + MessageService.SEQ_NULLS_LAST_ORDER
                    + " ASC, seq ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** 直插一行消息（seq=null 时考察触发器；列形状取 V1 schema 的 NOT NULL 列）。 */
    private static void insertMessage(Connection conn, String id, String sessionId, Long seq, String createdAt)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO messages(id, session_id, role, content, created_at, seq) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            ps.setString(3, "user");
            ps.setString(4, "内容-" + id);
            ps.setString(5, createdAt);
            if (seq == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, seq);
            }
            ps.executeUpdate();
        }
    }

    private static Long seqOf(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT seq FROM messages WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null; // 行不存在（被触发器拒掉）
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static void dropTriggers(Connection conn) throws Exception {
        for (String t : TRIGGERS) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TRIGGER IF EXISTS " + t);
            }
        }
    }

    private static List<String> triggerNames(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static int historySuccess(Connection conn, String version) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT success FROM flyway_schema_history WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static String readMigrationSql() throws Exception {
        try (InputStream in = V71MessagesSeqNullGuardMigrationTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("V71 迁移文件在类路径 %s", MIGRATION_RESOURCE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 剥掉 {@code --} 行注释后按 {@code ;} 切分为可逐条 executeUpdate 的语句。
     *
     * <p><b>与 V70 测试的分割器不同（必要差异）</b>：V71 含 {@code CREATE TRIGGER ... BEGIN ... END;}
     * —— 触发器体内部有分号，不能按行尾分号裸切。此处把 {@code BEGIN ... END;} 视为<b>一条</b>语句。
     * （生产路径不经过本分割器：Flyway 用 {@code SQLiteParser} 解析。）
     */
    static List<String> statements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inTriggerBody = false;
        for (String raw : sql.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            cur.append(raw).append('\n');
            if (!inTriggerBody && line.equalsIgnoreCase("BEGIN")) {
                inTriggerBody = true;
                continue;
            }
            if (inTriggerBody) {
                if (line.endsWith("END;")) {
                    inTriggerBody = false;
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                }
                continue;
            }
            if (line.endsWith(";")) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        return out;
    }
}

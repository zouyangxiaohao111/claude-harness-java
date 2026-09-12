package com.nexusai.domain.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * V70 回填 SQL 实测 · 真 sqlite 跑整段迁移文件（类路径读取生产同源文件）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：V70 把 {@code messages.seq} 立为会话内位置键
 * （读侧 {@code listPageBySession} 用它做游标 {@code qw.lt("seq", ...)}），回填必须保证
 * <b>同会话 seq = 1..N 连续唯一且 &gt; 0</b>。旧相关子查询 {@code COUNT(*)} 写法语义正确但
 * <b>O(N²)</b>（每行重扫全表：实测 3000 行 0.44s / 12000 行 7.6s，大会话迁移长时间阻塞）；
 * 本测试同时钉死「窗口函数一次扫描」版本的正确性（实测 12000 行 0.02s）与索引创建的幂等性。
 *
 * <p><b>RED 条件</b>：回填写成 {@code COUNT(*)} 相关子查询 → 语义仍绿（故仅靠本测试不能抓性能回归，
 * 性能由 12000 行耗时上界兜底）；回填按全表（而非 PARTITION BY session_id）取行号 → 跨会话行号
 * 不各自从 1 起 → 断言红；漏 {@code id} 兜底 → 同 created_at 并列时行号不唯一 → 断言红；
 * 索引去掉 {@code IF NOT EXISTS} → 二次执行抛 "index already exists" → 幂等断言红。
 */
@DisplayName("V70 回填 = 会话内 seq 1..N 连续唯一（窗口函数一次扫描）+ 索引幂等")
class V70MessagesSeqMigrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V70__messages_seq_and_transcript_flags.sql";

    /** 大会话行数（复现 O(N²) 写法会明显变慢的规模）。 */
    private static final int BIG_SESSION_ROWS = 3000;

    /** 小会话行数。 */
    private static final int SMALL_SESSION_ROWS = 20;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("造 2 会话（大 3000 行 + 同 created_at 并列）→ 执行整段 V70 → 同会话 1..N 连续唯一、min=1、无 NULL")
    void backfillProducesContiguousUniqueSeqPerSession() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbFile = tempDir.resolve("v70-backfill.db");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            // ── 1. V70 前的最小 messages 形状（回填只依赖 id/session_id/created_at 三列）──
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, created_at TEXT)");
            }

            // ── 2. 造数据：大会话 BIG 行 + 小会话 SMALL 行 + 大会话内 2 行同 created_at（考 id 兜底）──
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < BIG_SESSION_ROWS; i++) {
                rows.add(new Object[]{"a-" + pad(i), "sess-big",
                    String.format("2026-09-09T10:%02d:%02d.%03d+08:00", i / 600, i % 600, i % 1000)});
            }
            for (int i = 0; i < SMALL_SESSION_ROWS; i++) {
                rows.add(new Object[]{"b-" + pad(i), "sess-small",
                    String.format("2026-09-09T11:%02d:00.000+08:00", i)});
            }
            // 同 created_at 并列：id 不同 → 行号必须仍唯一（ORDER BY created_at, id 的 id 兜底）
            rows.add(new Object[]{"a-tie-1", "sess-big", "2026-09-09T09:00:00.000+08:00"});
            rows.add(new Object[]{"a-tie-2", "sess-big", "2026-09-09T09:00:00.000+08:00"});
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO messages(id, session_id, created_at) VALUES(?,?,?)")) {
                for (Object[] r : rows) {
                    ps.setString(1, (String) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setString(3, (String) r[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // ── 3. 执行整段 V70（类路径读取生产同源文件；逐条 executeUpdate）──
            long start = System.currentTimeMillis();
            for (String sql : statements(readMigrationSql())) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate(sql);
                }
            }
            long elapsedMs = System.currentTimeMillis() - start;

            // ── 4. 断言：同会话 1..N 连续唯一 + min=1 + 无 NULL + 顺序 = (created_at, id) 稳定序 ──
            assertSessionSeqContiguous(conn, "sess-big", BIG_SESSION_ROWS + 2);
            assertSessionSeqContiguous(conn, "sess-small", SMALL_SESSION_ROWS);
            int nulls = queryInt(conn, "SELECT COUNT(*) FROM messages WHERE seq IS NULL");
            assertThat(nulls).as("回填后不得有 NULL seq（NULL 在 ORDER BY seq 下排最前 → 顺序错乱）").isZero();

            // 同 created_at 并列的两行 → 行号不同（id 兜底生效）
            assertThat(queryInt(conn,
                "SELECT COUNT(DISTINCT seq) FROM messages WHERE session_id='sess-big' AND created_at='2026-09-09T09:00:00.000+08:00'"))
                .as("同 created_at 并列行 seq 仍唯一（ORDER BY created_at, id 的 id 兜底）").isEqualTo(2);

            // 粗粒度性能上界（非精确基准）：旧 COUNT(*) 相关子查询 12000 行 7.6s，窗口函数 3000 行 <0.1s。
            assertThat(elapsedMs)
                .as("回填耗时应远低于 O(N²) 量级（3000+20 行窗口函数版实测 <0.1s；10s 为灾备上界）")
                .isLessThan(10_000L);

            // ── 5. 索引存在 + 二次执行幂等（IF NOT EXISTS）──
            assertThat(queryInt(conn,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_messages_session_seq'"))
                .as("排序键索引已建（listRawForTranscript/listPageBySession 按 seq）").isEqualTo(1);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq)");
            }
        }
    }

    /** 断言该会话 seq 恰为 1..N 连续唯一（无空洞/无重复/无 NULL），且顺序 = (created_at, id) 稳定序。 */
    private static void assertSessionSeqContiguous(Connection conn, String sessionId, int expectedRows) throws Exception {
        List<Integer> ordered = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT seq FROM messages WHERE session_id=? ORDER BY created_at, id")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ordered.add(rs.getInt(1));
                }
            }
        }
        assertThat(ordered).as("%s 行数", sessionId).hasSize(expectedRows);
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= expectedRows; i++) {
            expected.add(i);
        }
        assertThat(ordered).as("%s 的 seq 按 (created_at,id) 序恰为 1..N（连续唯一，min=1）", sessionId)
            .containsExactlyElementsOf(expected);
    }

    private static int queryInt(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String pad(int i) {
        return String.format("%06d", i);
    }

    /** 从测试类路径读取生产迁移文件（src/main/resources 在测试类路径上）。 */
    private static String readMigrationSql() throws Exception {
        try (InputStream in = V70MessagesSeqMigrationTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("V70 迁移文件在类路径 %s", MIGRATION_RESOURCE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 剥掉 `--` 行注释后按 `;` 切分为可逐条 executeUpdate 的语句（迁移文件无多行字符串/触发器）。 */
    private static List<String> statements(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String s : sb.toString().split(";")) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}

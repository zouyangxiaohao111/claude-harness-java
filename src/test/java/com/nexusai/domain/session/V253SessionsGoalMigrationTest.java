package com.nexusai.domain.session;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [goal V253] {@code sessions.goal} 列迁移测试。
 *
 * <p><b>WHY 需要</b>：目标是本仓「多会话」的核心状态（真源 = 会话列）。迁移一旦失效，
 * 目标会在重启后消失 ⇒ 整个 /goal 机制退化成一次性功能。本测试用<b>真 Flyway 全量迁移</b>
 * （classpath:db/migration → V1..V253 顺序应用，与生产启动同路径）锁死：
 * <ol>
 *   <li>V253 已应用且 success=1</li>
 *   <li>{@code sessions.goal} 列存在、可空（TEXT）</li>
 *   <li>既有行（迁移前写入的）goal 为 NULL —— 迁移不得写脏值</li>
 *   <li>可写入 JSON 串并读回（列不是只读/生成列）</li>
 * </ol>
 */
@DisplayName("[goal V253] V253 sessions.goal 列迁移")
class V253SessionsGoalMigrationTest {

    @TempDir
    Path tempDir;

    private Path dbFile;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("v253.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        // 真 Flyway 全量迁移（与生产启动同路径：classpath:db/migration 顺序应用）
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").baselineOnMigrate(true)
            .load().migrate();
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    @Test
    @DisplayName("V253 已应用（history success=1）且 sessions.goal 列存在")
    void v253AppliedAndColumnExists() throws Exception {
        try (Connection conn = open()) {
            assertThat(historySuccess(conn, "253"))
                .as("flyway_schema_history 记录 V253 且 success=1").isEqualTo(1);
            assertThat(columnInfo(conn, "sessions", "goal"))
                .as("sessions.goal 列存在且类型为 TEXT").isNotNull();
            assertThat(columnInfo(conn, "sessions", "goal")).contains("TEXT");
        }
    }

    @Test
    @DisplayName("迁移不写脏值：既有行的 goal 为 NULL（无目标 = 无目标）")
    void existingRowsHaveNullGoal() throws Exception {
        try (Connection conn = open()) {
            seedSession(conn, "sess-before-migration");
            assertThat(goalOf(conn, "sess-before-migration"))
                .as("迁移前写入的会话，goal 应为 NULL（不是空串、不是 '{}'）")
                .isNull();
        }
    }

    @Test
    @DisplayName("列可写可读：JSON 串原样往返")
    void columnIsWritableAndReadable() throws Exception {
        try (Connection conn = open()) {
            seedSession(conn, "sess-with-goal");
            String json = "{\"condition\":\"e2e 全绿\",\"phase\":\"active\",\"iterations\":2,"
                + "\"setAt\":1762300000000,\"tokensAtStart\":145320}";
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE sessions SET goal = '" + json + "' WHERE id = 'sess-with-goal'");
            }
            assertThat(goalOf(conn, "sess-with-goal")).isEqualTo(json);
        }
    }

    @Test
    @DisplayName("列可空：新会话不写 goal 也合法（默认 NULL）")
    void columnIsNullable() throws Exception {
        try (Connection conn = open()) {
            seedSession(conn, "sess-null-goal");
            assertThat(goalOf(conn, "sess-null-goal")).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private int historySuccess(Connection conn, String version) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT success FROM flyway_schema_history WHERE version = '" + version + "'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** PRAGMA table_info 取列类型（未找到 → null）。 */
    private String columnInfo(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return rs.getString("type");
                }
            }
        }
        return null;
    }

    private String goalOf(Connection conn, String sessionId) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT goal FROM sessions WHERE id = '" + sessionId + "'")) {
            return rs.next() ? rs.getString("goal") : null;
        }
    }

    /**
     * 建一条最小会话行。
     *
     * <p>必填列（V14 重建后的形状）：id / model_tag / title / time / session_group
     * + created_at / updated_at（后两个有 DEFAULT，但显式写更稳）。
     */
    private void seedSession(Connection conn, String sessionId) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO sessions "
                + "(id, model_tag, title, time, session_group, created_at, updated_at) VALUES ('"
                + sessionId + "', 'test-model', 'test', '2026-11-03 00:00:00', 'default', "
                + "'2026-11-03T00:00:00+08:00', '2026-11-03T00:00:00+08:00')");
        }
    }
}

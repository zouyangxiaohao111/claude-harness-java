package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移 SQL 在<b>临时 sqlite</b> 上的可执行性验证（V250 / V251）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：迁移一旦被任何库应用，<b>字节即冻结</b>
 * （加一行注释就改 checksum ⇒ 已应用的库启动即拒）。所以迁移必须<b>一次写对</b> ——
 * 而"一次写对"的唯一可靠验证 = <b>在真 sqlite 上跑一遍</b>（SQLite 方言与 MySQL 差异大：
 * 没有 {@code SELECT ... FOR UPDATE}、没有 {@code ENGINE=}、没有 {@code AUTO_INCREMENT}）。
 *
 * <p>⛔ 本测试只碰<b>临时文件库</b>，绝不连用户真库。
 */
class ComputerUseMigrationSqlTest {

    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("V250 在临时 sqlite 上可执行 ⇒ sessions 表多出 computer_use_grants 列")
    void v250AddsColumn() throws Exception {
        SQLiteDataSource ds = ComputerUseTestSqlite.create(tempDir, "v250.db");
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT)");
        }
        executeMigration(ds, "V250__add_sessions_computer_use_grants.sql");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(sessions)")) {
            boolean found = false;
            while (rs.next()) {
                if ("computer_use_grants".equals(rs.getString("name"))) {
                    found = true;
                    assertThat(rs.getString("type")).isEqualTo("TEXT");
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    @DisplayName("V251 在临时 sqlite 上可执行 ⇒ computer_use_lock 单行表就位且有 id=1 空闲行")
    void v251CreatesLockTable() throws Exception {
        SQLiteDataSource ds = ComputerUseTestSqlite.create(tempDir, "v251.db");
        executeMigration(ds, "V251__create_computer_use_lock.sql");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, session_id, pid, acquired_at FROM computer_use_lock")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isNull();
            // pid / acquired_at 恒为 NULL（空闲态）—— getLong 对 NULL 返回 0，须用 getObject 判空
            assertThat(rs.getObject(3)).isNull();
            assertThat(rs.getObject(4)).isNull();
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("两个迁移连跑（V250 → V251）无冲突，且注释里没有 Flyway 占位符字面量")
    void migrationsRunTogetherAndHaveNoPlaceholders() throws Exception {
        for (String name : List.of("V250__add_sessions_computer_use_grants.sql",
                "V251__create_computer_use_lock.sql")) {
            String sql = readMigration(name);
            assertThat(sql).doesNotContain("${");
        }

        SQLiteDataSource ds = ComputerUseTestSqlite.create(tempDir, "both.db");
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT)");
        }
        executeMigration(ds, "V250__add_sessions_computer_use_grants.sql");
        executeMigration(ds, "V251__create_computer_use_lock.sql");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' "
                     + "AND name IN ('sessions','computer_use_lock')")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables).containsExactlyInAnyOrder("sessions", "computer_use_lock");
        }
    }

    // ── 内部辅助 ──

    private static String readMigration(String name) throws IOException {
        Path path = MIGRATION_DIR.resolve(name);
        assertThat(Files.exists(path)).as("迁移文件存在: %s", path).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void executeMigration(SQLiteDataSource ds, String name) throws Exception {
        String sql = readMigration(name);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = stripComments(statement).trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
    }

    /** 去掉行注释（迁移里只有 {@code --} 行注释）。 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}

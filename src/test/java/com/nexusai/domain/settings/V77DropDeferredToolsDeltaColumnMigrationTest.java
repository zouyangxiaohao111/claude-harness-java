package com.nexusai.domain.settings;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V77（{@code settings.deferred_tools_delta_enabled} 孤儿列 DROP）· <b>真 sqlite 跑真迁移</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：V77 的存在理由就一条 —— 该列在迁移后必须
 * <b>真的消失</b>，且不误伤同表其余列、不误伤索引/数据。这条不验证等于没删。
 *
 * <p><b>反向对照（本类的核心）</b>：{@link #columnAbsentOnlyAfterV77Applied()} 在<b>同一个库</b>上
 * 先迁到 V76（列必须<b>在</b>）再迁到最新（列必须<b>不在</b>）。两次断言只差「V77 是否应用」这一个
 * 变量 ⇒ 排除「列本来就没了 / PRAGMA 读法不对 / 表不存在」等等价解释。
 * ⛔ 若把 V77 的 DROP 语句注释掉，本条第二个断言会红（mutation 自证）。
 *
 * <p><b>RED 条件</b>：①V77 文件缺失或 DROP 被注释 → 第二次断言红；②DROP 误删邻列 →
 * {@link #columnAbsentOnlyAfterV77Applied()} 的邻列断言红；③SQLite/驱动升到不支持 DROP COLUMN
 * → 迁移直接抛错 → 两个用例都红。
 */
@DisplayName("[V77] settings.deferred_tools_delta_enabled 孤儿列 DROP（真 sqlite 真迁移）")
class V77DropDeferredToolsDeltaColumnMigrationTest {

    private static final String COLUMN = "deferred_tools_delta_enabled";

    /** DROP 的邻列（V60 同批建的提示词对齐门控列）：DROP 不得误伤它们。 */
    private static final List<String> NEIGHBOURS = List.of(
        "task_reminder_enabled", "system_prompt_boundary_enabled", "verify_plan_reminder_enabled");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("全量迁移后：V77 success=1 且 settings 表内该列已不存在（邻列仍在）")
    void v77AppliedAndColumnGone() throws Exception {
        Path dbFile = tempDir.resolve("v77-full.db");
        migrateFully(dbFile);

        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "77"))
                .as("flyway_schema_history 记录 V77 且 success=1").isEqualTo(1);
            assertThat(columnNames(conn))
                .as("V77 DROP COLUMN 后 settings 表不得再有该列").doesNotContain(COLUMN);
            assertThat(columnNames(conn))
                .as("DROP 不得误伤同批其余列").containsAll(NEIGHBOURS);
        }
    }

    @Test
    @DisplayName("反向对照：迁到 V76 时列『在』→ 再迁到最新后列『不在』（同一库，只差 V77）")
    void columnAbsentOnlyAfterV77Applied() throws Exception {
        Path dbFile = tempDir.resolve("v77-ab.db");

        // WHEN ①：只迁到 V76（V77 未应用）
        migrateTo(dbFile, "76");
        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "77"))
                .as("V77 未应用时历史表不应有 V77 行").isEqualTo(-1);
            assertThat(columnNames(conn))
                .as("反向对照前提：V76 状态下该列必须【在】（否则本对照无意义）").contains(COLUMN);
            assertThat(columnNames(conn)).containsAll(NEIGHBOURS);
        }

        // WHEN ②：同一库继续迁到最新（补上 V77）
        migrateFully(dbFile);
        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "77")).isEqualTo(1);
            assertThat(columnNames(conn))
                .as("应用 V77 后该列必须【不在】—— 两轮只差『V77 是否应用』这一个变量")
                .doesNotContain(COLUMN);
            assertThat(columnNames(conn))
                .as("继续迁移到最新不得丢邻列").containsAll(NEIGHBOURS);
        }
    }

    // ══════════════════════════ helpers ══════════════════════════

    /** 真 Flyway 全量迁移（classpath:db/migration → V1..最新 顺序应用，与生产启动同路径）。 */
    private static void migrateFully(Path dbFile) {
        Flyway.configure().dataSource(ds(dbFile)).locations("classpath:db/migration")
            .baselineOnMigrate(true).load().migrate();
    }

    /** 只迁到指定版本（反向对照用；不应用更高版本）。 */
    private static void migrateTo(Path dbFile, String version) {
        Flyway.configure().dataSource(ds(dbFile)).locations("classpath:db/migration")
            .baselineOnMigrate(true).target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static SQLiteDataSource ds(Path dbFile) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }

    private static Connection open(Path dbFile) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private static List<String> columnNames(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(settings)")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
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
}

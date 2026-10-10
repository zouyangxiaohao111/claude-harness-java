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
 * V78（{@code settings.stream_idle_timeout_ms} 新列 · 流空闲看门狗阈值）· <b>真 sqlite 跑真迁移</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：该列是「前端设置→通用页可配」的落点。列名与
 * Java 字段的 camelCase↔snake 映射（{@code streamIdleTimeoutMs → stream_idle_timeout_ms}）一旦
 * 错位，症状是<b>静默失败</b>：PUT 保存成功、GET 也读回，但 select 到的是「列不存在」错误或
 * 永远 null → 阈值配置「点了等于没点」（V37 websearchUseSmallModel 小写 s 错列史同类）。
 * 故本类把「列真的存在、名字精确、不误伤邻列」钉死在真迁移上。
 *
 * <p><b>反向对照（本类的核心）</b>：{@link #columnPresentOnlyAfterV78Applied()} 在<b>同一库</b>上
 * 先迁到 V77（列必须<b>不在</b>）再迁到最新（列必须<b>在</b>）。两次断言只差「V78 是否应用」这一个
 * 变量 ⇒ 排除「列本来就有 / PRAGMA 读法不对 / 表不存在」等等价解释。
 * ⛔ 若把 V78 的 ALTER 语句注释掉，本条第二个断言会红（mutation 自证）。
 *
 * <p><b>RED 条件</b>：①V78 文件缺失或 ALTER 被注释 → 第二次断言红；②列名拼错 → 断言红；
 * ③V78 误伤邻列 → 邻列断言红。
 */
@DisplayName("[V78] settings.stream_idle_timeout_ms 新列（真 sqlite 真迁移）")
class V78StreamIdleTimeoutMigrationTest {

    private static final String COLUMN = "stream_idle_timeout_ms";

    /** 同表既有列（V34/V55/V72）：加列不得误伤它们。 */
    private static final List<String> NEIGHBOURS = List.of(
        "auto_memory_enabled", "snip_nudge_threshold", "allow_dynamic_header_values");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("全量迁移后：V78 success=1 且 settings 表内该列已存在（邻列仍在）")
    void v78AppliedAndColumnPresent() throws Exception {
        Path dbFile = tempDir.resolve("v78-full.db");
        migrateFully(dbFile);

        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "78"))
                .as("flyway_schema_history 记录 V78 且 success=1").isEqualTo(1);
            assertThat(columnNames(conn))
                .as("V78 ALTER 后 settings 表必须有该列（否则配置恒为「未设置」→ 静默回落默认）")
                .contains(COLUMN);
            assertThat(columnNames(conn))
                .as("加列不得误伤同表既有列").containsAll(NEIGHBOURS);
        }
    }

    @Test
    @DisplayName("反向对照：迁到 V77 时列『不在』→ 再迁到最新后列『在』（同一库，只差 V78）")
    void columnPresentOnlyAfterV78Applied() throws Exception {
        Path dbFile = tempDir.resolve("v78-ab.db");

        // WHEN ①：只迁到 V77（V78 未应用）
        migrateTo(dbFile, "77");
        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "78"))
                .as("V78 未应用时历史表不应有 V78 行").isEqualTo(-1);
            assertThat(columnNames(conn))
                .as("反向对照前提：V77 状态下该列必须【不在】（否则本对照无意义）").doesNotContain(COLUMN);
            assertThat(columnNames(conn)).containsAll(NEIGHBOURS);
        }

        // WHEN ②：同一库继续迁到最新（补上 V78）
        migrateFully(dbFile);
        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "78")).isEqualTo(1);
            assertThat(columnNames(conn))
                .as("应用 V78 后该列必须【在】—— 两轮只差『V78 是否应用』这一个变量")
                .contains(COLUMN);
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

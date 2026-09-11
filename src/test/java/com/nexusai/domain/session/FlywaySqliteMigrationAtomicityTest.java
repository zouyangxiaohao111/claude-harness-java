package com.nexusai.domain.session;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【实测】Flyway 在 SQLite 上是否把<b>单个迁移文件包在一个事务里</b> —— 决定「V70 中断重试」场景
 * （应用起不来：第 1 条 {@code ALTER TABLE ... ADD COLUMN} 报 {@code duplicate column name: seq}）
 * 是「结构性安全」还是「需文档化的运维步骤」。
 *
 * <p><b>WHY 必须实测而不是读文档/读代码</b>：Flyway 的事务行为取决于
 * {@code Database.supportsDdlTransactions()} 与驱动实现。静态反编译显示本仓实际使用的
 * {@code org.flywaydb.core.internal.database.sqlite.SQLiteDatabase.supportsDdlTransactions()} 返回
 * {@code true}（iconst_1），<b>但静态结论不作数</b>（CLAUDE.md：不信注释/推断，要跑）。本测试用与生产
 * 完全同源的依赖栈（Spring Boot 3.5.3 BOM → flyway-core，sqlite-jdbc）真跑一次「中途失败」。
 *
 * <p><b>场景复现方式</b>：迁移目录里 V1 = V70 之前的最小 {@code messages} 形状；V2 = <b>真实 V70 文件
 * 逐字内容 + 末尾追加一条必然报错的语句</b>（再次 {@code ADD COLUMN seq} → duplicate column name）。
 * 追加在末尾 → 报错前 V70 的三条 ALTER / 回填 UPDATE / 索引<b>都已成功执行</b>，正好观测「已应用的
 * 半成品是否被回滚」。
 *
 * <p><b>实测结论（本测试断言即结论，改依赖版本若翻案会变红）</b>：
 * <ol>
 *   <li>{@code migrate()} 抛 {@link FlywayException}（消息含 {@code duplicate column name}）；</li>
 *   <li>失败后 {@code messages.seq} 列<b>不存在</b>、{@code idx_messages_session_seq} 索引<b>不存在</b>
 *       → 整个迁移文件被<b>整体回滚</b>（SQLite 支持 DDL 事务，Flyway 据此把单文件包进一个事务）；</li>
 *   <li>{@code flyway_schema_history} 里<b>没有</b> V2 行（连失败记录都没有）→ 把 V2 换成修好的版本
 *       重跑 {@code migrate()} <b>可直接成功，无需 flyway repair</b>。</li>
 * </ol>
 * 故 <b>V70「中断重试」场景 (a) 属结构性安全</b>（进程被杀那一刻的迁移要么全应用、要么全没应用），
 * 不需要额外的运维步骤；V71 里「不能改 V70」的唯一硬约束因此只剩 <b>checksum</b>（改文件会让所有
 * 已有库 validate 失败），而不是「怕半应用状态」。
 *
 * <p><b>RED 条件</b>：Flyway/sqlite-jdbc 升级后若 SQLite 被改成「不支持 DDL 事务」，第 2/3 条断言会红
 * —— 那正是必须把场景 (a) 写成运维步骤（中断后 {@code flyway repair} + 手工补列）的信号，不要放宽断言。
 */
@DisplayName("[实测] Flyway × SQLite：单个迁移文件 = 全应用或全回滚（中断重试结构性安全）")
class FlywaySqliteMigrationAtomicityTest {

    private static final String V70_RESOURCE = "db/migration/V70__messages_seq_and_transcript_flags.sql";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("V70 逐字副本 + 末尾插一条必错语句 → 整文件回滚：列/索引/历史行都不留，修好后可直接重跑")
    void failedMigrationRollsBackWholeFile_noHalfAppliedState() throws Exception {
        Path migrations = tempDir.resolve("probe-migrations");
        Files.createDirectories(migrations);

        // V1 = V70 之前的最小 messages 形状（V70 的 ALTER/回填只依赖 id/session_id/created_at）
        Files.writeString(migrations.resolve("V1__probe_base.sql"),
            "CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, created_at TEXT);\n");

        // V2 = 真实 V70 逐字内容 + 末尾必错语句（重复 ADD COLUMN → duplicate column name: seq）
        String v70 = readResource(V70_RESOURCE);
        Files.writeString(migrations.resolve("V2__probe_v70_with_midway_failure.sql"),
            v70 + "\nALTER TABLE messages ADD COLUMN seq INTEGER NULL;\n");

        Path dbFile = tempDir.resolve("flyway-atomicity.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Flyway flyway = Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + migrations.toAbsolutePath().toString().replace('\\', '/'))
            .load();

        // ── 1. 中途失败：必须抛 FlywayException（不得静默成功）──
        assertThatThrownBy(flyway::migrate)
            .as("V70 副本末尾的重复 ADD COLUMN 必须让迁移失败并抛错（不是静默跳过）")
            .isInstanceOf(FlywayException.class);

        // ── 2. 观测失败后的库内状态（原样打印 = 证据）──
        List<String> columns;
        List<String> historyVersions;
        int seqIndexCount;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            columns = columnNames(conn, "messages");
            historyVersions = historySuccessFlags(conn);
            seqIndexCount = queryInt(conn,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_messages_session_seq'");
        }
        System.out.println("[PROBE] 失败后 messages 列 = " + columns);
        System.out.println("[PROBE] 失败后 flyway_schema_history(version,success) = " + historyVersions);
        System.out.println("[PROBE] 失败后 idx_messages_session_seq 计数 = " + seqIndexCount);

        // ── 3. 断言实测结论：SQLite 上「单文件 = 全应用或全回滚」──
        assertThat(columns)
            .as("失败后 seq 列不得留下（整个 V70 副本被回滚；若此处出现 seq = 半应用状态，"
                + "场景(a) 必须写成运维步骤）")
            .doesNotContain("seq");
        assertThat(columns)
            .as("is_compact_summary / is_visible_in_transcript_only 同样不得留下")
            .doesNotContain("is_compact_summary", "is_visible_in_transcript_only");
        assertThat(seqIndexCount).as("排序键索引也不得留下（回滚覆盖 DDL 与索引）").isZero();
        assertThat(historyVersions)
            .as("历史表里不得留有 V2 的失败记录（同事务回滚）→ 无需 flyway repair")
            .doesNotContain("2");

        // ── 4. 修好 V2（= 真 V70 原文，去掉必错语句）→ 直接重跑 migrate() 必须成功 ──
        Files.writeString(migrations.resolve("V2__probe_v70_with_midway_failure.sql"), v70);
        flyway.migrate();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            List<String> after = columnNames(conn, "messages");
            System.out.println("[PROBE] 修复后重跑成功，messages 列 = " + after);
            assertThat(after).as("重跑后 seq 列存在 = 中断重试可直接恢复（无需 repair）")
                .contains("seq");
        }
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream in = FlywaySqliteMigrationAtomicityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("资源 %s 在测试类路径上", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> columnNames(Connection conn, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    private static List<String> historySuccessFlags(Connection conn) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                out.add(rs.getString(1) + ":" + rs.getInt(2));
            }
        }
        return out;
    }

    private static int queryInt(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}

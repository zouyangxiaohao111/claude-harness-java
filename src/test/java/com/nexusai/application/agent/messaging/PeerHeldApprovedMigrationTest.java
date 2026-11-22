package com.nexusai.application.agent.messaging;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V332 迁移：历史 {@code approved} 行一次性转回 pending（严格 CC 语义收尾）。
 *
 * <h2>WHY</h2>
 * <p>旧实现把「已批准但未消费」持久化为 status='approved'（V323）。CC 没有 approved 中间态
 * ⇒ 这些行必须回到 pending 让用户重新处理；同时清掉批准/展示中间态标记（approved_at /
 * admission / admission_reason / last_shown_at / pending_expires_at），否则重新展示时旧到期
 * 时刻可能落在过去 ⇒ 一进列表就过期。{@code seen_at} 保留（用户确实看过，避免重复提醒）。
 *
 * <h2>RED tooth（变异自检）</h2>
 * <p>把 V332 的 UPDATE 改成 no-op（不匹配 approved）⇒ 本测试红。
 */
@DisplayName("V332 · approved 行转 pending")
class PeerHeldApprovedMigrationTest {

    @Test
    @DisplayName("迁移后：approved → pending（清批准/展示标记，保留 seen_at）；其它终态不变")
    void approvedRowsBecomePending() throws Exception {
        Path dir = Files.createTempDirectory("peer-mig-approved-");
        Path db = dir.resolve("mig.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?journal_mode=WAL&busy_timeout=5000");
        String location = migrationsLocation();

        // 1) 迁到 V331（V332 之前）：模拟旧版本库。
        Flyway.configure()
            .dataSource(ds)
            .locations(location)
            .baselineOnMigrate(true)
            .target(MigrationVersion.fromVersion("331"))
            .load()
            .migrate();

        // 2) 造历史数据：一条 approved 中间态 + 一条 accepted 终态（必须不动）。
        insertRow(ds, "peer-mig-approved", "approved", true);
        insertRow(ds, "peer-mig-accepted", "accepted", true);

        // 3) 升级到最新（应用 V332）。
        Flyway.configure()
            .dataSource(ds)
            .locations(location)
            .baselineOnMigrate(true)
            .load()
            .migrate();

        // 4) approved → pending，且中间态标记清空。
        assertThat(column(ds, "status", "peer-mig-approved")).isEqualTo("pending");
        assertThat(column(ds, "approved_at", "peer-mig-approved")).isNull();
        assertThat(column(ds, "admission", "peer-mig-approved")).isNull();
        assertThat(column(ds, "admission_reason", "peer-mig-approved")).isNull();
        assertThat(column(ds, "last_shown_at", "peer-mig-approved")).isNull();
        assertThat(column(ds, "pending_expires_at", "peer-mig-approved")).isNull();
        assertThat(column(ds, "seen_at", "peer-mig-approved"))
            .as("seen_at 保留（用户已看过，避免重复提醒）").isEqualTo("2026-11-20T00:00:00Z");

        // 5) 其它终态行不受影响。
        assertThat(column(ds, "status", "peer-mig-accepted")).isEqualTo("accepted");
        assertThat(column(ds, "approved_at", "peer-mig-accepted"))
            .as("非 approved 行的审计字段不得被迁移改写").isEqualTo("2026-11-20T00:00:00Z");
    }

    private static void insertRow(SQLiteDataSource ds, String messageId, String status,
                                  boolean withApprovalMarkers) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO peer_held_message (message_id, target_session_id, source_session_id, "
                     + "body, reason, status, created_at, seen_at, approved_at, admission, "
                     + "admission_reason, last_shown_at, pending_expires_at) "
                     + "VALUES (?, 'sess-mig-target', 'sess-mig-source', 'body', 'EXPLICIT_HOLD', ?, "
                     + "'2026-11-20T00:00:00Z', '2026-11-20T00:00:00Z', ?, ?, ?, ?, ?)")) {
            ps.setString(1, messageId);
            ps.setString(2, status);
            ps.setString(3, withApprovalMarkers ? "2026-11-20T00:00:00Z" : null);
            ps.setString(4, withApprovalMarkers ? "approved" : null);
            ps.setString(5, withApprovalMarkers ? "user-approved" : null);
            ps.setLong(6, withApprovalMarkers ? 1L : 0L);
            ps.setLong(7, withApprovalMarkers ? 1L : 0L);
            ps.executeUpdate();
        }
    }

    private static Object column(SQLiteDataSource ds, String column, String messageId)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM peer_held_message WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("行必须存在：" + messageId).isTrue();
                return rs.getObject(1);
            }
        }
    }

    private static String migrationsLocation() {
        String override = System.getProperty("adf.migrations");
        return override == null || override.isBlank()
            ? "filesystem:src/main/resources/db/migration" : override;
    }
}

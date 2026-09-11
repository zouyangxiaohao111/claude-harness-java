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
 * [seq NULL 兜底 · 真引擎] 读侧排序片段 {@link MessageService#SEQ_NULLS_LAST_ORDER} 在
 * <b>真 SQLite</b>（生产同一驱动 sqlite-jdbc）上的排序后果。
 *
 * <p><b>WHY（CLAUDE.md 规则九 + 「真源」纪律）</b>：{@code NULL} 在 SQL 里的排序位置是实现定义细节
 * （SQLite 规则：NULL 视为最小 → ASC 排最前、DESC 排最后）。缺陷三的整个论证依赖这一点，
 * 故不能只对着 SQL 字符串做断言（那是 mock 层），必须在真引擎上把「修前 → 修后」的差异钉下来：
 * <ul>
 *   <li>修前裸 {@code ORDER BY seq ASC}：NULL <b>排最前</b>（冒充会话首条消息 → 进模型上下文顶部）；</li>
 *   <li>修后 {@code ORDER BY seq IS NULL, seq ASC}：NULL <b>排最后</b>（不遮挡真实首条、不被 boundary 静默剪掉）；</li>
 *   <li>DESC 侧若用 {@code seq IS NULL DESC}：NULL 排最前 = <b>冒充「最新」</b>（被本测试显式钉为「拒绝方案」）；
 *       生产用 {@code seq IS NULL, seq DESC} → NULL 落在 DESC 结果最末（= 最旧那头）。</li>
 * </ul>
 *
 * <p><b>测试与生产同源</b>：SQL 片段直接用 {@link MessageService#SEQ_NULLS_LAST_ORDER} 常量拼装
 * （不是在测试里另写一份字面量），保证「测试断言的口径」不会与「生产发出的片段」漂移。
 *
 * <p><b>RED 条件</b>：把 {@code SEQ_NULLS_LAST_ORDER} 改成别的字面量（例如 {@code "1"} / 去掉前置键）
 * → 本类与 {@link MessageServiceNullSeqReadTest} 同时红。
 */
@DisplayName("[seq NULL 兜底·真 SQLite] ORDER BY seq IS NULL 的实测排序后果")
class MessageSeqNullOrderSqlTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ASC：修前 NULL 排最前（缺陷）→ 修后 NULL 排最后；DESC：NULL 落末尾（绝不冒充最新）")
    void nullPlacementInAscAndDescOnRealSqlite() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbFile = tempDir.resolve("seq-null-order.db");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE t(id TEXT PRIMARY KEY, seq INTEGER)");
            }
            for (Object[] r : new Object[][]{{"a", 10L}, {"b", 11L}, {"dirty", null}}) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t(id, seq) VALUES(?,?)")) {
                    ps.setString(1, (String) r[0]);
                    if (r[1] == null) {
                        ps.setNull(2, java.sql.Types.INTEGER);
                    } else {
                        ps.setLong(2, (Long) r[1]);
                    }
                    ps.executeUpdate();
                }
            }

            // ① 修前（裸 seq ASC）：NULL 冒到最前 —— 这就是缺陷三「被送进模型上下文顶部」的机制
            assertThat(ids(conn, "SELECT id FROM t ORDER BY seq ASC"))
                .as("裸 seq ASC：NULL 排最前（缺陷现状，本批要修掉的）")
                .containsExactly("dirty", "a", "b");

            // ② 修后（生产片段 + seq ASC）：NULL 稳定推到末尾
            assertThat(ids(conn, "SELECT id FROM t ORDER BY " + MessageService.SEQ_NULLS_LAST_ORDER + " ASC, seq ASC"))
                .as("listBySession 的实测排序：NULL 在末尾（不遮挡真实首条、不被 boundary 静默剪掉）")
                .containsExactly("a", "b", "dirty");

            // ③ DESC 侧生产片段：NULL 落在 DESC 结果末尾（= 最旧那头），不进尾页冒充「最新」
            //   生产 SQL = `ORDER BY seq IS NULL, seq DESC`（前置键无显式方向 = ASC 语义）
            assertThat(ids(conn, "SELECT id FROM t ORDER BY " + MessageService.SEQ_NULLS_LAST_ORDER + ", seq DESC"))
                .as("listPageBySession(DESC) 的实测排序：NULL 在最后 → 不会被算进「最新 N 条」尾页")
                .containsExactly("b", "a", "dirty");

            // ④ 被否决的替代方案：SEQ_NULLS_LAST_ORDER + DESC 会把 NULL 顶成「最新」（固化为反例，防后来者改回）
            assertThat(ids(conn, "SELECT id FROM t ORDER BY " + MessageService.SEQ_NULLS_LAST_ORDER + " DESC, seq DESC"))
                .as("已否决方案（seq IS NULL DESC）：NULL 冒充最新（生产禁止使用）")
                .containsExactly("dirty", "b", "a");
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
}

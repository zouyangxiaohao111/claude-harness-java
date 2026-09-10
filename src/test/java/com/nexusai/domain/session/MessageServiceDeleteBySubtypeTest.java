package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SM/compact 对齐 CC · 先插后删] {@code MessageService.deleteBySessionAndSubtype} 的<b>真实 QueryWrapper</b> 覆盖。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：本方法是 §14 hook_additional_context
 * 「先插后删（覆盖式写）」的删除半边 —— DB 恒 1 条的唯一保证。既有测试（LlmAgentLoopHookAdditionalContextPersistChainTest
 * 的 {@code InMemoryMessageService}）<b>自实现</b>了 row 过滤（{@code sessionId.equals(...) && subtype.equals(...)
 * && !excludeId.equals(...)}），生产 SQL 其实零覆盖：若生产 QueryWrapper 漏掉三个条件里的任意一个，
 * 后果分别是：
 * <ul>
 *   <li>漏 {@code session_id} → 删掉<b>其它会话</b>的 hook 行（跨会话数据损坏）；</li>
 *   <li>漏 {@code subtype} → 删掉本会话<b>其它 subtype</b> 的消息（误删工具结果/boundary）；</li>
 *   <li>漏 {@code id != excludeId} → 把<b>刚插入的新份自己</b>删掉 ⇒ DB 变 0 条（永久丢注入，
 *       正是「先插后删」要消除的丢份窗口）；</li>
 *   <li>漏 null/blank 早退 → 空 sessionId/subtype 拼出 {@code session_id = ''} 之类的裸条件。</li>
 * </ul>
 *
 * <p><b>RED 条件</b>：生产删除条件删掉任意一项 → 对应 SQL 断言红；把 3 参重载退化成 2 参
 * （不排除新份 id）→ 「排除新份」断言红。
 */
@DisplayName("[先插后删] MessageService.deleteBySessionAndSubtype 真实 QueryWrapper = sessionId + subtype + 排除新份 id")
class MessageServiceDeleteBySubtypeTest {

    private static final String SESSION = "sess-delsub01";
    private static final String SUBTYPE = "hook_additional_context";
    private static final String NEW_ID = "hook-new-id";

    private MessageService service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", mock(SessionMapper.class));
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
    }

    private QueryWrapper captureDelete() {
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).deleteByQuery(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("[三条件] 3 参重载下发的 SQL 同时含 session_id + subtype + 排除新份 id（不得只断言被调用过）")
    void threeArgOverload_sqlContainsAllThreeConditions() {
        when(messageMapper.deleteByQuery(any())).thenReturn(1);

        service.deleteBySessionAndSubtype(SESSION, SUBTYPE, NEW_ID);

        QueryWrapper qw = captureDelete();
        String sql = String.valueOf(qw.toSQL());
        // 注：QueryWrapper.toSQL() 不带表名（mybatis-flex 执行期才由 mapper entity 解析 SELECT/DELETE FROM messages），
        //   故断言落在 WHERE 条件面。eq/ne 条件值在此渲染为字面量（实测 'sess-delsub01' / 'hook-new-id'）。
        assertThat(sql)
            .as("① 会话隔离条件（漏掉 → 删其它会话的 hook 行）")
            .contains("session_id = 'sess-delsub01'");
        assertThat(sql)
            .as("② subtype 条件（漏掉 → 删本会话其它 subtype 消息）")
            .contains("subtype = 'hook_additional_context'");
        assertThat(sql)
            .as("③ 排除新份 id 条件（漏掉 → 把刚插入的新份删掉 ⇒ DB 0 条丢份）")
            .contains("id != 'hook-new-id'");
        assertThat(sql)
            .as("三条件以 AND 串联（缺一则删除面失控）")
            .contains(" AND ");
    }

    @Test
    @DisplayName("[行为·真 SQLite] 3 参重载下发条件在真实 SQLite 上恰好删掉本会话同 subtype 其它旧份（新份/他会话/他 subtype 均保留）")
    void threeArgOverload_deletesExactlyOtherOldRowsOnRealSqlite() throws Exception {
        // WHY（CLAUDE.md 规则九）：mock 只证明「拼了哪些条件」，不证明「条件的实际杀伤范围」。
        //   本测试把生产 QueryWrapper 的真实 WHERE 子句喂给真 SQLite（与生产同引擎），
        //   用 5 行造出「该删 / 不该删」的四类边界，钉死覆盖式写「DB 恒 1 条」不变量：
        //     - 本会话同 subtype 旧份 ×2 → 必须删（否则副本累积）
        //     - 刚插入的新份 → 必须留（否则 DB 0 条丢份）
        //     - 本会话其它 subtype → 必须留（否则误删 boundary/工具行）
        //     - 其它会话同 subtype → 必须留（否则跨会话数据损坏）
        when(messageMapper.deleteByQuery(any())).thenReturn(1);

        service.deleteBySessionAndSubtype(SESSION, SUBTYPE, NEW_ID);

        String sql = String.valueOf(captureDelete().toSQL());
        int whereIdx = sql.indexOf("WHERE");
        assertThat(whereIdx).as("生产条件包含 WHERE 子句").isGreaterThan(-1);
        String whereClause = sql.substring(whereIdx);

        Class.forName("org.sqlite.JDBC");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, subtype TEXT)");
                st.executeUpdate("INSERT INTO messages VALUES('hook-old-1','" + SESSION + "','" + SUBTYPE + "')");
                st.executeUpdate("INSERT INTO messages VALUES('hook-old-2','" + SESSION + "','" + SUBTYPE + "')");
                st.executeUpdate("INSERT INTO messages VALUES('" + NEW_ID + "','" + SESSION + "','" + SUBTYPE + "')");
                st.executeUpdate("INSERT INTO messages VALUES('boundary-1','" + SESSION + "','snip_boundary')");
                st.executeUpdate("INSERT INTO messages VALUES('other-sess-hook','sess-other','" + SUBTYPE + "')");
            }
            int deleted;
            try (java.sql.Statement st = conn.createStatement()) {
                deleted = st.executeUpdate("DELETE FROM messages " + whereClause);
            }
            assertThat(deleted)
                .as("恰好删 2 条 = 本会话同 subtype 的其它旧份（新份/他 subtype/他会话不在删除面内）")
                .isEqualTo(2);
            java.util.List<String> remaining = new java.util.ArrayList<>();
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT id FROM messages ORDER BY id")) {
                while (rs.next()) {
                    remaining.add(rs.getString("id"));
                }
            }
            assertThat(remaining)
                .as("新份保留（DB 恒 ≥1 条）+ 他 subtype/他会话行未被误删")
                .containsExactlyInAnyOrder(NEW_ID, "boundary-1", "other-sess-hook");
        }
    }

    @Test
    @DisplayName("[2 参重载] excludeId 为 null → 不下发排除条件（删全部同 subtype 行）")
    void twoArgOverload_noExcludeCondition() {
        when(messageMapper.deleteByQuery(any())).thenReturn(2);

        service.deleteBySessionAndSubtype(SESSION, SUBTYPE);

        String sql = String.valueOf(captureDelete().toSQL());
        assertThat(sql).as("仍含 session_id + subtype").contains("session_id").contains(SUBTYPE);
        assertThat(sql).as("2 参重载不排除任何 id（无 != 条件）").doesNotContain("!=");
    }

    @Test
    @DisplayName("[保命闸] sessionId/subtype 为 null 或空白 → 0 行删除且<b>一条 SQL 都不下发</b>")
    void blankArgs_shortCircuit_noSqlAtAll() {
        // WHY: 空 sessionId/subtype 若继续下发，WHERE 退化为「session_id = ''」甚至只剩 subtype →
        //   跨会话/全表删除。这是最坏情况（不可逆）。
        assertThat(service.deleteBySessionAndSubtype(null, SUBTYPE, NEW_ID)).isZero();
        assertThat(service.deleteBySessionAndSubtype(SESSION, null, NEW_ID)).isZero();
        assertThat(service.deleteBySessionAndSubtype("  ", SUBTYPE, NEW_ID)).isZero();
        assertThat(service.deleteBySessionAndSubtype(SESSION, "  ", NEW_ID)).isZero();
        verify(messageMapper, never()).deleteByQuery(any());
    }

    @Test
    @DisplayName("[行为] excludeId 空白 → 等同 2 参（不排除）；非空白才排除")
    void blankExcludeId_behavesAsTwoArg() {
        when(messageMapper.deleteByQuery(any())).thenReturn(1);

        service.deleteBySessionAndSubtype(SESSION, SUBTYPE, "   ");

        String sql = String.valueOf(captureDelete().toSQL());
        assertThat(sql).as("空白 excludeId → 等同于 2 参重载，不发排除条件").doesNotContain("!=");
        assertThat(sql).as("会话/subtype 条件仍在").contains("session_id").contains(SUBTYPE);
    }
}

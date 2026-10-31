package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.session.MessageService.TurnPage;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [turn-paging] {@link MessageService#listMessagesByTurns} 查询策略单测（mock mapper，不启 Spring）。
 *
 * <p><b>WHY（规则九）</b>：轮边界算术已由 {@link TurnPagingTest} 纯测覆盖；本类只钉「落在 SQL 上的三件事」
 * —— 它们是巨型轮缺陷能否真正修好的分水岭：
 * <ul>
 *   <li><b>行区间下推</b>：{@code seq >= from} / {@code seq < to}（切点 = 下一个用户提问的 seq，不含该行）；</li>
 *   <li><b>绝无 LIMIT</b>：一旦有人「顺手」加条数上限，巨型轮又会被截断（= 缺陷原样复发）；</li>
 *   <li><b>游标非法必须 400</b>：静默返回空页会让前端误判「已经到顶」而不再往上翻。</li>
 * </ul>
 */
@DisplayName("[turn-paging] MessageService.listMessagesByTurns（seq 区间下推 + 无 LIMIT + 游标失败语义）")
class MessageServiceTurnsTest {

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    private static MessageRecord anchor(String id, long seq) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setSeq(seq);
        m.setRole("user");
        return m;
    }

    private static MessageRecord row(String id, long seq, String role) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setSeq(seq);
        m.setRole(role);
        m.setCreatedAt("2026-10-29T00:00:00Z");
        return m;
    }

    private static List<String> ids(TurnPage page) {
        return page.messages().stream().map(ChatMessageDto::id).toList();
    }

    /** 3 轮锚点：u1@10 / u2@100 / u3@400（与 TurnPagingTest 同形，便于跨层对照）。 */
    private static List<MessageRecord> anchors3() {
        return List.of(anchor("msg-u1", 10L), anchor("msg-u2", 100L), anchor("msg-u3", 400L));
    }

    @Test
    @DisplayName("最近 2 轮：行区间 = [u2.seq, ∞)（无 LIMIT），返回整段 ASC，hasMore=true")
    void latestTwoTurns_pushesSeqRangeWithoutLimit() {
        when(messageMapper.selectListByQuery(any())).thenReturn(
            // ① 锚点扫描
            anchors3(),
            // ② 取行：u2 起（含轮内 tool）
            List.of(row("msg-u2", 100L, "user"), row("msg-tool-1", 101L, "tool"),
                row("msg-u3", 400L, "user")));

        TurnPage page = service.listMessagesByTurns("sess-1", null, 2);

        assertThat(ids(page)).containsExactly("msg-u2", "msg-tool-1", "msg-u3");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.firstTurnId()).isEqualTo("msg-u2");
        assertThat(page.lastTurnId()).isEqualTo("msg-u3");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper, times(2)).selectListByQuery(captor.capture());

        String anchorSql = captor.getAllValues().get(0).toSQL();
        assertThat(anchorSql)
            .as("锚点扫描只读最小列集（不读 content / reasoning）")
            .doesNotContain("content");
        assertThat(anchorSql)
            .as("锚点按位置键排序（索引可用，无 TEMP B-TREE）")
            .contains("seq ASC NULLS LAST");

        String pageSql = captor.getAllValues().get(1).toSQL();
        assertThat(pageSql)
            .as("下界 = 窗口首轮 u2 的 seq（含起点用户消息本身）")
            .contains("seq >= 100");
        assertThat(pageSql)
            .as("窗口到会话末尾 ⇒ 无上界（不得凭空造一个 seq < 上限而丢掉刚写入的行）")
            .doesNotContain("seq <");
        assertThat(pageSql)
            .as("⛔ 绝不加 LIMIT：加了就等于把巨型轮截断（缺陷原样复发）")
            .doesNotContain("LIMIT");
        assertThat(pageSql)
            .as("按 seq 位置序 ASC（与 /messages/page 同口径）")
            .contains("seq ASC NULLS LAST");
    }

    @Test
    @DisplayName("游标 u3：行区间 = [u2.seq, u3.seq)（上界切在下一个用户提问，不含 u3 本身）")
    void cursor_pushesHalfOpenRange() {
        when(messageMapper.selectListByQuery(any())).thenReturn(
            anchors3(),
            List.of(row("msg-u1", 10L, "user"), row("msg-u2", 100L, "user")));

        TurnPage page = service.listMessagesByTurns("sess-1", "msg-u3", 2);

        assertThat(ids(page)).containsExactly("msg-u1", "msg-u2");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.firstTurnId()).isEqualTo("msg-u1");
        assertThat(page.lastTurnId()).isEqualTo("msg-u2");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper, times(2)).selectListByQuery(captor.capture());
        String pageSql = captor.getAllValues().get(1).toSQL();
        // 窗口覆盖到首轮 ⇒ 无下界（首轮之前的无主行随本页返回，否则永远取不到）
        assertThat(pageSql).doesNotContain("seq >=");
        assertThat(pageSql).contains("seq < 400");
        assertThat(pageSql).doesNotContain("LIMIT");
    }

    @Test
    @DisplayName("流水线：firstTurnId 再传回来 = 上一页无重叠、无缺口（游标就是上一页的 firstTurnId）")
    void cursorRoundTrip_noOverlapNoGap() {
        // 第 1 页：最近 2 轮 = [u2, u3]；第 2 页：beforeTurnId=u2 → [u1]（更早的只剩 1 轮）
        when(messageMapper.selectListByQuery(any())).thenReturn(
            anchors3(),
            List.of(row("msg-u2", 100L, "user"), row("msg-u3", 400L, "user")));
        TurnPage p1 = service.listMessagesByTurns("sess-1", null, 2);
        assertThat(p1.firstTurnId()).isEqualTo("msg-u2");

        when(messageMapper.selectListByQuery(any())).thenReturn(
            anchors3(),
            List.of(row("msg-u1", 10L, "user")));
        TurnPage p2 = service.listMessagesByTurns("sess-1", p1.firstTurnId(), 2);

        assertThat(ids(p2)).doesNotContainAnyElementsOf(ids(p1)); // 无重叠（u2 不在第 2 页）
        assertThat(p2.lastTurnId()).isEqualTo("msg-u1");
        assertThat(p2.hasMore()).isFalse();                       // 到顶
    }

    @Test
    @DisplayName("beforeTurnId 不属于该会话 → ValidationException（400），且不发行区间查询")
    void cursorNotInSession_throwsValidation_andSkipsRowQuery() {
        when(messageMapper.selectListByQuery(any())).thenReturn(anchors3());
        when(messageMapper.selectOneByQuery(any())).thenReturn(null);

        assertThatThrownBy(() -> service.listMessagesByTurns("sess-1", "msg-ghost", 2))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("msg-ghost")
            .hasMessageContaining("不存在");

        verify(messageMapper, times(1)).selectListByQuery(any()); // 只发了锚点扫描
    }

    @Test
    @DisplayName("beforeTurnId 存在但不是用户提问行 → ValidationException（400，区别于「不存在」）")
    void cursorNotATurn_throwsValidation() {
        when(messageMapper.selectListByQuery(any())).thenReturn(anchors3());
        when(messageMapper.selectOneByQuery(any())).thenReturn(row("msg-a2", 101L, "assistant"));

        assertThatThrownBy(() -> service.listMessagesByTurns("sess-1", "msg-a2", 2))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("不是用户提问行");
        verify(messageMapper, times(1)).selectListByQuery(any());
    }

    @Test
    @DisplayName("会话不存在 → NotFound（404），不查消息表")
    void missingSession_throwsNotFound() {
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.listMessagesByTurns("sess-nope", null, 2))
            .isInstanceOf(NotFoundException.class);
        verify(messageMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("sessionId 为空 → NotFound（不得让 eq(\"session_id\", null) 被静默丢弃成跨会话扫描）")
    void blankSessionId_throwsNotFound() {
        assertThatThrownBy(() -> service.listMessagesByTurns("  ", null, 2))
            .isInstanceOf(NotFoundException.class);
        verify(messageMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("无任何用户提问 → 空页 + hasMore=false，不发行区间查询")
    void noTurns_returnsEmptyPage() {
        // 只有 assistant 行 + 一条 subtype=cron 的「伪 user 行」⇒ 会话里没有任何轮起点
        MessageRecord cron = row("msg-cron", 2L, "user");
        cron.setSubtype("cron");
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(row("msg-a1", 1L, "assistant"), cron));

        TurnPage page = service.listMessagesByTurns("sess-1", null, 2);

        assertThat(page.messages()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.firstTurnId()).isNull();
        assertThat(page.lastTurnId()).isNull();
        verify(messageMapper, times(1)).selectListByQuery(any());
    }
}

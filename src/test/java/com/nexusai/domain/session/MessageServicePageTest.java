package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.domain.session.MessageService.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [window-paging] MessageService.listPageBySession 分页单测 · 对齐 deepseek 有界历史窗口。
 *
 * <p><b>WHY (CLAUDE.md 规则 9)</b>：前端主通道从「全量 GET /messages」改为「page 尾页 + 顶部加载更早」。
 * 变异点：
 * <ul>
 *   <li>返回顺序不是 created_at ASC（前端顺序渲染会乱）→ 红</li>
 *   <li>hasMore 判定错（多查 1 条误截/漏）→ 红</li>
 *   <li>beforeMessageId 不游标到该消息之前（仍含游标消息/忽略更早）→ 红</li>
 *   <li>session/游标不存在不抛 NotFound（前端拿 200 空列表当「无更早」假象）→ 红</li>
 *   <li>limit&lt;=0 不回落默认 50 → 红</li>
 *   <li>游标 seq 为 NULL 时不显式失败（mybatis-flex IGNORE_NULL 静默丢掉该条件 → 退化成重复尾页、
 *       翻页永久卡死且无日志）→ 红</li>
 * </ul>
 */
@DisplayName("[window-paging] MessageService.listPageBySession（page 尾页 + beforeMessageId 前页）")
class MessageServicePageTest {

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
        // session 存在（listPageBySession 校验）
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        // 每条消息 toDto 的 tool_calls 查询 → 空（简化）
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    /** 构造 DB 行：created_at 用可排序 ISO；seq = id 数字后缀（游标按 seq 位置键）；
     *  role=user（尾页补算需 assistant usage 才会挂快照，user 恒不触发）。 */
    private static MessageRecord rec(String id, String createdAt) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setRole("user");
        m.setContent("内容-" + id);
        m.setCreatedAt(createdAt);
        // [seq 排序键] m<N> → seq=N（游标 pivot.getSeq() 需非 null；排序由 mock 返回序决定，不受 SQL 影响）
        //   seq 现为雪花 long → 测试内按可读的小数值构造，仅用于游标 SQL 断言与位置示意。
        m.setSeq(Long.parseLong(id.substring(1)));
        return m;
    }

    private static List<String> ids(PageResult pr) {
        return pr.messages().stream().map(ChatMessageDto::id).toList();
    }

    private List<MessageRecord> desc(int fromId, int count) {
        // 模拟 DB 按 created_at DESC 返回 fromId..fromId-count+1
        List<MessageRecord> out = new ArrayList<>();
        for (int i = fromId; i > fromId - count; i--) {
            out.add(rec("m" + i, "2026-01-" + (i < 10 ? "0" + i : i) + "T00:00:00Z"));
        }
        return out;
    }

    @Test
    @DisplayName("尾页无更多：DESC 恰 limit 条 → messages created_at ASC（m1..m50）、hasMore=false、不查游标")
    void tailPage_noMore_returnsAscNoMore() {
        when(messageMapper.selectListByQuery(any())).thenReturn(desc(50, 50));

        PageResult pr = service.listPageBySession("sess-1", null, MessageService.DEFAULT_PAGE_SIZE);

        assertThat(ids(pr)).isEqualTo(ints(1, 50));
        assertThat(pr.hasMore()).isFalse();
        verify(messageMapper, never()).selectOneByQuery(any());
    }

    @Test
    @DisplayName("尾页还有更多：DESC 恰 limit+1 条 → 返回最新 limit 条 ASC、hasMore=true")
    void tailPage_hasMore_whenOneExtra() {
        when(messageMapper.selectListByQuery(any())).thenReturn(desc(51, 51)); // 更早还有 m0..m? → DESC 51 条多 1

        PageResult pr = service.listPageBySession("sess-1", null, 50);

        assertThat(pr.hasMore()).isTrue();
        // DESC 取前 50 = 最新 m51..m2（丢更早 m1 那侧的多余）→ reverse → m2..m51
        assertThat(ids(pr)).isEqualTo(ints(2, 51));
    }

    @Test
    @DisplayName("beforeMessageId 前页：游标到该消息之前，返回更早一页 ASC（游标消息自身排除）")
    void beforePage_returnsOlderAsc() {
        MessageRecord pivot = rec("m50", "2026-01-50T00:00:00Z");
        when(messageMapper.selectOneByQuery(any())).thenReturn(pivot);
        when(messageMapper.selectListByQuery(any())).thenReturn(desc(49, 10));

        PageResult pr = service.listPageBySession("sess-1", "m50", 50);

        verify(messageMapper).selectOneByQuery(any()); // 解析游标 seq
        assertThat(ids(pr)).isEqualTo(ints(40, 49));   // m49..m40 ASC（m50 之前）
        assertThat(pr.hasMore()).isFalse();
    }

    @Test
    @DisplayName("[seq 排序键] 游标用 seq（非 created_at）：SQL 条件 = seq < pivot.seq + ORDER BY seq DESC")
    void beforePage_cursorUsesSeqNotCreatedAt() {
        // WHY（CLAUDE.md 规则九）：compact append-only 落库把 kept 段「重挂 seq」到 boundary 之后，
        //   created_at 保持原值 → 若游标仍按 created_at，翻页会对重挂后的行重复/丢行（位置与时间序错位）。
        //   RED：游标退回 created_at → SQL 不含 "seq < 50" → 红。
        MessageRecord pivot = rec("m50", "2026-01-50T00:00:00Z"); // seq=50
        when(messageMapper.selectOneByQuery(any())).thenReturn(pivot);
        when(messageMapper.selectListByQuery(any())).thenReturn(desc(49, 10));

        service.listPageBySession("sess-1", "m50", 50);

        org.mockito.ArgumentCaptor<QueryWrapper> captor = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).selectListByQuery(captor.capture());
        String sql = captor.getValue().toSQL();
        assertThat(sql)
            .as("游标 = pivot 当前 seq（位置键）")
            .contains("seq < 50");
        assertThat(sql)
            .as("分页按 seq 位置序（不是 created_at 时间序）")
            .doesNotContain("created_at <");
    }

    @Test
    @DisplayName("[fail loud] 游标消息 seq 为 NULL → IllegalStateException，且绝不发出「无 seq 条件」的 SQL")
    void cursorSeqNull_throwsIllegalState_andNeverQueriesWithoutSeqCondition() {
        // WHY（CLAUDE.md 规则九/十二）：mybatis-flex 的 QueryColumnBehavior 默认 ignoreFunction=IGNORE_NULL，
        //   value==null 的条件会被<b>静默丢弃</b>：lt("seq",(Object)null) 生成的 SQL 与「不带游标」完全一致
        //   → 带 beforeMessageId 的请求退化成再取一次尾页 → 前端 handleLoadOlder 拿到重复页（按 id 去重后
        //   fresh=0）→ hasMore 恒 true 但游标永不前进（翻页永久卡死），且日志无任何 warn/error。
        //   seq 为空 = V70 位置键未落（数据异常），必须显式失败而不是退化成「无游标」。
        // RED：删掉 null 检查（回到 qw.lt("seq", pivot.getSeq())）→ 不抛错且 selectListByQuery 被调用 → 红。
        MessageRecord pivot = rec("m50", "2026-01-50T00:00:00Z");
        pivot.setSeq(null); // 位置键未落（V70 前遗留行 / 写入路径漏落）
        when(messageMapper.selectOneByQuery(any())).thenReturn(pivot);

        assertThatThrownBy(() -> service.listPageBySession("sess-1", "m50", 50))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("seq")
            .hasMessageContaining("sess-1")
            .hasMessageContaining("m50");

        verify(messageMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("[fail loud] sessionId 为空 → NotFound（不得让 eq(\"session_id\", null) 被静默丢弃成跨会话查询）")
    void blankSessionId_throwsNotFound() {
        // WHY：session_id 条件若被 IGNORE_NULL 丢弃 → 分页退化成跨会话扫描（越权 + 顺序错乱）。
        // RED：删掉空值守卫 → mock sessionMapper 返回 SessionRecord → 不抛错 → 红。
        assertThatThrownBy(() -> service.listPageBySession("  ", null, 50))
            .isInstanceOf(NotFoundException.class);
        verify(messageMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("limit<=0 回落默认 50：仍多查 1 条判 hasMore")
    void invalidLimit_fallsBackDefault() {
        when(messageMapper.selectListByQuery(any())).thenReturn(desc(51, 51));

        PageResult pr = service.listPageBySession("sess-1", null, 0);

        assertThat(pr.messages()).hasSize(50);
        assertThat(pr.hasMore()).isTrue();
    }

    @Test
    @DisplayName("session 不存在 → NotFound（页面不应假装空列表）")
    void missingSession_throwsNotFound() {
        when(sessionMapper.selectOneById(any())).thenReturn(null);
        assertThatThrownBy(() -> service.listPageBySession("sess-nope", null, 50))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("beforeMessageId 不在该会话 → NotFound")
    void beforeMessageNotInSession_throwsNotFound() {
        when(messageMapper.selectOneByQuery(any())).thenReturn(null);
        assertThatThrownBy(() -> service.listPageBySession("sess-1", "ghost", 50))
            .isInstanceOf(NotFoundException.class);
    }

    private static List<String> ints(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add("m" + i);
        }
        return out;
    }
}

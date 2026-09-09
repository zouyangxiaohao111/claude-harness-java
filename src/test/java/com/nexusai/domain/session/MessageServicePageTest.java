package com.nexusai.domain.session;

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

    /** 构造 DB 行：created_at 用可排序 ISO；role=user（尾页补算需 assistant usage 才会挂快照，user 恒不触发）。 */
    private static MessageRecord rec(String id, String createdAt) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setRole("user");
        m.setContent("内容-" + id);
        m.setCreatedAt(createdAt);
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

        verify(messageMapper).selectOneByQuery(any()); // 解析游标 created_at
        assertThat(ids(pr)).isEqualTo(ints(40, 49));   // m49..m40 ASC（m50 之前）
        assertThat(pr.hasMore()).isFalse();
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

package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.NotFoundException;
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

import java.time.OffsetDateTime;
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
 * [gap28 · 性能改造] MessageService.trimSessionAfter 意图测试 · 对话裁剪（删 pivot 起全部后续消息）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: CC rewindConversationTo（REPL.tsx:3661-3699）在<b>前端内存</b>
 * {@code setMessages(prev.slice(0, messageIndex))} 裁剪 —— Java 无前端内存，裁剪必须落 DB
 * （模型上下文来自 DB transcript，LlmAgentLoop 经 listRawForTranscript 加载），本方法即 DB 等价物。
 *
 * <p><b>本次改造</b>: 由「全量读出 + 逐条重插」（O(N)）改为「定位 seq + 区间删除」（O(log N) + DELETE），
 * 语义（删哪些 / 留哪些 / 轮序）必须<b>逐条等价</b>。变异点：
 * <ul>
 *   <li>删 pivot 区间错误（保留 pivot 而非丢弃）→ 模型上下文含 pivot → 红</li>
 *   <li>pivot 定位跨会话误匹配（用 getById 而非限定 session_id）→ 错删另一会话消息 → 红</li>
 *   <li>pivot / session 不存在静默返回 → 前端无法区分「已删」与「未删」→ 红</li>
 *   <li>tool_calls 未级联清 → 孤儿 tool_call 残留 → 红</li>
 *   <li>区间谓词漏掉 {@code seq IS NULL} → NULL 行（读侧 NULLS LAST 排在 pivot 之后）残留 → 红</li>
 *   <li>退回「删全表 + 重插」（保留段被重写）→ 会话万级消息时裁剪要跑数十秒 → 红（本用例断言不重插）</li>
 * </ul>
 */
class MessageServiceTrimAfterTest {

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;
    private ToolCallMapper toolCallMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        // session 存在（session 校验）
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        // 每条消息 toDto 的 tool_calls 查询 → 空（慢路径兜底用例才需要）
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    private static MessageRecord message(String id, String sessionId, String content) {
        return message(id, sessionId, content, null);
    }

    private static MessageRecord message(String id, String sessionId, String content, Long seq) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setAuthor(null);
        m.setContent(content);
        m.setReasoning(null);
        // [reasoningDurationMs] V41 列 fixture 值（round-trip 保真断言用；写读两侧贯穿）
        m.setReasoningDurationMs(1200L);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        m.setCreatedAt(OffsetDateTime.now().toString());
        m.setSeq(seq);
        return m;
    }

    /** pivot 定位命中（一次查询取 seq） */
    private void givenPivot(MessageRecord pivot) {
        when(messageMapper.selectOneByQuery(any())).thenReturn(pivot);
    }

    @Test
    @DisplayName("裁剪 = 区间删除（seq >= pivot ∪ seq IS NULL）：不再全量重写，返回删掉的条数")
    void trim_deletesRangeWithoutRewrite() {
        // WHY: 改造前是「全量读出 + 删全表 + 逐条重插」——会话 2.5 万条消息时该 DELETE 要跑数秒到
        //   数十秒，前端「编辑重发」卡在「发送中」。变异点：改回全量重写 → 本用例的
        //   「零 insert + 区间 DELETE 谓词」断言红。
        givenPivot(message("m2", "sess-1", "第二问", 200L));
        when(messageMapper.deleteByQuery(any())).thenReturn(2);

        int deleted = service.trimSessionAfter("sess-1", "m2");

        assertThat(deleted).as("返回本次裁掉的消息条数（pivot 起全部，含 pivot 自身）").isEqualTo(2);
        // 保留段<b>原地保留</b>：不重插任何消息（改造前会 insert N 条）
        verify(messageMapper, never()).insert(any(MessageRecord.class));
        // 区间 DELETE 谓词：pivot 及其后 = {seq >= pivotSeq} ∪ {seq IS NULL}
        //   （seq IS NULL 必须在内：读侧 seq ASC NULLS LAST ⇒ NULL 行恒在 pivot 之后）
        ArgumentCaptor<QueryWrapper> msgCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).deleteByQuery(msgCaptor.capture());
        String msgSql = String.valueOf(msgCaptor.getValue().toSQL());
        assertThat(msgSql)
            .as("区间删除谓词必须含 seq >= 边界与 seq IS NULL 两段")
            .contains("seq >=")
            .contains("IS NULL");
        assertThat(msgSql)
            .as("区间删除必须限定 session_id（不得跨会话删）")
            .contains("session_id =");
    }

    @Test
    @DisplayName("tool_calls 子表随区间一并清理（按 message_id 子查询，tool_calls 表无 session_id 列）")
    void trim_cleansToolCalls() {
        // WHY: tool_calls 是唯一以 message_id 为外键的子表（V1 schema）。不显式清 → 孤儿 tool_call
        //   残留（FK CASCADE 需 PRAGMA foreign_keys=on 才生效，显式删更稳，与 replaceSessionMessages
        //   口径一致）。变异点：不删 tool_calls / 按 session_id 删（该列不存在 → SQLITE_ERROR）→ 红。
        givenPivot(message("m2", "sess-1", "第二问", 200L));
        when(messageMapper.deleteByQuery(any())).thenReturn(2);

        service.trimSessionAfter("sess-1", "m2");

        ArgumentCaptor<QueryWrapper> tcCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(toolCallMapper).deleteByQuery(tcCaptor.capture());
        String tcSql = String.valueOf(tcCaptor.getValue().toSQL());
        assertThat(tcSql)
            .as("tool_calls 删除必须经 message_id 子查询（无 session_id 列）")
            .contains("message_id IN (SELECT id FROM messages WHERE session_id =");
        assertThat(tcSql)
            .as("tool_calls 的删除范围必须与消息删除范围一致（含 seq IS NULL 段）")
            .contains("seq >=")
            .contains("IS NULL");
    }

    @Test
    @DisplayName("pivot 是首条 → 区间删除覆盖全会话（谓词以 pivot 的 seq 为下界，条数 = 全删）")
    void trim_pivotFirst_deletesAll() {
        // WHY: 裁剪到第一条消息 → 之前无消息 → 全删（CC slice(0,0)）。变异点：保留首条 → 红。
        givenPivot(message("m1", "sess-1", "第一问", 100L));
        when(messageMapper.deleteByQuery(any())).thenReturn(3);

        int deleted = service.trimSessionAfter("sess-1", "m1");

        assertThat(deleted).isEqualTo(3);
        verify(messageMapper, never()).insert(any(MessageRecord.class));
    }

    @Test
    @DisplayName("pivot 不存在 → NotFoundException（404），不删任何消息")
    void trim_pivotMissing_throwsNotFound() {
        // WHY: 前端需区分「已删」与「没删成」；pivot 不存在静默返回会掩盖前端误传。
        //   变异点：未抛 → 前端当成功处理 → 红。
        givenPivot(null);

        assertThatThrownBy(() -> service.trimSessionAfter("sess-1", "ghost"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
        verify(messageMapper, never()).deleteByQuery(any());
        verify(toolCallMapper, never()).deleteByQuery(any());
    }

    @Test
    @DisplayName("session 不存在 → NotFoundException（404），不执行删除")
    void trim_sessionMissing_throwsNotFound() {
        // WHY: 会话已删/非法 id 时裁剪无意义；静默返回会让前端误以为已裁剪。
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.trimSessionAfter("ghost-session", "m1"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost-session");
        verify(messageMapper, never()).deleteByQuery(any());
        verify(messageMapper, never()).selectOneByQuery(any());
    }

    @Test
    @DisplayName("pivot 必须命中当前会话消息（跨会话同 id 不误删：定位查询限定 session_id）")
    void trim_pivotOnlyMatchesCurrentSession() {
        // WHY: pivot 定位必须在「当前会话」内匹配（改造前 = listRawForTranscript 结果集内按 id 匹配；
        //   改造后 = 同一条 WHERE 的等价投影：session_id + id）。消息 id 可能跨会话重复，
        //   getById 会命中另一会话消息 → 错删。变异点：改用 getById / 去掉 session_id 条件 → 红。
        givenPivot(null); // 本会话内查不到 m2（另一会话才有 id=m2 的消息）→ 必须 404

        assertThatThrownBy(() -> service.trimSessionAfter("sess-1", "m2"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("m2");
        verify(messageMapper, never()).deleteByQuery(any());

        // 定位查询本身必须带 session_id 条件（防止实现漂移成 getById）
        ArgumentCaptor<QueryWrapper> qwCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).selectOneByQuery(qwCaptor.capture());
        String sql = String.valueOf(qwCaptor.getValue().toSQL());
        assertThat(sql).contains("session_id").contains("id");
    }

    @Test
    @DisplayName("[慢路径兜底] pivot 行 seq 为空（位置键未落 = 数据异常）→ 回落全量重写，语义与改造前一致")
    void trim_pivotSeqNull_fallsBackToFullRewrite() {
        // WHY: 读侧排序 seq ASC NULLS LAST 下 NULL 行之间相对序未定义 ⇒ 「pivot（NULL 行）及其后」
        //   无法用区间谓词表达。此时必须走全量重写（改造前语义），不能误删/漏删。
        //   变异点：把 NULL 行当 seq=0 处理（会把 NULL 行全删）→ 本用例断言的保留条数红。
        givenPivot(message("m2", "sess-1", "第二问", null));
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            message("m1", "sess-1", "第一问", 100L),
            message("m2", "sess-1", "第二问", null),
            message("m3", "sess-1", "第三问", null)));

        int deleted = service.trimSessionAfter("sess-1", "m2");

        // keep = [m1]（pivot 之前）→ 删 2 条
        assertThat(deleted).isEqualTo(2);
        // 慢路径 = 全量重写：保留段被重插 1 条
        verify(messageMapper, times(1)).insert(any(MessageRecord.class));
        // 慢路径走 replaceSessionMessages 的「删全表」（谓词里没有 seq 边界 ⇒ 不是区间删除）
        ArgumentCaptor<QueryWrapper> msgCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).deleteByQuery(msgCaptor.capture());
        assertThat(String.valueOf(msgCaptor.getValue().toSQL()))
            .as("慢路径是删全表重插（无 seq 边界），不是区间删除")
            .doesNotContain("seq >=");
    }
}

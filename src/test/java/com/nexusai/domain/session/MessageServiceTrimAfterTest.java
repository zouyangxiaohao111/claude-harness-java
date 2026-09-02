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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [gap28] MessageService.trimSessionAfter 意图测试 · 对话裁剪（删 pivot 起全部后续消息）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: CC rewindConversationTo（REPL.tsx:3661-3699）在<b>前端内存</b>
 * {@code setMessages(prev.slice(0, messageIndex))} 裁剪 —— Java 无前端内存，裁剪必须落 DB
 * （模型上下文来自 DB transcript，LlmAgentLoop 经 listBySession 加载），本方法即 DB 等价物。
 * 变异点：
 * <ul>
 *   <li>删 pivot 区间错误（保留 pivot 而非丢弃）→ 模型上下文含 pivot → 红</li>
 *   <li>pivot 定位跨会话误匹配（用 getById 而非当前会话列表）→ 错删另一会话消息 → 红</li>
 *   <li>pivot / session 不存在静默返回 → 前端无法区分「已删」与「未删」→ 红</li>
 *   <li>tool_calls 未级联清 → 孤儿 tool_call 残留 → 红</li>
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
        // session 存在（listBySession / replaceSessionMessages 双重校验共用）
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        // 每条消息 toDto 的 tool_calls 查询 → 空（简化）
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    private static MessageRecord message(String id, String sessionId, String content) {
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
        return m;
    }

    private void givenMessages(List<MessageRecord> records) {
        when(messageMapper.selectListByQuery(any())).thenReturn(records);
    }

    @Test
    @DisplayName("trimSessionAfter 删 pivot 起全部（含 pivot）→ 返回 pivot 之前消息，DB 全量替换")
    void trim_keepsBeforePivot() {
        // WHY: CC slice(0, messageIndex)（REPL.tsx:3671）保留 pivot 之前、丢弃 pivot（含）及其后。
        //   变异点：保留 pivot 本身 → 返回列表多一条 → 红。
        givenMessages(List.of(
            message("m1", "sess-1", "第一问"),
            message("m2", "sess-1", "第二问"),
            message("m3", "sess-1", "第三问")));

        List<ChatMessageDto> kept = service.trimSessionAfter("sess-1", "m2");

        // 保留 [m1]，丢弃 [m2, m3]
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).id()).isEqualTo("m1");
        // 走「删全部 + 重插」路径（replaceSessionMessages）——tool_calls 显式级联清除。
        //   ⚠️ [裁剪 bug] tool_calls 表无 session_id 列（V1：id/message_id/...），必须按 message_id
        //   IN (该 session 的 messages.id) 子查询删，不能 eq("session_id")（SQLITE_ERROR no such column）。
        ArgumentCaptor<QueryWrapper> tcCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(toolCallMapper).deleteByQuery(tcCaptor.capture());
        String tcSql = String.valueOf(tcCaptor.getValue().toSQL());
        assertThat(tcSql)
            .as("tool_calls 删除必须经 message_id 子查询（无 session_id 列）")
            .contains("message_id IN (SELECT id FROM messages WHERE session_id =");
        // 裸删 "DELETE FROM tool_calls WHERE session_id = ?"（无子查询）→ no such column 500
        assertThat(tcSql.trim())
            .as("不得直接按 tool_calls.session_id 删（该列不存在）")
            .doesNotStartWith("DELETE FROM  WHERE session_id");
        verify(messageMapper).deleteByQuery(any());
        // 仅重插保留段（1 条）
        verify(messageMapper, times(1)).insert(any(MessageRecord.class));
    }

    @Test
    @DisplayName("pivot 是首条 → 返回空列表（全删，语义 = CC slice(0,0)）")
    void trim_pivotFirst_returnsEmpty() {
        // WHY: 裁剪到第一条消息 → 之前无消息 → 空列表（前端 setMessages([])）。
        //   变异点：返回非空 / 抛异常 → 前端无法展示空会话 → 红。
        givenMessages(List.of(
            message("m1", "sess-1", "第一问"),
            message("m2", "sess-1", "第二问")));

        List<ChatMessageDto> kept = service.trimSessionAfter("sess-1", "m1");

        assertThat(kept).isEmpty();
        verify(messageMapper, never()).insert(any(MessageRecord.class));
    }

    @Test
    @DisplayName("pivot 不存在 → NotFoundException（404），不删任何消息")
    void trim_pivotMissing_throwsNotFound() {
        // WHY: 前端需区分「已删」与「没删成」；pivot 不存在静默返回会掩盖前端误传。
        //   变异点：未抛 → 前端当成功处理 → 红。
        givenMessages(List.of(message("m1", "sess-1", "第一问")));

        assertThatThrownBy(() -> service.trimSessionAfter("sess-1", "ghost"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
        verify(messageMapper, never()).deleteByQuery(any());
    }

    @Test
    @DisplayName("session 不存在 → NotFoundException（404），不执行删除")
    void trim_sessionMissing_throwsNotFound() {
        // WHY: 会话已删/非法 id 时裁剪无意义；静默返回会让前端误以为已裁剪。
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.trimSessionAfter("ghost-session", "m1"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost-session");
        verify(messageMapper, never()).selectListByQuery(any());
    }

    @Test
    @DisplayName("pivot 必须命中当前会话消息列表（跨会话同 id 不误删）")
    void trim_pivotOnlyMatchesCurrentSession() {
        // WHY: pivot 定位用当前会话列表（listBySession :59-71）而非 getById —— 消息 id 可能跨会话
        //   重复，getById 会命中另一会话消息 → 错删。变异点：改用 getById → 本会话 pivot 不存在却
        //   定位成功（/ 或错误删除）→ 红。
        givenMessages(List.of(message("m1", "sess-1", "第一问")));

        // 另一会话也有 id=m2 的消息，但本会话列表无 m2 → 必须 404
        assertThatThrownBy(() -> service.trimSessionAfter("sess-1", "m2"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("m2");
    }

    @Test
    @DisplayName("裁剪后顺序保持 created_at ASC（重插保序，与 listBySession 一致）")
    void trim_preservesOrder() {
        // WHY: 重插 created_at=base.plusNanos(i) 保序（replaceSessionMessages :238）——裁剪后列表
        //   顺序必须与 listBySession 一致，否则前端 setMessages 顺序错乱。
        givenMessages(List.of(
            message("m1", "sess-1", "第一问"),
            message("m2", "sess-1", "第二问"),
            message("m3", "sess-1", "第三问"),
            message("m4", "sess-1", "第四问")));

        List<ChatMessageDto> kept = service.trimSessionAfter("sess-1", "m3");

        assertThat(kept).hasSize(2);
        assertThat(kept.get(0).id()).isEqualTo("m1");
        assertThat(kept.get(1).id()).isEqualTo("m2");
    }

    @Test
    @DisplayName("裁剪保留消息经 replaceSessionMessages 重插 + toDto 读回仍携带 reasoningDurationMs（V41 列 round-trip 保真）")
    void trim_keepsReasoningDurationMsRoundTrip() {
        // WHY (CLAUDE.md 规则 9 · 测试验证意图): reasoningDurationMs（V41 列）必须贯穿写侧
        //   （replaceSessionMessages）与读侧（toDto）。裁剪走「删全部 + 重插」路径——重插 rec 与
        //   归一化 DTO 若丢该字段，前端 setMessages（REPL.tsx:3673）后历史耗时丢失。变异点：
        //   replaceSessionMessages 不 set / toDto 不回填 → 读回 null → 红。
        givenMessages(List.of(
            message("m1", "sess-1", "第一问"),
            message("m2", "sess-1", "第二问")));

        List<ChatMessageDto> kept = service.trimSessionAfter("sess-1", "m2");

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).reasoningDurationMs())
            .as("裁剪后幸存消息经 replaceSessionMessages 重插 + toDto 读回必须保留 reasoningDurationMs（V41 列 round-trip）")
            .isEqualTo(1200L);
    }
}

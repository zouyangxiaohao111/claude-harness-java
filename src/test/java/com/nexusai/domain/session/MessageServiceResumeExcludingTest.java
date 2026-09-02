package com.nexusai.domain.session;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [fix-loop-resume-history] MessageService.listForResumeExcluding 意图测试 · loop 主路径恢复历史读取。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: loop 主路径 resume 时当前用户消息已被
 * {@code ChatController.createUserMessage} 落 DB，{@code listForResume} 会把它作为末条 →
 * SessionResumeDeserializer 误判 INTERRUPTED_PROMPT 并在其后 splice sentinel（破坏本轮回复）。
 * {@code listForResumeExcluding} 先排除在途用户消息再应用中断语义漏斗，使 history 末尾即上一轮
 * 真实终止状态（对齐 CC conversationRecovery.ts:485-512 loadConversationForResume 全量历史注入）。
 * 变异点：
 * <ul>
 *   <li>未排除当前用户消息 → deserializer splice sentinel（本轮回复被 sentinel 打断）→ 红</li>
 *   <li>排除后 deserializer 中断语义丢失（中断尾部不注入 Continue）→ resume 后"有问无答"→ 红</li>
 *   <li>excludeMessageId=null 与 listForResume 行为不一致 → 非流式测试路径回归 → 红</li>
 *   <li>session 仅当前消息仍注入（误判 resume）→ 全新会话上下文污染 → 红</li>
 * </ul>
 *
 * <p>纯单测：{@code new MessageService()} + {@link ReflectionTestUtils} 注入 mock mapper
 * （与 MessageServiceTrimAfterTest 同模式）；不破坏 {@code listBySession} 原始读取（DB 权威不变）。
 */
@DisplayName("[fix-loop-resume-history] listForResumeExcluding 排除在途用户消息 + 中断语义")
class MessageServiceResumeExcludingTest {

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
        // session 存在（listBySession 校验共用）
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        // 每条消息 toDto 的 tool_calls 查询 → 空（简化；断链 tool 用例单独 stub）
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    private static MessageRecord msg(String id, String role, String content) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setRole(role);
        m.setAuthor(null);
        m.setContent(content);
        m.setReasoning(null);
        // [reasoningDurationMs] V41 列 fixture 值（读侧 toDto 映射断言用）
        m.setReasoningDurationMs(1500L);
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
    @DisplayName("排除当前用户消息 → 历史末尾即上一轮真实终止态（无 sentinel 误插）")
    void excludeCurrentUserNoSentinel() {
        // WHY: DB=[user1, assistant1, user2(当前)]，若不过滤 user2 → deserializer 见末条 user →
        //   INTERRUPTED_PROMPT → splice sentinel（本轮回复被 sentinel 打断）。排除 user2 →
        //   末条 assistant1 → 完成态 → 无 sentinel。变异点：splice sentinel → 结果多一条 → 红。
        givenMessages(List.of(
            msg("u1", "user", "第一问"),
            msg("a1", "assistant", "第一答"),
            msg("u2", "user", "当前消息")));

        List<ChatMessageDto> history = service.listForResumeExcluding("sess-1", "u2");

        assertThat(history).extracting(ChatMessageDto::id)
            .as("排除当前用户消息后，历史只含上一轮真实消息（顺序保持 created_at ASC）")
            .containsExactly("u1", "a1");
        assertThat(history).noneMatch(m -> m.content() != null && m.content().contains("No response requested."))
            .as("末条为 assistant（完成态）→ 不得 splice 无响应 sentinel");
    }

    @Test
    @DisplayName("排除当前用户消息后中断尾部 tool_result → 仍注入 Continue（deserializer 语义保留）")
    void excludeCurrentStillDetectsInterruptedTurn() {
        // WHY: 中断 turn（工具中途被 kill）恢复后"有问无答"→ CC :213-224 追加 meta user Continue。
        //   排除当前用户消息不应对既存中断尾部失去该语义——否则 resume 后模型不续答。
        //   变异点：排除后跳过中断检测 → 无 Continue → 红。
        MessageRecord a1 = msg("a1", "assistant", null);
        MessageRecord tool = new MessageRecord();
        tool.setId("t1");
        tool.setSessionId("sess-1");
        tool.setRole("tool");
        tool.setAuthor(null);
        tool.setContent("工具输出");
        tool.setToolCallId("tc1");
        tool.setReasoning(null);
        tool.setReasoningDurationMs(1500L); // [reasoningDurationMs] V41 列（读侧 toDto 映射断言用）
        tool.setFinishReason(null);
        tool.setInputTokens(null);
        tool.setOutputTokens(null);
        tool.setCreatedAt(OffsetDateTime.now().toString());
        // a1 的 tool_call tc1（非终端工具名 Bash）
        ToolCallRecord tc = new ToolCallRecord();
        tc.setId("tc1");
        tc.setMessageId("a1");
        tc.setToolName("Bash");
        tc.setArguments("{}");
        tc.setResult(null);
        tc.setIsError(false);
        tc.setCreatedAt(OffsetDateTime.now().toString());
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of(tc));

        givenMessages(List.of(
            msg("u1", "user", "先做点什么"),
            a1,
            tool,
            msg("u2", "user", "当前消息")));

        List<ChatMessageDto> history = service.listForResumeExcluding("sess-1", "u2");

        assertThat(history)
            .as("排除当前用户消息后，history=[u1, a1, tool]，且中断尾部注入 meta user Continue")
            .anyMatch(m -> m.role() == Role.user && m.isMeta()
                && "Continue from where you left off.".equals(m.content()));
    }

    @Test
    @DisplayName("excludeMessageId=null → 等价 listForResume（全量 + 末条 user → sentinel）")
    void nullExcludeEqualsListForResume() {
        // WHY: streamUserMessageId==null（非流式测试路径）回落「转录非空」全量语义（对齐
        //   LlmAgentLoop :2342-2344）。变异点：null 分支行为漂移 → 非流式路径回归 → 红。
        givenMessages(List.of(
            msg("u1", "user", "第一问"),
            msg("a1", "assistant", "第一答"),
            msg("u2", "user", "末条用户")));

        List<ChatMessageDto> history = service.listForResumeExcluding("sess-1", null);

        assertThat(history).hasSize(4);
        assertThat(history).extracting(ChatMessageDto::id)
            .as("null → 不排除，末条 user 触发中断语义 → 全量 + sentinel")
            .containsExactly("u1", "a1", "u2", history.get(3).id());
        assertThat(history.get(3).role()).as("sentinel 为 assistant（No response requested.）")
            .isEqualTo(Role.assistant);
        assertThat(history.get(3).content()).isEqualTo("No response requested.");
    }

    @Test
    @DisplayName("session 仅当前消息 → 排除后为空（全新会话不注入）")
    void onlyCurrentMessage_returnsEmpty() {
        // WHY: 全新会话首 run 转录仅含当前 in-flight 用户消息 → 无历史可注入（否则误判 resume）。
        //   变异点：仍返回非空 → 全新会话上下文被注入 → 红。
        givenMessages(List.of(msg("u2", "user", "当前消息")));

        List<ChatMessageDto> history = service.listForResumeExcluding("sess-1", "u2");

        assertThat(history).as("排除当前消息后无历史 → 空列表（LlmAgentLoop 判定不注入）").isEmpty();
    }

    @Test
    @DisplayName("不破坏 listBySession 原始读取（DB 权威通道不变）")
    void listBySessionUnchanged() {
        // WHY: 双通道铁律 —— listForResumeExcluding 是恢复消费点专用漏斗，listBySession 原始展示
        //   不受影响。变异点：listForResumeExcluding 改写 DB / listBySession 行为 → 前端回放回归 → 红。
        givenMessages(List.of(
            msg("u1", "user", "第一问"),
            msg("a1", "assistant", "第一答"),
            msg("u2", "user", "当前消息")));

        service.listForResumeExcluding("sess-1", "u2");
        List<ChatMessageDto> raw = service.listBySession("sess-1");

        assertThat(raw).extracting(ChatMessageDto::id)
            .as("listBySession 仍返回 DB 原始全部消息（含当前），created_at ASC")
            .containsExactly("u1", "a1", "u2");
    }

    @Test
    @DisplayName("listForResumeExcluding 返回 DTO 携带 reasoningDurationMs（读侧 toDto 映射）")
    void resumeDtoCarriesReasoningDurationMs() {
        // WHY (CLAUDE.md 规则 9 · 测试验证意图): reasoningDurationMs（V41 列）经 toDto 回填到出站
        //   DTO；listForResumeExcluding 是恢复消费点漏斗，DTO 必须携带该字段（前端恢复历史可展示
        //   推理耗时）。变异点：toDto 不回填 → resume 历史 DTO 耗时 null → 红。
        givenMessages(List.of(
            msg("u1", "user", "第一问"),
            msg("a1", "assistant", "第一答")));

        List<ChatMessageDto> history = service.listForResumeExcluding("sess-1", null);

        assertThat(history)
            .as("恢复历史 DTO 必须携带 reasoningDurationMs（toDto 读侧映射，V41 列）")
            .filteredOn(m -> m.role() == Role.assistant)
            .allSatisfy(m -> assertThat(m.reasoningDurationMs()).isEqualTo(1500L));
    }
}

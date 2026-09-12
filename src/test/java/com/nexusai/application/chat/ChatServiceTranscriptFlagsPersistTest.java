package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SnipTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [D8=C 2026-09-12] ChatService 4 条实时落库写路径把 V70 两标记列真实接线
 * （{@code is_compact_summary} / {@code is_visible_in_transcript_only} 显式写 false，不再是 NULL）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：ChatService 直写 {@code messageMapper.insert}
 * <b>绕过 MessageService</b>，故其 4 条路径（snip_boundary / assistant(tool_calls) / assistant(纯文本) /
 * tool_result）需要独立接线（同 cwd 戳先例）。接线前这 4 条路径全部落 NULL，读侧
 * {@code Boolean.TRUE.equals} 把 NULL 当 false → 行为无差异，但「V70 后新行的 NULL」与「V70 前老行的
 * NULL」从此<b>不可区分</b>：任何回填/校正/校验迁移都失去判据（审计 §五 D8）。
 *
 * <p><b>RED 条件</b>：删除 {@code newAssistantMessage} / {@code newToolMessage} / snip_boundary 内联 rec
 * 中任一处 {@code setIsCompactSummary(false) / setIsVisibleInTranscriptOnly(false)} → 捕获到的 record
 * 对应字段为 null → 本类断言红。
 *
 * <p><b>顺带覆盖工厂复用面</b>：{@code newAssistantMessage} 同时服务 tool_calls 与纯文本两条分支，
 * 故两条分支都走一遍（同一工厂，任一分支漏接线都会被本类捕获）。
 *
 * <p>纯单测：{@code new ChatService()} + ReflectionTestUtils 注入 mock MessageMapper / ToolCallMapper；
 * 生产链路触发（{@code armRealTimePersist} + {@code state.appendMessage}）。
 */
@DisplayName("[D8=C] ChatService 实时落库 4 路径写 V70 两标记列（显式 false，非 NULL）")
class ChatServiceTranscriptFlagsPersistTest {

    private static final String SESSION = "sess-flags-persist";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
    }

    private static FeatureFlags snipEnabledFlags() {
        return new FeatureFlags(
            false, false, false, false, false, true,
            false, false, false, false, false, false,
            false, false, false, false, false, false,
            false, false, false, false, false, false);
    }

    private static ChatMessageDto text(String id, Role role, String content) {
        return text(id, role, content, List.of(), null);
    }

    private static ChatMessageDto text(String id, Role role, String content,
                                       List<ToolCallDto> toolCalls, String toolCallId) {
        return new ChatMessageDto(
            id, SESSION, role,
            role == Role.user ? "user" : (role == Role.tool ? "tool" : "assistant"),
            content, null, toolCalls, FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), toolCallId, null, null,
            List.of(), List.of(), null, false, false);
    }

    private static ToolUseContext ctxWithMessages(List<?> messages) {
        return ToolUseContext.of(UUID.randomUUID(), SESSION, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, messages);
    }

    private static ToolUseBlock snipCall(String... messageIds) {
        ObjectNode input = new ObjectMapper().createObjectNode();
        ArrayNode ids = input.putArray("message_ids");
        for (String id : messageIds) {
            ids.add(id);
        }
        return new ToolUseBlock(UUID.randomUUID().toString(), SnipTool.NAME, input);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> asToolResult(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    @Test
    @DisplayName("4 条落库路径（snip_boundary / assistant×2 / tool_result）落的两列非 NULL 且为 0")
    void allChatServicePersistPaths_writeNonNullFalseFlags() {
        // GIVEN: 被 snipe 区间（user0+assistant0+tool0）+ 后续 user1 + SnipTool 注入的 boundary
        ChatMessageDto user0 = text("u0", Role.user, "open baidu");
        ChatMessageDto assistantWithTools = text("a0", Role.assistant, "searching",
            List.of(new ToolCallDto("call_0", "Bash", "{\"command\":\"ls\"}", null, false)), null);
        ChatMessageDto tool0 = text("t0", Role.tool, "result", List.of(), "call_0");
        ChatMessageDto assistantPlain = text("a1", Role.assistant, "done");
        ChatMessageDto user1 = text("u1", Role.user, "next");
        List<ChatMessageDto> history = new ArrayList<>(
            List.of(user0, assistantWithTools, tool0, assistantPlain, user1));

        ToolResult<String> snipResult = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)));
        ChatMessageDto boundary = snipResult.newMessages().get(0);
        assertThat(boundary.snipMetadata()).as("前置：SnipTool 产出了 boundary").isNotNull();

        AgentState state = new AgentState("sys");

        // WHEN: 生产链路逐条 append（每次 append 即走 persistAppendedMessage 对应分支落库）
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, mock(SimpMessagingTemplate.class), "msg-user");
        for (ChatMessageDto m : List.of(user0, assistantWithTools, tool0, assistantPlain, user1, boundary)) {
            state.appendMessage(m);
        }

        // THEN: 所有落库行的两列都必须是<b>显式的 false</b>（null = 未接线 → 红）
        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, org.mockito.Mockito.atLeastOnce()).insert(cap.capture());
        List<MessageRecord> rows = cap.getAllValues();
        assertThat(rows).as("应至少落 4 条（assistant×2 + tool + snip_boundary）").hasSizeGreaterThanOrEqualTo(4);
        assertThat(rows).extracting(MessageRecord::getSubtype).contains("snip_boundary");
        assertThat(rows).extracting(MessageRecord::getRole)
            .contains(Role.assistant.name(), Role.tool.name(), Role.system.name());

        for (MessageRecord r : rows) {
            String where = "role=" + r.getRole() + " subtype=" + r.getSubtype() + " id=" + r.getId();
            assertThat(r.getIsCompactSummary())
                .as("is_compact_summary 必须显式 false（不是 NULL）· " + where)
                .isNotNull()
                .isFalse();
            assertThat(r.getIsVisibleInTranscriptOnly())
                .as("is_visible_in_transcript_only 必须显式 false（不是 NULL）· " + where)
                .isNotNull()
                .isFalse();
        }
        // 工厂复用面确认：assistant(tool_calls) 与 assistant(纯文本) 两条分支各落 1 条
        //   （tool 行的 id 由 newToolMessage 内部 generateId 生成，故按 toolCallId 而非 dto id 定位）
        assertThat(rows).extracting(MessageRecord::getId).contains("a0", "a1");
        assertThat(rows).extracting(MessageRecord::getToolCallId).contains("call_0");
    }
}

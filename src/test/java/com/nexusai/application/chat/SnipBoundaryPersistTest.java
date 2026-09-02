package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SnipTool;
import com.nexusai.eventbus.ws.MessageBoundaryEvent;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [snip-persist-field] snip_boundary 落库 + STOMP 推送验证（用户确认：落库持久 + 实时推送，
 * F5 由 GET /messages 兜底）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: SnipTool 注入的 boundary（removedUuids）
 * 必须在 turn 结束（replayAndPersist）落库 DB，供 GET /messages 出站（前端 F5 后读 removedUuids
 * 标注「已裁剪」）；同时 STOMP 推送 MessageBoundaryEvent，前端实时标注。被 snipe 消息本身不删
 * （对齐 CC transcript append-only）。缺失任一 → 前端无法标注。
 *
 * <p>纯单测：{@code new ChatService()} + ReflectionTestUtils 注入 mock MessageMapper /
 * SimpMessagingTemplate；replayAndPersist 反射调用（对齐 ChatServiceReplayPersistReasoningTest）。
 */
@DisplayName("[snip-persist-field] snip_boundary 落库 + STOMP 推送")
class SnipBoundaryPersistTest {

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private SimpMessagingTemplate wsTemplate;

    private static final String SESSION = "sess-snip-persist";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
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

    private static ChatMessageDto msg(String id, Role role, String content) {
        return msg(id, role, content, null);
    }

    private static ChatMessageDto msg(String id, Role role, String content, String toolCallId) {
        return new ChatMessageDto(
            id, SESSION, role, role == Role.user ? "user" : (role == Role.tool ? "tool" : "assistant"),
            content, null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), toolCallId, null, null,
            List.of(), List.of(), null, false, false);
    }

    private static ToolUseContext ctxWithMessages(List<?> messages) {
        return ToolUseContext.of(UUID.randomUUID(), SESSION, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, messages);
    }

    private static ToolUseBlock snipCall(String... messageIds) {
        com.fasterxml.jackson.databind.node.ObjectNode input =
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode ids = input.putArray("message_ids");
        for (String id : messageIds) {
            ids.add(id);
        }
        return new ToolUseBlock(UUID.randomUUID().toString(), SnipTool.NAME, input);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> asToolResult(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    private void invokeReplay(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod(
            "replayAndPersist",
            String.class, String.class, AgentState.class, String.class, SimpMessagingTemplate.class);
        m.setAccessible(true);
        m.invoke(service, SESSION, "msg-user", state, STREAM_TOPIC, wsTemplate);
    }

    @Test
    @DisplayName("SnipTool 注入 boundary → replayAndPersist 落库（role=system + snipMetadata）+ STOMP 推送")
    void persistsBoundaryAndPushesStomp() throws Exception {
        // GIVEN: 会话历史含被 snipe 区间（user0+assistant0+tool0）+ SnipTool 注入 boundary
        ChatMessageDto user0 = msg("u0", Role.user, "open baidu");
        ChatMessageDto asst0 = msg("a0", Role.assistant, "searching");
        ChatMessageDto tool0 = msg("t0", Role.tool, "result", "call_0");
        ChatMessageDto user1 = msg("u1", Role.user, "next");
        List<ChatMessageDto> history = new ArrayList<>(List.of(user0, asst0, tool0, user1));

        ToolResult<String> snipResult = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)));
        ChatMessageDto boundary = snipResult.newMessages().get(0);
        assertThat(boundary.snipMetadata()).isNotNull();

        AgentState state = new AgentState("sys");
        state.appendMessage(user0);
        state.appendMessage(asst0);
        state.appendMessage(tool0);
        state.appendMessage(user1);
        state.appendMessage(boundary);   // SnipTool 注入的 boundary 在本轮 state.messages()

        // WHEN: turn 结束回放持久化
        invokeReplay(state);

        // THEN: messageMapper.insert 收到 snip_boundary 记录（role=system + subtype + snipMetadata）
        //   （insert 还含 tool 记录 + final assistant，用 atLeastOnce + 按 subtype 过滤定位 boundary）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        MessageRecord inserted = captor.getAllValues().stream()
            .filter(r -> "snip_boundary".equals(r.getSubtype()))
            .findFirst()
            .orElse(null);
        assertThat(inserted).as("boundary 以 role=system + subtype=snip_boundary 落库（对齐 CC type='system'）").isNotNull();
        assertThat(inserted.getRole()).isEqualTo(Role.system.name());
        assertThat(inserted.getSnipMetadata())
            .as("snipMetadata.removedUuids 持久化（V62 列）")
            .isNotNull()
            .contains("u0");

        // THEN: wsTemplate 收到 MessageBoundaryEvent（removedUuids → 前端实时标注）
        ArgumentCaptor<MessageBoundaryEvent> evt = ArgumentCaptor.forClass(MessageBoundaryEvent.class);
        verify(wsTemplate).convertAndSend(eq(STREAM_TOPIC), evt.capture());
        assertThat(evt.getValue().getRemovedUuids())
            .as("MessageBoundaryEvent 携带被裁剪消息 id（区间 user0→user1 前，含 assistant0+tool0）")
            .containsExactly("u0", "a0", "t0");
    }

    @Test
    @DisplayName("无 snip_boundary 消息 → 零落库、零推送（普通 turn 不受影响）")
    void noBoundary_noPersistNoPush() throws Exception {
        ChatMessageDto user0 = msg("u0", Role.user, "hi");
        AgentState state = new AgentState("sys");
        state.appendMessage(user0);

        invokeReplay(state);

        // 普通 user 消息不走 insert（user 已由 createUserMessage 落库）；boundary 分支不触发
        verify(messageMapper, never()).insert(any());
        verify(wsTemplate, never()).convertAndSend(eq(STREAM_TOPIC), isA(MessageBoundaryEvent.class));
    }
}

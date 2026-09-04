package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [工具调用实时推] ChatService 实时落库去重 · 净新增。
 *
 * <p>覆盖 (设计规格 T9/T10):
 * <ul>
 *   <li>T9: id ∈ realtimeToolCallsPushed/realtimeToolResultsPushed → 实时落库跳过 STOMP
 *       (已实时推过, 防前端重复卡片), 但 DB 落库无条件 (toolCallMapper.insert /
 *       messageMapper.insert / toolCallMapper.update 仍执行)</li>
 *   <li>T10: realtime 集合为空 (全新会话/非流式/单测) → 实时落库仍全量推 tool_call + tool_result
 *       (行为等价现状, 向后兼容)</li>
 * </ul>
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 去重意图是「实时推后不重复推 STOMP,
 * 但 DB 权威落库不受影响」——若实现回退 (删守卫 → 双推; 误删 DB → 落库丢失), 对应断言必须变红.
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper +
 * mock wsTemplate；生产链路触发（{@code armRealTimePersist} + {@code state.appendMessage}，
 * 替代已删 replayAndPersist 反射调用）。
 */
@DisplayName("[工具调用实时推] ChatService 实时落库去重")
class ChatServiceReplayRealtimeDedupTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private SimpMessagingTemplate wsTemplate;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
    }

    /** 生产链路触发：武装实时落库 listener 后逐条 append（对齐 doRun「先 arm 后 append」）。 */
    private void armAndAppend(AgentState state, String userMessageId, ChatMessageDto... messages) {
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, userMessageId);
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
    }

    @Test
    @DisplayName("T9 id 已实时推过 → 实时落库跳过 STOMP 但 DB 落库无条件")
    void alreadyPushedRealtimeIds_skipStompKeepDb() {
        // GIVEN: 工具轮 assistant(toolCall=tc1) + tool_result, 且 tc1 已实时推过 (双集合登记)
        AgentState state = new AgentState("sys");
        state.realtimeToolCallsPushed().add("tc1");
        state.realtimeToolResultsPushed().add("tc1");
        ToolCallRecord existing = new ToolCallRecord();
        existing.setId("tc1");
        when(toolCallMapper.selectOneById("tc1")).thenReturn(existing);

        // WHEN: 生产链路实时落库（append 即触发 persistAppendedMessage，读 realtime 集合守卫）
        armAndAppend(state, "msg-user",
            new ChatMessageDto(
                "turn-t", SESSION, Role.assistant, null, "", "工具轮思考",
                List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
                FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null,
                null, List.of(), List.of(), null, false, false),
            new ChatMessageDto(
                "tr1", SESSION, Role.tool, "tool", "ok", null, null,
                null, null, null, "刚刚", OffsetDateTime.now(), "tc1", "turn-t",
                null, List.of(), List.of(), null, false, false));

        // THEN: STOMP 零推送 (tool_call + tool_result 均跳过)
        verify(wsTemplate, never()).convertAndSend(anyString(), any(Object.class));
        // THEN: DB 无条件落库 —— assistant + tool 消息各 1 次 insert
        verify(messageMapper, times(2)).insert(any(MessageRecord.class));
        // THEN: toolCallRecord insert (assistant 工具轮) 仍执行
        verify(toolCallMapper, times(1)).insert(any(ToolCallRecord.class));
        // THEN: toolCallMapper.update (回填 result) 仍执行
        verify(toolCallMapper, times(1)).update(any(ToolCallRecord.class));
    }

    @Test
    @DisplayName("T10 realtime 集合为空 → 实时落库仍全量推 tool_call + tool_result (向后兼容)")
    void emptyRealtimeSets_fullReplay() {
        // GIVEN: 工具轮 assistant(toolCall=tc1) + tool_result, realtime 集合为空 (全新会话/非流式)
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库
        armAndAppend(state, "msg-user",
            new ChatMessageDto(
                "turn-t", SESSION, Role.assistant, null, "", "工具轮思考",
                List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
                FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null,
                null, List.of(), List.of(), null, false, false),
            new ChatMessageDto(
                "tr1", SESSION, Role.tool, "tool", "ok", null, null,
                null, null, null, "刚刚", OffsetDateTime.now(), "tc1", "turn-t",
                null, List.of(), List.of(), null, false, false));

        // THEN: tool_call + tool_result 均推送 (共 2 次 STOMP)
        verify(wsTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
        // THEN: DB 照常落库
        verify(messageMapper, times(2)).insert(any(MessageRecord.class));
        verify(toolCallMapper, times(1)).insert(any(ToolCallRecord.class));
    }
}

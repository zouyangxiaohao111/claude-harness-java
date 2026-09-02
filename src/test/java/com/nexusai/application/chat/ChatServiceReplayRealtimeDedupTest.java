package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.eventbus.ws.MessageToolCallEvent;
import com.nexusai.eventbus.ws.MessageToolResultEvent;
import com.nexusai.eventbus.ws.StreamEvent;
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

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [工具调用实时推] ChatService.replayAndPersist 回放去重 · 净新增。
 *
 * <p>覆盖 (设计规格 T9/T10):
 * <ul>
 *   <li>T9: id ∈ realtimeToolCallsPushed/realtimeToolResultsPushed → 回放跳过 sendAndLog
 *       (已实时推过, 防前端重复卡片), 但 DB 落库无条件 (toolCallMapper.insert /
 *       messageMapper.insert / toolCallMapper.update 仍执行)</li>
 *   <li>T10: realtime 集合为空 (全新会话/非流式/单测) → 回放仍全量推 tool_call + tool_result
 *       (行为等价现状, 向后兼容)</li>
 * </ul>
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 回放去重意图是「实时推后不重复推 STOMP,
 * 但 DB 权威落库不受影响」——若实现回退 (删守卫 → 双推; 误删 DB → 落库丢失), 对应断言必须变红.
 */
@DisplayName("[工具调用实时推] ChatService.replayAndPersist 回放去重")
class ChatServiceReplayRealtimeDedupTest {

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

    @Test
    @DisplayName("T9 id 已实时推过 → 回放跳过 STOMP 但 DB 落库无条件")
    void alreadyPushedRealtimeIds_skipStompKeepDb() throws Exception {
        // GIVEN: 工具轮 assistant(toolCall=tc1) + tool_result, 且 tc1 已实时推过 (双集合登记)
        AgentState state = new AgentState("sys");
        state.realtimeToolCallsPushed().add("tc1");
        state.realtimeToolResultsPushed().add("tc1");
        state.appendMessage(new ChatMessageDto(
            "turn-t", "sess-1", Role.assistant, null, "", "工具轮思考",
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false));
        state.appendMessage(new ChatMessageDto(
            "tr1", "sess-1", Role.tool, "tool", "ok", null, null,
            null, null, null, "刚刚", OffsetDateTime.now(), "tc1", "turn-t",
            null, List.of(), List.of(), null, false, false));
        ToolCallRecord existing = new ToolCallRecord();
        existing.setId("tc1");
        when(toolCallMapper.selectOneById("tc1")).thenReturn(existing);

        // WHEN: 回放持久化
        invokeReplay(state);

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
    @DisplayName("T10 realtime 集合为空 → 回放仍全量推 tool_call + tool_result (向后兼容)")
    void emptyRealtimeSets_fullReplay() throws Exception {
        // GIVEN: 工具轮 assistant(toolCall=tc1) + tool_result, realtime 集合为空 (全新会话/非流式)
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "turn-t", "sess-1", Role.assistant, null, "", "工具轮思考",
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false));
        state.appendMessage(new ChatMessageDto(
            "tr1", "sess-1", Role.tool, "tool", "ok", null, null,
            null, null, null, "刚刚", OffsetDateTime.now(), "tc1", "turn-t",
            null, List.of(), List.of(), null, false, false));

        // WHEN: 回放持久化
        invokeReplay(state);

        // THEN: tool_call + tool_result 均推送 (共 2 次 STOMP)
        verify(wsTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
        // THEN: DB 照常落库
        verify(messageMapper, times(2)).insert(any(MessageRecord.class));
        verify(toolCallMapper, times(1)).insert(any(ToolCallRecord.class));
    }

    /** 反射调用 private {@code replayAndPersist(sessionId, userMessageId, state, streamTopic, wsTemplate)}。 */
    private void invokeReplay(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod(
            "replayAndPersist",
            String.class, String.class, AgentState.class, String.class, SimpMessagingTemplate.class);
        m.setAccessible(true);
        m.invoke(service, "sess-1", "msg-user", state, "/topic/stream", wsTemplate);
    }
}

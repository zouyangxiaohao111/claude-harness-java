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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [同源改造] replayAndPersist 落库 id 统一 turnAssistantId · 净新增。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: assistant 落库 id 与流式
 * {@code chunk.assistantMessageId}（=turnAssistantId）必须同源（前端「块 id 匹配 DB」目标域），
 * 而非随机 {@code finalAssistantId = "msg-"+UUID8}。变异点：
 * <ul>
 *   <li>final 块仍用随机 finalAssistantId → 纯文本 assistant 落库 id="msg-*" ≠ 源消息 id → 红</li>
 *   <li>final 块缺 toolCalls 空闸 → 工具轮末条被循环落库后 final 块再以随机 id 插一条重复行 → 红</li>
 *   <li>tool_result STOMP 事件父 id 仍用随机 finalAssistantId → 前端块 id 不匹配 → 红</li>
 * </ul>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper +
 * mock wsTemplate；{@code replayAndPersist} 私有方法反射调用（同
 * {@code ChatServiceReplayPersistReasoningTest} 模式）。
 */
@DisplayName("[同源改造] replayAndPersist 落库 id 统一 turnAssistantId")
class ChatServiceReplayPersistAssistantIdSameSourceTest {

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
    @DisplayName("纯文本 assistant final 落库 id == 源消息 id（=turnAssistantId，非随机 finalAssistantId）")
    void finalBlockPersistIdIsSourceMessageId() throws Exception {
        // GIVEN: 纯文本 assistant（无 tool_calls → 仅 final 块落库），id="turn-1"
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "turn-1", "sess-1", Role.assistant, null, "纯文本回复", "思考", List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false));

        // WHEN: 回放持久化（生产路径 replayAndPersist）
        invokeReplay(state, null);

        // THEN: 落库恰一次，id == "turn-1"（源 assistant 消息真实 id = turnAssistantId）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getId())
            .as("纯文本 assistant 落库 id 必须 == 源消息 id（turnAssistantId，前端块 id 匹配 DB）")
            .isEqualTo("turn-1");
    }

    @Test
    @DisplayName("工具轮末条不重复落库：循环落一次（id=turnAssistantId），final 块跳过（toolCalls 空闸）")
    void toolTurnLastAssistantNotDuplicated() throws Exception {
        // GIVEN: 工具轮 assistant（带 toolCalls，id="turn-t"）+ tool_result
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
        invokeReplay(state, wsTemplate);

        // THEN: 工具轮 assistant 恰落库一次（循环 :449），final 块 toolCalls 空闸跳过
        //   （不得产生第二条随机 id 重复行）→ 共 2 次 insert（assistant + tool_result）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        List<MessageRecord> assistantRecs = captor.getAllValues().stream()
            .filter(r -> "assistant".equals(r.getRole()))
            .toList();
        assertThat(assistantRecs)
            .as("工具轮末条 assistant 必须恰落库一次（final 块跳过，无重复行）")
            .hasSize(1);
        assertThat(assistantRecs.get(0).getId())
            .as("工具轮 assistant 落库 id == turnAssistantId")
            .isEqualTo("turn-t");
    }

    @Test
    @DisplayName("tool_result STOMP 事件父 id == turnAssistantId（m.assistantMessageId，非随机 finalAssistantId）")
    void toolResultEventAssistantMessageIdIsTurnId() throws Exception {
        // GIVEN: 工具轮 assistant（id="turn-t"）+ tool_result（assistantMessageId="turn-t"）
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "turn-t", "sess-1", Role.assistant, null, "", "思考",
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false));
        state.appendMessage(new ChatMessageDto(
            "tr1", "sess-1", Role.tool, "tool", "ok", null, null,
            null, null, null, "刚刚", OffsetDateTime.now(), "tc1", "turn-t",
            null, List.of(), List.of(), null, false, false));

        // WHEN: 回放持久化（STOMP 事件发出）
        invokeReplay(state, wsTemplate);

        // THEN: MessageToolResultEvent.assistantMessageId == "turn-t"（真实 turn id，非幻影随机串）
        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(wsTemplate, times(2)).convertAndSend(anyString(), captor.capture());
        MessageToolResultEvent toolResultEvt = captor.getAllValues().stream()
            .filter(MessageToolResultEvent.class::isInstance)
            .map(MessageToolResultEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(toolResultEvt.getAssistantMessageId())
            .as("tool_result STOMP 事件父 id 必须指向真实 turn id（turnAssistantId）")
            .isEqualTo("turn-t");
        // 对照：tool_call 事件父 id 同为 turnAssistantId（既有 :462 行为不回归）
        MessageToolCallEvent toolCallEvt = captor.getAllValues().stream()
            .filter(MessageToolCallEvent.class::isInstance)
            .map(MessageToolCallEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(toolCallEvt.getAssistantMessageId())
            .as("tool_call STOMP 事件父 id 必须指向真实 turn id（turnAssistantId）")
            .isEqualTo("turn-t");
    }

    /** 反射调用 private {@code replayAndPersist(sessionId, userMessageId, state, streamTopic, wsTemplate)}。 */
    private void invokeReplay(AgentState state, SimpMessagingTemplate ws) throws Exception {
        Method m = ChatService.class.getDeclaredMethod(
            "replayAndPersist",
            String.class, String.class, AgentState.class, String.class, SimpMessagingTemplate.class);
        m.setAccessible(true);
        m.invoke(service, "sess-1", "msg-user", state, "/topic/stream", ws);
    }
}

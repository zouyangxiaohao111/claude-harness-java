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

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [同源改造] 实时落库 id 统一 turnAssistantId · 净新增。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: assistant 落库 id 与流式
 * {@code chunk.assistantMessageId}（=turnAssistantId）必须同源（前端「块 id 匹配 DB」目标域），
 * 而非随机 {@code finalAssistantId = "msg-"+UUID8}。变异点：
 * <ul>
 *   <li>纯文本 assistant 落库仍用随机 id → 落库 id="msg-*" ≠ 源消息 id → 红</li>
 *   <li>工具轮末条被重复落库 → 以随机 id 插一条重复行 → 红</li>
 *   <li>tool_result STOMP 事件父 id 仍用随机 finalAssistantId → 前端块 id 不匹配 → 红</li>
 * </ul>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper +
 * mock wsTemplate；生产链路触发（{@code armRealTimePersist} 武装 appendListener 后
 * {@code state.appendMessage} 逐条实时落库，替代已删 replayAndPersist 反射调用）。
 */
@DisplayName("[同源改造] 实时落库 id 统一 turnAssistantId")
class ChatServiceReplayPersistAssistantIdSameSourceTest {

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
    @DisplayName("纯文本 assistant 落库 id == 源消息 id（=turnAssistantId，非随机）")
    void finalBlockPersistIdIsSourceMessageId() {
        // GIVEN: 纯文本 assistant（无 tool_calls → 仅纯文本落库），id="turn-1"
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库（append 即触发 persistAppendedMessage）
        armAndAppend(state, "msg-user", new ChatMessageDto(
            "turn-1", SESSION, Role.assistant, null, "纯文本回复", "思考", List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false));

        // THEN: 落库恰一次，id == "turn-1"（源 assistant 消息真实 id = turnAssistantId）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getId())
            .as("纯文本 assistant 落库 id 必须 == 源消息 id（turnAssistantId，前端块 id 匹配 DB）")
            .isEqualTo("turn-1");
    }

    @Test
    @DisplayName("工具轮末条不重复落库：assistant 落一次（id=turnAssistantId），无随机 id 重复行")
    void toolTurnLastAssistantNotDuplicated() {
        // GIVEN: 工具轮 assistant（带 toolCalls，id="turn-t"）+ tool_result
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库（assistant + tool_result 逐条 append 即落）
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

        // THEN: 工具轮 assistant 恰落库一次（纯文本分支无重复落），共 2 次 insert（assistant + tool_result）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        List<MessageRecord> assistantRecs = captor.getAllValues().stream()
            .filter(r -> "assistant".equals(r.getRole()))
            .toList();
        assertThat(assistantRecs)
            .as("工具轮末条 assistant 必须恰落库一次（无随机 id 重复行）")
            .hasSize(1);
        assertThat(assistantRecs.get(0).getId())
            .as("工具轮 assistant 落库 id == turnAssistantId")
            .isEqualTo("turn-t");
    }

    @Test
    @DisplayName("tool_result STOMP 事件父 id == turnAssistantId（m.assistantMessageId，非随机）")
    void toolResultEventAssistantMessageIdIsTurnId() {
        // GIVEN: 工具轮 assistant（id="turn-t"）+ tool_result（assistantMessageId="turn-t"）
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库（STOMP 事件随 append 发出）
        armAndAppend(state, "msg-user",
            new ChatMessageDto(
                "turn-t", SESSION, Role.assistant, null, "", "思考",
                List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
                FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null,
                null, List.of(), List.of(), null, false, false),
            new ChatMessageDto(
                "tr1", SESSION, Role.tool, "tool", "ok", null, null,
                null, null, null, "刚刚", OffsetDateTime.now(), "tc1", "turn-t",
                null, List.of(), List.of(), null, false, false));

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
        // 对照：tool_call 事件父 id 同为 turnAssistantId（既有行为不回归）
        MessageToolCallEvent toolCallEvt = captor.getAllValues().stream()
            .filter(MessageToolCallEvent.class::isInstance)
            .map(MessageToolCallEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(toolCallEvt.getAssistantMessageId())
            .as("tool_call STOMP 事件父 id 必须指向真实 turn id（turnAssistantId）")
            .isEqualTo("turn-t");
    }
}

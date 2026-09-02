package com.nexusai.domain.session;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [reasoningDurationMs] MessageService 写读 round-trip 测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: reasoning_duration_ms 列贯穿写侧
 * （appendMessage / replaceSessionMessages / createUserMessage）与读侧（toDto 回填到
 * ChatMessageDto.reasoningDurationMs）。变异点：
 * <ul>
 *   <li>appendMessage 落库丢 duration → DB 写侧断头 → 红</li>
 *   <li>replaceSessionMessages 全量替换丢 duration → partial 压缩写回后历史耗时丢失 → 红</li>
 *   <li>toDto 不回填 → GET /messages（ChatController listBySession）出站无字段 → 红</li>
 *   <li>createUserMessage 误写 user 消息 duration → 无 reasoning 也留痕 → 红</li>
 * </ul>
 */
@DisplayName("[reasoningDurationMs] MessageService 写读 round-trip（V41 列）")
class MessageServiceReasoningDurationRoundTripTest {

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
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    private static ChatMessageDto assistant(String id, String reasoning, Long durationMs) {
        return new ChatMessageDto(
            id, "sess-1", Role.assistant, null, "回复", reasoning,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false)
            .withReasoningDurationMs(durationMs);
    }

    @Test
    @DisplayName("appendMessage 落库带 duration → listBySession 读回一致 + 返回 DTO 保留字段")
    void appendMessage_writesDuration_listBySessionReadsBack() {
        // GIVEN: 带推理耗时的 assistant DTO
        ChatMessageDto dto = assistant("msg-asst", "思考", 1200L);

        // WHEN: appendMessage 落库
        ChatMessageDto persisted = service.appendMessage(dto);

        // THEN: 落库 rec 携带 duration（V41 列写侧）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasoningDurationMs())
            .as("appendMessage 写侧必须把 dto.reasoningDurationMs 落库（V41 列）")
            .isEqualTo(1200L);
        // 返回 DTO 写后回传保留字段
        assertThat(persisted.reasoningDurationMs()).isEqualTo(1200L);

        // WHEN: 读回（toDto 回填）——stub selectListByQuery 返回已插入 rec
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(captor.getValue()));
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: GET /messages 出站 DTO 携带 duration
        assertThat(read).hasSize(1);
        assertThat(read.get(0).reasoningDurationMs())
            .as("toDto 读侧必须回填 reasoningDurationMs（GET /messages 出站唯一点）")
            .isEqualTo(1200L);
    }

    @Test
    @DisplayName("replaceSessionMessages 全量替换：重插 rec + 归一化 DTO 均保留 duration（null 也保真）")
    void replaceSessionMessages_reinsertedDtosCarryDuration() {
        // GIVEN: 含 duration（1200）与无 duration（null）的 DTO 列表
        List<ChatMessageDto> dtoList = List.of(
            assistant("a1", null, 900L),
            assistant("a2", null, null));

        // WHEN: 全量替换（partial 压缩写回路径）
        List<ChatMessageDto> normalized = service.replaceSessionMessages("sess-1", dtoList);

        // THEN: 重插 rec 各自保真 + 归一化 DTO 保留
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getReasoningDurationMs()).isEqualTo(900L);
        assertThat(captor.getAllValues().get(1).getReasoningDurationMs()).isNull();
        assertThat(normalized).extracting(ChatMessageDto::reasoningDurationMs)
            .as("归一化 DTO（写后回传）必须保留 reasoningDurationMs")
            .containsExactly(900L, null);
    }

    @Test
    @DisplayName("createUserMessage：user 消息 reasoningDurationMs 恒 null（不误写）")
    void createUserMessage_userDurationNull() {
        // GIVEN: 用户消息请求
        SendMessageRequest req = new SendMessageRequest(
            "你好", null, null, null, null, null, null, null, null);

        // WHEN: 落库
        service.createUserMessage("sess-1", req);

        // THEN: user 消息 reasoningDurationMs 恒 null（后端测推理耗时仅 assistant 消息，对称风格）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasoningDurationMs())
            .as("user 消息恒 null（无 reasoning 不记录，前端 null=无数据）")
            .isNull();
    }
}

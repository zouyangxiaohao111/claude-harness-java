package com.nexusai.domain.session;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
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
 * [token-compact-fix B1 方案A] MessageService cache 用量写读 round-trip 测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 用户拍板 cache 落库（V53 列），重算与实时一致。
 * cache_read_input_tokens / cache_creation_input_tokens 列贯穿写侧（appendMessage /
 * replaceSessionMessages，源 = AgentUsage.cacheReadInputTokens/cacheCreationInputTokens）
 * 与读侧（toDto 回填 ChatMessageDto.inputCacheReadTokens/inputCacheCreationTokens）。
 * 变异点：
 * <ul>
 *   <li>appendMessage 写侧丢 cache → DB 断头，重算少算 cache → 红</li>
 *   <li>replaceSessionMessages 全量替换丢 cache → partial 压缩写回后历史 cache 丢失 → 红</li>
 *   <li>toDto 读侧不回填 → GET /messages 出站无 cache，重算仍只按 input → 红</li>
 * </ul>
 */
@DisplayName("[token-compact-fix B1 方案A] MessageService cache 写读 round-trip（V53 列）")
class MessageServiceCacheTokensRoundTripTest {

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

    /** 携带完整 usage（含 cache 的 AgentUsage，LlmAgentLoop toMessage 链 withUsage 产物）的 assistant DTO。 */
    private static ChatMessageDto assistantWithCacheUsage(String id, int input, int output,
                                                          Long cacheRead, Long cacheCreation) {
        AgentUsage usage = new AgentUsage(
            input, output, cacheCreation, cacheRead, null, null, null);
        return new ChatMessageDto(
            id, "sess-1", Role.assistant, null, "回复", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false)
            .withUsage(usage);
    }

    @Test
    @DisplayName("appendMessage 写侧把 AgentUsage.cacheRead/cacheCreation 落库（V53 列）→ listBySession 读回 DTO")
    void appendMessage_writesCacheUsage_listBySessionReadsBack() {
        // GIVEN: 带 cache 的 assistant DTO（仅 usage 携带 cache，DTO 投影字段未设 →
        //   验证写侧真源读 AgentUsage）
        ChatMessageDto dto = assistantWithCacheUsage("msg-asst", 2000, 500, 500L, 300L);

        // WHEN: appendMessage 落库
        service.appendMessage(dto);

        // THEN: 落库 rec 携带 cache（V53 列写侧，源 = AgentUsage）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getCacheReadInputTokens())
            .as("appendMessage 写侧必须把 usage.cacheReadInputTokens 落库（V53 列）")
            .isEqualTo(500);
        assertThat(captor.getValue().getCacheCreationInputTokens())
            .as("appendMessage 写侧必须把 usage.cacheCreationInputTokens 落库（V53 列）")
            .isEqualTo(300);

        // WHEN: 读回（toDto 回填）——stub selectListByQuery 返回已插入 rec
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(captor.getValue()));
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: GET /messages 出站 DTO 携带 cache（toDto 读侧唯一点）
        assertThat(read).hasSize(1);
        assertThat(read.get(0).inputCacheReadTokens())
            .as("toDto 读侧必须回填 cacheRead（V53 列 → DTO）")
            .isEqualTo(500);
        assertThat(read.get(0).inputCacheCreationTokens())
            .as("toDto 读侧必须回填 cacheCreation（V53 列 → DTO）")
            .isEqualTo(300);
    }

    @Test
    @DisplayName("replaceSessionMessages 全量替换：重插 rec 携带 cache（null 也保真）")
    void replaceSessionMessages_reinsertedDtosCarryCache() {
        // GIVEN: 含 cache（500/300）与无 cache（null）的 DTO 列表
        List<ChatMessageDto> dtoList = List.of(
            assistantWithCacheUsage("a1", 2000, 500, 500L, 300L),
            assistantWithCacheUsage("a2", 100, 50, null, null));

        // WHEN: 全量替换（partial 压缩写回路径）
        service.replaceSessionMessages("sess-1", dtoList);

        // THEN: 重插 rec 各自保真
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getCacheReadInputTokens()).isEqualTo(500);
        assertThat(captor.getAllValues().get(0).getCacheCreationInputTokens()).isEqualTo(300);
        assertThat(captor.getAllValues().get(1).getCacheReadInputTokens()).isNull();
        assertThat(captor.getAllValues().get(1).getCacheCreationInputTokens()).isNull();
    }
}

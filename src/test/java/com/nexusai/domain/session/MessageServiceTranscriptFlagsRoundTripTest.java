package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SM/compact 对齐 CC · V70] {@code MessageService.appendMessage(dto)} 写侧把
 * {@code isCompactSummary / isVisibleInTranscriptOnly} 真正写入 {@link MessageRecord}（写侧 → 读回闭环）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：既有 {@code MessageServiceAppendPostCompactTest}
 * 只覆盖了<b>读侧</b>（手工 {@code setIsCompactSummary(true)} 后 listBySession 读回）—— 把
 * {@code appendMessage} 里那两行 {@code rec.setIsCompactSummary(...) / rec.setIsVisibleInTranscriptOnly(...)}
 * 整段删掉，既有测试<b>仍然全绿</b>，而生产后果是：
 * <ul>
 *   <li>compact 摘要消息落库时 {@code is_compact_summary} 恒 NULL → 重拉（GET /messages）读回 false →
 *       前端 TraceView.compactSummaryAfter 断裂（摘要详情不渲染）；</li>
 *   <li>{@code is_visible_in_transcript_only} 恒 NULL → 摘要行的 UI 展示语义丢失
 *       （shouldShowUserMessage, messages.ts:5115）。</li>
 * </ul>
 * 本测试用 {@code ArgumentCaptor<MessageRecord>} 捕获 {@code messageMapper.insert} 实参，直接断言写侧
 * 映射存在（不依赖读侧回填掩盖）。
 *
 * <p><b>RED 条件</b>：删除 appendMessage 中任一行 {@code rec.setIsCompactSummary / setIsVisibleInTranscriptOnly}
 * → 捕获到的 record 对应字段为 null → 断言红。
 *
 * <p><b>顺带锁字段契约一致性</b>：{@link MessageRecord} 用包装类型 {@code Boolean}（DB 列可 NULL，容错旧行），
 * {@link ChatMessageDto} 用原始 {@code boolean}（内存态恒非 null）。两者命名同源
 * （isCompactSummary / isVisibleInTranscriptOnly），避免后续重命名只改一侧导致映射静默断链。
 *
 * <p><b>[M1 / D8=C 2026-09-12 扩面]</b> 本类原只覆盖 {@code appendMessage} 一条路径，而当时另有
 * 7 条写路径完全不设这两列（新行照落 NULL）→ 上面那条「NULL 只属于 V70 前老行」的契约实际
 * <b>不成立</b>（审计判定「测试声称的语义不成立」）。D8=C 接线后本类补齐 MessageService 侧
 * 3 条路径（createUserMessage / createQueuedUserMessage / appendSystemSubtypeMessage）；
 * ChatService 侧 4 条落库路径由
 * {@code com.nexusai.application.chat.ChatServiceTranscriptFlagsPersistTest} 覆盖。
 * 两处合计 7 条 → 契约在<b>全部写路径</b>上成立。
 */
@DisplayName("[V70] MessageService.appendMessage 写侧落 isCompactSummary/isVisibleInTranscriptOnly（record 捕获）")
class MessageServiceTranscriptFlagsRoundTripTest {

    private static final String SESSION = "sess-flags01";
    private static final String SUMMARY_ID = "summary-flags-1";

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
        when(messageMapper.insert(any(MessageRecord.class))).thenReturn(1);
        // createXxx / appendSystemSubtypeMessage 入口先查会话行（非 Spring 直构 → 手工给一行）
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
    }

    /** compact 摘要消息（31 参 canonical 末 5 参 = 两标志 + 三个 boundary 元数据字段）。 */
    private static ChatMessageDto compactSummaryDto() {
        return new ChatMessageDto(
            SUMMARY_ID, SESSION, Role.user, "system",
            "摘要正文", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, "compact_summary",
            false, null, null, null,
            null, null, null,
            true,    // isCompactSummary
            true);   // isVisibleInTranscriptOnly
    }

    @Test
    @DisplayName("appendMessage 把 dto 的两标志写进 MessageRecord（删掉 setIsXxx 两行 → 红）")
    void appendMessage_writesTranscriptFlagsIntoRecord() {
        ChatMessageDto dto = compactSummaryDto();
        assertThat(dto.isCompactSummary()).as("前置：DTO 带 isCompactSummary=true").isTrue();
        assertThat(dto.isVisibleInTranscriptOnly()).as("前置：DTO 带 isVisibleInTranscriptOnly=true").isTrue();

        ChatMessageDto out = service.appendMessage(dto);

        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(cap.capture());
        MessageRecord rec = cap.getValue();

        assertThat(rec.getId()).as("写侧 id 沿用 dto.id").isEqualTo(SUMMARY_ID);
        assertThat(rec.getIsCompactSummary())
            .as("is_compact_summary 列必须被写入 true（漏写 → 重拉读回 false → TraceView 摘要详情缺失）")
            .isTrue();
        assertThat(rec.getIsVisibleInTranscriptOnly())
            .as("is_visible_in_transcript_only 列必须被写入 true（漏写 → UI 展示语义丢失）")
            .isTrue();
        // 写后回传保真（appendMessage 返回新 DTO，供调用方继续消费）
        assertThat(out.isCompactSummary()).as("appendMessage 返回值保留 isCompactSummary").isTrue();
        assertThat(out.isVisibleInTranscriptOnly()).as("appendMessage 返回值保留 isVisibleInTranscriptOnly").isTrue();
    }

    @Test
    @DisplayName("普通消息（两标志 false）→ record 落 false/Boolean.FALSE（不得为 null 而误判）")
    void appendMessage_plainMessage_writesFalseFlags() {
        // WHY: 生产 MessageService.toDto 读回按 Boolean.TRUE.equals 容错（NULL 与 FALSE 同判 false），
        //   但写侧应如实写 false 而非 null —— NULL 语义保留给「V70 前的历史行」，
        //   新写行的 NULL 会让「迁移是否回填完整」的排查失去区分度。
        //
        // [M1 2026-09-12 · 修正本契约的<b>适用范围</b>] 本条契约原先只在 appendMessage 成立，
        //   而其时另有 7 条写路径（createQueuedUserMessage / createUserMessage /
        //   appendSystemSubtypeMessage + ChatService 的 snip_boundary / assistant×2 / tool_result）
        //   完全不设这两列，新行照落 NULL → 契约名存实亡（审计据此判定「测试声称的语义不成立」）。
        //   D8=C 接线后这 7 条路径已补齐，契约在<b>全部写路径</b>上成立 —— 故断言从「appendMessage 一条」
        //   扩到本类下方 3 条 MessageService 路径 + ChatServiceTranscriptFlagsPersistTest 的 4 条
        //   ChatService 路径（删掉任一处 setIsXxx 两行 → 对应测试变红）。
        ChatMessageDto plain = new ChatMessageDto(
            "msg-plain", SESSION, Role.assistant, "assistant",
            "普通回复", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null);

        service.appendMessage(plain);

        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getIsCompactSummary()).as("普通消息落 false（非 null）").isFalse();
        assertThat(cap.getValue().getIsVisibleInTranscriptOnly()).as("普通消息落 false（非 null）").isFalse();
    }

    // ────────────────────────────────────────────────────────────────────
    // [D8=C 2026-09-12] 其余 3 条 MessageService 写路径 · 同款契约（原先完全不设这两列 → 落 NULL）
    //   RED 条件：删除对应方法里 setIsCompactSummary/setIsVisibleInTranscriptOnly 两行 → 捕获到 null → 红。
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[D8=C] createUserMessage 写路径：两标志显式 false（不是 NULL）")
    void createUserMessage_writesFalseFlags() {
        service.createUserMessage(SESSION, new SendMessageRequest(
            "你好", null, null, List.of(), null, null, null, null, null));

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getRole()).as("前置：落的是 user 行").isEqualTo("user");
        assertThat(rec.getIsCompactSummary())
            .as("createUserMessage 必须显式写 false（NULL 会让「V70 后新行」与「V70 前老行」不可区分）")
            .isFalse();
        assertThat(rec.getIsVisibleInTranscriptOnly()).isFalse();
    }

    @Test
    @DisplayName("[D8=C] createQueuedUserMessage 写路径：两标志显式 false（不是 NULL）")
    void createQueuedUserMessage_writesFalseFlags() {
        service.createQueuedUserMessage(SESSION, "msg-queued-1", "排队命令", null, false, "busy-queued");

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getRole()).as("前置：落的是 user 行").isEqualTo("user");
        assertThat(rec.getIsCompactSummary()).isFalse();
        assertThat(rec.getIsVisibleInTranscriptOnly()).isFalse();
    }

    @Test
    @DisplayName("[D8=C] appendSystemSubtypeMessage 写路径：两标志显式 false（不是 NULL）")
    void appendSystemSubtypeMessage_writesFalseFlags() {
        service.appendSystemSubtypeMessage(SESSION, "scheduled_task_fire", "Running scheduled task");

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getRole()).as("前置：落的是 system 行").isEqualTo("system");
        assertThat(rec.getIsCompactSummary()).isFalse();
        assertThat(rec.getIsVisibleInTranscriptOnly()).isFalse();
    }

    /** 捕获本方法内唯一一次 messageMapper.insert 的实参记录。 */
    private MessageRecord captureSingleInsert() {
        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("[字段契约] MessageRecord 两字段为 Boolean（可 NULL 容错旧行）· DTO 为 boolean（内存态恒非 null）")
    void fieldContract_recordBoolean_dtoPrimitive() throws Exception {
        // WHY: DB 列可 NULL（V70 前历史行）→ entity 必须 Boolean 才能表达「未回填」；
        //   DTO 是内存态 → 原始 boolean（读侧 Boolean.TRUE.equals 收敛）。若一侧被改成另一种，
        //   appendMessage 的自动装箱/拆箱会静默改变语义（null → 拆箱 NPE 或 false）。
        assertThat(MessageRecord.class.getDeclaredField("isCompactSummary").getType())
            .as("MessageRecord.isCompactSummary 必须是 Boolean（NULL 容错旧行）")
            .isEqualTo(Boolean.class);
        assertThat(MessageRecord.class.getDeclaredField("isVisibleInTranscriptOnly").getType())
            .as("MessageRecord.isVisibleInTranscriptOnly 必须是 Boolean")
            .isEqualTo(Boolean.class);
        assertThat(ChatMessageDto.class.getDeclaredField("isCompactSummary").getType())
            .as("ChatMessageDto.isCompactSummary 必须是 boolean（内存态恒非 null）")
            .isEqualTo(boolean.class);
        assertThat(ChatMessageDto.class.getDeclaredField("isVisibleInTranscriptOnly").getType())
            .as("ChatMessageDto.isVisibleInTranscriptOnly 必须是 boolean")
            .isEqualTo(boolean.class);
        // 命名同源（record component 名 == entity 属性名）
        assertThat(MessageRecord.class.getDeclaredField("isCompactSummary")).isNotNull();
        assertThat(MessageRecord.class.getDeclaredField("isVisibleInTranscriptOnly")).isNotNull();
    }

    @Test
    @DisplayName("落库不触发任何 DELETE（appendMessage 纯追加，不得走替换路径）")
    void appendMessage_neverDeletes() {
        service.appendMessage(compactSummaryDto());
        verify(messageMapper, org.mockito.Mockito.never()).deleteByQuery(any(QueryWrapper.class));
    }
}

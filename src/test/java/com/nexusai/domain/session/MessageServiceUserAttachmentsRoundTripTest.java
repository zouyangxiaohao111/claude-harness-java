package com.nexusai.domain.session;

import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [userAttachments] MessageService 附件快照写读 round-trip 测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: user_attachments 列贯穿写侧
 * （createUserMessage 落库 user 消息附件快照）与读侧（toDto 回填到
 * ChatMessageDto.userAttachments，前端 F5 重拉附件 chip）。变异点：
 * <ul>
 *   <li>createUserMessage 落库丢附件快照 → DB 写侧断头 → 红</li>
 *   <li>toDto 不回填 → GET /messages 出站无 userAttachments → 红</li>
 *   <li>parseUserAttachments 对 null 列返回 null → 前端 null 判空分支 → 红（契约恒非 null 空列表）</li>
 * </ul>
 */
@DisplayName("[userAttachments] MessageService 附件快照写读 round-trip（V62 列）")
class MessageServiceUserAttachmentsRoundTripTest {

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

    @Test
    @DisplayName("createUserMessage(带 image+file 附件) 落库附件快照 → getById 读回 userAttachments 含两项")
    void createUserMessage_withImageAndFile_persistsAndReadsBackAttachments() {
        // GIVEN: 带 image + file 附件的用户消息请求
        SendMessageRequest req = new SendMessageRequest(
            "带附件", null, null,
            List.of(
                new AttachmentRequest("image", "img-1", "a.png", "image/png", null, null),
                new AttachmentRequest("file", "file-1", "a.pdf", "application/pdf", null, null)),
            null, null, null, null, null);

        // WHEN: 落库
        service.createUserMessage("sess-1", req);

        // THEN: 落库 rec 携带附件快照 JSON（V62 列写侧；全类型含图片）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        String json = captor.getValue().getUserAttachments();
        assertThat(json)
            .as("createUserMessage 写侧必须把全部附件 type+filename 落库（V62 列）")
            .contains("\"type\":\"image\"", "\"filename\":\"a.png\"")
            .contains("\"type\":\"file\"", "\"filename\":\"a.pdf\"");

        // WHEN: 读回（toDto 回填）——stub selectOneById 返回已插入 rec
        when(messageMapper.selectOneById(any())).thenReturn(captor.getValue());
        ChatMessageDto dto = service.getById(captor.getValue().getId());

        // THEN: GET /messages 出站 DTO 携带附件快照列表（含 image + file）
        assertThat(dto.userAttachments())
            .as("toDto 读侧必须回填 userAttachments（GET /messages 出站唯一点）")
            .isNotNull()
            .extracting(ChatMessageDto.UserAttachmentInfo::type)
            .containsExactlyInAnyOrder("image", "file");
        assertThat(dto.userAttachments())
            .extracting(ChatMessageDto.UserAttachmentInfo::filename)
            .containsExactlyInAnyOrder("a.png", "a.pdf");
    }

    @Test
    @DisplayName("createUserMessage(无附件) 落库 user_attachments=null → getById 出站 userAttachments 恒非 null 空列表")
    void createUserMessage_noAttachments_outboundEmptyListNotNull() {
        // GIVEN: 无附件请求
        SendMessageRequest req = new SendMessageRequest(
            "无附件", null, null, null, null, null, null, null, null);

        // WHEN: 落库
        service.createUserMessage("sess-1", req);

        // THEN: 写侧 user_attachments 恒 null（无附件不落脏 JSON）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserAttachments())
            .as("无附件时 user_attachments 落 NULL（serializeUserAttachments(null)=null）")
            .isNull();

        // WHEN: 读回
        when(messageMapper.selectOneById(any())).thenReturn(captor.getValue());
        ChatMessageDto dto = service.getById(captor.getValue().getId());

        // THEN: 出站 userAttachments 恒非 null 空列表（前端无附件分支，parseUserAttachments(null)=List.of()）
        assertThat(dto.userAttachments())
            .as("null 列读回必须为空列表而非 null（前端契约：恒非 null）")
            .isNotNull()
            .isEmpty();
    }
}

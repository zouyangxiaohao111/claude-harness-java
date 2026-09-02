package com.nexusai.domain.session;

import com.nexusai.application.agent.team.TeammateMessageFoldingChain;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [Fix C] MessageService.appendMessage role 适配 · 持久化边界把 role-less 消息映射为 Role.system。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: teammate 终端 task_status attachment
 * （{@code TeammateMessageFoldingChain.teammateTaskStatusAttachment}，恒 role=null，对齐 CC
 * AttachmentMessage 无 role 字段，attachments.ts:3201-3207）经 outboundSink → appendMessage 落库时，
 * {@code messages.role TEXT NOT NULL}（V1__init_schema.sql:63，无 DEFAULT）→ 修复前
 * {@code setRole(null)} 违反 NOT NULL，INSERT 必抛异常 → SpawnInProcess:287 catch 打"落库失败"
 * （MyBatis-Flex/SQLite 包装异常 getMessage() 常为 null/空，与主工作区 20:33 观察吻合）。
 * 修复 = 持久化边界把 null role 适配为 {@code Role.system}（系统生成元数据）；
 * 出站 DTO 保持 role=null 与 CC 契约一致（折叠链/前端判据是 author/subtype，不依赖 role）。
 */
class MessageServiceAppendRoleTest {

    private MessageService service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", mock(SessionMapper.class));
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
    }

    @Test
    @DisplayName("Fix C: role-less task_status attachment 落库时 role 适配为 Role.system（messages.role NOT NULL）")
    void appendMessage_rolelessAttachment_mapsRoleToSystem() {
        // WHY: role-less attachment 落库 → messages.role NOT NULL 违反 → 通知链断裂（折叠链无
        //   in_process_teammate 输入）。修复 = 持久化边界把 null role 适配为 Role.system。
        //   变异点：仍 setRole(null) → INSERT 违反 NOT NULL 抛异常 → 红。
        ChatMessageDto att = TeammateMessageFoldingChain.teammateTaskStatusAttachment(
            "t1", "alice", "completed", "sess-1");
        assertThat(att.role()).as("出站 DTO 保持 role=null（CC attachment 无 role，attachments.ts:3201-3207）").isNull();

        service.appendMessage(att);

        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getRole())
            .as("DB messages.role NOT NULL → null role 必须适配为 Role.system")
            .isEqualTo(Role.system.name());
    }
}

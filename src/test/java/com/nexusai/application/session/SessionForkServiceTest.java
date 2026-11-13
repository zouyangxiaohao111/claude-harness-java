package com.nexusai.application.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.ForkSessionResponse;
import com.nexusai.repository.session.entity.AttachmentRecord;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.entity.SessionFileRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.AttachmentMapper;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话分叉（截断复制）核心契约。
 *
 * <p><b>WHY（规则九）</b>：分叉最容易静默出错的三处——①截断点之后的消息混进来；
 * ②复制后消息仍互相引用**旧会话**的主键（前端归组/重试/compact 边界串线）；
 * ③产物 source_message_id 不重映射（全部落「历史产物」）。本测试把三处都钉死。
 */
class SessionForkServiceTest {

    private static final String SRC = "sess-test-fork-src";
    private static final String PIVOT = "msg-test-pivot";

    private SessionMapper sessionMapper;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private SessionFileMapper sessionFileMapper;
    private AttachmentMapper attachmentMapper;
    private SessionArtifactMapper sessionArtifactMapper;
    private SessionService sessionService;

    private final List<MessageRecord> insertedMessages = new ArrayList<>();
    private final List<ToolCallRecord> insertedToolCalls = new ArrayList<>();
    private final List<AttachmentRecord> insertedAttachments = new ArrayList<>();
    private final List<SessionArtifactRecord> insertedArtifacts = new ArrayList<>();
    private SessionRecord insertedSession;

    private SessionForkService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        sessionFileMapper = mock(SessionFileMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        sessionArtifactMapper = mock(SessionArtifactMapper.class);
        sessionService = mock(SessionService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new SessionForkService();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "sessionFileMapper", sessionFileMapper);
        ReflectionTestUtils.setField(service, "attachmentMapper", attachmentMapper);
        ReflectionTestUtils.setField(service, "sessionArtifactMapper", sessionArtifactMapper);
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "txManager", txManager);

        doAnswer(inv -> { insertedSession = inv.getArgument(0); return 1; }).when(sessionMapper).insert(any());
        doAnswer(inv -> { insertedMessages.add(inv.getArgument(0)); return 1; }).when(messageMapper).insert(any());
        doAnswer(inv -> { insertedToolCalls.add(inv.getArgument(0)); return 1; }).when(toolCallMapper).insert(any());
        doAnswer(inv -> { insertedAttachments.add(inv.getArgument(0)); return 1; }).when(attachmentMapper).insert(any());
        doAnswer(inv -> { insertedArtifacts.add(inv.getArgument(0)); return 1; }).when(sessionArtifactMapper).insert(any());
    }

    @Test
    @DisplayName("截断到 pivot：只复制前 2 条；引用 id 全部重映射；产物归属重映射；标题/清空/继承正确")
    void fork_truncatesRemapsAndRewritesOwnership() {
        SessionRecord src = session();
        when(sessionMapper.selectOneById(SRC)).thenReturn(src);

        MessageRecord m1 = message("m1", 1L, "user", "你好");
        m1.setUserMessageId("m1");
        m1.setUserAttachments("[{\"contentId\":\"10\",\"path\":\"C:/cache/image-cache/" + SRC + "/1.png\"}]");
        MessageRecord m2 = message("m2", 2L, "assistant", "回答");
        m2.setUserMessageId("m1");
        m2.setAssistantMessageId("m2");
        m2.setToolCallId("tc1");
        // 模拟 SQL 已按 seq <= pivotSeq 截断：第 3/4 轮不在返回结果里
        when(messageMapper.selectOneById(PIVOT)).thenReturn(m2);
        when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(m1, m2));

        ToolCallRecord tc = new ToolCallRecord();
        tc.setId("tc1");
        tc.setMessageId("m2");
        tc.setToolName("Read");
        when(toolCallMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(tc));

        AttachmentRecord att = new AttachmentRecord();
        att.setId(10L);
        att.setSessionId(SRC);
        att.setPath("C:/cache/image-cache/" + SRC + "/1.png");
        att.setSourceType("upload");
        when(attachmentMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(att));
        doAnswer(inv -> {
            AttachmentRecord r = inv.getArgument(0);
            r.setId(99L);
            insertedAttachments.add(r);
            return 1;
        }).when(attachmentMapper).insert(any());

        SessionArtifactRecord art = new SessionArtifactRecord();
        art.setId("art1");
        art.setSessionId(SRC);
        art.setKind("deliverable");
        art.setTitle("报告");
        art.setLocalPath(SessionArtifactService.sessionArtifactsDir(SRC)
            .resolve("deliverables/报告.md").toString());
        art.setSourceMessageId("m1");
        when(sessionArtifactMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(art));

        when(sessionFileMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.<SessionFileRecord>of());

        ForkSessionResponse res = service.fork(SRC, PIVOT);

        assertThat(res.copiedMessages()).isEqualTo(2);
        assertThat(insertedMessages).hasSize(2);
        assertThat(insertedMessages).extracting(MessageRecord::getId)
            .doesNotContain("m1", "m2")
            .doesNotHaveDuplicates();

        // 新会话行：标题 / 显式命名 / 计数 / 项目继承 / 状态清空
        assertThat(insertedSession.getTitle()).isEqualTo("原会话 分叉");
        assertThat(insertedSession.getTitleExplicit()).isEqualTo(1);
        assertThat(insertedSession.getMessageCount()).isEqualTo(2);
        assertThat(insertedSession.getMainProjectId()).isEqualTo("proj-1");
        assertThat(insertedSession.getModelTag()).isEqualTo("DS");
        assertThat(insertedSession.getEffortLevel()).isEqualTo("high");
        assertThat(insertedSession.getKbToolsEnabled()).isEqualTo(1);
        assertThat(insertedSession.getTodos()).isNull();
        assertThat(insertedSession.getGoal()).isNull();
        assertThat(insertedSession.getTeamContext()).isNull();
        assertThat(insertedSession.getPinned()).isNull();
        assertThat(insertedSession.getTotalCostYuan()).isNull();

        // 消息引用重映射：新 m1 的 id / m2.userMessageId / m2.assistantMessageId / m2.toolCallId
        String newM1Id = insertedMessages.get(0).getId();
        String newM2Id = insertedMessages.get(1).getId();
        assertThat(insertedMessages.get(1).getUserMessageId()).isEqualTo(newM1Id);
        assertThat(insertedMessages.get(1).getAssistantMessageId()).isEqualTo(newM2Id);
        assertThat(insertedMessages.get(1).getToolCallId()).isEqualTo(insertedToolCalls.get(0).getId());
        assertThat(insertedMessages.get(1).getSeq()).isEqualTo(2L);

        // tool_call 挂到新消息
        assertThat(insertedToolCalls).hasSize(1);
        assertThat(insertedToolCalls.get(0).getMessageId()).isEqualTo(newM2Id);

        // 产物：local_path 指向新会话目录；source_message_id 映射到新 m1
        String newSessionId = insertedSession.getId();
        assertThat(insertedArtifacts).hasSize(1);
        assertThat(insertedArtifacts.get(0).getSessionId()).isEqualTo(newSessionId);
        assertThat(insertedArtifacts.get(0).getSourceMessageId()).isEqualTo(newM1Id);
        assertThat(insertedArtifacts.get(0).getLocalPath())
            .startsWith(SessionArtifactService.sessionArtifactsDir(newSessionId).toString());

        // 附件：会话目录段改写 + contentId 重映射
        assertThat(insertedAttachments).hasSize(1);
        assertThat(insertedAttachments.get(0).getPath())
            .contains(newSessionId)
            .doesNotContain(SRC);
        assertThat(insertedMessages.get(0).getUserAttachments())
            .contains("\"contentId\":\"99\"")
            .contains(newSessionId);
    }

    @Test
    @DisplayName("截断点之后的消息不在复制集合里时，条数与引用都以实际查询结果为准")
    void fork_copiesOnlyRowsReturnedByTruncatedQuery() {
        when(sessionMapper.selectOneById(SRC)).thenReturn(session());
        MessageRecord only = message("m1", 1L, "user", "唯一一条");
        only.setUserMessageId("m1");
        when(messageMapper.selectOneById(PIVOT)).thenReturn(only);
        when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(only));
        when(toolCallMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        when(attachmentMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        when(sessionArtifactMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        when(sessionFileMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

        ForkSessionResponse res = service.fork(SRC, PIVOT);

        assertThat(res.copiedMessages()).isEqualTo(1);
        assertThat(insertedMessages).hasSize(1);
    }

    private static SessionRecord session() {
        SessionRecord s = new SessionRecord();
        s.setId(SRC);
        s.setTitle("原会话");
        s.setModelTag("DS");
        s.setModelName("DeepSeek");
        s.setMainProjectId("proj-1");
        s.setEffortLevel("high");
        s.setKbToolsEnabled(1);
        s.setTodos("{\"k\":[]}");
        s.setGoal("{\"text\":\"目标\"}");
        s.setTeamContext("{\"teamName\":\"t\"}");
        s.setPinned(1);
        s.setTotalCostYuan(1.2);
        s.setMessageCount(4);
        return s;
    }

    private static MessageRecord message(String id, long seq, String role, String content) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(SRC);
        m.setRole(role);
        m.setContent(content);
        m.setSeq(seq);
        m.setCreatedAt("2026-11-13T10:00:00+08:00");
        return m;
    }
}

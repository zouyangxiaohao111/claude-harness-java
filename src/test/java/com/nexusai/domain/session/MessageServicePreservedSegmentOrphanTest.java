package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 裁剪后清理「悬挂保留段」· {@code MessageService.clearPreservedSegmentsPointingAtDeletedRows}。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 这个缺陷的真实形态）</b>：用户裁剪对话时删掉的某条消息，可能正是
 * 某个 compact boundary 上 {@code preservedSegment.tailUuid} 指向的那一行。删完之后接线图成了
 * <b>悬挂指针</b>：
 * <ul>
 *   <li>读侧 {@code BoundaryReader.applyPreservedSegmentRelink} 判「head/tail 不在列表内」→
 *       整段 kept 放弃重挂（压缩白做），并且因为每次 post-sampling hook 都跑一次 → 一轮刷几十行 ERROR；</li>
 *   <li>本机实证：某会话 trim 掉 pivot {@code fc566adb…} 后，boundary 的 tailUuid 正指向它，
 *       此后该会话每次读上下文都报错。</li>
 * </ul>
 *
 * <p><b>变异点（测试必须能抓住）</b>：
 * <ul>
 *   <li>不清理 → 悬挂指针留存，读侧持续放弃重挂 → 红（本用例断言 metadata 被改写）；</li>
 *   <li>三个引用任一个缺失就算悬挂（只查 tail）→ head 被删的场景漏清 → 红；</li>
 *   <li>「引用全在」时误清（把好接线图摘掉）→ kept 段丢失 → 红（本用例的正向对照）；</li>
 *   <li>清理时机放在删除<b>之前</b>（用旧表状态判定）→ 永远判「都还在」→ 红。</li>
 * </ul>
 */
class MessageServicePreservedSegmentOrphanTest {

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;
    private ToolCallMapper toolCallMapper;

    private static final String SESSION = "sess-orphan";

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

    private static MessageRecord boundary(String id, String head, String anchor, String tail) {
        MessageRecord b = new MessageRecord();
        b.setId(id);
        b.setSessionId(SESSION);
        b.setRole("system");
        b.setSubtype("compact_boundary");
        b.setCompactMetadata("{\"trigger\":\"auto\",\"preTokens\":12345,"
            + "\"preservedSegment\":{\"headUuid\":\"" + head + "\","
            + "\"anchorUuid\":\"" + anchor + "\",\"tailUuid\":\"" + tail + "\"}}");
        return b;
    }

    private void givenPivotAndDeletion(long pivotSeq) {
        MessageRecord pivot = new MessageRecord();
        pivot.setId("m-pivot");
        pivot.setSessionId(SESSION);
        pivot.setSeq(pivotSeq);
        when(messageMapper.selectOneByQuery(any())).thenReturn(pivot);
        when(messageMapper.deleteByQuery(any())).thenReturn(3);
    }

    @Test
    @DisplayName("⭐ tailUuid 被裁掉 ⇒ 摘除 preservedSegment，其余元数据保留（读侧改走「无保留段」）")
    void tailDeleted_clearsSegment() {
        givenPivotAndDeletion(200L);
        MessageRecord b = boundary("b-1", "head-1", "anchor-1", "tail-1");
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(b));
        // 3 个引用只剩 2 个活着（tail-1 被 trim 删掉了）
        when(messageMapper.selectCountByQuery(any())).thenReturn(2L);

        int deleted = service.trimSessionAfter(SESSION, "m-pivot");

        assertThat(deleted).isEqualTo(3);
        ArgumentCaptor<MessageRecord> patch = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).update(patch.capture());
        String meta = patch.getValue().getCompactMetadata();
        assertThat(patch.getValue().getId()).as("只改 boundary 那一行").isEqualTo("b-1");
        assertThat(meta)
            .as("悬挂接线图必须摘除，否则读侧每次投影都放弃重挂并刷 ERROR")
            .doesNotContain("preservedSegment");
        assertThat(meta)
            .as("其余压缩元数据原样保留（摘的是接线图，不是整条 boundary 的元数据）")
            .contains("preTokens")
            .contains("auto");
    }

    @Test
    @DisplayName("⭐ 只有 headUuid 被删也照样清理（不是只盯 tail）")
    void headDeleted_clearsSegment() {
        givenPivotAndDeletion(200L);
        when(messageMapper.selectListByQuery(any()))
            .thenReturn(List.of(boundary("b-2", "head-x", "anchor-x", "tail-x")));
        when(messageMapper.selectCountByQuery(any())).thenReturn(2L);

        service.trimSessionAfter(SESSION, "m-pivot");

        ArgumentCaptor<MessageRecord> patch = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).update(patch.capture());
        assertThat(patch.getValue().getCompactMetadata()).doesNotContain("preservedSegment");
    }

    @Test
    @DisplayName("正向对照：三个引用都还在 ⇒ 不动这条 boundary（好接线图不能被误摘）")
    void allRefsAlive_keepsSegment() {
        givenPivotAndDeletion(200L);
        when(messageMapper.selectListByQuery(any()))
            .thenReturn(List.of(boundary("b-3", "head-ok", "anchor-ok", "tail-ok")));
        when(messageMapper.selectCountByQuery(any())).thenReturn(3L);

        service.trimSessionAfter(SESSION, "m-pivot");

        verify(messageMapper, never()).update(any(MessageRecord.class));
    }

    @Test
    @DisplayName("无 boundary ⇒ 零额外 UPDATE（不打扰没有压缩过的会话）")
    void noBoundary_noUpdate() {
        givenPivotAndDeletion(200L);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of());

        service.trimSessionAfter(SESSION, "m-pivot");

        verify(messageMapper, never()).update(any(MessageRecord.class));
        verify(messageMapper, never()).selectCountByQuery(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("⭐ 单条删除（delete）同样收口 —— 第三条删行路径不能漏")
    void singleDelete_alsoClearsSegment() {
        // WHY: 删行路径有三条 —— trimSessionAfter（区间）/ replaceSessionMessages（全量重写）/
        //   delete（单条，含 assistant 连带删其 tool_result）。首版修复只挂前两条，**漏了 delete**
        //   ⇒ 用户从界面删单条消息后仍会留下悬挂接线图。变异点：删掉 delete 里的收口调用 → 红。
        MessageRecord target = new MessageRecord();
        target.setId("m-single");
        target.setSessionId(SESSION);
        target.setRole("user");
        when(messageMapper.selectOneById("m-single")).thenReturn(target);
        when(messageMapper.selectListByQuery(any()))
            .thenReturn(List.of(boundary("b-d", "head-d", "anchor-d", "tail-d")));
        when(messageMapper.selectCountByQuery(any())).thenReturn(2L);

        service.delete("m-single");

        verify(messageMapper).deleteById("m-single");
        ArgumentCaptor<MessageRecord> patch = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).update(patch.capture());
        assertThat(patch.getValue().getCompactMetadata())
            .as("单条删除后同样不能留下悬挂接线图")
            .doesNotContain("preservedSegment");
    }

    @Test
    @DisplayName("delete 删的是没有 sessionId 的行 ⇒ 不去扫 boundary（不越界）")
    void singleDelete_withoutSession_noScan() {
        MessageRecord orphan = new MessageRecord();
        orphan.setId("m-nosession");
        when(messageMapper.selectOneById("m-nosession")).thenReturn(orphan);

        service.delete("m-nosession");

        verify(messageMapper).deleteById("m-nosession");
        verify(messageMapper, never()).selectListByQuery(any());
    }
}

package com.nexusai.apis.export;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P2-21 2026-09-12] 导出 Markdown 必须消费 V70 两标记列（{@code is_compact_summary} /
 * {@code is_visible_in_transcript_only}）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：旧实现 {@code renderMarkdown} 只按 {@code role}
 * 分派 → compact 摘要被当成普通 {@code ## User} 导出、仅 transcript 可见的行也被导出。
 * CC 导出真源（{@code commands/export/export.tsx} → {@code utils/exportRenderer.tsx} 以
 * {@code screen="prompt"} 渲染 {@code <Messages>} → {@code messages.ts:500 isTranscriptMode=false}）：
 * <ul>
 *   <li>{@code isVisibleInTranscriptOnly=true} 的 user 消息命中 {@code messages.ts:5115}
 *       {@code if (message.isVisibleInTranscriptOnly & !isTranscriptMode) return false} →
 *       <b>整条不渲染</b>；</li>
 *   <li>{@code isCompactSummary=true} 的 user 消息走 {@code Message.tsx:141 <CompactSummary>}
 *       <b>专用渲染</b>（不是普通用户气泡）。</li>
 * </ul>
 *
 * <p><b>RED 条件</b>：删掉 {@code renderMarkdown} 里的 ① skip 分支 → transcript-only 行重新出现在
 * 导出正文；删掉 ② 摘要分支 → 摘要行回落 {@code ## User}。
 */
@DisplayName("[P2-21] 导出 md 读 V70 两标记列（transcript-only 不导出 / 摘要专用小节）")
class ExportControllerTranscriptFlagsTest {

    private static final String SESSION = "sess-export-flags";

    private ExportController controller;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        controller = new ExportController();
        messageMapper = mock(MessageMapper.class);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getById(anyString())).thenReturn(sessionDto());
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "messageMapper", messageMapper);
    }

    @Test
    @DisplayName("transcript-only 行不导出 · 摘要行出独立小节 · 普通行渲染不变")
    void rendersTranscriptFlags() {
        MessageRecord plainUser = row("u1", "user", "普通提问", null, null);
        MessageRecord summary = row("s1", "user", "【压缩摘要正文】", true, null);
        MessageRecord transcriptOnly = row("t1", "user", "【仅 transcript 可见的行】", null, true);
        MessageRecord assistant = row("a1", "assistant", "普通回复", null, null);
        when(messageMapper.selectListByQuery(any(QueryWrapper.class)))
            .thenReturn(List.of(plainUser, summary, transcriptOnly, assistant));

        String body = controller.export(SESSION, "md").getBody();

        assertThat(body).as("导出正文非空").isNotNull();
        // ① 普通 user 行：渲染不变（回归守卫）
        assertThat(body).contains("## User").contains("普通提问");
        // ② 摘要行：走独立小节，不再冒充 ## User
        assertThat(body)
            .as("is_compact_summary=true 必须出独立小节（CC Message.tsx:141 CompactSummary 专用渲染）")
            .contains("## Compact Summary")
            .contains("【压缩摘要正文】");
        // ③ 仅 transcript 可见的行：整条不导出（CC shouldShowUserMessage + !isTranscriptMode → false）
        assertThat(body)
            .as("is_visible_in_transcript_only=true 的 user 行不得出现在导出正文（CC messages.ts:5115）")
            .doesNotContain("【仅 transcript 可见的行】");
        // ④ 普通 assistant 行不受影响
        assertThat(body).contains("## Assistant").contains("普通回复");
    }

    @Test
    @DisplayName("摘要正文不得出现在 '## User' 小节之后（防「当普通用户消息导出」回归）")
    void summaryBodyIsNotRenderedAsPlainUser() {
        MessageRecord summary = row("s1", "user", "【压缩摘要正文】", true, null);
        when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(summary));

        String body = controller.export(SESSION, "md").getBody();

        assertThat(body).as("正文里不得出现 '## User' 小节（该行是摘要，不是用户输入）").doesNotContain("## User");
        assertThat(body).contains("## Compact Summary");
        // 摘要正文与其小节标题相邻出现（顺序：标题 → 正文）
        assertThat(body.indexOf("## Compact Summary")).isLessThan(body.indexOf("【压缩摘要正文】"));
    }

    @Test
    @DisplayName("[P2-21 裁决 b] 两标志并存的压缩摘要必须导出正文（有意偏离 CC 判定顺序）")
    void bothFlagsSummaryStillExported() {
        // WHY 这条重要：full / SM compact 的摘要**同时带两标志**（CC compact.ts:643-650）。
        //   CC 的判定序是「先 isVisibleInTranscriptOnly 后 isCompactSummary」→ 该行先被 ① 命中
        //   → 导出里**整条不出现**。但 .md 是归档件（没有实时 UI 兜底），丢摘要 = 丢压缩上下文
        //   ⇒ 用户裁定改为「先 isCompactSummary」（裁决 b）：带摘要标志的行一律走摘要小节。
        //   本断言正是「两标志并存」这一唯一有分歧的形态 —— 若判定序被改回 CC 原序，本测试必红。
        MessageRecord bothFlags = row("b1", "user", "【两标志并存的摘要正文】", true, true);
        when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(bothFlags));

        String body = controller.export(SESSION, "md").getBody();

        assertThat(body)
            .as("两标志并存时必须仍导出摘要正文（归档件不得丢失压缩上下文）—— 裁决 b 的核心断言")
            .contains("## Compact Summary")
            .contains("【两标志并存的摘要正文】");
        assertThat(body).as("摘要行不得冒充普通用户消息").doesNotContain("## User");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一行消息（两标记列可空 —— null 等价「V70 前老行 / 未接线」，读侧 Boolean.TRUE.equals 容错）。 */
    private static MessageRecord row(String id, String role, String content,
                                     Boolean isCompactSummary, Boolean isVisibleInTranscriptOnly) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(SESSION);
        m.setRole(role);
        m.setAuthor(role);
        m.setContent(content);
        m.setCreatedAt(OffsetDateTime.now().toString());
        m.setIsCompactSummary(isCompactSummary);
        m.setIsVisibleInTranscriptOnly(isVisibleInTranscriptOnly);
        return m;
    }

    private static SessionDto sessionDto() {
        return new SessionDto(SESSION, ModelTag.DS, "test-model", "导出示例", "刚刚", SessionGroup.current,
            null, null, null, OffsetDateTime.now(), OffsetDateTime.now(),
            null, null, null, null, null, null, null, null, null);
    }
}

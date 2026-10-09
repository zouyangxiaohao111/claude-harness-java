package com.nexusai.application.chat;

import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sm-boundary-reload] {@code ChatService.filterCompactBoundaryRows} 契约测试（即时层筛行的单点判据）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：即时层把「压缩落库的返回行集」经 stream 通道推
 * {@code message.insert}，前端拿到后直接插进消息列表显示「已压缩 · 对话历史已总结」分割线。
 * 推送必须<b>只含 boundary 行</b>：
 * <ul>
 *   <li>多推（把 summary / kept 消息一起 insert）→ 前端重复插行（同 id 幂等可挡重复，但会把
 *       <b>整段摘要正文</b>当新消息插到列表尾 → 聊天区出现重复内容）；</li>
 *   <li>漏推（boundary 被判非 boundary）→ 分割线仍然不出现（本次修复的目标失效）；</li>
 *   <li>落库返回集可能含 null / 顺序不定 ⇒ 筛选必须容忍 null 并保持相对顺序。</li>
 * </ul>
 * 判据复用 {@code BoundaryReader.isCompactBoundaryMessage} 单一来源（role=system && subtype=compact_boundary，
 * messages.ts:4608），本测试同时钉死「非 boundary 的 system 行（microcompact）与同 subtype 的非 system 行都不入选」。
 */
class ChatServiceBoundaryRowFilterTest {

    @Test
    @DisplayName("只留 compact boundary：system+compact_boundary 入选；user/summary、null 剔除（保序）")
    void filter_keepsOnlyCompactBoundary() {
        ChatMessageDto boundary = compactBoundary();
        ChatMessageDto summary = row("summary-1", Role.user, "summary");

        assertThat(ChatService.filterCompactBoundaryRows(
                Arrays.<ChatMessageDto>asList(boundary, summary, null)))
            .as("恰为 [boundary]：非 boundary 行（user/summary）与 null 均不得进入 message.insert 载荷")
            .containsExactly(boundary);
    }

    @Test
    @DisplayName("非 boundary 的其它形态：microcompact_boundary（system）与同 subtype 的非 system 行都不入选")
    void filter_excludesNonCompactBoundaryShapes() {
        ChatMessageDto micro = CompactBoundaryMessage
            .createMicrocompactBoundaryMessage("auto", 100, 50, List.of(), List.of())
            .toChatMessageDto();
        ChatMessageDto compactSubtypeButNotSystem = row("u-1", Role.user, "compact_boundary");

        assertThat(ChatService.filterCompactBoundaryRows(List.of(micro, compactSubtypeButNotSystem)))
            .as("microcompact_boundary 不在判别范围（CC 仅匹配 compact_boundary）；role≠system 不入选")
            .isEmpty();
    }

    @Test
    @DisplayName("边界输入：空列表 / null 列表 / 纯 null 元素 → 空结果，不抛")
    void filter_handlesEmptyAndNullInputs() {
        assertThat(ChatService.filterCompactBoundaryRows(List.of()))
            .as("空列表 → 空结果")
            .isEmpty();
        assertThat(ChatService.filterCompactBoundaryRows(null))
            .as("null 列表 → 空结果（落库未武装时返回入参可能为 null）")
            .isEmpty();
        assertThat(ChatService.filterCompactBoundaryRows(Arrays.<ChatMessageDto>asList(null, null)))
            .as("纯 null 元素 → 空结果（不得 NPE）")
            .isEmpty();
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** 真 boundary 行（生产同源工厂：CompactBoundaryMessage.toChatMessageDto）。 */
    private static ChatMessageDto compactBoundary() {
        return CompactBoundaryMessage.createCompactBoundaryMessage("auto", 100, null, null, null)
            .toChatMessageDto();
    }

    /**
     * 指定 role/subtype 的普通行 · 用既有 21 参构造器（…isError, subtype —— 本测试只需要
     * role/subtype 两个判别字段，其余给最小合法值）。
     */
    private static ChatMessageDto row(String id, Role role, String subtype) {
        return new ChatMessageDto(
            id, "sess-boundary-filter", role, role == Role.user ? "user" : "system",
            "content", null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of(), null,
            false, false, subtype);
    }
}

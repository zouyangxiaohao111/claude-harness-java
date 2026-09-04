package com.nexusai.application.agent;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmAgentLoop.defendOrphanToolResults resume 防御测试（vision-cc-align 2026-09-03）。
 *
 * <p>WHY（规则 9）：DB 分离存储（assistant tool_calls 在 tool_calls 表、role=tool tool_result 独立行
 * 不挂 FK）在中断/裁剪下残留<b>无前置 assistant tool_calls 的孤儿 tool_result</b>（实证 sess-4b06118b
 * 11 条）→ resume 原样注入 OpenAI 400 'role tool must follow tool_calls'。本防御对孤儿 tool_result 做
 * <b>snip 剔除</b>（不注入 LLM；用户拍板 2026-09-03：不折叠伪造 user 文本，DB/前端历史不受影响），
 * 正常配对 tool_result 原样保留不误伤 —— 若回退（不剔除/误剔除）本测试即红。
 */
class LlmAgentLoopResumeOrphanDefenseTest {

    private static ChatMessageDto message(String id, Role role, String content, String toolCallId,
                                         List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(
            id, "sess-1", role, "user", content, null,
            toolCalls, null, null, null, null, null,
            toolCallId, null, null, null, null, null,
            false, false, null, null);   // isMeta=false, isError=false（primitive boolean 不可 null）
    }

    private static ToolCallDto toolCall(String id) {
        return new ToolCallDto(id, "Bash", "{}", null, false);
    }

    @Test
    @DisplayName("正常配对：assistant(tool_calls=[t1,t2]) + tool_result(t1)(t2) → 全部原样保留 role=tool（不误伤）")
    void pairedToolResults_preservedAsTool() {
        ChatMessageDto asst = message("a1", Role.assistant, "", null,
            List.of(toolCall("t1"), toolCall("t2")));
        ChatMessageDto r1 = message("r1", Role.tool, "结果1", "t1", null);
        ChatMessageDto r2 = message("r2", Role.tool, "结果2", "t2", null);

        List<ChatMessageDto> out = LlmAgentLoop.defendOrphanToolResults(List.of(asst, r1, r2));

        assertThat(out).hasSize(3);
        assertThat(out.get(0).role()).isEqualTo(Role.assistant);
        assertThat(out.get(1).role()).isEqualTo(Role.tool);
        assertThat(out.get(2).role()).isEqualTo(Role.tool);
        assertThat(out.get(1).content()).isEqualTo("结果1"); // 内容未改动
    }

    @Test
    @DisplayName("孤儿 tool_result（无前置 assistant tool_calls）→ snip 剔除（不注入 LLM），非折叠伪造 user 文本")
    void orphanToolResult_snippedFromResume() {
        ChatMessageDto orphan = message("o1", Role.tool, "孤立结果", "call_orphan", null);

        List<ChatMessageDto> out = LlmAgentLoop.defendOrphanToolResults(List.of(orphan));

        assertThat(out)
            .as("孤儿 tool_result 不注入 LLM（协议无效：无前置 tool_calls → OpenAI 400）—— snip 剔除语义；"
                + "DB/前端历史不受影响（本方法只作用于 resume 注入副本）")
            .isEmpty();
    }

    @Test
    @DisplayName("混合：配对 tool_result 保留 + 孤儿 tool_result 折叠，纯文本 user 不受影响")
    void mixed_pairedKeptOrphanFolded() {
        ChatMessageDto asst = message("a1", Role.assistant, "", null, List.of(toolCall("t1")));
        ChatMessageDto r1 = message("r1", Role.tool, "正常结果", "t1", null);
        ChatMessageDto orphan = message("o1", Role.tool, "孤儿结果", "call_x", null);
        ChatMessageDto plain = message("u1", Role.user, "普通问题", null, null);

        List<ChatMessageDto> out = LlmAgentLoop.defendOrphanToolResults(
            new ArrayList<>(List.of(asst, r1, orphan, plain)));

        assertThat(out)
            .as("配对 tool_result 保留 + 孤儿 snip 剔除 + 纯文本 user 不动")
            .hasSize(3);
        assertThat(out.get(1).role()).isEqualTo(Role.tool);   // 配对 → 保留
        assertThat(out.get(2).role()).isEqualTo(Role.user);   // 纯文本不动
        assertThat(out.get(2).content()).isEqualTo("普通问题");
        assertThat(out.get(1).content()).isEqualTo("正常结果"); // 配对内容未改
    }

    // ── [对齐 CC] filterIncompleteAssistantToolCalls：删含未完成 tool_calls 的 assistant ──

    @Test
    @DisplayName("filterIncomplete：assistant 的 tool_calls 全部有 result → 保留（不误删完整轮）")
    void filterIncomplete_allResolved_kept() {
        ChatMessageDto asst = message("a1", Role.assistant, "", null,
            List.of(toolCall("t1"), toolCall("t2")));
        ChatMessageDto r1 = message("r1", Role.tool, "R1", "t1", null);
        ChatMessageDto r2 = message("r2", Role.tool, "R2", "t2", null);

        List<ChatMessageDto> out = LlmAgentLoop.filterIncompleteAssistantToolCalls(
            List.of(asst, r1, r2));

        assertThat(out)
            .as("tool_calls 全部闭合 → assistant 保留")
            .extracting(ChatMessageDto::role)
            .containsExactly(Role.assistant, Role.tool, Role.tool);
    }

    @Test
    @DisplayName("filterIncomplete：assistant 带 tool_calls 但部分无 result（工具执行中被中断）→ 整条 assistant 删除（CC ANY 语义）")
    void filterIncomplete_partialUnresolved_assistantRemoved() {
        ChatMessageDto asst = message("a1", Role.assistant, "", null,
            List.of(toolCall("t1"), toolCall("t2"))); // t2 无 result（执行中被打断）
        ChatMessageDto r1 = message("r1", Role.tool, "R1", "t1", null);

        List<ChatMessageDto> out = LlmAgentLoop.filterIncompleteAssistantToolCalls(List.of(asst, r1));

        assertThat(out)
            .as("含未完成 tool_calls 的 assistant 整条删除（对齐 CC filterIncompleteToolCalls runAgent.ts:866）")
            .extracting(ChatMessageDto::role)
            .containsExactly(Role.tool);   // 只剩已完成那条 tool_result，由后续 defend 清成孤
    }

    @Test
    @DisplayName("串联（filter + defend）：工具执行中被打断的半轮整体作废，后续 user 保留")
    void combined_partialToolTurn_discardedFromModel() {
        ChatMessageDto asst = message("a1", Role.assistant, "", null,
            List.of(toolCall("t1"), toolCall("t2"))); // t2 未完成
        ChatMessageDto r1 = message("r1", Role.tool, "R1", "t1", null);
        ChatMessageDto next = message("u1", Role.user, "继续", null, null);

        List<ChatMessageDto> filtered = LlmAgentLoop.filterIncompleteAssistantToolCalls(
            new ArrayList<>(List.of(asst, r1, next)));
        List<ChatMessageDto> out = LlmAgentLoop.defendOrphanToolResults(filtered);

        assertThat(out)
            .as("半轮（assistant + 已完成 tool_result）整体从模型上下文作废，后续 user 保留 —— 模型从上一完整点重来")
            .extracting(ChatMessageDto::role)
            .containsExactly(Role.user);
    }
}

package com.nexusai.application.agent.session;

import com.nexusai.application.agent.session.SessionResumeDeserializer.InterruptionKind;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S1] 会话恢复补中断语义 · 对齐 CC conversationRecovery.ts:167-255 deserializeMessagesWithInterruptDetection。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：Java 从 DB messages 原始行恢复（created_at ASC），无中断检测 /
 * tool_use 配对过滤 / "Continue" sentinel 注入 —— 中断 turn 恢复后"有问无答"。本测试锁定六个分支
 * （对齐计划 §4 测试锚点）：
 * <ol>
 *   <li>未配对 tool_use 剥离（assistant 全部 tool_use 无 tool_result 配对 → 删）</li>
 *   <li>孤立 thinking-only 剥离（assistant 仅 reasoning 且无同 assistantMessageId 非 thinking → 删）</li>
 *   <li>纯空白 assistant 剥离（content 仅空白 + reasoning 空白 → 删）</li>
 *   <li>末条 assistant → none（完成 turn）</li>
 *   <li>末条 tool_result 终端工具（SendUserMessage）→ none（brief 模式完成）</li>
 *   <li>末条 tool_result 非终端 → interrupted_turn（统一为 interrupted_prompt）+ Continue 注入</li>
 *   <li>末条纯 user → interrupted_prompt + No response requested. sentinel 注入</li>
 *   <li>空列表 → none</li>
 * </ol>
 * 变异点：任一分支语义缺失 → 中断 turn 恢复仍"有问无答"（回退到原始行直通）。
 */
@DisplayName("[S1] SessionResumeDeserializer：会话恢复补中断语义（对齐 CC conversationRecovery.ts:167-255）")
class SessionResumeDeserializerTest {

    // ── helpers（20 参兼容构造器：…structuredOutput, isMeta, isError）──

    private static ChatMessageDto user(String id, String content) {
        return msg(id, Role.user, content, null, null, null, null, false, false);
    }

    private static ChatMessageDto toolResult(String id, String toolCallId) {
        return msg(id, Role.tool, "result:" + toolCallId, null, null, toolCallId, null, false, false);
    }

    private static ChatMessageDto assistant(String id, String content) {
        return msg(id, Role.assistant, content, null, null, null, null, false, false);
    }

    private static ChatMessageDto assistantWithTools(String id, String assistantMessageId,
                                                     List<ToolCallDto> tools) {
        return msg(id, Role.assistant, null, null, tools, null, assistantMessageId, false, false);
    }

    private static ChatMessageDto thinkingOnly(String id, String assistantMessageId) {
        return msg(id, Role.assistant, " ", "think...", null, null, assistantMessageId, false, false);
    }

    private static ChatMessageDto whitespaceOnly(String id) {
        return msg(id, Role.assistant, "  \n  ", null, null, null, null, false, false);
    }

    private static ChatMessageDto system(String id) {
        return msg(id, Role.system, "sys", null, null, null, null, false, false);
    }

    /** 26 参 ER-IMP-11 构造器（…isApiErrorMessage/apiError/error/errorDetails）· 20 参构造器不暴露该字段。 */
    private static ChatMessageDto apiErrorAssistant(String id) {
        return new ChatMessageDto(id, "sess-x", Role.assistant, "author", "api err", null,
            null, null, null, null, "刚刚", null, null, null,
            null, List.of(), List.of(), null, false, false,
            null, null,
            true, "max_output_tokens", "err", "details");
    }

    private static ChatMessageDto msg(String id, Role role, String content, String reasoning,
                                      List<ToolCallDto> tools, String toolCallId,
                                      String assistantMessageId, boolean isMeta, boolean isError) {
        return new ChatMessageDto(id, "sess-x", role, "author", content, reasoning, tools,
            null, null, null, "刚刚", null, toolCallId, assistantMessageId,
            null, List.of(), List.of(), null, isMeta, isError);
    }

    // ── 分支 1: 未配对 tool_use 剥离 ──

    @Test
    @DisplayName("未配对 tool_use：assistant 全部 tool_use 无 tool_result 配对 → 删（CC messages.ts:3149-3199）")
    void filtersUnresolvedToolUses() {
        // WHY: 中断 turn 的 assistant 可能只写了 tool_use 块未收到 tool_result（kill 中止）——
        //   直接重放会让 LLM 看到"有 tool_use 无结果"半吊子回合 → 必须剥离。
        ChatMessageDto a = assistantWithTools("a1", "asst-1", List.of(new ToolCallDto("t1", "Bash", "{}", null, null)));
        ChatMessageDto u = user("u1", "please"); // 无 t1 的 tool_result

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(u, a));

        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("未配对 tool_use assistant 必须剥离")
            .contains("u1").doesNotContain("a1");
        assertThat(result.interruption()).as("剥离后末条为 user → interrupted_prompt").isEqualTo(InterruptionKind.INTERRUPTED_PROMPT);
    }

    @Test
    @DisplayName("已配对 tool_use：assistant tool_use 有对应 tool_result → 保留；末条非终端 → interrupted_turn")
    void keepsPairedToolUses() {
        // WHY: 完整 turn 的 tool_use + tool_result 配对是合法历史，不得误删。
        //   末条是非终端工具（Bash）tool_result 且无 assistant 文本 → 中断 turn（CC :313-324）。
        ChatMessageDto a = assistantWithTools("a1", "asst-1", List.of(new ToolCallDto("t1", "Bash", "{}", null, null)));
        ChatMessageDto tr = toolResult("tr1", "t1");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(a, tr));

        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("已配对 tool_use + tool_result 均保留（含中断语义注入）")
            .contains("a1", "tr1");
        assertThat(result.interruption())
            .as("非终端 tool_result 末条 → interrupted_turn 统一为 interrupted_prompt")
            .isEqualTo(InterruptionKind.INTERRUPTED_PROMPT);
        assertThat(result.messages()).extracting(ChatMessageDto::content)
            .as("中断 turn 注入 Continue meta user")
            .contains("Continue from where you left off.");
    }

    // ── 分支 2: 孤立 thinking-only 剥离 ──

    @Test
    @DisplayName("孤立 thinking-only：assistant 仅 reasoning 且无同 assistantMessageId 非 thinking → 删")
    void filtersOrphanedThinkingOnly() {
        // WHY: 流式按 content block 分离产生纯 thinking 消息（同 message.id 分片）；加载时无同 id
        //   非 thinking 合并 → API "thinking blocks cannot be modified" 400（CC messages.ts:5440-5519）。
        ChatMessageDto a = thinkingOnly("a1", "asst-1"); // 仅 reasoning，无同 id 非 thinking
        ChatMessageDto u = user("u1", "hello");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(a, u));

        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("孤立 thinking-only assistant 必须剥离")
            .contains("u1").doesNotContain("a1");
    }

    @Test
    @DisplayName("有同 assistantMessageId 非 thinking → thinking-only 保留（后续合并）")
    void keepsThinkingWithSibling() {
        // WHY: 同 message.id 的 thinking 块 + 文本块会在 normalizeMessagesForAPI 合并 —— 非孤立即保留。
        ChatMessageDto t = thinkingOnly("a-t", "asst-2");
        // 同 asst-2 的 assistant 含非 thinking 内容（content 非空）→ 非孤立即保留
        ChatMessageDto a = new ChatMessageDto("a-c", "sess-x", Role.assistant, "author", "real text", null,
            null, null, null, null, "刚刚", null, null, "asst-2",
            null, List.of(), List.of(), null, false, false);

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(t, a));

        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("有同 assistantMessageId 非 thinking → thinking-only 保留")
            .containsExactly("a-t", "a-c");
    }

    // ── 分支 3: 纯空白 assistant 剥离 ──

    @Test
    @DisplayName("纯空白 assistant：content 仅空白 + reasoning 空白 → 删（CC messages.ts:5328-5379）")
    void filtersWhitespaceOnlyAssistant() {
        // WHY: 模型输出 "\n\n" 后用户取消 —— 空白文本无语义价值，且违反 API text block 非空约束。
        ChatMessageDto a = whitespaceOnly("a1");
        ChatMessageDto u = user("u1", "real");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(a, u));

        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("纯空白 assistant 必须剥离")
            .contains("u1").doesNotContain("a1");
    }

    // ── 分支 4: 末条 assistant → none ──

    @Test
    @DisplayName("末条 assistant → none（完成 turn，无中断）")
    void lastAssistant_isNone() {
        // WHY: 过滤未配对 tool_use 后 assistant 末条 = 正常完成（CC :300-307 stop_reason 恒 null）。
        ChatMessageDto u = user("u1", "hi");
        ChatMessageDto a = assistant("a1", "reply");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(u, a));

        assertThat(result.interruption()).isEqualTo(InterruptionKind.NONE);
        assertThat(result.messages()).extracting(ChatMessageDto::id).containsExactly("u1", "a1");
    }

    // ── 分支 5: 末条 tool_result 终端工具 → none ──

    @Test
    @DisplayName("末条 tool_result 终端工具（SendUserMessage）→ none（brief 模式完成，不注入幻影 Continue）")
    void lastTerminalToolResult_isNone() {
        // WHY: brief 模式（CC #20467）SendUserMessage 后无 assistant 文本 —— 转录止于工具结果 = 完成，
        //   非中断（否则 resume 误判 every brief 会话为中断并注入幻影 Continue）。
        ChatMessageDto a = assistantWithTools("a1", "asst-1",
            List.of(new ToolCallDto("t1", "SendUserMessage", "{}", null, null)));
        ChatMessageDto tr = toolResult("tr1", "t1");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(a, tr));

        assertThat(result.interruption())
            .as("末条为 SendUserMessage tool_result → 终端完成 none")
            .isEqualTo(InterruptionKind.NONE);
        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("terminal tool_result 不注入 Continue（仅 splice sentinel 保持交替合法）")
            .contains("a1", "tr1");
        assertThat(result.messages()).extracting(ChatMessageDto::content)
            .doesNotContain("Continue from where you left off.");
    }

    // ── 分支 6: 末条 tool_result 非终端 → interrupted_turn + Continue ──

    @Test
    @DisplayName("末条 tool_result 非终端 → interrupted_turn（统一 interrupted_prompt）+ Continue meta user")
    void lastNonTerminalToolResult_isInterruptedTurn() {
        // WHY: 非终端工具（如 Bash）中途 kill → 末条 tool_result 无后续 assistant 文本 = 中断 turn；
        //   注入「Continue from where you left off.」meta user 使恢复后 LLM 继续应答（CC :213-224）。
        ChatMessageDto a = assistantWithTools("a1", "asst-1", List.of(new ToolCallDto("t1", "Bash", "{}", null, null)));
        ChatMessageDto tr = toolResult("tr1", "t1");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(a, tr));

        assertThat(result.interruption()).isEqualTo(InterruptionKind.INTERRUPTED_PROMPT);
        assertThat(result.messages()).extracting(ChatMessageDto::content)
            .as("非终端 tool_result 后注入 Continue meta user")
            .contains("Continue from where you left off.");
        assertThat(result.messages()).filteredOn(ChatMessageDto::isMeta)
            .as("Continue 消息是 meta user")
            .hasSize(1);
    }

    // ── 分支 7: 末条纯 user → interrupted_prompt + sentinel ──

    @Test
    @DisplayName("末条纯 user → interrupted_prompt + No response requested. sentinel 注入")
    void lastPlainUser_isInterruptedPrompt() {
        // WHY: 纯文本 user prompt 且无 assistant 回应 = interrupted_prompt（CC 未开始回应）；
        //   注入 assistant sentinel 使 API 消息交替合法（末条 user 后无 assistant 时补 sentinel）。
        ChatMessageDto u = user("u1", "please help");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(u));

        assertThat(result.interruption()).isEqualTo(InterruptionKind.INTERRUPTED_PROMPT);
        assertThat(result.messages()).extracting(ChatMessageDto::content)
            .as("末条 user 后注入 No response requested. sentinel")
            .contains("No response requested.");
        assertThat(result.messages().get(result.messages().size() - 1).role())
            .as("sentinel 是 assistant（CC NO_RESPONSE_REQUESTED, messages.ts:241）")
            .isEqualTo(Role.assistant);
    }

    // ── 分支 8: 空列表 → none ──

    @Test
    @DisplayName("空列表 → none（空转录不注入任何 sentinel）")
    void emptyList_isNone() {
        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of());
        assertThat(result.messages()).isEmpty();
        assertThat(result.interruption()).isEqualTo(InterruptionKind.NONE);
    }

    @Test
    @DisplayName("null 输入 → 空列表 + none（fail-safe）")
    void nullInput_isEmptyNone() {
        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(null);
        assertThat(result.messages()).isEmpty();
        assertThat(result.interruption()).isEqualTo(InterruptionKind.NONE);
    }

    // ── 补充: 跳过 system / api-error assistant ──

    @Test
    @DisplayName("跳过尾部 system + api-error assistant → 末条相关为 user → interrupted_prompt")
    void skipsSystemAndApiErrorAssistants() {
        // WHY: system/progress/api-error assistant 是簿记产物，不得掩盖真实中断（CC detectTurnInterruption :287-292）。
        ChatMessageDto u = user("u1", "please");
        ChatMessageDto sys = system("s1");
        ChatMessageDto err = apiErrorAssistant("e1");

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(u, sys, err));

        assertThat(result.interruption())
            .as("跳过尾部 system + api-error assistant → 末条相关为 user → interrupted_prompt")
            .isEqualTo(InterruptionKind.INTERRUPTED_PROMPT);
    }

    @Test
    @DisplayName("isMeta 末条 → none（非真实 prompt）")
    void lastMetaUser_isNone() {
        // WHY: meta user（如 hook 注入 / compact summary / slash 技能内容 isMeta 落库）不是用户真实
        //   prompt，不算中断（CC :310-312）。
        // [Fix-P1 MODERATE] 同时不得注入 'No response requested.' sentinel —— prompt 型技能内容
        //   isMeta 落库后 resume 历史末条 = user(isMeta)，注入幽灵 sentinel 会混入模型上下文
        //   （CC [metadata, user(isMeta)] 无 sentinel）。变异点：splice sentinel → 结果含幽灵 assistant → 红。
        ChatMessageDto meta = msg("m1", Role.user, "meta", null, null, null, null, true, false);

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(meta));

        assertThat(result.interruption()).isEqualTo(InterruptionKind.NONE);
        assertThat(result.messages())
            .as("isMeta 末条不得 splice 'No response requested.' sentinel（对齐 detectTurnInterruption isMeta→NONE）")
            .noneMatch(m -> m.content() != null && m.content().contains("No response requested."));
        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .containsExactly("m1");
    }

    @Test
    @DisplayName("compact summary 末条 → none")
    void lastCompactSummary_isNone() {
        // WHY: isCompactSummary 的 user 是压缩摘要，非真实中断 prompt（CC conversationRecovery.ts:310）。
        // [Fix-P1 MODERATE] 同 isMeta：不得注入 sentinel（连续 user 消息 API 允许，CC 同构）。
        // 31 参 IMP2-14 构造器：…isApiErrorMessage/apiError/error/errorDetails +
        //   compactMetadata/microcompactMetadata/logicalParentUuid/isCompactSummary/isVisibleInTranscriptOnly
        ChatMessageDto summary = new ChatMessageDto("c1", "sess-x", Role.user, "author", "summary", null,
            null, null, null, null, "刚刚", null, null, null,
            null, List.of(), List.of(), null, false, false,
            null, null,
            false, null, null, null,
            Map.of(), null, null, true, false);
        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(summary));
        assertThat(result.interruption()).isEqualTo(InterruptionKind.NONE);
        assertThat(result.messages())
            .as("compact summary 末条不得 splice 'No response requested.' sentinel")
            .noneMatch(m -> m.content() != null && m.content().contains("No response requested."));
    }

    // ── fix-toolcalls-400 C: 部分配对 assistant 补 synthetic error tool_result ──

    @Test
    @DisplayName("部分配对 assistant: [assistant(a1, t1/t2), toolResult(tr1, t1)] → 补 t2 synthetic error tool_result（解锁坏会话）")
    void partialPairedAssistant_getsSyntheticToolResults() {
        // WHY: filterUnresolvedToolUses 只删"全部未配对"assistant；部分配对（N call 只有 S<N result，
        //   旧版 A/B 缺口落库的失衡链）保留的 assistant 仍有未配对 tool_use → resume 注入 OpenAI 400。
        //   Fix C 为每个未配对 id 补 error tool_result（对齐 CC yieldMissingToolResultBlocks is_error:true
        //   + sourceToolAssistantUUID），使注入历史合法 —— 解锁坏会话（sess-ba8595e2 类）。
        ChatMessageDto a = assistantWithTools("a1", "asst-1",
            List.of(new ToolCallDto("t1", "Bash", "{}", null, null),
                    new ToolCallDto("t2", "Read", "{}", null, null)));
        ChatMessageDto tr1 = toolResult("tr1", "t1"); // 只有 t1 有结果，t2 缺失

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(a, tr1));

        // a1 / tr1 保留
        assertThat(result.messages()).extracting(ChatMessageDto::id).contains("a1", "tr1");
        // 新增 Role.tool t2 的 synthetic error
        List<ChatMessageDto> synthetic = result.messages().stream()
            .filter(m -> m.role() == Role.tool && "t2".equals(m.toolCallId()))
            .toList();
        assertThat(synthetic).as("必须为未配对 t2 补 synthetic error tool_result").hasSize(1);
        assertThat(synthetic.get(0).content()).isEqualTo("Tool result missing");
        assertThat(synthetic.get(0).isError()).isTrue();
        assertThat(synthetic.get(0).assistantMessageId())
            .as("assistantMessageId = CC sourceToolAssistantUUID 等价位")
            .isEqualTo("asst-1");
        // 意图锚：每个 tool_call id 都有 tool 响应（配对完整性 → 注入历史合法 → 不再 400）
        java.util.Set<String> toolResultIds = result.messages().stream()
            .filter(m -> m.toolCallId() != null)
            .map(ChatMessageDto::toolCallId)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(toolResultIds).as("每个 tool_use 都必须有 tool_result 配对").contains("t1", "t2");
    }

    @Test
    @DisplayName("全部未配对 assistant 仍被剥离（Fix C 不破坏 filterUnresolvedToolUses 语义）")
    void fullyUnresolvedAssistant_stillRemoved() {
        // WHY: Fix C 只补部分配对的缺失 result；全部未配对的 assistant 仍由步骤 1 剥离（CC :3196-3198）。
        ChatMessageDto a = assistantWithTools("a1", "asst-1",
            List.of(new ToolCallDto("t1", "Bash", "{}", null, null)));
        ChatMessageDto u = user("u1", "please"); // 无 t1 的 tool_result

        var result = SessionResumeDeserializer.deserializeWithInterruptDetection(List.of(u, a));

        assertThat(result.messages()).extracting(ChatMessageDto::id)
            .as("全部未配对 assistant 必须剥离（不得因 Fix C 复活）")
            .contains("u1").doesNotContain("a1");
        assertThat(result.interruption())
            .as("剥离后末条为 user → interrupted_prompt")
            .isEqualTo(InterruptionKind.INTERRUPTED_PROMPT);
    }
}

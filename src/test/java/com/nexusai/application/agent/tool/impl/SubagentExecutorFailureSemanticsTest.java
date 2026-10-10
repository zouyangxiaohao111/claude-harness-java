package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.impl.SubagentExecutor.FinalConclusion;
import com.nexusai.application.agent.tool.impl.SubagentExecutor.SubagentResult;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-toolcall-fault B] 子代理失败语义 · 对齐 CC（错误文本作交付 + 失败落 failed）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：用户报障"子代理没干成活"。根因链后半段 ——
 * 子代理回合因 provider 流错误死亡（{@code finalState.exitReason()=STREAM_ERROR}）后，
 * 收尾只写 attachment（LLM 不消费）+ {@code extractConclusionFromMessages} 找不到任何非空
 * assistant 文本 ⇒ 落占位文案 {@code "Subagent completed without final answer."}
 * 且 <b>状态仍 completed</b>（{@code SubagentExecutor} 从不读 {@code exitReason}）。
 * 父 Agent 拿到"完成"的空话，真正的错误（上游 tool_call/流错误）被完全掩盖。
 *
 * <p><b>CC 同形依据（R2 精确化 · 已亲验 CC 真源）</b>：
 * <ul>
 *   <li>① <b>模型/流错误 → completed + 错误文本</b>：CC query.ts:990-992
 *       {@code yield createAssistantAPIErrorMessage({content: errorMessage})} + :996
 *       {@code return {reason:'model_error'}} —— 错误被 yield 成一条 assistant 消息，子代理生命周期
 *       当普通消息收下 ⇒ {@code finalizeAgentTool}（agentToolUtils.ts:304-315）取到的就是错误文本。</li>
 *   <li>② <b>failed 只留给真·零产出</b>：连一条 assistant 消息都没有时
 *       {@code throw new Error('No assistant messages found')}（agentToolUtils.ts:297-300）→
 *       async 生命周期 catch → {@code failAsyncAgent(taskId, msg)} + 通知 {@code status:'failed'}
 *       （:670-681）。</li>
 *   <li>③ <b>AbortError → killed</b>（:640-668）⇒ Java abort/INTERRUPTED 家族走 aborted 通道。</li>
 * </ul>
 *
 * <p>变异点：把「有错误文本」错判成 failed（或反之把真零产出判 completed）→ 对应用例红。
 */
@DisplayName("[fix-toolcall-fault B] 子代理失败语义（结论=错误文本 · 状态=failed）")
class SubagentExecutorFailureSemanticsTest {

    private static final String SESSION = UUID.randomUUID().toString();

    /** R32-b14 17 参兼容构造器：只关心 role + content，其余字段取安全默认。 */
    private static ChatMessageDto msg(Role role, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, role, null, content, null,
            null, null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 空 handoff 附件构造（对齐 LlmAgentLoop:8133 assistant_api_error 7 参形态）。 */
    private static AttachmentMessageDto assistantApiError(String text) {
        return new AttachmentMessageDto(null, "attachment", "assistant_api_error", text, null, null, null);
    }

    // ══════════════════ ① 有错误文本 ⇒ completed + 错误文本（CC query.ts:990-992 同形）══════════════════

    @Test
    @DisplayName("B-1 STREAM_ERROR + 零 assistant 文本 + 有错误文本 → 结论=错误文本（不再交付占位）· 状态=completed")
    void streamError_noAssistantText_conclusionIsErrorText_completed() {
        // WHY: 用户报障的直接可见形态 —— 子代理喷 upstream 真实错误，父 Agent 却收到
        //   "Subagent completed without final answer."（假完成掩盖真故障）。
        //   CC 真源：该错误在 CC 里是一条 assistant 错误消息（query.ts:990-992）⇒ 正常收下 ⇒
        //   completed + 错误文本作交付。本仓等价位 = 错误文本（lastError/attachment）进结论文本。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
        state.setError("OpenAI SDK 流式调用失败: No such tool available: __malformed_tool_call__");
        // 历史里只有 user / attachment（assistant_api_error 不算 assistant 文本，LLM 不消费）
        state.appendMessage(msg(Role.user, "调研任务"));
        state.appendAttachment(assistantApiError(state.lastError()));

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.summary())
            .as("错误情形结论必须是错误文本（对齐 CC 错误文本作交付）")
            .isEqualTo("OpenAI SDK 流式调用失败: No such tool available: __malformed_tool_call__");
        assertThat(c.summary())
            .as("不得再交付占位文案")
            .isNotEqualTo(SubagentExecutor.SUBAGENT_NO_FINAL_ANSWER_PLACEHOLDER);
        assertThat(c.status())
            .as("有错误文本 ⇒ completed（CC query.ts:990-992 错误 yield 成 assistant 消息 ⇒ 生命周期照常收下）")
            .isEqualTo("completed");
    }

    @Test
    @DisplayName("B-2 MODEL_ERROR + 零 assistant 文本 + 有错误文本 → completed + 错误文本")
    void modelError_noAssistantText_completedWithErrorText() {
        // WHY: withRetry 重试耗尽（CannotRetryException → MODEL_ERROR）在 CC 里同样经 query.ts:996
        //   catch → 错误消息 ⇒ completed + 错误文本（不是 failed）。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.MODEL_ERROR);
        state.setError("temporary error retry exhausted");

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status()).isEqualTo("completed");
        assertThat(c.summary()).isEqualTo("temporary error retry exhausted");
    }

    @Test
    @DisplayName("B-3 错误文本落点兜底：lastError 空 → 取 assistant_api_error attachment 原文（仍 completed）")
    void errorTextFallback_fromAttachment() {
        // WHY: 错误文本有两条既有落点（LlmAgentLoop:7239 state.setError + :8133 attachment）。
        //   lastError 缺失（如 setError 未触达的退出路径）时仍须交付真错误原文，不得回落占位。
        //   STREAM_TIMEOUT 判读：CC 的流空闲超时是**普通 Error 抛出**（claude.ts:2334
        //   'Stream idle timeout - no chunks received'；SDK 级超时 :2460 APIConnectionTimeoutError）
        //   → query.ts catch → 错误消息 ⇒ completed（**不是** AbortError ⇒ 不归 killed）。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.STREAM_TIMEOUT);
        state.appendAttachment(assistantApiError("stream timeout (300s)"));

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status()).isEqualTo("completed");
        assertThat(c.summary()).isEqualTo("stream timeout (300s)");
    }

    // ══════════════════ 防过度：有文本 / 非错误退出 仍 completed ══════════════════

    @Test
    @DisplayName("B-4 yield 家族：错误退出 ∧ 有错误文本 ∧ 有更早 assistant 文本 → 错误文本优先（CC 顺序 :296-318）")
    void errorExit_errorTextWinsOverEarlierText() {
        // WHY（F1 · 对齐裁定）: CC {@code finalizeAgentTool}（agentToolUtils.ts:296-318）顺序 =
        //   **先取最后一条 assistant 消息的 text；仅当其为空才逆序回退**。yield 家族
        //   （STREAM_ERROR/MODEL_ERROR/…）在 CC 里 API 错误消息**总是最后一条且必有 text**
        //   ⇒ CC 永远交付错误文本（更早的部分产出不进 content）。
        //   旧实现「有更早文本 → 优先取更早文本」会把错误吞掉（与规则③自相矛盾，父 Agent 看不到真故障）。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
        state.setError("stream broke mid-way");
        state.appendMessage(msg(Role.assistant, "已完成 3/5 项：结论如下…"));

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status()).as("有错误文本 → completed（交付错误）").isEqualTo("completed");
        assertThat(c.summary())
            .as("yield 家族：错误文本优先于任何更早 assistant 文本（CC 最后一条=错误消息）")
            .isEqualTo("stream broke mid-way");
    }

    @Test
    @DisplayName("B-4b 防过度：非 yield 家族（MAX_TURNS）∧ 有 assistant 文本 → 仍取该文本")
    void nonYieldExit_withAssistantText_keepsText() {
        // WHY: max_turns / hook 等退出**没有**「API 错误消息恒为最后一条」语义（CC 不 yield 错误消息）
        //   ⇒ 仍走 CC 的常规路径：取最后一条 assistant 文本（CC :307-315 逆序回退）。
        //   若把 F1 的「错误文本优先」无条件套到所有退出码 → 本用例红（过度）。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.MAX_TURNS);
        state.setError("max turns exceeded (50)");
        state.appendMessage(msg(Role.assistant, "已完成 3/5 项：结论如下…"));

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status()).isEqualTo("completed");
        assertThat(c.summary())
            .as("非 yield 家族仍取 assistant 文本（不得被错误文本抢占）")
            .isEqualTo("已完成 3/5 项：结论如下…");
    }

    @Test
    @DisplayName("B-5 防过度: NORMAL 退出且无非空文本 → completed + 占位（占位仅剩最后兜底语义）")
    void normalExit_noText_placeholderCompleted() {
        // WHY: 非错误退出（模型正常收尾但没写文本）不属"错误情形"，占位文案保留为最后兜底 ——
        //   本用例锁定"占位文案仍在、但只在这个非错误场景出现"。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.NORMAL);
        state.appendMessage(msg(Role.user, "任务"));

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status()).isEqualTo("completed");
        assertThat(c.summary()).isEqualTo(SubagentExecutor.SUBAGENT_NO_FINAL_ANSWER_PLACEHOLDER);
    }

    @Test
    @DisplayName("B-6 防过度: MAX_TURNS / STOP_HOOK_PREVENTED / INTERRUPTED 不属错误退出（不被误判 failed）")
    void nonFailureExits_notClassifiedAsFailure() {
        // WHY: MAX_TURNS（步数上限）与 STOP_HOOK_PREVENTED（stop hook 优雅终止）是正常语义的终止，
        //   CC 也不把它们当 agent 失败 —— 误判会让父 Agent 对正常收尾做失败处理。
        //   INTERRUPTED 属 abort 家族（CC AbortError → killed），由 isAbortExit 单独判（B-10b）。
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.MAX_TURNS)).isFalse();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.STOP_HOOK_PREVENTED)).isFalse();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.HOOK_STOPPED)).isFalse();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.NORMAL)).isFalse();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.INTERRUPTED)).isFalse();
        assertThat(SubagentExecutor.isFailureExit(null)).isFalse();
        // 对照组：错误退出必须为 true（含 STREAM_ERROR / MODEL_ERROR / STREAM_TIMEOUT / NO_ASSISTANT_TEXT）
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.STREAM_ERROR)).isTrue();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.MODEL_ERROR)).isTrue();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.STREAM_TIMEOUT)).isTrue();
        assertThat(SubagentExecutor.isFailureExit(AgentState.ExitReason.NO_ASSISTANT_TEXT)).isTrue();
    }

    @Test
    @DisplayName("B-10b abort 家族判据: INTERRUPTED → isAbortExit（CC AbortError→killed :640-668）")
    void abortExitClassification() {
        // WHY: R2.3 —— CC 只有 AbortError 走 killed；其余模型/流错误走错误消息（completed）。
        //   Java 等价位 = INTERRUPTED（LlmAgentLoop:7314-7318 等待流完成被中断）。
        //   误把 INTERRUPTED 归 failed/completed 都会让 killed 三态失真（S4 P1 差异项 6）。
        assertThat(SubagentExecutor.isAbortExit(AgentState.ExitReason.INTERRUPTED)).isTrue();
        assertThat(SubagentExecutor.isAbortExit(AgentState.ExitReason.STREAM_ERROR)).isFalse();
        assertThat(SubagentExecutor.isAbortExit(AgentState.ExitReason.STREAM_TIMEOUT)).isFalse();
        assertThat(SubagentExecutor.isAbortExit(AgentState.ExitReason.MODEL_ERROR)).isFalse();
        assertThat(SubagentExecutor.isAbortExit(AgentState.ExitReason.NORMAL)).isFalse();
        assertThat(SubagentExecutor.isAbortExit(null)).isFalse();
    }

    @Test
    @DisplayName("B-10c INTERRUPTED + 零 assistant 文本 → 走 aborted 通道（不被 completed/failed 抢）")
    void interrupted_routesToAborted() {
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.INTERRUPTED);
        state.setError("interrupted");

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status())
            .as("INTERRUPTED = CC AbortError 等价位 ⇒ killed 通道（aborted）")
            .isEqualTo("aborted");
    }

    @Test
    @DisplayName("B-10 真零产出（错误退出 ∧ 无文本 ∧ 无错误文本）→ failed + 失败态独立文案（CC throw 家族 :297-300）")
    void zeroOutputFailure_returnsFailed() {
        // WHY: R2.2 —— failed 的正确触发面 = 「连错误文本都没有」的真零产出：CC finalizeAgentTool
        //   连一条 assistant 消息都没有时 throw 'No assistant messages found'（:297-300）→
        //   async catch → failAsyncAgent（:671）落 failed。
        //   [F4] 结论必须用**失败态独立文案**（不得用 completed 的 "Subagent completed without
        //   final answer."）—— 否则 FAILED 通知会渲染成 "failed: Subagent completed without final
        //   answer."（自相矛盾 + 正是用户反感的占位语）。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
        // 无 lastError、无 attachment、无 assistant 文本

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(false, state.rawMessages(), state);

        assertThat(c.status()).isEqualTo("failed");
        assertThat(c.summary())
            .as("失败态独立文案（不与 completed 占位同文）")
            .isEqualTo(SubagentExecutor.SUBAGENT_NO_OUTPUT_FAILURE_TEXT);
        assertThat(c.summary())
            .as("⛔ 失败结论不得出现 'completed' 字样")
            .doesNotContain("completed");
    }

    @Test
    @DisplayName("B-7 abort 优先: aborted=true → status=aborted（killed 三态不被 failed 抢）")
    void aborted_winsOverFailed() {
        // WHY: 用户主动 kill 的语义是 killed（CC killAsyncAgent），不得被 failed 覆盖
        //   （三态 completed/killed/failed 互斥，S4 P1 差异项 6）。
        AgentState state = new AgentState("test-system-prompt");
        state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
        state.setError("stream broke");
        state.appendMessage(msg(Role.assistant, "部分产出"));

        FinalConclusion c = SubagentExecutor.resolveFinalConclusion(true, state.rawMessages(), state);

        assertThat(c.status()).isEqualTo("aborted");
        assertThat(c.summary()).isEqualTo("部分产出");
    }

    @Test
    @DisplayName("B-8 lastAssistantTextOrNull: 全空白 assistant 文本视为「无文本」（不得被空白骗过）")
    void lastAssistantTextOrNull_blankOnly_isNull() {
        // WHY: extractConclusionFromMessages 原判据即非 blank（:5281）；收尾判定必须同口径，
        //   否则空白 assistant 消息会让"无文本"判据失效 → 错误被占位掩盖的缺陷复现。
        assertThat(SubagentExecutor.lastAssistantTextOrNull(List.of(
            msg(Role.assistant, "   "), msg(Role.user, "hi")))).isNull();
        assertThat(SubagentExecutor.lastAssistantTextOrNull(List.of(
            msg(Role.assistant, "   "), msg(Role.assistant, "真结论")))).isEqualTo("真结论");
        assertThat(SubagentExecutor.lastAssistantTextOrNull(List.of())).isNull();
        assertThat(SubagentExecutor.lastAssistantTextOrNull(null)).isNull();
    }

    // ══════════════════ ② 状态通道：SubagentResult.failed ══════════════════

    @Test
    @DisplayName("B-9 SubagentResult.failed 工厂 → status=failed（async 路由据此走 failAsyncAgent 通道）")
    void subagentResult_failed_factory() {
        // WHY: SubagentTool async 收尾按 status 三态路由（aborted→killed / failed→FAILED /
        //   其余→COMPLETED）。若失败情形仍产 'completed'，BackgroundTaskRunner 会把 task 写 COMPLETED
        //   —— 父 Agent 面板显示"完成"（用户报障的"没干成活"体感）。
        SubagentResult failed = SubagentResult.failed(
            "OpenAI SDK 流式调用失败: boom", 2, 1200L, "a1234567890abcdef", 42L,
            AgentUsage.fromInputOutput(10, 5));

        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.summaryText()).isEqualTo("OpenAI SDK 流式调用失败: boom");
        assertThat(failed.totalToolUseCount()).isEqualTo(2);
        assertThat(failed.totalTokens()).isEqualTo(42L);
        // 对照组：既有三态工厂不被误改
        assertThat(SubagentResult.completed("ok", 0, 0L, "a1").status()).isEqualTo("completed");
        assertThat(SubagentResult.aborted("part", 0, 0L, "a1").status()).isEqualTo("aborted");
    }
}

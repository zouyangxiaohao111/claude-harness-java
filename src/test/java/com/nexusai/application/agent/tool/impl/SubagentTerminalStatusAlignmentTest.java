package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.ToolError;
import com.nexusai.application.agent.tool.ToolErrorKind;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子代理终态对齐 CC · 两处残余（探源 §7.4）：
 * <ol>
 *   <li><b>sync 零产出/失败</b>：CC 重抛 syncAgentError（AgentTool.tsx:1226-1228）⇒ 工具框架产
 *       is_error；本仓此前返回 success + status="failed"（父模型收到内容而非工具错误）。</li>
 *   <li><b>async 部分产出后异常</b>：CC async catch 非 AbortError → failAsyncAgent(status failed)
 *       （agentToolUtils.ts:670-681）；本仓此前恒 completed（只区分零产出）。</li>
 * </ol>
 */
@DisplayName("子代理终态对齐：sync failed→工具错误 / async 部分失败→failed")
class SubagentTerminalStatusAlignmentTest {

    // ───────────────────── ① sync：failed → 工具错误 ─────────────────────

    @Test
    @DisplayName("sync failed → ToolResult 失败载荷（原文 + CALL_THREW）")
    void syncFailedBecomesToolError() {
        SubagentExecutor.SubagentResult failed =
            SubagentExecutor.SubagentResult.failed("boom", 2, 100L, "a1234567890abcdef");

        ToolResult<?> result = SubagentTool.syncErrorResult("toolu_s", failed);

        assertThat(result).isNotNull();
        assertThat(result.data()).isInstanceOf(ToolError.class);
        ToolError err = (ToolError) result.data();
        assertThat(err.message()).isEqualTo("boom");
        assertThat(err.kind()).isEqualTo(ToolErrorKind.CALL_THREW);
    }

    @Test
    @DisplayName("sync aborted → 中断错误（CC throw AbortError → INTERRUPT_MESSAGE_FOR_TOOL_USE）")
    void syncAbortedBecomesInterrupt() {
        SubagentExecutor.SubagentResult aborted =
            SubagentExecutor.SubagentResult.aborted("partial", 1, 10L, "a1");

        ToolResult<?> result = SubagentTool.syncErrorResult("toolu_s", aborted);

        assertThat(result).isNotNull();
        assertThat(result.data()).isInstanceOf(ToolError.class);
        ToolError err = (ToolError) result.data();
        assertThat(err.kind()).isEqualTo(ToolErrorKind.INTERRUPT);
        assertThat(err.message()).isEqualTo(
            com.nexusai.application.agent.LlmAgentLoop.INTERRUPT_MESSAGE_FOR_TOOL_USE);
    }

    @Test
    @DisplayName("sync completed → 不走错误通道（返回 null）")
    void syncCompletedKeepsSuccessPath() {
        assertThat(SubagentTool.syncErrorResult("toolu_s",
            SubagentExecutor.SubagentResult.completed("ok", 1, 10L, "a1"))).isNull();
        assertThat(SubagentTool.syncErrorResult("toolu_s", null)).isNull();
    }

    // ───────────────────── ② async 部分失败 → failed ─────────────────────

    @Test
    @DisplayName("异常穿出：取消 → aborted（优先于一切）")
    void thrownCancelledIsAborted() {
        SubagentExecutor.SubagentResult r = SubagentExecutor.classifyThrownAfterPartial(
            "partial", "boom", 3, "a1", 10L, AgentUsage.EMPTY, true, false, true);
        assertThat(r.status()).isEqualTo("aborted");
    }

    @Test
    @DisplayName("异常穿出：零输出 → failed（结论=异常原文），sync/async 一致")
    void thrownZeroOutputIsFailed() {
        SubagentExecutor.SubagentResult sync = SubagentExecutor.classifyThrownAfterPartial(
            "placeholder", "boom", 0, "a1", 0L, AgentUsage.EMPTY, false, true, false);
        assertThat(sync.status()).isEqualTo("failed");
        assertThat(sync.summaryText()).isEqualTo("boom");

        SubagentExecutor.SubagentResult async = SubagentExecutor.classifyThrownAfterPartial(
            "placeholder", "boom", 0, "a1", 0L, AgentUsage.EMPTY, false, true, true);
        assertThat(async.status()).isEqualTo("failed");
    }

    @Test
    @DisplayName("异常穿出：有部分产出 + async → failed（CC failAsyncAgent）")
    void thrownPartialAsyncIsFailed() {
        SubagentExecutor.SubagentResult r = SubagentExecutor.classifyThrownAfterPartial(
            "partial", "boom", 2, "a1", 5L, AgentUsage.EMPTY, false, false, true);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.summaryText()).isEqualTo("boom");
    }

    @Test
    @DisplayName("异常穿出：有部分产出 + sync → completed（保留部分进展，CC:1223-1252）")
    void thrownPartialSyncIsCompleted() {
        SubagentExecutor.SubagentResult r = SubagentExecutor.classifyThrownAfterPartial(
            "partial", "boom", 2, "a1", 5L, AgentUsage.EMPTY, false, false, false);
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.summaryText()).isEqualTo("partial");
    }
}

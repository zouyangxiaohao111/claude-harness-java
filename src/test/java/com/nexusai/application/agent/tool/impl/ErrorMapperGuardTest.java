package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolErrorKind;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [错误通道 CCJ-ERR-014 · 第二道防线] NOGUARD mapper 在失败载荷下不得输出成功模板。
 *
 * <p>正常路径由执行层直构错误块（{@code LlmAgentLoop.toolResultMessage}，C1 已落）；
 * 本测试覆盖「绕过执行器直接调 mapper」时的兜底：失败必须返回 {@code null}
 * （调用方回退默认渲染器 → 错误原文 + is_error=true），或本身就不产生成功模板。
 */
@DisplayName("NOGUARD mapper 失败守卫")
class ErrorMapperGuardTest {

    private static AgentToolResult<?> failure(String message) {
        return ToolResult.failure("tu-err", message, ToolErrorKind.CALL_THREW);
    }

    private static AgentToolResult<?> validateFailure(String message) {
        return ToolResult.failure("tu-val", message, ToolErrorKind.VALIDATE_INPUT);
    }

    // ── 本批新增守卫的三个 mapper ────────────────────────────────

    @Test
    @DisplayName("WebSearch：ToolError 载荷 → mapper 返回 null（不再渲染 'Web search results for query'）")
    void webSearchMapperSkipsFailurePayload() {
        WebSearchTool tool = new WebSearchTool();
        assertThat(tool.mapToToolResultBlockParam(failure("websearch upstream failed"), "tu-err", true))
            .isNull();
        // isError 显式置位（历史调用点写法）同样不渲染
        AgentToolResult<?> legacy = ToolResult.success("tu-legacy",
            "{\"query\":\"q\",\"results\":[]}");
        assertThat(tool.mapToToolResultBlockParam(legacy, "tu-legacy", true)).isNull();
    }

    @Test
    @DisplayName("Snip：失败载荷 → mapper 返回 null（不再渲染 'Snipped 0 messages. Summary: '）")
    void snipMapperSkipsFailurePayload() {
        SnipTool tool = new SnipTool();
        assertThat(tool.mapToToolResultBlockParam(failure("snip disabled"), "tu-err", true)).isNull();
        assertThat(tool.mapToToolResultBlockParam(validateFailure("bad message_ids"), "tu-val", false))
            .isNull();
    }

    @Test
    @DisplayName("EnterPlanMode：失败载荷 → mapper 返回 null（不再拼 6 步探索指令）")
    void enterPlanModeMapperSkipsFailurePayload() {
        EnterPlanModeTool tool = new EnterPlanModeTool();
        assertThat(tool.mapToToolResultBlockParam(failure("plan mode unavailable"), "tu-err", true))
            .isNull();
    }

    // ── 读码判定「无需改」的四个 mapper（回归锁定） ────────────────

    @Test
    @DisplayName("REPL：失败载荷 → mapper 返回 null（交调用方兜底，避免 is_error 与内容不同源）")
    void replMapperSkipsFailurePayload() {
        REPLTool tool = new REPLTool();
        assertThat(tool.mapToToolResultBlockParam(failure("repl execution failed"), "tu-err", true))
            .isNull();
    }

    @Test
    @DisplayName("VerifyPlanExecution：非 JsonNode 数据 → 返回 null（失败天然不进成功模板）")
    void verifyPlanMapperReturnsNullForNonJsonData() {
        VerifyPlanExecutionTool tool = new VerifyPlanExecutionTool();
        assertThat(tool.mapToToolResultBlockParam(failure("plan not verifiable"), "tu-err", true))
            .isNull();
    }
}

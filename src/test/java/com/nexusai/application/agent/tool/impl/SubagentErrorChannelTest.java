package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolError;
import com.nexusai.application.agent.tool.ToolErrorKind;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C6 · CCJ-ERR-016] 子代理错误通道与主循环同源。
 *
 * <p><b>CC 基线</b>：错误块在错误点直构（toolExecution.ts:469-489/:616-680/:717-732/:1033），
 * per-tool {@code mapToolResultToToolResultBlockParam} 只在 {@code tool.call()} 成功返回后调用
 * （:1288-1292）。主/子/fork 共用同一 {@code runToolUse}，故子代理错误不得走另一套模板。
 *
 * <p><b>本仓旧缺陷</b>：{@code SubagentTool} 的前置校验（description/prompt/name）以裸 String 返回，
 * 靠旧前缀表反推错误态 —— 未入表时父模型把参数拒绝当成功；{@code SubagentExecutor.toolResultMessage}
 * 影子工厂无条件调 per-tool mapper，Edit/Write 等不看 isError 的 mapper 会把失败渲染成
 * {@code "The file … has been updated successfully."}。
 *
 * <p>本测试锁定：① 参数校验失败 → {@code VALIDATE_INPUT}（父侧带 {@code <tool_use_error>} 外壳、
 * {@code is_error=true}）；② 运行期拒绝 → {@code CALL_THREW}（原文直出、不套壳）；
 * ③ 影子工厂失败路径不调 mapper、成功路径仍走 mapper。
 */
@DisplayName("[C6] 子代理错误通道：参数校验套壳 / 运行期拒绝直出 / 影子工厂双路径")
class SubagentErrorChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ───────────────────────── 安全上下文 ─────────────────────────

    private static ToolUseBlock callWith(ObjectNode input) {
        return new ToolUseBlock("call-subagent-1", "Agent", input);
    }

    private static ObjectNode baseInput() {
        ObjectNode in = JSON.createObjectNode();
        in.put("description", "do the thing");
        in.put("prompt", "please do the thing");
        return in;
    }

    /** 渲染父侧 tool_result 消息（与 AgentLoopContext 生产路径同一入口）。 */
    private static ChatMessageDto render(ToolResult<?> result, boolean isError) {
        return LlmAgentLoop.toolResultMessage(result, "call-subagent-1", isError, null, null,
            null, List.of(), List.of(), Map.of());
    }

    // ───────────────────────── SubagentTool 前置校验 ─────────────────────────

    @Test
    @DisplayName("description 为空 → VALIDATE_INPUT，父侧带 <tool_use_error> 外壳且 is_error=true")
    void blankDescriptionIsValidateInputWithShell() {
        ObjectNode input = baseInput();
        input.put("description", "");

        ToolResult<?> result = new SubagentTool().execute(callWith(input));

        assertThat(result.data())
            .as("参数校验失败必须是结构化失败载荷（不再靠文案前缀）")
            .isInstanceOf(ToolError.class);
        ToolError err = (ToolError) result.data();
        assertThat(err.kind()).isEqualTo(ToolErrorKind.VALIDATE_INPUT);
        assertThat(err.message()).isEqualTo("description cannot be empty");

        ChatMessageDto msg = render(result, true);
        assertThat(msg.isError()).isTrue();
        assertThat(msg.content())
            .as("对齐 CC zod 解析失败：<tool_use_error>InputValidationError 族外壳")
            .isEqualTo("<tool_use_error>description cannot be empty</tool_use_error>");
    }

    @Test
    @DisplayName("prompt 为空 → VALIDATE_INPUT，父侧带壳")
    void blankPromptIsValidateInputWithShell() {
        ObjectNode input = baseInput();
        input.put("prompt", "");

        ToolResult<?> result = new SubagentTool().execute(callWith(input));

        assertThat(result.data()).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result.data()).kind()).isEqualTo(ToolErrorKind.VALIDATE_INPUT);
        assertThat(render(result, true).content())
            .isEqualTo("<tool_use_error>prompt cannot be empty</tool_use_error>");
    }

    @Test
    @DisplayName("运行期拒绝（teammate 不能 spawn teammate）→ CALL_THREW，原文直出、不套壳")
    void runtimeRejectionHasNoShell() {
        ObjectNode input = baseInput();
        input.put("name", "researcher");
        input.put("team_name", "alpha");
        TeammateIdentity identity = new TeammateIdentity(
            "researcher@alpha", "researcher", "alpha", null, false, "sess-parent");

        ToolResult<?> result = new SubagentTool().execute(
            callWith(input), null, null, AgentOptions.defaultOptions(), null, identity);

        assertThat(result.data()).isInstanceOf(ToolError.class);
        ToolError err = (ToolError) result.data();
        assertThat(err.kind())
            .as("CC 在 call() 内抛错 → formatError 原文直出，不套 <tool_use_error>")
            .isEqualTo(ToolErrorKind.CALL_THREW);
        assertThat(err.message()).startsWith("Teammates cannot spawn other teammates");

        ChatMessageDto msg = render(result, true);
        assertThat(msg.isError()).isTrue();
        assertThat(msg.content())
            .as("不套壳：content 即错误原文")
            .isEqualTo(err.message());
    }

    // ───────────────────────── SubagentExecutor 影子工厂 ─────────────────────────

    /** 模拟 Edit/Write 一类「不看 isError」的 mapper：任何输入都产成功模板。 */
    private static Tool successTemplateTool() {
        return new Tool() {
            @Override
            public String name() {
                return "Edit";
            }

            @Override
            public String description() {
                return "stub";
            }

            @Override
            public JsonNode inputSchema() {
                return JSON.createObjectNode().put("type", "object");
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public ToolResultBlockParam mapToToolResultBlockParam(
                    AgentToolResult<?> result, String toolUseId, boolean isError) {
                return new ToolResultBlockParam(toolUseId, "tool_result",
                    "The file  has been updated successfully.", false);
            }
        };
    }

    private static SubagentExecutor executor() {
        return new SubagentExecutor(
            new com.nexusai.application.agent.tool.ToolRegistry(),
            new com.nexusai.application.agent.permission.hook.HookRegistry(),
            (LlmAgentLoop) null,
            new com.nexusai.infra.llm.LlmProviderFactory(),
            com.nexusai.infra.llm.ProviderConfig.empty(),
            "fallback-model",
            "fallback system prompt",
            null);
    }

    @Test
    @DisplayName("影子工厂：失败不调 mapper —— 成功模板不得覆盖错误")
    void executorErrorPathSkipsMapper() {
        ToolResult<?> failure = ToolResult.failure(
            "call-1", "path is empty", ToolErrorKind.VALIDATE_INPUT);

        ChatMessageDto msg = executor().toolResultMessage(
            "sess-1", failure, successTemplateTool(), "call-1", true);

        assertThat(msg.isError()).isTrue();
        assertThat(msg.content())
            .as("失败必须直构错误块，不能被 per-tool 成功模板覆盖")
            .isEqualTo("<tool_use_error>path is empty</tool_use_error>");
    }

    @Test
    @DisplayName("影子工厂：成功仍走 per-tool mapper（成功文案不变）")
    void executorSuccessPathUsesMapper() {
        ToolResult<?> ok = ToolResult.success("call-1", "anything");

        ChatMessageDto msg = executor().toolResultMessage(
            "sess-1", ok, successTemplateTool(), "call-1", false);

        assertThat(msg.isError()).isFalse();
        assertThat(msg.content())
            .as("成功路径保持逐字节现状")
            .isEqualTo("The file  has been updated successfully.");
    }

    @Test
    @DisplayName("影子工厂 3 参重载：isError 由载荷结构推导（不再硬编码 false）")
    void executorThreeArgOverloadDerivesErrorFromPayload() {
        ToolResult<?> failure = ToolResult.failure("call-1", "boom", ToolErrorKind.CALL_THREW);

        ChatMessageDto msg = executor().toolResultMessage("sess-1", failure, successTemplateTool());

        assertThat(msg.isError())
            .as("ToolError 载荷必须被判定为失败，否则影子工厂会复现主循环的假成功")
            .isTrue();
        assertThat(msg.content()).isEqualTo("boom");
    }
}

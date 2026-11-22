package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [错误通道 · CCJ-ERR-014] 工具失败结果的结构化承载 + 成功/失败双渲染路径。
 *
 * <p><b>WHY</b>：CC 只在 {@code tool.call()} 成功返回后调用
 * {@code mapToolResultToToolResultBlockParam}（services/tools/toolExecution.ts:1288-1292）；
 * 所有错误/取消路径在错误点**直构** {@code is_error:true} 的 tool_result 块，永不经过 per-tool
 * mapper。Java 旧实现把所有结果都交给 mapper，于是不看 {@code isError} 的 mapper（Edit/Write 等）
 * 把失败渲染成 {@code "The file … has been updated successfully."} —— 实测「回执成功、实际零改动」。
 *
 * <p>本测试锁定三件事：
 * <ol>
 *   <li>失败由 {@link ToolError} <b>类型</b>承载（不再靠错误文案前缀反推）；</li>
 *   <li>8 类失败中只有 unknown/schema/validateInput 套 {@code <tool_use_error>} 外壳，
 *       其余原文直出（CC 错误分支逐条）；</li>
 *   <li>{@link LlmAgentLoop#toolResultMessage} 在失败时直构块（不调 mapper），
 *       成功路径的 per-tool 成功模板保持不变。</li>
 * </ol>
 */
@DisplayName("错误通道 · ToolError 结构判定与成功/失败双路径")
class ToolErrorChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ───────────────────────── 载荷与判定 ─────────────────────────

    @Test
    @DisplayName("ToolResult.error 产出的 data 是 ToolError，isToolErrorData 结构判定为真")
    void errorFactoryCarriesStructuredFailure() {
        ToolResult<?> r = ToolResult.error("toolu_1", "path is empty");

        assertThat(r.data()).isInstanceOf(ToolError.class);
        ToolError err = (ToolError) r.data();
        assertThat(err.message()).isEqualTo("path is empty");
        assertThat(err.kind()).isEqualTo(ToolErrorKind.CALL_THREW);

        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isTrue();
        assertThat(LlmAgentLoop.isToolErrorData("path is empty")).as("裸 String 不再是失败信号").isFalse();
        assertThat(ToolResult.renderToolResultPayloadText(r)).isEqualTo("path is empty");
    }

    @Test
    @DisplayName("历史 category 字符串映射到类别（validation→VALIDATE_INPUT / permission→PERMISSION_DENY）")
    void legacyCategoryMapsToKind() {
        assertThat(((ToolError) ToolResult.error("id", "x", "validation").data()).kind())
            .isEqualTo(ToolErrorKind.VALIDATE_INPUT);
        assertThat(((ToolError) ToolResult.error("id", "x", "permission").data()).kind())
            .isEqualTo(ToolErrorKind.PERMISSION_DENY);
        assertThat(((ToolError) ToolResult.error("id", "x", "other").data()).kind())
            .isEqualTo(ToolErrorKind.CALL_THREW);
    }

    @Test
    @DisplayName("空/空白错误消息归一为 unknown error；toString 即消息原文")
    void blankMessageNormalizedAndToStringIsMessage() {
        ToolError blank = (ToolError) ToolResult.error("id", "   ").data();
        assertThat(blank.message()).isEqualTo("unknown error");

        ToolError err = new ToolError("Exit code 2", ToolErrorKind.CALL_THREW);
        assertThat(err.toString()).isEqualTo("Exit code 2");
    }

    @Test
    @DisplayName("成功结果不被误判为失败")
    void successIsNotFailure() {
        ToolResult<String> ok = ToolResult.success("id", "done");
        assertThat(LlmAgentLoop.isToolErrorData(ok.data())).isFalse();
        assertThat(ToolResult.renderToolResultPayloadText(ok)).isEqualTo("done");
    }

    // ───────────────────────── ≤ 外壳规则（CC 逐条） ─────────────────────────

    @Test
    @DisplayName("8 类失败的外壳规则：3 类套 <tool_use_error>，5 类原文直出")
    void toolUseErrorShellRuleForAllKinds() {
        assertThat(ToolErrorKind.UNKNOWN_TOOL.wrapsWithToolUseErrorShell()).isTrue();
        assertThat(ToolErrorKind.SCHEMA.wrapsWithToolUseErrorShell()).isTrue();
        assertThat(ToolErrorKind.VALIDATE_INPUT.wrapsWithToolUseErrorShell()).isTrue();

        assertThat(ToolErrorKind.PERMISSION_DENY.wrapsWithToolUseErrorShell()).isFalse();
        assertThat(ToolErrorKind.CALL_THREW.wrapsWithToolUseErrorShell()).isFalse();
        assertThat(ToolErrorKind.CANCEL.wrapsWithToolUseErrorShell()).isFalse();
        assertThat(ToolErrorKind.INTERRUPT.wrapsWithToolUseErrorShell()).isFalse();
        assertThat(ToolErrorKind.SYNTHETIC.wrapsWithToolUseErrorShell()).isFalse();
    }

    // ───────────────────────── 渲染：toolResultMessage ─────────────────────────

    @Test
    @DisplayName("SCHEMA 失败：直构 <tool_use_error>…</tool_use_error>，is_error=true")
    void schemaFailureRendersToolUseErrorShell() {
        ToolResult<?> r = ToolResult.failure("toolu_s", "Edit failed due to the following issue:\nMissing old_string",
            ToolErrorKind.SCHEMA);

        ChatMessageDto msg = render(r, null, true);

        assertThat(msg.content()).isEqualTo(
            "<tool_use_error>Edit failed due to the following issue:\nMissing old_string</tool_use_error>");
        assertThat(msg.isError()).isTrue();
    }

    @Test
    @DisplayName("VALIDATE_INPUT 失败：套壳且不暴露内部 errorCode 结构")
    void validateInputFailureRendersShellWithoutErrorCode() {
        ToolResult<?> r = ToolResult.failure("toolu_v",
            "File has not been read yet. Read it first before writing to it.", ToolErrorKind.VALIDATE_INPUT);

        ChatMessageDto msg = render(r, null, true);

        assertThat(msg.content()).isEqualTo(
            "<tool_use_error>File has not been read yet. Read it first before writing to it.</tool_use_error>");
        assertThat(msg.content()).doesNotContain("Input validation failed");
        assertThat(msg.isError()).isTrue();
    }

    @Test
    @DisplayName("UNKNOWN_TOOL 失败：CC 逐字 `Error: No such tool available: X` 被套壳")
    void unknownToolFailureRendersShell() {
        ToolResult<?> r = ToolResult.failure("toolu_u", "Error: No such tool available: nope",
            ToolErrorKind.UNKNOWN_TOOL);

        ChatMessageDto msg = render(r, null, true);

        assertThat(msg.content())
            .isEqualTo("<tool_use_error>Error: No such tool available: nope</tool_use_error>");
        assertThat(msg.isError()).isTrue();
    }

    @Test
    @DisplayName("CANCEL / PERMISSION_DENY / CALL_THREW 失败：原文直出，不套壳")
    void nonShellKindsRenderRawText() {
        for (ToolErrorKind kind : List.of(ToolErrorKind.CANCEL, ToolErrorKind.PERMISSION_DENY,
                ToolErrorKind.CALL_THREW, ToolErrorKind.INTERRUPT, ToolErrorKind.SYNTHETIC)) {
            ToolResult<?> r = ToolResult.failure("toolu_x", "Exit code 2", kind);
            ChatMessageDto msg = render(r, null, true);

            assertThat(msg.content()).as("kind=%s 应为原文直出", kind).isEqualTo("Exit code 2");
            assertThat(msg.isError()).as("kind=%s 必须 is_error=true", kind).isTrue();
        }
    }

    @Test
    @DisplayName("关键回归：失败结果绝不出现任何成功模板文案（Edit 假成功根因）")
    void failureNeverRendersSuccessTemplate() {
        // 旧实现的真实现场：Edit 空 file_path → "path is empty"，且 t.isError 被判 false。
        ToolResult<?> r = ToolResult.error("toolu_e", "path is empty");

        // ① isError 由执行层推导为 false 的旧姿势下，也必须靠 data 类型识别为失败
        ChatMessageDto msg = render(r, null, false);

        assertThat(msg.content()).doesNotContain("has been updated successfully");
        assertThat(msg.content()).isEqualTo("path is empty");
        assertThat(msg.isError()).as("失败载荷必须强制 is_error=true").isTrue();
    }

    @Test
    @DisplayName("成功结果仍走 per-tool mapper（成功模板不变）")
    void successStillUsesPerToolMapper() {
        ToolResult<?> ok = ToolResult.successWithStructuredOutput("toolu_ok",
            "The file /x/y.txt has been updated successfully.",
            Map.of("filePath", "/x/y.txt", "userModified", false, "replaceAll", false));

        // tool=null → 默认渲染器取 summary（与 mapper 成功模板同文本）
        ChatMessageDto msg = render(ok, null, false);

        assertThat(msg.content()).isEqualTo("The file /x/y.txt has been updated successfully.");
        assertThat(msg.isError()).isFalse();
    }

    @Test
    @DisplayName("真实 EditFileTool 在场时，失败也不落到它的成功模板（假成功修复点）")
    void editToolSubjectDoesNotRenderSuccessTemplateOnFailure() throws Exception {
        com.nexusai.application.agent.tool.impl.EditFileTool editTool =
            new com.nexusai.application.agent.tool.impl.EditFileTool(
                new PathGuard(java.nio.file.Paths.get(
                    java.nio.file.Paths.get("").toAbsolutePath().toString())));

        // 现场复现：空 file_path 走到 executeInternal 返回 "path is empty"，且旧前缀表判 isError=false
        ToolResult<?> r = ToolResult.error("toolu_edit", "path is empty");
        ChatMessageDto msg = render(r, editTool, false);

        assertThat(msg.content())
            .as("必须回错误原文，而不是 EditFileTool 的 The file … has been updated successfully.")
            .isEqualTo("path is empty");
        assertThat(msg.isError()).isTrue();

        // 对照：成功结果仍由同一个 mapper 产出成功模板（C2 改 mapper 时本断言会提醒同步更新）
        ToolResult<?> ok = ToolResult.successWithStructuredOutput("toolu_ok2",
            "ignored", Map.of("filePath", "/x/y.txt", "userModified", false, "replaceAll", false));
        assertThat(render(ok, editTool, false).content())
            .isEqualTo("The file /x/y.txt has been updated successfully.");
    }

    // ───────────────────────── 默认 mapper 的第二道防线 ─────────────────────────

    @Test
    @DisplayName("默认 mapper 遇失败载荷：按类别套壳/原文，且 is_error 强制 true（绕过执行器时兜底）")
    void defaultMapperGuardsFailurePayload() {
        Tool stub = stubTool();

        ToolResultBlockParam schemaBlock = stub.mapToToolResultBlockParam(
            ToolResult.failure("toolu_a", "bad params", ToolErrorKind.SCHEMA), "toolu_a", false);
        assertThat(schemaBlock.content()).isEqualTo("<tool_use_error>bad params</tool_use_error>");
        assertThat(schemaBlock.isError()).isTrue();

        ToolResultBlockParam denyBlock = stub.mapToToolResultBlockParam(
            ToolResult.failure("toolu_b", "Permission to use Edit has been denied.", ToolErrorKind.PERMISSION_DENY),
            "toolu_b", false);
        assertThat(denyBlock.content()).isEqualTo("Permission to use Edit has been denied.");
        assertThat(denyBlock.isError()).isTrue();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static ChatMessageDto render(ToolResult<?> result, Tool tool, boolean isError) {
        return LlmAgentLoop.toolResultMessage(result, "toolu_render", isError, tool, null,
            null, List.of(), List.of(), Map.of());
    }

    /** 最小 Tool 实现（不 override mapper → 走 Tool 默认 mapper 的第二道防线）。 */
    private static Tool stubTool() {
        return new Tool() {
            @Override
            public String name() {
                return "Stub";
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
                return ToolResult.success(call.id(), "ok");
            }
        };
    }
}

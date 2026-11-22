package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolError;
import com.nexusai.application.agent.tool.ToolErrorKind;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [错误通道 · C2] Edit / Write / NotebookEdit 的失败路径。
 *
 * <p><b>WHY</b>：实测假成功链路为「空 file_path 落到 executeInternal 普通 String 错误 →
 * 前缀表漏判 is_error=false → 成功模板 mapper 渲染成 The file  has been updated successfully」。
 * 本类锁定三件事：
 * <ol>
 *   <li>空 {@code file_path} 在 {@code validateInput} 就被拒（VALIDATE_INPUT 类别），不再下探到写盘；</li>
 *   <li>mapper 在 {@code isError} 或失败载荷（{@link ToolError}）时返回 {@code null}，
 *       绝不由本方法产成功模板；</li>
 *   <li>成功路径文案逐字不变（对齐 CC FileEditTool.ts:575-594 / FileWriteTool.ts:418-433）。</li>
 * </ol>
 */
@DisplayName("错误通道 C2 · Edit/Write 失败路径不产成功模板")
class EditWriteErrorChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabase() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static ToolUseBlock editCall(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return new ToolUseBlock("call-edit", "Edit", input);
    }

    private static ToolUseBlock writeCall(String path, String content) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return new ToolUseBlock("call-write", "Write", input);
    }

    private static ToolUseContext ctxFor(Path workspace) {
        String agentId = com.nexusai.application.agent.subagent.AgentContext.createAgentId();
        String sessionId = UUID.nameUUIDFromBytes(("c2-sess-" + workspace).getBytes()).toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    private static ToolResult<?> editSuccessResult(String filePath, boolean replaceAll) {
        Map<String, Object> so = new LinkedHashMap<>();
        so.put("filePath", filePath);
        so.put("userModified", false);
        so.put("replaceAll", replaceAll);
        return ToolResult.successWithStructuredOutput("call-edit", "ok", so);
    }

    private static ToolResult<?> writeSuccessResult(String filePath, String type) {
        Map<String, Object> so = new LinkedHashMap<>();
        so.put("filePath", filePath);
        so.put("type", type);
        return ToolResult.successWithStructuredOutput("call-write", "ok", so);
    }

    // ───────────────────────── 空 file_path 前置拒绝 ─────────────────────────

    @Test
    @DisplayName("Edit · 空 file_path → validateInput 拒绝（EMPTY_FILE_PATH，模型可行动）")
    void editEmptyPathRejectedByValidateInput(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));

        Tool.ValidationResult vr = tool.validateInput(
            editCall("", "a", "b").input(), ctxFor(workspace));

        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("EMPTY_FILE_PATH");
        assertThat(vr.message()).contains("file_path").contains("empty");
    }

    @Test
    @DisplayName("Edit · 空 file_path execute → 失败载荷 VALIDATE_INPUT，且不含成功文案")
    void editEmptyPathExecuteIsFailure(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));

        ToolResult<?> result = tool.execute(editCall("", "a", "b"), ctxFor(workspace));

        assertThat(result.data()).isInstanceOf(ToolError.class);
        ToolError err = (ToolError) result.data();
        assertThat(err.kind()).isEqualTo(ToolErrorKind.VALIDATE_INPUT);
        assertThat(err.message()).doesNotContain("has been updated successfully");
        assertThat(ToolResult.isErrorData(result.data())).isTrue();
    }

    @Test
    @DisplayName("Write · 空 file_path → validateInput 拒绝（EMPTY_FILE_PATH）")
    void writeEmptyPathRejectedByValidateInput(@TempDir Path workspace) {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));

        Tool.ValidationResult vr = tool.validateInput(
            writeCall("", "body").input(), ctxFor(workspace));

        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("EMPTY_FILE_PATH");
        assertThat(vr.message()).contains("file_path").contains("empty");
    }

    @Test
    @DisplayName("Write · 空 file_path execute → 失败载荷 VALIDATE_INPUT，且不含成功文案")
    void writeEmptyPathExecuteIsFailure(@TempDir Path workspace) {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));

        ToolResult<?> result = tool.execute(writeCall("", "body"), ctxFor(workspace));

        assertThat(result.data()).isInstanceOf(ToolError.class);
        ToolError err = (ToolError) result.data();
        assertThat(err.kind()).isEqualTo(ToolErrorKind.VALIDATE_INPUT);
        assertThat(err.message()).doesNotContain("updated successfully");
    }

    // ───────────────────────── mapper 第二道防线 ─────────────────────────

    @Test
    @DisplayName("Edit mapper · isError=true → null（不产成功模板）")
    void editMapperReturnsNullOnErrorFlag(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(
            editSuccessResult("/x/a.txt", false), "call-edit", true);

        assertThat(block).isNull();
    }

    @Test
    @DisplayName("Edit mapper · 失败载荷 ToolError（isError=false）→ null（不产成功模板）")
    void editMapperReturnsNullOnToolErrorPayload(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolResult<?> failure = ToolResult.failure(
            "call-edit", "The file_path parameter is empty.", ToolErrorKind.VALIDATE_INPUT);

        AgentToolResult<?> asAgentResult = failure;
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(asAgentResult, "call-edit", false);

        assertThat(block).isNull();
    }

    @Test
    @DisplayName("Write mapper · isError=true → null；成功 create 文案逐字不变")
    void writeMapperGuardAndSuccessText(@TempDir Path workspace) {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));

        assertThat(tool.mapToToolResultBlockParam(writeSuccessResult("/x/a.txt", "create"),
            "call-write", true))
            .as("失败必须不产成功模板").isNull();

        ToolResultBlockParam ok = tool.mapToToolResultBlockParam(
            writeSuccessResult("/x/a.txt", "create"), "call-write", false);
        assertThat(ok).isNotNull();
        assertThat(ok.content()).isEqualTo("File created successfully at: /x/a.txt");

        ToolResultBlockParam upd = tool.mapToToolResultBlockParam(
            writeSuccessResult("/x/a.txt", "update"), "call-write", false);
        assertThat(upd).isNotNull();
        assertThat(upd.content()).isEqualTo("The file /x/a.txt has been updated successfully.");
    }

    @Test
    @DisplayName("Edit mapper · 成功文案逐字不变（默认 / replace_all）")
    void editMapperSuccessTextVerbatim(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));

        ToolResultBlockParam def = tool.mapToToolResultBlockParam(
            editSuccessResult("/x/a.txt", false), "call-edit", false);
        assertThat(def).isNotNull();
        assertThat(def.content()).isEqualTo("The file /x/a.txt has been updated successfully.");

        ToolResultBlockParam all = tool.mapToToolResultBlockParam(
            editSuccessResult("/x/a.txt", true), "call-edit", false);
        assertThat(all).isNotNull();
        assertThat(all.content())
            .isEqualTo("The file /x/a.txt has been updated. All occurrences were successfully replaced.");
    }

    @Test
    @DisplayName("Notebook mapper · 失败载荷 ToolError（isError=false）→ 交默认渲染器，is_error=true")
    void notebookMapperDelegatesOnToolError() {
        NotebookEditTool tool = new NotebookEditTool();
        ToolResult<?> failure = ToolResult.failure(
            "call-nb", "Notebook edit failed.", ToolErrorKind.CALL_THREW);

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(failure, "call-nb", false);

        assertThat(block).isNotNull();
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).isEqualTo("Notebook edit failed.");
    }
}

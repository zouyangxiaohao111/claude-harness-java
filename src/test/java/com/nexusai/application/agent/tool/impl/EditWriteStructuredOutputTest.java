package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.StructuredPatchHunk;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session E1 · Edit/Write 结构化输出契约（CC data 形状 + mapToolResult 短文本）。
 *
 * <p>WHY（规则九 · 验证意图）：E1 把 Edit/Write 输出从纯文本 ToolResult.success 改为
 * successWithStructuredOutput（{@code structuredPatch} + {@code originalFile} + 可选 {@code gitDiff}），
 * 结构化载荷经 ToolResultApplier → AttachmentMessageDto.structured_output 供前端 diff 视图消费。
 * 本测试锁定 data 形状（对齐 CC FileEditTool.ts:555-572 / FileWriteTool.ts:369-401）
 * 与模型可见 summary 文本（对齐 CC mapToolResultToToolResultBlockParam）——
 * 任一字段被删/文本被改，前端 diff 视图或 CC 对齐度即断裂。
 *
 * <p>注意：测试环境 gate 默认关（无 CLAUDE_CODE_REMOTE env）→ 输出不应含 gitDiff 字段。
 */
@DisplayName("Session E1 · Edit/Write 结构化输出契约（CC data 形状 + map 短文本）")
class EditWriteStructuredOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock editCallWith(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return new ToolUseBlock("call-edit", "edit_file", input);
    }

    private static ToolUseBlock writeCallWith(String path, String content) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return new ToolUseBlock("call-write", "write_file", input);
    }

    private static ToolUseBlock readCallWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    private static ToolUseContext ctxFor(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("esos-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("esos-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("Edit 成功 → 结构化输出含 CC data 全字段 + 普通 summary 短文本")
    void editReturnsCcDataShape(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "old\n");
        // read-before-write 门禁：先 Read（同一 ctx，否则 gate state 不在会话内）
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));

        ToolResult<?> result = tool.execute(editCallWith("a.txt", "old", "new"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        Map<String, Object> out = ToolResult.presentationMeta(result);
        // CC data 全字段（FileEditTool.ts:561-568）
        assertThat(out).containsKeys(
            "filePath", "oldString", "newString", "originalFile",
            "structuredPatch", "userModified", "replaceAll");
        assertThat(out.get("oldString")).isEqualTo("old");
        assertThat(out.get("newString")).isEqualTo("new");
        assertThat(out.get("originalFile")).isEqualTo("old\n");
        assertThat(out.get("userModified")).isEqualTo(false);
        assertThat(out.get("replaceAll")).isEqualTo(false);
        assertThat(out.get("structuredPatch"))
            .as("structuredPatch 必须是 hunk 数组（CC hunkSchema）")
            .isInstanceOf(List.class);
        List<?> patch = (List<?>) out.get("structuredPatch");
        assertThat(patch).isNotEmpty();
        assertThat(patch.get(0)).isInstanceOf(StructuredPatchHunk.class);
        // gate 默认关 → 无 gitDiff
        assertThat(out).doesNotContainKey("gitDiff");
        // 模型可见短文本（CC FileEditTool.ts:592）。IMP-C2 组 2-1 拍板后 summary 不再单独作为
        // data(String)，而是折入 data(Map) 的 "summary" 键（successWithStructuredOutput），
        // 与 CC data 结构化对象同构（CC mapToolResult content 经 renderToolResultPayloadText 提取）。
        String filePath = (String) out.get("filePath");
        assertThat(filePath).endsWith("a.txt");
        assertThat(out.get("summary")).isEqualTo(
            "The file " + filePath + " has been updated successfully.");
    }

    @Test
    @DisplayName("Edit replace_all=true → summary 走 All occurrences 文案（CC FileEditTool.ts:585）")
    void editReplaceAllSummary(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "old\nold\n");
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));

        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", "a.txt");
        input.put("old_string", "old");
        input.put("new_string", "new");
        input.put("replace_all", true);

        ToolResult<?> result = tool.execute(
            new ToolUseBlock("call-edit", "edit_file", input), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        Map<String, Object> out = ToolResult.presentationMeta(result);
        assertThat(out.get("replaceAll")).isEqualTo(true);
        assertThat(out.get("structuredPatch")).isInstanceOf(List.class);
        String filePath = (String) out.get("filePath");
        assertThat(out.get("summary")).isEqualTo(
            "The file " + filePath + " has been updated. All occurrences were successfully replaced.");
    }

    @Test
    @DisplayName("Write 新建文件 → type=create + structuredPatch=[] + originalFile=null + create summary")
    void writeCreateShape(@TempDir Path workspace) throws Exception {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);

        ToolResult<?> result = tool.execute(writeCallWith("fresh.txt", "content"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        Map<String, Object> out = ToolResult.presentationMeta(result);
        // CC FileWriteTool.ts:396-401
        assertThat(out.get("type")).isEqualTo("create");
        assertThat(out.get("content")).isEqualTo("content");
        assertThat(out.get("structuredPatch")).isEqualTo(List.of());
        assertThat(out.get("originalFile")).isNull();
        assertThat(out).doesNotContainKey("gitDiff");
        String filePath = (String) out.get("filePath");
        assertThat(filePath).endsWith("fresh.txt");
        // CC FileWriteTool.ts:424
        assertThat(out.get("summary")).isEqualTo("File created successfully at: " + filePath);
    }

    @Test
    @DisplayName("Write 覆盖已有文件 → type=update + structuredPatch 非空 + originalFile=旧内容 + update summary")
    void writeUpdateShape(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("b.txt"), "old\n");
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("b.txt"), ctx);
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));

        ToolResult<?> result = tool.execute(writeCallWith("b.txt", "new\n"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        Map<String, Object> out = ToolResult.presentationMeta(result);
        // CC FileWriteTool.ts:373-378
        assertThat(out.get("type")).isEqualTo("update");
        assertThat(out.get("content")).isEqualTo("new\n");
        assertThat(out.get("originalFile")).isEqualTo("old\n");
        assertThat(out.get("structuredPatch")).isInstanceOf(List.class);
        List<?> patch = (List<?>) out.get("structuredPatch");
        assertThat(patch).isNotEmpty();
        assertThat(out).doesNotContainKey("gitDiff");
        String filePath = (String) out.get("filePath");
        // CC FileWriteTool.ts:426-430
        assertThat(out.get("summary")).isEqualTo(
            "The file " + filePath + " has been updated successfully.");
    }
}

package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code replace_all} 的 semanticBoolean 容忍 · 对齐 CC {@code utils/semanticBoolean.ts:24-29}。
 *
 * <p><b>WHY</b>：CC 的 Edit schema 用 {@code semanticBoolean(z.boolean().default(false))} ——
 * 广告仍是 boolean，但运行时把字符串 {@code "true"}/{@code "false"} 归一为布尔。
 * Java 旧 schema 无 {@code x-semantic-boolean}，模型把布尔写成字符串时会被判 invalid_type，
 * 与 CC（能执行）相反。
 *
 * <p>本测试锁定：① schema 广告 boolean + 运行时容忍标记；② 带标记时字符串通过 schema 校验；
 * ③ 不带标记的其它字符串仍按要求拒绝；④ 经 {@link EditFileTool#validateInput} 的多匹配判定
 * 与字符串布尔一致（Jackson {@code TextNode.asBoolean} 会把 "true"/"false" 解析为布尔）。
 */
@DisplayName("Edit replace_all semanticBoolean 容忍（CC utils/semanticBoolean.ts）")
class EditReplaceAllSemanticBooleanTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolInputValidator VALIDATOR = new ToolInputValidator();

    @BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabase() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static ToolUseContext ctxFor(Path workspace) {
        String agentId = com.nexusai.application.agent.subagent.AgentContext.createAgentId();
        String sessionId = UUID.nameUUIDFromBytes(("rall-sess-" + workspace).getBytes()).toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    private static ToolUseBlock readCallWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    private static ObjectNode editInput(String path, String oldText, String newText, JsonNode replaceAll) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        if (replaceAll != null) {
            input.set("replace_all", replaceAll);
        }
        return input;
    }

    @Test
    @DisplayName("schema：replace_all 仍广告 boolean，但带 x-semantic-boolean 运行时容忍标记")
    void schemaAdvertisesBooleanWithSemanticMarker() {
        EditFileTool tool = new EditFileTool(new PathGuard(Path.of(".")));
        JsonNode replaceAll = tool.inputSchema().path("properties").path("replace_all");
        assertThat(replaceAll.path("type").asText()).isEqualTo("boolean");
        assertThat(replaceAll.path("default").asBoolean()).isFalse();
        assertThat(replaceAll.path("x-semantic-boolean").asBoolean())
            .as("对齐 CC semanticBoolean：广告 boolean + 运行时字符串容忍")
            .isTrue();
    }

    @Test
    @DisplayName("schema 校验：字符串 \"true\"/\"false\" 通过；其它字符串仍拒")
    void stringBooleansPassSchemaValidation() {
        EditFileTool tool = new EditFileTool(new PathGuard(Path.of(".")));

        assertThat(VALIDATOR.safeParseSchema(tool,
            editInput("a.txt", "x", "y", JSON.getNodeFactory().textNode("true"))).ok())
            .as("CC semanticBoolean 接受 \"true\"")
            .isTrue();
        assertThat(VALIDATOR.safeParseSchema(tool,
            editInput("a.txt", "x", "y", JSON.getNodeFactory().textNode("false"))).ok())
            .as("CC semanticBoolean 接受 \"false\"")
            .isTrue();
        assertThat(VALIDATOR.safeParseSchema(tool,
            editInput("a.txt", "x", "y", JSON.getNodeFactory().textNode("yes"))).ok())
            .as("非法字符串仍按 invalid_type 拒绝（z.preprocess 不通过 → inner boolean 失败）")
            .isFalse();
    }

    @Test
    @DisplayName("validateInput：字符串 \"true\" 视为 replace_all=true（多处匹配放行）")
    void stringTrueAllowsMultipleMatches(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\nhello\n");
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

        Tool.ValidationResult vr = editTool.validateInput(
            editInput("a.txt", "hello", "bye", JSON.getNodeFactory().textNode("true")), ctx);

        assertThat(vr.ok())
            .as("CC semanticBoolean(\"true\") → true，多匹配不应报 errorCode 9")
            .isTrue();
    }

    @Test
    @DisplayName("validateInput：字符串 \"false\" 视为 replace_all=false（多匹配拒绝 errorCode 9）")
    void stringFalseRejectsMultipleMatches(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\nhello\n");
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

        Tool.ValidationResult vr = editTool.validateInput(
            editInput("a.txt", "hello", "bye", JSON.getNodeFactory().textNode("false")), ctx);

        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("9");
    }
}

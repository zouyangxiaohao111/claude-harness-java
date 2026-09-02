package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.bash.BashCommandClassification;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 · mapToToolResultBlockParam 接线 + searchReadKind/BashCommandClassification 分类测试.
 *
 * <p><b>WHY (规则 9 · 测试验证意图)</b>: G2 把 tool_result 块构造从「renderToolResultPayloadText
 * 旁路直拼」（DEL-G2-01）改为 per-tool mapper（对齐 CC toolExecution.ts:1292），本测试锁定:
 * <ul>
 *   <li>per-tool mapper（Bash/Edit/Write）产出 CC 同款 content 文案（BashTool.tsx:617-623 /
 *       FileEditTool.ts:575-596 / FileWriteTool.ts:418-433）;</li>
 *   <li>{@link LlmAgentLoop#toolResultMessage} 消费 per-tool mapper（有 Tool → mapper 文案；
 *       无 Tool → 默认兜底）;</li>
 *   <li>Bash {@link Tool#searchReadKind} 4 态分类（CC BashTool.tsx:469-477 +
 *       BashTool.tsx:95-172 算法）;</li>
 *   <li>{@link BashCommandClassification} 操作符/重定向/语义中性分类（CC 语义陷阱）。</li>
 * </ul>
 */
class G2ToolResultBlockParamTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PathGuard GUARD = new PathGuard(java.nio.file.Paths.get(".").toAbsolutePath());

    // ────────────────────────── Bash mapper ──────────────────────────

    @Test
    @DisplayName("Bash mapper: content=data 文本, is_error 透传（CC BashTool.tsx:617-623）")
    void bashMapper_contentIsDataIsErrorPassthrough() {
        BashTool tool = new BashTool();
        ToolResult ok = ToolResult.success("toolu_b", "line1\nline2");
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(ok, "toolu_b", false);
        assertThat(block.toolUseId()).isEqualTo("toolu_b");
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.content()).isEqualTo("line1\nline2");
        assertThat(block.isError()).isFalse();

        ToolResult err = ToolResult.error("toolu_be", "boom");
        ToolResultBlockParam errBlock = tool.mapToToolResultBlockParam(err, "toolu_be", true);
        assertThat(errBlock.isError()).as("interrupted/error → is_error=true（CC is_error: interrupted）").isTrue();
        assertThat(errBlock.content()).isEqualTo("boom");
    }

    // ────────────────────────── Edit mapper ──────────────────────────

    @Test
    @DisplayName("Edit mapper: replaceAll 分支 → '...All occurrences...'（CC FileEditTool.ts:583-586）")
    void editMapper_replaceAllBranch() {
        EditFileTool tool = new EditFileTool(GUARD);
        Map<String, Object> so = new LinkedHashMap<>();
        so.put("filePath", "/tmp/a.java");
        so.put("userModified", false);
        so.put("replaceAll", true);
        ToolResult ok = ToolResult.successWithStructuredOutput("toolu_e", "summary", so);
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(ok, "toolu_e", false);
        assertThat(block.content())
            .isEqualTo("The file /tmp/a.java has been updated. All occurrences were successfully replaced.");
        assertThat(block.isError()).isFalse();
    }

    @Test
    @DisplayName("Edit mapper: userModified 追加 modifiedNote（CC FileEditTool.ts:579-581）")
    void editMapper_userModifiedNote() {
        EditFileTool tool = new EditFileTool(GUARD);
        Map<String, Object> so = new LinkedHashMap<>();
        so.put("filePath", "/tmp/b.java");
        so.put("userModified", true);
        so.put("replaceAll", false);
        ToolResult ok = ToolResult.successWithStructuredOutput("toolu_e2", "summary", so);
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(ok, "toolu_e2", false);
        // CC FileEditTool.ts:579-581 modifiedNote + :594 `has been updated successfully${modifiedNote}.`
        // —— 注意 trailing '.' 在 modifiedNote 之后（CC 原文如此，含 "them. ." 双句点）
        assertThat(block.content())
            .isEqualTo("The file /tmp/b.java has been updated successfully"
                + ".  The user modified your proposed changes before accepting them. "
                + ".");
    }

    // ────────────────────────── Write mapper ──────────────────────────

    @Test
    @DisplayName("Write mapper: create/update 分支（CC FileWriteTool.ts:420-432）")
    void writeMapper_createAndUpdate() {
        WriteFileTool tool = new WriteFileTool(GUARD);

        Map<String, Object> createSo = new LinkedHashMap<>();
        createSo.put("type", "create");
        createSo.put("filePath", "/tmp/new.txt");
        ToolResultBlockParam createBlock =
            tool.mapToToolResultBlockParam(ToolResult.successWithStructuredOutput("toolu_c", "s", createSo),
                "toolu_c", false);
        assertThat(createBlock.content()).isEqualTo("File created successfully at: /tmp/new.txt");

        Map<String, Object> updateSo = new LinkedHashMap<>();
        updateSo.put("type", "update");
        updateSo.put("filePath", "/tmp/existing.txt");
        ToolResultBlockParam updateBlock =
            tool.mapToToolResultBlockParam(ToolResult.successWithStructuredOutput("toolu_u", "s", updateSo),
                "toolu_u", false);
        assertThat(updateBlock.content()).isEqualTo("The file /tmp/existing.txt has been updated successfully.");
    }

    // ─────────────────── LlmAgentLoop.toolResultMessage 接线 ───────────────────

    private Tool stubWithMapper(String name, String prefix) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
            @Override public ToolResultBlockParam mapToToolResultBlockParam(
                    AgentToolResult<?> result, String toolUseId, boolean isError) {
                return new ToolResultBlockParam(toolUseId, "tool_result",
                    prefix + result.data(), isError);
            }
        };
    }

    @Test
    @DisplayName("toolResultMessage 经 per-tool mapper 构造 payload（对齐 CC toolExecution.ts:1292）")
    void toolResultMessage_usesPerToolMapper() {
        Tool tool = stubWithMapper("stub", "MAPPED:");
        ToolResult result = ToolResult.success("toolu_1", "payload");
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage(result, "toolu_1", false, tool, "asm-1",
            null, List.of(), List.of(), Map.of());
        assertThat(dto.role()).isEqualTo(Role.tool);
        assertThat(dto.toolCallId()).isEqualTo("toolu_1");
        assertThat(dto.content())
            .as("payload 必须来自 per-tool mapper（DEL-G2-01 旁路直拼已删）")
            .isEqualTo("MAPPED:payload");
        assertThat(dto.isError()).isFalse();
    }

    @Test
    @DisplayName("toolResultMessage 无 Tool → 默认渲染器兜底（synthetic error / fork 路径）")
    void toolResultMessage_nullTool_fallsBackToDefault() {
        ToolResult result = ToolResult.success("toolu_2", "payload");
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage(result, "toolu_2", false, null, null,
            null, List.of(), List.of(), Map.of());
        assertThat(dto.content())
            .as("无 Tool 实例 → renderToolResultPayloadText 兜底")
            .isEqualTo("payload");
        assertThat(dto.toolCallId()).isEqualTo("toolu_2");
    }

    @Test
    @DisplayName("toolResultMessage 经 Bash per-tool mapper（data 文本原样为 payload）")
    void toolResultMessage_usesBashMapper() {
        BashTool tool = new BashTool();
        ToolResult result = ToolResult.success("toolu_bash", "hello world");
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage(result, "toolu_bash", false, tool, null,
            null, List.of(), List.of(), Map.of());
        assertThat(dto.content()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("toolResultMessage 块数组注入 contentBlocks（tool_reference 不被 renderToolResultPayloadText 丢弃为 record toString）")
    void toolResultMessage_blockArrayInjectsContentBlocks() {
        Tool tool = new Tool() {
            @Override public String name() { return "ToolSearchTool"; }
            @Override public String description() { return "ToolSearchTool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
            @Override public ToolResultBlockParam mapToToolResultBlockParam(
                    AgentToolResult<?> result, String toolUseId, boolean isError) {
                return new ToolResultBlockParam(toolUseId, "tool_result",
                    List.of(new ToolReferenceBlockParam("LspTool")), false);
            }
        };
        ToolResult result = ToolResult.success("toolu_search", "ignored-record-data");
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage(result, "toolu_search", false, tool, "asm-search",
            null, List.of(), List.of(), Map.of());

        // WHY (规则 9): 块数组 content 若走 renderToolResultPayloadText 会产出内部 record toString
        // （"ToolSearchOutput[...]"），tool_reference 语义被丢弃，LspTool.shouldDefer 可达性依赖的
        // tool_reference 块无法送达模型。此处断言 payload 置空 + 块数组真实流入 contentBlocks。
        assertThat(dto.content())
            .as("块数组场景 payload 置空，避免 provider 前置空文本块")
            .isEmpty();
        assertThat(dto.contentBlocks()).hasSize(1);
        JsonNode block = (JsonNode) dto.contentBlocks().get(0);
        assertThat(block.get("type").asText()).isEqualTo("tool_reference");
        assertThat(block.get("tool_name").asText())
            .as("键名须为 tool_name（@JsonProperty 生效），否则 provider 端 tool_name 读空被 fail-loud 丢弃")
            .isEqualTo("LspTool");
        // 精确 JSON 形状断言（防 @JsonTypeInfo 意外包裹/改写多余字段）
        assertThat(block.size()).isEqualTo(2);
        assertThat(block.has("toolName"))
            .as("不得产出 camelCase toolName 键")
            .isFalse();
    }

    // ─────────────────── Bash searchReadKind 4 态分类 ───────────────────

    private Tool.SearchReadKind classify(String command) {
        BashTool tool = new BashTool();
        com.fasterxml.jackson.databind.node.ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return tool.searchReadKind(input);
    }

    @Test
    @DisplayName("Bash searchReadKind: cat→IS_READ / grep·find→IS_SEARCH / ls→IS_LIST（CC BashTool.tsx:469-477）")
    void bashSearchReadKind_basicClassification() {
        assertThat(classify("cat file.txt")).isEqualTo(Tool.SearchReadKind.IS_READ);
        assertThat(classify("head -5 file.txt")).isEqualTo(Tool.SearchReadKind.IS_READ);
        assertThat(classify("grep foo file.txt")).isEqualTo(Tool.SearchReadKind.IS_SEARCH);
        assertThat(classify("find . -name '*.java'")).isEqualTo(Tool.SearchReadKind.IS_SEARCH);
        assertThat(classify("ls -la")).isEqualTo(Tool.SearchReadKind.IS_LIST);
        assertThat(classify("tree")).isEqualTo(Tool.SearchReadKind.IS_LIST);
    }

    @Test
    @DisplayName("Bash searchReadKind: 非搜索/读取命令 → NONE（rm/echo 等）")
    void bashSearchReadKind_nonCollapsible() {
        assertThat(classify("rm -rf /")).as("写命令不折叠（CC BashTool.tsx:147-151 整条全 false）").isEqualTo(Tool.SearchReadKind.NONE);
        assertThat(classify("echo hello")).as("仅语义中性命令不折叠（BashTool.tsx:160-165）").isEqualTo(Tool.SearchReadKind.NONE);
        assertThat(classify("")).as("空命令不折叠").isEqualTo(Tool.SearchReadKind.NONE);
    }

    @Test
    @DisplayName("Bash searchReadKind: 重定向目标跳读 + 操作符分隔（CC BashTool.tsx:121-128）")
    void bashSearchReadKind_redirectAndOperators() {
        assertThat(classify("ls > out.txt"))
            .as("重定向目标 out.txt 被跳读，ls 仍折叠为 IS_LIST")
            .isEqualTo(Tool.SearchReadKind.IS_LIST);
        assertThat(classify("cat file | grep foo"))
            .as("管线全为 read/search → isRead && isSearch 并存（Java 优先返回 IS_SEARCH）")
            .isEqualTo(Tool.SearchReadKind.IS_SEARCH);
        assertThat(classify("ls dir && echo '---' && ls dir2"))
            .as("echo 语义中性，双 ls 折叠为 IS_LIST")
            .isEqualTo(Tool.SearchReadKind.IS_LIST);
        assertThat(classify("cat file | bq"))
            .as("bq 非三集命令 → 整条不折叠（CC 语义陷阱）")
            .isEqualTo(Tool.SearchReadKind.NONE);
    }

    // ─────────────────── BashCommandClassification 单测 ───────────────────

    @Test
    @DisplayName("BashCommandClassification: 引号内操作符不切分 + 多命令复合")
    void classification_quotedOperatorsNotSplit() {
        BashCommandClassification.SearchReadClassification c =
            BashCommandClassification.classify("echo 'a | b' && ls");
        assertThat(c.isList()).as("引号内 | 不切分，ls 仍识别为 list").isTrue();
    }

    @Test
    @DisplayName("BashCommandClassification: isCollapsible = isSearch||isRead||isList（collapseReadSearch.ts:220）")
    void classification_collapsibleAggregation() {
        assertThat(BashCommandClassification.classify("grep x f").isCollapsible()).isTrue();
        assertThat(BashCommandClassification.classify("cat f").isCollapsible()).isTrue();
        assertThat(BashCommandClassification.classify("ls").isCollapsible()).isTrue();
        assertThat(BashCommandClassification.classify("rm -rf /").isCollapsible()).isFalse();
        assertThat(BashCommandClassification.classify("echo hi").isCollapsible()).isFalse();
    }

    // ──────────── isDestructive / isOpenWorld 语义对照（CC Tool.ts 契约） ────────────

    /**
     * WHY (规则 9 · 测试验证意图): G2 语义核查确认 isDestructive/isOpenWorld 是「基于 input
     * 的安全分类」。CC 消费方全在 UI 渲染层（print.ts:1661-1662 是 MCP server 列表渲染标记），
     * Java 无前端 → 本测试<b>只断言与 CC 契约一致的映射语义，不断言任何消费路径</b>
     * （不虚构消费方，RES-G2-01 受控残留）。
     */
    @Test
    @DisplayName("isDestructive/isOpenWorld 默认 false（CC buildTool isDestructive→false Tool.ts:761；isOpenWorld 不在 DefaultableToolKeys 缺省）")
    void defaultTool_safetyClassification_defaultFalse() {
        Tool tool = new Tool() {
            @Override public String name() { return "plain"; }
            @Override public String description() { return "plain"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) { return null; }
        };
        JsonNode input = JSON.createObjectNode();
        assertThat(tool.isDestructive(input))
            .as("CC TOOL_DEFAULTS.isDestructive=()=>false（Tool.ts:761）→ Java default false 对齐")
            .isFalse();
        assertThat(tool.isOpenWorld(input))
            .as("CC isOpenWorld 不在 DefaultableToolKeys（Tool.ts:709-717）无 buildTool 默认 → "
                + "print.ts:1662 ?.() 缺省；Java default false 为 fail-closed 等价")
            .isFalse();
    }

}

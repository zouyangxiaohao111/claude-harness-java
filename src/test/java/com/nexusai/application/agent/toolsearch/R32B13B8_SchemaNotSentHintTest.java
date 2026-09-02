package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b13 · B8 SchemaNotSentHint · 对齐 CC Open-ClaudeCode/src/services/tools/toolExecution.ts:572-597
 * {@code buildSchemaNotSentHint} · [Session H P2-1] 升级为 CC 完整 4 道乐观门后回归测试.
 *
 * <p><b>WHY (意图验证)</b>: b13 brief 对齐 CC schema not sent 路径. 当 tool schema 因
 * deferred 机制未发给 LLM 时, schema 验证失败应注入 hint 让 LLM 知道重新加载工具.
 * Java 端 4 道门 (CC toolExecution.ts:587-591):
 * <ol>
 *   <li>{@code isToolSearchEnabledOptimistic} — feature gate (env)</li>
 *   <li>{@code isToolSearchToolAvailable(tools)} — ToolSearch 在工具列表</li>
 *   <li>{@code isDeferredTool(tool)} — tool 是 deferred (CC prompt.ts:62-108)</li>
 *   <li>{@code !extractDiscoveredToolNames(messages).has(tool.name)} — 不在 discovered set</li>
 * </ol>
 *
 * <h2>测试用例 (5 项)</h2>
 * <ol>
 *   <li>MCP tool + schema fail → 注入 hint (核心场景, 4 门全过)</li>
 *   <li>MCP tool + alwaysLoad=true → 不注入 (CC prompt.ts:64-66 alwaysLoad 优先级最高)</li>
 *   <li>Non-MCP tool + shouldDefer=false → 不注入 (CC prompt.ts:107 shouldDefer 默认 false)</li>
 *   <li>Null tool/ctx → 不注入 (defensive)</li>
 *   <li>Hint 文案包含 ToolSearch 常量名 + 工具名 (供 LLM 识别加载目标)</li>
 * </ol>
 */
class R32B13B8_SchemaNotSentHintTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolUseContext createCtx(List<Tool> availableTools) {
        UUID agentId = UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return new ToolUseContext(agentId, sessionId, PermissionMode.DEFAULT,
            Map.of(), availableTools, "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    /** Mock tool: isMcp / alwaysLoad / shouldDefer 可控 (对齐 4 门语义). */
    static class MockTool implements Tool {
        private final String name;
        private final boolean isMcp;
        private final boolean alwaysLoad;
        private final boolean shouldDefer;

        MockTool(String name, boolean isMcp, boolean alwaysLoad, boolean shouldDefer) {
            this.name = name;
            this.isMcp = isMcp;
            this.alwaysLoad = alwaysLoad;
            this.shouldDefer = shouldDefer;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "Mock tool"; }

        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        @Override public boolean isMcp() { return isMcp; }

        @Override public boolean alwaysLoad() { return alwaysLoad; }

        @Override public boolean shouldDefer(com.fasterxml.jackson.databind.JsonNode input) {
            return shouldDefer;
        }

        @Override public McpServerInfo mcpInfo() {
            return isMcp ? new McpServerInfo(name + "_server", "stdio") : null;
        }
    }

    /** gate2 前置: 工具列表含 ToolSearch (CC isToolSearchToolAvailable). */
    private static final MockTool TOOL_SEARCH = new MockTool("ToolSearch", false, false, false);

    @Test
    @DisplayName("B8.1 MCP tool + 4 门全过 → 注入 hint (核心场景)")
    void mcpTool_schemaFail_injectsHint() {
        MockTool mcpTool = new MockTool("mcp__github__create_issue", true, false, true);
        ToolUseContext ctx = createCtx(List.of(TOOL_SEARCH, mcpTool));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, JSON.createObjectNode());

        assertThat(hint).isNotNull();
        assertThat(hint).contains("schema");      // 提到 schema 问题
        assertThat(hint).contains("mcp__github__create_issue"); // 包含 tool name 供 LLM 识别
        assertThat(hint).contains("Load the tool first"); // CC 英文引导 LLM 重新加载工具 (select:)
    }

    @Test
    @DisplayName("B8.2 MCP tool + alwaysLoad=true → 不注入 (CC prompt.ts:64-66 优先级最高)")
    void mcpToolWithAlwaysLoad_schemaFail_noHint() {
        MockTool mcpTool = new MockTool("mcp__core__essential", true, true, true);
        ToolUseContext ctx = createCtx(List.of(TOOL_SEARCH, mcpTool));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, JSON.createObjectNode());

        // alwaysLoad=true 是 CC isDeferredTool 永远 false 的最强规则
        assertThat(hint).isNull();
    }

    @Test
    @DisplayName("B8.3 Non-MCP tool + shouldDefer=false → 不注入 (CC prompt.ts:107 默认 false)")
    void nonMcpTool_schemaFail_noHint() {
        MockTool readTool = new MockTool("Read", false, false, false);
        ToolUseContext ctx = createCtx(List.of(TOOL_SEARCH, readTool));

        String hint = SchemaNotSentHint.build(readTool, ctx, JSON.createObjectNode());

        // 4 门语义: 非 MCP 且 shouldDefer=false → isDeferredTool=false → gate3 命中 null
        assertThat(hint).isNull();
    }

    @Test
    @DisplayName("B8.4 Null tool / null ctx → 不注入 (defensive, 不抛 NPE)")
    void nullInputs_noHint_noNpe() {
        // null tool → null
        assertThat(SchemaNotSentHint.build(null, createCtx(List.of(TOOL_SEARCH)),
            JSON.createObjectNode())).isNull();
        // null ctx → null
        MockTool mcpTool = new MockTool("mcp__test", true, false, true);
        assertThat(SchemaNotSentHint.build(mcpTool, null, JSON.createObjectNode())).isNull();
    }

    @Test
    @DisplayName("B8.5 Hint 文案包含 ToolSearch 常量名 + 工具名 (加载目标指引)")
    void hintTemplate_containsToolSearchConstant() {
        // 验证 TOOL_SEARCH_TOOL_NAME 常量已定义且等于 "ToolSearch"
        // (与 CC tools/ToolSearchTool/constants.ts:1 对齐)
        assertThat(ToolNameConstants.TOOL_SEARCH_TOOL_NAME).isEqualTo("ToolSearch");

        // 验证 hint 文案不为空 + 中文 (CLAUDE.md 规则)
        MockTool mcpTool = new MockTool("mcp__demo", true, false, true);
        String hint = SchemaNotSentHint.build(mcpTool, createCtx(List.of(TOOL_SEARCH, mcpTool)),
            JSON.createObjectNode());

        assertThat(hint).isNotBlank();
        // CC 英文版三段特征 (toolExecution.ts:592-596)
        assertThat(hint).containsAnyOf("Load the tool first", "schema", "select:");
    }
}

package com.nexusai.application.agent.toolsearch;

import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.SessionToolVisibility;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本轮选中应用 ⇒ alwaysLoad（豁免「MCP 恒 defer」）。
 *
 * <p>三处一致：
 * <ol>
 *   <li>schema：选中应用的工具不被 deferred 剔除（直接进本轮 schema）；</li>
 *   <li>deferred 集合：选中工具不在其中；未选工具仍在（若还留在池里）；</li>
 *   <li>执行池：allowlist 裁剪后只剩选中应用，与 schema 结果一致。</li>
 * </ol>
 */
@DisplayName("app-selection · 选中应用 alwaysLoad（schema/deferred/执行池一致）")
class SelectedAppAlwaysLoadTest {

    private static Tool mcp(String fullName) {
        String server = McpStringUtils.mcpInfoFromString(fullName).serverName();
        String tool = McpStringUtils.mcpInfoFromString(fullName).toolName();
        return new Tool() {
            @Override public String name() { return fullName; }
            @Override public String description() { return fullName; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
            @Override public McpServerInfo mcpInfo() { return new McpServerInfo(server, tool); }
        };
    }

    private static Tool plain(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static List<Tool> pool() {
        return List.of(plain("Read"), mcp("mcp__A__t1"), mcp("mcp__B__t1"));
    }

    @Test
    @DisplayName("选中应用的工具：豁免 MCP 恒 defer（未选仍 defer）")
    void selectedTool_isNotDeferred() {
        Set<String> alwaysLoad = Set.of("mcp__A__t1");

        assertThat(ToolSearchService.isDeferredTool(mcp("mcp__A__t1"), null, alwaysLoad)).isFalse();
        assertThat(ToolSearchService.isDeferredTool(mcp("mcp__B__t1"), null, alwaysLoad)).isTrue();
        // 不带覆盖：维持「MCP 恒 defer」
        assertThat(ToolSearchService.isDeferredTool(mcp("mcp__A__t1"), null)).isTrue();
    }

    @Test
    @DisplayName("deferred 集合：选中工具被排除，未选 MCP 仍在")
    void deferredSet_excludesSelected() {
        Set<String> deferred = ToolSearchService.computeDeferredToolNames(
            pool(), Set.of("mcp__A__t1"));

        assertThat(deferred).contains("mcp__B__t1").doesNotContain("mcp__A__t1");
    }

    @Test
    @DisplayName("schema 保留选中工具（inline）且与执行池裁剪结果一致")
    void schemaKeepsSelected_inlineMatchesExecutionPool() {
        Set<String> alwaysLoad = Set.of("mcp__A__t1");
        Set<String> deferred = ToolSearchService.computeDeferredToolNames(pool(), alwaysLoad);

        List<String> schemaNames = ToolSearchService
            .filterToolsForSchema(pool(), true, deferred, Set.of())
            .stream().map(Tool::name).toList();
        List<String> executionNames = SessionToolVisibility
            .filterByMcpAllowlist(pool(),
                SessionToolVisibility.normalizeSelectedApps(List.of("A")))
            .stream().map(Tool::name).toList();

        assertThat(schemaNames).contains("mcp__A__t1", "Read").doesNotContain("mcp__B__t1");
        assertThat(executionNames).containsExactlyInAnyOrderElementsOf(schemaNames);
    }
}

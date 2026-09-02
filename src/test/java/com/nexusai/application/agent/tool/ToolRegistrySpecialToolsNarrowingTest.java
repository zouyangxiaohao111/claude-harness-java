package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.tool.impl.ListMcpResourcesTool;
import com.nexusai.application.agent.tool.impl.ReadMcpResourceTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S04 (B4): SPECIAL_TOOLS 过滤收窄测试（验收 2）· 对齐 CC {@code tools.ts:301-307} specialTools
 * 只过滤 builtin 基座（getAllBaseTools）+ {@code client.ts:2182-2198} resource 工具条件性加入
 * LLM 池（resources 能力 server 存在时对 LLM 可见）。
 *
 * <p>schema 期（{@link ToolRegistry#toOpenAiToolsArray}）只过滤 builtin 分区特例
 * {@code SYNTHETIC_OUTPUT_TOOL_NAME}（主链唯一）；{@code ListMcpResourcesTool}/
 * {@code ReadMcpResourceTool}（恒注册，对齐 CC getAllBaseTools 恒含，决策 #65）与 {@code mcp__*} 前缀工具对 LLM 可见。
 * builtin 分区（{@link ToolRegistry#getTools}）保持三成员过滤完整（对齐 CC getTools tools.ts:307）。
 */
@DisplayName("S04 SPECIAL_TOOLS 过滤收窄（schema 期仅滤 builtin 特例）")
class ToolRegistrySpecialToolsNarrowingTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public List<String> aliases() { return List.of(); }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isEnabled() { return true; }
        };
    }

    private static List<String> schemaNames(ArrayNode arr) {
        List<String> names = new ArrayList<>();
        arr.forEach(wrapper -> names.add(wrapper.path("function").path("name").asText()));
        return names;
    }

    @Test
    @DisplayName("toOpenAiToolsArray: resource 两工具 + mcp__ 前缀工具可见，StructuredOutput 被滤（CC tools.ts:307 + client.ts:2182-2198）")
    void toOpenAiToolsArray_resourceToolsVisible_mcpVisible_structuredOutputFiltered() {
        McpToolPool pool = new McpToolPool(null, null, null);
        ToolRegistry registry = ToolRegistry.from(List.of(
            new ListMcpResourcesTool(pool),
            new ReadMcpResourceTool(pool),
            stub(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME),
            stub("Normal"),
            stub("mcp__svr__list_mcp_resources")));

        List<String> names = schemaNames(registry.toOpenAiToolsArray());

        assertThat(names)
            .as("resource 两工具 + mcp__ 前缀工具对 LLM 可见（CC：resource 工具条件注册即入 LLM 池）")
            .contains("ListMcpResourcesTool", "ReadMcpResourceTool", "mcp__svr__list_mcp_resources", "Normal");
        assertThat(names)
            .as("schema 期唯一 builtin 特例 StructuredOutput 仍被滤")
            .doesNotContain(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }

    @Test
    @DisplayName("getTools(null): builtin 分区保持三成员 SPECIAL_TOOLS 过滤（对齐 CC getTools tools.ts:307）")
    void getTools_stillFiltersAllThreeSpecialTools() {
        McpToolPool pool = new McpToolPool(null, null, null);
        ToolRegistry registry = ToolRegistry.from(List.of(
            new ListMcpResourcesTool(pool),
            new ReadMcpResourceTool(pool),
            stub(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME),
            stub("Normal")));

        List<String> names = registry.getTools(null).stream().map(Tool::name).toList();

        assertThat(names)
            .as("builtin 分区过滤不回归：三成员（ListMcpResources/ReadMcpResource/StructuredOutput）全部剔除")
            .containsExactly("Normal");
    }

    @Test
    @DisplayName("skipSpecialToolsFilter=true: StructuredOutput 可见（hook agent 语义不回归，CC execAgentHook.ts:93-105）")
    void skipSpecialToolsFilter_true_structuredOutputVisible() {
        ToolRegistry registry = ToolRegistry.from(List.of(
            stub(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME)));
        registry.setSkipSpecialToolsFilter(true);

        List<String> names = schemaNames(registry.toOpenAiToolsArray());

        assertThat(names)
            .as("hook agent effectiveRegistry 跳过过滤 → StructuredOutput 进入 LLM schema")
            .containsExactly(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }
}

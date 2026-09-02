package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.impl.ListMcpResourcesTool;
import com.nexusai.application.agent.tool.impl.ReadMcpResourceTool;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolNameConstants SPECIAL_TOOLS 值名修正测试 · 对齐 CC tools.ts:300-307。
 *
 * <p>WHY：SPECIAL_TOOLS 是 toOpenAiToolsArray/getTools 的内部 dispatch 过滤集合——
 * LLM schema 不得暴露 ListMcpResources / ReadMcpResource / StructuredOutput。CC 的
 * specialTools 用 {@code ListMcpResourcesTool.name}（= 'ListMcpResourcesTool'）等<b>真名</b>
 * 构造集合；Java 端旧值 "ListMcpResources" / "ReadMcpResource"（缺 Tool 后缀）与 D2 改名的
 * 真工具名不匹配 → 过滤失效 → 内部工具泄漏进 LLM schema。本测试锁定值名 = CC 真名。
 */
class ToolNameConstantsSpecialToolsTest {

    @Test
    void listMcpResourcesToolName_hasToolSuffix() {
        // WHY: CC ListMcpResourcesTool/prompt.ts:1 LIST_MCP_RESOURCES_TOOL_NAME = 'ListMcpResourcesTool'
        assertEquals("ListMcpResourcesTool", ToolNameConstants.LIST_MCP_RESOURCES_TOOL_NAME);
    }

    @Test
    void readMcpResourceToolName_hasToolSuffix() {
        // WHY: CC ReadMcpResourceTool.ts:60 name: 'ReadMcpResourceTool'
        assertEquals("ReadMcpResourceTool", ToolNameConstants.READ_MCP_RESOURCE_TOOL_NAME);
    }

    @Test
    void syntheticOutputToolName_isStructuredOutput() {
        // WHY: CC SyntheticOutputTool.ts:20 SYNTHETIC_OUTPUT_TOOL_NAME = 'StructuredOutput'
        assertEquals("StructuredOutput", ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }

    @Test
    void specialTools_setMatchesCCToolNames() {
        // WHY: CC tools.ts:302-304 specialTools = new Set([ListMcpResourcesTool.name,
        //   ReadMcpResourceTool.name, SYNTHETIC_OUTPUT_TOOL_NAME])
        assertEquals(
            Set.of("ListMcpResourcesTool", "ReadMcpResourceTool", "StructuredOutput"),
            ToolNameConstants.SPECIAL_TOOLS);
    }

    @Test
    void realToolNamesAreContainedInSpecialTools() {
        // WHY: toOpenAiToolsArray/getTools 按 name() 查 SPECIAL_TOOLS —— 若真工具 name()
        //   不在集合中，SPECIAL_TOOLS 过滤失效，内部工具泄漏进 LLM schema（D3 修复目标）。
        //   变异点：值名缺 Tool 后缀 → 两个真工具 name() 不在集合 → 红。
        McpToolPool pool = new McpToolPool(new McpTransportFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        assertTrue(ToolNameConstants.SPECIAL_TOOLS.contains(new ListMcpResourcesTool(pool).name()),
            "ListMcpResourcesTool.name() 必须在 SPECIAL_TOOLS 中");
        assertTrue(ToolNameConstants.SPECIAL_TOOLS.contains(new ReadMcpResourceTool(pool).name()),
            "ReadMcpResourceTool.name() 必须在 SPECIAL_TOOLS 中");
    }
}

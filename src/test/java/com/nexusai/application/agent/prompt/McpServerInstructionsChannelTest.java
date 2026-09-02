package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.tool.McpClientRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RES-L2 · C8] MCP server instructions 通道测试.
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC {@code getMcpInstructions()} (prompts.ts:579-604)
 * 过滤 connected + instructions 非空的 MCP client，生成 {@code # MCP Server Instructions}
 * section 输出到 system prompt。通道接通前 Java 硬编码 instructions=null，该 section 恒返 null。
 * 本测试锁定：
 * <ul>
 *   <li>{@link McpClientRuntime} 含 instructions 字段（mcpClients map 值承载，
 *       [IMP-E1 DC-2] 自 McpServerInfo 迁移：CC ConnectedMCPServer.instructions types.ts:189）</li>
 *   <li>mcp_instructions compute: connected + instructions 非空 → section 含 instructions 内容（AC3）</li>
 *   <li>mcp_instructions compute: 全部 instructions 为 null → section 返 null（AC3 软降级）</li>
 * </ul>
 */
class McpServerInstructionsChannelTest {

    // ─── AC1: McpClientRuntime instructions 字段存在（[IMP-E1 DC-2] 承载载体）───

    @Test
    @DisplayName("AC1: McpClientRuntime 含 instructions 字段")
    void mcpClientRuntime_hasInstructionsField() {
        McpClientRuntime info = new McpClientRuntime("server1", "tool1", "Use this server for X");

        assertThat(info.instructions())
            .as("instructions 字段必须可读")
            .isEqualTo("Use this server for X");
        assertThat(info.serverName()).isEqualTo("server1");
        assertThat(info.toolName()).isEqualTo("tool1");
    }

    @Test
    @DisplayName("AC1: 2 参便捷构造器 instructions 默认 null")
    void mcpClientRuntime_2arg_instructionsNull() {
        McpClientRuntime info = new McpClientRuntime("server1", "tool1");

        assertThat(info.instructions())
            .as("2 参构造器 instructions 默认 null")
            .isNull();
    }

    // ─── AC3: mcp_instructions compute 输出 ───

    @Test
    @DisplayName("AC3: connected + instructions 非空 → section 含 instructions 内容")
    void mcpInstructionsCompute_withInstructions_outputsSection() throws Exception {
        SystemPromptAssemblyInput.McpClientInfo client =
            new SystemPromptAssemblyInput.McpClientInfo("myServer", "Server instructions here", true);
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(Set.of("tool1"), "model", List.of(), List.of(client), null, List.of(), null, null, false);

        CompletableFuture<String> result = SystemPromptSections.mcpInstructionsCompute(input);

        assertThat(result.get())
            .as("有 instructions 的 connected client 必须产出 # MCP Server Instructions section")
            .isNotNull()
            .contains("# MCP Server Instructions")
            .contains("## myServer")
            .contains("Server instructions here");
    }

    @Test
    @DisplayName("AC3: 全部 instructions 为 null → section 返回 null（软降级）")
    void mcpInstructionsCompute_noInstructions_returnsNull() throws Exception {
        SystemPromptAssemblyInput.McpClientInfo client =
            new SystemPromptAssemblyInput.McpClientInfo("myServer", null, true);
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(Set.of("tool1"), "model", List.of(), List.of(client), null, List.of(), null, null, false);

        CompletableFuture<String> result = SystemPromptSections.mcpInstructionsCompute(input);

        assertThat(result.get())
            .as("所有 client instructions 为 null → section 返回 null（CC :583-585 软降级）")
            .isNull();
    }

    @Test
    @DisplayName("AC3: disconnected client 即使有 instructions 也被过滤")
    void mcpInstructionsCompute_disconnected_filtered() throws Exception {
        SystemPromptAssemblyInput.McpClientInfo disconnected =
            new SystemPromptAssemblyInput.McpClientInfo("deadServer", "should not appear", false);
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(Set.of("tool1"), "model", List.of(), List.of(disconnected), null, List.of(), null, null, false);

        CompletableFuture<String> result = SystemPromptSections.mcpInstructionsCompute(input);

        assertThat(result.get())
            .as("disconnected client 的 instructions 必须被过滤（CC :579-582 type === 'connected'）")
            .isNull();
    }

    @Test
    @DisplayName("AC3: 多个 server 的 instructions 合并到同一 section")
    void mcpInstructionsCompute_multipleServers_merged() throws Exception {
        SystemPromptAssemblyInput.McpClientInfo c1 =
            new SystemPromptAssemblyInput.McpClientInfo("server1", "Instructions for server1", true);
        SystemPromptAssemblyInput.McpClientInfo c2 =
            new SystemPromptAssemblyInput.McpClientInfo("server2", "Instructions for server2", true);
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(Set.of("tool1"), "model", List.of(), List.of(c1, c2), null, List.of(), null, null, false);

        CompletableFuture<String> result = SystemPromptSections.mcpInstructionsCompute(input);

        assertThat(result.get())
            .as("多个 server 的 instructions 用 \\n\\n 分隔合并")
            .contains("## server1")
            .contains("Instructions for server1")
            .contains("## server2")
            .contains("Instructions for server2");
    }

    @Test
    @DisplayName("AC3: 空 mcpClients → section 返回 null")
    void mcpInstructionsCompute_emptyClients_returnsNull() throws Exception {
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(Set.of("tool1"), "model", List.of(), List.of(), null, List.of(), null, null, false);

        CompletableFuture<String> result = SystemPromptSections.mcpInstructionsCompute(input);

        assertThat(result.get())
            .as("无 MCP client → section 返回 null")
            .isNull();
    }
}

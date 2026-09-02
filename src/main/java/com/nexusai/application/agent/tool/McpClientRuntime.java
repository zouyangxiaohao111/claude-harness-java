package com.nexusai.application.agent.tool;

/**
 * MCP client 运行时快照 · 对齐 CC {@code ConnectedMCPServer}（types.ts:181-192）运行时字段子集。
 *
 * <p>承载于 {@link ToolUseContext#mcpClients()}（Map&lt;serverName, McpClientRuntime&gt;），
 * 供 system prompt 的 {@code mcp_instructions} section 与 compact 的
 * {@code mcp_instructions_delta} 附件消费。
 *
 * <p><b>[IMP-E1 DC-2] 迁移载体</b>：旧实现把 serverUrl/scope/instructions 三扩展字段塞进
 * {@link McpServerInfo}（CC mcpInfo 仅 {serverName,toolName}，client.ts:1780）。DC-2 后
 * McpServerInfo 收敛 2 字段；instructions 改由本记录承载（对齐 CC ConnectedMCPServer.instructions，
 * types.ts:189），serverUrl 走 McpToolPool 配置（getServerBaseUrl），scope 走配置派生（见
 * {@code McpServerScope}）。
 *
 * @param serverName   MCP server 名（对齐 ConnectedMCPServer.name）
 * @param toolName     server 首个工具的原始名（保留旧 mcpClients map 语义；消费方以 key 为准）
 * @param instructions server 使用说明（{@code null} = 无说明 / 未接通）·
 *                     CC original: {@code instructions?: string} (types.ts:189)
 */
public record McpClientRuntime(String serverName, String toolName, String instructions) {
    public McpClientRuntime {
        if (serverName == null || serverName.isBlank()) throw new IllegalArgumentException("serverName is blank");
        // toolName / instructions 可为 null（首工具缺失 / 无说明）
    }

    /**
     * 2 参便捷构造（无 instructions）· 供测试/降级沿用。
     */
    public McpClientRuntime(String serverName, String toolName) {
        this(serverName, toolName, null);
    }
}

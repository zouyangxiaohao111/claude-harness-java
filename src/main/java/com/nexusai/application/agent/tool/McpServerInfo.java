package com.nexusai.application.agent.tool;

/**
 * MCP 服务器信息 · 对齐 CC Tool.ts mcpInfo 字段.
 *
 * <p>CC 真源 mcpInfo 仅 <b>2 字段</b> {@code { serverName, toolName }}（client.ts:1780
 * {@code mcpInfo: { serverName: client.name, toolName: tool.name }}）。本 record 与 CC
 * 逐字对齐，不承载 serverUrl/scope/instructions —— 三者分别走各自 CC 通道：
 * <ul>
 *   <li>{@code serverUrl} → server 配置（CC getLoggingSafeMcpBaseUrl，metadata.ts:102-116）</li>
 *   <li>{@code scope} → server 配置 scope（CC getMcpServerScopeFromToolName 经
 *       getMcpConfigByName(serverName).scope，utils.ts:413-436）</li>
 *   <li>{@code instructions} → ConnectedMCPServer.instructions（types.ts:189，
 *       Java 端 McpServerService.getServerInstructions）</li>
 * </ul>
 *
 * <h2>IMP-E1（tool_v3 组 2-7）</h2>
 * <p>删除 TR-E1-DC-2 扩展字段 serverUrl/scope/instructions（CC mcpInfo 仅 2 字段），
 * 4 消费点迁移：PermissionDecisionLogger（serverUrl 走 McpToolPool 配置）/ LlmAgentLoop
 * mcp_instructions（instructions 走 McpServerService）/ McpServerScope（scope 走配置）/
 * AgentMcpTool（构造点）。保留 TR-E1-DC-3 构造器防御校验（serverName/toolName 非空白，
 * 防御性基础设施）。
 *
 * @param serverName 服务器名
 * @param toolName 工具在 MCP 服务器中的原始名
 */
public record McpServerInfo(String serverName, String toolName) {
    public McpServerInfo {
        if (serverName == null || serverName.isBlank()) throw new IllegalArgumentException("serverName is blank");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is blank");
    }
}

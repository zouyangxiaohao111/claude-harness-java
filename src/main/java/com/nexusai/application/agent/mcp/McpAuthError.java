package com.nexusai.application.agent.mcp;

/**
 * [Session H P2-5] MCP 认证错误 · 对齐 CC Open-ClaudeCode/src/services/mcp/client.ts:146-159
 * {@code McpAuthError}.
 *
 * <p><b>用途</b>: 指示 MCP 工具调用因认证问题失败（如 OAuth token 过期返回 401）。
 * 该异常应在工具执行层被捕获, 将 client 状态更新为 {@code needs-auth}
 * （CC toolExecution.ts:1601-1629 catch 分支）。
 *
 * <p>CC 原版 {@code extends Error} + {@code name = 'McpAuthError'} + {@code serverName} 字段;
 * Java 版 {@code extends RuntimeException} 携带 {@code serverName}（降级 appState 时
 * 按 server 名定位 mcp.clients 条目）。
 */
public class McpAuthError extends RuntimeException {

    private final String serverName;

    /**
     * @param serverName MCP 服务器名 (CC {@code MCPServerConnection.name})
     * @param message    错误消息 (CC: {@code MCP server "${name}" requires re-authorization (token expired)})
     */
    public McpAuthError(String serverName, String message) {
        super(message);
        this.serverName = serverName;
    }

    /** 触发 401 的 MCP 服务器名. */
    public String serverName() {
        return serverName;
    }
}

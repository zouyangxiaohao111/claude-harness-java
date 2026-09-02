package com.nexusai.application.agent.mcp;

/**
 * [Q-11-5 DIV-1] MCP 会话过期错误 · 对齐 CC Open-ClaudeCode/src/services/mcp/client.ts:161-170
 * {@code McpSessionExpiredError}。
 *
 * <p><b>用途</b>: 指示 MCP server 返回 HTTP 404 + JSON-RPC -32001（Session not found），
 * 会话 ID 已失效（区别于通用 HTTP 错误与限流）。该异常由 {@link HttpMcpTransport} 在响应
 * code=404 且 body 含 {@code "code":-32001} 时抛出，{@link McpToolPool#callTool} 捕获后
 * 清除连接缓存（对齐 CC clearServerCache client.ts:1648-1673），使下一次调用经
 * ensureConnectedClient 重建连接（新 session ID）。
 *
 * <p>CC 原版 {@code extends Error} + {@code name = 'McpSessionExpiredError'} + serverName 构造
 * （client.ts:165-170，message = {@code MCP server "${serverName}" session expired}）；
 * Java 版 {@code extends RuntimeException} 携带 {@code serverName}（定位清除目标 server）。
 */
public class McpSessionExpiredException extends RuntimeException {

    private final String serverName;

    /**
     * @param serverName MCP 服务器名（CC {@code MCPServerConnection.name}）
     * @param message    错误消息（对齐 CC {@code MCP server "${serverName}" session expired}，
     *                   附加 HTTP 404 + JSON-RPC -32001 判别依据便于排障）
     */
    public McpSessionExpiredException(String serverName, String message) {
        super(message);
        this.serverName = serverName;
    }

    /** 会话过期的 MCP 服务器名. */
    public String serverName() {
        return serverName;
    }
}

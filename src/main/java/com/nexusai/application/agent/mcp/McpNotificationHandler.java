package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * server→client 通知处理器（MCP {@code notifications} 系列方法）· 对齐 CC
 * {@code useManageMCPConnections.ts:619-751} 中 {@code client.setNotificationHandler(Schema, handler)}
 * 的 handler 语义。
 *
 * <p>CC 原名：{@code setNotificationHandler(Schema, handler)}（useManageMCPConnections.ts:619/:669/:707），
 * handler 闭包捕获 client.name。Java 端把「Schema（method 字符串）+ handler」拆为
 * {@link McpTransport#setNotificationHandler(String, McpNotificationHandler)} 两个入参。
 *
 * <p>CC 实际行为（自验 useManageMCPConnections.ts:618-751）：server 在
 * {@code notifications/{tools,prompts,resources}/list_changed} 时通知客户端刷新对应
 * fetch 缓存（fetchToolsForClient.cache.delete 等）。handler 入参为 notification params
 * （list_changed 无 params，恒空对象），server 名由注册时闭包捕获的 {@code serverName} 承载。
 */
@FunctionalInterface
public interface McpNotificationHandler {

    /**
     * 处理一条 server→client 通知。
     *
     * @param params JSON-RPC notification 的 params 节点（list_changed 恒为 {@code {} }）
     */
    void handle(JsonNode params);
}

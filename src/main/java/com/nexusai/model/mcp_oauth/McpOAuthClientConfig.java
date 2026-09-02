package com.nexusai.model.mcp_oauth;

/**
 * Domain entity: MCP OAuth 预配置 client（CC {@code mcpOAuthClientConfig[serverKey]}）。
 *
 * <p>对齐 CC auth.ts clientInformation() 二级回退：serverConfig.oauth.clientId 命中时，
 * client_secret 从 mcpOAuthClientConfig[serverKey] 读取（:1449-1462）。
 *
 * <p>DDD 分层：纯 POJO，持久化由
 * {@link com.nexusai.repository.mcp_oauth.entity.McpOAuthClientConfigRecord} 负责。
 */
public class McpOAuthClientConfig {
    private String serverKey;
    private String clientSecret;

    public String getServerKey() { return serverKey; }
    public void setServerKey(String serverKey) { this.serverKey = serverKey; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
}

package com.nexusai.repository.mcp_oauth.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.mcp_oauth.McpOAuthClientConfig;

/**
 * MyBatis-Flex 持久化记录：{@code mcp_oauth_client_config} 表行。
 *
 * <p>对齐 CC {@code mcpOAuthClientConfig[serverKey]}（auth.ts:1449-1462 clientInformation 二级回退）。
 */
@Table("mcp_oauth_client_config")
public class McpOAuthClientConfigRecord {
    @Id private String serverKey;
    private String clientSecret;
    private String createdAt;
    private String updatedAt;

    public McpOAuthClientConfig toDomain() {
        McpOAuthClientConfig c = new McpOAuthClientConfig();
        c.setServerKey(serverKey);
        c.setClientSecret(clientSecret);
        return c;
    }

    public static McpOAuthClientConfigRecord fromDomain(McpOAuthClientConfig c) {
        McpOAuthClientConfigRecord r = new McpOAuthClientConfigRecord();
        r.setServerKey(c.getServerKey());
        r.setClientSecret(c.getClientSecret());
        return r;
    }

    public String getServerKey() { return serverKey; }
    public void setServerKey(String serverKey) { this.serverKey = serverKey; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}

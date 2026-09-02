package com.nexusai.repository.mcp_oauth.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.mcp_oauth.McpOAuthToken;

/**
 * MyBatis-Flex 持久化记录：{@code mcp_oauth_tokens} 表行。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link com.nexusai.model.mcp_oauth.McpOAuthToken}（domain POJO）通过
 * {@link #toDomain()} 与 {@link #fromDomain(McpOAuthToken)} 互转。
 * 应用层（{@code McpOAuthTokenService}）应只持有 {@link McpOAuthToken}，不直接依赖 Record。
 */
@Table("mcp_oauth_tokens")
public class McpOAuthTokenRecord {
    @Id private String serverKey;
    private String serverName;
    private String serverUrl;
    private String accessToken;
    private String refreshToken;
    private Long expiresAt;
    private String scope;
    private String clientId;
    private String clientSecret;
    private String stepUpScope;
    private String discoveryState;
    private String createdAt;
    private String updatedAt;

    // ============== domain 互转 ==============

    public McpOAuthToken toDomain() {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey(serverKey);
        t.setServerName(serverName);
        t.setServerUrl(serverUrl);
        t.setAccessToken(accessToken);
        t.setRefreshToken(refreshToken);
        t.setExpiresAt(expiresAt);
        t.setScope(scope);
        t.setClientId(clientId);
        t.setClientSecret(clientSecret);
        t.setStepUpScope(stepUpScope);
        t.setDiscoveryState(discoveryState);
        return t;
    }

    public static McpOAuthTokenRecord fromDomain(McpOAuthToken t) {
        McpOAuthTokenRecord r = new McpOAuthTokenRecord();
        r.setServerKey(t.getServerKey());
        r.setServerName(t.getServerName());
        r.setServerUrl(t.getServerUrl());
        r.setAccessToken(t.getAccessToken());
        r.setRefreshToken(t.getRefreshToken());
        r.setExpiresAt(t.getExpiresAt());
        r.setScope(t.getScope());
        r.setClientId(t.getClientId());
        r.setClientSecret(t.getClientSecret());
        r.setStepUpScope(t.getStepUpScope());
        r.setDiscoveryState(t.getDiscoveryState());
        return r;
    }

    // ============== getters/setters ==============

    public String getServerKey() { return serverKey; }
    public void setServerKey(String serverKey) { this.serverKey = serverKey; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getStepUpScope() { return stepUpScope; }
    public void setStepUpScope(String stepUpScope) { this.stepUpScope = stepUpScope; }
    public String getDiscoveryState() { return discoveryState; }
    public void setDiscoveryState(String discoveryState) { this.discoveryState = discoveryState; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}

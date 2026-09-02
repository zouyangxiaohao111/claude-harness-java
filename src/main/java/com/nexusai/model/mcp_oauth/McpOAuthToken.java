package com.nexusai.model.mcp_oauth;

/**
 * Domain entity: MCP OAuth token 持久化记录。
 *
 * <p>对齐 CC {@code SecureStorageData.mcpOAuth[serverKey]}（auth.ts:1500+）单条目字段：
 * serverName/serverUrl/accessToken/refreshToken/expiresAt/scope/clientId/clientSecret/
 * stepUpScope/discoveryState。CC 存 keychain，Java 落 DB（Q-01=A 受控偏差，keychain→DB）。
 *
 * <p>DDD 分层：纯 POJO，无 {@code @Table} 注解，持久化由
 * {@link com.nexusai.repository.mcp_oauth.entity.McpOAuthTokenRecord} 负责。
 */
public class McpOAuthToken {
    private String serverKey;      // getServerKey() = sha256(稳定键序 JSON) 16hex + serverName|
    private String serverName;
    private String serverUrl;
    private String accessToken;
    private String refreshToken;
    private Long expiresAt;        // epoch millis
    private String scope;
    private String clientId;
    private String clientSecret;
    private String stepUpScope;    // redirectToAuthorization 持久化，step-up 复用
    private String discoveryState; // CC discoveryState {authorizationServerUrl, resourceMetadataUrl} JSON

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
}

package com.nexusai.model.connector;

/**
 * 连接器连接状态（{@code connector_credentials} 表行 · domain POJO）。
 *
 * <p>敏感列（{@code *_Encrypted}）只存 {@link com.nexusai.infra.util.CryptoUtil} 密文，
 * 读写必须经过 {@link com.nexusai.domain.connector.ConnectorCredentialService}，
 * ⛔ 不要把明文/密文暴露到 REST（读接口只回 {@code connected/account/expiresAt}）。
 *
 * <p>用 POJO 而非 record：MyBatis-Flex 通过 setter 映射，record 不可变形态不适用。
 */
public class ConnectorCredential {

    private String connectorKey;
    private String authType;
    /** unconnected | pending | connected | error | disabled */
    private String status;
    private String account;
    private String scope;
    private String tokenEndpoint;
    private String redirectUri;
    private String accessTokenEncrypted;
    private String refreshTokenEncrypted;
    private String clientId;
    /** dcr | catalog | manual · 决定 redirect_uri 变化时能否重新注册。 */
    private String clientSource;
    private String clientSecretEncrypted;
    private String extraEncrypted;
    private Long expiresAt;
    private String state;
    private Long stateExpiresAt;
    private String lastError;
    private String createdAt;
    private String updatedAt;

    public String getConnectorKey() { return connectorKey; }
    public void setConnectorKey(String connectorKey) { this.connectorKey = connectorKey; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getTokenEndpoint() { return tokenEndpoint; }
    public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
    public void setAccessTokenEncrypted(String v) { this.accessTokenEncrypted = v; }
    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public void setRefreshTokenEncrypted(String v) { this.refreshTokenEncrypted = v; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSource() { return clientSource; }
    public void setClientSource(String clientSource) { this.clientSource = clientSource; }
    public String getClientSecretEncrypted() { return clientSecretEncrypted; }
    public void setClientSecretEncrypted(String v) { this.clientSecretEncrypted = v; }
    public String getExtraEncrypted() { return extraEncrypted; }
    public void setExtraEncrypted(String extraEncrypted) { this.extraEncrypted = extraEncrypted; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getStateExpiresAt() { return stateExpiresAt; }
    public void setStateExpiresAt(Long stateExpiresAt) { this.stateExpiresAt = stateExpiresAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}

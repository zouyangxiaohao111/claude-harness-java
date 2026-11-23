package com.nexusai.repository.connector.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.connector.ConnectorCredential;

/**
 * MyBatis-Flex 持久化记录：{@code connector_credentials} 表行（V336）。
 *
 * <p>与 domain POJO {@link ConnectorCredential} 互转；应用层只持有 domain 对象。
 * 敏感列名为 {@code *_Encrypted}，值必须是 CryptoUtil 密文。
 */
@Table("connector_credentials")
public class ConnectorCredentialRecord {

    @Id private String connectorKey;
    private String authType;
    private String status;
    private String account;
    private String scope;
    private String tokenEndpoint;
    private String redirectUri;
    private String accessTokenEncrypted;
    private String refreshTokenEncrypted;
    private String clientId;
    private String clientSource;
    private String clientSecretEncrypted;
    private String extraEncrypted;
    private Long expiresAt;
    private String state;
    private Long stateExpiresAt;
    private String lastError;
    private String createdAt;
    private String updatedAt;

    public ConnectorCredential toDomain() {
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey(connectorKey);
        c.setAuthType(authType);
        c.setStatus(status);
        c.setAccount(account);
        c.setScope(scope);
        c.setTokenEndpoint(tokenEndpoint);
        c.setRedirectUri(redirectUri);
        c.setAccessTokenEncrypted(accessTokenEncrypted);
        c.setRefreshTokenEncrypted(refreshTokenEncrypted);
        c.setClientId(clientId);
        c.setClientSource(clientSource);
        c.setClientSecretEncrypted(clientSecretEncrypted);
        c.setExtraEncrypted(extraEncrypted);
        c.setExpiresAt(expiresAt);
        c.setState(state);
        c.setStateExpiresAt(stateExpiresAt);
        c.setLastError(lastError);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(updatedAt);
        return c;
    }

    public static ConnectorCredentialRecord fromDomain(ConnectorCredential c) {
        ConnectorCredentialRecord r = new ConnectorCredentialRecord();
        r.setConnectorKey(c.getConnectorKey());
        r.setAuthType(c.getAuthType());
        r.setStatus(c.getStatus());
        r.setAccount(c.getAccount());
        r.setScope(c.getScope());
        r.setTokenEndpoint(c.getTokenEndpoint());
        r.setRedirectUri(c.getRedirectUri());
        r.setAccessTokenEncrypted(c.getAccessTokenEncrypted());
        r.setRefreshTokenEncrypted(c.getRefreshTokenEncrypted());
        r.setClientId(c.getClientId());
        r.setClientSource(c.getClientSource());
        r.setClientSecretEncrypted(c.getClientSecretEncrypted());
        r.setExtraEncrypted(c.getExtraEncrypted());
        r.setExpiresAt(c.getExpiresAt());
        r.setState(c.getState());
        r.setStateExpiresAt(c.getStateExpiresAt());
        r.setLastError(c.getLastError());
        r.setCreatedAt(c.getCreatedAt());
        r.setUpdatedAt(c.getUpdatedAt());
        return r;
    }

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

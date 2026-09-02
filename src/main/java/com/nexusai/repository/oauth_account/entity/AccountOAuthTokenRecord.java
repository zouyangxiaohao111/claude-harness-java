package com.nexusai.repository.oauth_account.entity;

import com.mybatisflex.annotation.Table;
import com.nexusai.model.oauth_account.AccountOAuthToken;

/**
 * MyBatis-Flex 持久化记录：{@code oauth_account_tokens} 表行。
 *
 * <p>复合主键 {@code (provider, identity)}，与 V14 DDL {@code PRIMARY KEY (provider, identity)}
 * 一致。MyBatis-Flex 1.10.0 对复合主键的 selectOneById/deleteById 单 id 语义不适用，
 * 服务层（{@code AccountOAuthTokenService}）统一走 QueryWrapper 两列 eq 拼接
 * （先例：CommandService.selectOneByQuery / ProviderService.deleteByQuery），
 * 故本 Record 不标注单一 {@code @Id}。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link com.nexusai.model.oauth_account.AccountOAuthToken}（domain POJO）通过
 * {@link #toDomain()} 与 {@link #fromDomain(AccountOAuthToken)} 互转。
 * 应用层（{@code AccountOAuthTokenService}）应只持有 {@link AccountOAuthToken}，不直接依赖 Record。
 */
@Table("oauth_account_tokens")
public class AccountOAuthTokenRecord {
    private String provider;
    private String identity;
    private String accessToken;
    private String refreshToken;
    private Long expiresAt;
    private String scope;
    private String createdAt;
    private String updatedAt;

    // ============== domain 互转 ==============

    public AccountOAuthToken toDomain() {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider(provider);
        t.setIdentity(identity);
        t.setAccessToken(accessToken);
        t.setRefreshToken(refreshToken);
        t.setExpiresAt(expiresAt);
        t.setScope(scope);
        return t;
    }

    public static AccountOAuthTokenRecord fromDomain(AccountOAuthToken t) {
        AccountOAuthTokenRecord r = new AccountOAuthTokenRecord();
        r.setProvider(t.getProvider());
        r.setIdentity(t.getIdentity());
        r.setAccessToken(t.getAccessToken());
        r.setRefreshToken(t.getRefreshToken());
        r.setExpiresAt(t.getExpiresAt());
        r.setScope(t.getScope());
        return r;
    }

    // ============== getters/setters ==============

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}

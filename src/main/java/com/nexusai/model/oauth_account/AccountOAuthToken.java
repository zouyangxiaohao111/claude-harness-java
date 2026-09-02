package com.nexusai.model.oauth_account;

/**
 * Domain entity: 账号级 OAuth token 持久化记录（provider|identity 复合键）。
 *
 * <p>对齐 CC {@code SecureStorageData.claudeAiOauth}（Open-ClaudeCode/src/utils/auth.ts:1217-1228）
 * 单条目字段 accessToken/refreshToken/expiresAt/scopes/subscriptionType/rateLimitTier，
 * 由 CC 单 key {@code claudeAiOauth} 泛化为 provider|identity 复合键：
 * CC 只支持 claude.ai 单 provider 单账号（硬编码 getOauthConfig() 三套 Anthropic 端点，
 * 见 Open-ClaudeCode/src/constants/oauth.ts），Java 账号 OAuth 须按 provider 维度存储，
 * 支持 GitHub / GitLab 等多 provider 多账号并存。
 *
 * <p>CC 存 keychain（getSecureStorage），Java 落 DB（受控偏差，同 mcp_oauth_tokens Q-01=A）。
 *
 * <p>DDD 分层：纯 POJO，无 {@code @Table} 注解，持久化由
 * {@link com.nexusai.repository.oauth_account.entity.AccountOAuthTokenRecord} 负责。
 */
public class AccountOAuthToken {
    /** OAuth provider 标识（如 'github'），与 OAuthProviderConfig.provider() 契约一致（接线在 S3/S5）。 */
    private String provider;
    /** 账号唯一标识（GitHub login），与 provider 组成复合主键 provider|identity。 */
    private String identity;
    /** accessToken · CC original: accessToken (auth.ts:1218) */
    private String accessToken;
    /** refreshToken · CC original: refreshToken (auth.ts:1219)。可空——GitHub OAuth App 无 refresh_token（G-8）。 */
    private String refreshToken;
    /** expiresAt · CC original: expiresAt (auth.ts:1220)。epoch millis；null=不过期（对齐 CC isOAuthTokenExpired(null)→false，client.ts:345）。 */
    private Long expiresAt;
    /** scope · CC original: scopes (auth.ts:1221)。space-joined 持久化形式（CC scopes:string[]，同 McpOAuthToken.scope 惯例）。 */
    private String scope;

    /**
     * 复合键展示形式（仅用于日志/展示，不做 DB 键）。
     *
     * <p>真实主键是 provider + identity 两列复合，DB 层由 QueryWrapper 两列 eq 拼接，
     * 本助手不参与任何 DB 键计算。
     */
    public static String accountKey(String provider, String identity) {
        return provider + '|' + identity;
    }

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
}

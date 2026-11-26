package com.nexusai.model.provider;

/**
 * Domain entity: Provider 聚合根。
 *
 * <p>DDD 分层：纯 POJO，无 {@code @Table} 注解，持久化由
 * {@link com.nexusai.model.provider.persistence.ProviderRecord} 负责。
 */
public class Provider {
    private String id;
    private String name;
    private String type;
    private String baseUrl;
    private String apiKeyHash;
    private String apiKeyMasked;
    private String apiKeyEncrypted;
    private String extraHeaders;
    private Boolean enabled;
    private String createdAt;
    private String updatedAt;
    /** 凭据形态：{@code api_key} | {@code codex_oauth}（V296）。 */
    private String authMode;
    /** OAuth access token 密文（CryptoUtil），⛔ 明文不落库、不出 DTO。 */
    private String oauthAccessEncrypted;
    /** OAuth refresh token 密文（CryptoUtil）。 */
    private String oauthRefreshEncrypted;
    /** access token 过期时刻（epoch 毫秒；null = 无过期信息）。 */
    private Long oauthExpiresAt;
    /** OAuth 账号标识（chatgpt account id）。 */
    private String oauthAccountId;
    /** 登录流程在途的一次性 state（CSRF）。 */
    private String oauthState;
    /** [V338] Google 订阅的 Cloud Code project（null = 未取得）。 */
    private String oauthProject;
    /** [V299] 归属品牌 key（BuiltinProviderCatalog.BRANDS[].key；null = 自定义连接）。 */
    private String brandKey;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }
    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }
    public String getExtraHeaders() { return extraHeaders; }
    public void setExtraHeaders(String extraHeaders) { this.extraHeaders = extraHeaders; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public String getOauthAccessEncrypted() { return oauthAccessEncrypted; }
    public void setOauthAccessEncrypted(String oauthAccessEncrypted) { this.oauthAccessEncrypted = oauthAccessEncrypted; }
    public String getOauthRefreshEncrypted() { return oauthRefreshEncrypted; }
    public void setOauthRefreshEncrypted(String oauthRefreshEncrypted) { this.oauthRefreshEncrypted = oauthRefreshEncrypted; }
    public Long getOauthExpiresAt() { return oauthExpiresAt; }
    public void setOauthExpiresAt(Long oauthExpiresAt) { this.oauthExpiresAt = oauthExpiresAt; }
    public String getOauthAccountId() { return oauthAccountId; }
    public void setOauthAccountId(String oauthAccountId) { this.oauthAccountId = oauthAccountId; }
    public String getOauthState() { return oauthState; }
    public void setOauthState(String oauthState) { this.oauthState = oauthState; }
    public String getOauthProject() { return oauthProject; }
    public void setOauthProject(String oauthProject) { this.oauthProject = oauthProject; }
    public String getBrandKey() { return brandKey; }
    public void setBrandKey(String brandKey) { this.brandKey = brandKey; }
}

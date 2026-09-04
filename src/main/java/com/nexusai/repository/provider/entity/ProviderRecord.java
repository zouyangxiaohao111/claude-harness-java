package com.nexusai.repository.provider.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.provider.Provider;

/**
 * MyBatis-Flex 持久化记录：{@code providers} 表行。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link Provider}（domain POJO）通过
 * {@link #toDomain()} 与 {@link #fromDomain(Provider)} 互转。
 * 应用层（{@code ProviderService}）应只持有 {@link Provider}，不直接依赖 Record。
 */
@Table("providers")
public class ProviderRecord {
    @Id private String id;
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

    public Provider toDomain() {
        Provider p = new Provider();
        p.setId(id);
        p.setName(name);
        p.setType(type);
        p.setBaseUrl(baseUrl);
        p.setApiKeyHash(apiKeyHash);
        p.setApiKeyMasked(apiKeyMasked);
        p.setApiKeyEncrypted(apiKeyEncrypted);
        p.setExtraHeaders(extraHeaders);
        p.setEnabled(enabled);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(updatedAt);
        return p;
    }

    public static ProviderRecord fromDomain(Provider p) {
        ProviderRecord r = new ProviderRecord();
        r.setId(p.getId());
        r.setName(p.getName());
        r.setType(p.getType());
        r.setBaseUrl(p.getBaseUrl());
        r.setApiKeyHash(p.getApiKeyHash());
        r.setApiKeyMasked(p.getApiKeyMasked());
        r.setApiKeyEncrypted(p.getApiKeyEncrypted());
        r.setExtraHeaders(p.getExtraHeaders());
        r.setEnabled(p.getEnabled());
        r.setCreatedAt(p.getCreatedAt());
        r.setUpdatedAt(p.getUpdatedAt());
        return r;
    }

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
}

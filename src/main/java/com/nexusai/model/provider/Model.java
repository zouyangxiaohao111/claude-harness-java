package com.nexusai.model.provider;

/**
 * Domain entity: Model（Provider 聚合内的实体）。
 *
 * <p>DDD 分层：纯 POJO，无 {@code @Table} 注解，持久化由
 * {@link com.nexusai.model.provider.persistence.ModelRecord} 负责。
 */
public class Model {
    private String id;
    private String providerId;
    private String name;
    private String alias;
    private String tag;
    private String description;
    private String type;
    private Integer maxTokens;
    private Double temperature;
    private Double topP;
    /**
     * 模型级上下文窗口（tokens）· 对应 models.max_context_tokens 列（V24）。
     * <p>W2-1 打通：运行时 4 处窗口/预算源由 providers.max_context_tokens 改读本字段
     * （CC 等价 getContextWindowForModel 的 cap.max_input_tokens 语义）。
     */
    private Integer maxContextTokens;
    /**
     * 输入 tokens 价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.inputTokens}
     * (modelCost.ts:27-33)。可空 = 未配置 → ModelCostCalculator 回落内置默认。
     */
    private Double inputPricePeak;
    /**
     * 输入 tokens 价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.inputTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double inputPriceOffpeak;
    /**
     * 输出 tokens 价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.outputTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double outputPricePeak;
    /**
     * 输出 tokens 价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.outputTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double outputPriceOffpeak;
    /**
     * 缓存命中输入价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheReadTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double cacheReadPricePeak;
    /**
     * 缓存命中输入价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheReadTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double cacheReadPriceOffpeak;
    /**
     * 缓存写入输入价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheWriteTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double cacheWritePricePeak;
    /**
     * 缓存写入输入价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheWriteTokens}
     * (modelCost.ts:27-33)。可空 = 未配置。
     */
    private Double cacheWritePriceOffpeak;
    private String think;
    private Boolean enabled;
    private String createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(Integer maxContextTokens) { this.maxContextTokens = maxContextTokens; }
    public Double getInputPricePeak() { return inputPricePeak; }
    public void setInputPricePeak(Double inputPricePeak) { this.inputPricePeak = inputPricePeak; }
    public Double getInputPriceOffpeak() { return inputPriceOffpeak; }
    public void setInputPriceOffpeak(Double inputPriceOffpeak) { this.inputPriceOffpeak = inputPriceOffpeak; }
    public Double getOutputPricePeak() { return outputPricePeak; }
    public void setOutputPricePeak(Double outputPricePeak) { this.outputPricePeak = outputPricePeak; }
    public Double getOutputPriceOffpeak() { return outputPriceOffpeak; }
    public void setOutputPriceOffpeak(Double outputPriceOffpeak) { this.outputPriceOffpeak = outputPriceOffpeak; }
    public Double getCacheReadPricePeak() { return cacheReadPricePeak; }
    public void setCacheReadPricePeak(Double cacheReadPricePeak) { this.cacheReadPricePeak = cacheReadPricePeak; }
    public Double getCacheReadPriceOffpeak() { return cacheReadPriceOffpeak; }
    public void setCacheReadPriceOffpeak(Double cacheReadPriceOffpeak) { this.cacheReadPriceOffpeak = cacheReadPriceOffpeak; }
    public Double getCacheWritePricePeak() { return cacheWritePricePeak; }
    public void setCacheWritePricePeak(Double cacheWritePricePeak) { this.cacheWritePricePeak = cacheWritePricePeak; }
    public Double getCacheWritePriceOffpeak() { return cacheWritePriceOffpeak; }
    public void setCacheWritePriceOffpeak(Double cacheWritePriceOffpeak) { this.cacheWritePriceOffpeak = cacheWritePriceOffpeak; }
    public String getThink() { return think; }
    public void setThink(String think) { this.think = think; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

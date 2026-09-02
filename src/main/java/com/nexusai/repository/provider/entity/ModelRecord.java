package com.nexusai.repository.provider.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.provider.Model;

/**
 * MyBatis-Flex 持久化记录：{@code models} 表行。
 *
 * <p>DDD 严格分层：persistence 关注点，与 {@link Model}（domain）通过
 * {@link #toDomain()} 与 {@link #fromDomain(Model)} 互转。
 */
@Table("models")
public class ModelRecord {
    @Id private String id;
    private String providerId;
    private String name;
    private String alias;
    private String tag;
    private String description;
    private String type;
    private Integer maxTokens;
    private Double temperature;
    private Double topP;
    private String think;
    private Boolean enabled;
    private String createdAt;
    private Integer maxContextTokens;
    /**
     * 输入 tokens 价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.inputTokens}
     * (modelCost.ts:27-33) —— V47 列 input_price_peak。可空 = 未配置 → 运行时回落内置默认。
     */
    private Double inputPricePeak;
    /**
     * 输入 tokens 价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.inputTokens}
     * (modelCost.ts:27-33) —— V47 列 input_price_offpeak。可空 = 未配置。
     */
    private Double inputPriceOffpeak;
    /**
     * 输出 tokens 价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.outputTokens}
     * (modelCost.ts:27-33) —— V47 列 output_price_peak。可空 = 未配置。
     */
    private Double outputPricePeak;
    /**
     * 输出 tokens 价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.outputTokens}
     * (modelCost.ts:27-33) —— V47 列 output_price_offpeak。可空 = 未配置。
     */
    private Double outputPriceOffpeak;
    /**
     * 缓存命中输入价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheReadTokens}
     * (modelCost.ts:27-33) —— V47 列 cache_read_price_peak。可空 = 未配置。
     */
    private Double cacheReadPricePeak;
    /**
     * 缓存命中输入价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheReadTokens}
     * (modelCost.ts:27-33) —— V47 列 cache_read_price_offpeak。可空 = 未配置。
     */
    private Double cacheReadPriceOffpeak;
    /**
     * 缓存写入输入价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheWriteTokens}
     * (modelCost.ts:27-33) —— V47 列 cache_write_price_peak。可空 = 未配置。
     */
    private Double cacheWritePricePeak;
    /**
     * 缓存写入输入价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheWriteTokens}
     * (modelCost.ts:27-33) —— V47 列 cache_write_price_offpeak。可空 = 未配置。
     */
    private Double cacheWritePriceOffpeak;

    public Model toDomain() {
        Model m = new Model();
        m.setId(id);
        m.setProviderId(providerId);
        m.setName(name);
        m.setAlias(alias);
        m.setTag(tag);
        m.setDescription(description);
        m.setType(type);
        m.setMaxTokens(maxTokens);
        m.setTemperature(temperature);
        m.setTopP(topP);
        // W2-1: 模型级上下文窗口（models.max_context_tokens）映射进领域对象（此前死列零接线）
        m.setMaxContextTokens(maxContextTokens);
        // [V-TOK 实施] 价格 8 列 → 领域对象（与 toDomain 对称，ModelCostCalculator 读）
        m.setInputPricePeak(inputPricePeak);
        m.setInputPriceOffpeak(inputPriceOffpeak);
        m.setOutputPricePeak(outputPricePeak);
        m.setOutputPriceOffpeak(outputPriceOffpeak);
        m.setCacheReadPricePeak(cacheReadPricePeak);
        m.setCacheReadPriceOffpeak(cacheReadPriceOffpeak);
        m.setCacheWritePricePeak(cacheWritePricePeak);
        m.setCacheWritePriceOffpeak(cacheWritePriceOffpeak);
        m.setThink(think);
        m.setEnabled(enabled);
        m.setCreatedAt(createdAt);
        return m;
    }

    public static ModelRecord fromDomain(Model m) {
        ModelRecord r = new ModelRecord();
        r.setId(m.getId());
        r.setProviderId(m.getProviderId());
        r.setName(m.getName());
        r.setAlias(m.getAlias());
        r.setTag(m.getTag());
        r.setDescription(m.getDescription());
        r.setType(m.getType());
        r.setMaxTokens(m.getMaxTokens());
        r.setTemperature(m.getTemperature());
        r.setTopP(m.getTopP());
        // W2-1: 领域对象 → models.max_context_tokens 列（与 toDomain 对称）
        r.setMaxContextTokens(m.getMaxContextTokens());
        // [V-TOK 实施] 领域对象 → 价格 8 列（与 toDomain 对称）
        r.setInputPricePeak(m.getInputPricePeak());
        r.setInputPriceOffpeak(m.getInputPriceOffpeak());
        r.setOutputPricePeak(m.getOutputPricePeak());
        r.setOutputPriceOffpeak(m.getOutputPriceOffpeak());
        r.setCacheReadPricePeak(m.getCacheReadPricePeak());
        r.setCacheReadPriceOffpeak(m.getCacheReadPriceOffpeak());
        r.setCacheWritePricePeak(m.getCacheWritePricePeak());
        r.setCacheWritePriceOffpeak(m.getCacheWritePriceOffpeak());
        r.setThink(m.getThink());
        r.setEnabled(m.getEnabled());
        r.setCreatedAt(m.getCreatedAt());
        return r;
    }

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
    public String getThink() { return think; }
    public void setThink(String think) { this.think = think; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
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
}

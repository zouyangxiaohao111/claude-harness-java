package com.nexusai.model.provider.dto;

import java.math.BigDecimal;

/** 响应：Model 完整信息（与 Provider.models[] 中元素一致） */
public record ModelDto(
    String id,
    String name,
    String alias,
    ModelTag tag,
    String desc,
    ModelType type,
    Integer maxTokens,
    BigDecimal temperature,
    BigDecimal topP,
    String think,
    boolean enabled,
    /** W2-1: 模型级上下文窗口 tokens（models.max_context_tokens，可空 → 运行时回落默认） */
    Integer maxContextTokens,
    /** 输入 tokens 价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.inputTokens} (modelCost.ts:27-33) */
    BigDecimal inputPricePeak,
    /** 输入 tokens 价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.inputTokens} (modelCost.ts:27-33) */
    BigDecimal inputPriceOffpeak,
    /** 输出 tokens 价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.outputTokens} (modelCost.ts:27-33) */
    BigDecimal outputPricePeak,
    /** 输出 tokens 价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.outputTokens} (modelCost.ts:27-33) */
    BigDecimal outputPriceOffpeak,
    /** 缓存命中输入价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheReadTokens} (modelCost.ts:27-33) */
    BigDecimal cacheReadPricePeak,
    /** 缓存命中输入价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheReadTokens} (modelCost.ts:27-33) */
    BigDecimal cacheReadPriceOffpeak,
    /** 缓存写入输入价格（高峰档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheWriteTokens} (modelCost.ts:27-33) */
    BigDecimal cacheWritePricePeak,
    /** 缓存写入输入价格（空闲档，元/百万 tokens）· CC original: {@code ModelCosts.promptCacheWriteTokens} (modelCost.ts:27-33) */
    BigDecimal cacheWritePriceOffpeak
) {}

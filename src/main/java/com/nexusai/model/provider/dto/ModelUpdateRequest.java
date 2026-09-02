package com.nexusai.model.provider.dto;

import java.math.BigDecimal;

/** 更新 Model 请求体（PATCH 语义：null 字段不改） */
public record ModelUpdateRequest(
    String name,
    String alias,
    String desc,
    ModelType type,
    Integer maxTokens,
    BigDecimal temperature,
    BigDecimal topP,
    String think,
    Boolean enabled,
    /** W2-1: 模型级上下文窗口 tokens（PATCH 语义：null 不改） */
    Integer maxContextTokens,
    /** 输入 tokens 价格（高峰档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.inputTokens} (modelCost.ts:27-33) */
    BigDecimal inputPricePeak,
    /** 输入 tokens 价格（空闲档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.inputTokens} (modelCost.ts:27-33) */
    BigDecimal inputPriceOffpeak,
    /** 输出 tokens 价格（高峰档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.outputTokens} (modelCost.ts:27-33) */
    BigDecimal outputPricePeak,
    /** 输出 tokens 价格（空闲档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.outputTokens} (modelCost.ts:27-33) */
    BigDecimal outputPriceOffpeak,
    /** 缓存命中输入价格（高峰档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.promptCacheReadTokens} (modelCost.ts:27-33) */
    BigDecimal cacheReadPricePeak,
    /** 缓存命中输入价格（空闲档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.promptCacheReadTokens} (modelCost.ts:27-33) */
    BigDecimal cacheReadPriceOffpeak,
    /** 缓存写入输入价格（高峰档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.promptCacheWriteTokens} (modelCost.ts:27-33) */
    BigDecimal cacheWritePricePeak,
    /** 缓存写入输入价格（空闲档，元/百万 tokens；PATCH 语义：null 不改）· CC original: {@code ModelCosts.promptCacheWriteTokens} (modelCost.ts:27-33) */
    BigDecimal cacheWritePriceOffpeak
) {}

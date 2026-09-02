package com.nexusai.model.provider.dto;

import java.math.BigDecimal;

/** 创建 Model 请求体 */
public record ModelCreateRequest(
    String name,
    String alias,
    ModelTag tag,
    String desc,
    ModelType type,
    Integer maxTokens,
    BigDecimal temperature,
    BigDecimal topP,
    String think,
    Boolean enabled,
    /** W2-1: 模型级上下文窗口 tokens（null → create 默认 1_048_576 = 1M，ModelService 兜底） */
    Integer maxContextTokens,
    /** 输入 tokens 价格（高峰档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.inputTokens} (modelCost.ts:27-33) */
    BigDecimal inputPricePeak,
    /** 输入 tokens 价格（空闲档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.inputTokens} (modelCost.ts:27-33) */
    BigDecimal inputPriceOffpeak,
    /** 输出 tokens 价格（高峰档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.outputTokens} (modelCost.ts:27-33) */
    BigDecimal outputPricePeak,
    /** 输出 tokens 价格（空闲档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.outputTokens} (modelCost.ts:27-33) */
    BigDecimal outputPriceOffpeak,
    /** 缓存命中输入价格（高峰档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.promptCacheReadTokens} (modelCost.ts:27-33) */
    BigDecimal cacheReadPricePeak,
    /** 缓存命中输入价格（空闲档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.promptCacheReadTokens} (modelCost.ts:27-33) */
    BigDecimal cacheReadPriceOffpeak,
    /** 缓存写入输入价格（高峰档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.promptCacheWriteTokens} (modelCost.ts:27-33) */
    BigDecimal cacheWritePricePeak,
    /** 缓存写入输入价格（空闲档，元/百万 tokens；null → 存 NULL（运行时回落 ModelCostCalculator 通用默认档））· CC original: {@code ModelCosts.promptCacheWriteTokens} (modelCost.ts:27-33) */
    BigDecimal cacheWritePriceOffpeak
) {}

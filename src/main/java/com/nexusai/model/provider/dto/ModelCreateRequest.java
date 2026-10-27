package com.nexusai.model.provider.dto;

import java.math.BigDecimal;

/** 创建 Model 请求体 */
public record ModelCreateRequest(
    String name,
    String alias,
    /** [D5] tag 自由文本（写路径 {@code ModelTags.normalize} 校验：1-8 位大写字母/数字，非法 400） */
    String tag,
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
    BigDecimal cacheWritePriceOffpeak,
    /**
     * 模型级额外请求体（JSON 对象字符串）· models.extra_body（V104）。
     *
     * <p>请求构造时**逐键合并**进 body（只增/覆盖同名键，不替换整包）；空 / 非对象 → 无额外参数。
     * 与 {@code think} 互不影响。
     */
    String extraBody,
    /** 向量维度（仅 type=embedding 生效）· models.embedding_dimensions（V200）；null = 服务端默认 */
    Integer embeddingDimensions,
    /** 单次 embedding 请求文本条数上限 · models.embedding_batch_size（V200）；null/≤0 = 10 */
    Integer embeddingBatchSize,
    /** 重排默认返回条数（仅 type=rerank 生效）· models.rerank_top_n（V200）；null = 服务端默认 */
    Integer rerankTopN,
    /** 重排默认指令（仅 type=rerank 生效）· models.rerank_instruct（V200）；null/空 = 不发送 */
    String rerankInstruct
) {}

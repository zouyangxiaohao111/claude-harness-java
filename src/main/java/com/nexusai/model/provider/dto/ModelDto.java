package com.nexusai.model.provider.dto;

import java.math.BigDecimal;

/** 响应：Model 完整信息（与 Provider.models[] 中元素一致） */
public record ModelDto(
    String id,
    String name,
    String alias,
    /** [D5] tag 自由文本（读路径 {@code ModelTags.parse} 安全解析：脏值 → null 不抛） */
    String tag,
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
    String rerankInstruct,
    /**
     * 所属提供商 ID · {@code models.provider_id}。
     *
     * <p>⭐ 2026-11-11 用户裁定：模型选择项要显示成 {@code provider/modelName}，
     * 否则同名模型（实测 {@code deepseek-v4.1-flash} 有三个 provider 各一份）在下拉里
     * 完全分不清。此前 DTO 不暴露该列 ⇒ 前端无法分组。
     */
    String providerId,
    /**
     * 所属提供商显示名 · {@code providers.name}（如 {@code deepseek} / {@code oc_new}）。
     *
     * <p>取不到（provider 被删）时为 null；前端回退用 {@link #providerId}。
     */
    String providerName
) {}

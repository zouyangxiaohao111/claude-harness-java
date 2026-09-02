package com.nexusai.application.agent.compact;

import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * contextTokensUsed 协议分派计算器（对齐 CC context.ts calculateContextPercentages）·
 * 收口实时/重算两处公式为<b>单点</b>，消除漂移。
 *
 * <p><b>WHY 单点（对齐规则 5 · 确定性计算交代码，且同语义禁止双实现）</b>: 实时
 * {@code message.complete} 事件（ChatService:572-578）与 cache 落库后的重拉补算
 * （MessageService.applyContextSnapshotToLastAssistant）按<b>同一协议分派</b>计算
 * contextTokensUsed —— Anthropic 用 {@code input + cacheRead + cacheCreate}（Claude API usage
 * 三字段独立，CC utils/context.ts:131-133 calculateContextPercentages）；OpenAI/DeepSeek
 * （openai_compatible）<b>仅用 input</b>（prompt_tokens 已含 cache hit，加 cacheRead 会双计）。
 * 两处各自内联曾引入漂移：重算恒三字段和 → 主模型 DeepSeek 双计 cache，违背「与实时 100% 一致」。
 *
 * <p>本类职责：
 * <ul>
 *   <li>{@link #computeContextTokensUsed}——纯函数公式（anthropic 布尔分派，null cache → 0）。</li>
 *   <li>{@link #isAnthropic}——模型名 → provider.type 判定（与 ChatService.providerTypeForModel
 *       同源链：ModelNameResolver.resolve → provider.type == "anthropic"；异常 warn + false 回落）。</li>
 * </ul>
 */
public final class ContextUsageCalculator {

    private static final Logger log = LoggerFactory.getLogger(ContextUsageCalculator.class);

    private ContextUsageCalculator() {}

    /**
     * 按协议分派计算 contextTokensUsed · 对齐 CC utils/context.ts:131-133
     * {@code calculateContextPercentages}（current_usage = input + cache_read + cache_creation）。
     *
     * <p><b>WHY 分派</b>: Anthropic（claude-*）Claude API usage 三字段独立 → 三字段和；
     * OpenAI/DeepSeek（openai_compatible）prompt_tokens 已含 cache hit，再加 cacheRead 会<b>双计</b>
     * （且通常无独立 cacheRead 字段）→ 仅 input。null cache → 0 容错（无 cache 的请求 / V53 前旧行
     * 无 cache 列）。
     *
     * @param input       input tokens（必填，长整；DTO/usage 投影到 long）
     * @param cacheRead   cache_read_input_tokens（null → 0）
     * @param cacheCreate cache_creation_input_tokens（null → 0）
     * @param anthropic   协议判定：true=Anthropic 三字段和；false=OpenAI/DeepSeek 仅 input
     * @return contextTokensUsed（已用 token 数，≥ input）
     */
    public static long computeContextTokensUsed(long input, Long cacheRead, Long cacheCreate, boolean anthropic) {
        if (anthropic) {
            return input
                + (cacheRead != null ? cacheRead : 0L)
                + (cacheCreate != null ? cacheCreate : 0L);
        }
        return input;
    }

    /**
     * 模型名 → 是否 Anthropic provider · 判定链与 ChatService.providerTypeForModel 同源
     * （ModelNameResolver.resolve → provider.type == "anthropic"）。
     *
     * <p>模型不可判定（modelName 空 / modelMapper 或 providerMapper 为 null / 未命中）或
     * provider.type 非 {@code "anthropic"} → false（OpenAI/DeepSeek 语义，computeContextTokensUsed
     * 走仅 input）。任一环节异常 → warn + false 回落（fail-loud 不吞；重拉路径不因模型解析失败而崩，
     * 同 resolveContextWindowForModel 容错模式）。
     *
     * @param modelMapper    模型 mapper（null → false）
     * @param providerMapper 提供商 mapper（null → false）
     * @param modelName      模型全名（providerName/modelName）或裸名（null/blank → false）
     * @return provider.type == "anthropic"
     */
    public static boolean isAnthropic(ModelMapper modelMapper, ProviderMapper providerMapper, String modelName) {
        if (modelName == null || modelName.isBlank() || modelMapper == null || providerMapper == null) {
            return false;
        }
        try {
            ModelRecord model = ModelNameResolver.resolve(modelMapper, providerMapper, modelName);
            if (model == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[ContextUsageCalculator] 模型不可判定, 按非 Anthropic（input-only）: model={}",
                        modelName);
                }
                return false;
            }
            ProviderRecord provider = providerMapper.selectOneById(model.getProviderId());
            boolean anthropic = provider != null && "anthropic".equals(provider.getType());
            if (log.isDebugEnabled()) {
                log.debug("[ContextUsageCalculator] 模型→provider 判定: model={} providerId={} type={} anthropic={}",
                    modelName, model.getProviderId(), provider != null ? provider.getType() : null, anthropic);
            }
            return anthropic;
        } catch (Exception e) {
            log.warn("[ContextUsageCalculator] 模型→provider 判定失败, 按非 Anthropic（input-only）回落: "
                    + "model={} err={}",
                modelName, e.toString());
            return false;
        }
    }
}

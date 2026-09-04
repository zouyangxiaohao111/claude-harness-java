package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.tool.AgentUsage;
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
     * cache 命中率 · 协议分派单点（A 命中率口径修复 · deepseek 双计）。
     *
     * <p><b>WHY 分派</b>: Anthropic（Claude usage 三字段独立）命中率 = {@code read/(input+read+create)}
     * （对齐 CC forkedAgent.ts:647-654 / compact.ts:1220-1226 分母 = input + cache_read + cache_create）；
     * OpenAI/DeepSeek（openai_compatible）{@code prompt_tokens(input_tokens)} <b>已含 cache hit</b>
     * （input == H+M，DB 实锤）→ 分母再加 cacheRead/cacheCreate 会把命中率<b>恒算成真实一半</b>
     * （如 deepseek 真实 ~85% 显示 ~42%），故非 anthropic 命中率 = {@code read/input}。
     *
     * <p>边界容错：cacheRead ≤ 0 → 0（无 cache 命中 / null）；分母 ≤ 0 → 0（input=0 等非法场景），
     * 与 CC 分母 ≤ 0 → 0 一致。cacheRead/cacheCreate 为 null → 0（无 cache 的请求 / 旧行无 cache 列）。
     *
     * @param input       input tokens（必填，长整；DTO/usage 投影到 long）
     * @param cacheRead   cache_read_input_tokens（null → 0）
     * @param cacheCreate cache_creation_input_tokens（null → 0）
     * @param anthropic   协议判定：true=Anthropic 三字段分母（read/(input+read+create)）；
     *                    false=OpenAI/DeepSeek 仅 input 分母（read/input）
     * @return cache 命中率（0~1；无命中/分母 ≤ 0 → 0）
     */
    public static double computeCacheHitRate(long input, Long cacheRead, Long cacheCreate, boolean anthropic) {
        long read = cacheRead != null ? cacheRead : 0L;
        if (read <= 0) {
            return 0d;
        }
        long denom = anthropic
            ? input + read + (cacheCreate != null ? cacheCreate : 0L)
            : input;
        return denom > 0 ? (double) read / denom : 0d;
    }

    /**
     * 上下文快照（window/used/percentLeft 三元组）· message.complete / message.usage 装配单点。
     *
     * <p><b>WHY 单点（对齐规则 5 · 同语义禁止双实现）</b>: 实时 {@code message.complete}
     * （ChatService.publishCompleteEvent）与本批次新增逐消息 {@code message.usage}
     * （LlmAgentLoop.publishMessageUsage）都要按「模型窗口回落 + 协议分派 used + 余量百分比
     * clamp」三步装配 —— 两处各算会重演本类类 javadoc 记载的 ChatService/MessageService
     * 公式漂移。本 record 收口三步：{@code contextWindow}（模型 max_context_tokens 回落 1M，
     * 同 ChatService:571 / MessageService.resolveContextWindowForModel 口径）、
     * {@code contextTokensUsed}（computeContextTokensUsed 协议分派）、{@code percentLeft}
     * （clamp ≥ 0）。
     *
     * @param contextWindow     窗口权威值（tokens；模型 max_context_tokens，未命中回落 1M）
     * @param contextTokensUsed 当前上下文用量（tokens；协议分派：Anthropic 三字段和 /
     *                          OpenAI/DeepSeek 仅 input）
     * @param percentLeft       余量百分比 0-100（clamp ≥0）；usage null → null（NON_NULL 省略）
     */
    public record Snapshot(long contextWindow, long contextTokensUsed, Integer percentLeft) {}

    /**
     * 计算上下文快照 · 对齐 CC context.ts:118-144（window/current_usage/percentLeft）。
     *
     * <p>窗口 = 模型 {@code max_context_tokens}（ModelNameResolver.resolve → ModelRecord），
     * 未命中/未配置/异常 → 回落 1_048_576（与实时 complete 事件同值，非 CompactConstants
     * CONTEXT_1M_WINDOW=1_000_000）；used = {@link #computeContextTokensUsed} 协议分派
     * （Anthropic input+cacheRead+cacheCreate；OpenAI/DeepSeek 仅 input——prompt_tokens 已含
     * cache hit，加 cacheRead 双计）；percentLeft = {@code round((1 - used/window)*100)} clamp 0。
     * usage null（无 usage 上报）→ used 0 + percentLeft null（NON_NULL 省略，与 complete 事件
     * 既有行为一致）。
     *
     * @param modelMapper    模型 mapper（null 容错：窗口回落 1M / isAnthropic false）
     * @param providerMapper 提供商 mapper（null 容错）
     * @param modelName      模型全名（providerName/modelName）或裸名（null/blank → 回落）
     * @param usage          provider 解析的 usage（null → used=0、percentLeft=null）
     * @return 上下文快照三元组
     */
    public static Snapshot snapshot(ModelMapper modelMapper, ProviderMapper providerMapper,
                                    String modelName, AgentUsage usage) {
        long contextWindow = resolveContextWindowForModel(modelMapper, providerMapper, modelName);
        boolean anthropic = isAnthropic(modelMapper, providerMapper, modelName);
        long contextTokensUsed = usage != null
            ? computeContextTokensUsed(usage.inputTokens(),
                usage.cacheReadInputTokens(), usage.cacheCreationInputTokens(), anthropic)
            : 0L;
        Integer percentLeft = usage != null
            ? Math.max(0, (int) Math.round((1 - (double) contextTokensUsed / contextWindow) * 100))
            : null;
        if (log.isDebugEnabled()) {
            log.debug("[ContextUsageCalculator] 上下文快照: model={} window={} used={} percentLeft={} anthropic={}",
                modelName, contextWindow, contextTokensUsed, percentLeft, anthropic);
        }
        return new Snapshot(contextWindow, contextTokensUsed, percentLeft);
    }

    /**
     * 解析模型上下文窗口 · 对齐 ChatService.publishCompleteEvent 实时路径（models.max_context_tokens，
     * 回落 1M）+ MessageService.resolveContextWindowForModel 同源链（ModelNameResolver.resolve →
     * ModelRecord.max_context_tokens；未命中/未配置/异常 → 回落 1_048_576）。
     *
     * @param modelMapper    模型 mapper（null → 回落 1M）
     * @param providerMapper 提供商 mapper（null → 回落 1M）
     * @param modelName      模型名（null/blank → 回落 1M）
     * @return 模型上下文窗口 token 数（> 0，恒正）
     */
    private static long resolveContextWindowForModel(ModelMapper modelMapper, ProviderMapper providerMapper,
                                                     String modelName) {
        if (modelName == null || modelName.isBlank() || modelMapper == null || providerMapper == null) {
            return 1_048_576L;
        }
        try {
            ModelRecord model = ModelNameResolver.resolve(modelMapper, providerMapper, modelName);
            if (model != null && model.getMaxContextTokens() != null && model.getMaxContextTokens() > 0) {
                return model.getMaxContextTokens();
            }
        } catch (Exception e) {
            log.warn("[ContextUsageCalculator] 模型窗口解析失败, 回落 1M: model={} err={}", modelName, e.toString());
        }
        return 1_048_576L;
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

package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 缓存命中率（cache hit rate）唯一计算口径 · 纯静态工具，⛔ 不得在别处重算。
 *
 * <h2>WHY 单点（规则九：不落这条会静默错在哪）</h2>
 * 「缓存命中率」在三个出口各有一份消费方：{@code message.usage}（单条 assistant 消息 ·
 * {@code LlmAgentLoop.publishMessageUsage}）、{@code message.complete}（会话累计 ·
 * {@code ChatService.publishCompleteEvent}）、{@code GET /api/v1/stats} 与
 * {@code GET /api/v1/sessions/{id}/usage}（读侧聚合 · {@code StatsService}）。
 * 若各处自行写公式，最典型的分叉是<b>忘记按协议分派</b>：
 * <ul>
 *   <li><b>Anthropic</b>：{@code input_tokens} <b>不含</b> cache —— prompt 总量 =
 *       {@code input + cacheRead + cacheCreation}（对齐
 *       {@link ContextUsageCalculator#computeContextTokensUsed} 的 current_usage 口径）；</li>
 *   <li><b>非 Anthropic（deepseek/openai）</b>：{@code input_tokens} <b>已含</b> cache hit
 *       （DB 实锤 input == H+M）—— 分母只能是 {@code input}，再加上 cacheRead 就是<b>重复计入</b>，
 *       命中率会系统性虚高（实测 deepseek 会算出 &gt;100% 的「命中率」）。</li>
 * </ul>
 * 另一类是<b>除零</b>：prompt 总量为 0（无 usage / 空桶 / 非 token 调用）时，
 * {@code 0.0/0.0 == NaN}、{@code x/0.0 == Infinity}。NaN/Infinity 一旦进 JSON
 * 会破坏前端数值渲染（{@code NaN} 在前端是「不显示」甚至抛错）。本类统一<b>返回 null</b>
 * （配合 {@code @JsonInclude(NON_NULL)} → 键省略 = 前端按「不显示」处理）。
 *
 * <h2>语义与单位</h2>
 * <ul>
 *   <li>返回值 = <b>0-1 比率</b>（0.75 = 75%），⛔ 不是 0-100 百分数；</li>
 *   <li>可空：{@code null} = 无数据 / 分母为 0（⛔ 不得输出 NaN / Infinity）；</li>
 *   <li>多模型合并必须<b>先合并分子分母再相除</b>（先算各自比率再平均是错的 ——
 *       小模型会与主模型等权），故暴露 {@link Ratio} 供调用方按模型桶累加后一次求值。</li>
 * </ul>
 *
 * <p><b>anthropic 判定</b>复用 {@link ContextUsageCalculator#isAnthropic}（模型 → provider.type
 * 同源链）；mapper 不可得（非 Spring 单测 / 未注入）→ 非 anthropic 语义（input 含 cache），
 * 与 {@code ModelCostCalculator} 的计费分派默认一致。
 */
public final class CacheHitRate {

    private static final Logger log = LoggerFactory.getLogger(CacheHitRate.class);

    private CacheHitRate() {}

    /**
     * 命中率的分子/分母对（未除）· 多模型桶合并的唯一正确中间载体。
     *
     * <p>{@code promptTokens} 已按协议分派（anthropic = input+cacheRead+cacheCreation；
     * 非 anthropic = input）；{@code cacheReadTokens} = 命中量（分子）。
     */
    public record Ratio(long cacheReadTokens, long promptTokens) {
        /** 本对的比率（分母 0 → null）。 */
        public Double rate() {
            return CacheHitRate.rate(cacheReadTokens, promptTokens);
        }
    }

    /**
     * 单模型<b>单次</b> usage 的命中率（{@code message.usage} 出口）。
     *
     * @param model          生效模型名（协议分派判定用；null → 非 anthropic 语义）
     * @param usage          本次 API usage（null → null）
     * @param modelMapper    模型 mapper（null 容错 → 非 anthropic）
     * @param providerMapper 提供商 mapper（null 容错 → 非 anthropic）
     * @return 0-1 比率；usage 缺失 / 分母 0 → null（⛔ 不输出 NaN/Infinity）
     */
    public static Double of(String model, AgentUsage usage, ModelMapper modelMapper,
                            ProviderMapper providerMapper) {
        if (usage == null) {
            return null;
        }
        return ratio(model, usage.inputTokens(), nz(usage.cacheReadInputTokens()),
            nz(usage.cacheCreationInputTokens()), modelMapper, providerMapper).rate();
    }

    /**
     * <b>按模型桶汇总</b>的会话累计命中率（{@code message.complete} 出口）。
     *
     * <p>逐桶用<b>该桶自己的模型名</b>判定 anthropic（同一会话可混跑 anthropic 与 deepseek），
     * 分子分母分别加总后一次相除 —— 见 {@link Ratio} 的 WHY。
     *
     * @param buckets        模型桶（键 = 模型名，值 = {@code CostTracker.ModelUsage}；null/空 → null）
     * @param modelMapper    模型 mapper（null 容错 → 非 anthropic）
     * @param providerMapper 提供商 mapper（null 容错 → 非 anthropic）
     * @return 0-1 比率；无桶 / 分母合计 0 → null
     */
    public static Double ofModels(Map<String, CostTracker.ModelUsage> buckets, ModelMapper modelMapper,
                                  ProviderMapper providerMapper) {
        if (buckets == null || buckets.isEmpty()) {
            return null;
        }
        long cacheRead = 0L;
        long prompt = 0L;
        for (Map.Entry<String, CostTracker.ModelUsage> e : buckets.entrySet()) {
            CostTracker.ModelUsage u = e.getValue();
            if (u == null) {
                continue;
            }
            Ratio r = ratio(e.getKey(), u.inputTokens(), u.cacheReadInputTokens(),
                u.cacheCreationInputTokens(), modelMapper, providerMapper);
            cacheRead += r.cacheReadTokens();
            prompt += r.promptTokens();
        }
        return rate(cacheRead, prompt);
    }

    /**
     * 聚合数值口径（读侧 SQL 聚合行 / 会话视图逐模型行直接消费）。
     *
     * <p>promptTotal 按协议分派：anthropic = {@code inputTokens + cacheReadTokens +
     * cacheCreationTokens}；非 anthropic = {@code inputTokens}（input 已含 cache hit）。
     *
     * @param model                模型名（协议分派判定用）
     * @param inputTokens          输入 token（非负）
     * @param cacheReadTokens      缓存读 token（非负；null 调用方按 0 传）
     * @param cacheCreationTokens  缓存写 token（非负；null 调用方按 0 传）
     * @return 未除的 {@link Ratio}（分母已按协议分派）
     */
    public static Ratio ratio(String model, long inputTokens, long cacheReadTokens,
                              long cacheCreationTokens, ModelMapper modelMapper,
                              ProviderMapper providerMapper) {
        boolean anthropic = ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, model);
        long input = Math.max(0L, inputTokens);
        long cacheRead = Math.max(0L, cacheReadTokens);
        long cacheCreation = Math.max(0L, cacheCreationTokens);
        long promptTotal = anthropic ? input + cacheRead + cacheCreation : input;
        return new Ratio(cacheRead, promptTotal);
    }

    /**
     * 命中率 = 命中量 / prompt 总量（已分派后的分母）。
     *
     * @param cacheReadTokens 命中 token（分子）
     * @param promptTokens    prompt 总量（分母，已按协议分派）
     * @return 0-1 比率；分母 ≤ 0 → {@code null}（⛔ 不输出 NaN/Infinity）
     */
    public static Double rate(long cacheReadTokens, long promptTokens) {
        if (promptTokens <= 0L) {
            return null;
        }
        double r = (double) cacheReadTokens / (double) promptTokens;
        if (!Double.isFinite(r)) {
            log.warn("[CacheHitRate] 命中率非有限值（异常输入），回落 null: cacheRead={} promptTotal={}",
                cacheReadTokens, promptTokens);
            return null;
        }
        return r;
    }

    /** Long/nullable → long（null → 0）。 */
    private static long nz(Long v) {
        return v != null ? v : 0L;
    }
}

package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 模型计费纯函数 · 对齐 CC {@code modelCost.ts}（tokensToUSDCost / MODEL_COSTS）。
 *
 * <p>职责：按模型 + 时段（空闲/高峰）把 token 用量折算为人民币元。Java 值用元
 * （用户拍板：字段名对齐 CC、值用人民币元，不换算 USD）。
 *
 * <p>价格源两级（plan §4）：
 * <ol>
 *   <li><b>运行时 DB 价优先</b>：models 表 8 价格列（V47，经 {@link ModelMapper} 查询）——
 *       锚定 {@code input_price_peak} 非 null 判定「该模型已配置价格」，单列 null 回落内置通用默认档。</li>
 *   <li><b>内置通用默认档</b>：{@link #DEFAULT_UNKNOWN_MODEL_TIER}（非模型特指 · 3/9 元档 · 1M/384K）·
 *       DB 未配置/未知模型回落它。</li>
 * </ol>
 *
 * <p><b>双档（空闲/高峰）</b>：空闲 = 高峰 × 50%（plan §二 口径）。{@link #isPeakHour()} 判定：
 * Asia/Shanghai，工作日 09:00-12:00 或 14:00-18:00（半开区间）→ peak；周末全天空闲。
 *
 * <p><b>计费公式</b>（对齐 CC {@code tokensToUSDCost} modelCost.ts:131-142）：
 * {@code (input/1e6)*inputPk + (output/1e6)*outputPk + (cacheRead/1e6)*cacheReadPk
 *  + (cacheCreation/1e6)*cacheWritePk}；webSearchRequests 不计（DeepSeek 无 web search 计费，×0）。
 *
 * <p>{@code @Autowired(required = false)} 双点注入（仿 tokenBudgetChecker）：非 Spring 单测
 * new 时 modelMapper/providerMapper 为 null → 回落内置通用默认档，不 NPE。
 */
@Component
public class ModelCostCalculator {

    /** 单模型双档价格（元/百万 tokens）+ 窗口/输出上限。 */
    public record PriceTier(
        double inputPricePeak,
        double inputPriceOffpeak,
        double outputPricePeak,
        double outputPriceOffpeak,
        double cacheReadPricePeak,
        double cacheReadPriceOffpeak,
        double cacheWritePricePeak,
        double cacheWritePriceOffpeak,
        long contextWindow,
        long maxOutputTokens
    ) {}

    /** 内置通用默认档（非模型特指 · 3/9 元档 · 1M 窗口 / 384K 输出上限）· DB 未配置/未知模型回落它。 */
    public static final PriceTier DEFAULT_UNKNOWN_MODEL_TIER =
        new PriceTier(3.0, 1.5, 9.0, 4.5, 0.10, 0.05, 3.0, 1.5, 1_048_576L, 384_000L);

    @Autowired(required = false) private ModelMapper modelMapper;
    @Autowired(required = false) private ProviderMapper providerMapper;

    /**
     * 按 usage 折算人民币元 · 对齐 CC {@code tokensToUSDCost} (modelCost.ts:131-142)。
     *
     * @param model  生效模型名（DB 价 / 内置默认解析用）
     * @param usage  provider 上报的 usage（null → 0.0）
     * @param isPeak true=高峰档 / false=空闲档
     */
    public double calculateCostYuan(String model, AgentUsage usage, boolean isPeak) {
        if (usage == null) return 0.0;
        long cacheRead = usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L;
        long cacheCreation = usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0L;
        return calculateCostYuan(model, usage.inputTokens(), usage.outputTokens(), cacheRead, cacheCreation, isPeak);
    }

    /**
     * T3 兜底重载 · 文本回合 usage 缺失时用估算 token 同口径计价（input/cache 0，output 估算值）。
     */
    public double calculateCostYuan(String model, long inputTokens, long outputTokens,
                                    long cacheRead, long cacheCreation, boolean isPeak) {
        PriceTier tier = resolveTier(model);
        double inputPrice = isPeak ? tier.inputPricePeak() : tier.inputPriceOffpeak();
        double outputPrice = isPeak ? tier.outputPricePeak() : tier.outputPriceOffpeak();
        double cacheReadPrice = isPeak ? tier.cacheReadPricePeak() : tier.cacheReadPriceOffpeak();
        double cacheWritePrice = isPeak ? tier.cacheWritePricePeak() : tier.cacheWritePriceOffpeak();
        return (inputTokens / 1e6) * inputPrice
            + (outputTokens / 1e6) * outputPrice
            + (cacheRead / 1e6) * cacheReadPrice
            + (cacheCreation / 1e6) * cacheWritePrice;
    }

    /**
     * 解析生效价格档：DB 价优先（models 表已配置）→ 内置通用默认档兜底。
     * 模型名规范化走 {@link ModelNameResolver#resolve}（对齐 ChatService findEnabledModelByName）。
     */
    public PriceTier resolveTier(String model) {
        PriceTier builtin = DEFAULT_UNKNOWN_MODEL_TIER;
        if (modelMapper != null && model != null) {
            ModelRecord record = ModelNameResolver.resolve(modelMapper, providerMapper, model);
            // 锚定 input_price_peak：该模型已在 models 表显式配置价格 → DB 档优先（单列 null 回落内置通用默认档）
            if (record != null && record.getInputPricePeak() != null) {
                return new PriceTier(
                    record.getInputPricePeak(),
                    orD(record.getInputPriceOffpeak(), builtin.inputPriceOffpeak()),
                    orD(record.getOutputPricePeak(), builtin.outputPricePeak()),
                    orD(record.getOutputPriceOffpeak(), builtin.outputPriceOffpeak()),
                    orD(record.getCacheReadPricePeak(), builtin.cacheReadPricePeak()),
                    orD(record.getCacheReadPriceOffpeak(), builtin.cacheReadPriceOffpeak()),
                    orD(record.getCacheWritePricePeak(), builtin.cacheWritePricePeak()),
                    orD(record.getCacheWritePriceOffpeak(), builtin.cacheWritePriceOffpeak()),
                    record.getMaxContextTokens() != null ? record.getMaxContextTokens() : builtin.contextWindow(),
                    builtin.maxOutputTokens());
            }
        }
        return builtin;
    }

    private static double orD(Double v, double def) { return v != null ? v : def; }

    /**
     * 当前是否高峰档 · Asia/Shanghai 工作日 09:00-12:00 或 14:00-18:00（半开区间）→ peak；
     * 周末全天 / 其余 → off-peak（plan §二 口径，验收 6）。
     */
    public boolean isPeakHour() {
        return isPeakHour(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /** 可测重载：注入时刻判定时段（边界 9:00/12:00/14:00/18:00 半开）。 */
    public boolean isPeakHour(ZonedDateTime at) {
        DayOfWeek dow = at.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false; // 周末全天空闲
        }
        LocalTime t = at.toLocalTime();
        boolean morning = !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(12, 0));
        boolean afternoon = !t.isBefore(LocalTime.of(14, 0)) && t.isBefore(LocalTime.of(18, 0));
        return morning || afternoon;
    }

    /** modelUsage 桶的 contextWindow（未知模型回退通用默认档 1M）。 */
    public int contextWindowFor(String model) {
        return (int) resolveTier(model).contextWindow();
    }

    /** modelUsage 桶的 maxOutputTokens（未知模型回退通用默认档 384K）。 */
    public long maxOutputFor(String model) {
        return resolveTier(model).maxOutputTokens();
    }
}

package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.compact.ContextUsageCalculator;
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
 * <p><b>V101 费用分项（{@link CostBreakdown}）</b>：{@link #price} 是本类<b>唯一公式点</b>——
 * {@link #calculateCostYuan} 已改为「委托 {@code price(...).totalYuan()}」，⛔ 不可另写一套总额公式
 * （两套公式必然漂）。分项写入 {@code llm_usage_calls} 的 {@code cost_*} 五列，供读侧回答
 * 「缓存省了多少钱」：反事实项 {@link CostBreakdown#noCacheInputYuan()} <b>不计入</b>总额。
 *
 * <p>{@code @Autowired(required = false)} 双点注入（仿 tokenBudgetChecker）：非 Spring 单测
 * new 时 modelMapper/providerMapper 为 null → 回落内置通用默认档，不 NPE。
 */
@Component
public class ModelCostCalculator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelCostCalculator.class);

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

    /**
     * 单次调用的费用<b>分项</b>（元）· V101 明细表 {@code llm_usage_calls.cost_*} 五列 + 总额的载体。
     *
     * <p><b>不变式（可断言）</b>：{@link #totalYuan()} == {@link #inputYuan()} + {@link #outputYuan()}
     * + {@link #cacheReadYuan()} + {@link #cacheWriteYuan()}；且 {@code totalYuan()} 与既有
     * {@link #calculateCostYuan} 同参同值（回归守护：后者已委托 {@link #price}）。
     *
     * <p><b>{@link #noCacheInputYuan()} 是反事实诊断项，不计入总额</b>：该轮若一次 cache 都没有
     * （命中/写入都按 input 全价）input 侧要花多少。读侧「缓存省了多少钱」=
     * {@code noCacheInputYuan − (inputYuan + cacheReadYuan + cacheWriteYuan)}：
     * anthropic = 命中×(全价−命中价) + 写入×(全价−写入价)；非 anthropic（input 已含命中）=
     * 命中×(全价−命中价)。⛔ 本类不摊分、不伪造分项。
     *
     * @param inputYuan        输入侧实付（anthropic = 全部 input 全价；非 anthropic = 扣命中后的 miss 全价）
     * @param outputYuan       输出侧实付（两分支同式）
     * @param cacheReadYuan    cache 命中（读）实付
     * @param cacheWriteYuan   cache 写入实付（仅 anthropic；非 anthropic = 0.0）
     * @param totalYuan        总额 = 上述四项之和（== {@link #calculateCostYuan} 同参结果）
     * @param noCacheInputYuan 反事实项：无任何 cache 时 input 侧的全价（不计入 {@code totalYuan}）
     */
    public record CostBreakdown(double inputYuan, double outputYuan, double cacheReadYuan,
                                double cacheWriteYuan, double totalYuan, double noCacheInputYuan) {}

    /** 全零分项（usage 缺失时用；record 不可变，可安全共享）。 */
    private static final CostBreakdown ZERO_BREAKDOWN = new CostBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

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
        // V101：总额统一取自唯一公式点 price(...)（usage == null → 全零分项 → 0.0）
        return price(model, usage, isPeak).totalYuan();
    }

    /**
     * T3 兜底重载 · 文本回合 usage 缺失时用估算 token 同口径计价（input/cache 0，output 估算值）。
     *
     * <p><b>A5-1 计费 provider 分派（deepseek 缓存双计修复）</b>: deepseek（openai 协议）
     * {@code input_tokens} <b>已含 cache hit</b>（input == H+M，DB 实锤）——Anthropic 语义的
     * {@code input + cacheRead + cacheCreate} 三字段求和计价对 deepseek <b>双计</b>（input 全价重复 +
     * 命中折价重复）。故按 {@link com.nexusai.application.agent.compact.ContextUsageCalculator#isAnthropic}
     * 分派：
     * <ul>
     *   <li><b>anthropic</b> → 现 4 项公式（CC modelCost.ts tokensToUSDCost）保留；</li>
     *   <li><b>非 anthropic（deepseek/openai）</b> → {@code max(0, input - cacheRead)/1e6*inputPrice
     *       + output/1e6*outputPrice + cacheRead/1e6*cacheReadPrice}（先扣命中再按 miss 全价计 input，
     *       命中按折价；<b>忽略 cacheCreate/cacheWritePrice</b>——deepseek 无 cache_write 档）。</li>
     * </ul>
     * <b>价语义（用户确认）</b>: input_price=miss 全价、cache_read_price=hit 折价、cache_write 对
     * deepseek 忽略。DB 全 NULL 时内置默认档 3.0/0.10 即此语义；日后配价按此填。
     * mapper 不可得（非 Spring new / 模型不可判定）→ isAnthropic false → deepseek 语义（input 含 cache）。
     *
     * <p><b>V101</b>：本方法已改为「{@code price(...).totalYuan()}」的<b>总额视图</b> ——
     * 公式本体只有一处（{@link #price(String, long, long, long, long, boolean)}），⛔ 不可在此重写公式。
     */
    public double calculateCostYuan(String model, long inputTokens, long outputTokens,
                                    long cacheRead, long cacheCreation, boolean isPeak) {
        return price(model, inputTokens, outputTokens, cacheRead, cacheCreation, isPeak).totalYuan();
    }

    /**
     * <b>V101 分项计价</b>（{@link AgentUsage} 入口）· 委托标量重载，usage 缺失 → 全零分项（不猜价格）。
     *
     * @param model  生效模型名（DB 价 / 内置默认解析用）
     * @param usage  provider 上报的 usage（null → 全零分项）
     * @param isPeak true=高峰档 / false=空闲档
     */
    public CostBreakdown price(String model, AgentUsage usage, boolean isPeak) {
        return price(model, usage, isPeak, null);
    }

    /**
     * <b>V101 分项计价</b> · 带<b>本次调用实际 provider 类型</b>的重载（计费链路专用）。
     *
     * <p><b>WHY</b>：{@code LlmCallRecord.modelName} 可能是裸名（历史兼容 / 旁路调用只拿到
     * SDK 裸名），而全库多个 provider 可有同名模型。计费只需要「这个模型的价格档」，不该走
     * 选模型链路的 fail-loud；给定 {@code providerType} 后按
     * {@link ModelNameResolver#resolveByProvider} 限定到该类型下 enabled provider 的模型行，
     * 唯一命中用 DB 价，否则回落内置默认档（不猜、不报 ERROR）。
     *
     * @param model        生效模型名（全名或裸名）
     * @param usage        provider 上报的 usage（null → 全零分项）
     * @param isPeak       true=高峰档 / false=空闲档
     * @param providerType 本次调用实际 provider 类型（空 → 通用解析）
     */
    public CostBreakdown price(String model, AgentUsage usage, boolean isPeak, String providerType) {
        if (usage == null) {
            return ZERO_BREAKDOWN;
        }
        long cacheRead = usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L;
        long cacheCreation = usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0L;
        return price(model, usage.inputTokens(), usage.outputTokens(), cacheRead, cacheCreation, isPeak,
            providerType);
    }

    /**
     * <b>V101 唯一公式点</b>：按 token 四通道 + 时段档算出费用分项（元）。
     *
     * <p>价格档与分派口径由 {@link #resolveTier}（DB 价优先 → 内置通用默认档）与
     * {@link ContextUsageCalculator#isAnthropic} 决定，语义与改造前 {@link #calculateCostYuan}
     * <b>逐字一致</b>（后者现为本方法总额的视图）：
     * <ul>
     *   <li><b>anthropic</b>（CC {@code modelCost.ts} tokensToUSDCost 4 项式）：
     *       {@code inputYuan = input/1e6*P_in}、{@code outputYuan = output/1e6*P_out}、
     *       {@code cacheReadYuan = cacheRead/1e6*P_cr}、{@code cacheWriteYuan = cacheCreation/1e6*P_cw}；
     *       {@code noCacheInputYuan = (input + cacheRead + cacheCreation)/1e6*P_in}
     *       （反事实：无 cache 时 input 侧全价）；</li>
     *   <li><b>非 anthropic（deepseek/openai，input 已含 cache hit）</b>：
     *       {@code miss = max(0, input - cacheRead)}、{@code inputYuan = miss/1e6*P_in}、
     *       {@code cacheWriteYuan = 0.0}（deepseek 无 cache_write 档）、
     *       {@code noCacheInputYuan = input/1e6*P_in}。</li>
     * </ul>
     * 总额恒为四项之和（{@code noCacheInputYuan} ⛔ 不计入）。
     *
     * @param model         生效模型名
     * @param inputTokens   输入 token（非 anthropic 时已含 cache 命中）
     * @param outputTokens  输出 token
     * @param cacheRead     cache 命中（读）token
     * @param cacheCreation cache 写入 token
     * @param isPeak        true=高峰档 / false=空闲档
     */
    public CostBreakdown price(String model, long inputTokens, long outputTokens,
                               long cacheRead, long cacheCreation, boolean isPeak) {
        return price(model, inputTokens, outputTokens, cacheRead, cacheCreation, isPeak, null);
    }

    /**
     * <b>V101 唯一公式点</b>（带 provider 类型）· 语义同 6 参重载，价格档与协议分派都按
     * {@code providerType} 限定（见 {@link #resolveTier(String, String)} 与
     * {@link ContextUsageCalculator#isAnthropic(ModelMapper, ProviderMapper, String, String)}）。
     *
     * @param model         生效模型名
     * @param inputTokens   输入 token（非 anthropic 时已含 cache 命中）
     * @param outputTokens  输出 token
     * @param cacheRead     cache 命中（读）token
     * @param cacheCreation cache 写入 token
     * @param isPeak        true=高峰档 / false=空闲档
     * @param providerType  本次调用实际 provider 类型（空 → 通用解析）
     */
    public CostBreakdown price(String model, long inputTokens, long outputTokens,
                               long cacheRead, long cacheCreation, boolean isPeak, String providerType) {
        PriceTier tier = resolveTier(model, providerType);
        double inputPrice = isPeak ? tier.inputPricePeak() : tier.inputPriceOffpeak();
        double outputPrice = isPeak ? tier.outputPricePeak() : tier.outputPriceOffpeak();
        double cacheReadPrice = isPeak ? tier.cacheReadPricePeak() : tier.cacheReadPriceOffpeak();
        double cacheWritePrice = isPeak ? tier.cacheWritePricePeak() : tier.cacheWritePriceOffpeak();
        boolean anthropic = ContextUsageCalculator.isAnthropic(this.modelMapper, this.providerMapper, model,
            providerType);
        if (anthropic) {
            double inputYuan = (inputTokens / 1e6) * inputPrice;
            double outputYuan = (outputTokens / 1e6) * outputPrice;
            double cacheReadYuan = (cacheRead / 1e6) * cacheReadPrice;
            double cacheWriteYuan = (cacheCreation / 1e6) * cacheWritePrice;
            double totalYuan = inputYuan + outputYuan + cacheReadYuan + cacheWriteYuan;
            double noCacheInputYuan = ((inputTokens + cacheRead + cacheCreation) / 1e6) * inputPrice;
            return new CostBreakdown(inputYuan, outputYuan, cacheReadYuan, cacheWriteYuan,
                totalYuan, noCacheInputYuan);
        }
        // 非 anthropic（deepseek/openai）：input 已含 cache hit → 先扣命中按 miss 全价，命中折价，
        // 忽略 cache_write（deepseek 无该档；cacheWriteYuan 记 0.0 而非 null —— PRICED 行的分项列齐全）
        long missTokens = Math.max(0L, inputTokens - cacheRead);
        double inputYuan = (missTokens / 1e6) * inputPrice;
        double outputYuan = (outputTokens / 1e6) * outputPrice;
        double cacheReadYuan = (cacheRead / 1e6) * cacheReadPrice;
        double cacheWriteYuan = 0.0;
        double totalYuan = inputYuan + outputYuan + cacheReadYuan + cacheWriteYuan;
        double noCacheInputYuan = (inputTokens / 1e6) * inputPrice;
        return new CostBreakdown(inputYuan, outputYuan, cacheReadYuan, cacheWriteYuan,
            totalYuan, noCacheInputYuan);
    }

    /**
     * 解析生效价格档：DB 价优先（models 表已配置）→ 内置通用默认档兜底。
     *
     * <p>[2026-10-29] 模型名规范化走 {@link ModelNameResolver#resolveQuiet}（<b>不是</b>选模型链路
     * 的 {@code resolve}）：计费只关心价格档，裸名歧义时回调默认档即可，不该刷 ERROR。
     * 能拿到本次调用 provider 类型的调用方请走 {@link #resolveTier(String, String)}。
     */
    public PriceTier resolveTier(String model) {
        return resolveTier(model, null);
    }

    /**
     * 解析生效价格档（带本次调用实际 provider 类型）· 计费链路推荐入口。
     *
     * <p>价格键解析走 {@link ModelNameResolver#resolveByProvider}：裸名只在
     * {@code providerType} 下 enabled provider 的模型行里找，唯一命中 → DB 价；0 / 多命中 →
     * 内置通用默认档（⛔ 不猜、不 fail-loud）。⛔ 选模型链路的 fail-loud 不受影响
     * （它走 {@code ModelNameResolver.resolve}）。
     *
     * @param model        生效模型名（全名或裸名）
     * @param providerType 本次调用实际 provider 类型（空 → 通用宽松解析）
     */
    public PriceTier resolveTier(String model, String providerType) {
        PriceTier builtin = DEFAULT_UNKNOWN_MODEL_TIER;
        if (modelMapper != null && model != null) {
            ModelRecord record = ModelNameResolver.resolveByProvider(modelMapper, providerMapper, model, providerType);
            // 锚定 input_price_peak：该模型已在 models 表显式配置价格 → DB 档优先（单列 null 回落内置通用默认档）
            if (record != null && record.getInputPricePeak() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[ModelCostCalculator] 价格档来源=DB: model={} providerType={} modelId={} providerId={}"
                            + " inputPricePeak={}",
                        model, providerType, record.getId(), record.getProviderId(), record.getInputPricePeak());
                }
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
            if (log.isDebugEnabled()) {
                log.debug("[ModelCostCalculator] 价格档来源=内置默认档（模型未配置价 / 裸名歧义 / 未命中）:"
                        + " model={} providerType={} recordPresent={}",
                    model, providerType, record != null);
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
        return contextWindowFor(model, null);
    }

    /**
     * modelUsage 桶的 contextWindow（带 provider 类型）· 与 {@link #resolveTier(String, String)}
     * 同源，供计费/统计链路避免裸名歧义刷屏。
     */
    public int contextWindowFor(String model, String providerType) {
        return (int) resolveTier(model, providerType).contextWindow();
    }

    /** modelUsage 桶的 maxOutputTokens（未知模型回退通用默认档 384K）。 */
    public long maxOutputFor(String model) {
        return maxOutputFor(model, null);
    }

    /** modelUsage 桶的 maxOutputTokens（带 provider 类型）· 同 {@link #resolveTier(String, String)}。 */
    public long maxOutputFor(String model, String providerType) {
        return resolveTier(model, providerType).maxOutputTokens();
    }
}

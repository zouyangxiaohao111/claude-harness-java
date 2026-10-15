package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [V101] {@link ModelCostCalculator#price} 费用<b>分项</b>契约（纯单测，无 Spring 容器）。
 *
 * <h2>WHY（规则九：不落这条会静默丢在哪）</h2>
 * V101 把「缓存省了多少钱」做成读侧可算的口径：计价点必须一次给出
 * input/output/cacheRead/cacheWrite 四项实付 + 一项反事实（{@code noCacheInputYuan}），
 * 再落 {@code llm_usage_calls} 的 {@code cost_*} 五列。这里钉死三件事：
 * <ol>
 *   <li><b>不变式</b>：{@code totalYuan == inputYuan + outputYuan + cacheReadYuan + cacheWriteYuan}
 *       （任一分项被漏加/多加，总额就与真账单不符）；反事实项 {@code noCacheInputYuan}
 *       <b>不计入</b>总额（否则「无 cache 假设成本」会被当成实付金额加进账单）；</li>
 *   <li><b>回归守护</b>：{@code price(...).totalYuan()} 与改造前 {@code calculateCostYuan(...)}
 *       在<b>两条分支</b>（anthropic 4 项式 / 非 anthropic 扣命中）上逐厘相等 —— 后者已改为委托
 *       {@code price(...).totalYuan()}，若有人把总额公式另写一套（两套公式必然漂），本断言立刻红；</li>
 *   <li><b>两分支的分项语义不同</b>：anthropic 的 {@code inputYuan} 是<b>全部</b> input 全价（cache 另计
 *       在 cacheRead/cacheWrite 项）；非 anthropic（deepseek/openai，input 已含命中）的 {@code inputYuan}
 *       是<b>扣掉命中后的 miss</b> 全价、且 {@code cacheWriteYuan = 0.0}。混用会让账单系统性偏高。</li>
 * </ol>
 *
 * <h2>反向实验（哪个变异会让哪条断言变红）</h2>
 * <ul>
 *   <li>anthropic 分支的 {@code inputYuan} 改成 {@code (input + cacheRead + cacheCreation)/1e6*P_in}
 *       （把反事实项当实付 → cache 双计）⇒
 *       {@code anthropicBreakdownComponentsAndInvariant} 的 inputYuan 断言 +
 *       {@code totalMatchesLegacyCalculateCostYuanOnBothBranches} 的 anthropic 例红；</li>
 *   <li>非 anthropic 分支的 {@code missTokens} 改回 {@code inputTokens}（回退 A5-1 双计修复）⇒
 *       {@code nonAnthropicBreakdownDeductsCacheHit} 的 inputYuan 断言 +
 *       {@code mutationDetector} 的同名断言红；</li>
 *   <li>{@code totalYuan} 漏加 {@code cacheWriteYuan}（anthropic 分支）⇒
 *       {@code anthropicBreakdownComponentsAndInvariant} 的不变式断言 + {@code mutationDetector} 红；</li>
 *   <li>anthropic 的 {@code noCacheInputYuan} 退化成 {@code input/1e6*P_in}（丢掉命中/写入）⇒
 *       「缓存省了多少钱」断言（≥ 命中折价差）红。</li>
 * </ul>
 */
@DisplayName("[V101] ModelCostCalculator 费用分项 price(...) 契约")
class ModelCostCalculatorBreakdownTest {

    /** DB 档价（anthropic 用例）：input 3.0 / output 9.0 / cacheRead 0.10 / cacheWrite 3.0（元/百万 tokens）。 */
    private static final double P_IN = 3.0;
    private static final double P_OUT = 9.0;
    private static final double P_CR = 0.10;
    private static final double P_CW = 3.0;

    /** 无 Spring / 无 mapper → isAnthropic false → 非 anthropic（deepseek）分支 + 内置通用默认档价。 */
    private final ModelCostCalculator deepseekCalc = new ModelCostCalculator();

    /** 模型 "claude-sonnet" 归属 provider type=anthropic + DB 已配价 → anthropic 分支走 DB 档价。 */
    private static ModelCostCalculator anthropicCalc() {
        ModelRecord dbRecord = new ModelRecord();
        dbRecord.setId("m-claude");
        dbRecord.setName("claude-sonnet");
        dbRecord.setEnabled(true);
        dbRecord.setProviderId("p-anthropic");
        dbRecord.setInputPricePeak(P_IN);
        dbRecord.setInputPriceOffpeak(P_IN / 2);
        dbRecord.setOutputPricePeak(P_OUT);
        dbRecord.setOutputPriceOffpeak(P_OUT / 2);
        dbRecord.setCacheReadPricePeak(P_CR);
        dbRecord.setCacheReadPriceOffpeak(P_CR / 2);
        dbRecord.setCacheWritePricePeak(P_CW);
        dbRecord.setCacheWritePriceOffpeak(P_CW / 2);

        ProviderRecord provider = new ProviderRecord();
        provider.setId("p-anthropic");
        provider.setType("anthropic");

        ModelCostCalculator calc = new ModelCostCalculator();
        ModelMapper mm = mock(ModelMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of(dbRecord));
        ProviderMapper pm = mock(ProviderMapper.class);
        when(pm.selectOneById("p-anthropic")).thenReturn(provider);
        ReflectionTestUtils.setField(calc, "modelMapper", mm);
        ReflectionTestUtils.setField(calc, "providerMapper", pm);
        return calc;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1 · anthropic 分支：4 项全价 + 反事实项 + 不变式
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("anthropic：input 全部全价、cache 另计、cacheWrite 按写入价；四项之和 == total，反事实项不计入")
    void anthropicBreakdownComponentsAndInvariant() {
        ModelCostCalculator calc = anthropicCalc();
        // input=1M / output=500K / cacheRead=1M / cacheCreation=500K（高峰档）
        // 期望：input=3.0 / output=4.5 / cacheRead=0.1 / cacheWrite=1.5 / total=9.1
        //       反事实 noCacheInput=(1M+1M+0.5M)/1e6*3 = 7.5（无 cache 时 input 侧全价）
        ModelCostCalculator.CostBreakdown cb =
            calc.price("claude-sonnet", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);

        assertThat(cb.inputYuan()).as("anthropic：input 全部按全价（cache 不得从 input 里扣）")
            .isCloseTo(3.0, within(1e-9));
        assertThat(cb.outputYuan()).as("output 全价").isCloseTo(4.5, within(1e-9));
        assertThat(cb.cacheReadYuan()).as("命中按命中折价").isCloseTo(0.1, within(1e-9));
        assertThat(cb.cacheWriteYuan()).as("anthropic 有 cache_write 档（非 anthropic 才是 0）")
            .isCloseTo(1.5, within(1e-9));
        assertThat(cb.totalYuan()).as("总额 = 四项之和").isCloseTo(9.1, within(1e-9));
        assertThat(cb.totalYuan())
            .as("不变式：totalYuan == input + output + cacheRead + cacheWrite（漏加任一项即红）")
            .isEqualTo(cb.inputYuan() + cb.outputYuan() + cb.cacheReadYuan() + cb.cacheWriteYuan());
        assertThat(cb.noCacheInputYuan()).as("反事实：三通道 input 侧 tokens 全价")
            .isCloseTo(7.5, within(1e-9));
        assertThat(cb.totalYuan())
            .as("反事实项⛔不得计入总额（否则把它当实付 → 金额系统性偏高）")
            .isNotCloseTo(cb.totalYuan() + cb.noCacheInputYuan(), within(1e-9));

        // 「缓存省了多少钱」= 反事实 − 三项实际支出 = 命中×(全价−命中价) + 写入×(全价−写入价)
        double saved = cb.noCacheInputYuan() - (cb.inputYuan() + cb.cacheReadYuan() + cb.cacheWriteYuan());
        assertThat(saved).as("anthropic：命中 1M×(3−0.1) + 写入 0.5M×(3−3) = 2.9 元")
            .isCloseTo(1_000_000L / 1e6 * (P_IN - P_CR) + 500_000L / 1e6 * (P_IN - P_CW), within(1e-9));
        assertThat(saved).isCloseTo(2.9, within(1e-9));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2 · 非 anthropic 分支：先扣命中（input 已含 cache hit）、无 cacheWrite 档
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("非 anthropic：miss = max(0,input−cacheRead) 按全价、命中折价、cacheWrite=0.0、反事实=input 全价")
    void nonAnthropicBreakdownDeductsCacheHit() {
        // 内置通用默认档高峰：input 3.0 / output 9.0 / cacheRead 0.10 / cacheWrite 3.0（不参与本分支）
        // input=1M / output=500K / cacheRead=1M / cacheCreation=500K → miss=0
        ModelCostCalculator.CostBreakdown allHit =
            deepseekCalc.price("any-model", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);
        assertThat(allHit.inputYuan()).as("命中已含在 input 里 → miss 全价项为 0（回退 A5-1 双计修复即红）")
            .isCloseTo(0.0, within(1e-9));
        assertThat(allHit.cacheReadYuan()).as("命中按命中折价").isCloseTo(0.1, within(1e-9));
        assertThat(allHit.cacheWriteYuan()).as("deepseek 无 cache_write 档 → 记 0.0（PRICED 行仍五列齐全）")
            .isZero();
        assertThat(allHit.outputYuan()).isCloseTo(4.5, within(1e-9));
        assertThat(allHit.totalYuan()).as("总额 = 0 + 4.5 + 0.1 + 0.0").isCloseTo(4.6, within(1e-9));
        assertThat(allHit.totalYuan())
            .isEqualTo(allHit.inputYuan() + allHit.outputYuan() + allHit.cacheReadYuan() + allHit.cacheWriteYuan());
        assertThat(allHit.noCacheInputYuan()).as("反事实：input 全部按全价（1M/1e6*3）")
            .isCloseTo(3.0, within(1e-9));

        // 部分命中：miss = 1M − 200K = 800K → 2.4 元
        ModelCostCalculator.CostBreakdown partial =
            deepseekCalc.price("any-model", 1_000_000L, 0L, 200_000L, 0L, true);
        assertThat(partial.inputYuan()).as("miss 全价 = 800K/1e6*3").isCloseTo(2.4, within(1e-9));
        assertThat(partial.cacheReadYuan()).isCloseTo(0.02, within(1e-9));
        assertThat(partial.totalYuan()).isCloseTo(2.42, within(1e-9));

        // cacheRead > input（上游异常/估算）→ miss 不得为负（Math.max(0, …)）
        ModelCostCalculator.CostBreakdown overReported =
            deepseekCalc.price("any-model", 100L, 0L, 1_000_000L, 0L, true);
        assertThat(overReported.inputYuan()).as("miss 下限 0（负 miss 会产出负费用）").isZero();
        assertThat(overReported.totalYuan()).as("只留命中折价 0.1").isCloseTo(0.1, within(1e-9));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3 · 回归守护：price().totalYuan() == 既有 calculateCostYuan()（两条分支 · 逐厘）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("回归守护：price(...).totalYuan() 与既有 calculateCostYuan(...) 同参同值（两分支 × 双档 × 多组 token）")
    void totalMatchesLegacyCalculateCostYuanOnBothBranches() {
        long[][] vectors = {
            {0L, 0L, 0L, 0L},
            {1_000_000L, 0L, 0L, 0L},
            {1_000_000L, 500_000L, 1_000_000L, 500_000L},
            {1_000_000L, 500_000L, 200_000L, 0L},
            {7L, 3L, 5L, 11L},
            {2_345_678L, 987_654L, 456_789L, 123_456L},
        };
        for (ModelCostCalculator calc : List.of(anthropicCalc(), deepseekCalc)) {
            for (boolean peak : new boolean[] {true, false}) {
                for (long[] v : vectors) {
                    ModelCostCalculator.CostBreakdown cb =
                        calc.price("claude-sonnet", v[0], v[1], v[2], v[3], peak);
                    double legacy = calc.calculateCostYuan("claude-sonnet", v[0], v[1], v[2], v[3], peak);
                    assertThat(cb.totalYuan())
                        .as("总额必须与既有 calculateCostYuan 同参同值（peak=%s, in=%d, out=%d, cr=%d, cw=%d）",
                            peak, v[0], v[1], v[2], v[3])
                        .isEqualTo(legacy);
                    assertThat(cb.totalYuan())
                        .isEqualTo(cb.inputYuan() + cb.outputYuan() + cb.cacheReadYuan() + cb.cacheWriteYuan());
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4 · AgentUsage 入口：与标量同源；usage 缺失 → 全零分项
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("AgentUsage 重载与标量重载同源（cache 两列 null 视为 0）；usage null → 全零分项且总额 0")
    void usageOverloadMatchesScalarAndNullIsZero() {
        AgentUsage usage = new AgentUsage(1_000_000L, 500_000L, 500_000L, 1_000_000L, null, null, null);
        ModelCostCalculator.CostBreakdown fromUsage = deepseekCalc.price("any-model", usage, true);
        ModelCostCalculator.CostBreakdown scalar =
            deepseekCalc.price("any-model", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);
        assertThat(fromUsage).as("AgentUsage 重载必须与标量重载分项全等").isEqualTo(scalar);

        // cache 两列 null（无 cache 数据）→ 视作 0，不得 NPE / 不得把 null 当命中
        AgentUsage noCache = new AgentUsage(1_000_000L, 0L, null, null, null, null, null);
        ModelCostCalculator.CostBreakdown noCacheCb = deepseekCalc.price("any-model", noCache, true);
        assertThat(noCacheCb.cacheReadYuan()).isZero();
        assertThat(noCacheCb.cacheWriteYuan()).isZero();
        assertThat(noCacheCb.inputYuan()).as("无 cache 信息 → miss 全价 = 3.0").isCloseTo(3.0, within(1e-9));
        assertThat(noCacheCb.noCacheInputYuan()).as("本就是无 cache 场景 → 反事实 == 实付 input 侧").isEqualTo(noCacheCb.inputYuan());

        ModelCostCalculator.CostBreakdown zero = deepseekCalc.price("any-model", (AgentUsage) null, true);
        assertThat(zero.totalYuan()).as("usage 缺失 → 0 元（不猜价格）").isZero();
        assertThat(zero).isEqualTo(new ModelCostCalculator.CostBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertThat(deepseekCalc.calculateCostYuan("any-model", (AgentUsage) null, true))
            .as("既有 AgentUsage 重载的 null 语义（0.0）不得因委托 price 而漂")
            .isZero();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5 · 反向实验（可执行）：变异体与生产值必须不同
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("反向实验：把分项公式改错（双计 / 漏项 / 反事实退化）时必须与生产值不同 —— 变异可被本类抓住")
    void mutationDetector() {
        // 变异① anthropic：inputYuan 写成三通道求和（双计）→ 7.5 而非 3.0
        ModelCostCalculator.CostBreakdown anthropic =
            anthropicCalc().price("claude-sonnet", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);
        double doubleCounted = (1_000_000L + 1_000_000L + 500_000L) / 1e6 * P_IN;
        assertThat(anthropic.inputYuan())
            .as("变异：anthropic inputYuan 双计（三通道求和当实付）→ 本断言变红")
            .isNotCloseTo(doubleCounted, within(1e-9));
        assertThat(anthropic.totalYuan())
            .as("变异：同上 → 总额变 %s，不再是 %s", doubleCounted + 4.5 + 0.1 + 1.5, anthropic.totalYuan())
            .isNotCloseTo(doubleCounted + 4.5 + 0.1 + 1.5, within(1e-9));

        // 变异② anthropic：totalYuan 漏加 cacheWriteYuan → 7.6 而非 9.1
        assertThat(anthropic.totalYuan())
            .as("变异：anthropic totalYuan 漏加 cacheWriteYuan → 本断言变红")
            .isNotCloseTo(anthropic.inputYuan() + anthropic.outputYuan() + anthropic.cacheReadYuan(), within(1e-9));

        // 变异③ anthropic：noCacheInputYuan 退化（丢掉命中/写入）→ 3.0 而非 7.5
        assertThat(anthropic.noCacheInputYuan())
            .as("变异：反事实项退化成 input/1e6*P_in（丢掉 cache 通道）→ 「缓存省了多少钱」变红")
            .isNotCloseTo(1_000_000L / 1e6 * P_IN, within(1e-9));

        // 变异④ 非 anthropic：missTokens 改回 inputTokens（回退 A5-1 双计修复）→ 3.0 而非 0.0
        ModelCostCalculator.CostBreakdown deepseek =
            deepseekCalc.price("any-model", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);
        assertThat(deepseek.inputYuan())
            .as("变异：非 anthropic 未扣命中（input 全价重复计）→ 本断言变红")
            .isNotCloseTo(1_000_000L / 1e6 * 3.0, within(1e-9));
    }
}

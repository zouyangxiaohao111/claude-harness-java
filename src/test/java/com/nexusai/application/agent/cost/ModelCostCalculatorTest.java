package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [V-TOK] ModelCostCalculator 计费纯函数测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：token usage/cost 上报链路的核心计价 ——
 * 高峰/空闲双档判定、公式对齐 CC tokensToUSDCost、未知模型回退 flash、DB 配置价优先，
 * 任一偏差都会让 message.complete 的 total_cost_usd/modelUsage.costUSD 失真。
 */
@DisplayName("[V-TOK] ModelCostCalculator 计费纯函数")
class ModelCostCalculatorTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private final ModelCostCalculator calc = new ModelCostCalculator();

    @Test
    @DisplayName("isPeakHour：工作日 9:00-12:00/14:00-18:00 半开区间判定 + 周末全天空闲")
    void isPeakHour_workdayBoundariesAndWeekend() {
        // WHY: 验收 6 —— 价格双档（空闲/高峰），高峰=北京 9:00-12:00/14:00-18:00 非周末；空闲=其余；
        //   周末全天空闲；空闲=高峰×50%。边界用半开区间（起点含、终点不含）。
        // 2026-08-24 = 周一（工作日）；2026-08-29/30 = 周六/周日
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 9, 0, 0, 0, SH)))
            .as("09:00 上午窗口起点（含）→ peak").isTrue();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 11, 59, 0, 0, SH)))
            .as("11:59 上午窗口内 → peak").isTrue();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 12, 0, 0, 0, SH)))
            .as("12:00 上午窗口终点（不含）→ off-peak").isFalse();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 13, 59, 0, 0, SH)))
            .as("13:59 中午休档 → off-peak").isFalse();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 14, 0, 0, 0, SH)))
            .as("14:00 下午窗口起点（含）→ peak").isTrue();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 17, 59, 0, 0, SH)))
            .as("17:59 下午窗口内 → peak").isTrue();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 18, 0, 0, 0, SH)))
            .as("18:00 下午窗口终点（不含）→ off-peak").isFalse();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 24, 8, 59, 0, 0, SH)))
            .as("08:59 窗口外 → off-peak").isFalse();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 29, 10, 0, 0, 0, SH)))
            .as("周六 10:00 → 全天空闲").isFalse();
        assertThat(calc.isPeakHour(ZonedDateTime.of(2026, 8, 30, 15, 0, 0, 0, SH)))
            .as("周日 15:00 → 全天空闲").isFalse();
    }

    @Test
    @DisplayName("off-peak = peak×50%（通用默认档双档价格表全字段半折）")
    void offPeak_isHalfOfPeak() {
        // WHY: 验收 6 —— 空闲=高峰×50% 是双档计费的定义性不变量（任一价格列偏斜都破坏）；
        //   未配置模型回落通用默认档，双档半折不变量对回落档同样成立
        double peak = calc.calculateCostYuan("any-model", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);
        double off = calc.calculateCostYuan("any-model", 1_000_000L, 500_000L, 1_000_000L, 500_000L, false);
        assertThat(off).as("off-peak 必须恰为 peak 的 50%").isCloseTo(peak * 0.5, within(1e-6));
    }

    @Test
    @DisplayName("通用默认档高峰计费公式（3/9 元档，对齐 CC tokensToUSDCost）")
    void calculateCostYuan_matchesCcFormula() {
        // WHY: 公式 = (input/1e6)*inputPk + (output/1e6)*outputPk + (cacheRead/1e6)*cacheReadPk
        //   + (cacheCreation/1e6)*cacheWritePk（webSearch 不计）。
        // 通用默认档高峰：input 3.0 / output 9.0 / cacheRead 0.10 / cacheWrite(=input) 3.0
        // 期望 = 1M/1e6*3 + 500K/1e6*9 + 1M/1e6*0.1 + 500K/1e6*3 = 3 + 4.5 + 0.1 + 1.5 = 9.1
        double scalar = calc.calculateCostYuan("any-model", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true);
        assertThat(scalar).as("标量重载计费公式").isCloseTo(9.1, within(1e-6));

        AgentUsage usage = new AgentUsage(1_000_000L, 500_000L, 500_000L, 1_000_000L, null, null, null);
        double fromUsage = calc.calculateCostYuan("any-model", usage, true);
        assertThat(fromUsage).as("AgentUsage 重载与标量同源（cacheCreate→cacheWrite 价）").isCloseTo(9.1, within(1e-6));
    }

    @Test
    @DisplayName("未知/未配置模型回退通用默认档（任意未配置模型同档同价）")
    void unknownModel_fallsBackToGenericDefaultTier() {
        // WHY: 验收 4 —— 未知模型不得因查不到价格产生 0 花费；统一回落通用默认档
        //   （DB 未配置/无对应记录 → DEFAULT_UNKNOWN_MODEL_TIER，不因模型名不同而不同价）
        double any = calc.calculateCostYuan("any-model", 1_000_000L, 0L, 0L, 0L, true);
        double another = calc.calculateCostYuan("another-unconfigured-model", 1_000_000L, 0L, 0L, 0L, true);
        assertThat(any).as("任意未配置模型 == 通用默认档计价").isEqualTo(another);
        assertThat(calc.resolveTier("any-model"))
            .as("resolveTier 未配置模型命中通用默认档实例").isSameAs(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER);
        assertThat(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER.inputPricePeak())
            .as("通用默认档高峰 input = 3.0 元/百万 tokens").isEqualTo(3.0);
    }

    @Test
    @DisplayName("DB 配置价优先于通用默认档（models 表 input_price_peak 锚定）")
    void dbPrice_takesPriorityOverGenericDefault() {
        // WHY: plan §4 —— 运行时优先读 models 表配置价格，未配置回落通用默认档；
        //   用户可自建模型自定价（前端模型管理页）；DB 已配置 = input_price_peak 非 null
        ModelRecord dbRecord = new ModelRecord();
        dbRecord.setId("m1");
        dbRecord.setName("custom-model");
        dbRecord.setEnabled(true);
        dbRecord.setInputPricePeak(12.0);   // 通用默认档高峰 input = 3.0，DB 配置 12.0 → 应优先
        dbRecord.setInputPriceOffpeak(6.0);
        dbRecord.setOutputPricePeak(18.0);
        dbRecord.setOutputPriceOffpeak(9.0);
        dbRecord.setCacheReadPricePeak(0.4);
        dbRecord.setCacheReadPriceOffpeak(0.2);
        dbRecord.setCacheWritePricePeak(12.0);
        dbRecord.setCacheWritePriceOffpeak(6.0);

        ModelCostCalculator dbCalc = new ModelCostCalculator();
        ModelMapper mm = mock(ModelMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of(dbRecord));
        ReflectionTestUtils.setField(dbCalc, "modelMapper", mm);
        ReflectionTestUtils.setField(dbCalc, "providerMapper", mock(ProviderMapper.class));

        double cost = dbCalc.calculateCostYuan("custom-model", 1_000_000L, 0L, 0L, 0L, true);
        assertThat(cost).as("DB input_price_peak=12 优先于通用默认档 3").isEqualTo(12.0);
    }

    @Test
    @DisplayName("DB 无记录回落通用默认档（models 表未建该模型）")
    void dbMissingRecord_fallsBackToGenericDefault() {
        // WHY: 验收 4 —— DB 无该模型记录（selectListByQuery 空）不得产生 0 花费，回落通用默认档；
        //   即「未配置模型」与「未建模型」同档同价
        ModelCostCalculator noRecCalc = new ModelCostCalculator();
        ModelMapper mm = mock(ModelMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(noRecCalc, "modelMapper", mm);
        ReflectionTestUtils.setField(noRecCalc, "providerMapper", mock(ProviderMapper.class));

        assertThat(noRecCalc.resolveTier("any-model"))
            .as("DB 无记录 → 通用默认档").isSameAs(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER);
        assertThat(noRecCalc.calculateCostYuan("any-model", 1_000_000L, 0L, 0L, 0L, true))
            .as("通用默认高峰 input 3.0 × 1M/1e6 = 3.0 元").isEqualTo(3.0);
    }

    @Test
    @DisplayName("DB 记录存在但价格列全 null 回落通用默认档（input_price_peak 锚定失败）")
    void dbRecordWithoutPrice_fallsBackToGenericDefault() {
        // WHY: 锚定 input_price_peak 是「该模型已显式配置价格」的唯一判据——记录存在但 8 价格列
        //   全 null（create null → 存 NULL）与未配置等价，回落通用默认档而非 0 花费
        ModelRecord bare = new ModelRecord();
        bare.setId("m1");
        bare.setName("any-model");
        bare.setEnabled(true);

        ModelCostCalculator noPriceCalc = new ModelCostCalculator();
        ModelMapper mm = mock(ModelMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of(bare));
        ReflectionTestUtils.setField(noPriceCalc, "modelMapper", mm);
        ReflectionTestUtils.setField(noPriceCalc, "providerMapper", mock(ProviderMapper.class));

        assertThat(noPriceCalc.resolveTier("any-model"))
            .as("记录无价格 → 通用默认档").isSameAs(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER);
        assertThat(noPriceCalc.calculateCostYuan("any-model", 1_000_000L, 0L, 0L, 0L, true))
            .as("回落默认 input 3.0 → 3.0 元").isEqualTo(3.0);
    }

    @Test
    @DisplayName("无 mapper 注入（非 Spring new）回落通用默认档，不 NPE")
    void noMapper_fallsBackToGenericDefault() {
        // WHY: {@code @Autowired(required = false)} 双点注入——非 Spring 单测 new 时
        //   modelMapper=null，必须静默回落通用默认档（否则计费链路 NPE）
        assertThat(calc.resolveTier("any-model"))
            .as("无 mapper → 通用默认档").isSameAs(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER);
        assertThat(calc.calculateCostYuan("any-model", 1_000_000L, 0L, 0L, 0L, true))
            .as("1M input × 3.0 = 3.0 元").isEqualTo(3.0);
    }
}

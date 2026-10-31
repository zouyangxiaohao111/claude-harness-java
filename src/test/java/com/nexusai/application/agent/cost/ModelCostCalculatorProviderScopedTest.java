package com.nexusai.application.agent.cost;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [2026-10-29] 计费价格档按「本次调用实际 provider 类型」限定 · {@link ModelCostCalculator} 单测。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：计费键 {@code modelName} 可能是裸名（旁路调用只拿到
 * SDK 裸名 / 历史数据），而全库可有多个 provider 提供同名模型。修复前计费走选模型链路的
 * {@code ModelNameResolver.resolve}：多命中 → ERROR + null → 回落内置默认档，日志刷屏且
 * 「价格表里配了也用不上」。修复后按 {@code LlmCallRecord.providerType()} 限定候选：
 * <ul>
 *   <li>该类型下 enabled provider 的唯一模型行 → 用它的 DB 价；</li>
 *   <li>0 / 多命中 → 内置默认档，且<b>零 WARN/ERROR</b>（噪声消掉）；</li>
 *   <li>选模型链路的 fail-loud 不经过本类，不受影响。</li>
 * </ul>
 */
@DisplayName("[2026-10-29] ModelCostCalculator 计费 provider 限定")
class ModelCostCalculatorProviderScopedTest {

    private static ModelRecord pricedModel(String id, String providerId, double inputPricePeak) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setName("deepseek-flash");
        m.setProviderId(providerId);
        m.setEnabled(true);
        m.setInputPricePeak(inputPricePeak);
        return m;
    }

    private static ProviderRecord provider(String id, String type) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setType(type);
        p.setEnabled(true);
        return p;
    }

    private static ListAppender<ILoggingEvent> attachCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModelCostCalculator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModelCostCalculator.class);
        logger.detachAppender(appender);
    }

    /** 解析器的 WARN/ERROR 也要一起盯（噪音原本就打在 ModelNameResolver 上）。 */
    private static ListAppender<ILoggingEvent> attachResolverCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.nexusai.infra.llm.ModelNameResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachResolverCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.nexusai.infra.llm.ModelNameResolver.class);
        logger.detachAppender(appender);
    }

    private static long warnOrErrorCount(ListAppender<ILoggingEvent> app) {
        return app.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).count();
    }

    private static ModelCostCalculator calcWith(ModelMapper mm, ProviderMapper pm) {
        ModelCostCalculator calc = new ModelCostCalculator();
        ReflectionTestUtils.setField(calc, "modelMapper", mm);
        ReflectionTestUtils.setField(calc, "providerMapper", pm);
        return calc;
    }

    @Test
    @DisplayName("给定 providerType：取该 provider 的价格行（不按字典序猜）且查询带 provider 过滤")
    void resolveTier_withProviderType_picksThatProvidersPrice() {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of(
            pricedModel("model-da68c5e0", "prov-a583e4c7", 99.0),
            pricedModel("model-0823a98d", "prov-b053b03d", 12.0)));
        when(pm.selectListByQuery(any())).thenReturn(List.of(
            provider("prov-b053b03d", "openai_compatible")));

        ModelCostCalculator calc = calcWith(mm, pm);

        assertThat(calc.resolveTier("deepseek-flash", "openai_compatible").inputPricePeak())
            .as("必须用 deepseek provider 那一行的 12 元，而不是字典序第一行（99 元）")
            .isEqualTo(12.0);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(pm).selectListByQuery(captor.capture());
        assertThat(String.valueOf(captor.getValue().toSQL()))
            .as("provider 查询条件里必须带 type 过滤")
            .contains("type").contains("enabled");
    }

    @Test
    @DisplayName("同 providerType 下多命中 → 内置默认档（不猜、零 WARN/ERROR）")
    void resolveTier_ambiguousWithinProviderType_fallsBackQuietly() {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of(
            pricedModel("m1", "prov-a", 99.0),
            pricedModel("m2", "prov-b", 12.0)));
        when(pm.selectListByQuery(any())).thenReturn(List.of(
            provider("prov-a", "openai_compatible"),
            provider("prov-b", "openai_compatible")));
        ModelCostCalculator calc = calcWith(mm, pm);

        ListAppender<ILoggingEvent> app = attachCapture();
        ListAppender<ILoggingEvent> resolverApp = attachResolverCapture();
        try {
            ModelCostCalculator.PriceTier tier = calc.resolveTier("deepseek-flash", "openai_compatible");
            assertThat(tier)
                .as("歧义时回落内置默认档实例（不把费用记到错误的模型行上）")
                .isSameAs(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER);
            assertThat(warnOrErrorCount(app))
                .as("计费器自身不得 WARN/ERROR")
                .isZero();
            assertThat(warnOrErrorCount(resolverApp))
                .as("解析器在计费路径上只允许 DEBUG —— 这正是本次要消掉的 ERROR 噪音")
                .isZero();
        } finally {
            detachCapture(app);
            detachResolverCapture(resolverApp);
        }
    }

    @Test
    @DisplayName("providerType 为空：退回通用宽松解析，裸名歧义回落默认档且零 WARN/ERROR")
    void resolveTier_noProviderType_quietFallback() {
        ModelMapper mm = mock(ModelMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of(
            pricedModel("m1", "prov-a", 12.0),
            pricedModel("m2", "prov-b", 99.0)));
        ModelCostCalculator calc = calcWith(mm, mock(ProviderMapper.class));

        ListAppender<ILoggingEvent> app = attachCapture();
        ListAppender<ILoggingEvent> resolverApp = attachResolverCapture();
        try {
            assertThat(calc.resolveTier("deepseek-flash", null))
                .as("无 providerType 时无法消歧 → 默认档").isSameAs(ModelCostCalculator.DEFAULT_UNKNOWN_MODEL_TIER);
            assertThat(warnOrErrorCount(app)).isZero();
            assertThat(warnOrErrorCount(resolverApp))
                .as("无 providerType 的计费路径也必须零 WARN/ERROR").isZero();
        } finally {
            detachCapture(app);
            detachResolverCapture(resolverApp);
        }
    }

    @Test
    @DisplayName("price(...) 带 providerType：协议分派直接用类型（anthropic 4 项式），不再 DB 反查")
    void price_withProviderType_dispatchesByType() {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        ModelRecord m = pricedModel("m1", "prov-a", 3.0);
        m.setOutputPricePeak(9.0);
        m.setCacheReadPricePeak(0.10);
        m.setInputPriceOffpeak(1.5);
        m.setOutputPriceOffpeak(4.5);
        m.setCacheReadPriceOffpeak(0.05);
        m.setCacheWritePricePeak(3.0);
        m.setCacheWritePriceOffpeak(1.5);
        when(mm.selectListByQuery(any())).thenReturn(List.of(m));
        when(pm.selectListByQuery(any())).thenReturn(List.of(provider("prov-a", "anthropic")));
        ModelCostCalculator calc = calcWith(mm, pm);

        // anthropic 4 项式：1M/1e6*3 + 0.5M/1e6*9 + 1M/1e6*0.1 + 0.5M/1e6*3 = 9.1
        ModelCostCalculator.CostBreakdown cb =
            calc.price("deepseek-flash", 1_000_000L, 500_000L, 1_000_000L, 500_000L, true, "anthropic");
        assertThat(cb.totalYuan()).as("providerType=anthropic → 4 项公式").isCloseTo(9.1, org.assertj.core.api.Assertions.within(1e-6));
    }
}

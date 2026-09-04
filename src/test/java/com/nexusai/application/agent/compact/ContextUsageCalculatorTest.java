package com.nexusai.application.agent.compact;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [修复] ContextUsageCalculator 协议分派单测 · 实时（ChatService）/重算（MessageService）共用单点。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: contextTokensUsed 按 provider 协议分派——
 * Anthropic 三字段和（Claude API usage 独立，utils/context.ts:131-133），OpenAI/DeepSeek 仅 input
 * （prompt_tokens 已含 cache hit，加 cacheRead 双计）。变异点：两处内联各自实现曾导致重算恒三字段和
 * → 主模型 DeepSeek 双计 cache。本测试钉死单点公式 + isAnthropic 判定链。
 */
@DisplayName("[修复] ContextUsageCalculator 协议分派")
class ContextUsageCalculatorTest {

    // ─────────────────────────── computeContextTokensUsed 纯函数 ───────────────────────────

    @Test
    @DisplayName("Anthropic：input+cacheRead+cacheCreate 三字段和")
    void compute_anthropicSumsAllThree() {
        assertThat(ContextUsageCalculator.computeContextTokensUsed(2000L, 500L, 300L, true))
            .as("Anthropic 三字段独立 → 三字段和")
            .isEqualTo(2800L);
    }

    @Test
    @DisplayName("Anthropic：cache 为 null → 0 容错（无 cache 请求 / 旧行无 cache 列）")
    void compute_anthropicNullCacheFallsBackToInput() {
        assertThat(ContextUsageCalculator.computeContextTokensUsed(2000L, null, null, true))
            .as("Anthropic + cache null → input + 0 + 0")
            .isEqualTo(2000L);
        assertThat(ContextUsageCalculator.computeContextTokensUsed(2000L, 500L, null, true))
            .as("Anthropic + cacheCreate null → input + cacheRead + 0")
            .isEqualTo(2500L);
    }

    @Test
    @DisplayName("openai_compatible：仅 input——加 cacheRead 双计 → 红（核心意图）")
    void compute_openaiCompatibleIgnoresCache() {
        // WHY: DeepSeek 走 openai_compatible，prompt_tokens 已含 cache hit；加 cacheRead 双计。
        assertThat(ContextUsageCalculator.computeContextTokensUsed(2000L, 500L, 300L, false))
            .as("openai_compatible → 仅 input（不双计 cache）")
            .isEqualTo(2000L);
    }

    // ─────────────────────────── computeCacheHitRate 纯函数 ───────────────────────────

    @Test
    @DisplayName("Anthropic：cache 命中率 = read/(input+read+create) 三字段分母（CC forkedAgent.ts:647-654）")
    void cacheHitRate_anthropic_threeFieldDenominator() {
        // WHY: Claude usage 三字段独立 → 分母 = input + cache_read + cache_create（CC :651-654）。
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, 900L, 100L, true))
            .as("read/(input+read+create) = 900/2000")
            .isCloseTo(0.45, within(1e-9));
    }

    @Test
    @DisplayName("Anthropic：cache 字段 null → 0 容错（无 cache / 旧行无 cache 列）")
    void cacheHitRate_anthropic_nullCacheFields() {
        // WHY: cacheRead null → read=0 → 0（无命中）；cacheRead 非 null 但 cacheCreate null → 0 补齐。
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, null, null, true))
            .as("cacheRead null → read=0 → 0")
            .isEqualTo(0d);
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, 900L, null, true))
            .as("cacheCreate null → 分母 = input + read + 0 = 900/1900")
            .isCloseTo(900.0 / 1900.0, within(1e-9));
    }

    @Test
    @DisplayName("非 anthropic（openai/deepseek）：命中率 = read/input——prompt_tokens 已含 cache hit（核心意图）")
    void cacheHitRate_nonAnthropic_readOverInput() {
        // WHY: DeepSeek input==H+M；旧恒三字段分母（read/(input+read+create) = 900/2000 = 0.45）
        //   恒为真实一半 → 命中率口径修复：read/input = 900/1000 = 0.9。
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, 900L, 100L, false))
            .as("read/input = 0.9（防 0.45 回归）")
            .isCloseTo(0.9, within(1e-9));
    }

    @Test
    @DisplayName("read=0 或分母≤0 → 0（无命中 / 非法场景）")
    void cacheHitRate_zeroWhenNoReadOrBadDenominator() {
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, 0L, 100L, true))
            .as("read=0 → 0")
            .isEqualTo(0d);
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, 0L, 100L, false))
            .as("read=0（非 anthropic）→ 0")
            .isEqualTo(0d);
        assertThat(ContextUsageCalculator.computeCacheHitRate(0L, 900L, 100L, false))
            .as("非 anthropic 分母=input=0 → 0（input=0 但 cacheRead>0 非法）")
            .isEqualTo(0d);
        assertThat(ContextUsageCalculator.computeCacheHitRate(0L, 900L, 100L, true))
            .as("anthropic 分母 = 0+900+100 > 0 → 900/1000 = 0.9（input=0 但 read/create 存在仍可算）")
            .isCloseTo(0.9, within(1e-9));
    }

    // ─────────────────────────── isAnthropic 判定链 ───────────────────────────

    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;

    /** 模型全名路径可解析（providers 前缀命中 → models 命中 m1）。 */
    private void stubResolvableModel() {
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("claude-sonnet-4-6");
        m.setEnabled(true);
        when(modelMapper.selectOneByQuery(any())).thenReturn(m);
    }

    @Test
    @DisplayName("provider.type='anthropic' → true")
    void isAnthropic_true() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        assertThat(ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, "anthropic/claude-sonnet-4-6"))
            .as("判定链：ModelNameResolver.resolve → provider.type=='anthropic'")
            .isTrue();
    }

    @Test
    @DisplayName("provider.type='openai_compatible' → false（DeepSeek 不双计）")
    void isAnthropic_false_forOpenAiCompatible() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        assertThat(ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, "deepseek/deepseek-v4-flash"))
            .isFalse();
    }

    @Test
    @DisplayName("provider 未命中（selectOneById → null）→ false")
    void isAnthropic_false_whenProviderMissing() {
        stubResolvableModel();
        // providerMapper.selectOneById 未 stub → Mockito null
        assertThat(ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, "deepseek/deepseek-v4-flash"))
            .isFalse();
    }

    @Test
    @DisplayName("模型不可判定（resolve → null / modelName 空 / mapper 缺失）→ false")
    void isAnthropic_false_whenModelUnresolvable() {
        // modelName null/blank
        assertThat(ContextUsageCalculator.isAnthropic(null, null, null)).isFalse();
        assertThat(ContextUsageCalculator.isAnthropic(null, null, "   ")).isFalse();
        // mapper 缺失
        assertThat(ContextUsageCalculator.isAnthropic(null, mock(ProviderMapper.class), "some-model")).isFalse();
        // resolve → null（unstubbed selectOneByQuery → 兼容路径未命中）
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(java.util.List.of());
        assertThat(ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, "unknown-model"))
            .as("未命中模型 → 非 Anthropic（openai_compatible 语义）")
            .isFalse();
    }

    // ─────────────────────────── snapshot 单点（window + used + percentLeft） ───────────────────────────

    @Test
    @DisplayName("snapshot：窗口=模型 max_context + 非 anthropic used=input + percentLeft 算对（防双计）")
    void snapshot_resolvesWindowAndAppliesProtocolDispatch() {
        // GIVEN: 可解析模型（openai_compatible provider + max_context_tokens=2000）
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);
        // stubResolvableModel 未设 maxContextTokens → 补设 2000（模型级窗口权威值）
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("deepseek-v4-flash");
        m.setEnabled(true);
        m.setMaxContextTokens(2000);
        when(modelMapper.selectOneByQuery(any())).thenReturn(m);

        ContextUsageCalculator.Snapshot snap = ContextUsageCalculator.snapshot(
            modelMapper, providerMapper, "deepseek/deepseek-v4-flash",
            new com.nexusai.application.agent.tool.AgentUsage(1500L, 500L, 300L, 1000L, null, null, null));

        assertThat(snap.contextWindow())
            .as("窗口 = 模型 max_context_tokens=2000（未配才回落 1M）").isEqualTo(2000L);
        assertThat(snap.contextTokensUsed())
            .as("非 anthropic → 仅 input=1500（cacheRead 1000 已含 input，加会双计）").isEqualTo(1500L);
        assertThat(snap.percentLeft())
            .as("round((1-1500/2000)*100)=25").isEqualTo(25);
    }

    @Test
    @DisplayName("snapshot：usage null → used=0 + percentLeft null（NON_NULL 省略），窗口仍解析")
    void snapshot_nullUsage_zeroUsedNullPercent() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("deepseek-v4-flash");
        m.setEnabled(true);
        m.setMaxContextTokens(4000);
        when(modelMapper.selectOneByQuery(any())).thenReturn(m);

        ContextUsageCalculator.Snapshot snap = ContextUsageCalculator.snapshot(
            modelMapper, providerMapper, "deepseek/deepseek-v4-flash", null);

        assertThat(snap.contextWindow()).isEqualTo(4000L);
        assertThat(snap.contextTokensUsed()).as("usage null → used 0").isZero();
        assertThat(snap.percentLeft()).as("usage null → percentLeft null（省略）").isNull();
    }
}

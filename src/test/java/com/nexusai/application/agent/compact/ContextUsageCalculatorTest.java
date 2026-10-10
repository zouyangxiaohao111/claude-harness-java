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
    @DisplayName("Anthropic：input+cacheRead+cacheCreate 三字段和（三小票形态数据）")
    void compute_anthropicSumsAllThree() {
        // [ant-deepseek 双计修复] 形态维度显式化：三字段和要求「三小票」数字形态
        //（cache_read 远大于 input，Claude 原生）。原数据 (2000,500,300) 恰为「总小票」形态
        //（cr+cc ≤ input）→ 现走自适应只取 input（见 compute_totalFormIgnoresCacheEvenWhenAnthropicFlag）。
        assertThat(ContextUsageCalculator.computeContextTokensUsed(1000L, 9000L, 2000L, true))
            .as("Anthropic 三字段独立 → 三字段和 = 12000")
            .isEqualTo(12000L);
    }

    @Test
    @DisplayName("Anthropic：cache 为 null → 0 容错（无 cache 请求 / 旧行无 cache 列）")
    void compute_anthropicNullCacheFallsBackToInput() {
        assertThat(ContextUsageCalculator.computeContextTokensUsed(2000L, null, null, true))
            .as("Anthropic + cache null → input + 0 + 0")
            .isEqualTo(2000L);
        // [ant-deepseek 双计修复] 用三小票形态数据（cacheRead 大）表达「cacheCreate null → 0 补齐」
        assertThat(ContextUsageCalculator.computeContextTokensUsed(1000L, 5000L, null, true))
            .as("Anthropic + cacheCreate null（三小票）→ input + cacheRead + 0 = 6000")
            .isEqualTo(6000L);
    }

    @Test
    @DisplayName("openai_compatible：仅 input——加 cacheRead 双计 → 红（核心意图）")
    void compute_openaiCompatibleIgnoresCache() {
        // WHY: DeepSeek 走 openai_compatible，prompt_tokens 已含 cache hit；加 cacheRead 双计。
        assertThat(ContextUsageCalculator.computeContextTokensUsed(2000L, 500L, 300L, false))
            .as("openai_compatible → 仅 input（不双计 cache）")
            .isEqualTo(2000L);
    }

    @Test
    @DisplayName("[ant-deepseek 双计修复] anthropic 标志但「总小票」形态数字 → 只取 input（防 ×2）")
    void compute_totalFormIgnoresCacheEvenWhenAnthropicFlag() {
        // WHY: DeepSeek /anthropic 端点 provider.type=anthropic 但数字是 OpenAI 语义
        //（2026-10-10 真库实锤 input=397798=cache_read 396800+cache_creation 998）；
        // 按旧三字段和 = 795596 双计 → 压缩判定 ×2 虚高 → 用户报「瞬间压缩」。
        assertThat(ContextUsageCalculator.computeContextTokensUsed(397798L, 396800L, 998L, true))
            .as("input 已含 cache hit → 只取 input")
            .isEqualTo(397798L);
        // cc=0 轮（本轮增量未写入 cache，真库 09:41:13 行 345389/341760/0）同样只取 input
        assertThat(ContextUsageCalculator.computeContextTokensUsed(345389L, 341760L, 0L, true))
            .as("cc=0 总小票轮 → 同样只取 input（宽松判据覆盖）")
            .isEqualTo(345389L);
    }

    // ─────────────────────────── computeCacheHitRate 纯函数 ───────────────────────────

    @Test
    @DisplayName("Anthropic：cache 命中率 = read/(input+read+create) 三字段分母（三小票形态数据）")
    void cacheHitRate_anthropic_threeFieldDenominator() {
        // WHY: Claude usage 三字段独立 → 分母 = input + cache_read + cache_create（CC :651-654）；
        // [ant-deepseek 双计修复] 数据用三小票形态（cacheRead 9000 远大于 input 1000，Claude 原生）。
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, 9000L, 2000L, true))
            .as("read/(input+read+create) = 9000/12000 = 0.75")
            .isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("Anthropic：cache 字段 null → 0 容错（无 cache / 旧行无 cache 列）")
    void cacheHitRate_anthropic_nullCacheFields() {
        // WHY: cacheRead null → read=0 → 0（无命中）；cacheRead 非 null 但 cacheCreate null → 0 补齐。
        assertThat(ContextUsageCalculator.computeCacheHitRate(1000L, null, null, true))
            .as("cacheRead null → read=0 → 0")
            .isEqualTo(0d);
        // [ant-deepseek 双计修复] 三小票形态数据（cacheRead 9000 > input 2000）
        assertThat(ContextUsageCalculator.computeCacheHitRate(2000L, 9000L, null, true))
            .as("cacheCreate null（三小票）→ 分母 = input + read + 0 = 9000/11000")
            .isCloseTo(9000.0 / 11000.0, within(1e-9));
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

    @Test
    @DisplayName("[ant-deepseek 双计修复] 总小票形态命中率 = read/input（防三项分母算成一半）")
    void cacheHitRate_totalFormReadOverInput() {
        // WHY: ant deepseek 真数字 397798/396800/998：正确命中率 = 396800/397798 ≈ 99.75%；
        //   按三项分母 = 396800/795596 ≈ 49.87%（用户可见的「命中率显示成一半」假象）。
        assertThat(ContextUsageCalculator.computeCacheHitRate(397798L, 396800L, 998L, true))
            .as("read/input ≈ 0.9975（防 0.4987 回归）")
            .isCloseTo(396800.0 / 397798.0, within(1e-9));
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

    @Test
    @DisplayName("[ant-deepseek 双计修复] snapshot：anthropic 标志 + 总小票数字 → used=input（阈值链不 ×2）")
    void snapshot_totalFormUsedEqualsInput() {
        // WHY: 快照 used 是「当前上下文条」与压缩阈值共用的数字；ant deepseek 下旧逻辑
        // = 2×真实 → 界面显示虚高 + 提前压缩（用户 2026-10-10 报障的全链闭环点）。
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(p);
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("deepseek-flash");
        m.setEnabled(true);
        m.setMaxContextTokens(1048576);
        when(modelMapper.selectOneByQuery(any())).thenReturn(m);

        // AgentUsage 构造序 = (input, output, cacheCreation, cacheRead)；数字取自真库 09:59:51 行
        ContextUsageCalculator.Snapshot snap = ContextUsageCalculator.snapshot(
            modelMapper, providerMapper, "ds-zcw/deepseek-flash",
            new com.nexusai.application.agent.tool.AgentUsage(397798L, 745L, 998L, 396800L, null, null, null));

        assertThat(snap.contextTokensUsed())
            .as("总小票 → used=397798（非 795596）").isEqualTo(397798L);
        assertThat(snap.percentLeft())
            .as("round((1-397798/1048576)*100)=62").isEqualTo(62);
    }
}

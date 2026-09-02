package com.nexusai.application.agent.compact;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
}

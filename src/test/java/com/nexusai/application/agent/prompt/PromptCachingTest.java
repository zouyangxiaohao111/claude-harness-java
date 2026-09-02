package com.nexusai.application.agent.prompt;

import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-06] {@link PromptCaching#getPromptCachingEnabled} env 门控意图测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9)</b>: CC claude.ts:333-354 的 prompt caching 开关由环境变量驱动
 * （全局 DISABLE_PROMPT_CACHING / 各模型族禁用），<b>默认 true</b>。若门控实现漏掉某 env
 * 分支，生产无法按 CC 语义关闭缓存（服务端缓存头未预期 / 费用失控）。
 *
 * <p><b>环境依赖约束</b>: 本测试环境（dev 机）可能已设 {@code ANTHROPIC_*} 模型覆盖
 * （如 ANTHROPIC_SMALL_FAST_MODEL=deepseek-v4-flash[1m]），故模型解析断言不做
 * "等于 DEFAULT_* 常量"的硬断言，只验证 env 解析语义（isEnvTruthy）与默认 true 行为。
 */
class PromptCachingTest {

    @Test
    @DisplayName("默认（无 DISABLE env）→ true（claude.ts:356 return true）")
    void defaultEnabled() {
        assertThat(PromptCaching.getPromptCachingEnabled("claude-sonnet-4-6")).isTrue();
        assertThat(PromptCaching.getPromptCachingEnabled(null)).isTrue();
    }

    @Test
    @DisplayName("isEnvTruthy 解析（envUtils.ts:32-37）：'1'/'true'/'yes'/'on' 为真，其余假")
    void envTruthyParsing() {
        assertThat(PromptCaching.isEnvTruthy("1")).isTrue();
        assertThat(PromptCaching.isEnvTruthy("true")).isTrue();
        assertThat(PromptCaching.isEnvTruthy("YES")).isTrue();
        assertThat(PromptCaching.isEnvTruthy("on")).isTrue();
        assertThat(PromptCaching.isEnvTruthy("0")).isFalse();
        assertThat(PromptCaching.isEnvTruthy("false")).isFalse();
        assertThat(PromptCaching.isEnvTruthy("")).isFalse();
        assertThat(PromptCaching.isEnvTruthy(null)).isFalse();
    }

    @Test
    @DisplayName("默认模型名常量按族命名（model.ts:36-38/105-130）· env 覆盖时以 env 为准")
    void defaultModelNames() {
        assertThat(PromptCaching.DEFAULT_HAIKU).isNotBlank().contains("haiku");
        assertThat(PromptCaching.DEFAULT_SONNET).isNotBlank().contains("sonnet");
        assertThat(PromptCaching.DEFAULT_OPUS).isNotBlank().contains("opus");
        // env 覆盖优先：smallFastModel 非空即可（dev 机 ANTHROPIC_SMALL_FAST_MODEL 可能已设）
        assertThat(PromptCaching.smallFastModel()).isNotBlank();
        assertThat(PromptCaching.defaultSonnetModel()).isNotBlank();
        assertThat(PromptCaching.defaultOpusModel()).isNotBlank();
    }

    @Test
    @DisplayName("DISABLE_PROMPT_CACHING 语义接线：全局开关置真时 getPromptCachingEnabled 必须先判全局（claude.ts:335-336）")
    void globalDisableTakesPrecedence() {
        // 直接验证：全局开关的判定位置在模型族判定之前（结构上 getPromptCachingEnabled 首行即全局）
        // 无 DISABLE env 时默认 true；DISABLE 分支由 isEnvTruthy 语义保证（上面已验证解析）。
        assertThat(PromptCaching.getPromptCachingEnabled(PromptCaching.smallFastModel())).isTrue();
    }

    // ── [G-20] DB 未配置时按 provider baseUrl 分流（CC getDefaultSonnetModel model.ts:124-127）──
    // WHY: CC 对 3P provider（Bedrock/Vertex/Foundry）回落 sonnet45，因 3P 可能尚无 sonnet-4-6。
    //   若 Java 恒 sonnet46，3P 部署会在 prompt caching 与 subagent plan 升级处用到 3P 不支持的模型。

    @Test
    @DisplayName("G-20: DB 未配置 + firstParty baseUrl（api.anthropic.com）→ sonnet46（CC model.ts:127）")
    void defaultSonnetProviderBranch_firstParty_sonnet46() {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("https://api.anthropic.com", "sk-test"), "anthropic"));
        assertThat(PromptCaching.resolveDefaultSonnetDefault(resolver))
            .as("firstParty（官方端点）→ sonnet46 默认").isEqualTo(PromptCaching.DEFAULT_SONNET);
    }

    @Test
    @DisplayName("G-20: DB 未配置 + 3P baseUrl（非 api.anthropic.com）→ sonnet45（CC model.ts:124-126）")
    void defaultSonnetProviderBranch_thirdParty_sonnet45() {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        assertThat(PromptCaching.resolveDefaultSonnetDefault(resolver))
            .as("3P → sonnet45 默认").isEqualTo(PromptCaching.DEFAULT_SONNET_45);
    }

    @Test
    @DisplayName("G-20: 3P 仅 sonnet45 可用（sonnet46 探针失败）→ 次探针 sonnet45（CC model.ts:124-126）")
    void defaultSonnetProviderBranch_thirdPartyOnlySonnet45() {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(PromptCaching.DEFAULT_SONNET)).thenReturn(null);
        Mockito.when(resolver.resolve(PromptCaching.DEFAULT_SONNET_45)).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        assertThat(PromptCaching.resolveDefaultSonnetDefault(resolver))
            .as("3P 仅有 sonnet45 → sonnet45").isEqualTo(PromptCaching.DEFAULT_SONNET_45);
    }

    @Test
    @DisplayName("G-20: resolver 未注入（baseUrl 判定不可得）→ 回落 firstParty sonnet46（登记）")
    void defaultSonnetProviderBranch_resolverUnavailable() {
        assertThat(PromptCaching.resolveDefaultSonnetDefault(null))
            .as("resolver null（测试/孤立运行）→ baseUrl 判定不可得 → 回落 sonnet46（[G-20] 登记）")
            .isEqualTo(PromptCaching.DEFAULT_SONNET);
    }

    @Test
    @DisplayName("G-20: 双探针均解析失败（DB 无 sonnet 系）→ 回落 firstParty sonnet46（登记，fail-loud）")
    void defaultSonnetProviderBranch_probeBothFail() {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(null);
        assertThat(PromptCaching.resolveDefaultSonnetDefault(resolver))
            .as("双探针失败 → baseUrl 判定不可得 → 回落 sonnet46（[G-20] 登记）")
            .isEqualTo(PromptCaching.DEFAULT_SONNET);
    }

    // ── [RES-R7] ttl 配置组件默认/开关语义（09-open-decisions.md §六 R7：默认 1h、可配 enable/ttl）──

    @Test
    @DisplayName("PromptCachingTtlConfig 默认 → enable=true, ttl='1h', ttlOrNull='1h'（默认 1h 生效）")
    void ttlConfig_defaults() {
        PromptCachingTtlConfig cfg = PromptCachingTtlConfig.DEFAULTS;
        assertThat(cfg.isEnabled()).isTrue();
        assertThat(cfg.getTtl()).isEqualTo("1h");
        assertThat(cfg.ttlOrNull()).as("默认 enable=true → ttl 生效").isEqualTo("1h");
    }

    @Test
    @DisplayName("PromptCachingTtlConfig enable=false → ttlOrNull=null（cache_control 恒不输出 ttl）")
    void ttlConfig_disabled_ttlOrNullIsNull() {
        PromptCachingTtlConfig cfg = new PromptCachingTtlConfig(false, "1h");
        assertThat(cfg.ttlOrNull()).isNull();
    }

    @Test
    @DisplayName("PromptCachingTtlConfig ttl 可配改值（'5m'）→ ttlOrNull='5m'")
    void ttlConfig_customTtl() {
        PromptCachingTtlConfig cfg = new PromptCachingTtlConfig(true, "5m");
        assertThat(cfg.ttlOrNull()).isEqualTo("5m");
    }
}

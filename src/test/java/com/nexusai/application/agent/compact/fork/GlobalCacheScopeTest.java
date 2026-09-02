package com.nexusai.application.agent.compact.fork;

import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalCacheScope firstParty gate 单实现测试 · 对齐 CC {@code shouldUseGlobalCacheScope()}
 * (Open-ClaudeCode/src/utils/betas.ts:227-233 = {@code getAPIProvider() === 'firstParty' &&
 * !isEnvTruthy(CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS)})。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: RES-R4-1 验收 4（单实现不漂移）要求 manual
 * /compact 与主线程共用同一 firstParty 判定（REQ-R4-3）——否则 fork 与主线程 cacheScope 分配
 * 不一致 → cache key 前缀字节不同 → 缓存永不命中。本测试钉死单实现语义：
 * <ol>
 *   <li>firstParty（baseUrl 含 api.anthropic.com）→ true（boundary 模式可用）</li>
 *   <li>3P（非 api.anthropic.com baseUrl / null）→ false（默认 org 模式）</li>
 *   <li>CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS truthy → false（对齐 betas.ts:227-233 第二项）</li>
 * </ol>
 *
 * <p>env 不可注入（System.getenv 只读）→ 测 package-private 核心重载
 * {@code shouldUseGlobalCacheScope(config, disableBetas)}；公共 1 参重载与主线程
 * LlmAgentLoop.useGlobalCacheScope 均委托该核心（REQ-R4-1 验收 4 单实现）。
 */
class GlobalCacheScopeTest {

    private static final String FIRST_PARTY = "https://api.anthropic.com";
    private static final String THIRD_PARTY = "https://gateway.example.com/v1";

    @Test
    @DisplayName("firstParty baseUrl（含 api.anthropic.com）→ true")
    void firstPartyBaseUrl_true() {
        assertThat(GlobalCacheScope.shouldUseGlobalCacheScope(new ProviderConfig(FIRST_PARTY, "key"), null))
            .isTrue();
    }

    @Test
    @DisplayName("3P baseUrl（非 api.anthropic.com）→ false")
    void thirdPartyBaseUrl_false() {
        assertThat(GlobalCacheScope.shouldUseGlobalCacheScope(new ProviderConfig(THIRD_PARTY, "key"), null))
            .isFalse();
    }

    @Test
    @DisplayName("null config（3P 默认，OPD-SP-27）→ false")
    void nullConfig_false() {
        assertThat(GlobalCacheScope.shouldUseGlobalCacheScope(null, null)).isFalse();
    }

    @Test
    @DisplayName("firstParty 但 CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1 → false（betas.ts:227-233 第二项）")
    void disableExperimentalBetasTruthy_false() {
        assertThat(GlobalCacheScope.shouldUseGlobalCacheScope(new ProviderConfig(FIRST_PARTY, "key"), "1"))
            .isFalse();
    }

    @Test
    @DisplayName("firstParty 且 disableBetas 空白/未禁用 → true（公共 1 参重载委托核心）")
    void disableBetasBlank_true() {
        assertThat(GlobalCacheScope.shouldUseGlobalCacheScope(new ProviderConfig(FIRST_PARTY, "key"), "  "))
            .isTrue();
    }
}

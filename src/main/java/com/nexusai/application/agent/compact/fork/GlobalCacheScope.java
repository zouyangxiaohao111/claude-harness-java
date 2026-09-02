package com.nexusai.application.agent.compact.fork;

/**
 * firstParty fork 缓存共享 gate 单实现 · 对齐 CC {@code shouldUseGlobalCacheScope()}
 * (Open-ClaudeCode/src/utils/betas.ts:227-233 = {@code getAPIProvider() === 'firstParty' &&
 * !isEnvTruthy(CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS)})。
 *
 * <p><b>WHY 存在（RES-R4-1 · 单实现约束 REQ-R4-1 验收 4）</b>: CC 是进程级全局函数，Java 不得
 * 出现第二份字节漂移实现。主线程（LlmAgentLoop auto 路径）与 fork 生产路径（manual /compact）
 * 的 firstParty 判定必须同源 —— 本类承载唯一判定，LlmAgentLoop.useGlobalCacheScope 委托本类，
 * ToolRegistrationConfig.handleCompactCommand 也经本类求值注入 CompactCommandContext。
 *
 * <p><b>OPD-SP-27</b>: Java 默认 3P → boundary 不插入。firstParty 判定 = baseUrl 含
 * {@code api.anthropic.com}（官方 Anthropic Messages API 端点）且未禁用 experimental betas。
 */
public final class GlobalCacheScope {

    private GlobalCacheScope() {
    }

    /**
     * firstParty gate 判定 · CC original: {@code shouldUseGlobalCacheScope()}
     * (utils/betas.ts:227-233)。
     *
     * @param config provider 运行时配置（baseUrl 判定 firstParty；null → 3P）
     * @return true 时 SystemPromptAssembler 插入 boundary + splitSysPromptPrefix boundary 模式
     */
    public static boolean shouldUseGlobalCacheScope(com.nexusai.infra.llm.ProviderConfig config) {
        return shouldUseGlobalCacheScope(config, System.getenv("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"));
    }

    /**
     * firstParty gate 判定（env 注入核心 · 测试可注入 disableBetas 值）· 对齐 CC
     * {@code shouldUseGlobalCacheScope()}（betas.ts:227-233 = {@code getAPIProvider()==='firstParty' &&
     * !isEnvTruthy(CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS)}）。
     *
     * <p><b>单实现约束</b>: 公共 {@link #shouldUseGlobalCacheScope(ProviderConfig)} 与主线程
     * LlmAgentLoop.useGlobalCacheScope 均委托本核心，判定逻辑仅此一处（REQ-R4-1 验收 4）。
     *
     * @param config        provider 运行时配置（baseUrl 判定 firstParty；null → 3P）
     * @param disableBetas  CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS 原始环境值（null/空白 → 未禁用）
     * @return true 时 SystemPromptAssembler 插入 boundary + splitSysPromptPrefix boundary 模式
     */
    static boolean shouldUseGlobalCacheScope(com.nexusai.infra.llm.ProviderConfig config, String disableBetas) {
        if (disableBetas != null) {
            String v = disableBetas.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on")) {
                return false;
            }
        }
        String baseUrl = config != null ? config.baseUrl() : null;
        return baseUrl != null && baseUrl.contains("api.anthropic.com");
    }
}

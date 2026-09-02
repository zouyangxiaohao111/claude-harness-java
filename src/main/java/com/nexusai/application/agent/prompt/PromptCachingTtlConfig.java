package com.nexusai.application.agent.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prompt caching 1h TTL 配置 · 用户拍板 RES-R7（09-open-decisions.md §六 R7）：
 * <ul>
 *   <li><b>默认 1h 生效</b>：enable=true, ttl='1h'（对齐 CC {@code getCacheControl} 输出
 *       {@code ttl:'1h'}，claude.ts:371）</li>
 *   <li><b>不做 CC 用户资格门控</b>：CC {@code should1hCacheTTL}（claude.ts:393-434）的
 *       ant/订阅+allowlist 资格判定在 Java 省略（用户拍板），仅剩可配置 enable/ttl 值</li>
 *   <li><b>仅 Anthropic 有效</b>：cache_control 由 {@link SystemPromptBlocksBuilder}
 *       （仅 AnthropicSdkProvider 消费）输出，OpenAI 不适用 Anthropic cache_control ttl</li>
 * </ul>
 *
 * <p>静态 {@link #current()} 由 Spring 启动期注册（{@link PromptCachingTtlConfigBootstrap}
 * {@code @EnableConfigurationProperties} 构造后调用 {@link #register}），yml 未配置时字段默认
 * （enable=true, ttl='1h'）保持默认 1h 生效；测试可临时 {@link #register} 覆盖
 * （仿 {@code MicroCompactor#setTimeBasedMCConfig} 测试钩子）。默认 {@link #DEFAULTS}。
 *
 * <p><b>注册模式</b>: 对齐 {@code BundledSkillFeatureFlags}（record + DEFAULTS +
 * @EnableConfigurationProperties）与 {@code CompactEnvProperties}（plain class + setter 绑定）。
 * 用 plain class（非 record）承载默认值 —— record 构造器绑定在 yml 未设时布尔落 false，
 * 会静默关闭 ttl（与"默认 1h 生效"相悖）。
 */
@ConfigurationProperties(prefix = "nexusai.prompt-caching")
public class PromptCachingTtlConfig {

    /** 默认配置：enable=true, ttl='1h'（用户拍板默认 1h 生效，不做资格门控）。 */
    public static final PromptCachingTtlConfig DEFAULTS = new PromptCachingTtlConfig();

    private static volatile PromptCachingTtlConfig current = DEFAULTS;

    /** 1h TTL 是否启用 · 默认 true（CC should1hCacheTTL 的 Java 简化：不做用户资格门控，默认生效）。 */
    private boolean enabled = true;

    /** 缓存 TTL 值 · 默认 '1h'（CC getCacheControl ttl:'1h'，claude.ts:371）；可配如 '5m'。 */
    private String ttl = "1h";

    public PromptCachingTtlConfig() {}

    public PromptCachingTtlConfig(boolean enabled, String ttl) {
        this.enabled = enabled;
        this.ttl = ttl;
    }

    /** 当前生效配置（默认 {@link #DEFAULTS}；Spring 启动后为 yml 绑定实例）。 */
    public static PromptCachingTtlConfig current() {
        return current;
    }

    /** 注册当前配置（null → 复位默认）· 供 Spring bootstrap 与测试钩子。 */
    public static void register(PromptCachingTtlConfig config) {
        current = config != null ? config : DEFAULTS;
    }

    /**
     * 生效的 ttl 值 · 对齐 CC {@code should1hCacheTTL(querySource) && {ttl:'1h'}}
     * （claude.ts:371）的 Java 简化：enabled 时返回 ttl 值，关闭时返回 null
     * （null → cache_control 不输出 ttl 字段，REQ-R7-3）。
     *
     * @return ttl 值（如 '1h'/'5m'）；enable=false → null
     */
    public String ttlOrNull() {
        return enabled ? ttl : null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTtl() {
        return ttl;
    }

    public void setTtl(String ttl) {
        this.ttl = ttl;
    }
}

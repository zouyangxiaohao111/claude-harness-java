package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt caching ttl 配置注册 · {@code @EnableConfigurationProperties} 把 application.yml
 * {@code nexusai.prompt-caching.*} 绑定为 {@link PromptCachingTtlConfig}，并在此构造器注册为静态
 * {@code current}（供静态工具类 {@link SystemPromptBlocksBuilder#getCacheControl} 消费）。
 *
 * <p><b>WHY（用户拍板 RES-R7，09-open-decisions.md §六 R7）</b>: CC 的 ttl 由
 * {@code should1hCacheTTL} 的 GrowthBook allowlist + 用户资格判定驱动；Java 简化为配置文件
 * enable/ttl 值（默认 enable=true, ttl='1h'）。yml 未配置 → 字段默认值即默认 1h 生效。
 *
 * <p><b>注册模式</b>: 对齐 {@code BundledSkillFeatureFlagsConfig} / {@code CompactThresholdConfig}
 * （@EnableConfigurationProperties）。静态 current 承载对齐 {@code MicroCompactor}
 * {@code static volatile TimeBasedMCConfig timeBasedMCConfig = DEFAULTS}。
 */
@Configuration
@EnableConfigurationProperties(PromptCachingTtlConfig.class)
public class PromptCachingTtlConfigBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PromptCachingTtlConfigBootstrap.class);

    public PromptCachingTtlConfigBootstrap(PromptCachingTtlConfig config) {
        PromptCachingTtlConfig.register(config);
        if (log.isDebugEnabled()) {
            log.debug("[PromptCachingTtlConfigBootstrap] 注册 prompt caching ttl 配置：enabled={} ttl={}"
                    + "（默认 1h 生效；enable=false → cache_control 不输出 ttl，09 §六 R7）",
                config.isEnabled(), config.getTtl());
        }
    }
}

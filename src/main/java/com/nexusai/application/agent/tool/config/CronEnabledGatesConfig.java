package com.nexusai.application.agent.tool.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cron 门控配置接线 · 仿 {@code BundledSkillFeatureFlagsConfig}
 * （@EnableConfigurationProperties + record @ConfigurationProperties）：
 * 注册 {@link CronEnabledGates} bean，让 application.yml
 * {@code nexusai.feature.agent-trigger-cron} / {@code nexusai.feature.cron-durable}
 * 被类型化绑定（默认 true，对齐 CC prompt.ts:36-45/:56-62）。
 */
@Configuration
@EnableConfigurationProperties(CronEnabledGates.class)
public class CronEnabledGatesConfig {

    private static final Logger log = LoggerFactory.getLogger(CronEnabledGatesConfig.class);

    public CronEnabledGatesConfig(CronEnabledGates gates) {
        if (log.isDebugEnabled()) {
            log.debug("[CronEnabledGatesConfig] 加载 Cron 门控：agentTriggerCron={} cronDurable={}（对齐 CC prompt.ts:36-45 isKairosCronEnabled + :56-62 isDurableCronEnabled，默认 true）",
                gates.agentTriggerCron(), gates.cronDurable());
        }
    }
}

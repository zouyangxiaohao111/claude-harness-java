package com.nexusai.application.agent.compact;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 压缩阈值体系 Spring 配置 · 注册 {@link CompactEnvProperties}（@ConfigurationProperties）
 * 与 {@link CompactThresholdSystem} 单例 bean。
 *
 * <p><b>WHY（OD-16 裁决）</b>: CC 的 3 个 override env（CLAUDE_CODE_AUTO_COMPACT_WINDOW /
 * CLAUDE_AUTOCOMPACT_PCT_OVERRIDE / CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE）经 Spring
 * @ConfigurationProperties 映射为 {@link CompactEnvProperties}（prefix = claude），
 * 由 {@link CompactThresholdSystem} 消费 —— 生产可经环境变量调阈值/blocking 窗口。
 *
 * <p>注册模式对齐既有 {@code McpConfig}（@EnableConfigurationProperties + @Bean）。
 */
@Configuration
@EnableConfigurationProperties(CompactEnvProperties.class)
public class CompactThresholdConfig {

    /**
     * 注册阈值体系单例 · 供 AutoCompactor / AgentLoopContext 注入。
     *
     * <p>model 上下文窗口解析器（DB model 元数据）由 {@link com.nexusai.application.agent.loop.AgentLoopContextFactory}
     * 注入（其持有 ModelMapper/ProviderMapper，对齐旧 computeBudgetFromGates 语义）。
     */
    @Bean
    public CompactThresholdSystem compactThresholdSystem(CompactEnvProperties env) {
        return new CompactThresholdSystem(env);
    }

    /**
     * 注册压缩配置实时读源单例 · [V52 B1-5] 供 AutoCompactor / ReactiveCompactor /
     * MicroCompactor / ContextCollapse / SessionMemoryService / LlmAgentLoop 注入。
     *
     * <p>SettingsMapper 经 {@code @Autowired(required=false) setSettingsMapper} 自动注入
     * （SettingsMapper 为 MyBatis-Flex mapper @Component）；无 Spring 上下文 / mapper 缺失
     * 时回落 null（各消费方零行为变化）。
     */
    @Bean
    public CompactSettingsResolver compactSettingsResolver() {
        return new CompactSettingsResolver();
    }
}

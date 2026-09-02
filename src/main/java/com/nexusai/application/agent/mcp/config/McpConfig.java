package com.nexusai.application.agent.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 配置接线 · 对齐 CC {@code services/mcp/config.ts}。
 *
 * <p>R32-b7a-3：通过 {@link EnableConfigurationProperties} 注册 {@link McpProperties} bean，
 * 让 application.yml {@code nexusai.mcp.*} 段可被类型化注入。stub Tool 通过
 * {@code @ConditionalOnProperty} 单独访问 {@code nexusai.mcp.features.*} 字段（不需要
 * McpProperties 注入）；本 config 仅暴露 bean 供后续 {@code McpToolPool} 等组件按需注入。
 *
 * <p>注意：故意不在此处添加任何 {@code @Bean} 方法（CLAUDE.md 规则 3：外科手术式修改）。
 * stub Tool 自身已 {@code @Component} 自动注册，避免双重注册冲突。
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    public McpConfig(McpProperties properties) {
        log.info("[McpConfig] 加载 MCP 配置：enabled={} servers={} features={}",
            properties.enabled(),
            properties.servers() == null ? 0 : properties.servers().size(),
            properties.features());
    }
}
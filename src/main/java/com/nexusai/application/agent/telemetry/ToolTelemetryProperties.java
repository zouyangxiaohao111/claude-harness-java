package com.nexusai.application.agent.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tool telemetry 配置 · 对齐 CC env 变量 {@code OTEL_LOG_TOOL_DETAILS} +
 * {@code CLAUDE_CODE_WORKSPACE_HOST_PATAILS}.
 *
 * <p><b>[R32-b12 D-11/D-12]</b> 严格对齐 CC Open-ClaudeCode/src/utils/telemetry/instrumentation.ts:86-214
 * bootstrapTelemetry 模式 (环境变量读取 + defaults). Java 端用 Spring Boot
 * {@code @ConfigurationProperties(prefix = "nexusai.telemetry.otel")} 注入.
 *
 * <p>默认值（按 CLAUDE.md 规则 11 匹配 CC 真源）：
 * <ul>
 *   <li>{@link #logToolDetails} = false (CC env {@code OTEL_LOG_TOOL_DETAILS} 默认关闭 ——
 *       tool parameters 可能含敏感内容 (bash command / MCP server name)，opt-in 而非 opt-out)</li>
 *   <li>{@link #workspaceHostPaths} = "" (CC env {@code CLAUDE_CODE_WORKSPACE_HOST_PATHS} 默认空)</li>
 *   <li>{@link #enabled} = true (OTel 默认开启，{@code OTEL_SDK_DISABLED=true} 时关闭)</li>
 *   <li>{@link #serviceName} = "nexusai-backend" (CC 默认 service.name = "claude-code")</li>
 * </ul>
 *
 * <h2>配置示例（application.yml）</h2>
 * <pre>{@code
 * nexusai:
 *   telemetry:
 *     otel:
 *       enabled: true
 *       log-tool-details: false
 *       workspace-host-paths: ""
 *       service-name: nexusai-backend
 * }</pre>
 *
 * <h2>设计依据（CC 真源对照）</h2>
 * <ul>
 *   <li>isToolDetailsLoggingEnabled() ↔ {@code OTEL_LOG_TOOL_DETAILS === 'true'}
 *       (Open-ClaudeCode/src/services/analytics/metadata.ts:86-88)</li>
 *   <li>workspace.host_paths ↔ {@code CLAUDE_CODE_WORKSPACE_HOST_PATHS.split('|')}
 *       (Open-ClaudeCode/src/utils/telemetry/events.ts:127-130)</li>
 * </ul>
 *
 * @see Telemetry
 * @since R32-b12
 */
@ConfigurationProperties(prefix = "nexusai.telemetry.otel")
public class ToolTelemetryProperties {

    /**
     * OTel 总开关. 默认 true (Spring Boot 启用 Actuator 时默认开启).
     * 对齐 CC OTel SDK 默认 enabled 状态.
     */
    private boolean enabled = true;

    /**
     * 是否在 OTel 事件中记录工具参数详情（bash command / file_path / MCP server name 等）.
     * 默认 false（opt-in）. 严格对齐 CC {@code OTEL_LOG_TOOL_DETAILS} 默认关闭 ——
     * 工具参数可能含敏感内容（命令 / 凭据），不应无脑写入 telemetry.
     */
    private boolean logToolDetails = false;

    /**
     * 工作目录 host 路径数组（{@code |} 分隔）. 对齐 CC {@code CLAUDE_CODE_WORKSPACE_HOST_PATHS}.
     * Java 端为简化版：单一字符串，多路径由应用层 split.
     */
    private String workspaceHostPaths = "";

    /**
     * OTel Resource service.name. 对齐 CC service.name = "claude-code".
     * Java 端默认 "nexusai-backend".
     */
    private String serviceName = "nexusai-backend";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogToolDetails() {
        return logToolDetails;
    }

    public void setLogToolDetails(boolean logToolDetails) {
        this.logToolDetails = logToolDetails;
    }

    public String getWorkspaceHostPaths() {
        return workspaceHostPaths;
    }

    public void setWorkspaceHostPaths(String workspaceHostPaths) {
        this.workspaceHostPaths = workspaceHostPaths == null ? "" : workspaceHostPaths;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName == null || serviceName.isBlank()
            ? "nexusai-backend" : serviceName;
    }
}
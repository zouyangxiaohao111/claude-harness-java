package com.nexusai.application.agent.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * MCP 配置 · 对齐 application.yml {@code nexusai.mcp.*} 段。
 *
 * <p>R32-b7a-3 方案 A：5 个 stub Tool 通过 {@code @ConditionalOnProperty}
 * 独立访问 {@code nexusai.mcp.features.*} 字段；本 record 提供类型化访问入口，
 * 供后续 {@code McpToolPool} 等组件按需注入。
 *
 * <p>字段清单（来源 r32-b7a-3-explore.md §6 配置项设计）：
 * <ul>
 *   <li>{@link #enabled()} — 全局 MCP 开关（默认 false）</li>
 *   <li>{@link #pool()} — MCP tool pool 并发/超时配置</li>
 *   <li>{@link #servers()} — MCP server 列表（按 nexusai.mcp.servers[]）</li>
 *   <li>{@link #features()} — 各 feature flag（对齐 stub Tool 的 @ConditionalOnProperty）</li>
 *   <li>{@link #auth()} — OAuth / Authorization Server discovery</li>
 *   <li>{@link #resources()} — resources/list + resources/subscribe</li>
 *   <li>{@link #prompts()} — prompts/list</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "nexusai.mcp")
public record McpProperties(
    boolean enabled,
    // [impl-I-3 T1] channel 全局 runtime 开关 · CC original: isChannelsEnabled()
    //   (channelAllowlist.ts:51-53, GrowthBook 'tengu_harbor' 默认 false)。默认 false →
    //   --channels 是 no-op、无 handler 注册。
    boolean channelsEnabled,
    // [impl-I-3 T6] channel permission relay 开关 · CC original: isChannelPermissionRelayEnabled()
    //   (channelPermissions.ts:36-38, GrowthBook 'tengu_harbor_permissions' 默认 false)。
    //   独立于 channelsEnabled（CC「no bake time if it goes out tomorrow」）。
    boolean channelsPermissionRelayEnabled,
    Pool pool,
    List<Server> servers,
    Features features,
    Auth auth,
    Resources resources,
    Prompts prompts,
    Policy policy,
    Output output
) {

    /**
     * MCP tool pool 配置（并发调用 + 超时 + 同 server 串行）。
     */
    public record Pool(
        int maxConcurrentCalls,
        long callTimeoutMs,
        boolean serializeSameServer
    ) {}

    /**
     * 单个 MCP server 配置（对齐 CC {@code MCPServerConnection}）。
     *
     * <p>transport 可选 {@code stdio / sse / http / ws / sdk / claudeai-proxy}，
     * command/args/env 用于 stdio，url 用于 http/sse/ws。
     */
    public record Server(
        String name,
        String transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        boolean enabled
    ) {}

    /**
     * Feature flags — 5 个 stub Tool 的开关。
     *
     * <p>与 stub 类 {@code @ConditionalOnProperty} 一一对应。
     */
    public record Features(
        boolean webBrowserTool,
        boolean listPeersTool,
        boolean sendUserFileTool,
        boolean pushNotificationTool,
        boolean subscribePrTool
    ) {}

    /**
     * OAuth / Authorization Server discovery 配置。
     */
    public record Auth(
        boolean oauthEnabled,
        long asDiscoveryTimeoutMs
    ) {}

    /**
     * MCP resources 端点配置。
     */
    public record Resources(
        boolean listEnabled,
        boolean subscribeEnabled
    ) {}

    /**
     * MCP prompts 端点配置。
     */
    public record Prompts(
        boolean listEnabled
    ) {}

    /**
     * MCP 策略过滤配置 · 对齐 CC policySettings（config.ts:364-551）。
     *
     * <p>Java 对 CC {@code allowedMcpServers / deniedMcpServers / allowManagedMcpServersOnly}
     * （settings/types.ts:417-434）的模拟。缺省 null/空 → 全放行（对齐 CC
     * 「无 allowlist 限制即允许」config.ts:427-429）。
     */
    public record Policy(
        List<Entry> allowedMcpServers,
        List<Entry> deniedMcpServers,
        boolean allowManagedMcpServersOnly
    ) {}

    /**
     * 策略条目 · 对齐 CC AllowedMcpServerEntrySchema / DeniedMcpServerEntrySchema
     * （settings/types.ts:115-158 / 164-...）：serverName / serverCommand / serverUrl
     * 三选一（exactly one of）。缺省空 = 无限制。
     */
    public record Entry(
        String serverName,
        List<String> serverCommand,
        String serverUrl
    ) {
        public static Entry byName(String name) {
            return new Entry(name, null, null);
        }

        public static Entry byCommand(List<String> command) {
            return new Entry(null, command, null);
        }

        public static Entry byUrl(String url) {
            return new Entry(null, null, url);
        }
    }

    /**
     * MCP 大结果输出配置 · CC original: {@code getMaxMcpOutputTokens()}（mcpValidation.ts:26-47，
     * env {@code MAX_MCP_OUTPUT_TOKENS} > growthbook {@code tengu_satin_quoll.mcp_tool} > 默认 25000）。
     *
     * <p>[impl-I-4 T5] Java 无 growthbook → yml {@code nexusai.mcp.output.max-tokens} 承接，
     * env 覆盖在 yml placeholder {@code ${MAX_MCP_OUTPUT_TOKENS:25000}} 处理（Spring 优先级
     * env > placeholder 默认）。
     */
    public record Output(int maxMcpOutputTokens) {}
}
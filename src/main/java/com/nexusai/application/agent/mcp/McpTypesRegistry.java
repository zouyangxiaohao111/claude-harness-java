package com.nexusai.application.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP config types registry · 对齐 CC services/mcp/types.ts.
 *
 * <p>CC source: services/mcp/types.ts (258 LOC).
 * 7 ConfigScope enum + 6 Transport enum + 8 McpServerConfig variants (union) +
 * ScopedMcpServerConfig + 5 McpServerConnection status + SerializedClient/State records
 * （[G3] SerializedTool record 已删除 —— MCP input schema 经 Tool.inputJSONSchema 接口承载）。
 */
public final class McpTypesRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpTypesRegistry.class);

    /** CC ConfigScope enum (7 values). */
    public enum ConfigScope { LOCAL, USER, PROJECT, DYNAMIC, ENTERPRISE, CLAUDEAI, MANAGED }

    /** CC Transport enum (6 values). */
    public enum Transport { STDIO, SSE, SSE_IDE, HTTP, WS, SDK }

    /**
     * CC McpXaaConfig (just a flag) — CC types.ts:41 McpXaaConfigSchema 结构镜像。
     *
     * <p>[impl-I-2 R2.2 闭环] XAA 分支不实现（Q-07 已删 Xaa/XaaIdpLogin），本 record 保留作
     * CC types.ts 结构镜像，字段恒 unused；未来接入 performMCPXaaAuth 需恢复全链（open-decisions Q-07）。
     */
    public record McpXaaConfig(boolean enabled) {}

    /**
     * CC McpOAuthConfig — CC types.ts:45-54 结构镜像。
     *
     * <p>[impl-I-2 R2.2 闭环] {@code xaa} 字段为 CC types.ts:54 结构镜像（XAA 分支不实现，恒 unused）；
     * clientId/callbackPort/authServerMetadataUrl 供 T4 TransportConfig.auth 通道（MCP-I-2）与配置层
     * oauth.* 段解析（归 MCP-I-1 配置批）。
     */
    public record McpOAuthConfig(String clientId, Integer callbackPort, String authServerMetadataUrl,
                                   McpXaaConfig xaa) {
        public static McpOAuthConfig empty() {
            return new McpOAuthConfig(null, null, null, null);
        }
    }

    /** CC McpStdioServerConfig. */
    public record McpStdioServerConfig(String command, List<String> args, Map<String, String> env) implements McpServerConfig {
    @Override public String type() { return "stdio"; }
}

    /** CC McpSSEServerConfig. */
    public record McpSSEServerConfig(String url, Map<String, String> headers,
                                       String headersHelper, McpOAuthConfig oauth) implements McpServerConfig {
    @Override public String type() { return "sse"; }
}

    /** CC McpSSEIDEServerConfig (internal IDE). */
    public record McpSSEIDEServerConfig(String url, String ideName, Boolean ideRunningInWindows) {}

    /** CC McpWebSocketIDEServerConfig (internal IDE). */
    public record McpWebSocketIDEServerConfig(String url, String ideName, String authToken,
                                                Boolean ideRunningInWindows) {}

    /** CC McpHTTPServerConfig. */
    public record McpHTTPServerConfig(String url, Map<String, String> headers,
                                       String headersHelper, McpOAuthConfig oauth) implements McpServerConfig {
    @Override public String type() { return "http"; }
}

    /** CC McpWebSocketServerConfig. */
    public record McpWebSocketServerConfig(String url, Map<String, String> headers,
                                            String headersHelper) implements McpServerConfig {
    @Override public String type() { return "ws"; }
}

    /** CC McpSdkServerConfig. */
    public record McpSdkServerConfig(String name) implements McpServerConfig {
    @Override public String type() { return "sdk"; }
}

    /** CC McpClaudeAIProxyServerConfig. */
    public record McpClaudeAIProxyServerConfig(String url, String id) {}

    /** CC McpServerConfig union (discriminated via type field; non-sealed for compatibility). */
    public interface McpServerConfig {
        String type();
    }

    /** CC ScopedMcpServerConfig. */
    public record ScopedMcpServerConfig(McpServerConfig config, ConfigScope scope,
                                          String pluginSource) {}

    /** CC ConnectedMCPServer. */
    public record ConnectedMCPServer(Object client, String name, String type,
                                       Map<String, Object> capabilities,
                                       Map<String, Object> serverInfo, String instructions,
                                       ScopedMcpServerConfig config, Runnable cleanup) {}

    /** CC FailedMCPServer. */
    public record FailedMCPServer(String name, ScopedMcpServerConfig config, String error) implements MCPServerConnection {
    @Override public String type() { return "failed"; }
}

    /** CC NeedsAuthMCPServer. */
    public record NeedsAuthMCPServer(String name, ScopedMcpServerConfig config) implements MCPServerConnection {
    @Override public String type() { return "needs-auth"; }
}

    /** CC PendingMCPServer. */
    public record PendingMCPServer(String name, ScopedMcpServerConfig config,
                                    Integer reconnectAttempt, Integer maxReconnectAttempts) implements MCPServerConnection {
    @Override public String type() { return "pending"; }
}

    /** CC DisabledMCPServer. */
    public record DisabledMCPServer(String name, ScopedMcpServerConfig config) implements MCPServerConnection {
    @Override public String type() { return "disabled"; }
}

    /** CC MCPServerConnection (discriminated via type field). */
    public interface MCPServerConnection {
        String type();
    }

    /** CC SerializedClient. */
    public record SerializedClient(String name, String type, Map<String, Object> capabilities) {}

    /** CC MCPCliState（Java 端保留 client/configs/resources 视图，tools 已迁出）. */
    public record MCPCliState(List<SerializedClient> clients, Map<String, ScopedMcpServerConfig> configs,
                                 Map<String, List<Map<String, Object>>> resources,
                                 Map<String, String> normalizedNames) {}

    public static Map<String, String> normalizedNames() { return new LinkedHashMap<>(); }

    public McpTypesRegistry(Supplier<Map<String, ScopedMcpServerConfig>> configSupplier) {
        Objects.requireNonNull(configSupplier);
    }
}

package com.nexusai.application.agent.mcp;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MCP transport 工厂 · 显式 type 判别 · 对齐 CC MCPConnectionManager transports 路由
 * + types.ts:124-135 8 传输 union。
 *
 * <p>L1 决策表（显式 {@code config.type()}，不再用 URL 前缀推断——旧实现把 command 当 url 读是 bug）:
 * <ul>
 *   <li>{@code stdio} → StdioMcpTransport（type 缺省也走 stdio，types.ts:30 backwards compatibility）</li>
 *   <li>{@code sse} → SseMcpTransport</li>
 *   <li>{@code http} → HttpMcpTransport</li>
 *   <li>{@code ws} → WsMcpTransport</li>
 *   <li>{@code sdk} → SdkMcpTransport（SDK 进程内承载，R3 最小 smoke）</li>
 *   <li>{@code claudeai-proxy} → 抛 IllegalArgumentException + TODO（Q-26：Java web 无
 *       claude.ai org 场景，不实现）</li>
 *   <li>type 为 null（旧 REST 请求无 type）→ 按 command 非空推导 stdio（back-compat，仅过渡）</li>
 * </ul>
 */
@Component
public class McpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(McpTransportFactory.class);

    /**
     * [S2] MCP OAuth Bearer 提供者 · null = 未接线（测试/无认证 server）→ transport 不注入
     * Authorization 头（no-op，保持既有行为）。
     */
    private McpAuthHeaderProvider authHeaderProvider;

    /** 测试/无 Spring 场景：无认证 provider（transport 不注入 Bearer）。 */
    public McpTransportFactory() {
    }

    /** [S2] 生产接线：Spring 注入 McpAuthHeaderProvider（@Autowired(required=false) 容错）。 */
    @Autowired(required = false)
    public void setAuthHeaderProvider(McpAuthHeaderProvider authHeaderProvider) {
        this.authHeaderProvider = authHeaderProvider;
        if (log.isDebugEnabled()) {
            log.debug("[McpTransportFactory] 注入 McpAuthHeaderProvider（{}）", authHeaderProvider != null);
        }
    }

    public McpTransport create(McpTransport.TransportConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("TransportConfig required");
        }
        String type = config.type();
        if (type == null || type.isBlank()) {
            // 旧请求无 type → 推导兼容：command 非空 → stdio（仅过渡，显式 type 为主）
            if (config.command() != null && !config.command().isBlank()) {
                log.warn("[McpTransportFactory] TransportConfig 缺 type，按 command 推导 stdio（back-compat）server={}",
                    config.serverName());
                return new StdioMcpTransport();
            }
            throw new IllegalArgumentException(
                "Cannot infer transport: config has no type and no command. config=" + config);
        }
        return switch (type) {
            case "stdio" -> new StdioMcpTransport();
            case "sse" -> new SseMcpTransport(authHeaderProvider);
            case "http" -> new HttpMcpTransport(authHeaderProvider);
            case "ws" -> new WsMcpTransport(authHeaderProvider);
            case "sdk" -> new SdkMcpTransport(config.serverName());
            case "claudeai-proxy" -> throw new IllegalArgumentException(
                "claudeai-proxy transport 未实现：Java web 后端无 claude.ai 组织场景（Q-26 TODO）");
            default -> throw new IllegalArgumentException(
                "Unknown MCP transport type: " + type
                    + ". Expected one of: stdio, sse, sse-ide, ws-ide, http, ws, sdk, claudeai-proxy");
        };
    }

    /** 便捷：按类型 + command 构造（测试/工具用）. */
    public McpTransport createByFields(String type, String command, List<String> args) {
        return create(new McpTransport.TransportConfig(command, args, null, null, null, type));
    }
}

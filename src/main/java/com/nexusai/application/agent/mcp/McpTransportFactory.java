package com.nexusai.application.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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
 *   <li>{@code in-process} → 进程内 server 的客户端侧 transport（{@link InProcessMcpTransport}
 *       linked pair 的 a 侧）· 对齐 CCB {@code client.ts:929-948} 的 computer-use in-process 接线</li>
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

    /**
     * 进程内 MCP server 注册表 · key = server 名，value = <b>客户端侧 transport 工厂</b>。
     *
     * <p><b>WHY 需要</b>：CCB 的 computer-use MCP server 是<b>进程内</b>承载的
     * （{@code src/services/mcp/client.ts:929-948}：{@code createComputerUseMcpServerForCli()}
     * → {@code createLinkedTransportPair()} → {@code inProcessServer.connect(serverTransport)}
     * → {@code transport = clientTransport}，<b>不 spawn 子进程</b>）。
     * <br>本仓的 MCP client 侧由 {@link McpToolPool#doConnectTransport} 统一驱动
     * （{@code transportFactory.create(config)} ⇒ start ⇒ initialize ⇒ tools/list），
     * ⇒ 进程内 server 只能通过本注册表<b>挂进既有链路</b>（⛔ 不自创 MCP 机制）。
     *
     * <p>注册项由接线方（computer-use 配置）在启动期写入；每次连接都会重新调用工厂方法
     * ⇒ 每次连接得到一对全新的 linked transport（对齐 CCB「每次 connect 建 server」语义）。
     */
    private final Map<String, Supplier<McpTransport>> inProcessServers = new ConcurrentHashMap<>();

    /**
     * 注册进程内 MCP server。
     *
     * @param serverName server 名（如 {@code computer-use}）
     * @param supplier   客户端侧 transport 工厂（返回 linked pair 的客户端侧）
     */
    public void registerInProcessServer(String serverName, Supplier<McpTransport> supplier) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("supplier is required");
        }
        inProcessServers.put(serverName, supplier);
        log.info("[McpTransportFactory] 注册进程内 MCP server: {}", serverName);
    }

    /**
     * 注销进程内 MCP server。
     *
     * @param serverName server 名
     */
    public void unregisterInProcessServer(String serverName) {
        if (inProcessServers.remove(serverName) != null) {
            log.info("[McpTransportFactory] 注销进程内 MCP server: {}", serverName);
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
            // [CU-MCP] 进程内 server（不 spawn 子进程）· 对齐 CCB client.ts:929-948
            case "in-process" -> createInProcess(config.serverName());
            case "claudeai-proxy" -> throw new IllegalArgumentException(
                "claudeai-proxy transport 未实现：Java web 后端无 claude.ai 组织场景（Q-26 TODO）");
            default -> throw new IllegalArgumentException(
                "Unknown MCP transport type: " + type
                    + ". Expected one of: stdio, sse, sse-ide, ws-ide, http, ws, sdk, in-process, claudeai-proxy");
        };
    }

    /**
     * 创建进程内 MCP server 的客户端侧 transport。
     *
     * @param serverName server 名
     * @return 客户端侧 transport（已与服务端 linked）
     */
    private McpTransport createInProcess(String serverName) {
        Supplier<McpTransport> supplier = serverName == null ? null : inProcessServers.get(serverName);
        if (supplier == null) {
            throw new IllegalStateException(
                "进程内 MCP server 未注册: " + serverName + "（先 registerInProcessServer）");
        }
        return supplier.get();
    }

    /** 便捷：按类型 + command 构造（测试/工具用）. */
    public McpTransport createByFields(String type, String command, List<String> args) {
        return create(new McpTransport.TransportConfig(command, args, null, null, null, type));
    }
}

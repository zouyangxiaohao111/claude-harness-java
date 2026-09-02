package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpElicitationStateMachine;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.infra.util.PluginOnlyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Agent MCP 服务器初始化 · 对齐 CC runAgent.ts:92-216 initializeAgentMcpServers
 * + client.ts:595 connectToServer memoize + :1748-1750 capabilities 门控。
 *
 * <p>Agent 可以定义自己的 MCP servers，连接到父 Agent 的 MCP clients 之上。
 * 这些 servers 在 agent 启动时连接，agent 结束时清理。
 *
 * <p>[S05 对齐 CC runAgent.ts + client.ts]:
 * <ul>
 *   <li><b>权限闸</b> (:117-127): {@code isSourceAdminTrusted(source)} +
 *       {@code isRestrictedToPluginOnly('mcp')} 检查, USER-CONTROLLED agent 在
 *       strictPluginOnlyCustomization 锁 MCP 时跳过 frontmatter MCP
 *       (plugin/built-in/policySettings admin-trusted 不跳过)。</li>
 *   <li><b>[A6] memoize 共享连接</b>（client.ts:595 connectToServer = memoize）：
 *       静态 {@link #CONNECTION_CACHE}（键 = name+command+args+env+type）命中复用；
 *       未命中新建并记入 newlyCreatedClients（agent 结束时 cleanup 关闭 + 移除缓存条目，
 *       防闭后复用；resetSession 自愈 CLOSED）。</li>
 *   <li><b>[A6] capabilities?.tools 门控</b>（client.ts:1748-1750 fetchToolsForClient）：
 *       initialize 响应解析 capabilities，{@code capabilities.tools} 存在且非 false/null
 *       才 tools/list（{} 为 truthy 需兼容）。</li>
 *   <li><b>[A5] inline config headers 透传</b>：{@link #fromConfig} 把 {@code cfg.headers}
 *       并入 env（对齐生产轨 McpServerService.upsertServer 远程契约「headers 存入 env 保留」）。</li>
 *   <li><b>newlyCreated 选择性 cleanup</b> (:132/176/198): 只清 newly created (inline)
 *       clients, 共享 memoized parentClients 不清。frontmatter spec 可为 string-ref
 *       (Q-32 按名解析 getMcpConfigByName → 共享 client) 或 inline (agent-specific);
 *       spec 在 SubagentExecutor 统一解析为 McpServerSpec。</li>
 * </ul>
 */
public class AgentMcpServers {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpServers.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();


    /**
     * MCP 服务器连接 · 对齐 CC MCPServerConnection
     */
    public interface McpServerConnection {
        String name();
        List<Tool> getTools();
        void cleanup();
    }

    /**
     * MCP 工具调用通道 · 对齐 CC {@code ConnectedMCPServer} 的 tools/call 承载
     * （client.ts:3092-3097）+ 会话重建（ensureConnectedClient）。
     *
     * <p>agent 轨独占通道：{@link #call} 携带请求侧 meta（_meta）发 tools/call；
     * {@link #resetSession} 重建 transport + initialize 会话（B8 会话重试载体，
     * tools/list 不重拉——快照缓存）。
     */
    public interface McpToolChannel {
        /**
         * 调用 MCP 工具 · CC original: {@code client.callTool({name, arguments, _meta})}
         * （client.ts:3092-3097）。
         *
         * @param args tools/call arguments
         * @param meta 请求侧 meta（R2-06 X-1 claudecode/toolUseId；空则不携带 _meta）
         * @return tools/call 响应 future
         */
        CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta);

        /**
         * 重建会话（close + create + start + initialize + initialized notification）·
         * 对齐 CC ensureConnectedClient 重连语义（client.ts:1862）。tools/list 不重拉。
         */
        void resetSession();
    }

    /**
     * MCP 服务器配置 · 对齐 CC AgentMcpServerSpec
     *
     * @param name    MCP server 名
     * @param command stdio → 命令；远程（http/sse/ws）→ url 承载（现有 Java 契约）
     * @param args    stdio 参数
     * @param env     stdio 环境变量 / 远程 headers（A5：headers 并入 env 载体）
     * @param type    传输类型（stdio/sse/http/ws/sdk 等 · [MCP-I-9 Q-32] 名字解析自 DB type 列）
     */
    public record McpServerSpec(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        String type
    ) {
        /** 4 参兼容构造器（缺 type）· stdio 缺省（inline spec 保持旧行为）。 */
        public McpServerSpec(String name, String command, List<String> args, Map<String, String> env) {
            this(name, command, args, env, "stdio");
        }
    }

    /**
     * config Map → McpServerSpec · 共享工厂（stdio→command+args；远程→command 列存 url；type 缺省 stdio）.
     *
     * <p>[MCP-I-9 Q-32] 供 resolver（ToolRegistrationConfig / SubagentTool 按名解析 DB config）
     * 与 [MCP-I-9 返工 R3] keyed inline {@code {[serverName]: config}} 内层 config 共用，
     * 保证两处 spec 构建口径一致。
     *
     * <p>[S05 A5] {@code cfg.headers} 并入 env——对齐生产轨 McpServerService.upsertServer
     * 远程契约「headers 存入 env 保留」（McpServerService.java:533-547），inline config
     * headers 经 env 载体透传到 transport（HttpMcpTransport 等消费 env 中的 Authorization
     * 等静态头）。透传通道测试锁「headers 到达 spec.env」。
     *
     * @param name server 名
     * @param cfg  config Map（type/command|url/args/env/headers · 对齐 McpServerService.serverConfig 形态）
     * @return McpServerSpec
     */
    public static McpServerSpec fromConfig(String name, Map<String, Object> cfg) {
        String type = String.valueOf(cfg.getOrDefault("type", "stdio"));
        @SuppressWarnings("unchecked")
        List<String> args = cfg.get("args") instanceof List
                ? (List<String>) cfg.get("args") : List.of();
        Map<String, String> env = cfg.get("env") instanceof Map
                ? (Map<String, String>) cfg.get("env") : Map.of();
        // [S05 A5] inline config headers → env 载体（对齐生产轨远程契约；value null → ""）
        Object headers = cfg.get("headers");
        if (headers instanceof Map<?, ?> m && !m.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(env);
            for (Map.Entry<?, ?> en : m.entrySet()) {
                merged.put(String.valueOf(en.getKey()),
                    en.getValue() == null ? "" : String.valueOf(en.getValue()));
            }
            env = merged;
            if (log.isDebugEnabled()) {
                log.debug("[AgentMcpServers] fromConfig headers 并入 env（A5 透传通道）server={} headers={}",
                    name, m.keySet());
            }
        }
        String command = String.valueOf(cfg.getOrDefault("command", cfg.getOrDefault("url", "")));
        return new McpServerSpec(name, command, args, env, type);
    }

    /**
     * 初始化结果 · 对齐 CC initializeAgentMcpServers 返回值
     */
    public record InitResult(
        List<McpServerConnection> clients,
        List<Tool> tools,
        Runnable cleanup
    ) {}

    /**
     * 初始化 Agent MCP servers · 对齐 CC runAgent.ts:92-216.
     *
     * <p>流程:
     * <ol>
     *   <li>无 frontmatter mcpServers → 返 parentClients + 空 tools + noop cleanup (:103-111)</li>
     *   <li>权限闸 (:117-127): pluginOnly 锁 MCP 且 source 非 admin-trusted → 跳过, 返
     *       parentClients + 空 tools + noop cleanup</li>
     *   <li>逐 spec connectToServer（memoize 命中复用 / 未命中真实 JSON-RPC 建连 +
     *       capabilities.tools 门控后才 tools/list）→ agentClients + agentTools</li>
     *   <li>cleanup 只清 newlyCreatedClients (:132/176/198), parentClients 不清；
     *       newlyCreated 的 cleanup 同时移除 memoize 缓存条目（防闭后复用）</li>
     * </ol>
     *
     * @param agentMcpServers           Agent 定义的 MCP servers
     * @param parentClients             父 Agent 的 MCP clients（additive）
     * @param transportFactory          {@link McpTransportFactory} 实例 (Spring DI 或测试 stub)
     * @param source                    Agent definition 的 source · CC original: agentDefinition.source
     *                                  (runAgent.ts:117)
     * @param pluginOnlySettingsSupplier 读 plugin-only policy settings · CC original: appState.settings
     *                                  (runAgent.ts:118 isRestrictedToPluginOnly('mcp'))
     * @param mcpToolTimeoutMs          MCP tool 调用超时 ms · CC original: getMcpToolTimeoutMs() (client.ts:224)
     * @param elicitationMachine        共享 URL elicitation 状态机（可为 null；null = 工具不接
     *                                  -32042 elicitation，测试直连）· CC original: context.handleElicitation
     *                                  (client.ts:1880)
     * @return 初始化结果, 含真实 tools[] (非 stub List.of())
     */
    public static InitResult initialize(
            Optional<List<McpServerSpec>> agentMcpServers,
            List<McpServerConnection> parentClients,
            McpTransportFactory transportFactory,
            String source,
            Supplier<Map<String, Object>> pluginOnlySettingsSupplier,
            long mcpToolTimeoutMs,
            McpElicitationStateMachine elicitationMachine) {

        if (transportFactory == null) {
            throw new IllegalArgumentException(
                "P2.3: McpTransportFactory required for real tools/list, got null");
        }
        // 无 frontmatter mcpServers → 返 parentClients as-is (CC runAgent.ts:103-111)
        if (agentMcpServers.isEmpty() || agentMcpServers.get().isEmpty()) {
            return new InitResult(parentClients, List.of(), () -> {});
        }

        // ── 权限闸 · 对齐 CC runAgent.ts:117-127 ──
        // strictPluginOnlyCustomization 锁 MCP 时, USER-CONTROLLED agent (userSettings/
        // projectSettings/flagSettings) 的 frontmatter MCP 跳过; plugin/built-in/policySettings
        // admin-trusted 不跳过 (admin-approved surface).
        boolean agentIsAdminTrusted = PluginOnlyPolicy.isSourceAdminTrusted(source);
        if (PluginOnlyPolicy.isRestrictedToPluginOnly("mcp", pluginOnlySettingsSupplier)
                && !agentIsAdminTrusted) {
            log.info("[AgentMcpServers] strictPluginOnlyCustomization 锁定 MCP 仅 plugin, "
                    + "跳过 frontmatter MCP (agent source: {})", source);
            return new InitResult(parentClients, List.of(), () -> {});
        }

        List<McpServerConnection> agentClients = new ArrayList<>();
        // 只清 newly created clients (CC runAgent.ts:132/176/198)。[A6 memoize]：
        // 命中复用 → 不入 newlyCreated（共享，本 agent 不清）；未命中新建 →
        // 入 newlyCreated（本 agent 结束时 cleanup 关闭 + 移除缓存条目）。
        List<McpServerConnection> newlyCreatedClients = new ArrayList<>();
        List<Tool> agentTools = new ArrayList<>();

        for (McpServerSpec spec : agentMcpServers.get()) {
            try {
                ConnectionResult cr = connectToServer(spec, transportFactory,
                    mcpToolTimeoutMs, elicitationMachine);
                agentClients.add(cr.connection());
                if (cr.createdNew()) {
                    newlyCreatedClients.add(cr.connection());
                }
                agentTools.addAll(cr.connection().getTools());
                log.info("[AgentMcpServers] connected to agent MCP server '{}' with {} tools{}",
                    spec.name(), cr.connection().getTools().size(),
                    cr.createdNew() ? "" : "（memoize 命中复用，CC client.ts:595）");
            } catch (Exception e) {
                log.warn("[AgentMcpServers] Failed to connect to agent MCP server '{}': {}",
                    spec.name(), e.getMessage());
            }
        }

        List<McpServerConnection> mergedClients = new ArrayList<>();
        if (parentClients != null) mergedClients.addAll(parentClients);
        mergedClients.addAll(agentClients);

        // 只清 newlyCreatedClients (CC runAgent.ts:197-210), 共享 memoized parentClients 不清
        Runnable cleanup = () -> {
            for (McpServerConnection client : newlyCreatedClients) {
                try { client.cleanup(); } catch (Exception e) {
                    log.warn("[AgentMcpServers] Cleanup error: {}", e.getMessage());
                }
            }
        };

        return new InitResult(mergedClients, agentTools, cleanup);
    }

    /** 连接结果 · createdNew = 本次新建（非 memoize 命中）。 */
    private record ConnectionResult(McpServerConnection connection, boolean createdNew) {}

    /**
     * [A6] memoize 连接缓存 · 对齐 CC client.ts:595 connectToServer = memoize——
     * 同 name+config 复用共享连接（不重复建 transport / 不重复 tools/list）。
     * 键 = name+command+args+env+type（headers 已并入 env，见 {@link #fromConfig}）。
     * 静态（跨 agent 共享，对齐 CC 全局 memoize）；测试经 {@link #clearConnectionCache()} 复位。
     */
    private static final Map<String, McpServerConnection> CONNECTION_CACHE = new ConcurrentHashMap<>();

    /** [A6] 测试 seam · 清空 memoize 缓存（等价 CC clearServerCache 的 agent 轨复位）。 */
    public static void clearConnectionCache() {
        CONNECTION_CACHE.clear();
        if (log.isDebugEnabled()) {
            log.debug("[AgentMcpServers] memoize 连接缓存已清空（clearConnectionCache）");
        }
    }

    private static String cacheKey(McpServerSpec spec) {
        return spec.name() + '\u0001' + spec.command() + '\u0001'
            + spec.args() + '\u0001' + spec.env() + '\u0001' + spec.type();
    }

    /**
     * memoize 建连 · 命中复用（不入 newlyCreated）；未命中新建（close 后缓存条目同步移除）。
     * 并发竞争：重复创建的连接立即 cleanup（防 transport 泄漏），用先到者。
     */
    private static ConnectionResult connectToServer(
            McpServerSpec spec, McpTransportFactory transportFactory,
            long mcpToolTimeoutMs, McpElicitationStateMachine elicitationMachine) {
        String key = cacheKey(spec);
        McpServerConnection cached = CONNECTION_CACHE.get(key);
        if (cached != null) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMcpServers] memoize 命中复用连接 '{}'（CC client.ts:595）", spec.name());
            }
            return new ConnectionResult(cached, false);
        }
        McpServerConnection connection = createConnection(spec, transportFactory,
            mcpToolTimeoutMs, elicitationMachine, key);
        McpServerConnection winner = CONNECTION_CACHE.putIfAbsent(key, connection);
        if (winner != null) {
            // 并发竞争：丢弃重复创建（close transport 防泄漏），用先到者
            connection.cleanup();
            return new ConnectionResult(winner, false);
        }
        return new ConnectionResult(connection, true);
    }

    /**
     * 共享池连接包装 · [Q-09-R2-1] 主链活跃池连接包装（LlmAgentLoop 顶层继承注入用）。
     *
     * <p>语义对齐 CC runAgent.ts:196-210：共享 memoized client 不被 agent cleanup 清——
     * {@code cleanup()=no-op}；{@code getTools()} 返不可变快照。
     *
     * @param serverName MCP server 名（主链活跃池工具 mcp__{server}__{tool} 解析所得）
     * @param tools      该 server 的活跃池工具快照
     * @return 连接包装（cleanup no-op）
     */
    public static McpServerConnection wrapSharedPoolClient(String serverName, List<Tool> tools) {
        final List<Tool> snapshot = tools == null ? List.of() : List.copyOf(tools);
        return new McpServerConnection() {
            @Override
            public String name() {
                return serverName;
            }

            @Override
            public List<Tool> getTools() {
                return snapshot;
            }

            @Override
            public void cleanup() {
                // [Q-09-R2-1] 共享池连接：agent cleanup 不清（CC runAgent.ts:196-210 共享 client 不清）
                if (log.isDebugEnabled()) {
                    log.debug("[AgentMcpServers] wrapSharedPoolClient.cleanup no-op server={}（共享池连接）",
                        serverName);
                }
            }
        };
    }

    /** 建连产物 · transport + initialize 响应 capabilities。 */
    private record ConnectResult(McpTransport transport, JsonNode capabilities) {}

    /**
     * create + start + initialize 握手 + initialized notification · 对齐 CC client.ts:191-207。
     * initialize 响应解析 capabilities（[A6] 门控数据源）。
     */
    private static ConnectResult openTransport(McpServerSpec spec, McpTransportFactory transportFactory) {
        McpTransport.TransportConfig config = new McpTransport.TransportConfig(
            spec.command(),
            spec.args(),
            spec.env(),
            null,  // cwd = 当前工作目录
            spec.name(),  // [Session H P2-5] serverName · 401 → McpAuthError 降级目标标识
            spec.type()   // [MCP-I-9 Q-32] 显式传输类型（stdio/remote）· McpTransportFactory 显式判别
        );
        McpTransport transport = transportFactory.create(config);
        transport.start(config);

        // initialize 握手 (CC client.ts:191-200)
        CompletableFuture<JsonNode> initFuture = transport.sendRequest("initialize",
            Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "nexusai-agent-mcp", "version", "1.0.0")
            ));
        JsonNode initResult = initFuture.join();
        log.info("[AgentMcpServers] MCP server '{}' initialize: {}",
            spec.name(), initResult.path("serverInfo").path("name").asText());

        // initialized notification (CC client.ts:202-207)
        transport.sendNotification("notifications/initialized", Map.of());

        return new ConnectResult(transport, initResult.path("capabilities"));
    }

    /**
     * [A6] capabilities.tools 门控 · 对齐 CC client.ts:1748-1750
     * {@code if (!client.capabilities?.tools) return []}——JS truthy 语义：
     * missing/null/false/0 → 不拉 tools/list；{} / [] / 其他 → truthy 拉取。
     */
    private static boolean hasToolsCapability(JsonNode capabilities) {
        if (capabilities == null || capabilities.isNull() || capabilities.isMissingNode()) {
            return false;
        }
        JsonNode tools = capabilities.get("tools");
        if (tools == null || tools.isNull() || tools.isMissingNode()) {
            return false;
        }
        if (tools.isBoolean()) {
            return tools.asBoolean();
        }
        if (tools.isNumber()) {
            return tools.asInt(1) != 0;
        }
        return true;  // 对象（含 {}）/ 数组 → truthy
    }

    /**
     * 真实建连（P2.3）· 用 {@link McpTransportFactory} 创 transport → initialize 握手 →
     * capabilities.tools 门控后才 tools/list → 缓存解析结果 → 匿名 McpServerConnection
     * （channel 承载 tools/call + resetSession）。
     */
    private static McpServerConnection createConnection(
            McpServerSpec spec, McpTransportFactory transportFactory, long mcpToolTimeoutMs,
            McpElicitationStateMachine elicitationMachine, String cacheKey) {

        AtomicReference<McpTransport> transportRef = new AtomicReference<>();
        ConnectResult connect = openTransport(spec, transportFactory);
        transportRef.set(connect.transport());

        // [A6] capabilities.tools 门控后才 tools/list（CC client.ts:1748-1750）
        List<Tool> cachedTools = new ArrayList<>();
        if (hasToolsCapability(connect.capabilities())) {
            // tools/list (CC client.ts:209-220)
            CompletableFuture<JsonNode> listFuture = connect.transport().sendRequest("tools/list", Map.of());
            JsonNode listResult = listFuture.join();
            JsonNode toolsNode = listResult.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.path("name").asText("");
                    if (toolName.isEmpty()) continue;
                    JsonNode inputSchema = toolNode.path("inputSchema");
                    // [S5 P0 差异 1] annotations + 真实 description 透传到 AgentMcpTool
                    //   · CC client.ts:1795-1808 annotations 映射 + :1786-1788 description
                    JsonNode annotations = toolNode.path("annotations");
                    // [S05] _meta 透传 · CC client.ts:1776-1785（searchHint/alwaysLoad 数据源）
                    JsonNode meta = toolNode.path("_meta");
                    String description = toolNode.path("description").asText("");
                    // [impl-I-4 F4 rework] 裸拼 `"mcp__"+spec.name()+"__"+toolName` → buildMcpToolName
                    // （规范化，对齐 T9/McpStringUtils）：server 名含空格/点/大写时与主链路规范化
                    // 注册名一致，权限/过滤/mcpInfoFromString 消费方不失配
                    String mcpToolName = McpStringUtils.buildMcpToolName(spec.name(), toolName);
                    Tool tool = wrapAgentTool(spec.name(), toolName, mcpToolName, inputSchema,
                        annotations, meta, description, transportRef, transportFactory, spec,
                        mcpToolTimeoutMs, elicitationMachine);
                    cachedTools.add(tool);
                    log.info("[AgentMcpServers] registered agent mcp tool: {}", mcpToolName);
                }
            }
        } else {
            log.info("[AgentMcpServers] MCP server '{}' 无 capabilities.tools，跳过 tools/list"
                + "（对齐 CC client.ts:1748-1750）", spec.name());
        }
        final List<Tool> snapshot = List.copyOf(cachedTools);

        return new McpServerConnection() {
            private volatile boolean cleanedUp = false;

            @Override
            public String name() { return spec.name(); }

            @Override
            public List<Tool> getTools() { return snapshot; }

            @Override
            public void cleanup() {
                if (cleanedUp) return;
                cleanedUp = true;
                try {
                    McpTransport t = transportRef.get();
                    if (t != null) {
                        t.close();
                    }
                    log.debug("[AgentMcpServers] MCP server '{}' cleaned up", spec.name());
                } catch (Exception e) {
                    log.warn("[AgentMcpServers] MCP cleanup error for '{}': {}", spec.name(), e.getMessage());
                }
                // [A6] 闭后移除缓存条目，防复用已关闭连接（跨 agent 复用被首个 agent cleanup 关闭场景）
                CONNECTION_CACHE.remove(cacheKey, this);
            }
        };
    }

    /**
     * 包装 agent-scoped MCP tool: tools/call 经 {@link McpToolChannel} 委托
     * transport.sendRequest('tools/call')（请求侧 _meta 并入）+ resetSession 会话重建。
     */
    private static Tool wrapAgentTool(String serverName, String toolName, String mcpToolName,
                                      JsonNode inputSchema, JsonNode annotations, JsonNode meta,
                                      String description,
                                      AtomicReference<McpTransport> transportRef,
                                      McpTransportFactory transportFactory,
                                      McpServerSpec spec, long mcpToolTimeoutMs,
                                      McpElicitationStateMachine elicitationMachine) {
        McpToolChannel channel = new McpToolChannel() {
            private final Object sessionLock = new Object();

            @Override
            public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
                synchronized (sessionLock) {
                    McpTransport t = transportRef.get();
                    if (t != null && t.getState() == McpTransport.State.CLOSED) {
                        // [A6 自愈] 连接已被其他 agent cleanup 关闭 → 重建会话（不悬挂）
                        if (log.isDebugEnabled()) {
                            log.debug("[AgentMcpServers] {}.{} 连接 CLOSED，resetSession 自愈",
                                serverName, toolName);
                        }
                        resetSession();
                    }
                }
                ObjectNode request = MAPPER.createObjectNode();
                request.put("name", toolName);
                request.set("arguments", MAPPER.valueToTree(args));
                // [R2-06 X-1] 请求侧 meta（claudecode/toolUseId）· CC client.ts:3096
                //   client.callTool({name, arguments, _meta: meta})（空 meta 省略，JSON-RPC 可选字段）
                if (meta != null && !meta.isEmpty()) {
                    request.set("_meta", MAPPER.valueToTree(meta));
                }
                return transportRef.get().sendRequest("tools/call", request);
            }

            @Override
            public void resetSession() {
                synchronized (sessionLock) {
                    McpTransport old = transportRef.get();
                    if (old != null && old.getState() != McpTransport.State.CLOSED) {
                        try {
                            old.close();
                        } catch (Exception ignored) {
                            // 重建路径：close 失败不阻断
                        }
                    }
                    ConnectResult fresh = openTransport(spec, transportFactory);
                    transportRef.set(fresh.transport());
                    if (log.isDebugEnabled()) {
                        log.debug("[AgentMcpServers] {}.{} 会话已重建（resetSession，tools/list 不重拉——快照缓存）",
                            serverName, toolName);
                    }
                }
            }
        };
        return new AgentMcpTool(serverName, toolName, mcpToolName, inputSchema, annotations, meta,
            description, channel, mcpToolTimeoutMs, elicitationMachine);
    }
}

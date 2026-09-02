package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.mcp.McpConnectivityStatus.McpClient;
import com.nexusai.application.agent.mcp.McpConnectivityStatus.Notification;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ConfigScope;
import com.nexusai.application.agent.mcp.McpTypesRegistry.FailedMCPServer;
import com.nexusai.application.agent.mcp.McpTypesRegistry.MCPServerConnection;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.NeedsAuthMCPServer;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ScopedMcpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 连接状态注册表 · 对齐 CC AppState.mcp.clients（MCPServerConnection[]）的生产与
 * {@code useMcpConnectivityStatus} 消费链（Open-ClaudeCode/src/hooks/notifs/useMcpConnectivityStatus.tsx:13-75）。
 *
 * <p>L1 语义（Q-08 接线）：
 * <ol>
 *   <li><b>状态生产</b>：由 {@link McpToolPool} 连接生命周期驱动（CC 状态生产链 client.ts:340-361
 *       handleRemoteAuthFailure / :1105-1137 401→needs-auth / :3194-3208 工具 401→McpAuthError），
 *       本 registry 持有当前各 server 的连接态：{@link NeedsAuthMCPServer}（需认证）/
 *       {@link FailedMCPServer}（连接失败）+ connected 集合（连接成功）。真实 CC record 被消费
 *       （Q-08「NeedsAuthMCPServer record 被消费」）。</li>
 *   <li><b>状态消费</b>：每次变化后调 {@link McpConnectivityStatus#classify(List, Predicate)}
 *       生成通知快照（CC useMcpConnectivityStatus 的 4 组 filter → addNotification），供查询接口
 *       {@link #queryStatus()} / {@link #queryClients()} 读取（为前端待实现登记做准备）。</li>
 * </ol>
 *
 * <p>L3 (Java idiom)：CC React {@code useState(mcpClients)} + {@code useEffect} → Java 内存
 * ConcurrentHashMap 注册表 + 变更即 recompute 通知快照。Java web 后端无 React 渲染，
 * 通知投递（NotificationBar/WebSocket）为前端待实现登记项，本类先暴露查询接口 + debug 日志。
 *
 * <p>claudeai-proxy 配置未接入连接状态注册表（Q-26 TODO）→ {@link #hasEverConnected} 对 claudeai
 * 不触发。
 */
@Component
public class McpConnectivityStatusRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpConnectivityStatusRegistry.class);

    /** 降级态（failed/needs-auth/pending/disabled）→ 真实 CC 记录（NeedsAuthMCPServer/FailedMCPServer）。 */
    private final Map<String, MCPServerConnection> degraded = new ConcurrentHashMap<>();

    /** 连接成功态 · name → configType（对齐 CC ConnectedMCPServer 的 name+config，查询投影用）。 */
    private final Map<String, String> connectedTypes = new ConcurrentHashMap<>();

    /** 最近一次分类通知快照（查询接口 · 前端待实现登记基础）。 */
    private volatile List<Notification> lastNotifications = List.of();

    /** 连接成功：记录 connected 态 + 清除降级态（恢复连接后不再产生 failed/needs-auth 通知）。 */
    public void updateConnected(String name, String configType) {
        if (name == null) {
            return;
        }
        connectedTypes.put(name, configType);
        degraded.remove(name);
        recompute("连接成功", name);
    }

    /** 需认证：连接期 401（CC handleRemoteAuthFailure → type='needs-auth'）或工具调用 401 降级。 */
    public void updateNeedsAuth(String name, String configType) {
        if (name == null) {
            return;
        }
        connectedTypes.remove(name);
        degraded.put(name, new NeedsAuthMCPServer(name, scoped(configType)));
        recompute("需认证", name);
    }

    /** 连接失败：连接/获取阶段异常（CC catch → type='failed'）。 */
    public void updateFailed(String name, String configType, String error) {
        if (name == null) {
            return;
        }
        connectedTypes.remove(name);
        degraded.put(name, new FailedMCPServer(name, scoped(configType), error));
        recompute("连接失败", name);
    }

    /** 移除（stop/teardown）：从注册表删除，不再产生通知。 */
    public void remove(String name) {
        if (name == null) {
            return;
        }
        boolean removed = degraded.remove(name) != null;
        removed |= connectedTypes.remove(name) != null;
        if (removed) {
            recompute("已移除", name);
        }
    }

    /** 清空全部连接状态（应用级重置）。 */
    public void clear() {
        degraded.clear();
        connectedTypes.clear();
        recompute("清空", null);
    }

    // ═══════════════ 查询接口（前端待实现登记 · Q-08 ②）═══════════════

    /** 降级态连接记录快照（喂给 {@link McpConnectivityStatus#classify}，消费真实 CC record）。 */
    public List<MCPServerConnection> snapshot() {
        return new ArrayList<>(degraded.values());
    }

    /**
     * [G27③] 降级态（failed/needs-auth）server 名集合 · 对齐 CC mcpClients 含 failed/needs-auth：
     * 供 {@link McpToolPool#activeServers()} 合并「已知 server 人口」（ReadMcpResourceTool /
     * ListMcpResourcesTool 的 not-found vs not-connected 区分对齐 CC client.ts:78-92）。
     */
    public java.util.Set<String> degradedNames() {
        return degraded.keySet();
    }

    /** 当前连接状态通知快照（classify 产物；mcp-failed/mcp-claudeai-failed/mcp-needs-auth/mcp-claudeai-needs-auth）。 */
    public List<Notification> queryStatus() {
        return lastNotifications;
    }

    /** 前端可渲染的完整连接列表投影（connected + 降级态；含 configType/type/name）。 */
    public List<McpClient> queryClients() {
        List<McpClient> out = new ArrayList<>();
        degraded.forEach((name, conn) -> out.add(new McpClient(conn.type(),
            McpConnectivityStatus.configTypeOf(conn), name)));
        connectedTypes.forEach((name, configType) -> {
            if (!degraded.containsKey(name)) {
                out.add(new McpClient("connected", configType, name));
            }
        });
        return out;
    }

    /** 指定 server 当前连接态类型（connected / failed / needs-auth / …；未知 → null）。 */
    public String typeOf(String name) {
        if (name == null) {
            return null;
        }
        MCPServerConnection conn = degraded.get(name);
        if (conn != null) {
            return conn.type();
        }
        if (connectedTypes.containsKey(name)) {
            return "connected";
        }
        return null;
    }

    /** CC hasClaudeAiMcpEverConnected 语义 · claudeai-proxy 配置未接入连接状态注册表（Q-26 TODO），对 claudeai 不触发。 */
    public boolean hasEverConnected(String name) {
        return name != null && connectedTypes.containsKey(name);
    }

    // ═══════════════ 内部 ═══════════════

    private void recompute(String reason, String name) {
        lastNotifications = McpConnectivityStatus.classify(
            McpConnectivityStatus.toClients(snapshot()), this::hasEverConnected);
        if (log.isDebugEnabled()) {
            log.debug("[McpConnectivityStatusRegistry] {} server={} 通知快照={}", reason, name, lastNotifications);
        }
        if (!lastNotifications.isEmpty()) {
            // 低频状态变化（非热路径）→ info 级登记，前端待实现前留可观测性
            log.info("[McpConnectivityStatusRegistry] MCP 连接状态通知: {}", lastNotifications);
        }
    }

    /** 构造 minimal ScopedMcpServerConfig（仅承载 configType，供 NeedsAuthMCPServer/FailedMCPServer）。 */
    private static ScopedMcpServerConfig scoped(String configType) {
        return new ScopedMcpServerConfig(new McpServerConfig() {
            @Override
            public String type() {
                return configType;
            }
        }, ConfigScope.LOCAL, null);
    }
}

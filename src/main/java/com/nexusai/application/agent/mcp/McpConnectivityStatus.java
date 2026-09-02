package com.nexusai.application.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * McpConnectivityStatus · 对齐 CC hooks/notifs/useMcpConnectivityStatus.tsx:14-80。
 *
 * <p>L1 语义: 把 MCP server 连接列表分为 4 类并生成通知: 本地失败 / claude.ai 失败 /
 * 本地需认证 / claude.ai 需认证。分类谓词 (CC _temp/_temp2/_temp3/_temp4):
 * <ul>
 *   <li>failedLocal: type=failed 且 configType∉{sse-ide,ws-ide,claudeai-proxy}</li>
 *   <li>failedClaudeAi: type=failed 且 configType=claudeai-proxy 且 everConnected(name)</li>
 *   <li>needsAuthLocal: type=needs-auth 且 configType≠claudeai-proxy</li>
 *   <li>needsAuthClaudeAi: type=needs-auth 且 configType=claudeai-proxy 且 everConnected(name)</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: classify(clients, everConnected) → List&lt;Notification&gt; (key/text/priority=medium)</li>
 *   <li><b>A2 Golden Trace</b>: 1 failedLocal → key=mcp-failed "1 MCP server failed"</li>
 *   <li><b>A3 纯函数</b>: 无副作用, everConnected 注入; 全空 → 空列表</li>
 *   <li><b>A4 边界</b>: 单复数 (1→server/2→servers, needs/need); sse-ide/ws-ide 不计入 failedLocal</li>
 *   <li><b>A5 业务场景</b>: 2 needsAuthLocal → "2 MCP servers need auth · key=mcp-needs-auth"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS React useEffect + JSX &lt;Text&gt; 通知 → Java 纯静态分类 + 文本构造,
 * JSX 渲染留给 Java 端 NotificationQueue; hasClaudeAiMcpEverConnected → 注入 Predicate。
 */
public final class McpConnectivityStatus {

    /** MCP client 精简模型 (对齐 CC MCPServerConnection 关键字段) */
    public record McpClient(String type, String configType, String name) {}

    /** 通知项 (对齐 CC addNotification 参数) */
    public record Notification(String key, String text, String priority) {}

    public static final String PRIORITY = "medium";

    private McpConnectivityStatus() {}

    private static boolean isFailedLocal(McpClient c) {
        return "failed".equals(c.type())
            && !"sse-ide".equals(c.configType())
            && !"ws-ide".equals(c.configType())
            && !"claudeai-proxy".equals(c.configType());
    }

    private static boolean isFailedClaudeAi(McpClient c, Predicate<String> everConnected) {
        return "failed".equals(c.type())
            && "claudeai-proxy".equals(c.configType())
            && everConnected.test(c.name());
    }

    private static boolean isNeedsAuthLocal(McpClient c) {
        return "needs-auth".equals(c.type()) && !"claudeai-proxy".equals(c.configType());
    }

    private static boolean isNeedsAuthClaudeAi(McpClient c, Predicate<String> everConnected) {
        return "needs-auth".equals(c.type())
            && "claudeai-proxy".equals(c.configType())
            && everConnected.test(c.name());
    }

    /**
     * CC useMcpConnectivityStatus.tsx:24-76 — 分类 + 生成通知 (顺序: failedLocal, failedClaudeAi,
     * needsAuthLocal, needsAuthClaudeAi)。全部为 0 时返回空列表 (对齐 CC early return)。
     */
    public static List<Notification> classify(List<McpClient> clients, Predicate<String> everConnected) {
        List<Notification> out = new ArrayList<>();
        if (clients == null || clients.isEmpty()) return out;

        long failedLocal = clients.stream().filter(McpConnectivityStatus::isFailedLocal).count();
        long failedClaudeAi = clients.stream().filter(c -> isFailedClaudeAi(c, everConnected)).count();
        long needsAuthLocal = clients.stream().filter(McpConnectivityStatus::isNeedsAuthLocal).count();
        long needsAuthClaudeAi = clients.stream().filter(c -> isNeedsAuthClaudeAi(c, everConnected)).count();

        if (failedLocal > 0) {
            out.add(new Notification("mcp-failed",
                failedLocal + " MCP " + (failedLocal == 1 ? "server" : "servers") + " failed", PRIORITY));
        }
        if (failedClaudeAi > 0) {
            out.add(new Notification("mcp-claudeai-failed",
                failedClaudeAi + " claude.ai " + (failedClaudeAi == 1 ? "connector" : "connectors") + " unavailable", PRIORITY));
        }
        if (needsAuthLocal > 0) {
            out.add(new Notification("mcp-needs-auth",
                needsAuthLocal + " MCP " + (needsAuthLocal == 1 ? "server needs" : "servers need") + " auth", PRIORITY));
        }
        if (needsAuthClaudeAi > 0) {
            out.add(new Notification("mcp-claudeai-needs-auth",
                needsAuthClaudeAi + " claude.ai " + (needsAuthClaudeAi == 1 ? "connector needs" : "connectors need") + " auth", PRIORITY));
        }
        return out;
    }

    /**
     * 生产接线辅助（Q-08）· 把真实 {@link McpTypesRegistry.MCPServerConnection} 记录
     * （{@link McpTypesRegistry.NeedsAuthMCPServer} / {@link McpTypesRegistry.FailedMCPServer}
     * 等，NeedsAuthMCPServer record 被生产消费）映射为精简 {@link McpClient} 列表，供
     * {@link McpConnectivityStatusRegistry} 喂给 {@link #classify(List, Predicate)}。
     *
     * <p>CC original: useMcpConnectivityStatus.tsx:29-33（useEffect 内对 mcpClients 做 4 组
     * filter，谓词 _temp/_temp2/_temp3/_temp4 见 :76-87）。
     *
     * <p>说明（受控偏差）：CC types.ts:221-222 的 {@code MCPServerConnection} union 显式包含
     * ConnectedMCPServer（{@code | ConnectedMCPServer | FailedMCPServer | NeedsAuthMCPServer |
     * PendingMCPServer | DisabledMCPServer}；types.ts:96-101 为 McpSSE 配置 schema，与连接 union 无关）。
     * Java 端 {@link McpTypesRegistry.ConnectedMCPServer} 为 pre-existing record，未实现
     * {@link McpTypesRegistry.MCPServerConnection} —— 结构偏差（本 session 不追平）。语义等价：
     * connected 不进入 registry 降级态（由 connected 集合承载）、不匹配 failed/needs-auth 分类
     * 谓词 → 不产生通知。
     *
     * @param connections 连接状态记录列表（降级态：failed/needs-auth/pending/disabled；可为空 → 空列表）
     * @return McpClient 列表（name 为 null 的未知记录跳过）
     */
    static List<McpClient> toClients(List<? extends McpTypesRegistry.MCPServerConnection> connections) {
        if (connections == null || connections.isEmpty()) {
            return List.of();
        }
        List<McpClient> clients = new ArrayList<>(connections.size());
        for (McpTypesRegistry.MCPServerConnection conn : connections) {
            String name = nameOf(conn);
            if (name == null) {
                continue;
            }
            clients.add(new McpClient(conn.type(), configTypeOf(conn), name));
        }
        return clients;
    }

    /** 从连接记录提取 name（CC MCPServerConnection.name）。 */
    static String nameOf(McpTypesRegistry.MCPServerConnection conn) {
        if (conn instanceof McpTypesRegistry.NeedsAuthMCPServer n) return n.name();
        if (conn instanceof McpTypesRegistry.FailedMCPServer f) return f.name();
        if (conn instanceof McpTypesRegistry.PendingMCPServer p) return p.name();
        if (conn instanceof McpTypesRegistry.DisabledMCPServer d) return d.name();
        return null;
    }

    /** 从连接记录提取 configType（CC config.type，types.ts:124-135）。 */
    static String configTypeOf(McpTypesRegistry.MCPServerConnection conn) {
        McpTypesRegistry.ScopedMcpServerConfig scoped = null;
        if (conn instanceof McpTypesRegistry.NeedsAuthMCPServer n) scoped = n.config();
        else if (conn instanceof McpTypesRegistry.FailedMCPServer f) scoped = f.config();
        else if (conn instanceof McpTypesRegistry.PendingMCPServer p) scoped = p.config();
        else if (conn instanceof McpTypesRegistry.DisabledMCPServer d) scoped = d.config();
        if (scoped == null || scoped.config() == null) {
            return null;
        }
        return scoped.config().type();
    }
}

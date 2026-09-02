package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP transport 抽象 · 对齐 CC MCPConnectionManager.ts transports 路由.
 *
 * <p>L1 语义: 与 MCP server 通信的载体. 4 种实现 (本 commit 不全做):
 * <ul>
 *   <li>stdio (本 commit 提供)</li>
 *   <li>SSE (Server-Sent Events, 留后续)</li>
 *   <li>SHTTP (Streamable HTTP, 留后续)</li>
 *   <li>WS (WebSocket, 留后续)</li>
 * </ul>
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #start} → CONNECTED 或抛 IllegalStateException</li>
 *   <li>{@link #sendRequest} 返回的 future 一定完成 (成功/失败), 不会悬挂</li>
 *   <li>{@link #close} 幂等</li>
 * </ul>
 */
public interface McpTransport {

    /** 启动 transport. 失败抛 IllegalStateException. */
    void start(TransportConfig config);

    /** 发送 JSON-RPC request (有 id, 等响应). */
    CompletableFuture<JsonNode> sendRequest(String method, Object params);

    /** 发送 JSON-RPC notification (无 id, 不等响应). */
    void sendNotification(String method, Object params);

    /**
     * 注册 server→client 通知处理器 · 对齐 CC {@code client.setNotificationHandler(Schema, handler)}
     * （useManageMCPConnections.ts:619/:669/:707，CC 原名 setNotificationHandler）。
     *
     * <p>CC 语义（自验 useManageMCPConnections.ts:618-751）：按
     * {@code client.capabilities.{tools,prompts,resources}.listChanged} 门控注册 3 类
     * {@code notifications/{tools,prompts,resources}/list_changed} 处理器；server 侧主动推送时按 method 分发到
     * 对应 handler。Java 端 method 即 MCP 协议通知名
     * （notifications/tools/list_changed、notifications/prompts/list_changed、
     * notifications/resources/list_changed）。
     *
     * <p>同 method 可注册多个 handler（按注册顺序依次分发）；未注册该 method 时入站通知
     * 静默忽略（对齐 CC 无 handler 时 SDK 丢弃）。
     *
     * @param method  MCP 通知方法名（JSON-RPC method，如 notifications/resources/list_changed）
     * @param handler 通知处理器（接收 notification params）
     */
    void setNotificationHandler(String method, McpNotificationHandler handler);

    /** 关闭 transport (幂等). */
    void close();

    /** 当前状态. */
    State getState();

    /** transport 生命周期状态. */
    enum State { NOT_CONNECTED, CONNECTED, CLOSED }

    /** transport 配置. */
    record TransportConfig(
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        // [Session H P2-5] serverName · 对齐 CC client.ts MCPServerConnection.name.
        //   HttpMcpTransport 401 抛 McpAuthError 时需要 serverName 标识降级目标
        //   (CC client.ts:3194-3208 throw new McpAuthError(name, ...)).
        //   可为 null (无 server 上下文启动, 如纯工具测试).
        String serverName,
        // [MCP-I-1 T8] type · 显式传输类型（stdio/sse/sse-ide/ws-ide/http/ws/sdk/claudeai-proxy）。
        //   CC original: McpServerConfig.type（types.ts:124-135）。null = 旧请求缺省 → 工厂按
        //   command/url 推导兼容（仅过渡）。
        String type
    ) {
        /** 旧 5 参构造（缺 type → 工厂按 command/url 推导）· 兼容既有调用方。 */
        public TransportConfig(String command, List<String> args, Map<String, String> env,
                String cwd, String serverName) {
            this(command, args, env, cwd, serverName, null);
        }
    }
}
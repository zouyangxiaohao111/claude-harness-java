package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK MCP transport 适配器 · 对齐 CC SdkControlClientTransport（SdkControlTransport.ts:60-95）。
 *
 * <p>L1 语义: type='sdk' 的 MCP server 由 SDK 进程内承载，CLI 侧通过 SdkControlClientTransport
 * 把 JSON-RPC 请求包装成控制消息走 stdout 桥接。Java web 后端无真实 SDK 进程（Q-26 拍板纳入
 * sdk 传输，但真实 server 未验证 —— 见 R3）。
 *
 * <p>L2 契约（最小 smoke）:
 * <ul>
 *   <li>构造 + {@link #start} 为 no-op（对齐 SdkControlClientTransport.start 空实现）；</li>
 *   <li>{@link #sendRequest}/{@link #sendNotification} → 异常完成 + TODO（真实 SDK 路由待 MCP-I-2）；</li>
 *   <li>{@link #close} 幂等。</li>
 * </ul>
 */
public final class SdkMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(SdkMcpTransport.class);
    private final SdkControlTransport.SdkControlClientTransport delegate;
    private volatile State state = State.NOT_CONNECTED;

    public SdkMcpTransport(String serverName) {
        // R3: 无真实 SDK 进程回调 → 占位；真实路由（mcp_tool_call → SDK）待 MCP-I-2 接线。
        this.delegate = new SdkControlTransport.SdkControlClientTransport(
            serverName == null ? "sdk" : serverName,
            (name, msg) -> {
                CompletableFuture<SdkControlTransport.JsonRpcMessage> f = new CompletableFuture<>();
                f.completeExceptionally(new UnsupportedOperationException(
                    "SDK MCP transport 未接线：无 SDK 进程回调（TODO MCP-I-2）"));
                return f;
            });
    }

    @Override
    public void start(TransportConfig config) {
        state = State.CONNECTED;
        log.info("[SdkMcpTransport] start server={}（SDK 进程内承载，无外部连接）",
            config == null ? null : config.serverName());
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        f.completeExceptionally(new UnsupportedOperationException(
            "SDK MCP transport 未接线：sendRequest 无 SDK 进程回调（TODO MCP-I-2）"));
        return f;
    }

    @Override
    public void sendNotification(String method, Object params) {
        // 未接线 → 静默丢弃（对齐 CC SDK 无 handler 时丢弃）
        log.warn("[SdkMcpTransport] sendNotification 未接线 method={}（TODO MCP-I-2）", method);
    }

    @Override
    public void setNotificationHandler(String method, McpNotificationHandler handler) {
        // 未接线
    }

    @Override
    public void close() {
        state = State.CLOSED;
        delegate.close();
    }

    @Override
    public State getState() {
        return state;
    }
}

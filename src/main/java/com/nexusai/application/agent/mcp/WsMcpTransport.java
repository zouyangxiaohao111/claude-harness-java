package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP WebSocket transport · 对齐 CC utils/mcpWebSocketTransport.ts.
 *
 * <p>L1 语义: 长连接 WebSocket 与 MCP server 双向通信, 与 stdio 同协议层 (JSON-RPC 2.0),
 * 帧格式为文本 JSON. 比 stdio 优势: 多路复用, 服务端可主动推送 notifications.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 文本帧 = JSON-RPC 2.0 message</li>
 *   <li><b>A2 Golden Trace</b>: connect → initialize → tools/list → tools/call → close</li>
 *   <li><b>A3</b>: NOT_CONNECTED → CONNECTED → CLOSED 严格单向</li>
 *   <li><b>A4</b>: sendRequest 必等 id 匹配响应, 单连接串行</li>
 *   <li><b>A5</b>: 不悬挂 (timeout via WebSocket.Builder.connectTimeout)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): java.net.http.WebSocket (JDK 11+) + ConcurrentHashMap 配 id → future.
 */
@Component
public class WsMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(WsMcpTransport.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicReference<McpTransport.State> state = new AtomicReference<>(McpTransport.State.NOT_CONNECTED);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private WebSocket ws;

    /**
     * [S2] 保存的 transport 配置 · start(config) 时保存，供 {@link McpAuthHeaderProvider}
     * 计算 serverKey 读 DB token（serverName/type/command/env）。
     */
    private McpTransport.TransportConfig config;

    /**
     * [S2] MCP OAuth Bearer token 提供者 · null = 未接线（测试/无认证）→ 不注入
     * Authorization 头（no-op）。对齐 CC client.ts:744-775 WS 连接时附加 Authorization
     * 头（Java 用 WebSocket.Builder.header），MCP server OAuth token 替代 session ingress token。
     */
    private final McpAuthHeaderProvider authHeaderProvider;

    /** [S2] 本次连接使用的 Bearer access token（供 4003-close 刷新 + 重连比对）。 */
    private volatile String lastWsToken;

    /**
     * [Q-11-8] 连接超时（毫秒）· 对齐 conn-fix 修复：start() 用 {@code ready.get(timeout)}
     * 兜底，重连尝试必须 fail-fast 不挂死（否则 WS 断线后的自动重连会永久阻塞建连线程）。
     */
    static final long CONNECT_TIMEOUT_MS = 10_000L;

    /** [Q-11-8] 建连就绪信号 · onOpen complete(null)；onError completeExceptionally（no-op 若已完成）。 */
    private volatile CompletableFuture<Void> ready;

    /**
     * [R2-1] WS 断开通知器 · McpToolPool 在 connectTransport 内经 {@code instanceof} 接线
     * （{@code config.type()=="ws"}）。
     *
     * <p>对齐 CC {@code client.onclose}（client.ts:1374-1402）语义：close → 清 memo/fetch 缓存
     * → 惰性重连（ensureConnectedClient client.ts:1688-1704）。Java 落地：McpToolPool 收到
     * notifier 后清 per-server fetch 缓存；authRequired=true（close 4003 且 token 刷新无法恢复）
     * → 走 needs-auth + S1 OAuth → 成功后自动重连；false → 有界退避主动重连。
     */
    private volatile WsDisconnectNotifier disconnectNotifier;

    /**
     * [R2-1] WS 断开通知回调 · 对齐 CC {@code client.onclose(client.ts:1374-1402)} +
     * {@code client.onerror(client.ts:1266-1371)}。
     *
     * @param authRequired true = close 4003（unauthorized）且 token 刷新无法恢复 → 调用方应触发
     *                     needs-auth + S1 OAuth 流（performMCPOAuthFlow）；false = 普通断开
     *                     （含 4003 但 token 已刷新）→ 清缓存 + 惰性/退避重连
     */
    @FunctionalInterface
    public interface WsDisconnectNotifier {
        void onWsDisconnected(boolean authRequired);
    }

    public WsMcpTransport() {
        this(null);
    }

    public WsMcpTransport(McpAuthHeaderProvider authHeaderProvider) {
        this.authHeaderProvider = authHeaderProvider;
    }

    /**
     * P2-15: server→client 通知处理器注册表 · 对齐 CC {@code client.setNotificationHandler}
     * （useManageMCPConnections.ts:619/:669/:707）。key = JSON-RPC 通知 method，value = 处理器列表。
     */
    private final Map<String, List<McpNotificationHandler>> notificationHandlers = new ConcurrentHashMap<>();

    @Override
    public void start(McpTransport.TransportConfig config) {
        // [S2] 保存完整 config 供 McpAuthHeaderProvider 计算 serverKey（Bearer 注入）
        this.config = config;
        // WS transport 用 command 字段作 URL (与 SseMcpTransport 一致)
        String url = config == null || config.command() == null ? null : config.command();
        if (url == null || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
            throw new IllegalStateException("WS transport requires ws:// or wss:// URL");
        }
        this.ready = new CompletableFuture<>();
        WsMcpTransport self = this;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        WebSocket.Builder wsBuilder = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10));
        // [S2] 连接时附加 Authorization: Bearer <accessToken>（对齐 CC client.ts:744-775 WS
        // 连接时附加 Authorization 头；Java WebSocket.Builder.header 对应）
        this.lastWsToken = resolveBearer();
        if (lastWsToken != null) {
            wsBuilder.header("Authorization", "Bearer " + lastWsToken);
            if (log.isDebugEnabled()) {
                log.debug("[WsMcpTransport] 连接附加 Bearer token server={}", serverName());
            }
        }
        wsBuilder.buildAsync(URI.create(url), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    state.set(McpTransport.State.CONNECTED);
                    self.ws = webSocket;
                    log.info("[WsMcpTransport] connected url={}", url);
                    ready.complete(null);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    handleIncoming(data.toString());
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    handleWsClose(statusCode, reason);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    handleWsError(error);
                }
            });
        try {
            // [Q-11-8] ready.get(timeout) 超时兜底：建连失败/超时抛可诊断异常，不再永久挂死
            // （WS 断线自动重连依赖 start() fail-fast——重连尝试若挂死，自动重连即失效）。
            ready.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                "WS connect timeout after " + CONNECT_TIMEOUT_MS + "ms: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WS connect interrupted: " + url, e);
        } catch (ExecutionException e) {
            // [OAuth-R1] 握手 403 + WWW-Authenticate: insufficient_scope → 标记 step-up pending
            // （WS 无 fetch 层，等价检测点是握手响应 WebSocketHandshakeException.getResponse()）。
            handleHandshakeStepUp(e.getCause() != null ? e.getCause() : e);
            throw new IllegalStateException("WS connect failed: "
                + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
        if (state.get() != McpTransport.State.CONNECTED) {
            CompletableFuture<JsonNode> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("transport not connected"));
            return failed;
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params == null ? Map.of() : params
            );
            ws.sendText(mapper.writeValueAsString(rpc), true);
            log.debug("[WsMcpTransport] sent request id={} method={}", id, method);
        } catch (Exception e) {
            pending.remove(id);
            fut.completeExceptionally(e);
        }
        return fut;
    }

    @Override
    public void sendNotification(String method, Object params) {
        if (state.get() != McpTransport.State.CONNECTED) {
            throw new IllegalStateException("transport not connected");
        }
        try {
            Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params == null ? Map.of() : params
            );
            ws.sendText(mapper.writeValueAsString(rpc), true);
        } catch (Exception e) {
            throw new IllegalStateException("WS send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void setNotificationHandler(String method, McpNotificationHandler handler) {
        notificationHandlers.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (log.isDebugEnabled()) {
            log.debug("[WsMcpTransport] 注册通知处理器 method={}", method);
        }
    }

    @Override
    public void close() {
        if (state.get() == McpTransport.State.CLOSED) {
            return;
        }
        state.set(McpTransport.State.CLOSED);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client close");
            } catch (Exception e) {
                log.debug("[WsMcpTransport] close error ignored: {}", e.getMessage());
            }
        }
        failAllPending(new IllegalStateException("transport closed"));
        log.info("[WsMcpTransport] closed");
    }

    @Override
    public McpTransport.State getState() {
        return state.get();
    }

    /**
     * [R2-1] 注册 WS 断开通知器 · McpToolPool.connectTransport 内接线（{@code instanceof}
     * 判定，config.type()=="ws"）；测试可直接设。
     *
     * @param notifier 断开回调；null 清除（不触发）
     */
    public void setDisconnectNotifier(WsDisconnectNotifier notifier) {
        this.disconnectNotifier = notifier;
    }

    /** [R2-1] 测试观察点：当前断开通知器。 */
    WsDisconnectNotifier disconnectNotifier() {
        return disconnectNotifier;
    }

    /**
     * [R2-1] 测试观察点：设置传输状态（供 handleWsClose 通知语义测试；生产不调用）。
     *
     * @param s 目标状态
     */
    void setStateForTest(McpTransport.State s) {
        this.state.set(s);
    }

    /** [R2-1] 测试观察点：注入 lastWsToken（供 4003+refresh 成功 → authRequired=false 测试；生产不调用）。 */
    void setLastWsTokenForTest(String token) {
        this.lastWsToken = token;
    }

    /** [R2-1] 测试观察点：注入 config（供 refreshOn401 走 provider 路径测试；生产不调用）。 */
    void setConfigForTest(McpTransport.TransportConfig c) {
        this.config = c;
    }

    /**
     * [R2-1] WS 关闭处理 · 对齐 CC {@code client.onclose}（client.ts:1374-1402）。
     *
     * <p>内部职责（Java 语义）：
     * <ol>
     *   <li>状态置 CLOSED（A3 NOT_CONNECTED→CONNECTED→CLOSED 严格单向）</li>
     *   <li>close 4003（unauthorized）→ 尝试 token 刷新（S2 refreshOn401）；刷新成功 →
     *       新 token 写回 lastWsToken + DB（{@code refreshAndGetAccessToken} 内部持久化），
     *       下次重连 {@link #resolveBearer} 取新 token（对齐 CC WS 4003 refreshHeaders→reconnect，
     *       mcpWebSocketTransport.ts 对应 SDK 段）；刷新失败 → authRequired=true（needs-auth）</li>
     *   <li>failAllPending（同 transport 在途请求一并失败，防悬挂 —— 对齐 CC client.close →
     *       transport.onclose → SDK _onclose reject 所有 pending，client.ts:1234-1239）</li>
     *   <li>非连接期失败（wasConnected）→ 触发断开 notifier（McpToolPool 清缓存 + 退避重连）</li>
     * </ol>
     *
     * <p><b>wasConnected 判定已区分用户主动关闭</b>：{@link #close()} 同步置 CLOSED →
     * 随后的 onClose 进入时 state==CLOSED → 不触发 notifier（对齐 CC 用户主动 close 不重连）。
     * 连接期失败（onOpen 未触发，state==NOT_CONNECTED）同样不触发 —— start() 已抛错走批连接
     * fail-soft（对齐 CC WS 无 3-strike 终端错误计数，client.ts:1333-1337 仅 sse/http）。
     *
     * @param statusCode close code（4003 = 认证失败）
     * @param reason     close reason
     */
    void handleWsClose(int statusCode, String reason) {
        boolean wasConnected = state.get() == McpTransport.State.CONNECTED;
        state.set(McpTransport.State.CLOSED);
        boolean authRequired = false;
        if (statusCode == 4003) {
            if (lastWsToken != null) {
                String newToken = refreshOn401(lastWsToken);
                if (newToken != null) {
                    lastWsToken = newToken;
                    log.info("[WsMcpTransport] close 4003 已刷新 token，下次重连用新 token（对齐 CC WS 4003 refreshHeaders→reconnect）server={}",
                        serverName());
                } else {
                    authRequired = true;
                    log.warn("[WsMcpTransport] close 4003 刷新 token 失败，降级 needs-auth server={}",
                        serverName());
                }
            } else {
                // 无 token 连接被 4003 拒绝 → 需认证
                authRequired = true;
            }
            // 4003 = 认证失败（对齐 HttpMcpTransport 401 → McpAuthError 降级语义）
            failAllPending(new McpAuthError(serverName(),
                "MCP server requires re-authorization (token expired)"));
        } else {
            failAllPending(new IllegalStateException("ws closed: " + statusCode + " " + reason));
        }
        log.info("[WsMcpTransport] closed code={} reason={}", statusCode, reason);
        if (wasConnected) {
            WsDisconnectNotifier notifier = disconnectNotifier;
            if (notifier != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[WsMcpTransport] 触发断开 notifier server={} authRequired={}",
                        serverName(), authRequired);
                }
                notifier.onWsDisconnected(authRequired);
            }
        }
    }

    /**
     * [R2-1] WS 错误处理 · 对齐 CC {@code client.onerror}（client.ts:1266-1371）。
     *
     * <p>java.net.http.WebSocket 契约：onError 之后必跟 onClose → 状态/notifier 由
     * {@link #handleWsClose} 兜底，此处只 failAllPending + 日志（对齐 CC WS 无 3-strike——
     * 终端错误计数仅 sse/http/claudeai-proxy，client.ts:1333-1337）。连接期错误额外 complete
     * ready 异常（Q-11-8：start() ready.get 抛 ExecutionException，不再挂死）。
     *
     * @param error 连接/运行时错误
     */
    void handleWsError(Throwable error) {
        CompletableFuture<Void> r = ready;
        if (r != null) {
            r.completeExceptionally(error);
        }
        // [OAuth-R1] 握手失败路径同样检测 403 insufficient_scope（onError 兜底 start 的
        // ExecutionException 捕获；两者互斥，靠 instanceof WebSocketHandshakeException 判定）
        handleHandshakeStepUp(error);
        failAllPending(error);
        log.warn("[WsMcpTransport] error: {}", error.getMessage());
    }

    // ────────────── [OAuth-R1] step-up 检测（握手 403 + insufficient_scope）──────────────

    /**
     * [OAuth-R1] WS 握手失败若为 403 + WWW-Authenticate: insufficient_scope → 标记 server
     * step-up pending（对齐 CC {@code wrapFetchWithStepUpDetection} auth.ts:1354-1374；
     * HTTP(S) 传输在 fetch 层检测，WS 无 fetch 层，等价检测点是握手响应）。
     *
     * <p>java.net.http.WebSocket 握手失败抛 {@link java.net.http.WebSocketHandshakeException}，
     * 其 {@code getResponse()} 携带 HTTP 响应（JDK 11+）。scope 提取复用
     * {@link McpAuthHeaderProvider#extractScopeFromWwwAuthenticate}（CC auth.ts:1365 regex，
     * RFC 6750 §3 带/不带引号）。
     *
     * @param error 握手失败异常（可能为 WebSocketHandshakeException，也可能是其包装）
     */
    private void handleHandshakeStepUp(Throwable error) {
        if (error instanceof java.net.http.WebSocketHandshakeException wse) {
            java.net.http.HttpResponse<?> resp = wse.getResponse();
            if (resp != null && resp.statusCode() == 403) {
                String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse(null);
                // CC 大小写敏感 includes（auth.ts:1362 wwwAuth?.includes('insufficient_scope')）
                if (wwwAuth != null && wwwAuth.contains("insufficient_scope")) {
                    String scope = McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(wwwAuth);
                    if (scope != null && authHeaderProvider != null && config != null) {
                        authHeaderProvider.markStepUpPending(config, scope);
                        if (log.isDebugEnabled()) {
                            log.debug("[WsMcpTransport] 握手 403 insufficient_scope，已标记 step-up pending server={} scope={}",
                                serverName(), scope);
                        }
                    }
                }
            }
        }
    }

    // ────────────── 内部辅助 ──────────────

    /** [S2] 当前 Bearer token 解析（无 provider / 无 config → null）。 */
    private String resolveBearer() {
        if (authHeaderProvider == null || config == null) {
            return null;
        }
        return authHeaderProvider.resolveAccessToken(config);
    }

    /** [S2] 4003-close 刷新：无 provider / 无 config / 无 failedToken → null（不刷新）。 */
    private String refreshOn401(String failedToken) {
        if (authHeaderProvider == null || config == null || failedToken == null) {
            return null;
        }
        return authHeaderProvider.refreshAndGetAccessToken(config, failedToken);
    }

    /** 当前 server 名（供日志 / McpAuthError；无 config → "?"）。 */
    private String serverName() {
        return config == null || config.serverName() == null ? "?" : config.serverName();
    }

    private void handleIncoming(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            if (node.has("id") && !node.get("id").isNull()) {
                long id = node.get("id").asLong();
                CompletableFuture<JsonNode> fut = pending.remove(id);
                if (fut != null) {
                    if (node.has("error")) {
                        fut.completeExceptionally(new IllegalStateException(
                            "JSON-RPC error: " + node.get("error")));
                    } else {
                        fut.complete(node.get("result"));
                    }
                }
            } else if (node.has("method")) {
                // P2-15: 服务端主动 notification（含 notifications/{tools,prompts,resources}/list_changed）
                // → 分发到已注册处理器（对齐 CC setNotificationHandler useManageMCPConnections.ts:619-751）。
                dispatchNotification(node.get("method").asText(), node.get("params"));
            }
        } catch (Exception e) {
            log.warn("[WsMcpTransport] parse failed: {}", e.getMessage());
        }
    }

    /**
     * P2-15: 按 method 分发 server→client 通知到已注册处理器 · 对齐 CC
     * {@code client.setNotificationHandler}（useManageMCPConnections.ts:619-751）。
     *
     * <p>未注册该 method 时静默忽略。分发在独立线程执行——本方法从 WS onText 回调线程
     * 调用，处理器内含阻塞 sendRequest 往返，同步执行会阻塞回调线程致响应无人读取死锁。
     *
     * @param method JSON-RPC 通知 method
     * @param params 通知 params（list_changed 恒为 {}）
     */
    private void dispatchNotification(String method, JsonNode params) {
        List<McpNotificationHandler> handlers = notificationHandlers.get(method);
        if (handlers == null || handlers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[WsMcpTransport] 收到通知但无处理器 method={}", method);
            }
            return;
        }
        for (McpNotificationHandler handler : handlers) {
            CompletableFuture.runAsync(() -> {
                try {
                    handler.handle(params);
                } catch (Exception e) {
                    log.warn("[WsMcpTransport] 通知处理器执行失败 method={}: {}", method, e.getMessage());
                }
            });
        }
    }

    private void failAllPending(Throwable cause) {
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        pending.clear();
    }
}
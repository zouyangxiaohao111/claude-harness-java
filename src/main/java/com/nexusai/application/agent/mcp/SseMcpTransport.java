package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP SSE (Server-Sent Events) transport · 对齐 CC MCPConnectionManager.tsx SSE path
 * （SSEClientTransport + eventSourceInit.fetch 持久 GET，client.ts:619-677）。
 *
 * <p>L1 语义: 客户端通过 HTTP POST 发请求 (JSON-RPC over HTTP), 服务端通过 SSE 推响应
 * (event-stream, 单向服务器→客户端)。<b>[S5] OAuth/PKCE 支持</b>: 连接 GET 携带 Bearer
 * access_token（对齐 CC eventSourceInit.fetch client.ts:648-671），401 时按需触发 S1
 * OAuth 流（performMCPOAuthFlow + token 注入，client.ts:621-660）。
 *
 * <p><b>[S02] 持久 GET 事件源流</b>（X-3/D-11 改造）：start() 建立持久 GET 连接
 * （读阶段免超时，对齐 CC eventSourceInit.fetch 注释「The EventSource connection is
 * long-lived, applying a 60-second timeout would kill it」client.ts:643-647），daemon
 * reader 按 SSE 帧解析：id 匹配 complete pending / method 通知 → 分发 / method+id 请求
 * → roots/list 响应或 -32601。GET 流 EOF → 1s 延迟重建（SDK maxRetries=2，client.ts:
 * 1338-1341），耗尽 → 「Maximum reconnection attempts」→ 立即关断 + pending 拒绝
 * （client.ts:1342-1348）；3-strike 终端错误计数（client.ts:1228+1333-1365）。
 *
 * <p><b>[S02 D-S02-2] POST 去同步阻塞</b>：POST 发后仅读状态码（2xx → 立即返回），
 * 响应体在后台任务有界读取（60s read timeout，对齐 CC MCP_REQUEST_TIMEOUT_MS
 * client.ts:463）；空体/读超时 → 放弃 POST 流，future 由持久 GET 流交付——标准 SSE
 * server POST 返回空 body 不再悬挂（X-3 根因消除）。
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #start} 建立持久 GET 流, 失败抛 IllegalStateException; 401 且认证无法恢复
 *       → 抛 {@link McpAuthError}（needs-auth 降级）</li>
 *   <li>{@link #sendRequest} HTTP POST（非阻塞） + 后台读, 响应经 GET/POST SSE 流按 id
 *       匹配 → complete future（不悬挂）</li>
 *   <li>{@link #sendNotification} 同 sendRequest 但不读响应</li>
 *   <li>{@link #close} 断开 GET + 中断 reader + 拒绝 pending, 幂等</li>
 * </ul>
 *
 * <p>L3 (Java idiom): HttpURLConnection (JDK 自带, 无第三方依赖) + ConcurrentHashMap 配 id →
 * future + AtomicLong 分配 id, 取代 CC axios + EventSource.
 */
@Component
public class SseMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(SseMcpTransport.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicReference<McpTransport.State> state = new AtomicReference<>(McpTransport.State.NOT_CONNECTED);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    // [S02] 常量 · 对齐 CC：
    //   GET 状态行阶段 connect+read 超时 30s = getConnectionTimeoutMs（client.ts:456-458，
    //   env MCP_TIMEOUT || 30000，读阶段置 0 免超时）
    //   POST 响应流有界读取 60s = MCP_REQUEST_TIMEOUT_MS（client.ts:463）
    //   GET 流 EOF → 重连延迟 1s（SDK SSE reconnect 退避，client.ts:1338-1341）
    //   重连尝试上限 2 = SDK maxRetries（client.ts:1338-1341）
    //   3-strike = MAX_ERRORS_BEFORE_RECONNECT（client.ts:1228）
    private static final int GET_CONNECT_READ_TIMEOUT_MS = 30_000;
    private static final int POST_RESPONSE_READ_TIMEOUT_MS = 60_000;
    private static final long SSE_RECONNECT_DELAY_MS = 1_000L;
    private static final int MAX_SSE_RECONNECT_ATTEMPTS = 2;
    private static final long MAX_ERRORS_BEFORE_RECONNECT = 3;

    // [tool-v3 合并保留] CC 名常量别名 · 对齐 CC getConnectionTimeoutMs()/MCP_REQUEST_TIMEOUT_MS
    // （client.ts:456-458/:463）。本类（取 master 结构）以 GET_CONNECT_READ_TIMEOUT_MS 30s +
    // POST_RESPONSE_READ_TIMEOUT_MS 60s 承载同值；SseMcpTransportOAuthTest 的
    // timeoutConstants_alignCc 直接断言这两个 CC 名常量，保留别名保证测试编译通过。
    static final long MCP_REQUEST_TIMEOUT_MS = 60_000L;
    static final long CONNECT_TIMEOUT_MS = 30_000L;

    private URI endpoint;
    /** 当前持久 GET 连接（SSE 事件源流）。 */
    private volatile HttpURLConnection sseConnection;
    private volatile Thread readerThread;
    private volatile boolean closed = false;

    /**
     * [S02] 流代数 · 新流建立时自增，使旧 reader 的 drop 处理失效（避免旧流 EOF 误触发
     * 重连/计数——旧连接被新流替换时会 disconnect 导致 readLine 抛异常）。
     */
    private final AtomicLong streamGeneration = new AtomicLong();

    /** [S02] 重连调度器（daemon 单线程）· 延迟可测试注入（对齐 setWsReconnectScheduler 先例）。 */
    private final ScheduledExecutorService reconnectScheduler =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "mcp-sse-reconnect");
            t.setDaemon(true);
            return t;
        });
    private volatile long reconnectDelayMs = SSE_RECONNECT_DELAY_MS;

    /** [S02] 3-strike 终端错误计数 · 对齐 CC client.ts:1228+1333-1365（非终端重置）。 */
    private final AtomicLong consecutiveConnectionErrors = new AtomicLong();
    /** [S02] 当前重连章节失败次数（成功建流后清零）· SDK maxRetries=2 语义。 */
    private final AtomicLong reconnectFailures = new AtomicLong();
    /** [S02] 关断守卫 · 对齐 CC hasTriggeredClose（client.ts:1230-1247，close 链防重入）。 */
    private volatile boolean hasTriggeredClose = false;

    /**
     * [S2] 保存的 transport 配置 · start(config) 时保存，供 {@link McpAuthHeaderProvider}
     * 计算 serverKey 读 DB token（serverName/type/command/env）。
     */
    private McpTransport.TransportConfig config;

    /**
     * [S2] MCP OAuth Bearer token 提供者 · null = 未接线（测试/无认证）→ 不注入
     * Authorization 头（no-op）。对齐 CC client.ts:621-660 SSE authProvider auth()→tokens()
     * 语义（EventSource/auth headers 携带 Bearer access_token）。
     */
    private final McpAuthHeaderProvider authHeaderProvider;

    public SseMcpTransport() {
        this(null);
    }

    public SseMcpTransport(McpAuthHeaderProvider authHeaderProvider) {
        this.authHeaderProvider = authHeaderProvider;
    }

    /**
     * P2-15: server→client 通知处理器注册表 · 对齐 CC {@code client.setNotificationHandler}
     * （useManageMCPConnections.ts:619/:669/:707）。key = JSON-RPC 通知 method，value = 处理器列表。
     *
     * <p>[S02] 持久 GET reader 实装后入站通知经 GET 流真实可达（此前仅 POST 响应体复用
     * SSE 流时可达）。
     */
    private final Map<String, List<McpNotificationHandler>> notificationHandlers = new ConcurrentHashMap<>();

    @Override
    public void start(McpTransport.TransportConfig config) {
        // [S2] 保存完整 config 供 McpAuthHeaderProvider 计算 serverKey（Bearer 注入）
        this.config = config;
        // SSE transport 用 endpoint 字段 (URL string) 替代 command
        String url = config == null || config.command() == null ? null : config.command();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("SSE transport requires endpoint URL");
        }
        try {
            this.endpoint = URI.create(url);
            // [S5] 连接 GET 携带 Bearer access_token（对齐 CC SSE eventSourceInit.fetch 注入
            // authProvider.tokens()，client.ts:648-671）：OAuth 保护的 SSE server 首连 GET
            // 必须带 Authorization，否则直接 401。
            String failedToken = resolveBearer();
            StreamHandle handle = openStreamHandle(failedToken);
            if (handle.code() == 401) {
                // [S5] 401 挑战：①S2 refresh → ②仍失败 → 触发 S1 OAuth 流（performMCPOAuthFlow）
                // → 注入新 token → 重试探针一次（对齐 CC SDK auth()，client.ts:621-660 + :1105-1106）。
                handle = handleConnect401(url, failedToken);
            }
            if (handle.code() == 401) {
                // 仍 401 → needs-auth 降级（CC UnauthorizedError → handleRemoteAuthFailure → needs-auth，
                // client.ts:1105-1106）。McpToolPool 连接期捕获 McpAuthError → 伪工具替换真实工具。
                this.state.set(McpTransport.State.NOT_CONNECTED);
                throw new McpAuthError(serverName(),
                    "MCP server requires re-authorization (token expired)");
            }
            if (handle.code() >= 500) {
                throw new IllegalStateException("SSE endpoint " + url + " returned " + handle.code());
            }
            if (handle.conn() == null) {
                // 非 2xx（如 403 insufficient_scope step-up 标记）→ 维持既有 CONNECTED 行为，
                // 不建立持久 GET 流（SseMcpTransportOAuthTest 兼容）。
                this.state.set(McpTransport.State.CONNECTED);
                log.info("[SseMcpTransport] connected endpoint={} probe={}（无持久 GET 流）", url, handle.code());
                return;
            }
            // [S02] 2xx → 连接转 reader 线程（读阶段免超时），持久 GET 流建立
            this.state.set(McpTransport.State.CONNECTED);
            startStreamReader(handle.conn());
            log.info("[SseMcpTransport] connected endpoint={} probe={}（持久 GET 流已建立）", url, handle.code());
        } catch (McpAuthError e) {
            // needs-auth 降级异常需原样上抛（不包装），供 McpToolPool 识别为认证失败
            this.state.set(McpTransport.State.NOT_CONNECTED);
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            this.state.set(McpTransport.State.NOT_CONNECTED);
            throw new IllegalStateException("SSE connect failed: " + e.getMessage(), e);
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
            // [S02 D-S02-2] POST 非阻塞：状态码阶段后立即返回，响应由后台/GET 流交付
            postRpc(rpc);
            log.debug("[SseMcpTransport] sent request id={} method={}", id, method);
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
            postRpc(Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params == null ? Map.of() : params
            ));
        } catch (Exception e) {
            throw new IllegalStateException("SSE POST failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void setNotificationHandler(String method, McpNotificationHandler handler) {
        notificationHandlers.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (log.isDebugEnabled()) {
            log.debug("[SseMcpTransport] 注册通知处理器 method={}", method);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            hasTriggeredClose = true;
            state.set(McpTransport.State.CLOSED);
            // [S02] 断开持久 GET 连接 + 中断 reader（幂等）
            HttpURLConnection conn = sseConnection;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                    // 已断开
                }
            }
            if (readerThread != null) {
                readerThread.interrupt();
            }
            reconnectScheduler.shutdownNow();
            for (var entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(new IllegalStateException("transport closed"));
            }
            pending.clear();
            log.info("[SseMcpTransport] closed");
        }
    }

    @Override
    public McpTransport.State getState() {
        return state.get();
    }

    // ────────────── [S02] 持久 GET 流 + 重连 + 3-strike ──────────────

    /** GET 探测结果：code + 2xx 时的连接（转 reader）。 */
    private record StreamHandle(int code, HttpURLConnection conn) {
    }

    /**
     * [S02] GET 连接（状态行阶段 30s connect+read 超时）· 对齐 CC eventSourceInit.fetch
     * （client.ts:648-671）：Accept text/event-stream + Bearer（null 不带）。2xx → 返回
     * 连接供 reader 持久读取（读阶段免超时）；401/其它非 2xx → 断开并返回状态码。
     */
    private StreamHandle openStreamHandle(String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) endpoint.toURL().openConnection();
        conn.setConnectTimeout(GET_CONNECT_READ_TIMEOUT_MS);
        conn.setReadTimeout(GET_CONNECT_READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "text/event-stream");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        int code = conn.getResponseCode();
        if (code == 403 && hasInsufficientScope(conn)) {
            // [OAuth-R1] 连接 GET 返回 403 insufficient_scope → 标记 step-up pending
            // （对齐 CC wrapFetchWithStepUpDetection auth.ts:1354-1374 包裹 eventSourceInit.fetch
            // 语义，client.ts:648-671）。
            markStepUpPending(conn);
        }
        if (code >= 200 && code < 300) {
            return new StreamHandle(code, conn);
        }
        conn.disconnect();
        if (log.isDebugEnabled()) {
            log.debug("[SseMcpTransport] GET 连接完成 url={} code={} 带Bearer={}",
                endpoint, code, bearerToken != null);
        }
        return new StreamHandle(code, null);
    }

    /**
     * [S5] 连接期 401 挑战处理 · 对齐 CC SDK auth()（client.ts:621-660）：
     * <ol>
     *   <li>①S2 refresh — {@link #refreshOn401}（token 过期但有 refreshToken → 静默恢复）</li>
     *   <li>②refresh 无法恢复（无 refreshToken / 刷新失败）→ 触发 S1 OAuth 流
     *       {@link McpAuthHeaderProvider#performOAuthFlow}（授权码 + PKCE + loopback 回调，
     *       授权 URL 经日志可观测）；成功 → 取新 token</li>
     *   <li>③新 token → 重探 GET 一次</li>
     * </ol>
     * 仍 401 → 返回 code=401（调用方 {@link #start} 降级 needs-auth）。
     *
     * @param url         SSE endpoint
     * @param failedToken 首探针携带的 token（可能 null = 无 token）
     * @return 重试后最终结果（code=401 = 认证仍失败）
     */
    private StreamHandle handleConnect401(String url, String failedToken) {
        String newToken = refreshOn401(failedToken);
        if (newToken == null && authHeaderProvider != null) {
            if (log.isDebugEnabled()) {
                log.debug("[SseMcpTransport] 连接 401 且 refresh 无法恢复，触发 S1 OAuth 流 server={}", serverName());
            }
            McpAuth.AuthResult result = authHeaderProvider.performOAuthFlow(config,
                authUrl -> log.info("[SseMcpTransport] OAuth 授权 URL 已生成 server={} authUrl={}",
                    serverName(), authUrl));
            if (result.success()) {
                newToken = resolveBearer();
            } else {
                log.warn("[SseMcpTransport] 连接期 OAuth 流未成功 server={} reason={}: {}",
                    serverName(), result.errorReason(), result.errorMessage());
            }
        }
        if (newToken != null) {
            try {
                return openStreamHandle(newToken);
            } catch (IOException e) {
                log.warn("[SseMcpTransport] 重探 GET 失败 server={}: {}", serverName(), e.getMessage());
                return new StreamHandle(401, null);
            }
        }
        return new StreamHandle(401, null);
    }

    /**
     * [S02] 启动持久 GET 流 reader（daemon 线程，读阶段免超时）· 对齐 CC eventSourceInit.fetch
     * 「GET 不套 60s 超时」（client.ts:643-647）。替换旧空占位 reader 启动方法
     * （D-S02-3 删除）。旧流（被重连替换）由 {@link #streamGeneration} 代数守卫静默退出。
     */
    private void startStreamReader(HttpURLConnection conn) {
        HttpURLConnection old = sseConnection;
        if (old != null && old != conn) {
            try {
                old.disconnect(); // 释放被替换的旧流（旧 reader 捕获 IOException 后按代数退出）
            } catch (Exception ignored) {
            }
        }
        this.sseConnection = conn;
        long gen = streamGeneration.incrementAndGet();
        conn.setReadTimeout(0); // 读阶段免超时（长连接语义）
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = br.readLine()) != null) {
                    handleSseLine(line);
                }
            } catch (Exception e) {
                if (!closed && log.isDebugEnabled()) {
                    log.debug("[SseMcpTransport] GET 流异常 server={}: {}", serverName(), messageOf(e));
                }
            }
            if (closed || gen != streamGeneration.get()) {
                return; // 本流已被 close / 新流取代 → 静默退出（不触发 drop 处理）
            }
            onStreamDropped();
        }, "mcp-sse-reader");
        t.setDaemon(true);
        this.readerThread = t;
        t.start();
    }

    /**
     * [S02] GET 流断开处理（EOF/异常）· 「SSE stream disconnected」终端错误 → 3-strike 计数
     * （CC isTerminalConnectionError client.ts:1260）；≥3 → 关断 + pending 拒绝；否则 1s 延迟
     * 重建（SDK maxRetries=2 重连语义，client.ts:1338-1341）。
     */
    private void onStreamDropped() {
        if (closed || hasTriggeredClose) {
            return;
        }
        long n = consecutiveConnectionErrors.incrementAndGet();
        log.warn("[SseMcpTransport] GET 流断开（SSE stream disconnected）{}/{} server={}",
            n, MAX_ERRORS_BEFORE_RECONNECT, serverName());
        if (n >= MAX_ERRORS_BEFORE_RECONNECT) {
            closeTransportAndRejectPending(new IllegalStateException(
                "max consecutive terminal errors (" + MAX_ERRORS_BEFORE_RECONNECT + ")"));
            return;
        }
        scheduleReconnect();
    }

    /** [S02] 延迟重建 GET 流（1s，可测试注入）。 */
    private void scheduleReconnect() {
        reconnectScheduler.schedule(() -> {
            if (closed || hasTriggeredClose) {
                return;
            }
            try {
                StreamHandle handle = openStreamHandle(resolveBearer());
                if (handle.conn() != null) {
                    reconnectFailures.set(0);
                    startStreamReader(handle.conn());
                    log.info("[SseMcpTransport] SSE GET 流重连成功 server={}", serverName());
                    return;
                }
                onReconnectFailed(handle.code());
            } catch (Exception e) {
                onReconnectFailed(-1);
            }
        }, reconnectDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * [S02] 重连尝试失败处理 · 对齐 CC client.ts:1338-1348：SDK 耗尽自身重连尝试
     * （maxRetries=2）后抛「Maximum reconnection attempts」→ 立即关断 + pending 拒绝
     * （client.ts:1342-1348，不依赖 3-strike）；未耗尽 → 「Failed to reconnect SSE stream」
     * 计入 3-strike（CC isTerminalConnectionError client.ts:1261）。
     *
     * @param code 失败状态码（-1 = 连接异常）
     */
    private void onReconnectFailed(int code) {
        if (closed || hasTriggeredClose) {
            return;
        }
        long attempts = reconnectFailures.incrementAndGet();
        if (attempts >= MAX_SSE_RECONNECT_ATTEMPTS) {
            log.error("[SseMcpTransport] Maximum reconnection attempts（{}）server={}，关断 transport",
                MAX_SSE_RECONNECT_ATTEMPTS, serverName());
            closeTransportAndRejectPending(new IllegalStateException(
                "Maximum reconnection attempts (" + MAX_SSE_RECONNECT_ATTEMPTS + ")"));
            return;
        }
        long n = consecutiveConnectionErrors.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[SseMcpTransport] 重连失败（Failed to reconnect SSE stream）{}/{} server={} code={}",
                attempts, MAX_SSE_RECONNECT_ATTEMPTS, serverName(), code);
        }
        if (n >= MAX_ERRORS_BEFORE_RECONNECT) {
            closeTransportAndRejectPending(new IllegalStateException(
                "max consecutive terminal errors (" + MAX_ERRORS_BEFORE_RECONNECT + ")"));
            return;
        }
        scheduleReconnect();
    }

    /**
     * [S02] 关断 transport + 拒绝全部 pending · 对齐 CC {@code closeTransportAndRejectPending}
     * （client.ts:1240-1247）：hasTriggeredClose 守卫 + CLOSED（陈旧连接不复用）+
     * failAllPending（防悬挂，CC 注释「hung callTool() promises fail with -32000」）。
     */
    private void closeTransportAndRejectPending(Throwable cause) {
        if (hasTriggeredClose) {
            return;
        }
        hasTriggeredClose = true;
        log.warn("[SseMcpTransport] 终端错误关断 transport server={} reason={}", serverName(), messageOf(cause));
        close();
    }

    // ────────────── [S02 D-S02-2] POST 非阻塞发送 + 后台有界读取 ──────────────

    /**
     * [S02] SSE POST（Bearer 注入 + 401-refresh-retry）· 对齐 CC client.ts:621-660 SSE
     * authProvider：每次 POST 附加 {@code Authorization: Bearer <access_token>}；401 时
     * refreshAuthorization 后用新 token 重试一次（retried 守卫防死循环）。仍 401 → 抛
     * {@link McpAuthError}（needs-auth 降级）。
     *
     * <p><b>[S02 D-S02-2] 去同步阻塞</b>：2xx → 状态码阶段后立即返回（调用线程不阻塞），
     * 响应体由 {@link #drainPostResponse} 后台有界读取；标准 SSE server（POST 空 body /
     * 202）→ 后台立即 EOF 放弃，future 由持久 GET 流交付（X-3 悬挂根因消除）。
     */
    private void postRpc(Map<String, Object> rpc) throws IOException {
        postRpc(rpc, resolveBearer(), false);
    }

    private void postRpc(Map<String, Object> rpc, String bearerToken, boolean retried) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) endpoint.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        byte[] body = mapper.writeValueAsBytes(rpc);
        conn.getOutputStream().write(body);
        conn.getOutputStream().flush();
        int code = conn.getResponseCode();
        if (code == 401 && !retried) {
            String newToken = refreshOn401(bearerToken);
            if (newToken != null) {
                conn.disconnect();
                if (log.isDebugEnabled()) {
                    log.debug("[SseMcpTransport] 401 已刷新 token，重试一次 server={}", serverName());
                }
                postRpc(rpc, newToken, true);
                return;
            }
        }
        if (code == 401) {
            // 刷新失败 / 已重试仍 401 → needs-auth 降级（对齐 HttpMcpTransport 401 → McpAuthError）
            conn.disconnect();
            log.info("[SseMcpTransport] SSE POST 返回 401，token 刷新后仍失败，降级 needs-auth server={}",
                serverName());
            throw new McpAuthError(serverName(),
                "MCP server requires re-authorization (token expired)");
        }
        if (code == 403 && hasInsufficientScope(conn)) {
            // [OAuth-R1] 403 + WWW-Authenticate: insufficient_scope → 标记 step-up pending
            // （对齐 CC wrapFetchWithStepUpDetection auth.ts:1354-1374；请求继续以 403 失败，
            // 消费在后续刷新/认证流程，见 McpAuthHeaderProvider.needsStepUp）。
            markStepUpPending(conn);
        }
        if (code >= 400) {
            conn.disconnect();
            throw new IOException("SSE POST failed: HTTP " + code);
        }
        // [S02 D-S02-2] 2xx → 立即返回；响应体后台有界读取（60s read timeout，
        // 对齐 CC MCP_REQUEST_TIMEOUT_MS client.ts:463）
        CompletableFuture.runAsync(() -> drainPostResponse(conn, rpc));
    }

    /**
     * [S02 D-S02-2] POST 响应体后台有界读取 · application/json 含本 id → complete pending；
     * text/event-stream 行 → {@link #handleSseLine}；空体/读超时 → 放弃 POST 流（响应由
     * 持久 GET 流交付，不悬挂——标准 SSE server POST 空 body 场景）。原同步阻塞读路径
     * 整段删除（D-S02-2）。
     */
    private void drainPostResponse(HttpURLConnection conn, Map<String, Object> rpc) {
        try {
            conn.setReadTimeout(POST_RESPONSE_READ_TIMEOUT_MS);
            try (var br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    handleSseLine(line);
                }
            }
        } catch (SocketTimeoutException e) {
            if (log.isDebugEnabled()) {
                log.debug("[SseMcpTransport] POST 响应流读超时（{}s），放弃 POST 流，响应由 GET 流交付 server={}",
                    POST_RESPONSE_READ_TIMEOUT_MS / 1000, serverName());
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SseMcpTransport] POST 响应流读取结束 server={}: {}", serverName(), messageOf(e));
            }
        } finally {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
                // 已断开
            }
        }
    }

    // ────────────── 既有辅助（Bearer / step-up / SSE 帧解析）──────────────

    /** [S2] 当前 Bearer token 解析（无 provider / 无 config → null）。 */
    private String resolveBearer() {
        if (authHeaderProvider == null || config == null) {
            return null;
        }
        return authHeaderProvider.resolveAccessToken(config);
    }

    /** [S2] 401 刷新：无 provider / 无 config / 无 failedToken → null（不刷新）。 */
    private String refreshOn401(String failedToken) {
        if (authHeaderProvider == null || config == null || failedToken == null) {
            return null;
        }
        return authHeaderProvider.refreshAndGetAccessToken(config, failedToken);
    }

    // ────────────── [OAuth-R1] step-up 检测（403 + insufficient_scope）──────────────

    /**
     * [OAuth-R1] 403 + WWW-Authenticate 含 insufficient_scope 判定 · 对齐 CC
     * {@code wrapFetchWithStepUpDetection}（auth.ts:1360-1362：{@code response.status === 403
     * && wwwAuth?.includes('insufficient_scope')}）。HttpURLConnection 头名大小写不敏感。
     *
     * @throws IOException HttpURLConnection.getResponseCode 失败
     */
    private static boolean hasInsufficientScope(HttpURLConnection conn) throws IOException {
        if (conn.getResponseCode() != 403) {
            return false;
        }
        String wwwAuth = conn.getHeaderField("WWW-Authenticate");
        // CC 大小写敏感 includes（auth.ts:1362 wwwAuth?.includes('insufficient_scope')）
        return wwwAuth != null && wwwAuth.contains("insufficient_scope");
    }

    /**
     * [OAuth-R1] 标记 server step-up pending · 无 provider / 无 config 时 no-op。
     * scope 提取复用 {@link McpAuthHeaderProvider#extractScopeFromWwwAuthenticate}
     * （CC auth.ts:1365 regex，RFC 6750 §3 带/不带引号）。
     *
     * @throws IOException HttpURLConnection.getHeaderField 失败
     */
    private void markStepUpPending(HttpURLConnection conn) throws IOException {
        if (authHeaderProvider == null || config == null) {
            return;
        }
        String scope = McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(
            conn.getHeaderField("WWW-Authenticate"));
        if (scope != null) {
            authHeaderProvider.markStepUpPending(config, scope);
            if (log.isDebugEnabled()) {
                log.debug("[SseMcpTransport] 检测 403 insufficient_scope，已标记 step-up pending server={} scope={}",
                    serverName(), scope);
            }
        }
    }

    /** 当前 server 名（供 401 降级日志；无 config → "?"）。 */
    private String serverName() {
        return config == null || config.serverName() == null ? "?" : config.serverName();
    }

    /**
     * [S02] SSE 帧解析（data: 行）· 三分类：
     * <ul>
     *   <li>method+id → server→client 请求：roots/list → 响应，未知 → -32601（不悬挂）</li>
     *   <li>method（无 id）→ server→client 通知 → 分发处理器（P2-15，对齐 CC
     *       setNotificationHandler useManageMCPConnections.ts:619-751）</li>
     *   <li>id（无 method）→ 对 client→server 请求的响应 → complete pending</li>
     * </ul>
     */
    private void handleSseLine(String line) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(data);
            boolean hasId = node.has("id") && !node.get("id").isNull();
            if (node.has("method") && hasId) {
                handleServerRequest(node.get("id"), node.get("method").asText(), node.get("params"));
            } else if (node.has("method")) {
                dispatchNotification(node.get("method").asText(), node.get("params"));
            } else if (hasId) {
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
            }
        } catch (Exception e) {
            log.warn("[SseMcpTransport] parse failed: {}", e.getMessage());
        }
    }

    /**
     * [S02] server→client JSON-RPC 请求处理（SSE 流到达）· 对齐 CC {@code client.setRequestHandler}：
     * roots/list → {@code {roots:[{uri:"file://"+cwd}]}}（CC client.ts:1009-1018 ListRootsRequestSchema
     * handler → uri=file://${getOriginalCwd()}，CC 真源自验 client.ts:1014：roots 用 STATE.originalCwd
     * =会话项目根，非 pwd/getCwd 动态 cwd）。Java 兜底走统一入口 CwdResolution.getOriginalCwdLayer()
     * （绑定项目层 ?? user.dir，对齐 CC getOriginalCwd）；config.cwd() 优先保留（非破坏）。
     * DEL-07：移除 System.getProperty("user.dir") 直读，经统一入口兜底。未知 → -32601（不悬挂）。响应经 POST 回传。
     */
    private void handleServerRequest(JsonNode idNode, String method, JsonNode params) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", idNode);
        if ("roots/list".equals(method)) {
            String cwd = config != null && config.cwd() != null && !config.cwd().isBlank()
                ? config.cwd() : CwdResolution.getOriginalCwdLayer();
            response.put("result", Map.of("roots", List.of(Map.of("uri", "file://" + cwd))));
            log.info("[SseMcpTransport] {} roots/list 响应: uri=file://{}", serverName(), cwd);
        } else {
            log.warn("[SseMcpTransport] 未处理 server→client 请求 method={}", method);
            response.put("error", Map.of("code", -32601, "message", "Method not found"));
        }
        try {
            postRpc(response);
        } catch (Exception e) {
            log.warn("[SseMcpTransport] server→client 响应回传失败 method={}: {}", method, e.getMessage());
        }
    }

    /**
     * P2-15: 按 method 分发 server→client 通知到已注册处理器 · 对齐 CC
     * {@code client.setNotificationHandler}（useManageMCPConnections.ts:619-751）。
     *
     * <p>未注册该 method 时静默忽略。分发在独立线程执行——本方法从 SSE 流读取线程调用，
     * 处理器内含阻塞 sendRequest 往返，同步执行会阻塞读取线程致响应无人读取死锁。
     *
     * @param method JSON-RPC 通知 method
     * @param params 通知 params（list_changed 恒为 {}）
     */
    private void dispatchNotification(String method, JsonNode params) {
        List<McpNotificationHandler> handlers = notificationHandlers.get(method);
        if (handlers == null || handlers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SseMcpTransport] 收到通知但无处理器 method={}", method);
            }
            return;
        }
        for (McpNotificationHandler handler : handlers) {
            CompletableFuture.runAsync(() -> {
                try {
                    handler.handle(params);
                } catch (Exception e) {
                    log.warn("[SseMcpTransport] 通知处理器执行失败 method={}: {}", method, e.getMessage());
                }
            });
        }
    }

    /** 取异常最内层消息（日志用）。 */
    private static String messageOf(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    /** [S02] 测试钩子：注入 GET 流重连延迟（对齐 setWsReconnectScheduler 先例；默认 1s）。 */
    void setSseReconnectDelayMs(long delayMs) {
        this.reconnectDelayMs = Math.max(0, delayMs);
    }
}

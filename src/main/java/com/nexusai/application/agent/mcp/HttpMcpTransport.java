package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.llm.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP Streamable HTTP transport · 对齐 CC client.ts SHTTP path (Streamable HTTP).
 *
 * <p>L1 语义: HTTP POST 发送 JSON-RPC 请求, 服务端返回 application/json 或 text/event-stream.
 * 与 SSE 不同: 单次 POST 一次响应, 无长连接. 与 stdio 不同: 网络而非 stdin/stdout.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: Accept: application/json, text/event-stream</li>
 *   <li><b>A2</b>: POST → 200/202/4xx/5xx 响应</li>
 *   <li><b>A3</b>: NOT_CONNECTED → CONNECTED → CLOSED</li>
 *   <li><b>A4</b>: 每次请求独立 session id (UUID), 服务端可选 SSE 流</li>
 *   <li><b>A5</b>: timeout 10s 默认, future 不悬挂</li>
 * </ul>
 */
@Component
public class HttpMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpTransport.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicReference<McpTransport.State> state = new AtomicReference<>(McpTransport.State.NOT_CONNECTED);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build();

    /** [IMP-E2 S-7] 连接超时（毫秒）· 对齐 CC getConnectionTimeoutMs() 默认 30000（client.ts:456-458），
     *  供 HttpClient.connectTimeout 使用（HTTP 建连 TCP 握手超时）。合并裁决：master 10s → 30s。 */
    static final long CONNECT_TIMEOUT_MS = 30_000L;

    /** [IMP-E2 S-8] 每请求超时（毫秒）· CC original: MCP_REQUEST_TIMEOUT_MS = 60000（client.ts:463）。
     *  Java 端替换原 10s 硬编码（EV-E3-044：Http 每请求 10s vs CC 60s，长任务提前超时）。 */
    static final long MCP_REQUEST_TIMEOUT_MS = 60_000L;

    /**
     * [S02 D-S02-1] MCP session id · 由 initialize 响应 {@code Mcp-Session-Id} 头协商（null = 未协商）。
     * 对齐 CC 委派 SDK StreamableHTTPClientTransport（client.ts:861-864）+ mcp-core 0.14.0
     * HttpClientStreamableHttpTransport.java:239-241（仅 {@code sessionId.isPresent()} 时附加
     * MCP_SESSION_ID 头）+ :446-447（markInitialized 捕获响应头）。<b>不自产 UUID 预发</b>——
     * 自产未知 id 会让支持 session 的 server 404（D-2 脏代码删除）。
     */
    private volatile String sessionId;
    private URI endpoint;

    /**
     * [S02] 3-strike 终端错误计数 · 对齐 CC client.ts:1228 {@code MAX_ERRORS_BEFORE_RECONNECT=3}
     * + :1350-1364（终端错误计数，非终端错误重置）。
     */
    private final AtomicLong consecutiveConnectionErrors = new AtomicLong();

    /** [S02] 3-strike 关断守卫 · 对齐 CC client.ts:1230-1247 hasTriggeredClose（close 幂等，防重入）。 */
    private volatile boolean hasTriggeredClose = false;

    /** [tool-v3 合并保留] {@link #closeTransportAndRejectPending(String)} 重入守卫（tool-v3 测试面
     *  HttpMcpTransportTest 直接驱动 String 重载；master 以 hasTriggeredClose 守卫 Throwable 重载）。 */
    private final AtomicBoolean closeTriggered = new AtomicBoolean(false);

    /**
     * [Session H P2-5] server 名 · start(config) 时保存, 401 抛 {@link McpAuthError} 用.
     * 对齐 CC client.ts:3194-3208 (throw new McpAuthError(name, ...)).
     */
    private String serverName;

    /**
     * [S2] 保存的 transport 配置 · start(config) 时保存，供 {@link McpAuthHeaderProvider}
     * 计算 serverKey 读 DB token（serverName/type/command/env）。
     */
    private McpTransport.TransportConfig config;

    /**
     * [S2] MCP OAuth Bearer token 提供者 · null = 未接线（测试/无认证）→ 不注入
     * Authorization 头（no-op，保持既有行为）。对齐 CC client.ts:802-840 HTTP authProvider
     * auth()→tokens() 语义。
     */
    private final McpAuthHeaderProvider authHeaderProvider;

    public HttpMcpTransport() {
        this(null);
    }

    public HttpMcpTransport(McpAuthHeaderProvider authHeaderProvider) {
        this.authHeaderProvider = authHeaderProvider;
    }
    /**
     * P2-15: server→client 通知处理器注册表 · 对齐 CC {@code client.setNotificationHandler}
     * （useManageMCPConnections.ts:619/:669/:707）。key = JSON-RPC 通知 method，value = 处理器列表。
     *
     * <p>当前 HTTP transport 无持久入站通知通道（仅 POST 请求/响应），服务端主动通知需
     * 长连接/SSE 流（后续 P-item）——分发点就位但真实到达受限。
     */
    private final Map<String, List<McpNotificationHandler>> notificationHandlers = new ConcurrentHashMap<>();

    @Override
    public void start(McpTransport.TransportConfig config) {
        // [Session H P2-5] 保存 server 名供 401 → McpAuthError 使用 (CC client.ts:3194-3208)
        this.serverName = config == null ? null : config.serverName();
        // [S2] 保存完整 config 供 McpAuthHeaderProvider 计算 serverKey（Bearer 注入）
        this.config = config;
        // Streamable HTTP transport 用 command 字段作 URL
        String url = config == null || config.command() == null ? null : config.command();
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new IllegalStateException("Streamable HTTP transport requires http:// or https:// URL");
        }
        this.endpoint = URI.create(url);
        // [MCP-I-1 D5] 删除 HEAD probe（⊕-25）：CC StreamableHTTPTransport 直接 POST initialize
        // 连接，无 HEAD 预探——HEAD 探针会让不支持 HEAD 但可用 POST 的 server 启动失败。
        this.state.set(McpTransport.State.CONNECTED);
        log.info("[HttpMcpTransport] connected endpoint={} session=negotiated:{}", url, sessionId);
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

        // [S2] 每次请求解析 Bearer access token（对齐 CC SDK auth()→tokens()，client.ts:802-840）
        String bearerToken = resolveBearer();
        doSendRequest(id, method, params, fut, bearerToken, false);
        return fut;
    }

    /**
     * [S2] HTTP POST 请求本体（Bearer 注入 + 401-refresh-retry）。
     *
     * <p>对齐 CC MCP SDK StreamableHTTPClientTransport：每次请求调 {@code authProvider.tokens()}
     * 附加 {@code Authorization: Bearer <access_token>}；401 时先 refreshAuthorization 再用新 token
     * 重试一次（retried 守卫防死循环）。
     *
     * @param id          JSON-RPC id（pending 表键）
     * @param retried     true = 已因 401 重试过一次，再 401 直接降级
     */
    private void doSendRequest(long id, String method, Object params, CompletableFuture<JsonNode> fut,
            String bearerToken, boolean retried) {
        try {
            Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params == null ? Map.of() : params
            );
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                // [IMP-E2 S-8] 每请求 60s 新鲜超时（对齐 CC MCP_REQUEST_TIMEOUT_MS=60000 client.ts:463）
                .timeout(Duration.ofMillis(MCP_REQUEST_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
            // [S02 D-S02-1] Mcp-Session-Id 仅协商成功后携带（mcp-core 0.14.0
            // HttpClientStreamableHttpTransport.java:239-241 sessionId.isPresent() 语义），
            // 不再每请求无条件预发自产 UUID（D-2 脏代码删除）。
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpRequest req = builder
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc), StandardCharsets.UTF_8))
                .build();
            // [S02] ofLines 流式读体：标准 SSE server（mcp-core HttpServletStreamableServer
            // TransportProvider 实证）对非 initialize 请求以持久 SSE 流回响应——ofString 会
            // 等到 10s 请求超时（SDK 客户端在首个匹配事件后即完成，见 mcp-core 0.14.0
            // HttpClientStreamableHttpTransport.java:478-505）。ofLines + 匹配帧后 close
            // 流（取消订阅 → 连接释放），等价 SDK 语义。
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        // [S02] 3-strike 终端错误检测 · 对齐 CC client.ts:1228 + :1333-1365：
                        // 终端连接错误（ECONNREFUSED/ETIMEDOUT/ECONNRESET/EPIPE/EHOSTUNREACH/
                        // terminated）计数，>=3 → closeTransportAndRejectPending（陈旧连接不复用）；
                        // 非终端错误重置计数（:1361-1364）。
                        if (isTerminalConnectionError(err)) {
                            long n = consecutiveConnectionErrors.incrementAndGet();
                            if (log.isDebugEnabled()) {
                                log.debug("[HttpMcpTransport] 终端连接错误 {}/{} server={} err={}",
                                    n, MAX_ERRORS_BEFORE_RECONNECT, serverName, messageOf(err));
                            }
                            if (n >= MAX_ERRORS_BEFORE_RECONNECT) {
                                consecutiveConnectionErrors.set(0);
                                closeTransportAndRejectPending(new IllegalStateException(
                                    "max consecutive terminal errors (" + MAX_ERRORS_BEFORE_RECONNECT
                                        + "): " + messageOf(err), err));
                                return; // failAllPending 已拒绝包括本请求在内的全部 pending
                            }
                        } else {
                            consecutiveConnectionErrors.set(0);
                        }
                        pending.remove(id);
                        fut.completeExceptionally(err);
                        return;
                    }
                    // [S02 D-S02-1] 响应 Mcp-Session-Id 头协商捕获 · 对齐 mcp-core 0.14.0
                    // markInitialized（HttpClientStreamableHttpTransport.java:446-447）：
                    // 首次捕获即生效（首请求 = initialize，connectTransport 顺序保证）；
                    // 已捕获后保留（DefaultMcpTransportSession 仅 404 会话失效时 invalidate）。
                    captureSessionId(resp);
                    int code = resp.statusCode();
                    if (code == 202) {
                        // 服务端接受请求, 异步处理. 简化: 不等流式响应, fail
                        closeLines(resp);
                        consecutiveConnectionErrors.set(0); // 服务端响应证明连接健康（非终端）
                        pending.remove(id);
                        fut.complete(null);
                        return;
                    }
                    if (code == 401) {
                        // [S2] 401 → 先 refreshAuthorization 再用新 token 重试一次
                        // （对齐 CC SDK 401→authProvider.refreshAuthorization→retry；token 过期
                        // 场景，CC 注释 "While MCP servers should return 401 for expired tokens
                        // (which triggers SDK-level refresh)"）。
                        closeLines(resp);
                        String newToken = refreshOn401(bearerToken);
                        if (newToken != null && !retried) {
                            if (log.isDebugEnabled()) {
                                log.debug("[HttpMcpTransport] 401 已刷新 token，重试一次 server={} method={}",
                                    serverName, method);
                            }
                            doSendRequest(id, method, params, fut, newToken, true);
                            return;
                        }
                        // 刷新失败 / 已重试仍 401 → McpAuthError 降级（needs-auth）
                        // [Session H P2-5] 对齐 CC client.ts:3194-3208
                        //   (error code === 401 → logEvent tengu_mcp_tool_call_auth_error
                        //   → throw new McpAuthError(name, 'MCP server "..." requires
                        //   re-authorization (token expired)')). 执行层 catch 后降级
                        //   appState mcp.clients → needs-auth (CC toolExecution.ts:1601-1629).
                        consecutiveConnectionErrors.set(0); // 401 为认证类非终端错误（CC :1361-1364）
                        pending.remove(id);
                        String message = "MCP server requires re-authorization (token expired)";
                        log.info("[HttpMcpTransport] 工具调用返回 401 Unauthorized, "
                            + "token 刷新后仍失败 (tengu_mcp_tool_call_auth_error 等价日志): "
                            + "server={} method={}", serverName, method);
                        fut.completeExceptionally(new McpAuthError(serverName, message));
                        return;
                    }
                    if (code == 403 && hasInsufficientScope(resp)) {
                        // [OAuth-R1] 403 + WWW-Authenticate: ...insufficient_scope... → 标记
                        // server step-up pending（对齐 CC wrapFetchWithStepUpDetection
                        // auth.ts:1354-1374：在 SDK 403 handler 调 auth() 之前标记，使后续
                        // tokens() 省略 refresh_token 走更高 scope 重授权）。标记写入 token
                        // 存储后本请求仍以 403 失败（Java 无 SDK 自动重授权，消费在后续
                        // 刷新/认证流程，见 McpAuthHeaderProvider.needsStepUp）。
                        markStepUpPending(resp);
                        closeLines(resp);
                        consecutiveConnectionErrors.set(0); // 403 为认证类非终端错误
                        pending.remove(id);
                        fut.completeExceptionally(new IllegalStateException("HTTP 403 insufficient_scope"));
                        return;
                    }
                    if (code >= 400) {
                        // [Q-11-5 DIV-1] 会话过期识别 · 对齐 CC isMcpSessionExpiredError
                        // （client.ts:193-206）：HTTP 404 + message 含 "code":-32001（Session
                        // not found）→ 会话过期，区别于通用 HTTP 错误。CC 处理链：工具调用捕获 →
                        // clearServerCache（client.ts:1648-1673）→ 下一次调用重建连接。
                        // Java 端：置 transport CLOSED + 抛 McpSessionExpiredException，
                        // McpToolPool.callTool 清连接缓存，下一次 ensureConnectedClient 重建
                        // （新 HttpMcpTransport 实例 → 新 sessionId 重新协商）。
                        String body = collectLines(resp, 4096);
                        if (isSessionExpired(code, body)) {
                            pending.remove(id);
                            state.set(McpTransport.State.CLOSED);
                            // [S02 D-S02-1] 会话失效 → 清协商 session（对齐 mcp-core 0.14.0
                            // :199-201 invalidate 语义；重建实例会重新协商）
                            this.sessionId = null;
                            McpSessionExpiredException ex = new McpSessionExpiredException(serverName,
                                "MCP server \"" + serverName + "\" session expired (HTTP 404 + JSON-RPC -32001): "
                                    + abbreviate(body));
                            fut.completeExceptionally(ex);
                            // 对齐 CC closeTransportAndRejectPending（client.ts:1313-1329）：
                            // 会话失效 → 同一 transport 上其它在途请求共享同一 session ID，必然
                            // 也 404 → 一并失败（防悬挂）。
                            failAllPending(ex);
                            log.info("[HttpMcpTransport] 会话过期 (HTTP 404 + JSON-RPC -32001)，"
                                + "连接缓存将清除待重连: server={}", serverName);
                            return;
                        }
                        // [S02] HTTP 5xx/4xx（非会话过期）为非终端错误 → 重置 3-strike 计数
                        // （对齐 CC client.ts:1361-1364：非终端 onerror 重置计数；服务端响应
                        // 证明连接健康，陈旧连接不应被误关断）。
                        consecutiveConnectionErrors.set(0);
                        pending.remove(id);
                        fut.completeExceptionally(new IllegalStateException("HTTP " + code));
                        return;
                    }
                    try {
                        String contentType = resp.headers().firstValue("Content-Type").orElse("");
                        if (contentType.toLowerCase().contains("text/event-stream")) {
                            // [S02] Streamable HTTP 规范允许响应体为 SSE 流（mcp-core
                            // HttpServletStreamableServerTransportProvider 对非 initialize
                            // 请求恒回 text/event-stream 且流保持打开）→ 逐行流式解析：
                            // 匹配帧即完成并关闭流（不等待流结束）。
                            processSseLines(resp, id, fut);
                            return;
                        }
                        String body = collectLines(resp, Integer.MAX_VALUE);
                        JsonNode result = mapper.readTree(body);
                        if (result.has("method")) {
                            // P2-15: 服务端主动通知（Streamable HTTP 响应体可能携带 method 帧）
                            // → 分发到已注册处理器（对齐 CC setNotificationHandler
                            // useManageMCPConnections.ts:619-751）。当前无持久入站通道，
                            // 分发点就位待后续长连接 P-item。
                            dispatchNotification(result.get("method").asText(), result.get("params"));
                            return;
                        }
                        JsonNode resultNode = result.path("result");
                        pending.remove(id);
                        if (result.has("error")) {
                            fut.completeExceptionally(new IllegalStateException(
                                "JSON-RPC error: " + result.get("error")));
                        } else {
                            fut.complete(resultNode);
                        }
                    } catch (Exception parseErr) {
                        pending.remove(id);
                        fut.completeExceptionally(parseErr);
                    }
                });
            if (log.isDebugEnabled()) {
                log.debug("[HttpMcpTransport] sent request id={} method={} 带Bearer={}", id, method, bearerToken != null);
            }
        } catch (Exception e) {
            pending.remove(id);
            fut.completeExceptionally(e);
        }
    }

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
     * && wwwAuth?.includes('insufficient_scope')}）。
     *
     * @param resp HTTP 响应（请求与通知路径共用）
     */
    private static boolean hasInsufficientScope(HttpResponse<?> resp) {
        if (resp.statusCode() != 403) {
            return false;
        }
        String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse(null);
        // CC 大小写敏感 includes（auth.ts:1362 wwwAuth?.includes('insufficient_scope')）
        return wwwAuth != null && wwwAuth.contains("insufficient_scope");
    }

    /**
     * [OAuth-R1] 标记 server step-up pending · 无 provider / 无 config 时 no-op。
     * scope 提取复用 {@link McpAuthHeaderProvider#extractScopeFromWwwAuthenticate}
     * （CC auth.ts:1365 regex，RFC 6750 §3 带/不带引号）。
     */
    private void markStepUpPending(HttpResponse<?> resp) {
        if (authHeaderProvider == null || config == null) {
            return;
        }
        String scope = McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(
            resp.headers().firstValue("WWW-Authenticate").orElse(null));
        if (scope != null) {
            authHeaderProvider.markStepUpPending(config, scope);
            if (log.isDebugEnabled()) {
                log.debug("[HttpMcpTransport] 检测 403 insufficient_scope，已标记 step-up pending server={} scope={}",
                    serverName, scope);
            }
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
        if (state.get() != McpTransport.State.CONNECTED) {
            throw new IllegalStateException("transport not connected");
        }
        // 对齐 CC client.ts：MCP transport 层不做 withOAuth401Retry 包装
        // （CC 仅在 claude.ai API 路径 bootstrap.ts/grove.ts/metricsOptOut.ts 使用）；
        // 401 等非 2xx 由 doSendNotificationSync 抛 LlmApiException 向上传播。
        try {
            Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params == null ? Map.of() : params
            );
            doSendNotificationSync(rpc);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP POST failed: " + e.getMessage(), e);
        }
    }

    /**
     * sendNotification 的底层同步 HTTP POST（Bearer 注入 + 401-refresh-retry）。
     */
    private void doSendNotificationSync(Map<String, Object> rpc) throws Exception {
        doSendNotificationSync(rpc, resolveBearer(), false);
    }

    private void doSendNotificationSync(Map<String, Object> rpc, String bearerToken, boolean retried) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            // [IMP-E2 S-8] 每请求 60s 新鲜超时（对齐 CC MCP_REQUEST_TIMEOUT_MS=60000 client.ts:463）
            .timeout(Duration.ofMillis(MCP_REQUEST_TIMEOUT_MS))
            .header("Content-Type", "application/json")
            // [S02] Accept 双值（application/json, text/event-stream）· MCP Streamable HTTP
            // 规范强制（mcp-core HttpServletStreamableServerTransportProvider:378-384 缺
            // Accept 即 400；真实 Spring AI server 实证——初始化经 sendRequest 带 Accept
            // 通过，通知经本路径缺 Accept 被 400 拒绝）。
            .header("Accept", "application/json, text/event-stream");
        // [S02 D-S02-1] 会话协商成功后携带（mcp-core 0.14.0 :239-241 语义；不自产 UUID）
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest req = builder
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc), StandardCharsets.UTF_8))
            .build();
        HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        // [S02 D-S02-1] 通知响应同样捕获 Mcp-Session-Id（SDK 每次响应读取语义）
        captureSessionId(resp);
        int code = resp.statusCode();
        if (code == 401 && !retried) {
            String newToken = refreshOn401(bearerToken);
            if (newToken != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[HttpMcpTransport] 通知 401 已刷新 token，重试一次 server={}", serverName);
                }
                doSendNotificationSync(rpc, newToken, true);
                return;
            }
        }
        if (code == 403 && hasInsufficientScope(resp)) {
            // [OAuth-R1] 通知路径同样检测 403 insufficient_scope → 标记 step-up pending
            // （对齐 CC wrapFetchWithStepUpDetection 包装 transport fetch，通知与请求共用）。
            markStepUpPending(resp);
        }
        if (code >= 400) {
            throw new LlmApiException(code, resp.headers().map(), "");
        }
    }

    @Override
    public void setNotificationHandler(String method, McpNotificationHandler handler) {
        notificationHandlers.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (log.isDebugEnabled()) {
            log.debug("[HttpMcpTransport] 注册通知处理器 method={}", method);
        }
    }

    @Override
    public void close() {
        if (state.get() == McpTransport.State.CLOSED) {
            return;
        }
        // [AM-CC-20260825] 先通知服务端释放连接（对齐 CC/SDK client.close() → transport.close()
        //   发送 notifications/terminated）：否则服务端单连接状态残留 → 新连接 initialize 被拒
        //   "Already connected to a transport" HTTP 500（2026-08-25 联调实测 curl 手动同 500）。
        //   best-effort：发送失败不阻断本地关闭（服务端可能已断）。
        try {
            if (sessionId != null) {
                sendNotification("notifications/terminated", null);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[HttpMcpTransport] terminated 通知发送失败（best-effort，连接仍关闭）: {}", e.toString());
            }
        }
        state.set(McpTransport.State.CLOSED);
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException("transport closed"));
        }
        pending.clear();
        // [S02 D-S02-1] 关闭即放弃协商 session（重建实例重新协商）
        this.sessionId = null;
        log.info("[HttpMcpTransport] closed session=negotiated:{}", sessionId);
    }

    @Override
    public McpTransport.State getState() {
        return state.get();
    }

    // ────────────── 内部辅助（Q-11-5 会话过期识别）──────────────

    /**
     * [Q-11-5 DIV-1] 会话过期判定 · 对齐 CC isMcpSessionExpiredError（client.ts:193-206）。
     *
     * <p>CC 逻辑（自验 TS 源码）：{@code error.code === 404}（HTTP 状态）AND message 含
     * {@code "code":-32001} 或 {@code "code": -32001}（SDK 把响应体文本嵌入 error.message，
     * MCP server 返回 {@code {"error":{"code":-32001,"message":"Session not found"}}}）。
     * 双信号判定避免误伤通用 404（URL 错误 / server 已下线）。
     *
     * @param httpStatus HTTP 响应状态码
     * @param body       响应体（可能为 null）
     * @return true = MCP 会话过期（404 + -32001）
     */
    private static boolean isSessionExpired(int httpStatus, String body) {
        if (httpStatus != 404 || body == null) {
            return false;
        }
        return body.contains("\"code\":-32001") || body.contains("\"code\": -32001");
    }

    /** 截断长文本（异常 message 排障用，避免巨长响应体撑爆日志）. */
    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
    /** 使所有在途请求以同一 cause 失败（对齐 CC closeTransportAndRejectPending 语义）. */
    private void failAllPending(Throwable cause) {
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        pending.clear();
    }

    // ────────────── [S02] session 协商 + 3-strike 终端错误 ──────────────

    /** [S02] 3-strike 阈值 · 对齐 CC client.ts:1228 {@code MAX_ERRORS_BEFORE_RECONNECT = 3}。 */
    private static final long MAX_ERRORS_BEFORE_RECONNECT = 3;

    /**
     * [S02 D-S02-1] 响应 Mcp-Session-Id 捕获（首次捕获即生效）· 对齐 mcp-core 0.14.0
     * {@code DefaultMcpTransportSession.markInitialized}（HttpClientStreamableHttpTransport
     * .java:446-447：仅 initialize 响应捕获；此后保留直至 404 会话失效 invalidate）。
     *
     * <p>实现：首次非空捕获后不再覆盖（SDK 对「后响应换 id」仅 warn 不更新）；响应无头
     * 不清除（SDK SSE 响应流不带会话头——mcp-core HttpServletStreamableMcpSessionTransport
     * 实证——若按「无头即清」处理，真实 server 会在首个 SSE 响应后丢失会话）。
     * 计划 S02 modifyList 原文「空则清」据此修正（SDK 语义为准），见 concerns。
     */
    private void captureSessionId(HttpResponse<?> resp) {
        if (sessionId == null) {
            String value = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (value != null && !value.isBlank()) {
                this.sessionId = value;
                if (log.isDebugEnabled()) {
                    log.debug("[HttpMcpTransport] 会话协商成功 server={} sessionId={}", serverName, value);
                }
            }
        }
    }

    /**
     * [S02] Streamable HTTP SSE 响应体流式解析（text/event-stream）· 标准 SSE server 对非
     * initialize 请求以持久 SSE 流回响应（mcp-core HttpServletStreamableServerTransportProvider
     * 实证：流在响应事件后保持打开）→ 逐行读取，匹配帧即完成并 close 流（取消订阅释放
     * 连接，等价 SDK 客户端「首个事件后完成」语义 mcp-core 0.14.0 :478-505）。
     *
     * @param resp 响应（body = 行流）
     * @param id   本请求 JSON-RPC id
     * @param fut  本请求 future
     */
    private void processSseLines(HttpResponse<java.util.stream.Stream<String>> resp, long id,
                                 CompletableFuture<JsonNode> fut) {
        try (var lines = resp.body()) {
            java.util.Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode node = mapper.readTree(data);
                boolean hasId = node.has("id") && !node.get("id").isNull();
                if (hasId && node.get("id").asLong() == id) {
                    pending.remove(id);
                    if (node.has("error")) {
                        fut.completeExceptionally(new IllegalStateException(
                            "JSON-RPC error: " + node.get("error")));
                    } else {
                        fut.complete(node.get("result"));
                    }
                    return; // try-with-resources close → 取消订阅 → 连接释放
                }
                if (node.has("method")) {
                    // P2-15: 响应流中的 server→client 通知 → 分发
                    dispatchNotification(node.get("method").asText(), node.get("params"));
                }
            }
            // 流结束（EOF）仍无匹配帧 → 明确失败（不悬挂）
            pending.remove(id);
            fut.completeExceptionally(new IllegalStateException(
                "SSE 响应流结束但未收到 id=" + id + " 的响应帧"));
        } catch (Exception e) {
            pending.remove(id);
            fut.completeExceptionally(e);
        }
    }

    /** [S02] 关闭响应行流（丢弃未读内容，取消底层订阅）。 */
    private static void closeLines(HttpResponse<java.util.stream.Stream<String>> resp) {
        try (var lines = resp.body()) {
            // close-only：不消费
        } catch (Exception ignored) {
            // 已关闭
        }
    }

    /** [S02] 收集响应行流为文本（上限 cap 字节，防巨响应体）。 */
    private static String collectLines(HttpResponse<java.util.stream.Stream<String>> resp, int cap) {
        StringBuilder sb = new StringBuilder();
        try (var lines = resp.body()) {
            java.util.Iterator<String> it = lines.iterator();
            while (it.hasNext() && sb.length() < cap) {
                sb.append(it.next()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return sb.toString();
        }
    }

    /**
     * [S02] 终端连接错误判定 · 对齐 CC {@code isTerminalConnectionError}（client.ts:1249-1263
     * 消息子串：ECONNRESET/ETIMEDOUT/EPIPE/EHOSTUNREACH/ECONNREFUSED/Body Timeout/terminated），
     * 翻译为 Java 异常类型 + 消息子串。走查 cause 链（sendAsync 失败以 CompletionException
     * 包装底层 IOException）。
     */
    private static boolean isTerminalConnectionError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.net.ConnectException) {
                return true;            // ECONNREFUSED
            }
            if (cur instanceof java.net.NoRouteToHostException) {
                return true;            // EHOSTUNREACH
            }
            if (cur instanceof java.net.http.HttpTimeoutException) {
                return true;            // ETIMEDOUT（sendAsync 请求级超时）
            }
            if (cur instanceof java.net.SocketTimeoutException) {
                return true;            // ETIMEDOUT（读超时）
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("connection reset") || m.contains("ecnrefused")
                    || m.contains("econnreset") || m.contains("broken pipe")
                    || m.contains("epipe") || m.contains("ehostunreach")
                    || m.contains("timed out") || m.contains("etimedout")
                    || m.contains("terminated") || m.contains("body timeout")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 取异常最内层消息（日志用）。 */
    private static String messageOf(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    /**
     * [S02] 关断 transport + 拒绝全部 pending · 对齐 CC {@code closeTransportAndRejectPending}
     * （client.ts:1240-1247）：{@code hasTriggeredClose} 守卫防重入（close 链中在途流再次
     * 触发 onerror）；置 CLOSED（陈旧连接不复用——establishConnection 见 CLOSED 即重建）；
     * failAllPending 拒绝悬挂请求（防悬挂，CC 注释「hung callTool() promises fail」）。
     */
    private void closeTransportAndRejectPending(Throwable cause) {
        if (hasTriggeredClose) {
            return;
        }
        hasTriggeredClose = true;
        state.set(McpTransport.State.CLOSED);
        this.sessionId = null;
        log.info("[HttpMcpTransport] 3-strike 终端错误关断 transport server={} reason={}",
            serverName, messageOf(cause));
        failAllPending(cause);
    }


    /**
     * [IMP-E2 M-9] 终端连接错误计数 + 3-strike 触发 · 对齐 CC client.onerror（client.ts:1350-1364）：
     * {@code isTerminalConnectionError} 命中 → 计数++，达 {@code MAX_ERRORS_BEFORE_RECONNECT} 归零 +
     * closeTransportAndRejectPending；非终端错误 → 计数归零。
     *
     * @param err 请求网络错误（sendAsync 回调 err 非 null）
     */
    /** [IMP-E2 M-9] 终端连接错误计数入口（package-private 供测试直接驱动 3-strike 状态机）。 */
    void recordConnectionError(Throwable err) {
        String msg = err.getMessage();
        String causeMsg = err.getCause() != null ? err.getCause().getMessage() : null;
        if (isTerminalConnectionError(msg) || isTerminalConnectionError(causeMsg)) {
            // 合并裁决后 consecutiveConnectionErrors 为 master AtomicLong → long 计数（int 会 lossy 编译错）
            long n = consecutiveConnectionErrors.incrementAndGet();
            log.warn("[HttpMcpTransport] 终端连接错误 {}/{} server={}: {}",
                n, MAX_ERRORS_BEFORE_RECONNECT, serverName, err.getMessage());
            if (n >= MAX_ERRORS_BEFORE_RECONNECT) {
                consecutiveConnectionErrors.set(0);
                closeTransportAndRejectPending("max consecutive terminal errors");
            }
        } else {
            consecutiveConnectionErrors.set(0);
        }
    }

    /**
     * [IMP-E2 M-9] 连接掉落终端错误判定 · 对齐 CC isTerminalConnectionError（client.ts:1249-1263）。
     * 命中任一子串即视为「连接已死」，连续 3 次触发重连关闭。
     */
    private static boolean isTerminalConnectionError(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("ECONNRESET")
            || msg.contains("ETIMEDOUT")
            || msg.contains("EPIPE")
            || msg.contains("EHOSTUNREACH")
            || msg.contains("ECONNREFUSED")
            || msg.contains("Body Timeout Error")
            || msg.contains("terminated")
            || msg.contains("SSE stream disconnected")
            || msg.contains("Failed to reconnect SSE stream");
    }

    /**
     * [IMP-E2 M-9] 关闭 transport 并 reject 全部在途请求 · 对齐 CC closeTransportAndRejectPending
     * （client.ts:1240-1247）：{@code client.close() → transport.onclose → SDK reject 全部 pending}
     * 防调用永久挂起。重入守卫（hasTriggeredClose）。置 CLOSED 供 {@code ensureConnectedClient}
     * 下次调用惰性重连（对齐 CC onclose 清缓存 → 下次重连）。
     *
     * @param reason 关闭原因（日志）
     */
    /** [IMP-E2 M-9] 关闭 transport 并 reject 全部在途请求（package-private 供测试直接驱动）。 */
    void closeTransportAndRejectPending(String reason) {
        if (!closeTriggered.compareAndSet(false, true)) {
            return;
        }
        log.warn("[HttpMcpTransport] 关闭 transport（{}）并 reject 全部在途请求 server={}",
            reason, serverName);
        state.set(McpTransport.State.CLOSED);
        failAllPending(new IllegalStateException("MCP connection closed: " + reason));
    }

    /**
     * [IMP-E2 D-3] 解析 Streamable HTTP 202/SSE 响应体 · 对齐 CC SDK StreamableHTTP 202→SSE 语义
     * （EV-E3-045）。响应体为 SSE 事件流（{@code data: ...} 行），逐行解析：
     * <ul>
     *   <li>含 {@code id} → resolve 对应 pending future（JSON-RPC result/error）</li>
     *   <li>含 {@code method} → server 主动通知 → 分发 {@link McpNotificationHandler}</li>
     * </ul>
     * 若流未含本请求 {@code id} → 该 future 以可诊断错误完成（不悬挂；对齐 CC 流中断 reject pending）。
     *
     * @param id     本请求 JSON-RPC id
     * @param fut    本请求 future
     * @param method 本请求 method（错误诊断用）
     * @param body   响应体文本（SSE 事件流）
     */
    private void handleSseResponseBody(long id, CompletableFuture<JsonNode> fut, String method, String body) {
        boolean resolved = false;
        if (body != null && !body.isBlank()) {
            for (String line : body.split("\\R")) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(data);
                    if (node.has("id") && !node.get("id").isNull()) {
                        long evtId = node.get("id").asLong();
                        CompletableFuture<JsonNode> p = pending.remove(evtId);
                        if (p != null) {
                            if (node.has("error")) {
                                p.completeExceptionally(new IllegalStateException(
                                    "JSON-RPC error: " + node.get("error")));
                            } else {
                                p.complete(node.get("result"));
                            }
                        }
                        if (evtId == id) {
                            resolved = true;
                        }
                    } else if (node.has("method")) {
                        dispatchNotification(node.get("method").asText(), node.get("params"));
                    }
                } catch (Exception parseErr) {
                    log.warn("[HttpMcpTransport] SSE 响应行解析失败: {}", parseErr.getMessage());
                }
            }
        }
        if (!resolved) {
            pending.remove(id);
            fut.completeExceptionally(new IllegalStateException(
                "MCP server accepted request (HTTP 202) but no SSE response for id "
                    + id + " method=" + method));
        }
    }

    /**
     * P2-15: 按 method 分发 server→client 通知到已注册处理器 · 对齐 CC
     * {@code client.setNotificationHandler}（useManageMCPConnections.ts:619-751）。
     *
     * <p>未注册该 method 时静默忽略。分发在独立线程执行——本方法从 HTTP 响应回调线程
     * 调用，处理器内含阻塞 sendRequest 往返，同步执行会阻塞回调线程致响应无人读取死锁。
     *
     * @param method JSON-RPC 通知 method
     * @param params 通知 params（list_changed 恒为 {}）
     */
    private void dispatchNotification(String method, JsonNode params) {
        List<McpNotificationHandler> handlers = notificationHandlers.get(method);
        if (handlers == null || handlers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[HttpMcpTransport] 收到通知但无处理器 method={}", method);
            }
            return;
        }
        for (McpNotificationHandler handler : handlers) {
            CompletableFuture.runAsync(() -> {
                try {
                    handler.handle(params);
                } catch (Exception e) {
                    log.warn("[HttpMcpTransport] 通知处理器执行失败 method={}: {}", method, e.getMessage());
                }
            });
        }
    }
}
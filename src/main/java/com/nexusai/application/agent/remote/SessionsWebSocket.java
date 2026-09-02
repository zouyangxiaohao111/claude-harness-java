package com.nexusai.application.agent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * CCR 会话 WebSocket 客户端 · 对齐 CC remote/SessionsWebSocket.ts 实际行为。
 *
 * <p><b>协议</b>（SessionsWebSocket.ts:74-81）:
 * <ol>
 *   <li>连接 {@code wss://api.anthropic.com/v1/sessions/ws/{sessionId}/subscribe?organization_uuid=...}；</li>
 *   <li>请求头带 {@code Authorization: Bearer <token>} + {@code anthropic-version}（TS :115-118）；</li>
 *   <li>接收 SDKMessage 流（含 control_request / control_response / control_cancel_request）。</li>
 * </ol>
 *
 * <p><b>重连语义</b>（TS :234-288，grep -n 自验）:
 * <ul>
 *   <li>4003（unauthorized）永久关闭 → 立即 {@link Callbacks#onClose()}，不重连（PERMANENT_CLOSE_CODES :34-36）；</li>
 *   <li>4001（session not found）可短暂瞬态（compaction 期间）→ 最多 {@link #MAX_SESSION_NOT_FOUND_RETRIES} 次，
 *       退避 {@code 2000ms × retries}（TS :258-272）；</li>
 *   <li>曾 CONNECTED 后正常关闭 → 重连 {@code 2000ms}，最多 {@link #MAX_RECONNECT_ATTEMPTS} 次（TS :274-283）；</li>
 *   <li>未 CONNECTED 就关闭 / 预算耗尽 → {@code onClose()} 停止（TS :284-287）。</li>
 * </ul>
 *
 * <p><b>心跳</b>: CONNECTED 后每 {@link #PING_INTERVAL_MS}（30s）发送 ping 帧（TS :301-313）。
 *
 * <p><b>Java idiom</b>: TS 原生 WebSocket/ws 事件 → {@link java.net.http.WebSocket.Listener}
 * （可注入 {@link WebSocketConnector} 以测试驱动事件）；TS setTimeout 自调度 → 注入
 * {@link ScheduledExecutorService}（缺省共享 daemon 调度器）。构造沿用 CC 四参
 * （sessionId, orgUuid, getAccessToken, callbacks），baseUrl/connector/scheduler 为 Java 注入点。
 */
public final class SessionsWebSocket {

    private static final Logger log = LoggerFactory.getLogger(SessionsWebSocket.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC SessionsWebSocket.ts:17 RECONNECT_DELAY_MS */
    public static final long RECONNECT_DELAY_MS = 2_000L;
    /** CC :18 MAX_RECONNECT_ATTEMPTS */
    public static final int MAX_RECONNECT_ATTEMPTS = 5;
    /** CC :19 PING_INTERVAL_MS */
    public static final long PING_INTERVAL_MS = 30_000L;
    /** CC :26 MAX_SESSION_NOT_FOUND_RETRIES */
    public static final int MAX_SESSION_NOT_FOUND_RETRIES = 3;
    /** CC :34-36 PERMANENT_CLOSE_CODES — 4003 unauthorized */
    public static final int CLOSE_CODE_UNAUTHORIZED = 4003;
    /** CC :26 注释 — 4001 session not found */
    public static final int CLOSE_CODE_SESSION_NOT_FOUND = 4001;
    /** CC :398-402 reconnect() 强制重连前小延迟 500ms */
    public static final long FORCED_RECONNECT_DELAY_MS = 500L;
    /** CC constants/oauth.ts BASE_API_URL='https://api.anthropic.com'（连接前换 wss://） */
    public static final String DEFAULT_BASE_API_URL = "https://api.anthropic.com";
    /** CC SessionsWebSocket.ts:117 anthropic-version 头 */
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 共享 daemon 调度器（重连/心跳计时；注入则用注入实例） */
    private static final ScheduledExecutorService SHARED_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-sessions-ws");
        t.setDaemon(true);
        return t;
    });

    /** CC :38 WebSocketState */
    public enum WebSocketState { CONNECTING, CONNECTED, CLOSED }

    /** CC :40-44 SessionsMessage = SDKMessage | control_*（type 字段区分，payload 承载其余字段） */
    public record SessionsMessage(String type, Map<String, Object> payload) {
    }

    /** CC :57-65 SessionsWebSocketCallbacks（onMessage 必选，其余可选） */
    public interface Callbacks {
        void onMessage(SessionsMessage message);

        /** 永久关闭 / 预算耗尽时触发（CC :62-63 注释：onClose 仅在 server 端终结或重连耗尽时触发） */
        default void onClose() {
        }

        default void onError(Exception error) {
        }

        default void onConnected() {
        }

        /** 检测到瞬态关闭、已排定重连时触发（CC :63-64） */
        default void onReconnecting() {
        }
    }

    /**
     * 可注入 WebSocket 连接器（测试用）。缺省走 {@link java.net.http.HttpClient#newWebSocketBuilder()}。
     * 连接成功后必须由实现方驱动 {@link WebSocket.Listener}（缺省 JDK 客户端在 open 后驱动 onOpen）。
     */
    @FunctionalInterface
    public interface WebSocketConnector {
        WebSocket connect(URI url, Map<String, String> headers, WebSocket.Listener listener) throws Exception;
    }

    private final String sessionId;
    private final String orgUuid;
    private final Supplier<String> accessTokenSupplier;
    private final Callbacks callbacks;
    private final Supplier<String> baseUrlSupplier;
    private final WebSocketConnector connector;
    private final ScheduledExecutorService scheduler;
    private final long reconnectDelayMs;
    private final long pingIntervalMs;
    private final long forcedReconnectDelayMs;

    private volatile WebSocket ws;
    private volatile WebSocketState state = WebSocketState.CLOSED;
    private volatile int reconnectAttempts;
    private volatile int sessionNotFoundRetries;
    private volatile ScheduledFuture<?> pingFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private final StringBuilder pendingText = new StringBuilder();
    private final Object lock = new Object();

    /** CC 四参构造（sessionId, orgUuid, getAccessToken, callbacks）— 生产缺省注入点。 */
    public SessionsWebSocket(String sessionId, String orgUuid, Supplier<String> accessTokenSupplier,
                             Callbacks callbacks) {
        this(sessionId, orgUuid, accessTokenSupplier, callbacks, () -> DEFAULT_BASE_API_URL,
            defaultConnector(), SHARED_SCHEDULER, RECONNECT_DELAY_MS, PING_INTERVAL_MS,
            FORCED_RECONNECT_DELAY_MS);
    }

    /** 全注入构造（测试用）— 自定义 baseUrl/connector/scheduler/重连间隔。 */
    SessionsWebSocket(String sessionId, String orgUuid, Supplier<String> accessTokenSupplier,
                      Callbacks callbacks, Supplier<String> baseUrlSupplier, WebSocketConnector connector,
                      ScheduledExecutorService scheduler, long reconnectDelayMs, long pingIntervalMs,
                      long forcedReconnectDelayMs) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.orgUuid = Objects.requireNonNull(orgUuid);
        this.accessTokenSupplier = Objects.requireNonNull(accessTokenSupplier);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.baseUrlSupplier = Objects.requireNonNull(baseUrlSupplier);
        this.connector = Objects.requireNonNull(connector);
        this.scheduler = scheduler == null ? SHARED_SCHEDULER : scheduler;
        this.reconnectDelayMs = reconnectDelayMs;
        this.pingIntervalMs = pingIntervalMs;
        this.forcedReconnectDelayMs = forcedReconnectDelayMs;
    }

    /** 当前状态（测试断言用）。 */
    WebSocketState getState() {
        return state;
    }

    /** 常规重连次数（测试断言用，对齐 CC reconnectAttempts）。 */
    int getReconnectAttempts() {
        return reconnectAttempts;
    }

    /** 4001 重试计数（测试断言用，对齐 CC sessionNotFoundRetries）。 */
    int getSessionNotFoundRetries() {
        return sessionNotFoundRetries;
    }

    // ────────────────────────────────────────────────────────────────────
    // 连接 / 消息 / 关闭
    // ────────────────────────────────────────────────────────────────────

    /** CC connect（:100-205）— 建立 wss 连接。state=CONNECTING → 事件驱动 CONNECTED。 */
    public void connect() {
        synchronized (lock) {
            if (state == WebSocketState.CONNECTING) {
                // CC :101-104 — 已在连接中则跳过
                log.debug("SessionsWebSocket.connect: 会话 {} 已在连接中, 跳过", sessionId);
                return;
            }
            state = WebSocketState.CONNECTING;
        }
        String baseUrl = baseUrlSupplier.get();
        String url = baseUrl.replace("https://", "wss://")
            + "/v1/sessions/ws/" + sessionId + "/subscribe?organization_uuid=" + orgUuid;
        String accessToken = accessTokenSupplier.get();
        Map<String, String> headers = new HashMap<>();
        if (accessToken != null) {
            headers.put("Authorization", "Bearer " + accessToken);
        }
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        log.info("SessionsWebSocket.connect: 连接 CCR 会话 {} url={}", sessionId, url);
        try {
            connector.connect(URI.create(url), headers, listener);
        } catch (Exception e) {
            // CC :147-151/:189-192 — 连接错误走 onError
            log.warn("SessionsWebSocket.connect: 连接失败: {}", e.getMessage());
            callbacks.onError(e instanceof Exception ex ? ex : new Exception(e));
        }
    }

    /** CC handleMessage（:210-229）— JSON 解析，type 为字符串的 SessionMessage 转发 onMessage。 */
    private void handleMessage(String data) {
        try {
            Object parsed = JSON.readValue(data, Object.class);
            if (parsed instanceof Map<?, ?> m && m.get("type") instanceof String type) {
                Map<String, Object> payload = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    payload.put(String.valueOf(e.getKey()), e.getValue());
                }
                callbacks.onMessage(new SessionsMessage(type, payload));
            } else {
                log.debug("SessionsWebSocket.handleMessage: 忽略非 SessionMessage 消息");
            }
        } catch (Exception e) {
            log.warn("SessionsWebSocket.handleMessage: 消息解析失败: {}", e.getMessage());
        }
    }

    /** CC handleClose（:234-288）— 4003 永久 / 4001 有限重试 / 已连接则有限重连。 */
    private void handleClose(int closeCode, String reason) {
        stopPingInterval();
        synchronized (lock) {
            if (state == WebSocketState.CLOSED) {
                return;
            }
            ws = null;
            WebSocketState previousState = state;
            state = WebSocketState.CLOSED;

            // CC :246-253 — 永久关闭码：不重连
            if (closeCode == CLOSE_CODE_UNAUTHORIZED) {
                log.warn("SessionsWebSocket.handleClose: 永久关闭码 {} 不重连", closeCode);
                callbacks.onClose();
                return;
            }
            // CC :255-272 — 4001 可瞬态（compaction 期间），有限重试
            if (closeCode == CLOSE_CODE_SESSION_NOT_FOUND) {
                sessionNotFoundRetries++;
                if (sessionNotFoundRetries > MAX_SESSION_NOT_FOUND_RETRIES) {
                    log.warn("SessionsWebSocket.handleClose: 4001 重试预算耗尽({}), 不重连", MAX_SESSION_NOT_FOUND_RETRIES);
                    callbacks.onClose();
                    return;
                }
                scheduleReconnect(reconnectDelayMs * sessionNotFoundRetries,
                    "4001 attempt " + sessionNotFoundRetries + "/" + MAX_SESSION_NOT_FOUND_RETRIES);
                return;
            }
            // CC :274-283 — 曾 CONNECTED 且预算内 → 重连
            if (previousState == WebSocketState.CONNECTED && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                scheduleReconnect(reconnectDelayMs,
                    "attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS);
            } else {
                log.debug("SessionsWebSocket.handleClose: 不重连 (previous={} attempts={})", previousState, reconnectAttempts);
                callbacks.onClose();
            }
        }
    }

    private void scheduleReconnect(long delayMs, String label) {
        callbacks.onReconnecting();
        log.debug("SessionsWebSocket.scheduleReconnect: {} 计划 {}ms 后重连", label, delayMs);
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
        }
        reconnectFuture = scheduler.schedule(() -> {
            reconnectFuture = null;
            connect();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ────────────────────────────────────────────────────────────────────
    // 心跳
    // ────────────────────────────────────────────────────────────────────

    /** CC startPingInterval（:301-313）— 每 30s ping 一次，保持连接存活。 */
    private void startPingInterval() {
        stopPingInterval();
        pingFuture = scheduler.scheduleAtFixedRate(() -> {
            WebSocket w = ws;
            if (w != null && state == WebSocketState.CONNECTED) {
                try {
                    w.sendPing(ByteBuffer.wrap(new byte[0]));
                } catch (Exception e) {
                    log.debug("SessionsWebSocket.startPingInterval: ping 发送失败, 交由 close 处理器处理");
                }
            }
        }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopPingInterval() {
        ScheduledFuture<?> f = pingFuture;
        pingFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 发送 / 状态 / 关闭 / 强制重连
    // ────────────────────────────────────────────────────────────────────

    /** CC sendControlResponse（:328-336）— 向会话发送 control_response（权限响应/错误）。 */
    public void sendControlResponse(Map<String, Object> response) {
        WebSocket w = ws;
        if (w == null || state != WebSocketState.CONNECTED) {
            log.warn("SessionsWebSocket.sendControlResponse: 未连接, 无法发送");
            return;
        }
        send(w, response);
    }

    /** CC sendControlRequest（:341-357）— 包装 {@code {type:'control_request', request_id:uuid, request}} 后发送。 */
    public void sendControlRequest(Map<String, Object> requestInner) {
        WebSocket w = ws;
        if (w == null || state != WebSocketState.CONNECTED) {
            log.warn("SessionsWebSocket.sendControlRequest: 未连接, 无法发送");
            return;
        }
        Map<String, Object> controlRequest = new HashMap<>();
        controlRequest.put("type", "control_request");
        controlRequest.put("request_id", UUID.randomUUID().toString());
        controlRequest.put("request", requestInner);
        send(w, controlRequest);
    }

    private void send(WebSocket w, Map<String, Object> message) {
        try {
            String text = JSON.writeValueAsString(message);
            // CC :335/:356 — fire-and-forget，失败仅日志
            w.sendText(text, true).exceptionally(ex -> {
                log.warn("SessionsWebSocket.send: 发送失败: {}", ex.getMessage());
                return null;
            });
        } catch (Exception e) {
            log.warn("SessionsWebSocket.send: 序列化失败: {}", e.getMessage());
        }
    }

    /** CC isConnected（:362-364）。 */
    public boolean isConnected() {
        return state == WebSocketState.CONNECTED;
    }

    /** CC close（:369-387）— 停止重连/心跳并发送 close 帧。 */
    public void close() {
        log.debug("SessionsWebSocket.close: 关闭连接 会话 {}", sessionId);
        synchronized (lock) {
            state = WebSocketState.CLOSED;
            stopPingInterval();
            if (reconnectFuture != null) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
            WebSocket w = ws;
            ws = null;
            if (w != null) {
                try {
                    w.sendClose(1000, "client close");
                } catch (Exception ignored) {
                    // close 失败无需处理
                }
            }
        }
    }

    /** CC reconnect（:393-403）— 强制重连（容器重启后订阅陈旧时用），先清计数再 500ms 后重连。 */
    public void reconnect() {
        log.debug("SessionsWebSocket.reconnect: 强制重连 会话 {}", sessionId);
        reconnectAttempts = 0;
        sessionNotFoundRetries = 0;
        close();
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
        }
        reconnectFuture = scheduler.schedule(() -> {
            reconnectFuture = null;
            connect();
        }, forcedReconnectDelayMs, TimeUnit.MILLISECONDS);
    }

    // ────────────────────────────────────────────────────────────────────
    // Listener / 注入默认实现
    // ────────────────────────────────────────────────────────────────────

    /** java.net.http WebSocket 事件监听 · 对齐 CC ws 事件（open/message/close/error/pong）。 */
    private final WebSocket.Listener listener = new WebSocket.Listener() {
        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            log.info("SessionsWebSocket.onOpen: 连接已建立");
            synchronized (lock) {
                state = WebSocketState.CONNECTED;
                reconnectAttempts = 0;
                sessionNotFoundRetries = 0;
            }
            startPingInterval();
            callbacks.onConnected();
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pendingText.append(data);
            if (last) {
                String message = pendingText.toString();
                pendingText.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("SessionsWebSocket.onClose: code={} reason={}", statusCode, reason);
            handleClose(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("SessionsWebSocket.onError: {}", error.getMessage());
            callbacks.onError(error instanceof Exception e ? e : new Exception(error));
        }
    };

    /** 缺省连接器 — java.net.http.HttpClient WebSocket（支持头注入，对齐 CC headers 认证）。 */
    private static WebSocketConnector defaultConnector() {
        return (url, headers, listener) -> {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            WebSocket.Builder builder = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10));
            headers.forEach(builder::header);
            return builder.buildAsync(url, listener).join();
        };
    }
}

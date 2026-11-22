package com.nexusai.domain.im_channel.inbound.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企微智能机器人 WebSocket 长连接客户端 —— 纯自实现（okhttp 只当传输层，协议全部手写）。
 *
 * <h2>状态机</h2>
 * <pre>
 *   DISCONNECTED --start()--> CONNECTING --onOpen--> AUTHENTICATING
 *        ↑                                            │ 收到鉴权响应（第一帧）
 *        │                                            ▼
 *   stop()│                                        CONNECTED ⇄ (心跳 / 读超时)
 *        │                                            │ 断开 / 失败 / 超时
 *        └──── DEGRADED ←────────────────────────────┘
 * </pre>
 *
 * <h2>时序约束（来源 {@code 90-report/weknora-reference/WECOM-PROTOCOL-实测.md} §6）</h2>
 * <ul>
 *   <li>心跳 <b>30s</b>：应用层 {@code ping} 帧（**无 body**）；</li>
 *   <li>读超时 <b>90s</b>（3 × 心跳，单次丢 pong 不误判）；</li>
 *   <li>重连退避 <b>1s → 30s</b> 指数，<b>无限重试</b>。</li>
 * </ul>
 *
 * <h2>照搬的六条实测坑（每条都有真机依据）</h2>
 * <ol>
 *   <li><b>PIT-WC-006</b>：{@code req_id} 取自 {@code headers}，不在 body
 *       （见 {@link WecomWsProtocol#reqIdOf}）。</li>
 *   <li><b>PIT-WC-013</b>：鉴权响应是「连接后第一帧」，<b>没有 cmd 字段</b> ⇒
 *       只能靠状态机区分（{@code AUTHENTICATING} 时收到的第一帧即鉴权结果），
 *       按 cmd 分发会漏判鉴权结果。</li>
 *   <li><b>PIT-WC-010</b>：<b>任何</b>入站帧都算「连接活着」⇒ 每收到一帧就重置读超时；
 *       只按 pong 重置会在消息密集时被误判超时并重连。</li>
 *   <li><b>PIT-WC-012</b>：okhttp 必须 {@code pingInterval(0)} + {@code readTimeout(0)}
 *       —— 否则 okhttp 默认 10s 读超时会自己掐断长连接，且自带 WS ping 与应用层 ping 打架；
 *       超时判定完全由本类负责。</li>
 *   <li><b>PIT-WC-011</b>：重连退避的回调里必须 {@code synchronized} 二次校验状态，
 *       否则用户已 {@code stop()} 或已重连成功时会再连一条 ⇒ <b>僵尸连接</b>。</li>
 *   <li><b>PIT-WC-005</b>：回复必须用 stream 帧形态（见 {@link #replyText}）。</li>
 * </ol>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>{@link #start()} <b>不阻塞</b>：okhttp 的 {@code newWebSocket} 是异步的；
 *       心跳/读超时/重连跑在一个 daemon 单线程调度器上；</li>
 *   <li>收包回调在 okhttp 的 IO 线程上 ⇒ 回调里抛异常会打断收包 ⇒ 一律 {@code safeCall} 兜住；</li>
 *   <li>{@link #stop()} 幂等：先置 {@code running=false}（让所有回调变成 no-op），
 *       再关 WS（{@code close} + {@code cancel}，后者保证阻塞读立刻结束），最后 {@code shutdownNow} 调度器。</li>
 * </ul>
 *
 * <h2>⛔ 凭证安全</h2>
 * {@code botSecret} 只出现在鉴权帧的 body 里，<b>不进日志、不进异常消息</b>。
 * 本类也没有覆写 {@code toString}（避免被顺手打印）。
 */
public final class WecomWsClient implements AutoCloseable {

    /** 连接状态（对外可见，便于状态面板显示）。 */
    public enum State {
        /** 未启动 / 已停止。 */
        DISCONNECTED,
        /** 正在建连。 */
        CONNECTING,
        /** 已连上，等鉴权响应（连接后第一帧）。 */
        AUTHENTICATING,
        /** 已鉴权，正常收发。 */
        CONNECTED,
        /** 掉线，等待重连。 */
        DEGRADED
    }

    /** 心跳间隔 30s（应用层 ping 帧）。 */
    public static final long DEFAULT_HEARTBEAT_MILLIS = 30_000L;
    /** 读超时 90s = 3 × 心跳。 */
    public static final long DEFAULT_READ_TIMEOUT_MILLIS = 90_000L;

    /** 重连退避：起始 1s。 */
    private static final long BACKOFF_BASE_MILLIS = 1_000L;
    /** 重连退避：封顶 30s。 */
    private static final long BACKOFF_MAX_MILLIS = 30_000L;

    /**
     * 一条入站帧。
     *
     * @param cmd   命令字（{@code aibot_msg_callback} 等）
     * @param reqId {@code headers.req_id}（回复必须原样带回，PIT-WC-006）
     * @param body  帧体（可能为 null）
     * @param raw   <b>整帧</b> JSON（排障与「平台到底发了什么」的唯一可信事实；⛔ 不进日志/不落库）
     */
    public record InboundFrame(String cmd, String reqId, JsonNode body, JsonNode raw) {
    }

    /**
     * 客户端回调。
     *
     * <p>⚠️ 所有回调都在 okhttp 的 IO 线程上被调用 ⇒ 实现方**不要抛异常**（会打断收包循环）。
     */
    public interface FrameListener {
        /** TCP/WS 已连上（还没鉴权）。 */
        default void onConnected() {
        }

        /** 鉴权通过（此后才会发心跳、才收业务帧）。 */
        default void onAuthenticated() {
        }

        /** 连接断开。 */
        default void onDisconnected(String reason) {
        }

        /** 即将重连。 */
        default void onReconnecting(int attempt, long delayMillis) {
        }

        /** 出错（鉴权失败、帧解析失败、发送失败…）。 */
        default void onError(Throwable error) {
        }

        /** 收到业务帧（消息回调 / 事件回调）。 */
        default void onFrame(InboundFrame frame) {
        }
    }

    private final String botId;
    private final String secret;
    private final String endpoint;
    private final FrameListener listener;
    private final long heartbeatMillis;
    private final long readTimeoutMillis;

    /** 定时器（心跳 / 读超时 / 重连）；单线程 daemon。 */
    private final ScheduledExecutorService scheduler;
    /** 传输层；只做「把字节送出去、把帧收回来」，协议与超时判定都在本类。 */
    private final OkHttpClient http;

    private volatile State state = State.DISCONNECTED;
    /** 🔴 停止开关：所有回调先看它，置 false 后回调一律 no-op（幂等 stop 的基础）。 */
    private volatile boolean running = false;
    /** 当前 WS 句柄（stop 时用它打断阻塞读）。 */
    private volatile WebSocket socket;

    private final AtomicInteger attempt = new AtomicInteger(0);
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> readTimeoutTask;
    private ScheduledFuture<?> reconnectTask;

    /** 生产构造：默认端点 + 30s 心跳 + 90s 读超时。 */
    public WecomWsClient(String botId, String secret, FrameListener listener) {
        this(botId, secret, WecomWsProtocol.WS_ENDPOINT, listener,
                DEFAULT_HEARTBEAT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS, "wecom-ws-timer");
    }

    /**
     * 全参构造。
     *
     * @param botId             机器人 bot_id（PIT-WC-004）
     * @param secret            机器人 bot_secret（⛔ 不进日志）
     * @param endpoint          WS 端点
     * @param listener          回调
     * @param heartbeatMillis   心跳间隔
     * @param readTimeoutMillis 读超时（必须 ≥ 心跳间隔）
     * @param timerThreadName   定时器线程名（便于排障定位）
     */
    public WecomWsClient(String botId, String secret, String endpoint, FrameListener listener,
                          long heartbeatMillis, long readTimeoutMillis, String timerThreadName) {
        if (botId == null || botId.isBlank()) {
            throw new IllegalArgumentException("botId 必填（PIT-WC-004）");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("botSecret 必填（PIT-WC-004）");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener 必填");
        }
        this.botId = botId;
        this.secret = secret;
        this.endpoint = (endpoint == null || endpoint.isBlank())
                ? WecomWsProtocol.WS_ENDPOINT : endpoint;
        this.listener = listener;
        this.heartbeatMillis = heartbeatMillis > 0 ? heartbeatMillis : DEFAULT_HEARTBEAT_MILLIS;
        this.readTimeoutMillis = readTimeoutMillis > 0 ? readTimeoutMillis : DEFAULT_READ_TIMEOUT_MILLIS;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, timerThreadName == null ? "wecom-ws-timer" : timerThreadName);
            t.setDaemon(true);      // ⛔ 别让定时器线程拖住 JVM 退出
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        this.http = new OkHttpClient.Builder()
                // 🔴 PIT-WC-012：okhttp 自带的 pingInterval 会与企微「应用层 ping 帧」打架
                .pingInterval(0, TimeUnit.SECONDS)
                // 🔴 PIT-WC-012：okhttp 默认 readTimeout=10s，会把长连接自己掐断
                .readTimeout(0, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    // ─────────────────────────── 状态查询 ───────────────────────────

    public State state() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** 是否在运行（{@code start()} 之后、{@code stop()} 之前）。 */
    public boolean isRunning() {
        return running;
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    /**
     * 开始连接（<b>不阻塞</b>：okhttp 建连是异步的，心跳/重连跑在后台线程）。
     *
     * <p>幂等：已在跑就直接返回。
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        attempt.set(0);
        doConnect();
    }

    /**
     * 停止（幂等）。
     *
     * <p>三步保证「干净退出」：
     * <ol>
     *   <li>{@code running=false} ⇒ 之后所有回调都是 no-op（不会有 close 竞态噪音）；</li>
     *   <li>关 WS 并 {@code cancel()} ⇒ <b>打断阻塞中的读</b>（否则读线程会一直挂着）；</li>
     *   <li>{@code shutdownNow()} 调度器 ⇒ 定时器线程退出。</li>
     * </ol>
     */
    public synchronized void stop() {
        running = false;
        cancel(heartbeatTask);
        heartbeatTask = null;
        cancel(readTimeoutTask);
        readTimeoutTask = null;
        cancel(reconnectTask);
        reconnectTask = null;

        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.close(1000, "client stop");
            } catch (Throwable ignore) {
                // PIT-WC-015：关闭竞态下的噪音（Server-sent frames must not be masked）不影响功能
            }
            try {
                // ⛔ 必须 cancel：close() 只发关闭帧，阻塞中的读要等对端回应才结束
                ws.cancel();
            } catch (Throwable ignore) {
                // 已断
            }
        }
        if (state != State.DISCONNECTED) {
            state = State.DISCONNECTED;
            safeCall(() -> listener.onDisconnected("client stop"));
        }
        scheduler.shutdownNow();
    }

    /** 停止并释放传输层资源（okhttp 的调度线程池 + 连接池）。 */
    @Override
    public void close() {
        stop();
        try {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        } catch (Throwable ignore) {
            // 已关
        }
    }

    // ─────────────────────────── 出站 ───────────────────────────

    /**
     * 回复一条入站消息。
     *
     * <p>🔴 <b>PIT-WC-005</b>：即便一次性发完，也必须包成 stream 帧（{@code finish=true}）。
     *
     * @param reqId   来自 {@link InboundFrame#reqId()}（PIT-WC-006：它在 headers 里）
     * @param content 正文
     */
    public void replyText(String reqId, String content) {
        if (reqId == null || reqId.isBlank()) {
            throw new IllegalArgumentException("缺少 req_id，无法回复（PIT-WC-006）");
        }
        WebSocket ws = socket;
        if (ws == null || state != State.CONNECTED) {
            throw new IllegalStateException("未连接，无法回复，state=" + state);
        }
        sendFrame(ws, WecomWsProtocol.respondStream(
                reqId, "stream_" + System.nanoTime(), content == null ? "" : content, true));
    }

    // ─────────────────────────── 连接 / 收包 ───────────────────────────

    private void doConnect() {
        state = State.CONNECTING;
        Request req = new Request.Builder().url(endpoint).build();
        WebSocket ws = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // ★ 以回调参数为准赋值：newWebSocket 返回后回调可能已经跑过
                socket = webSocket;
                handleOpen(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleText(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                // 对端发起关闭：回一个 close 完成握手（真正的关闭结果等 onClosed）
                try {
                    webSocket.close(code, reason);
                } catch (Throwable ignore) {
                    // 关闭噪音
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                handleDrop("closed:" + code);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                handleFailure(t);
            }
        });
        socket = ws;
    }

    private synchronized void handleOpen(WebSocket ws) {
        if (!running) {
            // stop() 早于建连完成 ⇒ 别留一条没人管的连接
            try {
                ws.close(1000, "client stop");
            } catch (Throwable ignore) {
                // 已断
            }
            try {
                ws.cancel();     // ⛔ 立刻释放 socket（close() 要等对端回应）
            } catch (Throwable ignore) {
                // 已断
            }
            return;
        }
        state = State.AUTHENTICATING;
        try {
            sendFrame(ws, WecomWsProtocol.subscribe(botId, secret));
        } catch (RuntimeException e) {
            safeCall(() -> listener.onError(e));
            handleDrop("subscribe-failed");
            return;
        }
        safeCall(listener::onConnected);
        armReadTimeout();
    }

    private synchronized void handleText(String message) {
        if (!running) {
            return;
        }
        // 🔴 PIT-WC-010：任何入站帧都算「连接活着」（不能只靠 pong 重置读超时）
        armReadTimeout();

        JsonNode frame = WecomWsProtocol.parse(message);
        if (frame == null || !frame.isObject()) {
            // 一条坏帧不能打断收包循环
            safeCall(() -> listener.onError(new IllegalStateException("收到非 JSON 帧，已丢弃")));
            return;
        }

        // 🔴 PIT-WC-013：鉴权响应是「连接后第一帧」，没有 cmd 字段 ⇒ 靠状态机区分，不靠 cmd 分发。
        if (state == State.AUTHENTICATING) {
            int errcode = frame.path("errcode").asInt(0);
            if (errcode != 0) {
                String reason = frame.path("errmsg").asText("");
                safeCall(() -> listener.onError(
                        new IllegalStateException("企微鉴权失败 code=" + errcode + " msg=" + reason)));
                handleDrop("auth-failed");
                return;
            }
            state = State.CONNECTED;
            attempt.set(0);
            safeCall(listener::onAuthenticated);
            armHeartbeat();
            return;
        }

        if (state != State.CONNECTED && state != State.DEGRADED) {
            return;      // 已停止或未就绪，丢弃
        }

        String cmd = WecomWsProtocol.cmdOf(frame);
        if (!WecomWsProtocol.CMD_MSG_CALLBACK.equals(cmd)
                && !WecomWsProtocol.CMD_EVENT_CALLBACK.equals(cmd)) {
            // pong / 其它控制帧：只算心跳，不派发
            return;
        }
        JsonNode body = frame.path("body");
        JsonNode bodyOrNull = (body.isMissingNode() || body.isNull()) ? null : body;
        // PIT-WC-006：req_id 在 headers 里
        String reqId = WecomWsProtocol.reqIdOf(frame);
        InboundFrame in = new InboundFrame(cmd, reqId, bodyOrNull, frame);
        safeCall(() -> listener.onFrame(in));
    }

    private void handleFailure(Throwable error) {
        // ★ stop() 之后的失败回调是正常竞态，静默丢弃（PIT-WC-015 同族：关闭语义噪音）
        if (!running) {
            return;
        }
        safeCall(() -> listener.onError(error));
        handleDrop("failure:" + error.getClass().getSimpleName());
    }

    private synchronized void handleDrop(String reason) {
        if (!running) {
            return;
        }
        cancel(heartbeatTask);
        heartbeatTask = null;
        cancel(readTimeoutTask);
        readTimeoutTask = null;
        if (state == State.DISCONNECTED) {
            return;
        }
        state = State.DEGRADED;
        safeCall(() -> listener.onDisconnected(reason));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        int n = attempt.incrementAndGet();
        long delay = backoffDelay(n);
        safeCall(() -> listener.onReconnecting(n, delay));
        cancel(reconnectTask);
        reconnectTask = schedule(() -> {
            // 🔴 PIT-WC-011：必须在 synchronized 内**二次校验**状态。
            //    否则「用户已 stop()」或「已经重连成功」时，这里会再建一条连接 ⇒ 僵尸连接。
            synchronized (WecomWsClient.this) {
                if (!running || state != State.DEGRADED) {
                    return;
                }
                doConnect();
            }
        }, delay);
    }

    /** 指数退避：1s → 2s → 4s … 封顶 30s（对齐 WeKnora longconn.go）。 */
    private static long backoffDelay(int attemptNo) {
        long delay = BACKOFF_BASE_MILLIS;
        for (int i = 1; i < attemptNo && delay < BACKOFF_MAX_MILLIS; i++) {
            delay = Math.min(delay * 2, BACKOFF_MAX_MILLIS);
        }
        return delay;
    }

    // ─────────────────────────── 心跳 / 读超时 ───────────────────────────

    private void armHeartbeat() {
        cancel(heartbeatTask);
        if (!running) {
            return;
        }
        heartbeatTask = schedule(this::onHeartbeatTick, heartbeatMillis);
    }

    private void onHeartbeatTick() {
        WebSocket ws = socket;
        if (!running || state != State.CONNECTED || ws == null) {
            return;
        }
        try {
            // 应用层心跳帧（无 body）—— 见 WecomWsProtocol.ping()
            sendFrame(ws, WecomWsProtocol.ping());
        } catch (RuntimeException e) {
            handleFailure(e);
            return;
        }
        armHeartbeat();
    }

    private void armReadTimeout() {
        cancel(readTimeoutTask);
        if (!running) {
            return;
        }
        readTimeoutTask = schedule(() -> handleDrop("read-timeout"), readTimeoutMillis);
    }

    /**
     * 往调度器里塞一个延时任务。
     *
     * <p>⚠️ {@code stop()} 会 {@code shutdownNow()} 调度器 ⇒ 与 stop 并发时这里可能被拒
     * （{@link RejectedExecutionException}）⇒ 吞掉即可（连接本来就要停了）。
     */
    private ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
        try {
            return scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;     // 已被 stop() 关掉
        }
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private void sendFrame(WebSocket ws, ObjectNode frame) {
        String text = WecomWsProtocol.write(frame);
        // okhttp 的 send 是「入队」语义；返回 false = 已关闭/队列满 ⇒ 必须当失败
        if (!ws.send(text)) {
            throw new IllegalStateException("WS 发送入队失败（连接已关闭或队列满）");
        }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) {
            try {
                f.cancel(false);
            } catch (Throwable ignore) {
                // 已取消
            }
        }
    }

    /** 回调兜底：回调抛异常会打断收包循环 ⇒ 一律吞掉（错误由回调方自己记日志）。 */
    private static void safeCall(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignore) {
            // listener 异常不影响连接
        }
    }
}

package com.nexusai.application.agent.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionsWebSocket 客户端定向测试 · 对齐 CC remote/SessionsWebSocket.ts 实际行为。
 *
 * <p><b>WHY（规则九）</b>: WF-6 T2 WS 订阅层 = RemoteSessionManager 的接收通道。
 * 重连语义（4003 永久 / 4001 有限重试 / 曾连接则 5 次预算）是 CC :234-288 的核心契约：
 * 若 4003 误触发重连或 4001 无限重试，权限响应将无限挂起 / 客户端在 unauthorized 后仍空转。
 * 测试通过 {@link SessionsWebSocket.WebSocketConnector} 注入驱动事件，锁死这些分支。
 */
@DisplayName("[W6-B] SessionsWebSocket 客户端（wss subscribe / 重连 / ping，对齐 CC SessionsWebSocket.ts）")
class SessionsWebSocketTest {

    /** 记录发帧的可注入 WebSocket（java.net.http.WebSocket 最小实现）。 */
    static class FakeWebSocket implements WebSocket {
        final List<String> sentText = new CopyOnWriteArrayList<>();
        final AtomicInteger pingCount = new AtomicInteger();
        final AtomicInteger closeSent = new AtomicInteger();
        volatile boolean closed;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentText.add(data.toString());
            return completed();
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return completed();
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            pingCount.incrementAndGet();
            return completed();
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return completed();
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeSent.incrementAndGet();
            closed = true;
            return completed();
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isInputClosed() {
            return closed;
        }

        @Override
        public boolean isOutputClosed() {
            return closed;
        }

        @Override
        public void abort() {
            closed = true;
        }

        @SuppressWarnings("unchecked")
        private static <T> CompletableFuture<T> completed() {
            return (CompletableFuture<T>) CompletableFuture.completedFuture(null);
        }
    }

    /** 捕获 listener/URL/头的可注入连接器。 */
    static class FakeConnector implements SessionsWebSocket.WebSocketConnector {
        final FakeWebSocket ws = new FakeWebSocket();
        WebSocket.Listener listener;
        URI lastUrl;
        Map<String, String> lastHeaders;
        int connectCalls;
        boolean failConnect;

        @Override
        public WebSocket connect(URI url, Map<String, String> headers, WebSocket.Listener l) {
            connectCalls++;
            if (failConnect) {
                throw new RuntimeException("connect failed");
            }
            this.listener = l;
            this.lastUrl = url;
            this.lastHeaders = headers;
            return ws;
        }
    }

    /** 回调记录器。 */
    static class Recorder implements SessionsWebSocket.Callbacks {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final AtomicInteger onCloseCalls = new AtomicInteger();
        final AtomicInteger onConnectedCalls = new AtomicInteger();
        final AtomicInteger onReconnectingCalls = new AtomicInteger();
        final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(SessionsWebSocket.SessionsMessage message) {
            messages.add(message.type());
        }

        @Override
        public void onClose() {
            onCloseCalls.incrementAndGet();
        }

        @Override
        public void onError(Exception error) {
            errors.add(error.getMessage());
        }

        @Override
        public void onConnected() {
            onConnectedCalls.incrementAndGet();
        }

        @Override
        public void onReconnecting() {
            onReconnectingCalls.incrementAndGet();
        }
    }

    private ScheduledExecutorService scheduler;
    private FakeConnector connector;
    private Recorder rec;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-ws");
            t.setDaemon(true);
            return t;
        });
        connector = new FakeConnector();
        rec = new Recorder();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static void await(long timeoutMs, java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /** 构造 + connect + 驱动 onOpen（CONNECTED）。 */
    private SessionsWebSocket openClient(long reconnectDelayMs, long pingIntervalMs) {
        SessionsWebSocket s = new SessionsWebSocket("sess-1", "org-1", () -> "token", rec,
            () -> "https://api.anthropic.com", connector, scheduler, reconnectDelayMs, pingIntervalMs,
            SessionsWebSocket.FORCED_RECONNECT_DELAY_MS);
        s.connect();
        connector.listener.onOpen(connector.ws);
        return s;
    }

    // ── connect / 消息 ──

    @Test
    @DisplayName("connect: wss URL + Bearer 认证头 + onOpen → CONNECTED（CC :108-139/:116-118）")
    void connectBuildsWssUrlAndAuthenticates() {
        SessionsWebSocket s = openClient(10_000, 10_000);
        assertThat(connector.lastUrl.toString()).isEqualTo(
            "wss://api.anthropic.com/v1/sessions/ws/sess-1/subscribe?organization_uuid=org-1");
        assertThat(connector.lastHeaders.get("Authorization")).isEqualTo("Bearer token");
        assertThat(connector.lastHeaders.get("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(rec.onConnectedCalls.get()).isEqualTo(1);
        assertThat(s.isConnected()).isTrue();
        assertThat(s.getReconnectAttempts()).isZero();
    }

    @Test
    @DisplayName("onText: SDKMessage 转发 onMessage；无 type 消息忽略（CC :210-229）")
    void onTextForwardsSessionsMessages() {
        SessionsWebSocket s = openClient(10_000, 10_000);
        connector.listener.onText(connector.ws, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}", true);
        assertThat(rec.messages).containsExactly("assistant");
        // 非 SessionMessage（无字符串 type）忽略
        connector.listener.onText(connector.ws, "{\"foo\":1}", true);
        // 非法 JSON 仅日志，不崩
        connector.listener.onText(connector.ws, "not-json", true);
        assertThat(rec.messages).hasSize(1);
        assertThat(s.isConnected()).isTrue();
    }

    // ── 关闭/重连 ──

    @Test
    @DisplayName("close 4003（unauthorized）→ 永久停止，不排定重连（CC :246-253）")
    void permanentClose4003StopsReconnecting() {
        SessionsWebSocket s = openClient(10_000, 10_000);
        connector.listener.onClose(connector.ws, SessionsWebSocket.CLOSE_CODE_UNAUTHORIZED, "unauthorized");
        assertThat(rec.onCloseCalls.get()).isEqualTo(1);
        assertThat(rec.onReconnectingCalls.get()).isZero();
        assertThat(s.getState()).isEqualTo(SessionsWebSocket.WebSocketState.CLOSED);
        assertThat(connector.connectCalls).isEqualTo(1); // 未发起重连
    }

    @Test
    @DisplayName("close 4001（session not found）→ 有限重试（3 次）后放弃（CC :255-272）")
    void sessionNotFound4001RetriesThenGivesUp() throws Exception {
        SessionsWebSocket s = openClient(30, 10_000);
        // 首次 4001 → retries=1
        connector.listener.onClose(connector.ws, SessionsWebSocket.CLOSE_CODE_SESSION_NOT_FOUND, "stale");
        assertThat(s.getSessionNotFoundRetries()).isEqualTo(1);
        // 等待重连 connect() 触发（state=CONNECTING，未 open —— 模拟重连尝试再次被拒）
        await(2000, () -> connector.connectCalls >= 2);
        assertThat(s.getState()).isEqualTo(SessionsWebSocket.WebSocketState.CONNECTING);
        // 重连尝试再次 4001 → retries=2
        connector.listener.onClose(connector.ws, SessionsWebSocket.CLOSE_CODE_SESSION_NOT_FOUND, "stale");
        await(2000, () -> connector.connectCalls >= 3);
        connector.listener.onClose(connector.ws, SessionsWebSocket.CLOSE_CODE_SESSION_NOT_FOUND, "stale");
        assertThat(s.getSessionNotFoundRetries()).isEqualTo(3);
        // 第 4 次 → 预算耗尽（>3）→ onClose，不再重连
        await(2000, () -> connector.connectCalls >= 4);
        connector.listener.onClose(connector.ws, SessionsWebSocket.CLOSE_CODE_SESSION_NOT_FOUND, "stale");
        assertThat(rec.onCloseCalls.get()).isEqualTo(1);
        assertThat(s.getSessionNotFoundRetries()).isEqualTo(4);
    }

    @Test
    @DisplayName("close 曾 CONNECTED → 排定重连；重连尝试未 open 再 close → 放弃（CC :274-287）")
    void reconnectsOnceThenGivesUpWhenReconnectFailsToOpen() throws Exception {
        SessionsWebSocket s = openClient(30, 10_000);
        // 首次 close（曾 CONNECTED）→ attempts=1 + 排定重连
        connector.listener.onClose(connector.ws, 1006, "connection dropped");
        assertThat(s.getReconnectAttempts()).isEqualTo(1);
        assertThat(rec.onReconnectingCalls.get()).isEqualTo(1);
        // 重连触发 connect()（state=CONNECTING，尚未 open）
        await(2000, () -> connector.connectCalls >= 2);
        assertThat(s.getState()).isEqualTo(SessionsWebSocket.WebSocketState.CONNECTING);
        // 重连尝试未 open 就 close → previousState='connecting' → 放弃，不再重连
        connector.listener.onClose(connector.ws, 1006, "connection dropped");
        assertThat(rec.onCloseCalls.get()).isEqualTo(1);
        assertThat(s.getReconnectAttempts()).isEqualTo(1); // 未再递增
        assertThat(s.getState()).isEqualTo(SessionsWebSocket.WebSocketState.CLOSED);
    }

    @Test
    @DisplayName("重连成功后 onOpen 重置 attempts（CC :135-136/:179-180）")
    void successfulReconnectResetsAttempts() throws Exception {
        SessionsWebSocket s = openClient(30, 10_000);
        connector.listener.onClose(connector.ws, 1006, "connection dropped");
        assertThat(s.getReconnectAttempts()).isEqualTo(1);
        await(2000, () -> connector.connectCalls >= 2);
        connector.listener.onOpen(connector.ws); // 重连成功 open
        assertThat(s.getReconnectAttempts()).isZero(); // onOpen 重置
        assertThat(s.isConnected()).isTrue();
        assertThat(rec.onReconnectingCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("sendControlRequest: 包装 control_request + request_id（CC :341-357）")
    void sendControlRequestWrapsWithRequestId() {
        SessionsWebSocket s = openClient(10_000, 10_000);
        s.sendControlRequest(Map.of("subtype", "interrupt"));
        assertThat(connector.ws.sentText).hasSize(1);
        assertThat(connector.ws.sentText.get(0)).contains("\"type\":\"control_request\"");
        assertThat(connector.ws.sentText.get(0)).contains("\"subtype\":\"interrupt\"");
        assertThat(connector.ws.sentText.get(0)).contains("\"request_id\"");
    }

    @Test
    @DisplayName("ping 每 pingInterval 发送；close 后停止（CC :301-313/:318-323）")
    void pingIntervalSendsPingAndStopsOnClose() throws Exception {
        SessionsWebSocket s = openClient(10_000, 50); // 50ms ping
        await(2000, () -> connector.ws.pingCount.get() >= 1);
        int before = connector.ws.pingCount.get();
        assertThat(before).isGreaterThanOrEqualTo(1);
        s.close();
        Thread.sleep(150);
        assertThat(connector.ws.pingCount.get()).isEqualTo(before);
        assertThat(s.getState()).isEqualTo(SessionsWebSocket.WebSocketState.CLOSED);
    }
}

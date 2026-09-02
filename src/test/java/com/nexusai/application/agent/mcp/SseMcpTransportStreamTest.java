package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S02 X-3] SSE 持久 GET 事件源流（替代 POST 同步阻塞读）· 对齐 CC client.ts:643-671
 * eventSourceInit.fetch（持久 GET 免超时 + authProvider Bearer）+ :1338-1341（SSE
 * reconnect）。
 *
 * <p><b>WHY（规则九）</b>：旧实现 postRpc 同步阻塞读 POST 响应流——标准 SSE server POST
 * 返回空 body（202）时 pending 永久悬挂（X-3/D-11 脏代码）；GET 持久流未实装
 * （ensureReaderStarted 空占位）。本测试锁定 4 条不变量：
 * <ol>
 *   <li>start() 建立持久 GET 流（读阶段免超时）且 GET 连接保持（多次 POST 后 GET 数仍为 1）</li>
 *   <li>GET 流 data: 帧按 id 匹配 complete pending（POST 空 body 场景下响应经 GET 流交付）</li>
 *   <li>POST 空 body（202）不悬挂——sendRequest 立即返回 pending，服务端事件到达后完成</li>
 *   <li>GET 流事件 method 帧到达 notificationHandlers</li>
 * </ol>
 */
@DisplayName("[S02 X-3] SSE 持久 GET 事件源流")
class SseMcpTransportStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockSseServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private McpTransport.TransportConfig cfg(String url) {
        return new McpTransport.TransportConfig(url, List.of(), Map.of(), null, "srv", "sse");
    }

    @Test
    @DisplayName("持久 GET 流保持 + POST 202 空 body 不悬挂 + GET 流事件按 id complete pending")
    void persistentGetStream_deliversResponseForEmptyBodyPost() throws Exception {
        server = new MockSseServer(freePort());
        SseMcpTransport transport = new SseMcpTransport();
        transport.start(cfg("http://127.0.0.1:" + server.port + "/"));

        assertThat(transport.getState()).isEqualTo(McpTransport.State.CONNECTED);
        // 等待 GET 流建立（server 侧收到 GET）
        server.awaitGetStream();
        assertThat(server.getCalls.get()).as("持久 GET 流必须已建立").isEqualTo(1);

        // POST tools/call → 202 空 body → sendRequest 立即返回（不悬挂），响应由 GET 流交付
        CompletableFuture<JsonNode> fut = transport.sendRequest("tools/call", Map.of("name", "t1", "arguments", Map.of()));
        server.awaitPost();
        long id = server.lastPostRequestId();
        assertThat(id).as("POST 必须携带 JSON-RPC id").isPositive();

        // GET 流推送响应事件 → pending 完成（同一持久流上多帧复用）
        server.push("data: {\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"sse-ok\"}]}}");
        JsonNode result = fut.get(5, TimeUnit.SECONDS);
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("sse-ok");

        // 再次 POST → GET 连接不重建（保持语义：GET 数仍为 1）
        CompletableFuture<JsonNode> fut2 = transport.sendRequest("tools/call", Map.of("name", "t2", "arguments", Map.of()));
        server.awaitPostCount(2);
        long id2 = server.lastPostRequestId();
        server.push("data: {\"jsonrpc\":\"2.0\",\"id\":" + id2 + ",\"result\":{\"ok\":true}}");
        assertThat(fut2.get(5, TimeUnit.SECONDS).path("ok").asBoolean()).isTrue();
        assertThat(server.getCalls.get()).as("持久 GET 流不得因 POST 重建（GET 连接保持）").isEqualTo(1);

        transport.close();
    }

    @Test
    @DisplayName("GET 流 method 事件（无 id）到达 notificationHandlers")
    void getStreamNotification_reachesHandler() throws Exception {
        server = new MockSseServer(freePort());
        SseMcpTransport transport = new SseMcpTransport();
        transport.start(cfg("http://127.0.0.1:" + server.port + "/"));
        server.awaitGetStream();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonNode> received = new AtomicReference<>();
        transport.setNotificationHandler("notifications/tools/list_changed",
            params -> {
                received.set(params);
                latch.countDown();
            });

        server.push("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}");
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("GET 流通知事件必须到达 notificationHandlers").isTrue();
        assertThat(received.get()).isNotNull();

        transport.close();
    }

    @Test
    @DisplayName("GET 流 method+id 请求（roots/list）→ POST 回传 {roots:[file://...]} 响应")
    void getStreamServerRequest_rootsList_respondsViaPost() throws Exception {
        server = new MockSseServer(freePort());
        SseMcpTransport transport = new SseMcpTransport();
        transport.start(cfg("http://127.0.0.1:" + server.port + "/"));
        server.awaitGetStream();

        server.push("data: {\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"roots/list\",\"params\":{}}");
        server.awaitPostCount(1);

        String body = server.postBodies.get(0);
        JsonNode frame = MAPPER.readTree(body);
        assertThat(frame.get("id").asInt()).isEqualTo(42);
        String uri = frame.path("result").path("roots").get(0).path("uri").asText();
        assertThat(uri).as("roots/list 回传 file:// + cwd").startsWith("file://");

        transport.close();
    }

    // ═══════════════ mock SSE server ═══════════════

    /**
     * 标准 SSE mock：GET → 200 text/event-stream 保持打开（chunked）；POST → 记录 body +
     * 202 空 body 立即返回（标准 SSE server 语义）。GET 流句柄入队；{@link #push} 复用
     * 同一持久流推送多帧（同一 GET 连接上多事件）。
     */
    static class MockSseServer implements AutoCloseable {
        final HttpServer http;
        final int port;
        final AtomicInteger getCalls = new AtomicInteger();
        final AtomicInteger postCalls = new AtomicInteger();
        final List<String> postBodies = new CopyOnWriteArrayList<>();
        final BlockingQueue<HttpExchange> streams = new LinkedBlockingQueue<>();
        private HttpExchange currentStream;

        MockSseServer(int port) throws IOException {
            this.port = port;
            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            http.createContext("/", ex -> {
                if ("GET".equals(ex.getRequestMethod())) {
                    getCalls.incrementAndGet();
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                    ex.getResponseHeaders().set("Cache-Control", "no-cache");
                    ex.sendResponseHeaders(200, 0); // 0 = chunked，流保持打开
                    ex.getResponseBody().flush();
                    streams.add(ex);
                } else {
                    postCalls.incrementAndGet();
                    postBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    ex.sendResponseHeaders(202, -1); // 202 + 空 body（标准 SSE server 语义）
                    ex.close();
                }
            });
            http.start();
        }

        /** 等待 GET 流建立（≤5s，不消费——推送复用同一流）。 */
        void awaitGetStream() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (streams.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(streams).as("GET 流必须已建立").isNotEmpty();
        }

        /** 等待 POST 到达（≤5s）。 */
        void awaitPost() throws InterruptedException {
            awaitPostCount(1);
        }

        void awaitPostCount(int n) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (postCalls.get() < n && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(postCalls.get()).as("POST 必须到达 %d 次", n).isGreaterThanOrEqualTo(n);
        }

        /** 最近一次 POST 的 JSON-RPC id。 */
        long lastPostRequestId() throws Exception {
            return MAPPER.readTree(postBodies.get(postBodies.size() - 1)).get("id").asLong();
        }

        /** 向 GET 流推送 SSE data 帧（复用同一持久流）。 */
        void push(String data) throws Exception {
            if (currentStream == null) {
                currentStream = streams.poll(5, TimeUnit.SECONDS);
            }
            if (currentStream == null) {
                throw new AssertionError("无 GET 流可推送");
            }
            OutputStream out = currentStream.getResponseBody();
            out.write((data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }
}

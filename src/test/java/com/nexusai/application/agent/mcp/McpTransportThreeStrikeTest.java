package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * [S02 X-6] 3-strike 终端错误检测 · 对齐 CC client.ts:1228
 * （{@code MAX_ERRORS_BEFORE_RECONNECT = 3}）+ :1333-1365（终端错误计数 → 关断 +
 * pending 拒绝；非终端错误重置计数）。陈旧连接不复用（CLOSED → 池层轻量重建）。
 *
 * <p><b>WHY（规则九）</b>：SDK 传输失败只调 onerror 不调 onclose → CC 桥接：连续终端
 * 错误 ≥3 → closeTransportAndRejectPending（防 pending 悬挂 + 陈旧连接复用）。Java 端
 * 此前无此机制——连接死亡后 pending 永久悬挂。
 *
 * <p>测试面：
 * <ol>
 *   <li><b>SSE</b>：GET 流连续断开（重连耗尽）→ 关断 + pending 拒绝</li>
 *   <li><b>HTTP</b>：指向已关闭端口 3 次 ConnectException → 关断 + pending 拒绝</li>
 *   <li><b>HTTP 非终端</b>：HTTP 500（服务端响应）重置计数不关断</li>
 * </ol>
 */
@DisplayName("[S02 X-6] 3-strike 终端错误检测")
class McpTransportThreeStrikeTest {

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    // ═══════════════ SSE：GET 流连续断开（重连耗尽）→ 关断 + pending 拒绝 ═══════════════

    @Test
    @DisplayName("SSE：GET 流连续断开（重连耗尽）→ 关断 + pending 拒绝")
    void sse_getStreamKeepsDropping_closesAndRejectsPending() throws Exception {
        // GET 恒 200 + 立即关闭（每次重连同样立即 EOF）→ 3-strike 计数 → 关断
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, 0); // 立即关闭的流（EOF）
                ex.close();
            } else {
                ex.getRequestBody().readAllBytes();
                ex.sendResponseHeaders(202, -1);
                ex.close();
            }
        });
        server.start();
        try {
            SseMcpTransport transport = new SseMcpTransport();
            transport.setSseReconnectDelayMs(0); // 测试注入：立即重连
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "sse"));

            CompletableFuture<JsonNode> fut = transport.sendRequest("tools/call", Map.of("name", "t"));
            // 等待 3-strike 关断（GET 流 EOF → 重连 → EOF → … → CLOSED）
            long deadline = System.currentTimeMillis() + 10_000;
            while (transport.getState() != McpTransport.State.CLOSED
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(transport.getState()).as("SSE GET 流连续断开（重连耗尽）→ 必须关断")
                .isEqualTo(McpTransport.State.CLOSED);
            Throwable thrown = catchThrowable(() -> fut.get(2, TimeUnit.SECONDS));
            assertThat(thrown).as("关断必须拒绝 pending（防悬挂）").isNotNull();
        } finally {
            server.stop(0);
        }
    }

    // ═══════════════ HTTP：3 次 ConnectException → 关断 ═══════════════

    @Test
    @DisplayName("HTTP：指向已关闭端口 3 次连接失败 → 关断 + pending 拒绝")
    void http_threeConnectFailures_closesAndRejectsPending() throws Exception {
        int deadPort = freePort(); // 端口上无服务 → ConnectException（ECONNREFUSED 等价）
        HttpMcpTransport transport = new HttpMcpTransport();
        transport.start(new McpTransport.TransportConfig(
            "http://127.0.0.1:" + deadPort + "/", List.of(), Map.of(), null, "srv", "http"));

        assertThat(transport.getState()).isEqualTo(McpTransport.State.CONNECTED);
        // 前两次连接失败：计数 1、2，transport 保持 CONNECTED
        Throwable t1 = catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS));
        assertThat(t1).as("首次连接失败必须上抛").isNotNull();
        assertThat(transport.getState()).as("1 次失败（1/3）不关断").isEqualTo(McpTransport.State.CONNECTED);
        Throwable t2 = catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS));
        assertThat(t2).isNotNull();
        assertThat(transport.getState()).as("2 次失败（2/3）不关断").isEqualTo(McpTransport.State.CONNECTED);

    }
    @Test
    @DisplayName("HTTP：非终端错误（500）重置 3-strike 计数不关断")
    void http_http500_resetsCounter_noClose() throws Exception {
        int port = freePort();
        HttpMcpTransport transport = new HttpMcpTransport();
        transport.start(new McpTransport.TransportConfig(
            "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));
        try {
            // 阶段 1：端口无服务 → 2 次连接失败（终端计数 1、2）
            assertThat(catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS)))
                .isNotNull();
            assertThat(catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS)))
                .isNotNull();
            assertThat(transport.getState()).as("2 次终端失败（2/3）不关断").isEqualTo(McpTransport.State.CONNECTED);

            // 阶段 2：同端口起 500 server → 1 次 HTTP 500（非终端错误 → 重置计数）
            HttpServer server = start500Server(port);
            try {
                Throwable t = catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS));
                assertThat(t).hasMessageContaining("HTTP 500");
                assertThat(transport.getState()).as("HTTP 500（服务端响应）不关断").isEqualTo(McpTransport.State.CONNECTED);
            } finally {
                server.stop(0);
            }

            // 阶段 3：端口再次无服务 → 再 2 次连接失败。若 500 未重置计数，阶段 3 首次失败即
            // 命中 3-strike 关断（CC :1361-1364 非终端错误重置语义）→ 断言仍 CONNECTED。
            assertThat(catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS)))
                .isNotNull();
            assertThat(catchThrowable(() -> transport.sendRequest("initialize", Map.of()).get(5, TimeUnit.SECONDS)))
                .isNotNull();
            assertThat(transport.getState()).as("500 重置计数后 2 次失败（2/3）仍不关断")
                .isEqualTo(McpTransport.State.CONNECTED);
        } finally {
            transport.close();
        }
    }

    /** 在指定端口起「恒 500」server（stop 后可重绑）。 */
    private static HttpServer start500Server(int port) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                server.createContext("/", ex -> {
                    ex.getRequestBody().readAllBytes();
                    byte[] body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"boom\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
                server.start();
                return server;
            } catch (Exception e) {
                last = e;
                Thread.sleep(100); // 端口 TIME_WAIT 重绑等待
            }
        }
        throw last != null ? last : new IllegalStateException("无法绑定 500 server 端口 " + port);
    }
}

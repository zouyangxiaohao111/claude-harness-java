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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S02 D-2] HTTP session 从 initialize 响应协商 Mcp-Session-Id · 对齐 mcp-core 0.14.0
 * HttpClientStreamableHttpTransport.java:239-241（仅 sessionId.isPresent() 时附加
 * MCP_SESSION_ID 头）+ :446-447（markInitialized 捕获响应头）。<b>不自产 UUID 预发</b>
 * （D-S02-1 脏代码删除：自产未知 id → 支持 session 的服务器 404 → 反复 404 循环）。
 *
 * <p><b>WHY（规则九）</b>：旧实现构造期自产随机 UUID 并在每请求无条件预发 Mcp-Session-Id
 * ——支持 session 的 server 对未知 id 回 404，客户端继续用同 id 重试 → 404 循环
 * （gap-analysis D-2 HIGH）。本测试锁定：
 * <ol>
 *   <li>首请求（initialize）<b>不携带</b> Mcp-Session-Id 头 → 响应头捕获 → 第二请求携带</li>
 *   <li>服务端响应无 Mcp-Session-Id 头 → session 保持 null → 后续请求仍不携带（无 UUID 自产）</li>
 *   <li>已捕获后保持（SDK DefaultMcpTransportSession 语义：仅 404 会话失效 invalidate，
 *       响应无头不清除——真实 server SSE 响应流不带会话头）</li>
 * </ol>
 */
@DisplayName("[S02 D-2] HTTP session 从 initialize 响应协商")
class HttpMcpTransportSessionTest {

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static final String OK_BODY =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{\"name\":\"mock\"},\"capabilities\":{}}}";

    @Test
    @DisplayName("首请求无 Mcp-Session-Id 头 → 响应头捕获 → 第二请求携带；响应无头不清除")
    void negotiateSession_fromInitializeResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstSessionHeader = new AtomicReference<>();
        AtomicReference<String> secondSessionHeader = new AtomicReference<>();
        AtomicReference<String> thirdSessionHeader = new AtomicReference<>();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            int n = calls.incrementAndGet();
            if (n == 1) {
                // 首请求（initialize）：客户端不得预发自产 UUID（D-S02-1）
                firstSessionHeader.set(session);
                ex.getResponseHeaders().add("Mcp-Session-Id", "sess-abc-123");
                byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } else if (n == 2) {
                // 第二请求必须携带协商出的 session
                secondSessionHeader.set(session);
                byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } else {
                // 第三请求：响应不再带会话头 → 已捕获 session 保持（SDK 语义）
                thirdSessionHeader.set(session);
                byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        try {
            HttpMcpTransport transport = new HttpMcpTransport();
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));

            JsonNode r1 = transport.sendRequest("initialize", Map.of()).join();
            assertThat(r1.path("serverInfo").path("name").asText()).isEqualTo("mock");
            assertThat(firstSessionHeader.get()).as("首请求不得预发自产 UUID（D-S02-1）").isNull();

            JsonNode r2 = transport.sendRequest("tools/list", Map.of()).join();
            assertThat(secondSessionHeader.get()).as("第二请求必须携带协商的 Mcp-Session-Id")
                .isEqualTo("sess-abc-123");

            JsonNode r3 = transport.sendRequest("tools/list", Map.of()).join();
            assertThat(thirdSessionHeader.get()).as("响应无会话头时已捕获 session 保持（SDK 语义，"
                    + "SSE 响应流不带头）")
                .isEqualTo("sess-abc-123");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("服务端从不返回 Mcp-Session-Id → 后续请求始终不携带（无 UUID 自产）")
    void noSessionHeader_neverSends() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (calls.incrementAndGet() == 1) {
                first.set(session);
            } else {
                second.set(session);
            }
            byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            HttpMcpTransport transport = new HttpMcpTransport();
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));

            transport.sendRequest("initialize", Map.of()).join();
            transport.sendRequest("tools/list", Map.of()).join();

            assertThat(first.get()).as("无协商 session 时首请求不携带").isNull();
            assertThat(second.get()).as("无协商 session 时后续请求仍不携带（无 UUID 自产）").isNull();
        } finally {
            server.stop(0);
        }
    }
}

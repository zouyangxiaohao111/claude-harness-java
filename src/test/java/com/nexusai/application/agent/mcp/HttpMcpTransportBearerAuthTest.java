package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 · HttpMcpTransport Bearer 注入 + 401-refresh-retry（Q-01 接线）行为验证。
 *
 * <p><b>WHY (规则九)</b>: CC MCP SDK HTTP transport 每次请求调 {@code authProvider.tokens()}
 * 附加 {@code Authorization: Bearer}，401 时 refreshAuthorization 后用新 token 重试一次
 * （client.ts:802-840 + SDK streamableHttp）。Java 端此前 HttpMcpTransport 不附加任何
 * Authorization 头、401 直接抛 {@link McpAuthError}。本测试锁定三条不变量：
 * <ol>
 *   <li>请求附加 {@code Bearer <resolveAccessToken>}</li>
 *   <li>401 → 调 {@code refreshAndGetAccessToken(failedToken)} → 用新 token 重试一次</li>
 *   <li>重试成功 → sendRequest 完成返回服务端 result</li>
 * </ol>
 */
class HttpMcpTransportBearerAuthTest {

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Test
    @DisplayName("401 → 刷新 token → 重试一次，两次请求分别携带新旧 Bearer")
    void sendRequest_attachesBearerAndRefreshesOn401() throws Exception {
        AtomicReference<String> firstAuth = new AtomicReference<>();
        AtomicReference<String> secondAuth = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            ex.getRequestBody().readAllBytes();
            int n = calls.incrementAndGet();
            if (n == 1) {
                // 首次：携带旧 token 仍 401（token 过期）→ 触发 transport 层 refresh
                firstAuth.set(auth);
                ex.sendResponseHeaders(401, 0);
            } else {
                // 重试：携带刷新后的新 token → 200 + result
                secondAuth.set(auth);
                byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
                    .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
            ex.close();
        });
        server.start();
        try {
            McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
            when(provider.resolveAccessToken(any())).thenReturn("at-1");
            when(provider.refreshAndGetAccessToken(any(), eq("at-1"))).thenReturn("at-2");

            HttpMcpTransport transport = new HttpMcpTransport(provider);
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));

            JsonNode result = transport.sendRequest("initialize", Map.of()).join();

            assertThat(calls.get()).as("应恰好两次请求（首 401 + 刷新后重试）").isEqualTo(2);
            assertThat(firstAuth.get()).as("首次请求应带旧 Bearer").isEqualTo("Bearer at-1");
            assertThat(secondAuth.get()).as("重试请求应带刷新后新 Bearer").isEqualTo("Bearer at-2");
            assertThat(result.path("ok").asBoolean()).isTrue();
            verify(provider).refreshAndGetAccessToken(any(), eq("at-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("有 token 且服务端直接 200 → 仅附加 Bearer，不触发刷新")
    void sendRequest_attachesBearerWithout401() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        try {
            McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
            when(provider.resolveAccessToken(any())).thenReturn("at-1");

            HttpMcpTransport transport = new HttpMcpTransport(provider);
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));

            JsonNode result = transport.sendRequest("initialize", Map.of()).join();

            assertThat(auth.get()).isEqualTo("Bearer at-1");
            assertThat(result.path("ok").asBoolean()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("无 provider（未接线）→ 不附加 Authorization，行为同既有（回归）")
    void sendRequest_withoutProvider_noAuthHeader() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        try {
            HttpMcpTransport transport = new HttpMcpTransport(); // 无 provider（测试/无认证）
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));

            JsonNode result = transport.sendRequest("initialize", Map.of()).join();

            assertThat(auth.get()).isNull();
            assertThat(result.path("ok").asBoolean()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[OAuth-R1] 403 + WWW-Authenticate insufficient_scope → 标记 step-up pending（写入 token 存储）")
    void sendRequest_403InsufficientScope_marksStepUpPending() throws Exception {
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            // RFC 6750 §3：WWW-Authenticate 带 scope 参数 + insufficient_scope error
            ex.getResponseHeaders().add("WWW-Authenticate",
                "Bearer scope=\"read write\", error=\"insufficient_scope\"");
            ex.sendResponseHeaders(403, 0);
            ex.close();
        });
        server.start();
        try {
            McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
            when(provider.resolveAccessToken(any())).thenReturn("at-1");

            HttpMcpTransport transport = new HttpMcpTransport(provider);
            transport.start(new McpTransport.TransportConfig(
                "http://127.0.0.1:" + port + "/", List.of(), Map.of(), null, "srv", "http"));

            // 403 insufficient_scope：请求以 403 失败（CC 语义由 SDK 自动重授权，Java 消费在
            // 后续刷新/认证流程），但必须先标记 step-up pending（CC wrapFetchWithStepUpDetection
            // auth.ts:1354-1374，早于 SDK auth() 调用）。
            assertThatThrownBy(() -> transport.sendRequest("tools/list", Map.of()).join())
                .hasMessageContaining("HTTP 403 insufficient_scope");
            verify(provider).markStepUpPending(any(), eq("read write"));
        } finally {
            server.stop(0);
        }
    }
}

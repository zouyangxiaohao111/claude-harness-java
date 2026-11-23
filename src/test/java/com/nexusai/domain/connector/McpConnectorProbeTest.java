package com.nexusai.domain.connector;

import com.nexusai.application.agent.mcp.McpAuthHeaderProvider;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.model.connector.dto.TestResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 「测试连接」= 真 MCP initialize + tools/list（[2026-11-23] 升级，⛔ 不是 GET 探活）。
 *
 * <p>本地假 MCP server 同时验证两件事：
 * ① 传输层从连接器加密凭据源拿到了 Bearer（Authorization 头断言）；
 * ② {@code probeLevel} 能区分 mcp_initialize / connect_only / none。
 */
class McpConnectorProbeTest {

    private HttpServer server;
    private String url;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastXTest = new AtomicReference<>();
    /** true = 无论带什么 token 都回 401（模拟凭据失效）。 */
    private volatile boolean alwaysUnauthorized;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            lastAuthorization.set(auth);
            lastQuery.set(ex.getRequestURI().getRawQuery());
            lastXTest.set(ex.getRequestHeaders().getFirst("X-Test"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (alwaysUnauthorized || auth == null || auth.isBlank()) {
                respond(ex, 401, "");
                return;
            }
            if (body.contains("notifications/initialized")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            if (body.contains("initialize")) {
                respond(ex, 200, """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"stub"}}}
                        """);
                return;
            }
            if (body.contains("tools/list")) {
                respond(ex, 200, """
                        {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"stub"}]}}
                        """);
                return;
            }
            respond(ex, 400, "");
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("授权有效 → initialize + tools/list 成功；Bearer 来自连接器凭据")
    void mcpInitializeSucceeds() {
        McpConnectorProbe probe = probeWith("connector-token", null);

        TestResponse r = probe.probe(def(), true);

        assertThat(r.ok()).isTrue();
        assertThat(r.probeLevel()).isEqualTo("mcp_initialize");
        assertThat(r.message()).contains("tools=1");
        assertThat(r.tools()).hasSize(1);
        assertThat(r.tools().get(0).name()).isEqualTo("search");
        assertThat(r.tools().get(0).description()).isEqualTo("stub");
        assertThat(r.toolCount()).isEqualTo(1);
        assertThat(r.errorCode()).isNull();
        assertThat(lastAuthorization.get()).isEqualTo("Bearer connector-token");
    }

    @Test
    @DisplayName("凭据被拒（401）→ connect_only + ok=false（不是假成功）")
    void unauthorizedIsConnectOnly() {
        alwaysUnauthorized = true;
        McpConnectorProbe probe = probeWith("stale-token", null);

        TestResponse r = probe.probe(def(), true);

        assertThat(r.ok()).isFalse();
        assertThat(r.probeLevel()).isEqualTo("connect_only");
        assertThat(r.message()).contains("401");
        assertThat(r.errorCode()).isEqualTo("auth_failed");
        assertThat(r.toolCount()).isZero();
    }

    @Test
    @DisplayName("尚未连接 → none，不发请求")
    void noCredentialIsNone() {
        McpConnectorProbe probe = probeWith("whatever", null);

        TestResponse r = probe.probe(def(), false);

        assertThat(r.ok()).isFalse();
        assertThat(r.probeLevel()).isEqualTo("none");
        assertThat(r.errorCode()).isEqualTo("no_credentials");
        assertThat(lastAuthorization.get()).isNull();
    }

    @Test
    @DisplayName("query 模板：探测 URL 带渲染后的 key；header 模板：请求带自定义头")
    void templateUrlAndHeaders() {
        McpConnectorProbe probe = probeWith("ignored", null);
        ConnectorDefinition def = def(
                "query", "apiKey", "query", null, url + "?key={key}", Map.of(), "key");

        TestResponse r = probe.probe(def, Map.of("key", "K&1"), true);

        assertThat(r.ok()).isTrue();
        assertThat(lastQuery.get()).contains("key=K%261");

        ConnectorDefinition headerDef = def(
                "header", "apiKey", "header", url, null, Map.of("X-Test", "{token}"), "token");
        TestResponse r2 = probe.probe(headerDef, Map.of("token", "h-1"), true);
        assertThat(r2.ok()).isTrue();
        assertThat(lastXTest.get()).isEqualTo("h-1");
    }

    // ── 工具 ──

    private static ConnectorDefinition def(String key, String authType, String authMode,
                                           String url, String template,
                                           Map<String, String> headers, String tokenField) {
        return new ConnectorDefinition(
                key, key, "desc", authType, "knowledge", List.of(),
                url, null, "http", null, null, null, null,
                false, false, false, null, null, key, "#000000",
                "available", "remote", "supported", null,
                List.of(), true, authMode, template, headers, tokenField);
    }

    private McpConnectorProbe probeWith(String token, String forcedRefresh) {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        ConnectorTokenProvider provider = new ConnectorTokenProvider() {
            @Override
            public Result resolve(String connectorKey) {
                return token == null
                        ? Result.reauthRequired("reauth_required")
                        : Result.connected(token);
            }

            @Override
            public String forceRefresh(String connectorKey) {
                return forcedRefresh;
            }
        };
        return new McpConnectorProbe(new McpAuthHeaderProvider(tokenStore, provider));
    }

    private ConnectorDefinition def() {
        return new ConnectorDefinition(
                "notion", "Notion", "desc", "oauth2", "knowledge", List.of(),
                url, "https://mcp.notion.com", "http", null, null, null, null,
                false, false, false, null, null, "notion", "#000000");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}

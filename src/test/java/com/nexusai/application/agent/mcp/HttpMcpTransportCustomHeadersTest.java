package com.nexusai.application.agent.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-23] 自定义请求头注入：HttpMcpTransport 必须把 mcp_servers.env 里的 headers
 * （如 X-Lark-MCP-UAT / x-api-key）带出去（此前只注入 Bearer）。
 */
class HttpMcpTransportCustomHeadersTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("initialize 请求带自定义头（X-Test: v1）")
    void customHeadersSent() throws Exception {
        AtomicReference<String> got = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            got.set(ex.getRequestHeaders().getFirst("X-Test"));
            byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

        HttpMcpTransport transport = new HttpMcpTransport();
        McpTransport.TransportConfig config = new McpTransport.TransportConfig(
                url, List.of(), Map.of("X-Test", "v1"), null, "srv", "http");
        try {
            transport.start(config);
            transport.sendRequest("initialize", Map.of()).join();
        } finally {
            transport.close();
        }

        assertThat(got.get()).isEqualTo("v1");
    }
}

package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [IMP-E2] HttpMcpTransport 基础设施对齐 · 超时语义（S-7/S-8）+ 连接掉落 3-strike（M-9）
 * + Streamable HTTP 202 → SSE（D-3 对齐 CC）。
 *
 * <p><b>WHY (规则九)</b>: CC client.ts 三处基础设施语义——
 * <ul>
 *   <li>S-7 连接超时 30s（getConnectionTimeoutMs()，client.ts:456-458）+ S-8 每请求 60s 新鲜超时
 *       （MCP_REQUEST_TIMEOUT_MS=60000，client.ts:463）：Java 旧实现 10s 硬编码导致长任务提前超时；</li>
 *   <li>M-9 连接掉落 3-strike（MAX_ERRORS_BEFORE_RECONNECT=3 + closeTransportAndRejectPending，
 *       client.ts:1227-1365）：SSE/HTTP 流中断时在途调用永久挂起（HIGH H-11）；</li>
 *   <li>D-3 HTTP 202 → SSE 流（MCP spec 202 语义）：旧实现 complete null 简化偏离 CC（EV-E3-045）。</li>
 * </ul>
 */
class HttpMcpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static McpTransport.TransportConfig cfg(String url) {
        return new McpTransport.TransportConfig(url, List.of(), Map.of(), null, "srv", "http");
    }

    // ═══════════════ 超时语义（S-7 / S-8）═══════════════

    @Test
    @DisplayName("每请求超时常量 = CC MCP_REQUEST_TIMEOUT_MS 60s（旧实现 10s 提前超时）")
    void requestTimeout_alignsCc60s() {
        // WHY: EV-E3-044 Http 每请求 10s vs CC 60s；长任务（MCP pagination/慢速 SSE）在 Java 端
        // 提前超时。常量锁死 CC 值，防回归到 10s。
        assertThat(HttpMcpTransport.MCP_REQUEST_TIMEOUT_MS).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("连接超时常量 = CC getConnectionTimeoutMs() 30s")
    void connectTimeout_alignsCc30s() {
        // WHY: EV-E3-043 Java 无统一 30s 连接超时；HttpClient.connectTimeout 对齐 CC 30s。
        assertThat(HttpMcpTransport.CONNECT_TIMEOUT_MS).isEqualTo(30_000L);
    }

    // ═══════════════ D-3 · HTTP 202 → SSE 流 ═══════════════

    @Test
    @DisplayName("HTTP 202 → 读取 SSE 响应体 → resolve 对应 pending（对齐 CC StreamableHTTP 202 语义）")
    void sendRequest_202_parsesSseStreamAndResolves() throws Exception {
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            // 202 Accepted：异步处理，响应经 SSE 事件流到达
            String sse = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n";
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(202, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        try {
            HttpMcpTransport transport = new HttpMcpTransport();
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            JsonNode result = transport.sendRequest("initialize", Map.of()).join();
            assertThat(result.path("ok").asBoolean()).as("202 后 SSE 流应携带 JSON-RPC result")
                .isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HTTP 202 → SSE 流未含本请求 id → 可诊断错误完成（不悬挂）")
    void sendRequest_202_noMatchingSseEvent_failsDiagnostically() throws Exception {
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String sse = "data: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}\n\n";
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(202, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        try {
            HttpMcpTransport transport = new HttpMcpTransport();
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            // WHY: 202 接受但 SSE 未回本请求 id → CC 流中断 reject pending 防悬挂（client.ts:1240-1247）；
            // Java 以可诊断错误完成，不永久挂起。
            assertThatThrownBy(() -> transport.sendRequest("initialize", Map.of()).join())
                .hasMessageContaining("HTTP 202");
        } finally {
            server.stop(0);
        }
    }

    // ═══════════════ M-9 · 连接掉落 3-strike ═══════════════

    @Test
    @DisplayName("连续 3 个终端连接错误 → closeTransportAndRejectPending（置 CLOSED + reject 全部在途）")
    void threeTerminalErrors_triggerCloseAndRejectPending() {
        // WHY: CC MAX_ERRORS_BEFORE_RECONNECT=3（client.ts:1228）——SSE/HTTP 流中断时在途调用
        // 永久挂起（HIGH H-11）；3 次终端错误后必须 reject 全部 pending + 置 CLOSED 供下次重连。
        HttpMcpTransport transport = new HttpMcpTransport();
        transport.start(cfg("http://127.0.0.1:9/"));
        CompletableFuture<JsonNode> pending1 = transport.sendRequest("tools/list", Map.of());
        CompletableFuture<JsonNode> pending2 = transport.sendRequest("tools/call", Map.of());

        // 前两次终端错误：计数，pending 仍未决
        transport.recordConnectionError(new java.io.IOException("Connection reset (ECONNRESET)"));
        transport.recordConnectionError(new java.io.IOException("Connection reset (ECONNRESET)"));
        assertThat(transport.getState()).as("2 次终端错误未达上限仍 CONNECTED").isEqualTo(McpTransport.State.CONNECTED);

        // 第 3 次 → closeTransportAndRejectPending
        transport.recordConnectionError(new java.io.IOException("Connection reset (ECONNRESET)"));
        assertThat(transport.getState()).as("3 次终端错误后 transport 置 CLOSED（下次惰性重连）")
            .isEqualTo(McpTransport.State.CLOSED);
        assertThat(pending1).as("在途请求 1 被 reject（防悬挂）").isCompletedExceptionally();
        assertThat(pending2).as("在途请求 2 被 reject（防悬挂）").isCompletedExceptionally();
    }

    @Test
    @DisplayName("非终端错误 → 计数归零，不触发关闭")
    void nonTerminalError_resetsCounter() {
        // WHY: CC client.ts:1361-1364 非终端错误（transient）→ consecutiveConnectionErrors=0。
        HttpMcpTransport transport = new HttpMcpTransport();
        transport.start(cfg("http://127.0.0.1:9/"));
        transport.recordConnectionError(new IllegalStateException("HTTP 500 (transient)"));
        transport.recordConnectionError(new IllegalStateException("HTTP 500 (transient)"));
        // 非终端错误不计数 → 再一个非终端也不触发 3-strike
        transport.recordConnectionError(new IllegalStateException("HTTP 500 (transient)"));
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CONNECTED);
    }

    @Test
    @DisplayName("closeTransportAndRejectPending 重入守卫（hasTriggeredClose）")
    void closeTransportAndRejectPending_guardsReentry() {
        // WHY: CC hasTriggeredClose（client.ts:1232）——close() 中止在途流可能再次触发 onerror，
        // 重入不得重复执行。
        HttpMcpTransport transport = new HttpMcpTransport();
        transport.start(cfg("http://127.0.0.1:9/"));
        transport.closeTransportAndRejectPending("first");
        transport.closeTransportAndRejectPending("second");
        // 无异常即通过（第二调用被守卫跳过）
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CLOSED);
    }
}

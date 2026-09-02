package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S5 · SseMcpTransport OAuth 支持（Q-01 随行）行为验证。
 *
 * <p><b>WHY (规则九)</b>: CC SSE transport 连接经 EventSource GET 长连接注入
 * {@code authProvider.tokens()} Bearer（client.ts:648-671），认证失败 → SDK auth() 触发
 * OAuth 流（client.ts:621-660）。[IMP-E2 D-4] Java SSE GET 连接探针已删除（CC 无探针；
 * Java SSE 为每次请求独立 POST + SSE 响应）——认证挑战移到<b>请求期</b> postRpc（对齐 CC
 * SDK auth() 请求期语义）。本测试锁定 4 条请求期不变量：
 * <ol>
 *   <li>POST 附加 {@code Bearer <resolveAccessToken>}；401 → ①S2 refresh → 新 token 重试 → 成功</li>
 *   <li>POST 401 → refresh 无法恢复 → 触发 S1 OAuth 流（performOAuthFlow）→ 新 token 重试 → 成功</li>
 *   <li>POST 401 → refresh + OAuth 均失败 → 抛 {@link McpAuthError}（needs-auth 降级）</li>
 *   <li>POST 403 + insufficient_scope → 标记 step-up pending</li>
 * </ol>
 * start() 仅校验 URL 并置 CONNECTED（无网络调用，对齐 CC 无探针语义）。
 */
class SseMcpTransportOAuthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /** 解包 CompletableFuture.join() 的 CompletionException 包装，取最内层真实异常。 */
    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.util.concurrent.CompletionException ce
            && ce.getCause() != null) {
            cur = ce.getCause();
        }
        return cur;
    }

    private McpTransport.TransportConfig cfg(String url) {
        return new McpTransport.TransportConfig(url, List.of(), Map.of(), null, "srv", "sse");
    }

    /** 写入 SSE 事件行（data: ...）响应。 */
    private static void writeSse(HttpExchange ex, String data) throws java.io.IOException {
        byte[] body = ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        ex.close();
    }

    @Test
    @DisplayName("[IMP-E2 D-4] start 仅校验 URL 置 CONNECTED，无网络调用（探针已删）")
    void start_validatesUrl_connectsWithoutNetwork() {
        // WHY: CC SSE 无 GET 探针（TR-E3-D-4），Java SSE 为请求期认证；start() 不应发任何网络请求。
        SseMcpTransport transport = new SseMcpTransport();
        transport.start(cfg("http://127.0.0.1:9/sse"));
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CONNECTED);
    }

    @Test
    @DisplayName("POST 401 → S2 refresh → 新 token 重试 → 成功（请求期 401 处理）")
    void sendRequest_401_refreshAndRetrySucceeds() throws Exception {
        AtomicReference<String> firstAuth = new AtomicReference<>();
        AtomicReference<String> secondAuth = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            ex.getRequestBody().readAllBytes();
            int n = calls.incrementAndGet();
            if (n == 1) {
                firstAuth.set(a);
                ex.sendResponseHeaders(401, 0); // token 过期 → 触发 refresh
            } else {
                secondAuth.set(a);
                writeSse(ex, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
            }
            ex.close();
        });
        server.start();
        try {
            McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
            when(provider.resolveAccessToken(any())).thenReturn("at-1");
            when(provider.refreshAndGetAccessToken(any(), eq("at-1"))).thenReturn("at-2");

            SseMcpTransport transport = new SseMcpTransport(provider);
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            var result = transport.sendRequest("initialize", Map.of()).join();
            assertThat(calls.get()).as("应恰好两次 POST（首 401 + refresh 后重试）").isEqualTo(2);
            assertThat(firstAuth.get()).isEqualTo("Bearer at-1");
            assertThat(secondAuth.get()).as("重试应带刷新后新 Bearer").isEqualTo("Bearer at-2");
            assertThat(result.path("ok").asBoolean()).isTrue();
            verify(provider).refreshAndGetAccessToken(any(), eq("at-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("POST 401 → refresh 无法恢复 → 触发 S1 OAuth 流 → 新 token 重试 → 成功")
    void sendRequest_401_refreshFails_oauthFlowThenRetrySucceeds() throws Exception {
        AtomicReference<String> firstAuth = new AtomicReference<>();
        AtomicReference<String> secondAuth = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            ex.getRequestBody().readAllBytes();
            int n = calls.incrementAndGet();
            if (n == 1) {
                firstAuth.set(a);
                ex.sendResponseHeaders(401, 0); // 无有效 token → 挑战
            } else {
                secondAuth.set(a);
                writeSse(ex, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
            }
            ex.close();
        });
        server.start();
        try {
            McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
            when(provider.resolveAccessToken(any())).thenReturn("at-1", "at-oauth");
            when(provider.refreshAndGetAccessToken(any(), eq("at-1"))).thenReturn(null); // 无 refreshToken
            when(provider.performOAuthFlow(any(), any())).thenReturn(
                new McpAuth.AuthResult(true,
                    new McpAuth.Tokens("at-oauth", "rt-oauth",
                        System.currentTimeMillis() + 3600_000L, "read"),
                    null, null));

            SseMcpTransport transport = new SseMcpTransport(provider);
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            var result = transport.sendRequest("initialize", Map.of()).join();
            assertThat(calls.get()).as("应恰好两次 POST（首 401 + OAuth 后重试）").isEqualTo(2);
            assertThat(firstAuth.get()).isEqualTo("Bearer at-1");
            assertThat(secondAuth.get()).as("重试应带 OAuth 流产生的新 Bearer").isEqualTo("Bearer at-oauth");
            verify(provider).performOAuthFlow(any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("POST 401 → refresh + OAuth 均失败 → 抛 McpAuthError（needs-auth 降级）")
    void sendRequest_401_oauthFlowFails_throwsMcpAuthError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            ex.sendResponseHeaders(401, 0);
            ex.close();
        });
        server.start();
        try {
            McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
            when(provider.resolveAccessToken(any())).thenReturn("at-1");
            when(provider.refreshAndGetAccessToken(any(), eq("at-1"))).thenReturn(null);
            when(provider.performOAuthFlow(any(), any())).thenReturn(
                new McpAuth.AuthResult(false, null, "OAuth flow failed",
                    McpAuth.MCPOAuthFlowErrorReason.AUTH_TIMEOUT));

            SseMcpTransport transport = new SseMcpTransport(provider);
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            Throwable thrown = catchThrowable(
                () -> transport.sendRequest("tools/list", Map.of()).join());
            assertThat(rootCause(thrown)).isInstanceOf(McpAuthError.class);
            assertThat(rootCause(thrown).getMessage())
                .as("McpAuthError 消息应含 re-authorization（needs-auth 降级）")
                .contains("re-authorization");
            verify(provider).performOAuthFlow(any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[IMP-E2 M-9] SSE 流中断（EOF 未响应）→ reject pending，不永久挂起")
    void sendRequest_streamEndedWithoutResponse_rejectsPending() throws Exception {
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            // 只推送非本请求 id 的事件后立即关闭 → 本请求 pending 永不 resolve → 流中断 reject
            String sse = "data: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}\n\n";
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        try {
            SseMcpTransport transport = new SseMcpTransport();
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            // WHY: EV-E3-048/风险② —— SSE 流中断而 transport 未 reject pending → future 永久挂起
            // （SseMcpTransport.java:263-270 旧实现）；对齐 CC closeTransportAndRejectPending
            // （client.ts:1240-1247）后，流中断 → 在途请求以可诊断错误完成。
            Throwable thrown = catchThrowable(
                () -> transport.sendRequest("tools/list", Map.of()).join());
            assertThat(rootCause(thrown).getMessage())
                .as("流中断应 reject 在途请求（不永久挂起）")
                .contains("SSE stream ended");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[IMP-E2 rework F1] SSE 每请求读超时 = CC MCP_REQUEST_TIMEOUT_MS 60s + 连接超时 30s")
    void timeoutConstants_alignCc() {
        // WHY (规则九): 原 SSE POST 无任何超时（HttpURLConnection 默认无限）→「server 接受连接后
        // 不发数据也不断开」的静默场景永久挂起（H-11 域）；CC wrapFetchWithTimeout 每请求 60s
        // （client.ts:463 + client.ts:492-550，GET 长连接流排除）+ 连接 30s（client.ts:456-458）。
        // Java SSE 每请求独立连接，读超时不会误伤共享长流。常量锁死 CC 值，防回归到无超时。
        assertThat(SseMcpTransport.MCP_REQUEST_TIMEOUT_MS).isEqualTo(60_000L);
        assertThat(SseMcpTransport.CONNECT_TIMEOUT_MS).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("[IMP-E2 rework F2] 并发请求：一流中断仅 fail 当前请求，另一请求正常完成（不误伤）")
    void concurrentRequests_streamBreakFailsOnlyCurrentRequest() throws Exception {
        // WHY (规则九): CC 所有请求共享一条 EventSource GET 长流，流断 → reject 全部在途正确
        // （client.ts:1240-1247）；Java SSE 每请求独立连接，transport 级 failAllPending 会把仍在
        // 其它连接正常读取的并发请求误失败。回归锁定：流中断只 fail 本请求，并发请求不受误伤。
        // 有序化：正常请求的响应延迟到「流中断请求已写+关闭」之后 → 旧 failAllPending 实现会在
        // 正常请求 resolve 前把其 future 误失败（RED），修复后仅 fail 流中断请求（GREEN）。
        int port = freePort();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        java.util.concurrent.CountDownLatch brokenStreamWritten = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", ex -> {
            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode rpc = MAPPER.readTree(body);
                if ("tools/call".equals(rpc.path("method").asText())) {
                    // 正常请求：等流中断请求先写+关闭（EOF 先行）+ 小宽限（客户端处理 EOF 后），
                    // 再回本请求 id 的响应事件 → resolve（旧 failAllPending 实现此时已误伤本请求）
                    brokenStreamWritten.await(5, TimeUnit.SECONDS);
                    Thread.sleep(200);
                    writeSse(ex, "{\"jsonrpc\":\"2.0\",\"id\":" + rpc.path("id").asLong()
                        + ",\"result\":{\"ok\":true}}");
                } else {
                    // 流中断请求：只回非本请求 id 事件后立即关闭 → 本请求 pending 永不 resolve → EOF reject
                    writeSse(ex, "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}");
                    brokenStreamWritten.countDown();
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();
        try {
            SseMcpTransport transport = new SseMcpTransport();
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            // 两请求并发在途（各自独立 POST + SSE 连接）；tools/call 正常完成，tools/list 流中断。
            CompletableFuture<JsonNode> callFut = CompletableFuture.supplyAsync(
                () -> transport.sendRequest("tools/call", Map.of()).join());
            CompletableFuture<JsonNode> listFut = CompletableFuture.supplyAsync(
                () -> transport.sendRequest("tools/list", Map.of()).join());

            JsonNode good = callFut.get(5, TimeUnit.SECONDS);
            assertThat(good.path("ok").asBoolean())
                .as("并发另一请求不应被本请求的流中断误伤")
                .isTrue();
            Throwable thrown = catchThrowable(() -> listFut.get(5, TimeUnit.SECONDS));
            assertThat(rootCause(thrown).getMessage())
                .as("流中断仅 reject 当前请求（不永久挂起）")
                .contains("SSE stream ended");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("POST 403 + WWW-Authenticate insufficient_scope → 标记 step-up pending")
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

            SseMcpTransport transport = new SseMcpTransport(provider);
            transport.start(cfg("http://127.0.0.1:" + port + "/"));

            // 403 insufficient_scope：请求以 403 失败（Java 无 SDK 自动重授权），但必须先标记
            // step-up pending（对齐 CC wrapFetchWithStepUpDetection auth.ts:1354-1374）。
            Throwable thrown = catchThrowable(
                () -> transport.sendRequest("tools/list", Map.of()).join());
            assertThat(rootCause(thrown).getMessage())
                .as("403 请求应失败（非 2xx）")
                .contains("HTTP 403");
            verify(provider).markStepUpPending(any(), eq("read write"));
        } finally {
            server.stop(0);
        }
    }
}

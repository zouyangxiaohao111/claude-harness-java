package com.nexusai.application.agent.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback 回调监听器 · 对齐 CC auth.ts:1099-1214 的 {@code createServer} + {@code /callback} 处理。
 *
 * <p><b>职责</b>：在 {@code 127.0.0.1:<port>} 上监听 OAuth 授权回调，校验 state 防 CSRF，
 * 提取授权码。这是 performMCPOAuthFlow 编排步骤③（loopback 回调监听）+ ④（state 校验）的
 * {@link McpAuth.CallbackHandler} 真实实现（CC 用 Node http.createServer，Java 用 JDK HttpServer）。
 *
 * <p><b>状态机</b>：{@link #bind(int, String)} 先绑定监听（对齐 CC createServer + listen 早于
 * 浏览器打开），再 {@link #waitForAuthorizationCode} 阻塞等待；回调到达后按 CC 顺序处理：
 * <ol>
 *   <li>无 error 且 state 不匹配 → HTTP 400 + 「Invalid state parameter」+ 拒绝（CSRF 攻击）</li>
 *   <li>有 error → HTTP 200 + 净化后的错误页 + 拒绝（provider_denied）</li>
 *   <li>有 code → HTTP 200 + 「Authentication Successful」+ 返回 code</li>
 * </ol>
 *
 * <p><b>超时</b>：{@code timeoutMs} 超时抛「Authentication timeout」。
 * <b>XSS 防御</b>：错误页内联 error 文本前先转义 HTML（对齐 CC {@code xss()}）。
 */
public final class LoopbackCallbackHandler implements McpAuth.CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(LoopbackCallbackHandler.class);

    private volatile HttpServer server;
    private volatile CompletableFuture<String> codeFuture;
    private volatile String expectedState;

    /**
     * 绑定 loopback server 到指定端口（CC createServer + listen，仅 127.0.0.1 监听）。
     *
     * @param port  回调端口（与 buildRedirectUri 同一端口）
     * @param state 期望的 OAuth state（回调校验用）
     * @throws IOException 端口占用 / 绑定失败
     */
    public synchronized void bind(int port, String state) throws IOException {
        close(); // 幂等：先关旧 server（单实例可复用多次 flow）
        this.expectedState = state;
        this.codeFuture = new CompletableFuture<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/callback", this::handle);
        s.start();
        this.server = s;
        if (log.isDebugEnabled()) {
            log.debug("[LoopbackCallbackHandler] 已绑定 127.0.0.1:{} /callback", port);
        }
    }

    /** 回调处理（对齐 CC auth.ts:1099-1151）。 */
    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String code = query.get("code");
        String state = query.get("state");
        String error = query.get("error");
        String errorDescription = query.getOrDefault("error_description", "");
        String errorUri = query.get("error_uri");

        try {
            if (error == null && !expectedState.equals(state)) {
                // CSRF 防护：state 不匹配（CC auth.ts:1110-1118）
                respond(exchange, 400,
                    "<h1>Authentication Error</h1><p>Invalid state parameter. Please try again.</p>"
                        + "<p>You can close this window.</p>");
                completeExceptionally("OAuth state mismatch - possible CSRF attack");
                return;
            }
            if (error != null) {
                // provider 拒绝授权（CC auth.ts:1120-1140）— 错误文本先净化防 XSS
                respond(exchange, 200,
                    "<h1>Authentication Error</h1><p>" + escape(error) + ": " + escape(errorDescription)
                        + "</p><p>You can close this window.</p>");
                String message = "OAuth error: " + error
                    + (errorDescription.isEmpty() ? "" : " - " + errorDescription)
                    + (errorUri == null ? "" : " (See: " + errorUri + ")");
                completeExceptionally(message);
                return;
            }
            if (code != null) {
                respond(exchange, 200,
                    "<h1>Authentication Successful</h1><p>You can close this window. Return to Claude Code.</p>");
                CompletableFuture<String> f = codeFuture;
                if (f != null) {
                    f.complete(code);
                }
                return;
            }
            // 非有效回调 URL，忽略以便重试（CC 分支外无响应）
        } finally {
            exchange.close();
        }
    }

    @Override
    public String waitForAuthorizationCode(String state, long timeoutMs) {
        // 编排层已先 bind(port, state)；此处幂等兜底（未 bind 时抛明确错误）
        if (server == null || codeFuture == null) {
            throw new IllegalStateException(
                "OAuth callback server not bound — call bind(port, state) before waitForAuthorizationCode");
        }
        try {
            String code = codeFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) {
                log.debug("[LoopbackCallbackHandler] 收到授权码 code 长度={}", code == null ? 0 : code.length());
            }
            return code;
        } catch (TimeoutException e) {
            throw new IllegalStateException("Authentication timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Authentication was cancelled", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        } finally {
            close();
        }
    }

    /** 关闭 loopback server（CC cleanup：close + 吞掉迟到的 error）。幂等。 */
    public synchronized void close() {
        HttpServer s = server;
        server = null;
        codeFuture = null;
        expectedState = null;
        if (s != null) {
            s.stop(0);
            if (log.isDebugEnabled()) {
                log.debug("[LoopbackCallbackHandler] 已关闭 loopback server");
            }
        }
    }

    private void completeExceptionally(String message) {
        CompletableFuture<String> f = codeFuture;
        if (f != null && !f.isDone()) {
            f.completeExceptionally(new IllegalStateException(message));
        }
    }

    private static void respond(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 解析 query string（URLDecoder，RFC 6749 query 参数）。 */
    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                out.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** 最小 HTML 转义（对齐 CC xss()：防错误页 XSS）。 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

package com.nexusai.domain.connector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 连接器通用 loopback 回调监听 · <b>按 state 路由</b>（多 provider / 多连接器共用）。
 *
 * <p><b>为什么不复用 {@code ClaudeOAuthCallbackServer}</b>：那个类端口写死 53692、回调直接绑死
 * {@code ClaudeOAuthService}，两个连接器同时授权会互相覆盖。
 * <b>为什么不直接用 {@code LoopbackCallbackHandler}</b>：它为「一次 flow 一个期望 state」设计，
 * {@code bind(port, state)} 后只能校验那一个 state —— 无法在一个端口上同时服务多个连接器。
 * 本类沿用它的<b>语义</b>（state 不匹配 → 400；provider 拒绝 → 200 错误页；成功 → 200），
 * 但把 state 校验改为「查在途表」，从而支持并发授权。
 *
 * <p>绑定地址固定 {@code 127.0.0.1}；优先用 {@code nexusai.connector.callback-port}
 * （缺省 34118），被占用则回落到随机端口。{@code redirect_uri} 用 loopback IP 字面量
 * （RFC 8252 §7.3），与 DCR 注册的回调地址保持一致。
 */
@Component
public class ConnectorCallbackServer {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCallbackServer.class);

    public static final String CALLBACK_PATH = "/callback";

    private final int preferredPort;
    private final Object lock = new Object();
    private final Map<String, PendingFlow> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CallbackResult>> futures = new ConcurrentHashMap<>();

    private volatile HttpServer server;
    private volatile int port = -1;

    public ConnectorCallbackServer(
            @Value("${nexusai.connector.callback-port:34118}") int preferredPort) {
        this.preferredPort = preferredPort;
    }

    /** 授权流程的一次性在途信息（授权成功后用于换 token）。 */
    public record PendingFlow(
            String connectorKey,
            String codeVerifier,
            String clientId,
            String clientSecret,
            String authorizationEndpoint,
            String tokenEndpoint,
            String registrationEndpoint,
            String redirectUri,
            String scope,
            boolean tokenExpires,
            long expiresAt) {
    }

    /** 回调结果：{@code code} 与 {@code error} 最多一个非空。 */
    public record CallbackResult(String code, String error) {
        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * 确保监听已启动，返回实际端口。
     *
     * @throws IOException 首选端口与随机端口都绑定失败
     */
    public int ensureStarted() throws IOException {
        if (server != null) {
            return port;
        }
        synchronized (lock) {
            if (server != null) {
                return port;
            }
            HttpServer s;
            try {
                s = createAndStart(preferredPort > 0 ? preferredPort : 0);
            } catch (IOException e) {
                if (preferredPort <= 0) {
                    throw e;
                }
                log.warn("[ConnectorCallback] 首选端口 {} 绑定失败（{}），回落随机端口", preferredPort, e.getMessage());
                s = createAndStart(0);
            }
            this.server = s;
            this.port = s.getAddress().getPort();
            log.info("[ConnectorCallback] loopback 回调已监听 http://127.0.0.1:{}{}", port, CALLBACK_PATH);
            return port;
        }
    }

    private HttpServer createAndStart(int bindPort) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", bindPort), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext(CALLBACK_PATH, this::handle);
        s.start();
        return s;
    }

    /** 当前 loopback 回调地址（会确保监听已启动）。 */
    public String redirectUri() throws IOException {
        return "http://127.0.0.1:" + ensureStarted() + CALLBACK_PATH;
    }

    /** 当前实际端口（未启动为 -1）。 */
    public int port() {
        return port;
    }

    /**
     * 登记一次授权流程，返回回调结果 future。
     *
     * @param state     一次性 state（回调按它路由）
     * @param flow      在途信息
     * @param ttlMillis state 有效期
     * @return 回调到达/过期/出错时完成的 future
     */
    public CompletableFuture<CallbackResult> register(String state, PendingFlow flow, long ttlMillis) {
        long now = System.currentTimeMillis();
        // 清理过期在途流程：连同 future 一起了结，避免内存里留下永不完成的等待者
        pending.entrySet().removeIf(e -> {
            if (e.getValue().expiresAt() < now) {
                CompletableFuture<CallbackResult> f = futures.remove(e.getKey());
                if (f != null) {
                    f.complete(new CallbackResult(null, ConnectorCredentialService.AUTH_EXPIRED_MESSAGE));
                }
                return true;
            }
            return false;
        });
        futures.values().removeIf(CompletableFuture::isDone);
        PendingFlow timed = new PendingFlow(
                flow.connectorKey(), flow.codeVerifier(), flow.clientId(), flow.clientSecret(),
                flow.authorizationEndpoint(), flow.tokenEndpoint(), flow.registrationEndpoint(),
                flow.redirectUri(), flow.scope(), flow.tokenExpires(), now + ttlMillis);
        CompletableFuture<CallbackResult> future = new CompletableFuture<>();
        pending.put(state, timed);
        futures.put(state, future);
        return future;
    }

    /** 取在途流程（仅测试/诊断用）。 */
    PendingFlow peek(String state) {
        return pending.get(state);
    }

    /** 关闭监听（测试/退出用）。幂等。 */
    public void stop() {
        synchronized (lock) {
            HttpServer s = server;
            server = null;
            port = -1;
            pending.clear();
            futures.values().forEach(f -> f.complete(new CallbackResult(null, "callback server stopped")));
            futures.clear();
            if (s != null) {
                s.stop(0);
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            String state = q.get("state");
            PendingFlow flow = state == null ? null : pending.remove(state);
            CompletableFuture<CallbackResult> future = state == null ? null : futures.remove(state);
            if (flow == null || future == null) {
                // CSRF 防护：未知/重复/缺失 state 一律拒绝（语义同 LoopbackCallbackHandler）
                respond(exchange, 400,
                        "<h1>Authentication Error</h1><p>Invalid state parameter. Please try again.</p>"
                                + "<p>You can close this window.</p>");
                if (future != null) {
                    future.complete(new CallbackResult(null, "OAuth state mismatch - possible CSRF attack"));
                }
                return;
            }
            if (System.currentTimeMillis() > flow.expiresAt()) {
                respond(exchange, 400,
                        "<h1>Authentication Error</h1><p>This authorization request has expired. "
                                + "Please try again.</p>");
                future.complete(new CallbackResult(null, ConnectorCredentialService.AUTH_EXPIRED_MESSAGE));
                return;
            }
            String error = q.get("error");
            if (error != null) {
                String description = q.getOrDefault("error_description", "");
                respond(exchange, 200,
                        "<h1>Authentication Error</h1><p>" + escape(error) + ": " + escape(description)
                                + "</p><p>You can close this window.</p>");
                future.complete(new CallbackResult(null,
                        "OAuth error: " + error + (description.isEmpty() ? "" : " - " + description)));
                return;
            }
            String code = q.get("code");
            if (code != null && !code.isBlank()) {
                respond(exchange, 200,
                        "<h1>Authentication Successful</h1><p>You can close this window. "
                                + "Return to NexusAI.</p>");
                future.complete(new CallbackResult(code, null));
                return;
            }
            respond(exchange, 400,
                    "<h1>Authentication Error</h1><p>Missing authorization code.</p>");
            future.complete(new CallbackResult(null, "missing authorization code"));
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

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

    /** 最小 HTML 转义（错误页防 XSS，对齐 LoopbackCallbackHandler）。 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

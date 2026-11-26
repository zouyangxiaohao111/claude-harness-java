package com.nexusai.application.agent.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Google 订阅登录的回调监听（{@code 127.0.0.1:<临时端口>/oauth2callback}）。
 *
 * <p><b>为什么是动态端口</b>：官方浏览器流用临时端口（oauth2.ts:660-691
 * {@code getAvailablePort}），redirect_uri 随端口变化；⛔ 不要照搬 Claude 的固定 53692。
 * 端口由 {@link GoogleOAuthService} 用 {@code new ServerSocket(0)} 选出后传入。
 *
 * <p><b>同一时刻只保留一个监听</b>：用户重复点「登录」会换端口 ⇒ {@link #start(int)} 先停旧的再起新的。
 *
 * <p><b>失败降级</b>：端口被占用 / 启动失败只记 warn 并返回 false（前端仍可走「粘贴回调 URL」的
 * {@code complete} 回退），⛔ 不抛异常、不影响应用启动。
 *
 * <p><b>线程全是 daemon</b>；空闲 10 分钟自动停机。
 *
 * <p><b>不打印 token / code / state</b>：日志只记状态码与可读原因。
 */
@Component
public class GoogleOAuthCallbackServer {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthCallbackServer.class);

    /** 回调路径（与 redirect_uri 一致）。 */
    static final String CALLBACK_PATH = GoogleOAuthProviderConfig.CALLBACK_PATH;

    /** 空闲多久后自动停机。 */
    static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    private static final long IDLE_CHECK_PERIOD_MS = TimeUnit.MINUTES.toMillis(1);
    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";

    private final GoogleOAuthService googleOAuthService;

    private HttpServer server;
    private int port;
    private ExecutorService executor;
    private ScheduledExecutorService idleTimer;
    private volatile long lastRequestAt;

    private final Object lock = new Object();

    public GoogleOAuthCallbackServer(GoogleOAuthService googleOAuthService) {
        this.googleOAuthService = googleOAuthService;
    }

    /**
     * 在指定回环端口启动监听（幂等：同端口已跑 → true；不同端口 → 先停旧的）。
     *
     * @return true = 正在监听；false = 端口占用 / 启动失败（调用方降级手动粘贴）
     */
    public boolean start(int requestedPort) {
        synchronized (lock) {
            if (server != null && port == requestedPort) {
                return true;
            }
            if (server != null) {
                stopLocked();
            }
            HttpServer created;
            try {
                created = HttpServer.create(
                    new InetSocketAddress(GoogleOAuthProviderConfig.CALLBACK_HOST, requestedPort), 0);
            } catch (BindException e) {
                log.warn("[GoogleOAuth] 端口 {} 被占用，降级为手动粘贴模式: {}", requestedPort, e.getMessage());
                return false;
            } catch (IOException e) {
                log.warn("[GoogleOAuth] 回调监听启动失败，降级为手动粘贴模式: {}", e.getMessage());
                return false;
            }
            created.createContext(CALLBACK_PATH, this::handle);
            executor = Executors.newCachedThreadPool(daemonFactory("google-oauth-callback"));
            created.setExecutor(executor);
            created.start();
            server = created;
            port = requestedPort;
            lastRequestAt = System.currentTimeMillis();
            idleTimer = Executors.newSingleThreadScheduledExecutor(daemonFactory("google-oauth-idle"));
            idleTimer.scheduleAtFixedRate(this::stopIfIdle, IDLE_CHECK_PERIOD_MS,
                IDLE_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
            log.info("[GoogleOAuth] 回调监听已启动 http://127.0.0.1:{}{}", requestedPort, CALLBACK_PATH);
            return true;
        }
    }

    /** 当前是否监听在指定端口。 */
    public boolean isRunningOn(int candidatePort) {
        synchronized (lock) {
            return server != null && port == candidatePort;
        }
    }

    /** 当前监听端口；未监听 → -1。 */
    public int runningPort() {
        synchronized (lock) {
            return server == null ? -1 : port;
        }
    }

    /** 主动停机（退出登录 / 空闲自停 / 应用关闭走这里；已停则空操作）。 */
    @PreDestroy
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        if (server == null) {
            return;
        }
        int stoppedPort = port;
        server.stop(0);
        server = null;
        port = -1;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (idleTimer != null) {
            idleTimer.shutdownNow();
            idleTimer = null;
        }
        log.info("[GoogleOAuth] 回调监听已停止（127.0.0.1:{}）", stoppedPort);
    }

    private void stopIfIdle() {
        if (System.currentTimeMillis() - lastRequestAt < IDLE_TIMEOUT_MS) {
            return;
        }
        synchronized (lock) {
            log.info("[GoogleOAuth] {} 分钟无回调请求，自动停止监听",
                TimeUnit.MILLISECONDS.toMinutes(IDLE_TIMEOUT_MS));
            stopLocked();
        }
    }

    private void handle(HttpExchange exchange) {
        lastRequestAt = System.currentTimeMillis();
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, TEXT, "仅支持 GET 回调");
                return;
            }
            Map<String, String> query = queryParams(exchange.getRequestURI().getRawQuery());
            String error = query.get("error");
            String code = query.get("code");
            String state = query.get("state");

            if (error != null && !error.isBlank()) {
                // 交给服务按「当前有效未过期 attempt」受限终结；⛔ 不回显 error/error_description/state/HTML。
                try {
                    googleOAuthService.onCallbackError(state, error);
                } catch (RuntimeException e) {
                    log.warn("[GoogleOAuth] 回调 error 处理异常: {}", e.getClass().getSimpleName());
                }
                respond(exchange, 400, HTML, errorHtml());
                return;
            }
            if (code == null || code.isBlank()) {
                respond(exchange, 400, TEXT, "回调里没有 code 参数，请重新点击登录");
                return;
            }
            if (state == null || state.isBlank()) {
                respond(exchange, 400, TEXT, "回调里没有 state 参数，请重新点击登录");
                return;
            }
            try {
                googleOAuthService.onCallback(state, code);
            } catch (IllegalArgumentException e) {
                log.warn("[GoogleOAuth] 回调登录失败: {}", e.getMessage());
                respond(exchange, 400, TEXT, readable(e.getMessage()));
                return;
            } catch (OAuthTokenExchangeError e) {
                log.warn("[GoogleOAuth] 回调换 token 失败: error={} detail={}",
                    e.errorCode(), e.getMessage());
                respond(exchange, 400, TEXT, "授权码兑换失败，请重新点击登录获取新的授权链接");
                return;
            } catch (RuntimeException e) {
                log.error("[GoogleOAuth] 回调处理异常", e);
                respond(exchange, 500, TEXT, "登录失败，请回到应用重试或改用手动粘贴");
                return;
            }
            log.info("[GoogleOAuth] 回调登录完成");
            respond(exchange, 200, HTML, successHtml());
        } catch (IOException e) {
            log.warn("[GoogleOAuth] 回调响应写出失败: {}", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static String successHtml() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<title>登录成功</title></head>"
            + "<body style=\"font-family:system-ui,-apple-system,sans-serif;padding:48px;"
            + "text-align:center;color:#1f2328\">"
            + "<h2>登录成功，可以关闭此窗口</h2>"
            + "<p style=\"color:#59636e\">已自动完成 Google 订阅登录，回到应用即可。</p>"
            + "</body></html>";
    }

    /** 错误页：固定安全文案，⛔ 不拼任何 provider 输入。 */
    private static String errorHtml() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<title>授权未完成</title></head>"
            + "<body style=\"font-family:system-ui,-apple-system,sans-serif;padding:48px;"
            + "text-align:center;color:#1f2328\">"
            + "<h2>授权未完成</h2>"
            + "<p style=\"color:#59636e\">请回到应用，重新点击「登录」获取新的授权链接。</p>"
            + "</body></html>";
    }

    private static String readable(String message) {
        return message == null || message.isBlank() ? "登录失败，请重新点击登录" : message;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
            if (!result.containsKey(decodedKey)) {
                result.put(decodedKey, URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

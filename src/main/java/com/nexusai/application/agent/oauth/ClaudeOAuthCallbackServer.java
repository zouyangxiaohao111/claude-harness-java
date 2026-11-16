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
 * Claude 订阅登录的 53692 回调监听（自动完成登录）。
 *
 * <p><b>为什么监听固定端口</b>：Claude Code 的 public client 把 {@code redirect_uri} 写死为
 * {@code http://localhost:53692/callback}（见 {@link ClaudeOAuthProviderConfig#REDIRECT_URI}），
 * 改不了。监听后浏览器跳转直接被本机接住，用户不必再复制地址栏。
 *
 * <p><b>⚠️ 绑 127.0.0.1 但 redirect_uri 写 localhost 是刻意的</b>：上游注册值就是 localhost，
 * 统一成 127.0.0.1 会 {@code redirect_uri_mismatch}。浏览器访问 localhost 会解析到回环地址，
 * 本监听即可接住（与 Codex 版同一做法）。
 *
 * <p><b>端口占用是正常形态</b>：用户可能另开一个实例、或本机别的工具也占着 53692 ⇒
 * {@link BindException} 只记 warn 并返回 false（降级为前端的手动粘贴），⛔ 不抛异常、不影响启动。
 *
 * <p><b>幂等</b>：{@link #start()} 可被多次调用（每次点「登录」都会调），只真正启动一次。
 *
 * <p><b>空闲自停</b>：处理完回调不立刻关（同一进程可能连续登录多个 provider）；改为单线程
 * daemon 定时任务，最后一次请求后 10 分钟无请求才 {@code stop()}。
 *
 * <p><b>线程全是 daemon</b>：HTTP 工作线程与定时任务都不能阻止 JVM 退出。
 *
 * <p><b>不打印任何 token / code / verifier / state</b>：日志只记状态码与可读原因。
 */
@Component
public class ClaudeOAuthCallbackServer {

    private static final Logger log = LoggerFactory.getLogger(ClaudeOAuthCallbackServer.class);

    /** 授权回调监听端口（与 {@link ClaudeOAuthProviderConfig#REDIRECT_URI} 的端口一致）。 */
    public static final int PORT = ClaudeOAuthProviderConfig.CALLBACK_PORT;

    /** 回调路径（与 redirect_uri 一致）。 */
    static final String CALLBACK_PATH = ClaudeOAuthProviderConfig.CALLBACK_PATH;

    /** 空闲多久后自动停机。 */
    static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    /** 空闲检查周期。 */
    private static final long IDLE_CHECK_PERIOD_MS = TimeUnit.MINUTES.toMillis(1);

    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";

    private final ClaudeOAuthService claudeOAuthService;

    /** 当前运行的 HTTP 服务；null = 未启动。 */
    private HttpServer server;
    /** HTTP 工作线程池（daemon；停机时一并关闭）。 */
    private ExecutorService executor;
    /** 空闲自停定时器（单个 daemon 线程）。 */
    private ScheduledExecutorService idleTimer;
    /** 最后一次请求时间（handler 线程写、定时线程读）。 */
    private volatile long lastRequestAt;

    private final Object lock = new Object();

    public ClaudeOAuthCallbackServer(ClaudeOAuthService claudeOAuthService) {
        this.claudeOAuthService = claudeOAuthService;
    }

    /**
     * 启动监听（幂等）。
     *
     * @return true = 正在监听（含「已经启动过」）；false = 端口被占用或启动失败（调用方降级手动粘贴）
     */
    public boolean start() {
        synchronized (lock) {
            if (server != null) {
                return true;
            }
            HttpServer created;
            try {
                created = HttpServer.create(
                    new InetSocketAddress(ClaudeOAuthProviderConfig.CALLBACK_HOST, PORT), 0);
            } catch (BindException e) {
                log.warn("[ClaudeOAuth] 端口 {} 被占用，降级为手动粘贴模式: {}", PORT, e.getMessage());
                return false;
            } catch (IOException e) {
                log.warn("[ClaudeOAuth] 回调监听启动失败，降级为手动粘贴模式: {}", e.getMessage());
                return false;
            }
            created.createContext(CALLBACK_PATH, this::handle);
            executor = Executors.newCachedThreadPool(daemonFactory("claude-oauth-callback"));
            created.setExecutor(executor);
            created.start();
            server = created;
            lastRequestAt = System.currentTimeMillis();
            idleTimer = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("claude-oauth-idle"));
            idleTimer.scheduleAtFixedRate(this::stopIfIdle, IDLE_CHECK_PERIOD_MS,
                IDLE_CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
            log.info("[ClaudeOAuth] 回调监听已启动 http://127.0.0.1:{}{}", PORT, CALLBACK_PATH);
            return true;
        }
    }

    /** 主动停机（空闲自停 / 应用关闭走这里；已停则空操作）。 */
    @PreDestroy
    void stop() {
        synchronized (lock) {
            if (server == null) {
                return;
            }
            server.stop(0);
            server = null;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (idleTimer != null) {
                idleTimer.shutdownNow();
                idleTimer = null;
            }
            log.info("[ClaudeOAuth] 回调监听已停止（127.0.0.1:{}）", PORT);
        }
    }

    private void stopIfIdle() {
        if (System.currentTimeMillis() - lastRequestAt < IDLE_TIMEOUT_MS) {
            return;
        }
        log.info("[ClaudeOAuth] {} 分钟无回调请求，自动停止监听",
            TimeUnit.MILLISECONDS.toMinutes(IDLE_TIMEOUT_MS));
        stop();
    }

    // ───────────────────────────── 请求处理 ─────────────────────────────

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
                respond(exchange, 400, TEXT, "授权未完成：provider 返回 " + error);
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
                claudeOAuthService.onCallback(state, code);
            } catch (IllegalArgumentException e) {
                log.warn("[ClaudeOAuth] 回调登录失败: {}", e.getMessage());
                respond(exchange, 400, TEXT, readable(e.getMessage()));
                return;
            } catch (OAuthTokenExchangeError e) {
                // 诊断用：把 token 端点的拒绝原因（HTTP 状态 + 响应体）打出来。
                // 消息里只有错误响应，不含 code/verifier/token。
                log.warn("[ClaudeOAuth] 回调换 token 失败: error={} detail={}",
                    e.errorCode(), e.getMessage());
                String reason = e.errorCode() == null || e.errorCode().isBlank()
                    ? "token 端点拒绝了本次请求" : "provider 返回 " + e.errorCode();
                respond(exchange, 400, TEXT, "授权码兑换失败（" + reason + "），请重新点击登录");
                return;
            } catch (RuntimeException e) {
                // 非预期异常：不把内部细节写回浏览器，只留日志（消息里不含 token/code/verifier）
                log.error("[ClaudeOAuth] 回调处理异常", e);
                respond(exchange, 500, TEXT, "登录失败，请回到应用重试或改用手动粘贴");
                return;
            }
            log.info("[ClaudeOAuth] 回调登录完成");
            respond(exchange, 200, HTML, successHtml());
        } catch (IOException e) {
            log.warn("[ClaudeOAuth] 回调响应写出失败: {}", e.getMessage());
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
            + "<p style=\"color:#59636e\">已自动完成 Claude 订阅登录，回到应用即可。</p>"
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

    /** 解析 query 串（同名取第一个；值做百分号解码，同 {@code ClaudeOAuthService} 的口径）。 */
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

    /** 生产 daemon 线程的工厂（HTTP 工作线程池与空闲定时器共用）。 */
    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

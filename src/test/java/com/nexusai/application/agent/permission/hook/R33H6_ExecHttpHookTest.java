package com.nexusai.application.agent.permission.hook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H6] ExecHttpHook HTTP hook 执行器 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/execHttpHook.ts} (242 行).
 *
 * <p>WHY (规则九·测试验证意图): CC http hook 把 hook 输入 JSON POST 到配置 URL,
 * 带 5 重安全防御: URL allowlist / env var 白名单插值 / header CRLF 消毒 /
 * SSRF guard / 禁重定向. Java 端此前完全缺失 HTTP hook 执行器 (glob 0 命中).
 * 本测试覆盖 7 条核心路径 + 1 条成功路径, 每条验证 "为何该防御重要" (WHY),
 * 而非仅断言 "做了什么" (WHAT).
 *
 * <h2>测试用例 (8 项, 覆盖 H6 步骤 2 + 成功路径)</h2>
 * <ol>
 *   <li>{@link #execHttp_blocksUrlNotInAllowlist()} - CC :137-145 URL allowlist 拦截</li>
 *   <li>{@link #execHttp_emptyAllowlistBlocksAll()} - CC :138 三态语义 ([] 拦截全部)</li>
 *   <li>{@link #urlMatchesPattern_wildcardStar()} - CC :64-68 通配符 * 匹配 + 特殊字符转义</li>
 *   <li>{@link #sanitizesHeaderCrlfInjection()} - CC :76-79 去 CR/LF/NUL 防 header 注入</li>
 *   <li>{@link #interpolatesAllowedEnvVarsOnly()} - CC :89-108 白名单内插值, 非白名单置空</li>
 *   <li>{@link #noRedirectsFollowed()} - CC :206 maxRedirects:0 = Redirect.NEVER</li>
 *   <li>{@link #respectsTimeout()} + {@link #defaultTimeoutIs10Minutes()} - CC :12/147-149 超时</li>
 *   <li>{@link #integratesSsrfGuardedLookup()} - CC :216 ssrfGuardedLookup private IP 拦截</li>
 *   <li>{@link #successPathReturnsBodyAndStatus()} - CC :226-230 2xx 成功返回</li>
 * </ol>
 *
 * <p><b>测试基建</b>: redirect/timeout/success 用例用 JDK 内置
 * {@link HttpServer} 起本地 127.0.0.1 服务 (loopback 被 SsrfGuard 显式放行, 不触发 SSRF).
 * env 插值用例通过 {@code envResolver} 注入 mock (因 {@link System#getenv} 不可运行时设置).
 *
 * @since Session H6 (P2)
 */
@DisplayName("[H6] ExecHttpHook HTTP hook 执行器对齐 CC execHttpHook.ts")
class R33H6_ExecHttpHookTest {

    private SsrfGuard ssrfGuard;
    private HookEvent hookEvent;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        ssrfGuard = new SsrfGuard();
        hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 起本地 HTTP 服务 (127.0.0.1 随机端口), 挂载指定 handler.
     * WHY: loopback 地址被 SsrfGuard 显式放行 (CC ssrfGuard.ts:68 if(a===127) return false),
     * 故测试服务可正常通过 SSRF 校验, 聚焦验证 redirect/timeout/success 行为本身.
     */
    private String startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /**
     * 构造 ExecHttpHook, policy 由 allowedUrls/allowedEnvVars 决定 (null = undefined = 不限制, 对齐 CC 三态).
     *
     * <p>[IMP-HOOKS-S1 H1] 注入方式从 policySupplier lambda 改 merged setter ——
     * getHttpHookPolicy 读 merged 视图 (setMergedHttpHookPolicy), policySupplier
     * 不再承载 allowlist.
     */
    private ExecHttpHook buildHook(List<String> allowedUrls, List<String> allowedEnvVars) {
        HooksSettings settings = new HooksSettings();
        settings.setMergedHttpHookPolicy(allowedUrls, allowedEnvVars);
        return new ExecHttpHook(settings, ssrfGuard);
    }

    /** 构造最小 HttpHook (仅 url, 其余默认). */
    private static HttpHook httpHook(String url) {
        return new HttpHook(url, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. URL allowlist (CC :137-145)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC :137-145: policy.allowedUrls 非 undefined 时, URL 必须匹配某 pattern.
     * WHY: 无 allowlist 则任意 hook 配置可 POST 到任意外站, 等于开放 SSRF + 数据外泄通道.
     */
    @Test
    @DisplayName("1. URL 不在 allowlist 时被拦截 (CC :137-145)")
    void execHttp_blocksUrlNotInAllowlist() {
        ExecHttpHook hook = buildHook(List.of("https://allowed.example.com/*"), null);
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("https://evil.example.com/hook"),
            "test-hook", hookEvent, "{}", null);
        assertThat(result.ok()).isFalse();
        assertThat(result.body()).isEmpty();
        assertThat(result.error()).contains("HTTP hook blocked");
        assertThat(result.aborted()).isFalse();
    }

    /**
     * CC :138 三态语义: undefined=不限制 / []=全拦 / 非空=须匹配.
     * WHY: 空 list 与 undefined 行为必须区分 (空 list = 管理员显式禁止所有 http hook),
     * 否则配置 [] 会被误当 "不限制", 破坏企业管控意图.
     */
    @Test
    @DisplayName("2. 空 allowlist 拦截所有 URL (CC :138 三态语义)")
    void execHttp_emptyAllowlistBlocksAll() {
        ExecHttpHook hook = buildHook(List.of(), null);  // 空 = 全拦
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("https://anywhere.example.com/hook"),
            "test-hook", hookEvent, "{}", null);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("HTTP hook blocked");
    }

    /**
     * CC :64-68: pattern 中 * -> .*, 其余正则元字符转义, ^$ 锚定.
     * WHY: 若 * 不转义其余元字符, pattern "example.com" 的 "." 会匹配任意字符,
     * 导致 "exampleXcom" 误命中 allowlist, 绕过域名管控.
     */
    @Test
    @DisplayName("3. urlMatchesPattern 通配符 * + 特殊字符转义 (CC :64-68)")
    void urlMatchesPattern_wildcardStar() {
        // 通配符匹配
        assertThat(ExecHttpHook.urlMatchesPattern(
            "https://foo.example.com/path", "https://*.example.com/*")).isTrue();
        assertThat(ExecHttpHook.urlMatchesPattern(
            "https://foo.other.com/path", "https://*.example.com/*")).isFalse();
        // 精确匹配
        assertThat(ExecHttpHook.urlMatchesPattern(
            "https://example.com/hook", "https://example.com/hook")).isTrue();
        // "." 是字面量, 非正则任意字符
        assertThat(ExecHttpHook.urlMatchesPattern(
            "https://exampleXcom/hook", "https://example.com/hook")).isFalse();
    }

    /**
     * CC :76-79: header value 去 CR/LF/NUL, 防 HTTP header 注入.
     * WHY: env var 值或 hook 配置的 header 模板若含 \r\n, 可注入第二行 header
     * (如 "token\r\nX-Evil: 1"), 让攻击者伪造请求头.
     */
    @Test
    @DisplayName("4. sanitizeHeaderValue 去 CR/LF/NUL 防 header 注入 (CC :76-79)")
    void sanitizesHeaderCrlfInjection() {
        assertThat(ExecHttpHook.sanitizeHeaderValue("token\r\nX-Evil: 1")).isEqualTo("tokenX-Evil: 1");
        assertThat(ExecHttpHook.sanitizeHeaderValue("clean")).isEqualTo("clean");
        assertThat(ExecHttpHook.sanitizeHeaderValue("a\rb\nc\0d")).isEqualTo("abcd");
        assertThat(ExecHttpHook.sanitizeHeaderValue(null)).isNull();
    }

    /**
     * CC :89-108: $VAR / ${VAR} 仅替换白名单内变量, 非白名单置空; 末尾再 sanitize.
     * WHY: 若任意 env var 都可插值, hook 配置方可通过 header 值外泄 secrets
     * (如 "X-Leak: $AWS_SECRET_ACCESS_KEY"). 白名单 + 末尾消毒双重防御.
     */
    @Test
    @DisplayName("5. interpolateEnvVars 仅替换白名单内 env var, 非白名单置空 (CC :89-108)")
    void interpolatesAllowedEnvVarsOnly() {
        Function<String, String> env = k -> "MY_TOKEN".equals(k) ? "secret" : null;
        Set<String> allowed = Set.of("MY_TOKEN");
        // 白名单内 -> 解析; 非白名单 -> 空
        assertThat(ExecHttpHook.interpolateEnvVars("Bearer $MY_TOKEN $OTHER", allowed, env))
            .isEqualTo("Bearer secret ");
        // ${VAR} 大括号形式
        assertThat(ExecHttpHook.interpolateEnvVars("Bearer ${MY_TOKEN}", allowed, env))
            .isEqualTo("Bearer secret");
        // 非白名单 -> 空 (不外泄)
        assertThat(ExecHttpHook.interpolateEnvVars("$SECRET", allowed, env)).isEqualTo("");
        // 白名单内但 env 未设 -> 空
        assertThat(ExecHttpHook.interpolateEnvVars("$UNSET", Set.of("UNSET"), env)).isEqualTo("");
        // 解析出的值含 CRLF -> 末尾消毒
        Function<String, String> evilEnv = k -> "evil\r\nX-Inject: 1";
        assertThat(ExecHttpHook.interpolateEnvVars("$MY_TOKEN", allowed, evilEnv))
            .isEqualTo("evilX-Inject: 1");
    }

    /**
     * CC :206 maxRedirects:0: 关闭自动重定向, 防攻击者通过 302 到内部 IP 绕过 SSRF Guard.
     * WHY: 若跟随重定向, SSRF 校验只查首跳 host, 重定向到 169.254.169.254 即可绕过.
     * = Java HttpClient.Redirect.NEVER.
     */
    @Test
    @DisplayName("6. 不跟随重定向 (CC :206 maxRedirects:0 = Redirect.NEVER)")
    void noRedirectsFollowed() throws IOException {
        String url = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        ExecHttpHook hook = buildHook(null, null);  // 无 allowlist = 不限制
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook(url), "test-hook", hookEvent, "{}", null);
        // 不跟随: 直接返回 302, ok=false (非 2xx)
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.ok()).isFalse();
        assertThat(result.aborted()).isFalse();
    }

    /**
     * CC :147-149 + :234: hook.timeout (秒) 转 ms, 默认 10 分钟; 超时 -> aborted=true.
     * WHY: 无超时则恶意/慢服务端可挂起 hook 线程耗尽资源. 超时必须可中断并标记 aborted.
     */
    @Test
    @DisplayName("7. 超时返回 aborted (CC :147-149 timeout, :234 aborted)")
    void respectsTimeout() throws IOException {
        String url = startServer(exchange -> {
            try {
                Thread.sleep(3000);  // 服务端故意慢 3s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        ExecHttpHook hook = buildHook(null, null);
        // hook.timeout=1 (秒) -> 1000ms 超时, 服务端 3s -> 必超时
        HttpHook httpHook = new HttpHook(url, null, 1, null, null, null, null);
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook, "test-hook", hookEvent, "{}", null);
        assertThat(result.aborted()).isTrue();
        assertThat(result.ok()).isFalse();
    }

    /**
     * CC :12 DEFAULT_HTTP_HOOK_TIMEOUT_MS = 10*60*1000; hook.timeout 秒->毫秒换算.
     * WHY: 10 分钟对齐 TOOL_HOOK_EXECUTION_TIMEOUT_MS, 与 CC 默认值一致; 换算错误会导致
     * 超时配置失效 (如把秒当毫秒 -> 1s 变 1ms 误超时).
     */
    @Test
    @DisplayName("8. 默认超时 10 分钟 + 秒->毫秒换算 (CC :12, :147-149)")
    void defaultTimeoutIs10Minutes() {
        assertThat(ExecHttpHook.resolveTimeoutMs(null)).isEqualTo(600_000L);
        assertThat(ExecHttpHook.resolveTimeoutMs(5)).isEqualTo(5_000L);
        // 0 视为默认 (CC truthy 语义: 0 falsy -> 默认)
        assertThat(ExecHttpHook.resolveTimeoutMs(0)).isEqualTo(600_000L);
    }

    /**
     * CC :216 lookup:ssrfGuardedLookup: 自定义 DNS 解析, 解析到 private/link-local IP 即拒.
     * WHY: 无 SSRF guard 则 hook 配置方可 POST 到 169.254.169.254 (云 metadata) 或 10.x 内网,
     * 窃取云凭证/探测内网. 10.0.0.1 是 IP 字面量, ssrfGuardedLookup 直接校验, 无需 DNS.
     */
    @Test
    @DisplayName("9. SSRF guard 集成: private IP 被拦截 (CC :216 ssrfGuardedLookup)")
    void integratesSsrfGuardedLookup() {
        ExecHttpHook hook = buildHook(null, null);
        // 10.0.0.1 是 private (10.0.0.0/8) -> ssrfGuardedLookup 抛 SecurityException
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("http://10.0.0.1/hook"),
            "test-hook", hookEvent, "{}", null);
        assertThat(result.ok()).isFalse();
        // [CCJ-EXEC-07] 错误消息来自 SsrfGuard SsrfBlockedException（CC ssrfGuard.ts:285-294 固定文本）
        assertThat(result.error()).contains("HTTP hook blocked: 10.0.0.1 resolves to 10.0.0.1");
    }

    /**
     * CC :226-230: 2xx 响应 -> ok=true + statusCode + body; Content-Type: application/json 自动注入 (CC :159).
     * WHY: 成功路径必须正确解析 status/body, 且请求体为 JSON (hook 输入契约).
     */
    @Test
    @DisplayName("10. 成功路径: 2xx 返回 ok + body + Content-Type:application/json (CC :159, :226-230)")
    void successPathReturnsBodyAndStatus() throws IOException {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        String url = startServer(new HeaderCapturingHandler(capturedHeaders, "ok-response"));
        ExecHttpHook hook = buildHook(null, null);
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook(url), "test-hook", hookEvent,
            "{\"event\":\"test\"}", null);
        assertThat(result.ok()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("ok-response");
        // 验证 Content-Type 自动注入 (CC :159)
        Map<String, String> recv = capturedHeaders.get();
        assertThat(recv).isNotNull();
        assertThat(recv.getOrDefault("content-type", "")).isEqualTo("application/json");
    }

    /**
     * 捕获请求头并返回 200 + 指定 body 的 handler.
     */
    private static class HeaderCapturingHandler implements HttpHandler {
        private final AtomicReference<Map<String, String>> sink;
        private final String body;

        HeaderCapturingHandler(AtomicReference<Map<String, String>> sink, String body) {
            this.sink = sink;
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // key 统一小写, 规避 HttpServer 存储 header key 大小写不确定的问题
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) ->
                headers.put(k.toLowerCase(java.util.Locale.ROOT),
                    v != null && !v.isEmpty() ? v.get(0) : ""));
            sink.set(headers);
            byte[] resp = body.getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        }
    }
}

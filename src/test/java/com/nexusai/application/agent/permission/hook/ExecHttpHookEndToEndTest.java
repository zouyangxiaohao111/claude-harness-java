package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H7] ExecHttpHook 端到端接线 + ssrf TOCTOU 修复 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks/execHttpHook.ts:123-242} (执行器) +
 * {@code Open-ClaudeCode/src/utils/hooks.ts:2296-2444} (HTTP 分支调用方解释层).
 *
 * <p>WHY (规则九 · 测试验证意图): H7 前 HttpHook dispatch 已接到 ExecHttpHook, 但存在三个缺口:
 * <ol>
 *   <li><b>TOCTOU 缺口</b>: exec 调 {@code ssrfGuardedLookup} 校验后仍用原始 hostname URL 建连
 *       (validate-only), 存在 DNS rebinding 窗口 — 攻击者先解析公网 IP 过校验, 连接时重绑定到内网.
 *       修复: HTTP scheme 把连接 URI 重写为校验过的 IP 字面量 (决策 H7-3).</li>
 *   <li><b>JSON 解释层缺失</b>: {@code httpToHookResult} 只按 ok/error/aborted 映射 outcome,
 *       不解析 body JSON — HTTP hook 返回的 {@code {"continue":false}} / {@code {"decision":"block"}}
 *       会被静默吞掉 (CC hooks.ts:2363-2440 会解析). 修复: 补 parseHttpHookOutput →
 *       processHookJSONOutput 链路.</li>
 *   <li>[IMPL-10] DEL-EX-01: hookId 结果字段已删除（CC hooks.ts:2199 randomUUID 属分发层
 *       事件标识，Java 事件层由 HookEventBus 承担）.</li>
 * </ol>
 *
 * <p><b>测试基建</b>:
 * <ul>
 *   <li>直连成功/超时/JSON 解释用例用 JDK {@link HttpServer} (loopback 被 SsrfGuard 放行)</li>
 *   <li>TOCTOU 用例用 {@link StubSsrfGuard} 固定返回 127.0.0.1 (模拟"已校验"IP),
 *       配合不可解析 hostname {@code rebinding.example.test}, 断言连接用校验 IP</li>
 *   <li>代理用例用 {@link FakeProxy} (原始 ServerSocket 假代理), 验证"路由到代理 + 跳过 ssrf lookup"</li>
 * </ul>
 *
 * @since Session H7 (P1)
 */
@DisplayName("[H7] ExecHttpHook 端到端接线 + ssrf TOCTOU 修复对齐 CC")
class ExecHttpHookEndToEndTest {

    private static final String SESSION_ID = "sess-1";
    private static final String AGENT_ID = "agent-1";

    private HookEvent hookEvent;
    private HttpServer server;
    private FakeProxy proxy;

    @BeforeEach
    void setUp() {
        hookEvent = HookEvent.userPromptSubmit(SESSION_ID, AGENT_ID, "do something");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 测试基建
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 起本地 HTTP 服务 (127.0.0.1 随机端口), 挂载指定 handler, 返回 URL.
     * WHY: loopback 被 SsrfGuard 显式放行 (CC ssrfGuard.ts:68 if(a===127) return false),
     * 测试服务可正常通过校验.
     */
    private String startServer(HttpServerHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                throw e;
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @FunctionalInterface
    private interface HttpServerHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** 统一响应 helper: 空 body → sendResponseHeaders(status, -1) (无 body), 否则写 JSON. */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null || body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        }
        exchange.close();
    }

    /**
     * 捕获请求头 (key 小写) 并返回 200 + body 的 handler.
     * WHY: TOCTOU 测试用 Host 头证明连接用的是校验过的 IP 字面量, 而非原始 hostname.
     */
    private static void captureHeaders(HttpExchange exchange, AtomicReference<Map<String, String>> sink,
                                       String body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) ->
            headers.put(k.toLowerCase(Locale.ROOT), v != null && !v.isEmpty() ? v.get(0) : ""));
        sink.set(headers);
        respond(exchange, 200, body);
    }

    /**
     * Stub SsrfGuard · 覆写 ssrfGuardedLookup 固定返回预设 IP, 记录调用次数.
     * WHY: TOCTOU 测试需要"校验已通过"的确定 IP (不必真实 DNS), 同时计数证明
     * ssrfGuardedLookup 只调一次 (无二次解析 = 无 rebinding 窗口).
     */
    static class StubSsrfGuard extends SsrfGuard {
        final AtomicInteger lookupCalls = new AtomicInteger();
        final InetAddress result;

        StubSsrfGuard(InetAddress result) {
            this.result = result;
        }

        @Override
        public InetAddress ssrfGuardedLookup(String hostname) {
            lookupCalls.incrementAndGet();
            return result;
        }
    }

    /**
     * 内存假代理 · 原始 ServerSocket 模拟 HTTP 代理, 捕获请求文本并返回 200 JSON.
     * WHY: 真实代理 (如企业 HTTP_PROXY) 自己解析目标 DNS. 测试用假代理验证两个行为:
     * (1) 请求确实路由到代理 (而非直连目标); (2) ssrf lookup 被跳过 (proxy 路径 lookup=undefined).
     */
    static class FakeProxy implements AutoCloseable {
        private final ServerSocket socket;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> lastRequest = new AtomicReference<>();
        private volatile boolean closed;

        FakeProxy() throws IOException {
            socket = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        }

        int port() {
            return socket.getLocalPort();
        }

        void start() {
            Thread t = new Thread(this::acceptLoop, "fake-proxy");
            t.setDaemon(true);
            t.start();
        }

        private void acceptLoop() {
            while (!closed) {
                try (Socket s = socket.accept()) {
                    requests.incrementAndGet();
                    s.setSoTimeout(10_000);
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[2048];
                    // 读到 header 结束符 (\r\n\r\n) 即满足断言 (请求行含 absolute-form 目标 URL)
                    while (headerBuf.size() < 8192) {
                        int n = in.read(chunk);
                        if (n < 0) {
                            break;
                        }
                        headerBuf.write(chunk, 0, n);
                        String text = headerBuf.toString(StandardCharsets.ISO_8859_1);
                        if (text.contains("\r\n\r\n")) {
                            break;
                        }
                    }
                    lastRequest.set(headerBuf.toString(StandardCharsets.ISO_8859_1));
                    // 略读余下 body 字节防 RST (POST body 很小, 一次已到)
                    try {
                        int avail = in.available();
                        if (avail > 0) {
                            in.skip(avail);
                        }
                    } catch (IOException ignored) {
                        // ignore
                    }
                    String body = "{\"continue\":true}";
                    byte[] respBody = body.getBytes(StandardCharsets.UTF_8);
                    String resp = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + respBody.length + "\r\n"
                        + "Connection: close\r\n\r\n"
                        + body;
                    OutputStream out = s.getOutputStream();
                    out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (Exception e) {
                    // 单次连接异常 (如并行构建负载下 2s 读超时) 不得杀死 accept 循环 —
                    // 旧实现 break 后后续连接无人应答 → "header parser received no bytes" flaky.
                    // close() 后 socket.accept() 抛 SocketException → closed=true 时正常退出.
                    if (closed) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            socket.close();
        }
    }

    private static HttpHook httpHook(String url) {
        return new HttpHook(url, null, null, null, null, null, null);
    }

    private static ExecHttpHook buildHook(HooksSettings settings, SsrfGuard guard) {
        return new ExecHttpHook(settings, guard);
    }

    /** 经 HookRegistry 全链路执行单个 HTTP hook (dispatch → exec → httpToHookResult). */
    private static GenericHook.HookResult runHttpHookThroughRegistry(String url) {
        HookRegistryDispatchTest.StubMatcherEngine engine = new HookRegistryDispatchTest.StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(httpHook(url), null, null, null, "settings")));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setExecHttpHook(buildHook(new HooksSettings(key -> null), new SsrfGuard()));
        return registry.executeEvent(HookEvent.userPromptSubmit(SESSION_ID, AGENT_ID));
    }

    /**
     * 直接测 httpToHookResult (JSON 解释层) · 用构造的 ok HttpHookResult + HttpHook.
     * WHY (规则九): executeEvent 聚合层只透传 stop/blockingError/retry 语义 (对齐 CC
     * hasBlockingResult), permissionBehavior / NON_BLOCKING_ERROR 是 per-hook 语义
     * (CC hooks.ts:2872 "Check result.permissionBehavior, not the aggregated"),
     * 必须在解释层直接断言 — 这正是本 session 补的 JSON 解释层, 隔离聚合层单独验证.
     */
    private static GenericHook.HookResult interpretBody(String body) {
        ExecHttpHook.HttpHookResult r =
            new ExecHttpHook.HttpHookResult(true, 200, body, null, false);
        return HookRegistry.httpToHookResult(r, httpHook("http://127.0.0.1:1/hook"),
            "config-http:test", null);
    }

    /**
     * 直接测 httpToHookResult + expectedHookEvent 校验 (H7-v2 H7-GAP-3) ·
     * 带期望事件名 (CC PascalCase), 验证 hookSpecificOutput.hookEventName 不匹配 → fail-loud.
     */
    private static GenericHook.HookResult interpretBodyWithExpected(String body, String expectedHookEvent) {
        ExecHttpHook.HttpHookResult r =
            new ExecHttpHook.HttpHookResult(true, 200, body, null, false);
        return HookRegistry.httpToHookResult(r, httpHook("http://127.0.0.1:1/hook"),
            "config-http:test", expectedHookEvent, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. TOCTOU 修复 (正向 + 安全): 校验过的 InetAddress 用于建连
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): DNS rebinding 攻击 = 解析与连接间的 TOCTOU — 首次解析返回公网 IP 过校验,
     * 攻击者随后把域名重绑定到内网 IP (如 169.254.169.254 云 metadata), 连接时被直连内网.
     * CC 用 axios {@code lookup:ssrfGuardedLookup} 把校验过的 IP 直接交给 socket (ssrfGuard.ts:207-215),
     * 校验与连接共用同一地址, 无重解析窗口. Java 端必须在 HTTP scheme 下把连接 URI 重写为
     * 校验过的 IP 字面量, 否则 "validate-only" 仍留窗口 (concern H6-1).
     * 断言: (1) 本地 HttpServer (127.0.0.1) 被命中 — 连接用了校验 IP;
     *       (2) Host 头是 127.0.0.1 而非原始 hostname — URI 确实被重写;
     *       (3) ssrfGuardedLookup 只调 1 次 — 无二次 DNS 解析.
     */
    @Test
    @DisplayName("1. HTTP scheme: 用 ssrfGuardedLookup 校验过的 IP 字面量建连, 无 DNS rebinding 窗口")
    void execHttpHook_usesGuardedInetAddress_noDnsRebinding() throws Exception {
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        String serverUrl = startServer(exchange ->
            captureHeaders(exchange, capturedHeaders, "{\"continue\":true}"));
        // Stub 固定返回"已校验"的 127.0.0.1; hostname 是不可解析域名 —
        // 若代码仍用原始 hostname 建连必然 DNS 失败, 服务器不会被命中.
        StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("127.0.0.1"));
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);
        int port = URI.create(serverUrl).getPort();
        String targetUrl = "http://rebinding.example.test:" + port + "/hook";

        ExecHttpHook.HttpHookResult result = hook.exec(httpHook(targetUrl), "test-hook", hookEvent, "{}", null);

        assertThat(result.ok()).isTrue();
        assertThat(guard.lookupCalls.get()).isEqualTo(1); // 只用首次校验 IP, 无重解析
        assertThat(capturedHeaders.get()).isNotNull();
        assertThat(capturedHeaders.get().getOrDefault("host", ""))
            .as("连接 URI 应重写为校验过的 IP 字面量 (Host 头=127.0.0.1)")
            .contains("127.0.0.1");
        assertThat(capturedHeaders.get().getOrDefault("host", ""))
            .as("不得再用原始 hostname (rebinding.example.test) 建连")
            .doesNotContain("rebinding.example.test");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMPL-10] DEL-EX-01: hookId 测试已删除 — hookId 结果字段随删除
    //   （CC hooks.ts:2199 randomUUID 属分发层事件标识，Java 事件层由 HookEventBus 承担）。
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // 3-7. HTTP 分支调用方 JSON 解释层 (HookRegistry httpToHookResult)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC hooks.ts:2363-2440 — HTTP hook 响应 body 必须 JSON, 经 parseHttpHookOutput →
     * processHookJSONOutput. continue:false → preventContinuation=true + stopReason
     * (hooks.ts:518-523). H7 前 httpToHookResult 只按 ok/error 映射 outcome, continue:false
     * 被静默当 SUCCESS 吞掉, hook 无法阻断主流程 — 这是本 session 要补的解释层.
     */
    @Test
    @DisplayName("3. HTTP body continue:false → preventContinuation + stopReason 透传 (CC hooks.ts:518-523)")
    void httpResponseJson_continueFalse_blocksContinuation() throws IOException {
        String url = startServer(exchange ->
            respond(exchange, 200, "{\"continue\":false,\"stopReason\":\"stop-hook\"}"));

        GenericHook.HookResult result = runHttpHookThroughRegistry(url);

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS); // 阻断语义不改变 outcome
        assertThat(result.preventContinuation()).isTrue();
        assertThat(result.stopReason()).isEqualTo("stop-hook");
        assertThat(result.hook()).isInstanceOf(HttpHook.class); // hook 字段填 HttpHook
    }

    /**
     * WHY: CC hooks.ts:525-543 decision:'block' → permissionBehavior=deny + blockingError.
     * H7 前解释层缺失, decision:block 会被当 SUCCESS 放行, 权限拦截彻底失效 (安全语义丢失).
     */
    @Test
    @DisplayName("4. HTTP body decision:block → permissionBehavior=DENY + blockingError (CC hooks.ts:525-543)")
    void httpResponseJson_decisionBlock_denies() {
        GenericHook.HookResult result = interpretBody("{\"decision\":\"block\",\"reason\":\"blocked-by-hook\"}");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS); // 阻断语义不改变 outcome
        assertThat(result.permissionBehavior()).isEqualTo(com.nexusai.application.agent.hook.PermissionBehavior.DENY);
        assertThat(result.blockingError()).isNotNull();
        assertThat(result.blockingError().blockingError()).contains("blocked-by-hook");
        assertThat(result.hook()).isInstanceOf(HttpHook.class); // hook 字段填 HttpHook
    }

    /**
     * WHY: CC hooks.ts:2394-2411 — async 响应 ({"async":true}) → outcome success, 不再继续处理
     * (后台化). 若 Java 端把 async 误当 sync 解析 (无 decision/continue), 应仍 SUCCESS 且不阻断.
     */
    @Test
    @DisplayName("5. HTTP body async:true → SUCCESS, 不再阻断 (CC hooks.ts:2394-2411)")
    void httpResponseJson_async_isSuccess() {
        GenericHook.HookResult result = interpretBody("{\"async\":true}");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.preventContinuation()).isFalse();
    }

    /**
     * WHY: CC hooks.ts:2364-2391 — parseHttpHookOutput 对非 '{' 开头 body 产 validationError →
     * non_blocking_error. HTTP hook 契约是"必须返回 JSON" (hooks.ts:453-473), 非法 body 不能
     * 当成功放行 (否则静默吞掉 hook 的错误返回).
     */
    @Test
    @DisplayName("6. HTTP body 非 JSON → NON_BLOCKING_ERROR (CC hooks.ts:2364-2391)")
    void httpResponse_nonJsonBody_isNonBlockingError() {
        GenericHook.HookResult result = interpretBody("this is not json");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(result.preventContinuation()).isFalse();
    }

    /**
     * WHY: CC parseHttpHookOutput (hooks.ts:459-467) — 空 body → 空 JSON 对象 {} → 视为成功
     * (空对象无 continue/decision, 不阻断). 空响应是合法 hook 返回 (如 fire-and-forget webhook).
     */
    @Test
    @DisplayName("7. HTTP body 为空 → 视为空 JSON 对象 → SUCCESS (CC hooks.ts:459-467)")
    void httpResponse_emptyBody_isSuccess() {
        GenericHook.HookResult result = interpretBody("");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.preventContinuation()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 8-9. URL allowlist 三态 (正向 + 反向)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC execHttpHook.ts:137-145 三态语义 — undefined=不限制 / []=全拦 / 非空=须匹配.
     * 三态必须区分: 若 [] 被误当"不限制", 管理员显式禁所有 http hook 的配置会失效 (管控绕过).
     */
    @Test
    @DisplayName("8. allowedUrls 三态: undefined=不限制 / []=全拦 / 非空=须匹配 (CC :137-145)")
    void allowedUrls_triState() throws IOException {
        String serverUrl = startServer(exchange -> respond(exchange, 200, "{\"continue\":true}"));

        // undefined → 不限制 → 本地 server 命中
        // [IMP-HOOKS-S1 H1] 注入方式改 merged setter (getHttpHookPolicy 读 merged 视图,
        // policySupplier lambda 已不生效; 未注入 = null = 不限制)
        ExecHttpHook unrestricted = buildHook(new HooksSettings(), new SsrfGuard());
        assertThat(unrestricted.exec(httpHook(serverUrl), "t", hookEvent, "{}", null).ok()).isTrue();

        // [] → 全拦
        HooksSettings emptySettings = new HooksSettings();
        emptySettings.setMergedHttpHookPolicy(List.of(), null);
        ExecHttpHook empty = buildHook(emptySettings, new SsrfGuard());
        assertThat(empty.exec(httpHook(serverUrl), "t", hookEvent, "{}", null).ok()).isFalse();

        // 非空 不匹配 → 拦截
        HooksSettings nonMatchSettings = new HooksSettings();
        nonMatchSettings.setMergedHttpHookPolicy(List.of("https://allowed.example.com/*"), null);
        ExecHttpHook nonMatch = buildHook(nonMatchSettings, new SsrfGuard());
        assertThat(nonMatch.exec(httpHook(serverUrl), "t", hookEvent, "{}", null).ok()).isFalse();

        // 非空 匹配 → 放行
        int port = URI.create(serverUrl).getPort();
        HooksSettings matchSettings = new HooksSettings();
        matchSettings.setMergedHttpHookPolicy(List.of("http://127.0.0.1:" + port + "/*"), null);
        ExecHttpHook match = buildHook(matchSettings, new SsrfGuard());
        assertThat(match.exec(httpHook(serverUrl), "t", hookEvent, "{}", null).ok()).isTrue();
    }

    /**
     * WHY: URL 不匹配 allowlist 必须返回 {ok:false, body:'', error} (CC execHttpHook.ts:141-144),
     * 请求不得发出. body 为空 + error 非空 = 调用方可感知拦截原因, 且不产生任何外发流量.
     */
    @Test
    @DisplayName("9. URL 不匹配 allowlist → {ok:false, body:'', error} (CC :141-144)")
    void urlNotInAllowlist_returnsOkFalse() {
        HooksSettings settings = new HooksSettings();
        settings.setMergedHttpHookPolicy(List.of("https://allowed.example.com/*"), null);
        ExecHttpHook hook = buildHook(settings, new SsrfGuard());

        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("https://evil.example.com/hook"),
            "test-hook", hookEvent, "{}", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.body()).isEmpty();
        assertThat(result.error()).contains("HTTP hook blocked");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 10-12. 代理路径 (env 代理 / sandbox 代理) → 跳过 ssrf lookup
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC execHttpHook.ts:184-187 envProxyActive + :216 lookup=undefined — 设 HTTP_PROXY 时
     * 代理自己解析目标 DNS, 对代理本身 IP 做 SSRF 校验会误伤企业代理 (如 10.0.0.1:3128).
     * Java 端用 ProxySelector 把请求路由到代理, 并跳过 ssrf lookup.
     * 断言: 假代理收到请求 (路由成功) + ssrfGuardedLookup 0 次 (跳过).
     */
    @Test
    @DisplayName("10. HTTP_PROXY 路径: 请求路由到代理 + 跳过 ssrf lookup (CC :184-187, :216)")
    void envProxy_skipsSsrf_routesToProxy() throws IOException {
        proxy = new FakeProxy();
        proxy.start();
        StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("1.2.3.4"));
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);
        hook.setEnvResolver(k -> "http_proxy".equals(k)
            ? "http://127.0.0.1:" + proxy.port() : null);

        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("http://target.example.com/hook"),
            "test-hook", hookEvent, "{}", null);

        assertThat(result.ok()).isTrue();
        assertThat(proxy.requests.get()).as("请求必须路由到代理").isEqualTo(1);
        assertThat(proxy.lastRequest.get()).contains("target.example.com"); // 代理解析目标域名
        assertThat(guard.lookupCalls.get()).as("代理路径必须跳过 ssrf lookup (代理自己解析 DNS)")
            .isZero();
    }

    /**
     * WHY: CC execHttpHook.ts:186-187 — NO_PROXY 命中时 envProxyActive=false, 走 ssrfGuardedLookup
     * (shouldBypassProxy, proxy.ts:88-129). 若 NO_PROXY 被忽略, 本应直连的地址被错误丢给代理
     * (隐私: NO_PROXY=localhost 场景), 或本应过 SSRF 校验的直连被跳过.
     * 断言: ssrfGuardedLookup 被调 (未跳过) + 代理未收到请求.
     */
    @Test
    @DisplayName("11. NO_PROXY 命中 → 不走代理, ssrf lookup 恢复 (CC :186-187, proxy.ts:88-129)")
    void envProxy_noProxyBypass_restoresSsrf() throws IOException {
        proxy = new FakeProxy();
        proxy.start();
        StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("1.2.3.4"));
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);
        hook.setEnvResolver(k -> switch (k) {
            case "http_proxy" -> "http://127.0.0.1:" + proxy.port();
            case "no_proxy" -> "target.example.com"; // 精确 hostname 命中 → bypass
            default -> null;
        });

        // 直连 target.example.com 不可解析 → error; 但我们只断言 ssrf 恢复 + 代理未命中
        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("http://target.example.com/hook"),
            "test-hook", hookEvent, "{}", null);

        assertThat(guard.lookupCalls.get()).as("NO_PROXY 命中必须恢复 ssrf lookup").isEqualTo(1);
        assertThat(proxy.requests.get()).as("NO_PROXY 命中不得路由到代理").isZero();
        assertThat(result.ok()).isFalse(); // 直连不可解析 → error (证明走的直连路径)
    }

    /**
     * WHY: [CCJ-EXEC-02] CC proxy.ts:64-66 getProxyUrl =
     *   {@code https_proxy || HTTPS_PROXY || http_proxy || HTTP_PROXY} 单值优先级链，
     *   <b>不区分 scheme</b>。旧 Java 按 scheme 分读（http 目标只看 http_proxy）→
     *   仅设 https_proxy + http 目标时 CC 走代理（跳过 SSRF）而 Java 直连（走 SSRF lookup）。
     *   本测试用 http 目标 + 仅 https_proxy 验证该差异已消除（反向方向同样成立；
     *   正向 https 目标经代理走 CONNECT+TLS，假代理无法承载 TLS，故用 http 目标验证单值链）。
     */
    @Test
    @DisplayName("12. [CCJ-EXEC-02] 单值代理链：仅设 https_proxy + http 目标 → 仍走代理 + 跳过 ssrf")
    void envProxy_singleValueChain_httpsProxyAppliesToHttpTarget() throws IOException {
        proxy = new FakeProxy();
        proxy.start();
        StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("1.2.3.4"));
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);
        // 仅 https_proxy（无 http_proxy）· CC proxy.ts:64-66 单值链命中
        hook.setEnvResolver(k -> "https_proxy".equals(k)
            ? "http://127.0.0.1:" + proxy.port() : null);

        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("http://target.example.com/hook"),
            "test-hook", hookEvent, "{}", null);

        assertThat(result.ok()).isTrue();
        assertThat(proxy.requests.get()).as("https_proxy 单值链必须路由 http 目标到代理").isEqualTo(1);
        assertThat(guard.lookupCalls.get()).as("代理路径必须跳过 ssrf lookup").isZero();
    }

    /**
     * WHY: CC execHttpHook.ts:176-199 sandboxProxy > envProxy > 直连三分支; sandbox 代理强制
     * 域名 allowlist, 对代理 IP 做 SSRF 校验会误伤. Java 无沙箱, sandbox 代理做成可注入字段
     * (默认 null), 测试注入 127.0.0.1:port 验证分支.
     * 断言: 假代理收到请求 + ssrfGuardedLookup 0 次.
     */
    @Test
    @DisplayName("12. sandbox 代理路径: 请求路由到代理 + 跳过 ssrf lookup (CC :176-199)")
    void sandboxProxy_skipsSsrf_routesToProxy() throws IOException {
        proxy = new FakeProxy();
        proxy.start();
        StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("1.2.3.4"));
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);
        hook.setSandboxProxy(new InetSocketAddress("127.0.0.1", proxy.port()));

        ExecHttpHook.HttpHookResult result = hook.exec(httpHook("http://target.example.com/hook"),
            "test-hook", hookEvent, "{}", null);

        assertThat(result.ok()).isTrue();
        assertThat(proxy.requests.get()).as("sandbox 代理路径必须路由到代理").isEqualTo(1);
        assertThat(guard.lookupCalls.get()).as("sandbox 代理路径必须跳过 ssrf lookup").isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 13. 边界: 超时 → aborted
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC execHttpHook.ts:234 — combinedSignal.aborted → {ok:false, body:'', aborted:true}
     * (hooks.ts:2310-2332 → outcome=cancelled). 无超时则恶意/慢服务端挂起 hook 线程耗尽资源;
     * 超时标记 aborted=true 让调用方明确知道是"取消"而非"错误".
     */
    @Test
    @DisplayName("13. 超时 → aborted=true {ok:false, body:'', aborted:true} (CC :234)")
    void timeout_returnsAborted() throws IOException {
        String url = startServer(exchange -> {
            try {
                Thread.sleep(3000); // 服务端故意慢 3s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), new SsrfGuard());
        HttpHook httpHook = new HttpHook(url, null, 1, null, null, null, null); // timeout=1s

        ExecHttpHook.HttpHookResult result = hook.exec(httpHook, "test-hook", hookEvent, "{}", null);

        assertThat(result.aborted()).isTrue();
        assertThat(result.ok()).isFalse();
        assertThat(result.body()).isEmpty();
        assertThat(result.error()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 14-16. [H7-v2] H7-GAP-4 修复: 取消/失败路径 message attachment
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): CC hooks.ts:2322-2332 — aborted → yield {message: hook_cancelled attachment}.
     * Java 端 H7 前 message 恒 null (H7-GAP-4), HTTP hook 取消的系统提醒不会注入 LLM. 修复后
     * aborted 路径必须产 {@link AttachmentMessageDto}(type='hook_cancelled') — 否则取消提醒静默丢失.
     * RED→GREEN: 修复前 message 为 null (断言失败), 修复后为 hook_cancelled attachment.
     */
    @Test
    @DisplayName("14. [H7-v2] HTTP aborted → message=hook_cancelled attachment (CC :2322-2332)")
    void httpAborted_producesHookCancelledMessage() {
        ExecHttpHook.HttpHookResult r =
            new ExecHttpHook.HttpHookResult(false, null, "", null, true);
        GenericHook.HookResult result = HookRegistry.httpToHookResult(
            r, httpHook("http://127.0.0.1:1/hook"), "config-http:test", "UserPromptSubmit", null);

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_cancelled");
        assertThat(att.hookName()).isEqualTo("config-http:test");
        assertThat(att.hookEvent()).isEqualTo("UserPromptSubmit");
    }

    /**
     * WHY (规则九): CC hooks.ts:2334-2360 — error||!ok → yield {message: hook_non_blocking_error
     * attachment}, stderr = error 或 `HTTP ${status} from ${url}` (CC :2336). 修复前 message 恒 null
     * (H7-GAP-4), HTTP hook 失败原因不会注入 LLM. 断言: message 是 hook_non_blocking_error attachment,
     * content 承载 stderr (error 文本), toolUseID 透传.
     */
    @Test
    @DisplayName("15. [H7-v2] HTTP error → message=hook_non_blocking_error attachment (CC :2334-2360)")
    void httpError_producesHookNonBlockingErrorMessage() {
        ExecHttpHook.HttpHookResult r =
            new ExecHttpHook.HttpHookResult(false, 503, "", "upstream down", false);
        GenericHook.HookResult result = HookRegistry.httpToHookResult(
            r, httpHook("http://127.0.0.1:1/hook"), "config-http:test", "UserPromptSubmit", "tool-1");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        assertThat(att.content()).contains("upstream down"); // stderr=error (CC :2336)
        assertThat(att.hookEvent()).isEqualTo("UserPromptSubmit");
        assertThat(att.toolUseID()).isEqualTo("tool-1");
    }

    /**
     * WHY (规则九): CC hooks.ts:2367-2391 — body 校验失败 → hook_non_blocking_error attachment,
     * stderr=`JSON validation failed: ${error}` (CC :2382), stdout=body (CC :2383). 修复前 message
     * 恒 null, 校验失败原因不会注入 LLM. 断言: message 是 hook_non_blocking_error attachment,
     * content 以 "JSON validation failed" 开头.
     */
    @Test
    @DisplayName("16. [H7-v2] HTTP body 非 JSON → message=hook_non_blocking_error attachment (CC :2367-2391)")
    void httpValidationError_producesHookNonBlockingErrorMessage() {
        ExecHttpHook.HttpHookResult r =
            new ExecHttpHook.HttpHookResult(true, 200, "not json", null, false);
        GenericHook.HookResult result = HookRegistry.httpToHookResult(
            r, httpHook("http://127.0.0.1:1/hook"), "config-http:test", "UserPromptSubmit", null);

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        assertThat(att.content()).startsWith("JSON validation failed"); // stderr (CC :2382)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 17-18. [H7-v2] H7-GAP-3 修复: expectedHookEvent 校验 (CC hooks.ts:583-590)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): CC processHookJSONOutput (hooks.ts:583-590) — expectedHookEvent 非空且
     * hookSpecificOutput.hookEventName 不匹配 → throw → runHook catch → non_blocking_error (fail-loud).
     * H7 前 executeConfiguredHttp 传 null 跳过校验, hook 返回错误事件名被静默接受 (H7-GAP-3).
     * RED→GREEN: 修复前本 body 被当 SUCCESS (additionalContext 透传); 修复后 NON_BLOCKING_ERROR.
     */
    @Test
    @DisplayName("17. [H7-v2+H3-v4] hookSpecificOutput 事件名不匹配 expected → NON_BLOCKING_ERROR + hook_non_blocking_error attachment (CC :583-590 + :2715-2729)")
    void httpHookSpecificOutput_wrongEventName_failsLoudly() {
        GenericHook.HookResult result = interpretBodyWithExpected(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
            "UserPromptSubmit");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        // [H3 v4 Gap②] CC runHook catch (hooks.ts:2698-2729) 产 hook_non_blocking_error attachment
        //   (stderr=`Failed to run: ...`, stdout='', exitCode=1). v3 前 catch 路径 message=null,
        //   调用方拿不到 attachment 注入 LLM (对抗复验 PARTIAL 残留). 本断言锁 CC 载荷.
        AttachmentMessageDto msg = (AttachmentMessageDto) result.message();
        assertThat(msg).as("http sync catch 必须产 hook_non_blocking_error attachment（CC :2715-2729）")
            .isNotNull();
        assertThat(msg.type()).isEqualTo("hook_non_blocking_error");
        assertThat(msg.stderr()).contains("Failed to run: Hook returned incorrect event name");
        assertThat(msg.stdout()).as("CC runHook catch stdout:''（hooks.ts:2726）").isEmpty();
        assertThat(msg.exitCode()).isEqualTo(1);
    }

    /**
     * WHY: 匹配事件名必须正常通过 (不误伤). CC hooks.ts:583-590 仅在不匹配时 throw.
     * 若校验实现过严 (如误判匹配), 合法 hook 会被误杀阻断.
     */
    @Test
    @DisplayName("18. [H7-v2] hookSpecificOutput 事件名匹配 expected → SUCCESS + additionalContext")
    void httpHookSpecificOutput_matchingEventName_accepted() {
        GenericHook.HookResult result = interpretBodyWithExpected(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"x\"}}",
            "UserPromptSubmit");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.preventContinuation()).isFalse();
        assertThat(result.additionalContexts()).containsExactly("x");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 19. [H7-v2] H7-GAP-1: HTTPS validate-only 锁定 (平台限制如实标注)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): HTTPS 的 DNS rebinding TOCTOU 窗口无法在 Java 关闭 (平台限制: HttpClient
     * 无 lookup 注入 + IP 字面量破坏 SNI/证书校验, 见 J.md H7-GAP-1 / concern H7-3). 本测试锁定
     * HTTPS 仍走 ssrfGuardedLookup 校验层 (validate-only): guard 抛 SecurityException (解析到
     * 内网地址) → exec 必须返回 error. 若有人"修复"时把 https 也跳过校验 (仿代理路径), 本测试变红 —
     * 防止 HTTPS 连校验层都丢失. 当前窗口只是"校验→连接"间的重解析, 校验本身必须保留.
     */
    @Test
    @DisplayName("19. [H7-v2] HTTPS 仍走 ssrfGuardedLookup 校验层 (validate-only, H7-GAP-1)")
    void httpsScheme_stillRunsSsrfValidation_validateOnly() throws Exception {
        StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("127.0.0.1")) {
            @Override
            public InetAddress ssrfGuardedLookup(String hostname) {
                // 模拟解析到内网 metadata 地址 → 校验层必须拦截 (HTTPS 仍受 SSRF 保护)
                throw new SecurityException("SSRF blocked: 169.254.169.254");
            }
        };
        ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);

        ExecHttpHook.HttpHookResult result = hook.exec(
            httpHook("https://metadata.example.test/hook"), "test-hook", hookEvent, "{}", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("169.254.169.254");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 20. [H7-v3] Gap①: executeEvent 聚合层折叠非阻断 message attachment
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 测试验证意图): httpToHookResult 对 HTTP 500 (error||!ok) 产出
     * {@code hook_non_blocking_error} attachment (message 字段, CC hooks.ts:2334-2360),
     * 但 executeEvent 聚合层只折叠 preventContinuation/blockingError/retry (v2 对抗复验
     * PARTIAL Gap①) — 非阻断 message 在聚合层被静默丢弃, 非阻断错误原因到不了调用方/LLM.
     * 本测试走完整分发路径 (executeEvent → executeConfiguredHooks → executeConfiguredHttp
     * → httpToHookResult), 断言聚合结果 message() 携带 AttachmentMessageDto. 修复前
     * resolveEventResult 全 null → proceed(), message=null (本断言变红).
     */
    @Test
    @DisplayName("20. [H7-v3] HTTP 500 经 executeEvent 分发 → 聚合结果保留非阻断 message attachment")
    void httpError_throughDispatch_aggregatesMessageAttachment() throws IOException {
        String url = startServer(exchange -> respond(exchange, 500, ""));

        GenericHook.HookResult result = runHttpHookThroughRegistry(url);

        assertThat(result.message())
            .as("executeEvent 聚合层必须保留 httpToHookResult 产出的非阻断 message attachment (Gap①)")
            .isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_non_blocking_error");
        assertThat(att.content()).contains("HTTP 500");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 21-22. [H7-v3] Gap②: executeConfiguredHttp 真实分发路径 ccName 接线回归
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 测试验证意图): H7-v2 的 expectedHookEvent 测试 (17-18) 直接调
     * {@code httpToHookResult} 显式传 expectedHookEvent, 未覆盖"真实分发路径" —
     * {@code executeConfiguredHttp} 是否真把 {@code event.type().ccName()} 接上
     * (v2 对抗复验 PARTIAL Gap②). 本测试走完整分发链 executeEvent → executeConfiguredHooks
     * → executeOneConfiguredHook → executeConfiguredHttp → httpToHookResult, hook 返回
     * 错误事件名 (PostToolUse) 而实际事件是 UserPromptSubmit → 必须 NON_BLOCKING_ERROR
     * (fail-loud, CC hooks.ts:583-590). 若把 executeConfiguredHttp 的 ccName 传回 null
     * (revert), 校验被跳过 → 本 body 当 SUCCESS 接受 → 断言变红.
     */
    @Test
    @DisplayName("21. [H7-v3] 真实分发路径: hook 返回错误事件名 → NON_BLOCKING_ERROR (ccName 接线)")
    void dispatchPath_wrongEventName_failsLoudly() throws IOException {
        String url = startServer(exchange -> respond(exchange, 200,
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}"));

        GenericHook.HookResult result = runHttpHookThroughRegistry(url);

        assertThat(result.outcome())
            .as("executeConfiguredHttp 必须把 event.type().ccName() (UserPromptSubmit) 接给 "
                + "httpToHookResult, 事件名不匹配 → fail-loud NON_BLOCKING_ERROR (Gap②)")
            .isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
    }

    /**
     * WHY: 匹配事件名 (UserPromptSubmit) 经真实分发路径必须正常通过 — ccName 接线回归
     * 的对照组, 防止校验过严误杀合法 hook. 断言 outcome=SUCCESS 而非 additionalContext:
     * additionalContext 字段不在 executeEvent 聚合折叠集 (R30-P0-1 只折 stop/blockingError/
     * retry + H7-v3 新增 message/outcome), 是独立于 gap② 的既有限制 (见 J.md H7-GAP 登记),
     * 本测试只锁 ccName 接线语义.
     */
    @Test
    @DisplayName("22. [H7-v3] 真实分发路径: hook 返回匹配事件名 → SUCCESS (不误伤)")
    void dispatchPath_matchingEventName_accepted() throws IOException {
        String url = startServer(exchange -> respond(exchange, 200,
            "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"x\"}}"));

        GenericHook.HookResult result = runHttpHookThroughRegistry(url);

        assertThat(result.outcome())
            .as("匹配事件名经 ccName 接线必须正常通过 (校验只在不匹配时 fail-loud)")
            .isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.preventContinuation()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 23. [H7-v3] Gap③: HTTPS TOCTOU — IP 字面量建连 + 按原始 hostname 验证 + SNI
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 起本地 HTTPS 测试服务器 · 用 src/test/resources/hooks/https-test.p12 自签名证书
     * (SAN=dns:rebinding.example.test).
     *
     * <p>WHY: 真实 TLS 握手验证三件事: (1) 连接用校验过的 IP 字面量 (hostname 不可解析, 若
     * 用原始 hostname 建连必 DNS 失败, 服务器不会被命中); (2) 证书按原始 hostname 验证
     * (自签名证书无 IP SAN, 若按 IP 校验必失败); (3) SNI 保留原始 hostname (服务端
     * ExtendedSSLSession 捕获).
     *
     * @return [url, serverHits, sniCaptured]
     */
    private Object[] startHttpsServer() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = ExecHttpHookEndToEndTest.class.getResourceAsStream(
            "/hooks/https-test.p12")) {
            if (in == null) {
                throw new IllegalStateException("缺少测试 keystore: /hooks/https-test.p12");
            }
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, new SecureRandom());

        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> sniCaptured = new AtomicReference<>();
        HttpsServer srv = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setHttpsConfigurator(new HttpsConfigurator(serverSsl) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(serverSsl.getDefaultSSLParameters());
            }
        });
        srv.createContext("/", exchange -> {
            hits.incrementAndGet();
            if (exchange instanceof com.sun.net.httpserver.HttpsExchange httpsEx) {
                SSLSession session = httpsEx.getSSLSession();
                if (session instanceof ExtendedSSLSession ext) {
                    for (SNIServerName sn : ext.getRequestedServerNames()) {
                        if (sn instanceof SNIHostName h) {
                            sniCaptured.set(h.getAsciiName());
                        }
                    }
                }
            }
            byte[] resp = "{\"continue\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        srv.start();
        String url = "https://127.0.0.1:" + srv.getAddress().getPort() + "/hook";
        return new Object[]{srv, url, hits, sniCaptured};
    }

    /** 从测试 keystore 构建信任该自签名证书的 X509ExtendedTrustManager · 注入 ExecHttpHook. */
    private X509ExtendedTrustManager trustHttpsTestCert() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = ExecHttpHookEndToEndTest.class.getResourceAsStream(
            "/hooks/https-test.p12")) {
            if (in == null) {
                throw new IllegalStateException("缺少测试 keystore: /hooks/https-test.p12");
            }
            ks.load(in, "changeit".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("无 X509ExtendedTrustManager");
    }

    /**
     * WHY (规则九 · 测试验证意图): v2 对 https 只做 validate-only, {@code validatedIp} 不用于
     * 建连 (H7-GAP-1) — 校验域名→连接域名间存在 DNS rebinding 重解析窗口. v3 修复后 https
     * 也用校验过的 IP 字面量建连 + 按原始 hostname 验证证书 + SNI 保留 (对齐 CC axios lookup).
     * 断言三连:
     * <ol>
     *   <li>服务器被命中 — 连接用了校验 IP (127.0.0.1), 而非不可解析的原始 hostname</li>
     *   <li>ok=true — TLS 握手成功: 证书按原始 hostname 验证通过 (自签名证书无 IP SAN,
     *       若按 IP 校验必失败)</li>
     *   <li>sniCaptured=原始 hostname — SSLParameters.setServerNames 生效 (JDK 源码实证
     *       formSNIServerNames 在 URI host 为 IP 字面量时回退 client.sslParameters().getServerNames)</li>
     * </ol>
     * RED→GREEN: v3 修复前 https 用原始 hostname URI 建连 → rebinding.example.test 不可解析
     * → DNS 失败服务器不命中 → 断言 1 变红.
     */
    @Test
    @DisplayName("23. [H7-v3] HTTPS: IP 字面量建连 + 原始 hostname 证书校验 + SNI (无 rebinding 窗口)")
    void httpsScheme_usesValidatedIpLiteral_preservesSni() throws Exception {
        Object[] h = startHttpsServer();
        HttpsServer srv = (HttpsServer) h[0];
        try {
            String url = (String) h[1];
            AtomicInteger hits = (AtomicInteger) h[2];
            AtomicReference<String> sni = (AtomicReference<String>) h[3];
            int port = URI.create(url).getPort();
            String targetUrl = "https://rebinding.example.test:" + port + "/hook";

            StubSsrfGuard guard = new StubSsrfGuard(InetAddress.getByName("127.0.0.1"));
            ExecHttpHook hook = buildHook(new HooksSettings(key -> null), guard);
            hook.setHttpsTrustOverrideForTest(trustHttpsTestCert());

            ExecHttpHook.HttpHookResult result = hook.exec(httpHook(targetUrl), "test-hook",
                hookEvent, "{}", null);

            assertThat(hits.get())
                .as("HTTPS 必须用校验过的 IP 字面量建连 (rebinding.example.test 不可解析, "
                    + "用原始 hostname 建连服务器不会命中) — TOCTOU 关闭")
                .isEqualTo(1);
            assertThat(result.ok())
                .as("TLS 握手必须成功: 证书按原始 hostname (rebinding.example.test) 验证 "
                    + "(自签名证书无 IP SAN, 按 IP 校验必失败)")
                .isTrue();
            assertThat(sni.get())
                .as("SNI 必须保留原始 hostname (SSLParameters.setServerNames)")
                .isEqualTo("rebinding.example.test");
            assertThat(guard.lookupCalls.get())
                .as("ssrfGuardedLookup 只调 1 次 — 校验与连接共用同一 IP, 无重解析")
                .isEqualTo(1);
        } finally {
            srv.stop(0);
        }
    }
}

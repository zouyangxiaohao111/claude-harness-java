package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exec HTTP Hook · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/execHttpHook.ts} (242 行).
 *
 * <p>WHY: CC http hook 把 hook 输入 JSON POST 到配置 URL, 接收响应作为 hook output.
 * 5 重安全防御: URL allowlist (全局策略) + env var 白名单插值 + header CRLF 消毒 +
 * SSRF guard (private IP 拦截) + 禁重定向 (防重定向绕过 SSRF). Java 端此前完全缺失
 * HTTP hook 执行器 (glob 0 命中 ExecHttpHook). 本类用 {@link HttpClient} (Redirect.NEVER
 * 等价 axios {@code maxRedirects:0}) 复刻全套行为.
 *
 * <h2>CC 真源行为映射 (主 agent grep 实证, 不抄探查报告行号)</h2>
 * <ul>
 *   <li>URL allowlist 三态 (execHttpHook.ts:137-145): undefined=不限制 / []=全拦 / 非空=须匹配</li>
 *   <li>timeout (execHttpHook.ts:12,147-149): {@code hook.timeout*1000} 秒->毫秒, 默认 10 分钟</li>
 *   <li>headers (execHttpHook.ts:158-172): Content-Type:application/json + 用户 headers (env 插值)</li>
 *   <li>env 双重白名单交集 (execHttpHook.ts:163-167): hook.allowedEnvVars ∩ policy.allowedEnvVars</li>
 *   <li>interpolateEnvVars (execHttpHook.ts:89-108): $VAR/${VAR} 白名单内替换, 非白名单置空, 末尾消毒</li>
 *   <li>sanitizeHeaderValue (execHttpHook.ts:76-79): 去 CR/LF/NUL 防 header 注入</li>
 *   <li>maxRedirects:0 (execHttpHook.ts:206) -> {@link HttpClient.Redirect#NEVER}</li>
 *   <li>validateStatus:()=>true (execHttpHook.ts:205) -> HttpClient 默认不因 4xx/5xx 抛异常</li>
 *   <li>lookup:ssrfGuardedLookup (execHttpHook.ts:216) -> {@link SsrfGuard#ssrfGuardedLookup(String)}</li>
 *   <li>返回 5 字段 (execHttpHook.ts:128-134): ok / statusCode? / body / error? / aborted?</li>
 * </ul>
 *
 * <h2>Java 适配 (concern H6-1/H6-2/H7-3)</h2>
 * <ul>
 *   <li><b>[H7] TOCTOU 修复 (决策 H7-3)</b>: CC 用 axios {@code lookup:ssrfGuardedLookup} 把校验过的
 *       IP 直接交给 socket 连接 (消除 DNS rebinding TOCTOU). Java {@link HttpClient} 无 lookup 钩子.
 *       修复方案: 把连接 URI 重写为校验过的 IP 字面量 (host 换 {@link InetAddress},
 *       保留 scheme/port/path/query), 彻底消除 rebinding 窗口.
 *       <b>[H7-v3] HTTPS 亦用 IP 字面量建连 (Gap③)</b>: v2 因平台限制只对 http 关闭窗口,
 *       https 保留 validate-only (H7-GAP-1). v3 用三件套对齐 CC axios lookup 语义:
 *       (a) 连接 URI 重写为校验 IP 字面量 (消除重解析窗口); (b) 自定义
 *       {@link X509ExtendedTrustManager} — 按<b>原始 hostname</b>验证证书 (而非 IP 字面量,
 *       公共证书无 IP SAN), 链校验仍委托系统信任库; (c) {@code SSLParameters.setServerNames}
 *       显式携带原始 hostname 保 SNI. JDK 源码实证 (jdk-25 AbstractAsyncSSLConnection
 *       {@code formSNIServerNames}): URI host 是 IP 字面量时 SNI 回退到
 *       {@code client.sslParameters().getServerNames()} — 因此客户端 sslParameters 设
 *       serverNames 即可保 SNI; {@code createSSLParameters} 会把 endpointIdentification
 *       algorithm 强制 "HTTPS", 而 JSSE 的 endpoint identity 检查在
 *       {@code X509TrustManagerImpl.checkServerTrusted(chain, authType, engine)} 内完成 —
 *       自定义 {@link X509ExtendedTrustManager} 接管该检查, 按原始 hostname 而非 IP 校验.</li>
 *   <li>[IMPL-10] DEL-EX-01: 执行标识结果字段已删除（CC hooks.ts:2199 randomUUID 属分发层
 *       事件标识，Java 事件层由 HookEventBus 承担）.</li>
 *   <li><b>[H7] 代理路由</b>: 对齐 CC execHttpHook.ts:176-199 (sandboxProxy > envProxy > 直连).
 *       sandbox 代理为可注入字段 (默认 null, Java 无沙箱); env 代理判定 = 单值优先级链
 *       https_proxy→HTTPS_PROXY→http_proxy→HTTP_PROXY 不区分 scheme (CCJ-EXEC-02,
 *       proxy.ts:64-66) + NO_PROXY 绕过 (proxy.ts:88-129). 代理路径跳过 ssrf lookup
 *       (代理自己解析目标 DNS, 对代理 IP 做校验会误伤企业代理).</li>
 *   <li><b>[IMPL-06 D5-3/OD-EX-02] 父 abort 已接</b>: CC 用
 *       {@code createCombinedAbortSignal(signal, {timeoutMs})} 合并外部 signal + timeout signal
 *       (execHttpHook.ts:151-154)。Java 端父 abort 经 {@code sendAsync} 与 abort latch 竞速
 *       （父取消 → 立即返回 aborted=true，CC :234-236 combinedSignal.aborted）；timeout 仍由
 *       {@link HttpRequest#timeout(Duration)} 承载（超时 → HttpTimeoutException → aborted=true，
 *       与 CC :147-149 timeout → combinedSignal.aborted 同语义）。Java 无法强制关闭已发出请求的
 *       底层连接（HttpClient 无 abort API），in-flight 请求结果被丢弃（可观测行为对齐：分发层
 *       立即拿到 aborted）。</li>
 * </ul>
 *
 * <p><b>UI 集成预留</b>: {@link HttpHook} 配置由前端 hook 面板渲染提交, 本组件接收已解析 config 执行.
 *
 */
@Component
public class ExecHttpHook {

    private static final Logger log = LoggerFactory.getLogger(ExecHttpHook.class);

    /**
     * 默认超时 10 分钟 · 对齐 CC execHttpHook.ts:12
     * {@code DEFAULT_HTTP_HOOK_TIMEOUT_MS = 10 * 60 * 1000} (matches TOOL_HOOK_EXECUTION_TIMEOUT_MS).
     */
    public static final long DEFAULT_HTTP_HOOK_TIMEOUT_MS = 10L * 60 * 1000;

    /**
     * 共享 HttpClient · {@link HttpClient.Redirect#NEVER} 等价 axios {@code maxRedirects:0}
     * (execHttpHook.ts:206), 防重定向到内部 IP 绕过 SSRF Guard. 超时按请求设置 (非 client 级).
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** env var 解析器 · 默认 {@link System#getenv}, exec 用此; 测试通过静态方法注入 mock. */
    private static final Function<String, String> DEFAULT_ENV_RESOLVER = System::getenv;

    private final HooksSettings hooksSettings;
    private final SsrfGuard ssrfGuard;

    /**
     * sandbox 网络代理 · 对齐 CC getSandboxProxyConfig (execHttpHook.ts:21-41).
     * <p>WHY: CC 沙箱模式下请求经 sandbox 网络代理 (强制域名 allowlist, 拦截域名返 403).
     * Java 后端无沙箱 (无 SandboxManager), 故做成可注入字段, 默认 null (无代理);
     * 测试注入 127.0.0.1:port 验证 "sandbox 代理路径跳过 ssrf lookup + 路由到代理" 分支.
     */
    private volatile InetSocketAddress sandboxProxyAddress;

    /**
     * env var 读取器 (实例) · 代理检测用 ({@link #resolveEnvProxy}).
     * 这里读 https_proxy/HTTPS_PROXY/http_proxy/HTTP_PROXY/NO_PROXY 做代理路由决策 (单值链,
     * 测试需注入 mock 才能运行时设置 env).
     */
    private Function<String, String> envResolver = System::getenv;

    public ExecHttpHook(HooksSettings hooksSettings, SsrfGuard ssrfGuard) {
        this.hooksSettings = hooksSettings;
        this.ssrfGuard = ssrfGuard;
    }

    /** 注入 sandbox 代理地址 (默认 null = 无代理) · 测试用; Java 无沙箱, 生产不注入. */
    void setSandboxProxy(InetSocketAddress sandboxProxyAddress) {
        this.sandboxProxyAddress = sandboxProxyAddress;
    }

    /** 注入 env 读取器 (默认 System::getenv) · 测试用, 模拟运行时环境变量. */
    void setEnvResolver(Function<String, String> envResolver) {
        this.envResolver = envResolver != null ? envResolver : System::getenv;
    }

    /**
     * 注入 HTTPS 自定义信任库 override · [H7-v3 Gap③] 测试用.
     *
     * <p>WHY: {@link #httpsIpLiteralClient} 默认用系统信任库 (TrustManagerFactory default
     * → 系统 cacerts). 测试的 HTTPS 测试服务器用自签名证书 (SAN=rebinding.example.test,
     * 不在系统信任库), 需注入信任该证书的 X509ExtendedTrustManager 才能验证自定义
     * HostnameVerifyingTrustManager 的"按原始 hostname 校验"逻辑. 生产不注入 → null 走系统信任库.
     * @param override 测试自定义信任库; null = 系统默认信任库 (生产路径)
     */
    void setHttpsTrustOverrideForTest(X509ExtendedTrustManager override) {
        this.httpsTrustOverride = override;
    }

    /** HTTPS 信任库 override · 见 {@link #setHttpsTrustOverrideForTest}; null = 系统默认. */
    private volatile X509ExtendedTrustManager httpsTrustOverride;

    /**
     * 执行 HTTP hook · 对齐 CC execHttpHook.ts:123-242.
     *
     * <p>流程: URL allowlist 校验 -> 父 abort 预检 (对齐 CC createCombinedAbortSignal 预检,
     *   combinedAbortSignal.ts:22-25) -> timeout 解析 -> 代理检测 + SSRF 校验 (TOCTOU 修复) ->
     * headers 构建 (env 插值) -> POST (Redirect.NEVER, 按需代理/IP 字面量) -> 返回 6 字段结果.
     *
     * @param hook       hook 配置 (url/timeout/headers/allowedEnvVars) · 对齐 CC {@code HttpHook}
     * @param hookName   hook 名 (日志关联用, Java 适配; CC 用 url 日志)
     * @param hookEvent  hook 事件载体 (CC {@code _hookEvent}, 当前未用, 保留契约对齐)
     * @param jsonInput  POST body (hook 输入 JSON); null 视为空串
     * @param parentAbort 父循环 abort 信号 (可 null = 无父级取消) · 对齐 CC hooks.ts:2306
     *                    {@code signal} 参数（execHttpHook.ts:151-154 combinedSignal 父分量，
     *                    IMPL-06 D5-3/OD-EX-02）
     * @return {@link HttpHookResult} 5 字段 (ok / statusCode? / body / error? / aborted)
     */
    public HttpHookResult exec(HttpHook hook, String hookName, HookEvent hookEvent, String jsonInput,
                               AbortController parentAbort) {
        // [IMPL-10] DEL-EX-01: 执行标识已从结果结构删除（CC hooks.ts:2199 randomUUID 属分发层
        //   事件标识 — emitHookStarted/emitHookResponse 关联；Java 事件层由 HookEventBus 承担，
        //   结果结构内无执行标识等价物）。

        if (hook == null || hook.url() == null || hook.url().isBlank()) {
            log.warn("ExecHttpHook: hook={} url 为空, 拒绝执行", hookName);
            // 其他错误 · CC :240 {ok:false, body:'', error:msg}
            return new HttpHookResult(false, null, "", "missing or blank hook url", false);
        }
        String body = jsonInput != null ? jsonInput : "";

        // 1. URL allowlist 校验 · 对齐 CC execHttpHook.ts:137-145 (undefined/[]/非空 三态)
        HttpHookPolicy policy = hooksSettings.getHttpHookPolicy();
        if (policy.allowedUrls() != null) {
            boolean matched = false;
            for (String pattern : policy.allowedUrls()) {
                if (urlMatchesPattern(hook.url(), pattern)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                String msg = "HTTP hook blocked: " + hook.url()
                    + " does not match any pattern in allowedHttpHookUrls";
                log.warn("ExecHttpHook: hook={} URL 被 allowlist 拦截: {}", hookName, hook.url());
                // allowlist 拦截 · CC :143 {ok:false, body:'', error:msg}
                return new HttpHookResult(false, null, "", msg, false);
            }
        }

        // 2. 父 abort 预检 · 对齐 CC execHttpHook.ts:151-154 + combinedAbortSignal.ts:22-25
        //    （signal.aborted → combined.abort() → axios 立即拒绝 → aborted:true）
        //    [IMPL-06 r2] 预检置于 SSRF 校验前（CC createCombinedAbortSignal 先于 axios 内
        //    ssrfGuardedLookup, execHttpHook.ts:216）→ 预取消 + SSRF 拦截 host 角落用例
        //    aborted 优先（reflection P2-3 微差闭环）。
        if (parentAbort != null && parentAbort.isCancelled()) {
            if (log.isInfoEnabled()) {
                log.info("ExecHttpHook: hook={} 父 abort 已取消, 跳过 POST, 返回 aborted", hookName);
            }
            return new HttpHookResult(false, null, "", null, true);
        }

        // 3. timeout 解析 · 对齐 CC execHttpHook.ts:147-149
        long timeoutMs = resolveTimeoutMs(hook.timeout());

        // 4. 代理检测 + SSRF 校验 (TOCTOU 修复) · 对齐 CC execHttpHook.ts:176-216
        URI uri;
        try {
            uri = URI.create(hook.url());
        } catch (Exception e) {
            log.warn("ExecHttpHook: hook={} URL 解析失败: {}", hookName, e.getMessage());
            // 其他错误 · CC :240
            return new HttpHookResult(false, null, "", "invalid url: " + e.getMessage(), false);
        }
        String host = uri.getHost();

        // 代理路由决策 · 对齐 CC execHttpHook.ts:176-199 (sandboxProxy > envProxy > 直连三分支).
        // WHY: 代理自己解析目标 DNS, 对代理本身 IP 做 SSRF 校验会误伤企业代理 (如 10.0.0.1:3128,
        // execHttpHook.ts:184-187 注释), 故代理路径跳过 lookup (execHttpHook.ts:216 lookup=undefined).
        InetSocketAddress sandboxProxy = this.sandboxProxyAddress;
        InetSocketAddress envProxy = sandboxProxy == null ? resolveEnvProxy(uri) : null;
        boolean proxyActive = sandboxProxy != null || envProxy != null;

        // TOCTOU 修复 (决策 H7-3): 直连路径拿到校验过的 InetAddress, HTTP scheme 用它重写连接 URI.
        // CC 用 axios lookup 把校验 IP 直接交给 socket (ssrfGuard.ts:207-215 无 rebinding 窗口);
        // Java 无法注入 lookup, 故用返回的 InetAddress 显式构造连接 URI, 消除 rebinding 窗口.
        InetAddress validatedIp = null;
        if (proxyActive) {
            if (log.isDebugEnabled()) {
                log.debug("ExecHttpHook: hook={} POST 到 {} (via sandboxProxy={} envProxy={}), "
                        + "跳过 ssrf lookup (代理解析 DNS)",
                    hookName, hook.url(), sandboxProxy != null, envProxy != null);
            }
        } else if (host != null && !host.isBlank()) {
            try {
                validatedIp = ssrfGuard.ssrfGuardedLookup(host);
            } catch (SecurityException se) {
                log.warn("ExecHttpHook: hook={} SSRF guard 拦截 host={}: {}",
                    hookName, host, se.getMessage());
                // SSRF 拦截走 error 通道 · CC :238-240 (axios lookup 抛错 -> catch -> error)
                return new HttpHookResult(false, null, "", se.getMessage(), false);
            }
        }

        // 5. headers 构建 · 对齐 CC execHttpHook.ts:158-172 (Content-Type + env 插值)
        Map<String, String> headers = buildHeaders(hook, policy);

        if (log.isDebugEnabled()) {
            log.debug("ExecHttpHook: hook={} POST 到 {} (timeout={}ms)",
                hookName, hook.url(), timeoutMs);
        }

        // 6. POST (Redirect.NEVER) · 对齐 CC execHttpHook.ts:201-217
        try {
            HttpClient client = httpClient;
            URI connectUri = uri;
            if (proxyActive) {
                // 代理路由: 连接 URI 保持原始 hostname (代理解析 DNS), 用 ProxySelector 指向代理.
                // 对齐 CC `proxy: sandboxProxy ?? false` + global interceptor (execHttpHook.ts:201-217).
                InetSocketAddress proxyAddr = sandboxProxy != null ? sandboxProxy : envProxy;
                client = proxyHttpClient(proxyAddr);
            } else if (validatedIp != null) {
                // TOCTOU 修复 (决策 H7-3 + [H7-v3] Gap③): 连接 URI 重写为校验过的 IP 字面量,
                // 彻底消除 DNS rebinding 窗口 (校验与连接共用同一地址, 无重解析).
                // [H7-v3] HTTPS 分支: 自定义 SSLContext/X509ExtendedTrustManager 按原始
                // hostname 验证证书 + SSLParameters.setServerNames 保 SNI (见 httpsIpLiteralClient).
                connectUri = toIpLiteralUri(uri, validatedIp);
                if ("https".equalsIgnoreCase(uri.getScheme()) && host != null && !host.isBlank()) {
                    client = httpsIpLiteralClient(host);
                }
                // HTTP scheme 保持默认 client (httpClient 已 Redirect.NEVER).
            }
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(connectUri)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            for (Map.Entry<String, String> e : headers.entrySet()) {
                reqBuilder.header(e.getKey(), e.getValue());
            }
            HttpRequest request = reqBuilder.build();
            if (parentAbort == null) {
                // 无父 abort → 同步路径（原行为不变）
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return toResult(response);
            }
            // 父 abort 竞速路径 · 对齐 CC combinedAbortSignal 父分量:
            //   sendAsync 与 abort latch 竞速, 父取消 → 立即返回 aborted (CC :234-236)。
            //   timeout 由 HttpRequest.timeout 承载 → HttpTimeoutException → aborted (CC :147-149 同语义)。
            CompletableFuture<HttpResponse<String>> sendFuture =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> abortLatch = new CompletableFuture<>();
            // [E8/CCJ-EXEC-06] 引用捕获供 finally removeOnCancel · CC execHttpHook.ts:219/:232
            //   cleanup()（正常 + catch 两路径移除父 signal 监听器）——旧实现注册后永不移除。
            //   竞速安全：abortLatch.complete 幂等（父 abort 与移除之间的第二次 complete 返回 false）。
            java.util.function.Consumer<AbortController> parentAbortListener = ac -> {
                if (abortLatch.complete(null)) {
                    sendFuture.cancel(true); // 尽力取消底层 exchange（JDK 支持时关闭连接）
                }
            };
            parentAbort.onCancel(parentAbortListener);
            HttpResponse<String> response;
            try {
                try {
                    response = abortLatch.applyToEither(sendFuture, r -> r)
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    // HttpRequest.timeout 触发 → 超时 → aborted（CC :234 combinedSignal.aborted）
                    if (cause instanceof HttpTimeoutException) {
                        log.warn("ExecHttpHook: hook={} 超时 ({}ms), 返回 aborted", hookName, timeoutMs);
                        return new HttpHookResult(false, null, "", null, true);
                    }
                    throw cause;
                }
                if (response == null) {
                    // abort latch 胜出（父取消）→ aborted · 对齐 CC :234-236
                    if (log.isInfoEnabled()) {
                        log.info("ExecHttpHook: hook={} 父 abort, 返回 aborted", hookName);
                    }
                    return new HttpHookResult(false, null, "", null, true);
                }
                return toResult(response);
            } finally {
                // CC :219/:232 cleanup() · 移除父 abort 监听器（abortLatch.complete 幂等吸收竞态）
                parentAbort.removeOnCancel(parentAbortListener);
            }
        } catch (HttpTimeoutException te) {
            // timeout -> aborted · 对齐 CC execHttpHook.ts:234 (combinedSignal.aborted -> aborted:true)
            log.warn("ExecHttpHook: hook={} 超时 ({}ms), 返回 aborted",
                hookName, timeoutMs);
            return new HttpHookResult(false, null, "", null, true);
        } catch (TimeoutException te2) {
            // 竞速路径防御性超时兜底（HttpRequest.timeout 与 get(timeoutMs) 双保险）
            log.warn("ExecHttpHook: hook={} 竞速等待超时 ({}ms), 返回 aborted",
                hookName, timeoutMs);
            return new HttpHookResult(false, null, "", null, true);
        } catch (Throwable t) {
            // 其他异常 -> error · 对齐 CC execHttpHook.ts:238-240
            String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            log.error("ExecHttpHook: hook={} 执行异常: {}", hookName, errorMsg, t);
            return new HttpHookResult(false, null, "", errorMsg, false);
        }
    }

    /** 响应 → 5 字段结果 · 对齐 CC execHttpHook.ts:226-230 (2xx → ok). */
    private HttpHookResult toResult(HttpResponse<String> response) {
        String respBody = response.body() != null ? response.body() : "";
        int status = response.statusCode();
        if (log.isDebugEnabled()) {
            log.debug("ExecHttpHook: 响应 status={} bodyLen={}", status, respBody.length());
        }
        return new HttpHookResult(status >= 200 && status < 300, status, respBody, null, false);
    }

    // ════════════════════════════════════════════════════════════════════════
    // TOCTOU 修复 + 代理路由辅助 · 对齐 CC execHttpHook.ts:176-217 / proxy.ts:88-129
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 连接 URI 重写为校验过的 IP 字面量 · TOCTOU 修复核心 (决策 H7-3).
     * <p>[H7-v3 Gap③] 现在对 http/https 均生效 (v2 仅 http): HTTPS 分支由
     * {@link #httpsIpLiteralClient} 配合自定义信任库保 SNI/证书校验.
     * <p>WHY: 保留 scheme/port/path/query, 仅把 host 换成 {@link SsrfGuard#ssrfGuardedLookup}
     * 返回的校验过 {@link InetAddress}. 这样 socket 连的就是校验过的 IP, 攻击者无法在
     * 校验与连接之间把域名重绑定到内网 (DNS rebinding). 副作用: Host 头变成 IP 字面量
     * (Java HttpClient 的 Host 是 restricted header, 无法按原始 hostname 覆盖), 对
     * 虚拟主机场景有影响 — 已接受 (concern H7-3 记录).
     *
     * @param original 原始连接 URI
     * @param ip       校验过的 InetAddress
     * @return IP 字面量 URI (IPv6 由 7 参构造器自动加 [])
     */
    static URI toIpLiteralUri(URI original, InetAddress ip) {
        String host = ip.getHostAddress();
        try {
            return new URI(original.getScheme(), null, host, original.getPort(),
                original.getPath(), original.getQuery(), original.getFragment());
        } catch (URISyntaxException e) {
            // 理论不可达 (校验过的 IP 必合法); 兜底回退原始 URI, 防御退化
            return original;
        }
    }

    /**
     * 解析 env 代理 · 对齐 CC execHttpHook.ts:184-187 + proxy.ts:64-66 (getProxyUrl).
     * <p>[CCJ-EXEC-02] env 代理存在性判定 = 单值优先级链
     * {@code https_proxy || HTTPS_PROXY || http_proxy || HTTP_PROXY}
     * （proxy.ts:64-66，<b>不区分 scheme</b>）——旧实现按 scheme 分读（http→http_proxy、
     * https→https_proxy）导致"仅设 http_proxy + https 目标"时 CC 走代理而 Java 直连
     * （路由/SSRF 拦截结果不同）。NO_PROXY 旁路（proxy.ts:88-129 shouldBypassProxy）保留。
     *
     * @param uri 目标 URI
     * @return 代理地址; null = 无代理
     */
    InetSocketAddress resolveEnvProxy(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme);
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!http && !https) {
            return null;
        }
        // CC proxy.ts:64-66 单值优先级链 · 不区分 scheme（小写优先, 与 CC 逐字一致）
        String proxyEnv = firstNonBlank(envResolver.apply("https_proxy"),
            firstNonBlank(envResolver.apply("HTTPS_PROXY"),
                firstNonBlank(envResolver.apply("http_proxy"), envResolver.apply("HTTP_PROXY"))));
        if (proxyEnv == null || proxyEnv.isBlank()) {
            return null;
        }
        String noProxy = firstNonBlank(envResolver.apply("no_proxy"), envResolver.apply("NO_PROXY"));
        if (shouldBypassProxy(uri, noProxy)) {
            if (log.isDebugEnabled()) {
                log.debug("ExecHttpHook: NO_PROXY='{}' 命中 {} , 绕过 env 代理", noProxy, uri);
            }
            return null;
        }
        return parseProxyAddress(proxyEnv);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /**
     * NO_PROXY 匹配 · 对齐 CC proxy.ts:88-129 {@code shouldBypassProxy}.
     * <p>支持: 精确 hostname / 前导点域名后缀 (.example.com 匹配 example.com 与子域,
     * 不匹配 notexample.com) / 通配 * / host:port 精确匹配.
     *
     * @param uri     目标 URI
     * @param noProxy NO_PROXY 值 (逗号/空白分隔)
     * @return true = 绕过代理
     */
    static boolean shouldBypassProxy(URI uri, String noProxy) {
        if (noProxy == null || noProxy.isBlank()) {
            return false;
        }
        if ("*".equals(noProxy.trim())) {
            return true;
        }
        String hostname = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        if (hostname.isEmpty()) {
            return false;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        String hostWithPort = hostname + ":" + port;
        for (String raw : noProxy.split("[,\\s]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            String pattern = raw.toLowerCase(Locale.ROOT).trim();
            if (pattern.contains(":")) {
                if (hostWithPort.equals(pattern)) {
                    return true;
                }
            } else if (pattern.startsWith(".")) {
                String suffix = pattern;
                if (hostname.equals(suffix.substring(1)) || hostname.endsWith(suffix)) {
                    return true;
                }
            } else {
                if (hostname.equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 解析代理 env 值 ("http://host:port") → InetSocketAddress; 非法返回 null. */
    static InetSocketAddress parseProxyAddress(String proxyUrl) {
        try {
            URI u = URI.create(proxyUrl);
            String host = u.getHost();
            if (host == null) {
                return null;
            }
            int port = u.getPort() > 0 ? u.getPort()
                : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            return new InetSocketAddress(host, port);
        } catch (Exception e) {
            return null;
        }
    }

    /** 带 ProxySelector 的 HttpClient · 代理路由用 (连接 URI 保持原始 hostname, 代理解析 DNS). */
    private HttpClient proxyHttpClient(InetSocketAddress proxyAddr) {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.of(proxyAddr))
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [H7-v3 Gap③] HTTPS TOCTOU · 用校验过的 IP 字面量建连 + 按原始 hostname 验证证书
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 按原始 hostname 构建 HTTPS IP 字面量连接用的 HttpClient · TOCTOU 关闭 (Gap③).
     *
     * <p>WHY: v2 对 https 只做 validate-only (H7-GAP-1), {@code validatedIp} 不用于建连, 保留
     * "校验域名→连接域名"间的 DNS rebinding 窗口. 本方法对齐 CC axios lookup 语义, 三件套:
     * <ol>
     *   <li><b>IP 字面量 URI</b> (调用方 {@link #toIpLiteralUri} 已重写): socket 直连校验过的
     *       IP, 无重解析窗口</li>
     *   <li><b>自定义 {@link X509ExtendedTrustManager}</b>: 链校验委托系统默认信任库, 额外按
     *       <b>原始 hostname</b> 校验叶证书 (SAN/CN), 而非 IP 字面量 — 公共证书无 IP SAN,
     *       endpoint identity 若按 IP 检查必失败 (v2 平台限制②的根因)</li>
     *   <li><b>{@code SSLParameters.setServerNames(原始 hostname)}</b>: 保 SNI. JDK 源码实证
     *       (jdk-25 AbstractAsyncSSLConnection.formSNIServerNames): URI host 是 IP 字面量时
     *       SNI 回退到 {@code client.sslParameters().getServerNames()} — 因此设置 client 的
     *       sslParameters.serverNames 即可让 SNI 携带原始 hostname.</li>
     * </ol>
     *
     * <p><b>endpoint identification 归属</b>: JSSE 的 HTTPS endpoint identity 检查在
     * {@code X509TrustManagerImpl.checkServerTrusted(chain, authType, engine)} 内完成
     * (jdk-25 源码实证), 自定义 {@link X509ExtendedTrustManager} 接管该检查 — 本类按原始
     * hostname 验证, 绕开默认 trust manager 按 IP (peerHost=IP 字面量) 校验的死路.
     *
     * <p><b>Host 头副作用 (H7-GAP-2)</b>: IP 字面量 URI 下 HttpClient 的 Host 头是 restricted
     * header, 无法按原始 hostname 覆盖 (与 HTTP scheme 已接受的副作用一致, concern H7-3).
     *
     * @param originalHost 原始 hostname (SSRF 校验 + 证书验证 + SNI 三处共用)
     * @return 按原始 hostname 验证证书 + 保 SNI 的 HttpClient (Redirect.NEVER)
     */
    private HttpClient httpsIpLiteralClient(String originalHost) {
        try {
            X509ExtendedTrustManager systemTm = httpsTrustOverride;
            if (systemTm == null) {
                // 链校验委托系统默认信任库 (TrustManagerFactory default → 系统 cacerts).
                // 生产路径: 无 override → 系统信任库; 测试路径: 注入信任自签名证书的信任库
                // (见 setHttpsTrustOverrideForTest).
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((KeyStore) null);
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509ExtendedTrustManager x) {
                        systemTm = x;
                        break;
                    }
                }
            }
            if (systemTm == null) {
                // 兜底: 找不到 X509ExtendedTrustManager → 放弃 IP 字面量, 退回默认 client.
                // 该场景理论不可达 (JDK 默认信任库必返回 X509ExtendedTrustManager), 防御退化.
                log.warn("ExecHttpHook: 系统信任库无 X509ExtendedTrustManager, https TOCTOU 修复退化 (host={})",
                    originalHost);
                return httpClient;
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(new KeyManager[0],
                new TrustManager[]{new HostnameVerifyingTrustManager(systemTm, originalHost)},
                new SecureRandom());
            SSLParameters sslParams = new SSLParameters();
            // SNI 保原始 hostname · jdk-25 formSNIServerNames 实证: URI host 为 IP 字面量时
            // 回退 client.sslParameters().getServerNames().
            sslParams.setServerNames(List.of(new SNIHostName(originalHost)));
            return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(sslContext)
                .sslParameters(sslParams)
                .build();
        } catch (Exception e) {
            // SSL 配置失败 → 防御退化回默认 client (httpClient), 保留 HTTPS 基本可用性.
            // 注意: 退化后 validatedIp 仍用于 URI 重写, 但无自定义 trust manager 时 endpoint
            // identity 会按 IP 检查 → 公共证书场景握手失败 (fail-loud, 不静默降低安全).
            log.warn("ExecHttpHook: https IP 字面量 client 构建失败, 退回默认 client (host={}): {}",
                originalHost, e.toString());
            return httpClient;
        }
    }

    /**
     * [H7-v3] 按原始 hostname 验证证书的 X509ExtendedTrustManager · TOCTOU 修复的证书侧.
     *
     * <p>WHY: IP 字面量建连下, JSSE endpoint identity 默认按 peerHost=IP 检查叶证书 (公共
     * 证书无 IP SAN 必失败). 本类接管 {@link #checkServerTrusted}: 链校验委托系统信任库
     * (delegate), 身份校验按<b>原始 hostname</b>匹配叶证书 SAN dNSName / CN — 等价 CC
     * axios 的 https.Agent + lookup 语义 (域名解析成 IP 但 TLS 校验仍按原 hostname).
     */
    private static final class HostnameVerifyingTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final String expectedHost;

        HostnameVerifyingTrustManager(X509ExtendedTrustManager delegate, String expectedHost) {
            this.delegate = delegate;
            this.expectedHost = expectedHost;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType,
                                       java.net.Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType,
                                       SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyServerHostname(chain, expectedHost);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType,
                                       java.net.Socket socket) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyServerHostname(chain, expectedHost);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType,
                                       SSLEngine engine) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyServerHostname(chain, expectedHost);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        /**
         * 按 expectedHost 校验叶证书 SAN dNSName / CN · 对齐 RFC 6125 简化实现.
         *
         * <p>WHY: 只用 SAN dNSName + CN 匹配 (大小写不敏感, 支持通配符 {@code *.example.com}),
         * 不解析 subjectAltName 的 otherName (Java 拿不到 RFC822 类型的 DNS 值). 空 expectedHost
         * 视为无校验 (构建时已非空).
         *
         * @param chain 服务器证书链 (叶证书 = chain[0])
         * @param host  原始 hostname (非 IP 字面量)
         * @throws CertificateException SAN/CN 均不匹配 → 握手失败 (fail-loud)
         */
        private void verifyServerHostname(X509Certificate[] chain, String host)
                throws CertificateException {
            if (host == null || host.isBlank()) {
                return;
            }
            if (chain == null || chain.length == 0) {
                throw new CertificateException("HTTPS hook: 空证书链, host=" + host);
            }
            X509Certificate leaf = chain[0];
            String matchHost = host.toLowerCase(Locale.ROOT);
            // 1) SAN dNSName (type 2) 优先 · RFC 6125
            Collection<List<?>> sans = null;
            try {
                sans = leaf.getSubjectAlternativeNames();
            } catch (CertificateParsingException e) {
                // SAN 解析失败 → 继续尝试 CN
                if (log.isDebugEnabled()) {
                    log.debug("ExecHttpHook: 叶证书 SAN 解析失败, 尝试 CN (host={}): {}",
                        host, e.getMessage());
                }
            }
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                        Object name = san.get(1);
                        if (name != null && dnsNameMatches(String.valueOf(name), matchHost)) {
                            return;
                        }
                    }
                }
            }
            // 2) CN 兜底 (无 SAN 的老证书)
            String cn = extractCn(leaf.getSubjectX500Principal());
            if (cn != null && dnsNameMatches(cn, matchHost)) {
                return;
            }
            throw new CertificateException("HTTPS hook: 证书 SAN/CN 不匹配原始 hostname "
                + host + " (TOCTOU 修复要求按原始 hostname 验证证书)");
        }

        /**
         * SAN dNSName / CN 匹配 · 大小写不敏感, 支持单段通配符 {@code *.example.com}
         * (匹配 foo.example.com 但不匹配 a.b.example.com 与裸 example.com).
         */
        private static boolean dnsNameMatches(String pattern, String host) {
            String p = pattern.toLowerCase(Locale.ROOT).trim();
            if (p.equals(host)) {
                return true;
            }
            if (p.startsWith("*.")) {
                String suffix = p.substring(1); // ".example.com"
                if (host.endsWith(suffix)) {
                    String prefix = host.substring(0, host.length() - suffix.length());
                    // 通配符只匹配一段标签: prefix 非空且不含 '.' (foo ✓, a.b ✗)
                    return !prefix.isEmpty() && !prefix.contains(".");
                }
            }
            return false;
        }

        /** 从 subject DN 提取 CN (RFC2253), 无 CN → null. */
        private static String extractCn(X500Principal principal) {
            if (principal == null) {
                return null;
            }
            String dn = principal.getName(X500Principal.RFC2253);
            for (String part : dn.split(",")) {
                String p = part.trim();
                if (p.regionMatches(true, 0, "CN=", 0, 3)) {
                    return p.substring(3);
                }
            }
            return null;
        }
    }

    /**
     * 构建 headers · 对齐 CC execHttpHook.ts:158-172.
     * Content-Type:application/json + 用户 headers (env 插值 + 双重白名单交集).
     */
    private Map<String, String> buildHeaders(HttpHook hook, HttpHookPolicy policy) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");  // CC :159
        if (hook.headers() == null || hook.headers().isEmpty()) {
            return headers;
        }
        // env 双重白名单交集 · 对齐 CC execHttpHook.ts:163-167
        // hookVars ∩ policy.allowedEnvVars (policy 设了才求交, 否则用 hookVars)
        List<String> hookVars = hook.allowedEnvVars() != null ? hook.allowedEnvVars() : List.of();
        Set<String> allowedEnvVars;
        if (policy.allowedEnvVars() != null) {
            Set<String> policySet = new HashSet<>(policy.allowedEnvVars());
            allowedEnvVars = new HashSet<>();
            for (String v : hookVars) {
                if (policySet.contains(v)) {
                    allowedEnvVars.add(v);
                }
            }
        } else {
            allowedEnvVars = new HashSet<>(hookVars);
        }
        for (Map.Entry<String, String> entry : hook.headers().entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue() : "";
            headers.put(name, interpolateEnvVars(value, allowedEnvVars, DEFAULT_ENV_RESOLVER));
        }
        return headers;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 包级静态工具 (可直接单测) · 对齐 CC execHttpHook.ts:64-108
    // ════════════════════════════════════════════════════════════════════════

    /**
     * URL 通配符匹配 · 对齐 CC execHttpHook.ts:64-68 {@code urlMatchesPattern}.
     * pattern 中 {@code *} -> {@code .*}, 其余正则元字符转义, {@code ^$} 锚定.
     *
     * @param url     待匹配 URL
     * @param pattern 含 * 通配符的 pattern
     * @return true = 匹配
     */
    static boolean urlMatchesPattern(String url, String pattern) {
        if (url == null || pattern == null) {
            return false;
        }
        // CC :65 转义正则元字符 [.+?^${}()|[\]\\]
        String escaped = pattern.replaceAll("[.+?^${}()|\\[\\]\\\\]", "\\\\$0");
        // CC :66 * -> .*
        String regexStr = escaped.replace("*", ".*");
        // CC :67 ^$ 锚定
        return Pattern.compile("^" + regexStr + "$").matcher(url).matches();
    }

    /**
     * header value 消毒 · 对齐 CC execHttpHook.ts:76-79 {@code sanitizeHeaderValue}.
     * 去 CR/LF/NUL 防 HTTP header 注入 (CRLF injection).
     *
     * @param value 原始 header value
     * @return 去 CR/LF/NUL 后的 value; null 透传
     */
    static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        // CC :78 /[\r\n\x00]/g
        return value.replaceAll("[\r\n\0]", "");
    }

    /**
     * env var 插值 · 对齐 CC execHttpHook.ts:89-108 {@code interpolateEnvVars}.
     * {@code $VAR} / {@code ${VAR}} (VAR 匹配 {@code [A-Z_][A-Z0-9_]*}), 仅白名单内变量替换,
     * 非白名单置空 (防 secret 外泄); 末尾 {@link #sanitizeHeaderValue} 消毒.
     *
     * @param value          含占位符的原始值
     * @param allowedEnvVars 允许插值的变量名集合
     * @param envResolver    env 取值器 (默认 {@link System#getenv}, 测试注入 mock)
     * @return 插值 + 消毒后的值
     */
    static String interpolateEnvVars(String value, Set<String> allowedEnvVars,
                                     Function<String, String> envResolver) {
        if (value == null) {
            return null;
        }
        Set<String> allowed = allowedEnvVars != null ? allowedEnvVars : Set.of();
        Function<String, String> resolver = envResolver != null ? envResolver : s -> null;
        // CC :94 \${([A-Z_][A-Z0-9_]*)}|\$([A-Z_][A-Z0-9_]*)
        Pattern p = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}|\\$([A-Z_][A-Z0-9_]*)");
        Matcher m = p.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1) != null ? m.group(1) : m.group(2);
            String replacement;
            if (!allowed.contains(varName)) {
                // CC :97-102 非白名单 -> 空 (不外泄)
                if (log.isDebugEnabled()) {
                    log.debug("ExecHttpHook: env var ${} 不在 allowedEnvVars, 跳过插值 (置空)", varName);
                }
                replacement = "";
            } else {
                // CC :104 process.env[varName] ?? ''
                String val = resolver.apply(varName);
                replacement = val != null ? val : "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        // CC :107 末尾消毒
        return sanitizeHeaderValue(sb.toString());
    }

    /**
     * 解析超时毫秒数 · 对齐 CC execHttpHook.ts:147-149
     * {@code hook.timeout ? hook.timeout * 1000 : DEFAULT_HTTP_HOOK_TIMEOUT_MS}.
     *
     * <p>CC 用 truthy 判断 (0/undefined -> 默认). Zod schema 校验 timeout 为 positive,
     * 故 0/负值不会出现; 此处 {@code > 0} 等价 truthy 语义且更安全 (0 -> 默认).
     *
     * @param hookTimeout hook.timeout (秒, 可 null)
     * @return 超时毫秒数
     */
    static long resolveTimeoutMs(Integer hookTimeout) {
        return hookTimeout != null && hookTimeout > 0
            ? hookTimeout * 1000L
            : DEFAULT_HTTP_HOOK_TIMEOUT_MS;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 返回结构 · 对齐 CC execHttpHook.ts:128-134 (5 字段)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * HTTP hook 执行结果 · 对齐 CC execHttpHook.ts:128-134 返回结构 (5 字段).
     *
     * <p>4 种返回路径 (CC :143/:226/:235/:240), 在 {@link #exec} 内直接构造 + WHY 注释:
     * <ul>
     *   <li>2xx 成功 (CC :226-230): ok=status∈[200,300), statusCode, body</li>
     *   <li>allowlist 拦截 (CC :143): ok=false, body="", error=msg</li>
     *   <li>超时/abort (CC :235): ok=false, body="", aborted=true</li>
     *   <li>其他错误 (CC :240): ok=false, body="", error=msg</li>
     * </ul>
     *
     * <p>[IMPL-10] DEL-EX-01: 执行标识字段已删除 — CC hooks.ts:2199 randomUUID 属分发层
     * 事件标识（emitHookStarted/emitHookResponse 关联），Java 事件层由 HookEventBus 承担，
     * 结果结构内无 CC 对应物。
     *
     * <p><b>不提供静态工厂</b>: record 组件 {@code ok} / {@code aborted} 会生成同名访问器
     * {@code ok()} / {@code aborted()}, 与同名静态工厂冲突 (Java 不允许仅返回类型不同的同名同参方法).
     * 直接构造 + 注释更简单 (规则二), 避免 naming clash.
     *
     * @param ok          CC original: ok (execHttpHook.ts:129)
     * @param statusCode  CC original: statusCode (execHttpHook.ts:130) · 可 null
     * @param body        CC original: body (execHttpHook.ts:131)
     * @param error       CC original: error (execHttpHook.ts:132) · 可 null
     * @param aborted     CC original: aborted (execHttpHook.ts:133)
     */
    public record HttpHookResult(
        boolean ok,
        Integer statusCode,
        String body,
        String error,
        boolean aborted
    ) {
    }
}

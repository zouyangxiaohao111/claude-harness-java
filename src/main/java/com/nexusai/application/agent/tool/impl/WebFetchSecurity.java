package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.application.agent.tool.impl.web.ProxySelectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebFetch SSRF 安全链 · 对齐 CC {@code Open-ClaudeCode/src/tools/WebFetchTool/utils.ts}
 * （validateURL / checkDomainBlocklist / isPermittedRedirect / getWithPermittedRedirects /
 * getURLMarkdownContent / 双缓存）。P0-3（IMP-H1）把 preapproved.ts + utils.ts 完整移植。
 *
 * <p><b>CC original 行号锚</b>（baseline {@code 1992306b}）：
 * <ul>
 *   <li>{@code MAX_URL_LENGTH=2000}（utils.ts:106）、{@code MAX_HTTP_CONTENT_LENGTH=10MB}（:112）、
 *       {@code FETCH_TIMEOUT_MS=60_000}（:116）、{@code DOMAIN_CHECK_TIMEOUT_MS=10_000}（:119）、
 *       {@code MAX_REDIRECTS=10}（:125）、{@code MAX_MARKDOWN_LENGTH=100_000}（:128）</li>
 *   <li>{@code isPreapprovedUrl}（:130-137）、{@code validateURL}（:139-169）</li>
 *   <li>{@code checkDomainBlocklist}（:176-203）+ {@code DOMAIN_CHECK_CACHE}（:75-78，max 128 / 5min，仅缓存 allowed）</li>
 *   <li>{@code isPermittedRedirect}（:212-243）</li>
 *   <li>{@code getWithPermittedRedirects}（:262-329，maxRedirects:0 + 手动跟随 + egress 403 拦截）</li>
 *   <li>{@code getURLMarkdownContent}（:347-482，validate → cache → http→https 升级 → blocklist → fetch → 内容）</li>
 *   <li>{@code URL_CACHE}（:66-69，TTL 15min / maxSize 50MB，按 content 字节计重）</li>
 * </ul>
 *
 * <p><b>SSRF 防御层次</b>（对齐 CC 实际 TS 行为，非注释）：
 * <ol>
 *   <li>{@link #validateURL}：拒绝 &gt;2000 字符 / 含 user:pass / hostname 段数 &lt;2（localhost 类单段）。</li>
 *   <li>{@link #checkDomainBlocklist}：域预检（端点经 settings.websearch_domain_check_url 配置，
 *       V39；默认跳过——用户 2026-08-23 拍板不依赖 api.anthropic.com，enterprise 可 skipWebFetchPreflight）。</li>
 *   <li>{@link #isPermittedRedirect} + {@link #getWithPermittedRedirects}：跨 host 重定向不静默跟随
 *       （maxRedirects:0 + 手动 isPermittedRedirect 判定，上限 10 跳）。</li>
 * </ol>
 *
 * <p><b>java.net.http 差异</b>: CC axios {@code maxRedirects:0} 等价 Java {@code Redirect.NEVER} +
 * 手动处理 301/302/307/308；{@code maxContentLength:10MB} 由本类在 Content-Length 头 + body 长度双检实现；
 * {@code response.statusText}（axios reason phrase）由 {@link #reasonPhrase} 按标准映射补齐（JDK 不暴露）。
 *
 * <p><b>线程安全</b>: Caffeine 缓存线程安全；方法仅操作局部状态 + 不可变配置。
 */
public class WebFetchSecurity {

    private static final Logger log = LoggerFactory.getLogger(WebFetchSecurity.class);

    // ════════════════════════════════════════════════════════════════════════
    // CC utils.ts 常量（baseline 1992306b）
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: MAX_URL_LENGTH=2000（utils.ts:106）。 */
    public static final int MAX_URL_LENGTH = 2000;
    /** CC original: MAX_HTTP_CONTENT_LENGTH=10MB（utils.ts:112）。 */
    public static final long MAX_HTTP_CONTENT_LENGTH = 10L * 1024 * 1024;
    /** CC original: FETCH_TIMEOUT_MS=60_000（utils.ts:116）。 */
    public static final long FETCH_TIMEOUT_MS = 60_000L;
    /** CC original: DOMAIN_CHECK_TIMEOUT_MS=10_000（utils.ts:119）。 */
    public static final long DOMAIN_CHECK_TIMEOUT_MS = 10_000L;
    /** CC original: MAX_REDIRECTS=10（utils.ts:125）。 */
    public static final int MAX_REDIRECTS = 10;
    /** CC original: MAX_MARKDOWN_LENGTH=100_000（utils.ts:128）。 */
    public static final int MAX_MARKDOWN_LENGTH = 100_000;

    /** CC original: CACHE_TTL_MS=15min（utils.ts:63）。 */
    private static final long CACHE_TTL_MS = 15L * 60 * 1000;
    /** CC original: MAX_CACHE_SIZE_BYTES=50MB（utils.ts:64）。 */
    private static final long MAX_CACHE_SIZE_BYTES = 50L * 1024 * 1024;
    /** CC original: DOMAIN_CHECK_CACHE max=128（utils.ts:76）。 */
    private static final int DOMAIN_CHECK_CACHE_MAX = 128;
    /** CC original: DOMAIN_CHECK_CACHE ttl=5min（utils.ts:77）。 */
    private static final long DOMAIN_CHECK_CACHE_TTL_MS = 5L * 60 * 1000;

    /** CC original: getWebFetchUserAgent（http.ts:56-58）→ {@code Claude-User (...; +https://support.anthropic.com/)}。 */
    public static final String USER_AGENT =
            "Claude-User (claude-code/nexusai-backend; +https://support.anthropic.com/)";

    /** 重定向状态码（CC getWithPermittedRedirects :287 只处理 301/302/307/308）。 */
    private static final java.util.Set<Integer> REDIRECT_STATUSES =
            java.util.Set.of(301, 302, 307, 308);

    /** URL 缓存 · CC original: URL_CACHE（utils.ts:66-69）。 */
    private final Cache<String, CacheEntry> urlCache;
    /** 域名预检缓存 · CC original: DOMAIN_CHECK_CACHE（utils.ts:75-78），仅缓存 allowed。 */
    private final Cache<String, Boolean> domainCheckCache;

    private final HttpClient httpClient;
    private final String domainCheckBaseUrl;
    private final boolean skipDomainCheck;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 默认配置（[websearch-domaincheck] 默认跳过域预检，不依赖 api.anthropic.com，用户 2026-08-23 拍板）：
     * {@code skipDomainCheck=true}、{@code domainCheckBaseUrl=null}、60s 超时、10MB 上限。
     * 预检端点经 settings.websearch_domain_check_url（V39）配置后由 {@link #withProxy(String, String)} 注入
     * （CC 默认执行预检 api.anthropic.com，Java 因中国网络不可达改为默认跳过——机制保留，默认值显式背离，规则七）。
     */
    public WebFetchSecurity() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DOMAIN_CHECK_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
                null,
                true);
    }

    /**
     * 可配置构造（测试 / 部署注入）。
     *
     * @param httpClient        手动跟随重定向的 HttpClient（{@code Redirect.NEVER}）
     * @param domainCheckBaseUrl 域预检端点前缀（null + {@code skipDomainCheck=true} → 跳过预检；
     *                           非空 → 预检该端点，CC 默认 api.anthropic.com 已不硬编码）
     * @param skipDomainCheck    是否跳过域预检（对齐 CC settings.skipWebFetchPreflight，enterprise 用）
     */
    public WebFetchSecurity(HttpClient httpClient, String domainCheckBaseUrl, boolean skipDomainCheck) {
        this.httpClient = httpClient;
        this.domainCheckBaseUrl = domainCheckBaseUrl;
        this.skipDomainCheck = skipDomainCheck;
        this.urlCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL_MS, TimeUnit.MILLISECONDS)
                .maximumWeight(MAX_CACHE_SIZE_BYTES)
                .weigher((String key, CacheEntry value) -> Math.max(1, value.content().length()))
                .build();
        this.domainCheckCache = Caffeine.newBuilder()
                .expireAfterWrite(DOMAIN_CHECK_CACHE_TTL_MS, TimeUnit.MILLISECONDS)
                .maximumSize(DOMAIN_CHECK_CACHE_MAX)
                .build();
    }

    /**
     * [websearch-resid R-A + websearch-domaincheck] 带 HTTP 代理 + 域预检端点配置的工厂。
     *
     * <p>镜像默认构造（{@code connectTimeout(DOMAIN_CHECK_TIMEOUT_MS) + Redirect.NEVER}，见
     * {@link #WebFetchSecurity()}），叠加 {@code ProxySelector} + 域预检端点。proxy 语义 {@code host:port}
     * （兼容 {@code http://} / {@code https://} 前缀）；null/blank → 直连（不设 ProxySelector）。
     * proxy 格式非法 → {@link ProxySelectors#parseProxySelector} warn + null（fail-loud，不中断抓取）。
     *
     * <p><b>域预检</b>：{@code domainCheckUrl} 非空 → 预检该端点（{@code skipDomainCheck=false}，
     * {@code checkDomainBlocklist} can_fetch JSON 语义不变）；{@code domainCheckUrl} 空/blank → 跳过预检
     * （{@code skipDomainCheck=true}，不依赖 api.anthropic.com，用户 2026-08-23 拍板）。端点来源为
     * settings.websearch_domain_check_url（V39 列），不再硬编码 Anthropic 私有服务。
     *
     * <p><b>SSRF 链零改动</b>：{@link #validateURL} / {@link #checkDomainBlocklist} /
     * {@link #isPermittedRedirect} / {@link #getURLMarkdownContent} / {@link #getWithPermittedRedirects}
     * 全部不动——仅替换 HttpClient 实例 + 预检端点来源。
     *
     * @param proxy HTTP 代理 {@code host:port}（null/blank → 直连；非法格式 → warn + 直连）
     * @param domainCheckUrl 域预检端点（null/blank → 跳过预检；非空 → 预检该端点）
     * @return 带 ProxySelector 的 WebFetchSecurity（proxy 空/非法 → 默认无代理实例）
     */
    public static WebFetchSecurity withProxy(String proxy, String domainCheckUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DOMAIN_CHECK_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector selector = ProxySelectors.parseProxySelector(proxy);
        if (selector != null) {
            builder.proxy(selector);
        }
        boolean skip = (domainCheckUrl == null || domainCheckUrl.isBlank());
        return new WebFetchSecurity(builder.build(), skip ? null : domainCheckUrl.trim(), skip);
    }

    /**
     * [websearch-resid R-A] 带 HTTP 代理的工厂（1-参保留，域预检默认跳过）· 用户「proxy 肯定接线」。
     *
     * <p>兼容旧调用（WebFetchToolTest withProxy 工厂用例直接调用）；域预检语义委托
     * {@link #withProxy(String, String)} 空端点 → 跳过预检（默认，不依赖 api.anthropic.com，
     * 用户 2026-08-23 拍板）。
     *
     * @param proxy HTTP 代理 {@code host:port}（null/blank → 直连；非法格式 → warn + 直连）
     * @return 带 ProxySelector 的 WebFetchSecurity（proxy 空/非法 → 默认无代理实例；域预检默认跳过）
     */
    public static WebFetchSecurity withProxy(String proxy) {
        return withProxy(proxy, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 缓存清理
    // ════════════════════════════════════════════════════════════════════════

    /** 清空双缓存 · CC original: clearWebFetchCache（utils.ts:80-83）。 */
    public void clearWebFetchCache() {
        urlCache.invalidateAll();
        domainCheckCache.invalidateAll();
    }

    // ════════════════════════════════════════════════════════════════════════
    // validateURL · CC utils.ts:139-169
    // ════════════════════════════════════════════════════════════════════════

    /**
     * URL 合法性 / SSRF 前置校验 · 对齐 CC {@code validateURL}（utils.ts:139-169）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code url.length() > MAX_URL_LENGTH(2000)} → false（utils.ts:140-142）</li>
     *   <li>解析失败 → false（utils.ts:144-149）</li>
     *   <li>含 user:pass → false（utils.ts:156-158）</li>
     *   <li>hostname 段数 &lt;2 → false（utils.ts:162-166，拦截 localhost 单段）</li>
     * </ul>
     * 协议不做白名单（CC :151-152 注释：发起请求时会 http→https 升级）。
     *
     * @param url 目标 URL
     * @return true = 通过前置校验
     */
    public static boolean validateURL(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // 仅 http/https 有 host；其他 scheme（ftp/file 等）URI.create 不抛但无 host → 拦截
        String hostname = parsed.getHost();
        if (hostname == null) {
            return false;
        }
        // user:pass（CC :156-158）
        if (parsed.getUserInfo() != null && !parsed.getUserInfo().isEmpty()) {
            return false;
        }
        // hostname 段数 >= 2（CC :162-166）
        String[] parts = hostname.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // checkDomainBlocklist · CC utils.ts:176-203
    // ════════════════════════════════════════════════════════════════════════

    /** 域预检结果 · CC original: DomainCheckResult（utils.ts:171-174）。 */
    public enum DomainCheckResult {
        /** 允许抓取（can_fetch === true）。 */
        ALLOWED,
        /** 阻断（can_fetch !== true）。 */
        BLOCKED,
        /** 预检失败（网络/非 200/解析失败）→ 上层 fail-closed。 */
        CHECK_FAILED
    }

    /**
     * 域预检 · 对齐 CC {@code checkDomainBlocklist(domain)}（utils.ts:176-203）。
     *
     * <p>缓存语义：仅 {@code ALLOWED} 缓存（5min / max 128），blocked/check_failed 下次重查
     * （CC :75-78 注释 "Only 'allowed' is cached — blocked/failed re-check on next attempt"）。
     *
     * <p><b>skipDomainCheck</b>: 对齐 CC {@code settings.skipWebFetchPreflight}（utils.ts:383-398
     * getURLMarkdownContent 内 {@code if (!settings.skipWebFetchPreflight)}），enterprise 无法访问
     * claude.ai 时跳过预检。默认 true（跳过预检——用户 2026-08-23 拍板不依赖 api.anthropic.com，
     * CC 默认 false 执行预检，机制保留仅默认值背离）；预检端点经 settings.websearch_domain_check_url
     * 配置后由 {@link #withProxy(String, String)} / 3-参构造注入 false 恢复预检。
     *
     * @param domain 目标 hostname
     * @return 预检结果
     */
    public DomainCheckResult checkDomainBlocklist(String domain) {
        if (skipDomainCheck) {
            return DomainCheckResult.ALLOWED;
        }
        if (domain == null || domain.isBlank()) {
            return DomainCheckResult.CHECK_FAILED;
        }
        Boolean cached = domainCheckCache.getIfPresent(domain);
        if (cached != null) {
            return DomainCheckResult.ALLOWED;
        }
        try {
            String encoded = URLEncoder.encode(domain, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(domainCheckBaseUrl + "?domain=" + encoded))
                    .timeout(Duration.ofMillis(DOMAIN_CHECK_TIMEOUT_MS))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode body = MAPPER.readTree(resp.body());
                if (body.path("can_fetch").asBoolean(false)) {
                    domainCheckCache.put(domain, Boolean.TRUE);
                    return DomainCheckResult.ALLOWED;
                }
                return DomainCheckResult.BLOCKED;
            }
            if (log.isDebugEnabled()) {
                log.debug("WebFetchSecurity 域预检非 200: domain={} status={}", domain, resp.statusCode());
            }
            return DomainCheckResult.CHECK_FAILED;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (log.isDebugEnabled()) {
                log.debug("WebFetchSecurity 域预检失败: domain={} err={}", domain, e.getMessage());
            }
            return DomainCheckResult.CHECK_FAILED;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("WebFetchSecurity 域预检异常: domain={} err={}", domain, e.getMessage());
            }
            return DomainCheckResult.CHECK_FAILED;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // isPermittedRedirect · CC utils.ts:212-243
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 重定向是否允许跟随 · 对齐 CC {@code isPermittedRedirect(originalUrl, redirectUrl)}
     * （utils.ts:212-243）。
     *
     * <p>允许条件：协议相同 + 端口相同 + redirect 无 user:pass + 去 www 前缀后 hostname 相同
     * （CC :216-242）。任意解析失败 → false（fail-closed）。
     *
     * @param originalUrl 原始 URL
     * @param redirectUrl 重定向目标 URL
     * @return true = 同源（www 变体）可跟随
     */
    public static boolean isPermittedRedirect(String originalUrl, String redirectUrl) {
        try {
            URI parsedOriginal = URI.create(originalUrl);
            URI parsedRedirect = URI.create(redirectUrl);
            if (!parsedRedirect.getScheme().equals(parsedOriginal.getScheme())) {
                return false;
            }
            if (parsedRedirect.getPort() != parsedOriginal.getPort()) {
                return false;
            }
            if (parsedRedirect.getUserInfo() != null && !parsedRedirect.getUserInfo().isEmpty()) {
                return false;
            }
            // CC :236-239 stripWww 后 hostname 相等
            String origHost = stripWww(parsedOriginal.getHost());
            String redirectHost = stripWww(parsedRedirect.getHost());
            return origHost != null && origHost.equals(redirectHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripWww(String hostname) {
        if (hostname == null) {
            return null;
        }
        return hostname.startsWith("www.") ? hostname.substring(4) : hostname;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 抓取结果类型
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 已抓取内容 · CC original: FetchedContent（utils.ts:337-345，含 persistedPath/persistedSize）。
     *
     * @param content       内容（markdown/原始 utf-8 文本）
     * @param bytes         原始 body 字节数
     * @param code          HTTP 状态码
     * @param codeText      HTTP 状态文本（reason phrase）
     * @param contentType   Content-Type 头
     * @param persistedPath 二进制内容落盘路径（非二进制 → null；[G20③] CC utils.ts:440-449）
     * @param persistedSize 落盘文件字节数（非二进制 → null）
     */
    public record FetchedContent(String content, long bytes, int code, String codeText,
                                 String contentType, String persistedPath, Long persistedSize) {
    }

    /**
     * 重定向信息（未跟随，需用户/模型再次发起）· CC original: RedirectInfo（utils.ts:255-260）。
     *
     * @param originalUrl 原 URL
     * @param redirectUrl 重定向目标 URL
     * @param statusCode  重定向状态码
     */
    public record RedirectInfo(String originalUrl, String redirectUrl, int statusCode) {
    }

    /** URL_CACHE 条目 · CC original: CacheEntry（utils.ts:51-59，含 persistedPath/persistedSize）。 */
    private record CacheEntry(String content, long bytes, int code, String codeText,
                              String contentType, String persistedPath, Long persistedSize) {
        FetchedContent toFetched() {
            return new FetchedContent(content, bytes, code, codeText, contentType, persistedPath, persistedSize);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 抓取主入口 · CC getURLMarkdownContent (utils.ts:347-482)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 抓取 URL 并返回内容或重定向信息 · 对齐 CC {@code getURLMarkdownContent(url, abortController)}
     * （utils.ts:347-482）。
     *
     * <p>流程：validateURL → URL_CACHE → http→https 升级 → 域预检（unless skipWebFetchPreflight）
     * → getWithPermittedRedirects → 内容解码。返回 {@link RedirectInfo} 表示重定向未跟随
     * （上层 {@link WebFetchTool} 构造 REDIRECT DETECTED 提示）。
     *
     * @param url 目标 URL（原始，未升级）
     * @return FetchedContent 或 RedirectInfo
     * @throws IllegalArgumentException   validateURL 失败（"Invalid URL"）
     * @throws DomainBlockedException     域被预检阻断
     * @throws DomainCheckFailedException 域预检失败
     */
    public Object getURLMarkdownContent(String url) {
        if (!validateURL(url)) {
            throw new IllegalArgumentException("Invalid URL");
        }
        CacheEntry cached = urlCache.getIfPresent(url);
        if (cached != null) {
            return cached.toFetched();
        }

        String upgradedUrl = url;
        URI parsedUrl;
        try {
            parsedUrl = URI.create(url);
            // http→https 升级（CC :376-379）
            if ("http".equals(parsedUrl.getScheme())) {
                upgradedUrl = "https" + url.substring(4);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String hostname = parsedUrl.getHost();
        if (!skipDomainCheck) {
            DomainCheckResult checkResult = checkDomainBlocklist(hostname);
            switch (checkResult) {
                case ALLOWED -> {
                    // 继续抓取
                }
                case BLOCKED -> throw new DomainBlockedException(hostname);
                case CHECK_FAILED -> throw new DomainCheckFailedException(hostname);
            }
        }

        Object fetchResult = getWithPermittedRedirects(upgradedUrl, isPermittedRedirectPredicate(), 0);
        if (fetchResult instanceof RedirectInfo ri) {
            return ri;
        }
        HttpResponse<byte[]> resp = (HttpResponse<byte[]>) fetchResult;
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        long bytes = body.length;
        String content = new String(body, StandardCharsets.UTF_8);
        int code = resp.statusCode();
        String codeText = reasonPhrase(code);

        // [G20③] 二进制内容落盘 · 对齐 CC utils.ts:435-449（isBinaryContentType → persistBinaryContent）。
        // 落盘文件为补充（supplement），utf-8 解码内容仍继续走下方结果路径（CC :437-439 注释）。
        String persistedPath = null;
        Long persistedSize = null;
        if (isBinaryContentType(contentType)) {
            String persistId = "webfetch-" + System.currentTimeMillis() + "-"
                    + java.util.UUID.randomUUID().toString().substring(0, 6);
            java.nio.file.Path file = persistBinaryContent(body, contentType, persistId);
            if (file != null) {
                persistedPath = file.toString();
                persistedSize = bytes;
            }
        }

        // 缓存（CC :469-480 存原始 url 而非升级/重定向后的 url）
        CacheEntry entry = new CacheEntry(content, bytes, code, codeText, contentType, persistedPath, persistedSize);
        urlCache.put(url, entry);
        if (log.isDebugEnabled()) {
            log.debug("WebFetchSecurity 抓取完成: url={} code={} bytes={} contentType={} persisted={}",
                url, code, bytes, contentType, persistedPath);
        }
        return entry.toFetched();
    }

    /**
     * 带重定向管控的抓取 · 对齐 CC {@code getWithPermittedRedirects(url, signal, redirectChecker, depth)}
     * （utils.ts:262-329）。
     *
     * <p>Java 侧 {@code maxRedirects:0} 由 HttpClient {@code Redirect.NEVER} 表达，手动处理
     * 301/302/307/308：读取 Location（相对 URL 按原 URL 解析），redirectChecker 通过则递归跟随
     * （depth+1，上限 {@link #MAX_REDIRECTS}），否则返回 {@link RedirectInfo}（不跟随）。
     * 403 + {@code x-proxy-error: blocked-by-allowlist} → {@link EgressBlockedException}。
     *
     * @param url            目标 URL
     * @param redirectChecker (originalUrl, redirectUrl) → boolean
     * @param depth          当前重定向深度
     * @return {@link HttpResponse}{@code <byte[]>} 或 {@link RedirectInfo}
     */
    public Object getWithPermittedRedirects(String url,
                                            java.util.function.BiPredicate<String, String> redirectChecker,
                                            int depth) {
        if (depth > MAX_REDIRECTS) {
            throw new IllegalArgumentException("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
                    .header("Accept", "text/markdown, text/html, */*")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            if (REDIRECT_STATUSES.contains(status)) {
                String location = resp.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new IllegalArgumentException("Redirect missing Location header");
                }
                URI redirectUri = URI.create(url).resolve(location);
                String redirectUrl = redirectUri.toString();
                if (redirectChecker.test(url, redirectUrl)) {
                    return getWithPermittedRedirects(redirectUrl, redirectChecker, depth + 1);
                }
                return new RedirectInfo(url, redirectUrl, status);
            }
            // egress 代理拦截（CC :318-325）
            if (status == 403 && "blocked-by-allowlist".equalsIgnoreCase(
                    resp.headers().firstValue("x-proxy-error").orElse(""))) {
                String hostname = URI.create(url).getHost();
                throw new EgressBlockedException(hostname);
            }
            // maxContentLength 10MB 双检（CC axios maxContentLength）
            long declared = resp.headers().firstValue("Content-Length")
                    .map(l -> {
                        try {
                            return Long.parseLong(l);
                        } catch (NumberFormatException e) {
                            return -1L;
                        }
                    }).orElse(-1L);
            if (declared > MAX_HTTP_CONTENT_LENGTH) {
                throw new IllegalArgumentException("Response too large (max " + MAX_HTTP_CONTENT_LENGTH + " bytes)");
            }
            byte[] body = resp.body();
            if (body != null && body.length > MAX_HTTP_CONTENT_LENGTH) {
                throw new IllegalArgumentException("Response too large (max " + MAX_HTTP_CONTENT_LENGTH + " bytes)");
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("fetch failed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // 已检查异常（Redirect missing Location / Too many redirects / maxContentLength）
            throw e;
        }
    }

    private java.util.function.BiPredicate<String, String> isPermittedRedirectPredicate() {
        return WebFetchSecurity::isPermittedRedirect;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [G20③] 二进制内容落盘 · 对齐 CC mcpOutputStorage.ts（isBinaryContentType /
    // persistBinaryContent / extensionForMimeType，utils.ts:442-449 消费）
    // ════════════════════════════════════════════════════════════════════════

    /** 二进制内容落盘目录 · Java 全局 scratch 目录（CC getToolResultsDir() 的 Java 等价）。 */
    private static final java.nio.file.Path BINARY_PERSIST_DIR =
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "nexusai-webfetch");

    /**
     * content-type 是否二进制 · 对齐 CC mcpOutputStorage.ts:125-136 isBinaryContentType。
     *
     * <p>text/*、json、xml、form data 视为非二进制（可入模型上下文）；其余（pdf、octet-stream、
     * openxmlformats 等）视为二进制 → 落盘。
     */
    static boolean isBinaryContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String mt = (contentType.split(";")[0]).trim().toLowerCase();
        if (mt.startsWith("text/")) {
            return false;
        }
        if (mt.endsWith("+json") || "application/json".equals(mt)) {
            return false;
        }
        if (mt.endsWith("+xml") || "application/xml".equals(mt)) {
            return false;
        }
        if (mt.startsWith("application/javascript")) {
            return false;
        }
        if ("application/x-www-form-urlencoded".equals(mt)) {
            return false;
        }
        return true;
    }

    /**
     * mime → 扩展名 · 对齐 CC {@code mcpOutputStorage.ts:66-118 extensionForMimeType}
     * 逐 case 一致（自验 2026-08-22：含 application/msword→doc、application/vnd.ms-excel→xls、
     * audio/mpeg→mp3、audio/wav→wav、audio/ogg→ogg、video/mp4→mp4、video/webm→webm；
     * Java 旧实现额外添加的 gz/tar/x-json/xml/bmp 已移除——CC 无这些映射，未知类型统一 bin）。
     */
    private static String extensionForMimeType(String mimeType) {
        if (mimeType == null) {
            return "bin";
        }
        String mt = (mimeType.split(";")[0]).trim().toLowerCase();
        return switch (mt) {
            case "application/pdf" -> "pdf";
            case "application/json" -> "json";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "text/markdown" -> "md";
            case "application/zip" -> "zip";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/msword" -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }

    /**
     * 二进制字节落盘 · 对齐 CC mcpOutputStorage.ts:148-174 persistBinaryContent。
     *
     * <p>写原始字节（非 stringify）到 {@code {tmpdir}/nexusai-webfetch/{persistId}.{ext}}，
     * 供 Read 等原生工具打开。失败 → 返回 null（best-effort，不阻断抓取主链）。
     *
     * @return 落盘文件路径；失败返回 null
     */
    static java.nio.file.Path persistBinaryContent(byte[] bytes, String mimeType, String persistId) {
        try {
            java.nio.file.Files.createDirectories(BINARY_PERSIST_DIR);
            String ext = extensionForMimeType(mimeType);
            java.nio.file.Path file = BINARY_PERSIST_DIR.resolve(persistId + "." + ext);
            java.nio.file.Files.write(file, bytes);
            return file;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("WebFetchSecurity 二进制落盘失败（best-effort 跳过）: persistId={} mime={} err={}",
                    persistId, mimeType, e.toString());
            }
            return null;
        }
    }

    /** HTTP 状态文本（axios reason phrase 等价）· 标准映射，缺省回退数字。 */
    public static String reasonPhrase(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 408 -> "Request Timeout";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> String.valueOf(code);
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // 自定义异常 · CC utils.ts:21-48
    // ════════════════════════════════════════════════════════════════════════

    /** 域被阻断 · CC original: DomainBlockedError（utils.ts:21-26）。 */
    public static class DomainBlockedException extends RuntimeException {
        public DomainBlockedException(String domain) {
            super("NexusAI is unable to fetch from " + domain);
        }
    }

    /** 域预检失败 · CC original: DomainCheckFailedError（utils.ts:28-35）。 */
    public static class DomainCheckFailedException extends RuntimeException {
        public DomainCheckFailedException(String domain) {
            super("Unable to verify if domain " + domain
                    + " is safe to fetch. This may be due to network restrictions or enterprise security policies blocking claude.ai.");
        }
    }

    /** egress 代理拦截 · CC original: EgressBlockedError（utils.ts:37-48，JSON 载荷）。 */
    public static class EgressBlockedException extends RuntimeException {
        public EgressBlockedException(String domain) {
            super("{\"error_type\":\"EGRESS_BLOCKED\",\"domain\":\"" + domain
                    + "\",\"message\":\"Access to " + domain
                    + " is blocked by the network egress proxy.\"}");
        }
    }
}

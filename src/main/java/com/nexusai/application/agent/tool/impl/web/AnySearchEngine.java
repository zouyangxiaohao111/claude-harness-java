package com.nexusai.application.agent.tool.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.impl.WebFetchSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * anysearch 引擎 · 决策清单 组 4-1 拍板引入（参考 javaclawbot AnySearchEngine 策略实现）。
 *
 * <p><b>API 契约</b>（anysearch-ai 官方文档，外部后端，见 {@code 09-open-decisions} 登记）：
 * <ul>
 *   <li>端点：{@code POST {baseUrl}/v1/search}，JSON body
 *       {@code {query, max_results, allowed_domains?, blocked_domains?}}（匿名可用，低速率）。</li>
 *   <li>认证：{@code Authorization: Bearer <API_KEY>}（可选，配置为空则不带头）。</li>
 *   <li>响应：顶层 {@code results[]} 数组，每项含 {@code title}/{@code url}（与 CC
 *       {@code searchHitSchema} {@code {title, url}} 对齐）。</li>
 * </ul>
 *
 * <p><b>CC 对齐</b>：命中输出对齐 {@code searchHitSchema {title, url}}（WebSearchTool.ts:43-46）；
 * 域过滤不做客户端后置（D-TR-H1-08），allowed/blocked 透传到后端 body 由 API 处理。
 * 输入 schema 无 max_results（D-TR-H1-06）；内部取 {@link #ANYSEARCH_MAX_RESULTS} 条。
 */
public class AnySearchEngine implements SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(AnySearchEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认 anysearch API base URL（可经 DB settings.websearch_base_url 覆盖，WebSearchTool readBaseUrl）。 */
    public static final String DEFAULT_BASE_URL = "https://api.anysearch.com";

    /** 搜索路径。 */
    static final String SEARCH_PATH = "/v1/search";

    /** 请求后端的结果条数上限（anysearch API 允许 1-10；facade 再统一截断）。 */
    static final int ANYSEARCH_MAX_RESULTS = 8;

    /** 连接/请求超时 30s。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 默认 HttpClient 构造（直连，无代理）。
     *
     * @param baseUrl anysearch API base URL（null/blank → {@link #DEFAULT_BASE_URL}）
     * @param apiKey  可选 Bearer API key（null/blank → 不携带 Authorization 头）
     */
    public AnySearchEngine(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null, null);
    }

    /**
     * 可注入 HttpClient 构造（测试 mock / 部署自定义；直连，无代理）。
     *
     * @param baseUrl    anysearch API base URL
     * @param apiKey     Bearer API key
     * @param httpClient 请求 HttpClient（可为 null → 用默认）
     */
    public AnySearchEngine(String baseUrl, String apiKey, HttpClient httpClient) {
        this(baseUrl, apiKey, null, httpClient);
    }

    /**
     * 完整构造 · [websearch-ccalign T4 拍板] proxy 参数（HttpClient {@link ProxySelector}）。
     *
     * <p>proxy 为 {@code host:port} 字符串（DB settings.proxy 承载；兼容 {@code http://host:port} 前缀）；
     * null/blank → 直连（不设 ProxySelector）。外部注入的 {@code httpClient} 非 null 时优先使用
     * （测试/部署自定义），proxy 仅在自建 HttpClient 时生效。
     *
     * @param baseUrl    anysearch API base URL（null/blank → {@link #DEFAULT_BASE_URL}）
     * @param apiKey     Bearer API key（null/blank → 不携带 Authorization 头）
     * @param proxy      HTTP 代理 {@code host:port}（null/blank → 直连；非法格式 → warn + 直连）
     * @param httpClient 请求 HttpClient（可为 null → 按 proxy 自建默认）
     */
    public AnySearchEngine(String baseUrl, String apiKey, String proxy, HttpClient httpClient) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.httpClient = httpClient != null ? httpClient : buildHttpClient(proxy);
    }

    /** 按 proxy 自建默认 HttpClient（null/blank → 直连；非法格式 → warn + 直连，fail-loud 不静默）。 */
    private static HttpClient buildHttpClient(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector selector = ProxySelectors.parseProxySelector(proxy);
        if (selector != null) {
            builder.proxy(selector);
        }
        return builder.build();
    }

    @Override
    public String name() {
        return "anysearch";
    }

    @Override
    public List<SearchHit> search(SearchRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", request.query());
        body.put("max_results", ANYSEARCH_MAX_RESULTS);
        if (!request.allowedDomains().isEmpty()) {
            ArrayNode allowed = body.putArray("allowed_domains");
            request.allowedDomains().forEach(allowed::add);
        }
        if (!request.blockedDomains().isEmpty()) {
            ArrayNode blocked = body.putArray("blocked_domains");
            request.blockedDomains().forEach(blocked::add);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + SEARCH_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", WebFetchSecurity.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[AnySearchEngine] HTTP 非 200: status={} baseUrl={}", resp.statusCode(), baseUrl);
                throw new RuntimeException("anysearch HTTP " + resp.statusCode());
            }
            JsonNode data = MAPPER.readTree(resp.body());
            JsonNode results = data.path("results");
            List<SearchHit> hits = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode r : results) {
                    String title = r.path("title").asText("");
                    String url = r.path("url").asText("");
                    if (!title.isBlank() && !url.isBlank()) {
                        hits.add(new SearchHit(title, url));
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("AnySearchEngine 请求完成: query='{}' hits={}", request.query(), hits.size());
            }
            return hits;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("anysearch request interrupted", e);
        } catch (Exception e) {
            log.warn("[AnySearchEngine] 请求失败: query='{}' baseUrl={} err={}",
                    request.query(), baseUrl, e.getMessage());
            throw new RuntimeException("anysearch request failed: " + e.getMessage(), e);
        }
    }
}

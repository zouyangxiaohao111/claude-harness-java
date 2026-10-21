package com.nexusai.application.agent.tool.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * DeepSeek 搜索 引擎 · 接入 {@code POST https://api.deepseek.com/v1/tools/search}。
 *
 * <p>API 契约（DeepSeek 官方「网页搜索与抓取」指南）：
 * <ul>
 *   <li>端点：{@code POST {baseUrl}/v1/tools/search}，Bearer 鉴权（与 DeepSeek 模型同一 API Key）。</li>
 *   <li>请求：{@code {query, count(1~50), ori_question?, localization?, need_content?}}。</li>
 *   <li>响应：{@code {results: [{title, url, snippet, summary, content, rerank_score, ...}]}}；
 *       本引擎只取 {@code title}/{@code url}（对齐 CC {@code searchHitSchema}）。</li>
 * </ul>
 *
 * <p>搜索语法（site:/lang:/after:/before:/"精确短语"/-排除）原样透传 query，由 DeepSeek 引擎处理。
 *
 * <p>选择方式：DB settings {@code websearch_engine = "deepseek"}（前端设置页可选）。
 */
public class DeepSeekSearchEngine implements SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认 DeepSeek API base（可经 DB settings.websearch_base_url 覆盖）。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** 搜索路径。 */
    static final String SEARCH_PATH = "/v1/tools/search";

    /** 单次返回条数（facade 再统一截断到 DEFAULT_RESULTS）。 */
    static final int MAX_RESULTS = 8;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 默认构造（直连）。
     *
     * @param baseUrl DeepSeek API base（null/blank → {@link #DEFAULT_BASE_URL}）
     * @param apiKey  DeepSeek API Key（null/blank → 不携带 Authorization，请求将 401）
     */
    public DeepSeekSearchEngine(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null, null);
    }

    /**
     * 完整构造（proxy / 注入 HttpClient）。
     *
     * @param baseUrl    DeepSeek API base
     * @param apiKey     DeepSeek API Key
     * @param proxy      HTTP 代理 {@code host:port}（null/blank → 直连）
     * @param httpClient 注入的 HttpClient（非 null 优先，忽略 proxy）
     */
    public DeepSeekSearchEngine(String baseUrl, String apiKey, String proxy, HttpClient httpClient) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.httpClient = httpClient != null ? httpClient : buildHttpClient(proxy);
    }

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
        return "deepseek";
    }

    @Override
    public List<SearchHit> search(SearchRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", request.query());
        // 模型可自行决定返回条数（1~50，缺省 MAX_RESULTS）
        int count = request.count() == null ? MAX_RESULTS : Math.max(1, Math.min(50, request.count()));
        body.put("count", count);
        // 模型可自行决定是否要正文（缺省 false：只消费 title/url，正文通常交给 WebFetch）
        body.put("need_content", Boolean.TRUE.equals(request.needContent()));
        // 触发搜索的用户原始问题（有助理解意图；空则不传）
        if (request.oriQuestion() != null && !request.oriQuestion().isBlank()) {
            body.put("ori_question", request.oriQuestion());
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(stripTrailingSlash(baseUrl) + SEARCH_PATH))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (!apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("DeepSeek search HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body()));
            }
            JsonNode root = MAPPER.readTree(resp.body());
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode r : root.path("results")) {
                String url = r.path("url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                hits.add(new SearchHit(r.path("title").asText(""), url));
            }
            log.debug("[DeepSeekSearchEngine] query='{}' hits={}", request.query(), hits.size());
            return hits;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek search interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("DeepSeek search failed: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}

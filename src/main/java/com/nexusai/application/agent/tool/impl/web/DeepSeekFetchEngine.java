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

/**
 * DeepSeek 网页抓取 引擎 · 接入 {@code POST https://api.deepseek.com/v1/tools/fetch}。
 *
 * <p>API 契约（DeepSeek 官方「网页搜索与抓取」指南）：
 * <ul>
 *   <li>请求：{@code {"url": "https://..."}}（≤8192 字符，必须含 host 的 http/https，不允许
 *       用户名/密码）。</li>
 *   <li>响应：{@code {"title": "...", "content": "# Markdown 正文"}}；content 缺失时为空串。</li>
 *   <li>错误：OpenAI 兼容 {@code {error:{message,type,param,code}}}；408/429/5xx 可重试。</li>
 * </ul>
 *
 * <p>选择方式：DB settings {@code webfetch_engine = "deepseek"}（前端设置页可选）。
 * 抓取在 DeepSeek 服务端完成，本机不发起外连（无 SSRF 面）。
 */
public class DeepSeekFetchEngine {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekFetchEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认 DeepSeek API base（可经 DB settings.websearch_base_url 覆盖）。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** 抓取路径。 */
    static final String FETCH_PATH = "/v1/tools/fetch";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 抓取结果。
     *
     * @param title   页面标题（缺失为空串）
     * @param content Markdown 正文
     */
    public record Page(String title, String content) {
    }

    public DeepSeekFetchEngine(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null, null);
    }

    public DeepSeekFetchEngine(String baseUrl, String apiKey, String proxy, HttpClient httpClient) {
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

    /**
     * 抓取单页，返回标题 + Markdown 正文。
     *
     * @param url 目标 URL（http/https，含 host）
     * @return 抓取结果（content 可能为空串）
     * @throws IllegalStateException 网络/HTTP 错误（携带状态码与错误 message）
     */
    public Page fetch(String url) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("url", url);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(stripTrailingSlash(baseUrl) + FETCH_PATH))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (!apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("DeepSeek fetch HTTP " + resp.statusCode()
                        + ": " + extractError(resp.body()));
            }
            JsonNode root = MAPPER.readTree(resp.body());
            String title = root.path("title").asText("");
            String content = root.path("content").asText("");
            log.debug("[DeepSeekFetchEngine] url={} title='{}' contentLength={}", url, title, content.length());
            return new Page(title, content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek fetch interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("DeepSeek fetch failed: " + e.getMessage(), e);
        }
    }

    /** 从 OpenAI 兼容错误体提取 message（解析失败退回原文截断）。 */
    private static String extractError(String body) {
        try {
            JsonNode err = MAPPER.readTree(body).path("error").path("message");
            if (!err.isMissingNode() && !err.asText("").isBlank()) {
                return err.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体 → 原文截断
        }
        return body != null && body.length() > 300 ? body.substring(0, 300) + "…" : String.valueOf(body);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

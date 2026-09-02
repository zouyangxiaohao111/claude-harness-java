package com.nexusai.application.agent.tool.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.WebFetchTool;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DuckDuckGo HTML 抓取引擎（可配置引擎，非默认——缺省引擎为 anysearch）· 保留自旧
 * WebSearchTool 抓取实现，改造为可配置策略（D-TR-H1-05：DuckDuckGo 保留但可配置，
 * engine 字段切换 anysearch/duckduckgo；DB settings.websearchEngine 缺省 anysearch，
 * T4 对齐 CC anysearch 后端，见 {@link com.nexusai.application.agent.tool.impl.WebSearchTool#resolveEngine}）。
 *
 * <p><b>与旧实现的差异（IMP-H2）</b>：
 * <ul>
 *   <li>输出命中对齐 CC {@code searchHitSchema} {@code {title, url}}（WebSearchTool.ts:43-46），
 *       snippet 字段删除（D-TR-H1-07，CC 输出无 snippet）。</li>
 *   <li>客户端域过滤删除（D-TR-H1-08：CC 域过滤交给 API server tool 参数）；DuckDuckGo HTML
 *       端点不支持 allowed/blocked 域参数 → 忽略请求的域字段，不做客户端后置过滤。</li>
 *   <li>max_results 输入参数删除（D-TR-H1-06）；结果条数上限由 facade 内部常量施加
 *       （{@link com.nexusai.application.agent.tool.impl.WebSearchTool#DEFAULT_RESULTS}）。</li>
 * </ul>
 *
 * <p>抓取经 {@link WebFetchTool}（IMP-H1 SSRF 安全链 + 60s 超时 + Claude-User UA），从新输出契约
 * {@code {bytes, code, codeText, result, durationMs, url}} 提取 {@code result} 字段（HTML）。
 */
public class DuckDuckGoEngine implements SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** DuckDuckGo HTML 端点（教学 fallback，无 API key）。 */
    static final String DUCKDUCKGO_HTML_URL = "https://html.duckduckgo.com/html/?q=";

    private final WebFetchTool webFetch;

    public DuckDuckGoEngine(WebFetchTool webFetch) {
        this.webFetch = webFetch;
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public List<SearchHit> search(SearchRequest request) {
        String encodedQuery = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String url = DUCKDUCKGO_HTML_URL + encodedQuery;
        String html = fetch(url);
        List<SearchHit> hits = parseDuckDuckGoHtml(html);
        if (log.isDebugEnabled()) {
            log.debug("DuckDuckGoEngine 抓取完成: query='{}' hits={}", request.query(), hits.size());
        }
        return hits;
    }

    /**
     * 经 WebFetchTool 抓取 DuckDuckGo HTML 并返回结果文本。
     *
     * <p>IMP-H1 依赖同步：WebFetchTool 已删 max_bytes（D-TR-H1-01），输出契约改
     * {@code {bytes, code, codeText, result, durationMs, url}}——本方法只传 url+prompt，
     * 从 {@code result} 字段取 HTML。
     */
    private String fetch(String url) {
        ToolUseBlock fetchCall = new ToolUseBlock("ws-fetch-" + System.nanoTime(), WebFetchTool.NAME,
                JsonNodeFactory.instance.objectNode().put("url", url).put("prompt",
                        "Extract the raw HTML so it can be parsed for search results."));
        AgentToolResult<?> fetchResult = webFetch.execute(fetchCall, (ToolUseContext) null);
        String payload = fetchResult.data() instanceof String s
                ? s
                : String.valueOf(fetchResult.data());
        try {
            JsonNode parsed = MAPPER.readTree(payload);
            JsonNode result = parsed.get("result");
            if (result != null && result.isTextual()) {
                return result.asText();
            }
            // 契约 JSON 但无 result 字段（如 REDIRECT DETECTED / 异常形状）→ fail-loud
            throw new RuntimeException("webfetch result missing 'result' field for: " + url);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 非 JSON（异常路径，如 SSRF 阻断消息）→ 原样作为错误抛出
            throw new RuntimeException("webfetch error: " + payload, e);
        }
    }

    /** DuckDuckGo HTML 结果解析 · 对齐 CC searchHitSchema {title, url}。 */
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.DOTALL);

    /**
     * 解析 DuckDuckGo HTML 命中（title + url）。
     *
     * <p>snippet 不再解析/输出（D-TR-H1-07：CC 输出 {query, results, durationSeconds}，
     * 命中仅 {title, url}）。不做条数上限（上限由 facade 内部常量施加）。
     */
    static List<SearchHit> parseDuckDuckGoHtml(String html) {
        List<SearchHit> results = new ArrayList<>();
        if (html == null) {
            return results;
        }
        Matcher rm = RESULT_PATTERN.matcher(html);
        while (rm.find()) {
            String url = rm.group(1).trim();
            String title = rm.group(2).trim();
            if (!title.isEmpty() && !url.isEmpty()) {
                results.add(new SearchHit(title, url));
            }
        }
        return results;
    }
}

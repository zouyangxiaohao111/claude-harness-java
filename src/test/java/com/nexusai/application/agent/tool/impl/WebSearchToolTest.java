package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.web.AnySearchEngine;
import com.nexusai.application.agent.tool.impl.web.DuckDuckGoEngine;
import com.nexusai.application.agent.tool.impl.web.SearchEngine;
import com.nexusai.application.agent.tool.impl.web.SearchEngine.SearchHit;
import com.nexusai.application.agent.tool.impl.web.SearchEngine.SearchRequest;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.eventbus.ws.WebSearchResultEvent;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.session.entity.WebSearchResultRecord;
import com.nexusai.repository.session.mapper.WebSearchResultMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMP-H2 + [G19/G20] + [websearch-ccalign 2026-08-23] · WebSearch anysearch 引擎策略模式
 * + 注释模型二次总结 + CC 输出形状测试（组 4-1）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>: 本测试锁定的是 WebSearchTool 对齐的<b>可观察契约</b>：
 * <ol>
 *   <li><b>输出契约</b>：{@code {query, results[], durationSeconds}}——[websearch-ccalign T2] 对齐 CC
 *       outputSchema（WebSearchTool.ts:56-67）：results 数组首项 = hits 块
 *       {@code {tool_use_id, content: [{title,url}...]}}（CC searchResultSchema :42-52），末项 = 注释模型
 *       文本注释（CC makeOutputFromSearchResponse textAcc → string 项 :103-143）。summary 顶层字段已删除。</li>
 *   <li><b>注释模型链路</b>：[websearch-ccalign T3] 对齐 CC WebSearchTool.ts:262-280
 *       {@code useHaiku ? getSmallFastModel() : mainLoopModel}——DB settings.websearchUseSmallModel
 *       （CC tengu_plum_vx3）= true → fast 档；false/缺省 → 主循环模型（AgentState.currentModel 经
 *       SessionAgentStateRegistry）；主循环不可得 → 回落 fast 档。</li>
 *   <li><b>引擎配置入 DB</b>：[websearch-ccalign T4] websearch_engine/api_key/proxy 走 DB settings
 *       （缺省 anysearch；api_key 空 → 内置默认兜底；proxy 空 → 直连）。</li>
 *   <li><b>降级 fallback</b>：[G19/G20] 注释模型不可用/配置解析失败/调用失败 → 降级简单摘要
 *       （仍在 results 末项），搜索不中断。</li>
 *   <li><b>engine 策略切换</b>：配置 {@code engine} 字段选择 anysearch/duckduckgo（组 4-1 拍板），
 *       DuckDuckGo 保留可配置（D-TR-H1-05）。</li>
 *   <li><b>max_results 删除</b>（D-TR-H1-06）：输入 schema 无 max_results；结果上限为内部常量。</li>
 *   <li><b>validateInput</b>：errorCode 1（missing query）/ errorCode 2（allowed+blocked 互斥），
 *       逐字对齐 CC（WebSearchTool.ts:235-253）。</li>
 * </ol>
 *
 * <p>[websearch-deprecate 2026-08-24] 通道2/3 已停用：{@code WebSearchResultEvent}/{@code WebSearchResultRecord}/
 * {@code WebSearchResultMapper} 均标注 {@code @Deprecated}（前端不消费，保留供审计），测试改为断言
 * {@code convertAndSend}/{@code insert} 永不调用；schema 契约（searchHitSchema 仅 {title,url}）改直构事件验证。
 */
@SuppressWarnings("deprecation")
class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer anysearchServer;

    @AfterEach
    void tearDown() {
        if (anysearchServer != null) {
            anysearchServer.stop(0);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tool 契约对齐（CC WebSearchTool.ts:152-227）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("契约方法对齐 CC：name/searchHint/readOnly/concurrencySafe/maxResultSizeChars/userFacingName/shouldDefer")
    void toolContract_ccAligned() {
        WebSearchTool tool = new WebSearchTool();
        assertThat(tool.name()).isEqualTo("WebSearch");
        assertThat(tool.searchHint()).isEqualTo("search the web for current information"); // :154
        assertThat(tool.shouldDefer(null)).isTrue();                                        // :156
        assertThat(tool.isReadOnly(null)).isTrue();                                         // :203-205
        assertThat(tool.isConcurrencySafe(null)).isTrue();                                  // :200-202
        assertThat(tool.maxResultSizeChars()).isEqualTo(100_000L);                          // :155
        assertThat(tool.userFacingName()).isEqualTo("Web Search");                          // :160-162
        // toAutoClassifierInput = query（:206-208）
        ToolUseBlock call = new ToolUseBlock("id", "WebSearch",
                MAPPER.createObjectNode().put("query", "claude code"));
        assertThat(tool.toAutoClassifierInput(call.input())).isEqualTo("claude code");
    }

    @Test
    @DisplayName("inputSchema 对齐 CC：query(minLength 2) + allowed/blocked_domains，无 max_results（D-TR-H1-06）")
    void inputSchema_ccShape_noMaxResults() throws Exception {
        WebSearchTool tool = new WebSearchTool();
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse(); // z.strictObject → false
        assertThat(schema.path("required").toString()).contains("\"query\"");
        JsonNode props = schema.path("properties");
        // query minLength=2（CC z.string().min(2)，WebSearchTool.ts:27）
        assertThat(props.path("query").path("minLength").asInt()).isEqualTo(2);
        assertThat(props.has("allowed_domains")).isTrue();   // :28-31
        assertThat(props.has("blocked_domains")).isTrue();   // :32-35
        assertThat(props.has("max_results")).isFalse();      // D-TR-H1-06 删除
        assertThat(schema.toString()).doesNotContain("max_results");
    }

    @Test
    @DisplayName("outputSchema [websearch-ccalign T2]：{query, results(anyOf hits块/string), durationSeconds}，无 summary")
    void outputSchema_ccShape() {
        WebSearchTool tool = new WebSearchTool();
        JsonNode schema = tool.outputSchema();
        assertThat(schema.path("properties").has("query")).isTrue();
        assertThat(schema.path("properties").has("durationSeconds")).isTrue();
        // results 数组（CC WebSearchTool.ts:59-61）
        JsonNode results = schema.path("properties").path("results");
        assertThat(results.path("type").asText()).isEqualTo("array");
        JsonNode items = results.path("items");
        assertThat(items.path("anyOf").isArray()).isTrue();
        // ① SearchResult 块（CC searchResultSchema :42-52）
        JsonNode searchResult = items.path("anyOf").get(0);
        assertThat(searchResult.path("type").asText()).isEqualTo("object");
        assertThat(searchResult.path("properties").has("tool_use_id")).isTrue();
        JsonNode content = searchResult.path("properties").path("content");
        assertThat(content.path("type").asText()).isEqualTo("array");
        assertThat(content.path("items").path("properties").has("title")).isTrue();
        assertThat(content.path("items").path("properties").has("url")).isTrue();
        // ② 文本注释（string）
        assertThat(items.path("anyOf").get(1).path("type").asText()).isEqualTo("string");
        // summary 顶层字段已删除（D2/D3，CC 无该字段）
        assertThat(schema.path("properties").has("summary")).isFalse();
        assertThat(schema.path("properties").has("count")).isFalse();
        assertThat(schema.path("properties").has("snippet")).isFalse();
        assertThat(schema.path("required").toString()).contains("query", "results", "durationSeconds");
    }

    @Test
    @DisplayName("validateInput：missing query → errorCode 1；allowed+blocked 互斥 → errorCode 2（逐字对齐 CC）")
    void validateInput_errorCodes_ccAligned() {
        WebSearchTool tool = new WebSearchTool();
        // missing query（CC WebSearchTool.ts:236-243）
        Tool.ValidationResult missing = tool.validateInput(MAPPER.createObjectNode(), null);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.errorCode()).isEqualTo("1");
        assertThat(missing.message()).isEqualTo("Error: Missing query");
        // allowed + blocked 同时非空（CC :244-251）
        ObjectNode bothInput = MAPPER.createObjectNode();
        bothInput.put("query", "hello");
        bothInput.set("allowed_domains", MAPPER.createArrayNode().add("a.com"));
        bothInput.set("blocked_domains", MAPPER.createArrayNode().add("b.com"));
        Tool.ValidationResult both = tool.validateInput(bothInput, null);
        assertThat(both.ok()).isFalse();
        assertThat(both.errorCode()).isEqualTo("2");
        assertThat(both.message()).isEqualTo(
                "Error: Cannot specify both allowed_domains and blocked_domains in the same request");
        // 合法输入通过
        Tool.ValidationResult ok = tool.validateInput(
                MAPPER.createObjectNode().put("query", "hello"), null);
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("prompt() 对齐 CC getWebSearchPrompt：含 Sources 强约束 + 当前年份")
    void prompt_containsCcSourcesRequirement() {
        WebSearchTool tool = new WebSearchTool();
        String prompt = tool.prompt();
        // CC getWebSearchPrompt（prompt.ts:14-17）的强约束原文
        assertThat(prompt).contains("Sources:",
                "This is MANDATORY - never skip including sources in your response");
        assertThat(prompt).contains("Usage notes:", "Domain filtering is supported to include or block specific websites");
        assertThat(prompt).contains(String.valueOf(java.time.LocalDate.now().getYear()));
    }

    // ────────────────────────────────────────────────────────────────────────
    // DuckDuckGo 引擎（保留可配置 D-TR-H1-05；经 forcedEngine 强制，默认引擎已是 anysearch）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("execute(DuckDuckGo 强制引擎, 无模型依赖)：输出 {query, results[首项 hits块, 末项注释], durationSeconds}（T2）")
    void execute_duckduckgo_forcedEngine_producesCcOutput() throws Exception {
        String fixtureHtml = "<html><body>"
                + "<a class=\"result__a\" href=\"https://example.com/1\">Example One</a>"
                + "<a class=\"result__a\" href=\"https://example.com/2\">Example Two</a>"
                + "</body></html>";
        // WebFetchTool 新输出契约 {bytes, code, codeText, result, durationMs, url}
        String fetchJson = MAPPER.createObjectNode()
                .put("bytes", fixtureHtml.length())
                .put("code", 200)
                .put("codeText", "OK")
                .put("result", fixtureHtml)
                .put("durationMs", 12)
                .put("url", "https://html.duckduckgo.com/html/?q=test")
                .toString();
        // 默认引擎 = anysearch（T4 拍板）→ duckduckgo 用例必须 forcedEngine 强制
        FakeWebFetch fakeWebFetch = new FakeWebFetch(fetchJson);
        WebSearchTool tool = new WebSearchTool(fakeWebFetch, new DuckDuckGoEngine(fakeWebFetch));

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-1", "WebSearch", MAPPER.createObjectNode().put("query", "test")),
                null);

        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("query").asText()).isEqualTo("test");
        assertThat(out.has("count")).isFalse();      // D-TR-H1-07 count 删除
        assertThat(out.has("snippet")).isFalse();    // D-TR-H1-07 snippet 删除
        assertThat(out.has("summary")).isFalse();    // [websearch-ccalign T2] summary 顶层字段删除
        assertThat(out.path("durationSeconds").isNumber()).isTrue();
        // results 数组：首项 = hits 块（tool_use_id = call.id()），末项 = 注释文本
        JsonNode results = out.path("results");
        assertThat(results.isArray()).isTrue();
        assertThat(results.size()).isEqualTo(2);
        JsonNode hitsBlock = results.get(0);
        assertThat(hitsBlock.path("tool_use_id").asText())
                .as("hits 块 tool_use_id 必须 = call.id()（Java 端等价 CC server-tool-use id）")
                .isEqualTo("ws-1");
        JsonNode content = hitsBlock.path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).path("title").asText()).isEqualTo("Example One");
        assertThat(content.get(0).path("url").asText()).isEqualTo("https://example.com/1");
        assertThat(content.get(1).path("title").asText()).isEqualTo("Example Two");
        // 注释末项（无模型依赖 → 降级简单摘要，保留原始 title + url）
        String commentary = results.get(1).asText();
        assertThat(commentary).contains("Web search results for query: \"test\"");
        assertThat(commentary).contains("Example One", "https://example.com/1");
        assertThat(commentary).contains("Example Two", "https://example.com/2");
    }

    @Test
    @DisplayName("execute(DuckDuckGo 强制引擎)：结果超过内部上限 DEFAULT_RESULTS 时截断（非用户输入 max_results）")
    void execute_duckduckgo_truncatesToInternalCap() throws Exception {
        StringBuilder html = new StringBuilder("<html>");
        for (int i = 0; i < 12; i++) {
            html.append("<a class=\"result__a\" href=\"https://example.com/")
                    .append(i).append("\">Title ").append(i).append("</a>");
        }
        html.append("</html>");
        String fetchJson = MAPPER.createObjectNode()
                .put("code", 200).put("result", html.toString()).toString();
        FakeWebFetch fakeWebFetch = new FakeWebFetch(fetchJson);
        WebSearchTool tool = new WebSearchTool(fakeWebFetch, new DuckDuckGoEngine(fakeWebFetch));
        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-1", "WebSearch", MAPPER.createObjectNode().put("query", "test")),
                null);
        // hits 块 content 与注释的链接数 = 截断后的内部上限（DEFAULT_RESULTS）
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        JsonNode results = out.path("results");
        assertThat(results.get(0).path("content").size()).isEqualTo(WebSearchTool.DEFAULT_RESULTS);
        String commentary = results.get(1).asText();
        long linkCount = commentary.lines().filter(l -> l.startsWith("- [")).count();
        assertThat(linkCount).isEqualTo(WebSearchTool.DEFAULT_RESULTS);
        assertThat(commentary).contains("https://example.com/0", "https://example.com/7");
        assertThat(commentary).doesNotContain("example.com/8"); // 第 9+ 条被截断
    }

    @Test
    @DisplayName("[R-A] duckduckgo proxy 接线：@PostConstruct 把 settingsService 接入内部裸 webFetch → resolveSecurity HttpClient 带 ProxySelector")
    void duckduckgoProxyWired_throughPostConstruct() throws Exception {
        // WHY（规则九）：用户「proxy 肯定接线」——duckduckgo 经内部裸 new WebFetchTool 抓 HTML，
        // 其 @Autowired 字段全 null；只有 @PostConstruct wireWebFetchSettingsService 把 settingsService
        // 手动接入后，resolveSecurity() 才会从 settings.proxy 构建带 ProxySelector 的 HttpClient。
        // 若接线缺失（不 invoke @PostConstruct）→ 恒默认无代理 → proxy 静默失效（C9 陷阱兄弟路径）。
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        SettingsRecord settings = new SettingsRecord();
        settings.setWebsearchEngine("duckduckgo");
        when(settingsMapper.selectOneById(1)).thenReturn(settings);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.get()).thenReturn(new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, "proxy.example.com:8080", null, null,
                null,
                null,
                null,
                null,
                // [V52] 压缩配置 12 列未设 → null · [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));   // permissionMode（V44 全局默认，本 fixture 未设 → null）· [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        // 模拟 Spring 装配：裸 new + 反射注入字段 + 触发 @PostConstruct
        WebSearchTool tool = new WebSearchTool();
        ReflectionTestUtils.setField(tool, "settingsMapper", settingsMapper);
        ReflectionTestUtils.setField(tool, "settingsService", settingsService);
        ReflectionTestUtils.invokeMethod(tool, "wireWebFetchSettingsService");

        // duckduckgo 分支 → 内部 webFetch 的 resolveSecurity() 必须走代理
        SearchEngine engine = tool.resolveEngine();
        assertThat(engine).isInstanceOf(DuckDuckGoEngine.class);
        WebFetchTool internalWebFetch = (WebFetchTool) ReflectionTestUtils.getField(tool, "webFetch");
        WebFetchSecurity security =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(internalWebFetch, "resolveSecurity");
        assertThat(security).as("内部 webFetch resolveSecurity() 必须返回非 null security").isNotNull();
        HttpClient client = (HttpClient) ReflectionTestUtils.getField(security, "httpClient");
        assertThat(client.proxy()).as("settings.proxy 配置必须经 @PostConstruct 落到 duckduckgo 抓取 HttpClient")
                .isPresent();
    }

    @Test
    @DisplayName("[R-A] duckduckgo proxy 接线缺省：settingsService 未注入 → 内部 webFetch 保持默认无代理（直连）")
    void duckduckgoProxyNotWired_whenSettingsServiceAbsent() {
        // WHY：未注入 settingsService（测试/孤立运行）→ wireWebFetchSettingsService 不接线 →
        // resolveSecurity() 走默认无代理（直连）。保证隔离路径零行为变化（验收 #3 直连侧）。
        WebSearchTool tool = new WebSearchTool(); // settingsService 未注入
        ReflectionTestUtils.invokeMethod(tool, "wireWebFetchSettingsService"); // no-op（null 分支）

        WebFetchTool internalWebFetch = (WebFetchTool) ReflectionTestUtils.getField(tool, "webFetch");
        WebFetchSecurity security =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(internalWebFetch, "resolveSecurity");
        HttpClient client = (HttpClient) ReflectionTestUtils.getField(security, "httpClient");
        assertThat(client.proxy()).as("settingsService 未注入 → 默认无代理（ProxySelector 不设）").isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // anysearch 引擎（engine 字段切换）· D-TR-H1-05 + [websearch-ccalign T4] DB settings
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("engine 字段切换 anysearch（DB settings）：本地 mock server 收到 POST /v1/search + Bearer 头，输出契约对齐")
    void engineSwitch_anysearch_localServer() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        anysearchServer = HttpServer.create(new InetSocketAddress(0), 0);
        anysearchServer.createContext("/v1/search", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = ("{\"results\":[{\"title\":\"Alpha\",\"url\":\"https://alpha.example.com\","
                    + "\"snippet\":\"...\"},{\"title\":\"Beta\",\"url\":\"https://beta.example.com\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        anysearchServer.start();
        int port = anysearchServer.getAddress().getPort();

        // DB settings 注入：engine=anysearch + apiKey=test-key + baseUrl=本地 mock（T4 + R-B：
        // 引擎/key/base-url 全走 DB，不用 @Value）
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        SettingsRecord settings = new SettingsRecord();
        settings.setWebsearchEngine("anysearch");
        settings.setApiKey("test-key");
        settings.setWebsearchBaseUrl("http://localhost:" + port);
        when(settingsMapper.selectOneById(1)).thenReturn(settings);
        WebSearchTool tool = new WebSearchTool();
        tool.anysearchHttpClient = HttpClient.newHttpClient();
        ReflectionTestUtils.setField(tool, "settingsMapper", settingsMapper);

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-2", "WebSearch", MAPPER.createObjectNode().put("query", "claude")),
                null);

        // 后端请求契约：POST /v1/search，body 含 query + max_results，Bearer 认证（DB settings.apiKey 透传）
        assertThat(capturedAuth.get()).isEqualTo("Bearer test-key");
        assertThat(capturedBody.get()).contains("\"query\":\"claude\"");
        assertThat(capturedBody.get()).contains("\"max_results\"");

        // 输出契约 [websearch-ccalign T2]：{query, results[], durationSeconds}
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("query").asText()).isEqualTo("claude");
        assertThat(out.path("durationSeconds").isNumber()).isTrue();
        JsonNode results = out.path("results");
        assertThat(results.isArray()).isTrue();
        assertThat(results.size()).isEqualTo(2);
        JsonNode hitsBlock = results.get(0);
        assertThat(hitsBlock.path("tool_use_id").asText()).isEqualTo("ws-2");
        assertThat(hitsBlock.path("content").get(0).path("title").asText()).isEqualTo("Alpha");
        assertThat(hitsBlock.path("content").get(0).path("url").asText()).isEqualTo("https://alpha.example.com");
        assertThat(hitsBlock.path("content").get(1).path("title").asText()).isEqualTo("Beta");
        assertThat(hitsBlock.toString()).doesNotContain("snippet"); // snippet 不进输出（D-TR-H1-07）
        String commentary = results.get(1).asText();
        assertThat(commentary).contains("Alpha", "https://alpha.example.com");
        assertThat(commentary).contains("Beta", "https://beta.example.com");
        assertThat(commentary).doesNotContain("snippet");
    }

    @Test
    @DisplayName("resolveEngine：settings 未注入（settingsMapper null）→ 默认 anysearch（T4 拍板）")
    void resolveEngine_defaultIsAnySearch() {
        WebSearchTool tool = new WebSearchTool(); // settingsMapper 未注入 → readSettings null
        SearchEngine engine = tool.resolveEngine();
        assertThat(engine).isInstanceOf(AnySearchEngine.class);
        assertThat(engine.name()).isEqualTo("anysearch");
    }

    @Test
    @DisplayName("resolveEngine：DB settings.websearchEngine 选择引擎（anysearch/duckduckgo）")
    void resolveEngine_settingsEngineSelection() {
        // settings.engine=duckduckgo → DuckDuckGoEngine
        SettingsMapper duckMapper = mock(SettingsMapper.class);
        SettingsRecord duckSettings = new SettingsRecord();
        duckSettings.setWebsearchEngine("duckduckgo");
        when(duckMapper.selectOneById(1)).thenReturn(duckSettings);
        WebSearchTool duckTool = new WebSearchTool();
        ReflectionTestUtils.setField(duckTool, "settingsMapper", duckMapper);
        SearchEngine duckEngine = duckTool.resolveEngine();
        assertThat(duckEngine).isInstanceOf(DuckDuckGoEngine.class);
        assertThat(duckEngine.name()).isEqualTo("duckduckgo");

        // settings.engine=anysearch → AnySearchEngine
        SettingsMapper anyMapper = mock(SettingsMapper.class);
        SettingsRecord anySettings = new SettingsRecord();
        anySettings.setWebsearchEngine("anysearch");
        when(anyMapper.selectOneById(1)).thenReturn(anySettings);
        WebSearchTool anyTool = new WebSearchTool();
        ReflectionTestUtils.setField(anyTool, "settingsMapper", anyMapper);
        SearchEngine anyEngine = anyTool.resolveEngine();
        assertThat(anyEngine).isInstanceOf(AnySearchEngine.class);
        assertThat(anyEngine.name()).isEqualTo("anysearch");
    }

    @Test
    @DisplayName("resolveEngine：unknown engine 名 fail-loud 回退 anysearch（warn），非 duckduckgo")
    void resolveEngine_unknownFallsBackToAnySearch() {
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        SettingsRecord settings = new SettingsRecord();
        settings.setWebsearchEngine("bogus-engine");
        when(settingsMapper.selectOneById(1)).thenReturn(settings);
        WebSearchTool tool = new WebSearchTool();
        ReflectionTestUtils.setField(tool, "settingsMapper", settingsMapper);
        SearchEngine engine = tool.resolveEngine();
        assertThat(engine).isInstanceOf(AnySearchEngine.class);
        assertThat(engine.name()).isEqualTo("anysearch");
    }

    @Test
    @DisplayName("resolveEngine：forcedEngine 测试构造优先于 DB settings")
    void resolveEngine_forcedEngineWins() {
        SearchEngine spy = new SearchEngine() {
            @Override public String name() { return "spy"; }
            @Override public List<SearchHit> search(SearchRequest request) {
                return List.of(new SearchHit("T", "https://t.example.com"));
            }
        };
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy);
        assertThat(tool.resolveEngine()).isSameAs(spy);
    }

    @Test
    @DisplayName("resolveEngine：DB settings.apiKey 空 → 内置默认兜底；配置 → 透传（T4）")
    void resolveEngine_apiKeyDefaultFallback() {
        // apiKey 空 → 内置默认 as_sk_a95d63d2e77de587a95b88dd9e0de48b
        SettingsMapper emptyMapper = mock(SettingsMapper.class);
        SettingsRecord empty = new SettingsRecord();
        empty.setWebsearchEngine("anysearch");
        when(emptyMapper.selectOneById(1)).thenReturn(empty);
        WebSearchTool emptyTool = new WebSearchTool();
        ReflectionTestUtils.setField(emptyTool, "settingsMapper", emptyMapper);
        AnySearchEngine emptyEngine = (AnySearchEngine) emptyTool.resolveEngine();
        String emptyKey = (String) ReflectionTestUtils.getField(emptyEngine, "apiKey");
        assertThat(emptyKey).isEqualTo(WebSearchTool.DEFAULT_ANYSEARCH_API_KEY);

        // apiKey 配置 → 原样透传
        SettingsMapper keyMapper = mock(SettingsMapper.class);
        SettingsRecord keySettings = new SettingsRecord();
        keySettings.setWebsearchEngine("anysearch");
        keySettings.setApiKey("custom-key");
        when(keyMapper.selectOneById(1)).thenReturn(keySettings);
        WebSearchTool keyTool = new WebSearchTool();
        ReflectionTestUtils.setField(keyTool, "settingsMapper", keyMapper);
        AnySearchEngine keyEngine = (AnySearchEngine) keyTool.resolveEngine();
        String key = (String) ReflectionTestUtils.getField(keyEngine, "apiKey");
        assertThat(key).isEqualTo("custom-key");
    }

    @Test
    @DisplayName("resolveEngine：DB settings.proxy 配置 → AnySearchEngine HttpClient ProxySelector；proxy 空 → 直连（T4）")
    void resolveEngine_proxyPassedToAnySearchEngine() {
        // proxy 配置 → 自建 HttpClient 带 ProxySelector
        SettingsMapper proxyMapper = mock(SettingsMapper.class);
        SettingsRecord proxySettings = new SettingsRecord();
        proxySettings.setWebsearchEngine("anysearch");
        proxySettings.setApiKey("k");
        proxySettings.setProxy("proxy.example.com:8080");
        when(proxyMapper.selectOneById(1)).thenReturn(proxySettings);
        WebSearchTool proxyTool = new WebSearchTool();
        ReflectionTestUtils.setField(proxyTool, "settingsMapper", proxyMapper);
        AnySearchEngine proxyEngine = (AnySearchEngine) proxyTool.resolveEngine();
        HttpClient proxyClient = (HttpClient) ReflectionTestUtils.getField(proxyEngine, "httpClient");
        assertThat(proxyClient.proxy()).as("proxy 配置必须生效（ProxySelector 已设）").isPresent();

        // proxy 空 → 直连（ProxySelector 不设）
        SettingsMapper directMapper = mock(SettingsMapper.class);
        SettingsRecord directSettings = new SettingsRecord();
        directSettings.setWebsearchEngine("anysearch");
        directSettings.setApiKey("k");
        when(directMapper.selectOneById(1)).thenReturn(directSettings);
        WebSearchTool directTool = new WebSearchTool();
        ReflectionTestUtils.setField(directTool, "settingsMapper", directMapper);
        AnySearchEngine directEngine = (AnySearchEngine) directTool.resolveEngine();
        HttpClient directClient = (HttpClient) ReflectionTestUtils.getField(directEngine, "httpClient");
        assertThat(directClient.proxy()).as("proxy 空必须直连（ProxySelector 不设）").isEmpty();
    }

    @Test
    @DisplayName("[R-B] resolveEngine：settings.websearchBaseUrl 空/blank → AnySearchEngine 用默认 baseUrl")
    void resolveEngine_baseUrlDefaultsToAnySearch() {
        // WHY：R-B 把 base-url 移入 DB settings（V38 列 websearch_base_url）——空/blank 必须回退
        // AnySearchEngine.DEFAULT_BASE_URL（https://api.anysearch.com），否则生产 anysearch 端点错乱。
        // 空（null）分支
        SettingsMapper nullMapper = mock(SettingsMapper.class);
        SettingsRecord nullSettings = new SettingsRecord();
        nullSettings.setWebsearchEngine("anysearch");
        when(nullMapper.selectOneById(1)).thenReturn(nullSettings);
        WebSearchTool nullTool = new WebSearchTool();
        ReflectionTestUtils.setField(nullTool, "settingsMapper", nullMapper);
        AnySearchEngine nullEngine = (AnySearchEngine) nullTool.resolveEngine();
        assertThat(ReflectionTestUtils.getField(nullEngine, "baseUrl"))
                .as("websearchBaseUrl null → 默认 baseUrl")
                .isEqualTo(AnySearchEngine.DEFAULT_BASE_URL);

        // blank（空串）分支
        SettingsMapper blankMapper = mock(SettingsMapper.class);
        SettingsRecord blankSettings = new SettingsRecord();
        blankSettings.setWebsearchEngine("anysearch");
        blankSettings.setWebsearchBaseUrl("   ");
        when(blankMapper.selectOneById(1)).thenReturn(blankSettings);
        WebSearchTool blankTool = new WebSearchTool();
        ReflectionTestUtils.setField(blankTool, "settingsMapper", blankMapper);
        AnySearchEngine blankEngine = (AnySearchEngine) blankTool.resolveEngine();
        assertThat(ReflectionTestUtils.getField(blankEngine, "baseUrl"))
                .as("websearchBaseUrl blank → 默认 baseUrl")
                .isEqualTo(AnySearchEngine.DEFAULT_BASE_URL);
    }

    @Test
    @DisplayName("[R-B] resolveEngine：settings.websearchBaseUrl 配置 → 透传 AnySearchEngine（覆盖默认）")
    void resolveEngine_baseUrlOverriddenFromSettings() {
        // WHY：R-B 覆盖路径——配置值必须原样透传 AnySearchEngine.baseUrl（trim 后），
        // 端到端 POST 落在覆盖 base-url 而非默认（engineSwitch_anysearch_localServer 已证覆盖端到端）。
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord settings = new SettingsRecord();
        settings.setWebsearchEngine("anysearch");
        settings.setApiKey("k");
        settings.setWebsearchBaseUrl("  https://custom.anysearch.example.com  ");
        when(mapper.selectOneById(1)).thenReturn(settings);
        WebSearchTool tool = new WebSearchTool();
        ReflectionTestUtils.setField(tool, "settingsMapper", mapper);
        AnySearchEngine engine = (AnySearchEngine) tool.resolveEngine();
        assertThat(ReflectionTestUtils.getField(engine, "baseUrl"))
                .as("websearchBaseUrl 配置必须 trim 后透传 AnySearchEngine")
                .isEqualTo("https://custom.anysearch.example.com");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 客户端域过滤删除（D-TR-H1-08）：allowed/blocked 透传引擎，不在客户端 domainMatches 后置
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("allowed/blocked_domains 透传引擎请求，客户端无 domainMatches 后置过滤（D-TR-H1-08）")
    void domainFilter_forwardedToEngine_noClientSideFilter() throws Exception {
        AtomicReference<SearchEngine.SearchRequest> received = new AtomicReference<>();
        SearchEngine spy = new SearchEngine() {
            @Override public String name() { return "spy"; }
            @Override public List<SearchHit> search(SearchRequest request) {
                received.set(request);
                // 命中故意包含被 blocked 的域——客户端不得过滤（D-TR-H1-08）
                return List.of(new SearchHit("BlockedHit", "https://blocked.com/x"),
                        new SearchHit("AllowedHit", "https://allowed.com/y"));
            }
        };
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy);
        ObjectNode domInput = MAPPER.createObjectNode();
        domInput.put("query", "test");
        domInput.set("allowed_domains", MAPPER.createArrayNode().add("allowed.com"));
        domInput.set("blocked_domains", MAPPER.createArrayNode().add("blocked.com"));
        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-3", "WebSearch", domInput),
                null);
        // 请求透传：allowed/blocked 进入 SearchRequest
        assertThat(received.get().query()).isEqualTo("test");
        assertThat(received.get().allowedDomains()).containsExactly("allowed.com");
        assertThat(received.get().blockedDomains()).containsExactly("blocked.com");
        // 客户端不做 domainMatches 后置过滤：两个命中都在 hits 块与注释里
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        JsonNode results = out.path("results");
        assertThat(results.get(0).path("content").toString()).contains("BlockedHit", "AllowedHit");
        String commentary = results.get(1).asText();
        assertThat(commentary).contains("BlockedHit", "AllowedHit");
        assertThat(commentary).contains("https://blocked.com/x", "https://allowed.com/y");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 注释模型二次总结（G19/G20 + [websearch-ccalign T3] useSmall ? fast : mainLoop）
    // ────────────────────────────────────────────────────────────────────────

    private static final String FAST_FALLBACK = "claude-haiku-4-5-20251001";

    @Test
    @DisplayName("execute(注释模型总结成功)：useSmall=false + ctx null → 回落 fast 档，注释进 results 末项（T3）")
    void execute_commentarySummary_useSmallFalse_ctxNull_fallsBackToFast() throws Exception {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn("Fixed fast model summary about claude code");
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        // 主循环模型不可得（ctx null）→ 回落 fast 档（resolveFastModelName，fast→weak→固定默认 三级）
        when(resolver.resolveFastModelName(FAST_FALLBACK)).thenReturn("claude-fast-4-5");
        when(resolver.resolve("claude-fast-4-5")).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        SearchEngine spy = twoHitSpy();
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy, factory, resolver);

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-w1", "WebSearch",
                        MAPPER.createObjectNode().put("query", "claude code")),
                null);

        // 输出契约：{query, results[首项 hits块, 末项注释], durationSeconds}
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("query").asText()).isEqualTo("claude code");
        assertThat(out.path("results").get(1).asText())
                .as("注释模型固定摘要必须原样进入 results 末项（T2）")
                .isEqualTo("Fixed fast model summary about claude code");
        assertThat(out.path("durationSeconds").isNumber()).isTrue();

        // 注释模型调用参数：模型=fast 档（ctx null → 回落）、querySource='web_search_tool'（CC :285）、
        // thinking disabled、userPrompt 含搜索命中
        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LlmProvider.ChatRequestOptions> optionsCaptor =
                ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(provider).chatWithOptions(any(), modelCaptor.capture(), anyString(),
                userPromptCaptor.capture(), optionsCaptor.capture());
        assertThat(modelCaptor.getValue())
                .as("ctx null → 主循环不可得，回落 fast 档（CC useHaiku ? fast : mainLoop）")
                .isEqualTo("claude-fast-4-5");
        assertThat(optionsCaptor.getValue().querySource())
                .as("querySource 必须 = 'web_search_tool'（CC WebSearchTool.ts:285）")
                .isEqualTo("web_search_tool");
        assertThat(optionsCaptor.getValue().thinkingConfig().type()).isEqualTo("disabled");
        assertThat(userPromptCaptor.getValue())
                .contains("claude code", "https://alpha.example.com", "https://beta.example.com");
    }

    @Test
    @DisplayName("execute(注释模型总结成功)：useSmall=true（DB settings.websearchUseSmallModel）→ fast 档（T3）")
    void execute_commentarySummary_useSmallTrue_usesFastTier() throws Exception {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn("Fixed fast model summary about claude code");
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolveFastModelName(FAST_FALLBACK)).thenReturn("claude-fast-4-5");
        when(resolver.resolve("claude-fast-4-5")).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        // useSmall=true（CC tengu_plum_vx3 flag，WebSearchTool.ts:262-265）→ 走 fast 档
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        SettingsRecord settings = new SettingsRecord();
        settings.setWebsearchUseSmallModel(true);
        when(settingsMapper.selectOneById(1)).thenReturn(settings);

        SearchEngine spy = twoHitSpy();
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy, factory, resolver);
        ReflectionTestUtils.setField(tool, "settingsMapper", settingsMapper);

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-w2", "WebSearch",
                        MAPPER.createObjectNode().put("query", "claude code")),
                null);

        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("results").get(1).asText())
                .isEqualTo("Fixed fast model summary about claude code");

        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        verify(provider).chatWithOptions(any(), modelCaptor.capture(), anyString(), anyString(), any());
        assertThat(modelCaptor.getValue())
                .as("useSmall=true（CC tengu_plum_vx3）→ 必须用 fast 档而非主循环模型")
                .isEqualTo("claude-fast-4-5");
    }

    @Test
    @DisplayName("execute(注释模型总结成功)：useSmall=false + SessionAgentStateRegistry 注入 → 主循环模型（T3）")
    void execute_commentarySummary_useSmallFalse_usesMainLoopModel() throws Exception {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn("Fixed mainloop model summary about claude code");
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolve("claude-main-4-6")).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        // 主循环模型寻址：SessionAgentStateRegistry.get(sessionId) → AgentState.currentModel()
        SessionAgentStateRegistry registry = mock(SessionAgentStateRegistry.class);
        AgentState state = mock(AgentState.class);
        when(registry.get(SID)).thenReturn(state);
        when(state.currentModel()).thenReturn("claude-main-4-6");

        SearchEngine spy = twoHitSpy();
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy, factory, resolver);
        ReflectionTestUtils.setField(tool, "sessionAgentStateRegistry", registry);

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-w3", "WebSearch",
                        MAPPER.createObjectNode().put("query", "claude code")),
                ToolUseContext.of(UUID.randomUUID(), SID));

        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("results").get(1).asText())
                .isEqualTo("Fixed mainloop model summary about claude code");

        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        verify(provider).chatWithOptions(any(), modelCaptor.capture(), anyString(), anyString(), any());
        assertThat(modelCaptor.getValue())
                .as("useSmall=false + registry 注入 → 必须用主循环模型（CC mainLoopModel, WebSearchTool.ts:280）")
                .isEqualTo("claude-main-4-6");
    }

    @Test
    @DisplayName("execute(降级：fast 档配置不可用)：resolve 返回 null → 简单摘要兜底，搜索不中断")
    void execute_commentarySummary_degradesWhenModelConfigUnusable() throws Exception {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolveFastModelName(FAST_FALLBACK)).thenReturn("claude-fast-4-5");
        when(resolver.resolve("claude-fast-4-5")).thenReturn(null); // 配置不可用（warn+skip 不落 mock）

        SearchEngine spy = twoHitSpy();
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy, factory, resolver);

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-w4", "WebSearch",
                        MAPPER.createObjectNode().put("query", "test")),
                null);

        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("results").isArray()).isTrue();
        String commentary = out.path("results").get(1).asText();
        assertThat(commentary).startsWith("Web search results for query: \"test\"");
        assertThat(commentary).contains("Alpha", "https://alpha.example.com"); // 保留原始结果给 LLM 兜底
        // 配置不可用 → 绝不触发 getProvider（零模型调用，搜索不中断）
        verify(factory, never()).getProvider(any(), any());
    }

    @Test
    @DisplayName("execute(降级：注释模型调用失败)：chatWithOptions 抛异常 → 简单摘要兜底，搜索不中断")
    void execute_commentarySummary_degradesOnCallFailure() throws Exception {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("model down"));
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolveFastModelName(FAST_FALLBACK)).thenReturn("claude-fast-4-5");
        when(resolver.resolve("claude-fast-4-5")).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        SearchEngine spy = twoHitSpy();
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), spy, factory, resolver);

        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-w5", "WebSearch",
                        MAPPER.createObjectNode().put("query", "test")),
                null);

        // 调用失败 → 降级简单摘要（仍在 results 末项），搜索不中断
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("results").isArray()).isTrue();
        String commentary = out.path("results").get(1).asText();
        assertThat(commentary).startsWith("Web search results for query: \"test\"");
        assertThat(commentary).contains("Alpha", "https://alpha.example.com");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 通道2/通道3 旁路（websearch-event 2026-08-23 · AC-2/AC-3/AC-4/AC-5/AC-6）
    // ────────────────────────────────────────────────────────────────────────

    private static final String SID = "00000000-0000-0000-0000-0000000000aa";

    /** 固定 2 条命中的 spy 引擎（title/url 对齐 CC searchHitSchema {title,url}）。 */
    private static SearchEngine twoHitSpy() {
        return new SearchEngine() {
            @Override public String name() { return "spy"; }
            @Override public List<SearchHit> search(SearchRequest request) {
                return List.of(new SearchHit("Alpha", "https://alpha.example.com"),
                        new SearchHit("Beta", "https://beta.example.com"));
            }
        };
    }

    @Test
    @DisplayName("通道2已停用：convertAndSend 不再被调，通道1 data() 输出契约不变（2026-08-23 前端仅从 tool_result 抽取）")
    void execute_publishesWebSearchResultEvent_sessionTopic() throws Exception {
        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), twoHitSpy());
        ReflectionTestUtils.setField(tool, "wsTemplate", wsTemplate);
        // 未注入 mapper → 通道3 no-op，通道2 独立验证（隔离良好）
        ToolUseBlock call = new ToolUseBlock("ws-ev-1", "WebSearch",
                MAPPER.createObjectNode().put("query", "claude code"));
        AgentToolResult<?> result = tool.execute(call, ToolUseContext.of(UUID.randomUUID(), SID));

        // 通道2 已停用（2026-08-23）：STOMP 事件不再发布到 /topic/sessions/{sessionId}/websearch-results
        verify(wsTemplate, never()).convertAndSend(anyString(), any(Object.class));

        // AC-4 双通道隔离：给 LLM 的 data() 含 query/results（首项 hits块 tool_use_id=call.id()）/durationSeconds
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("results").isArray()).isTrue();
        assertThat(out.path("results").get(0).path("tool_use_id").asText()).isEqualTo("ws-ev-1");
        assertThat(out.has("toolUseId")).isFalse(); // 顶层无 toolUseId（hits 块内 tool_use_id 才有）
        assertThat(out.has("query")).isTrue();
        assertThat(out.has("durationSeconds")).isTrue();
    }

    @Test
    @DisplayName("通道3已停用：mapper.insert 不再被调，通道1 data() 输出契约不变（2026-08-23 不再落库）")
    void execute_storesWebSearchResultRecord() throws Exception {
        WebSearchResultMapper mapper = mock(WebSearchResultMapper.class);
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), twoHitSpy());
        ReflectionTestUtils.setField(tool, "webSearchResultMapper", mapper);
        ToolUseBlock call = new ToolUseBlock("ws-st-1", "WebSearch",
                MAPPER.createObjectNode().put("query", "claude code"));
        AgentToolResult<?> result = tool.execute(call, ToolUseContext.of(UUID.randomUUID(), SID));

        // 通道3 已停用（2026-08-23）：websearch_results 表不再落库
        verify(mapper, never()).insert(any(WebSearchResultRecord.class));

        // AC-4 双通道隔离：data() 仍含 hits 块（results 首项）
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("results").isArray()).isTrue();
    }

    @Test
    @DisplayName("ctx==null 亦不发布/存库，搜索成功，convertAndSend/insert 均未调用（通道2/3 已停用 2026-08-23）")
    void executeNullCtx_sideChannelsSkipped_searchSucceeds() throws Exception {
        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        WebSearchResultMapper mapper = mock(WebSearchResultMapper.class);
        WebSearchTool tool = new WebSearchTool(new WebFetchTool(), twoHitSpy());
        ReflectionTestUtils.setField(tool, "wsTemplate", wsTemplate);
        ReflectionTestUtils.setField(tool, "webSearchResultMapper", mapper);
        // execute(call, null) 路径（既有测试同构）：通道2/3 已停用（2026-08-23），不发布/存库（不触碰 ctx）
        AgentToolResult<?> result = tool.execute(
                new ToolUseBlock("ws-null-1", "WebSearch",
                        MAPPER.createObjectNode().put("query", "test")),
                null);
        JsonNode out = MAPPER.readTree(((ToolResult<?>) result).data().toString());
        assertThat(out.path("query").asText()).isEqualTo("test");
        assertThat(out.path("results").isArray()).isTrue();
        verify(wsTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(mapper, never()).insert(any(WebSearchResultRecord.class));
    }

    @Test
    @DisplayName("事件 results 元素 schema 对齐 CC searchHitSchema：仅 {title,url}（AC-6，通道2已停用 → 直构事件验证）")
    @SuppressWarnings("deprecation")
    void eventResultsSchemaOnlyTitleUrl() throws Exception {
        // 通道2 已停用（2026-08-23）：不再经 execute/convertAndSend 构造事件；直构 WebSearchResultEvent
        // 保住 CC searchHitSchema {title,url} 契约测试（WebSearchTool.ts:43-46）
        WebSearchResultEvent evt = new WebSearchResultEvent(
                SID.toString(), "ws-schema-1", "claude code",
                List.of(new SearchHit("Alpha", "https://alpha.example.com"),
                        new SearchHit("Beta", "https://beta.example.com")),
                0.0);
        // 序列化后 results 元素键集合恰为 {title, url}（AC-6 / CC searchHitSchema :43-46）
        String json = MAPPER.writeValueAsString(evt);
        JsonNode results = MAPPER.readTree(json).path("results");
        assertThat(results.isArray()).isTrue();
        for (JsonNode hit : results) {
            assertThat(hit.size()).isEqualTo(2);
            assertThat(hit.has("title")).isTrue();
            assertThat(hit.has("url")).isTrue();
            assertThat(hit.has("content")).isFalse();
            assertThat(hit.has("snippet")).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // mapToToolResultBlockParam 渲染（websearch-ccalign T2 · CC WebSearchTool.ts:401-434）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mapToToolResultBlockParam：CC 渲染（hits Links + 注释原文 + REMINDER），content 为 trim 文本")
    void mapToToolResultBlockParam_ccRendering() throws Exception {
        WebSearchTool tool = new WebSearchTool();
        AgentToolResult<?> result = ToolResult.success("ws-1",
                "{\"query\":\"test\",\"results\":["
                        + "{\"tool_use_id\":\"ws-1\",\"content\":[{\"title\":\"Alpha\",\"url\":\"https://alpha.example.com\"}]},"
                        + "\"Commentary text\"],\"durationSeconds\":1.0}");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "ws-1", false);

        assertThat(block.toolUseId()).isEqualTo("ws-1");
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.isError()).isFalse();
        String content = (String) block.content();
        // CC :404 开头 + :419 Links + 注释原文 + :426-427 REMINDER
        assertThat(content).startsWith("Web search results for query: \"test\"");
        assertThat(content).contains("Links: [{\"title\":\"Alpha\",\"url\":\"https://alpha.example.com\"}]");
        assertThat(content).contains("Commentary text");
        assertThat(content).endsWith("REMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks.");
        assertThat(content.trim()).isEqualTo(content); // CC :432 content = formattedOutput.trim()
    }

    @Test
    @DisplayName("mapToToolResultBlockParam：content 空 → No links found；null 项防御；string 项原文（CC :410-421）")
    void mapToToolResultBlockParam_noLinksAndNullDefense() throws Exception {
        WebSearchTool tool = new WebSearchTool();
        // 对象项 content 空数组 → "No links found."；null 项跳过；string 项原文
        AgentToolResult<?> result = ToolResult.success("ws-2",
                "{\"query\":\"q\",\"results\":["
                        + "{\"tool_use_id\":\"ws-2\",\"content\":[]},"
                        + "null,"
                        + "\"Note text\"],\"durationSeconds\":1.0}");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "ws-2", true);

        assertThat(block.isError()).isTrue(); // isError 透传
        String content = (String) block.content();
        assertThat(content).contains("No links found.");
        assertThat(content).contains("Note text");
        assertThat(content).doesNotContain("null");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 辅助
    // ────────────────────────────────────────────────────────────────────────

    /** 伪 WebFetchTool：返回预置的 WebFetch 输出契约 JSON（{bytes, code, codeText, result, ...}）。 */
    static class FakeWebFetch extends WebFetchTool {
        private final String payloadJson;

        FakeWebFetch(String payloadJson) {
            super();
            this.payloadJson = payloadJson;
        }

        @Override
        public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), payloadJson);
        }

        @Override
        public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
            return ToolResult.success(call.id(), payloadJson);
        }
    }
}

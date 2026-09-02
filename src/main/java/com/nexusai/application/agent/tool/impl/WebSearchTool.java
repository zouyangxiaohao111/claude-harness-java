package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
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
import com.nexusai.repository.session.entity.WebSearchResultRecord;
import com.nexusai.repository.session.mapper.WebSearchResultMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WebSearchTool — 对齐 CC {@code WebSearchTool.ts}（决策清单 组 4-1：WebSearch anysearch 后端）。
 *
 * <p><b>CC original:</b> {@code Open-ClaudeCode/src/tools/WebSearchTool/WebSearchTool.ts}
 * + {@code prompt.ts}（baseline {@code 1992306b}）。
 *
 * <p><b>IMP-H2 变更（anysearch 策略模式）</b>：
 * <ul>
 *   <li><b>SearchEngine 策略接口</b>：{@link SearchEngine} + {@link AnySearchEngine}
 *       （anysearch API）+ {@link DuckDuckGoEngine}（DuckDuckGo HTML 抓取，默认保留可配置）。
 *       facade 按配置 {@code nexusai.websearch.engine} 字段切换（anysearch/duckduckgo）。</li>
 *   <li><b>输出契约</b>：{@code {query, results, durationSeconds}}（WebSearchTool.ts:56-67），
 *       results 为一次搜索的 CC SearchResult 块 {@code {tool_use_id, content: [{title, url}]}}
 *       （makeOutputFromSearchResponse :124-128）。count/snippet/echo 删除（D-TR-H1-07）。</li>
 *   <li><b>max_results 输入删除</b>（D-TR-H1-06，CC 无该参数；max_uses=8 为 API server tool 固定值，
 *       Java 端由 {@link #DEFAULT_RESULTS} 内部上限表达，非用户输入）。</li>
 *   <li><b>客户端域过滤删除</b>（D-TR-H1-08：CC 域过滤交给 API server tool 参数；allowed/blocked
 *       透传到引擎，不在客户端 domainMatches 后置过滤）。</li>
 *   <li>输入校验：errorCode 1（missing query）/ errorCode 2（allowed+blocked 互斥），逐字对齐 CC
 *       validateInput（WebSearchTool.ts:235-253）。</li>
 * </ul>
 *
 * <p><b>受控残留（IMP-H2 范围外）</b>：
 * <ul>
 *   <li>CC 的 {@code queryModelWithStreaming}（经主/小模型发起 {@code web_search_20250305} server tool
 *       调用并流式接收结果）Java 无等价 Anthropic server tool 通道——本实现直接调搜索引擎后端
 *       （anysearch / DuckDuckGo），输出形状对齐 CC；模型侧二次处理与 isEnabled provider 门控
 *       （firstParty/vertex/foundry）登记为受控残留（与 OPD-TR-H1-03 同源）。</li>
 *   <li><b>duckduckgo proxy 透传（[websearch-resid R-A] 已接线）</b>：DuckDuckGoEngine 经
 *       {@link WebFetchTool} → {@link WebFetchSecurity} HttpClient 抓取 HTML；{@code @PostConstruct}
 *       {@link #wireWebFetchSettingsService()} 已把 settingsService 接入内部 webFetch →
 *       WebFetchTool.resolveSecurity() 从 DB settings.proxy 构建带 ProxySelector 的 HttpClient。
 *       proxy 配置同时作用于 anysearch（引擎直接透传）与 duckduckgo（经 WebFetchTool），
 *       不再受控残留（DuckDuckGoEngine 本体零改动，代理统一落在 WebFetchSecurity）。</li>
 * </ul>
 *
 * <p><b>[websearch-ccalign 2026-08-23] 模型链路 + 输出形状全量对齐 CC</b>：
 * <ul>
 *   <li><b>注释模型链路</b>（对齐 CC WebSearchTool.ts:262-280）：DB settings.websearchUseSmallModel
 *       （CC original: {@code tengu_plum_vx3} feature flag，:262-265）= true → fast 档
 *       （{@code getSmallFastModel}，model.ts:36-37）；false/缺省 → 主循环模型
 *       （CC {@code context.options.mainLoopModel}，Java 端 = AgentState.currentModel 经
 *       SessionAgentStateRegistry 寻址）；主循环不可得（ctx null / registry 未注入 / currentModel null）
 *       → 回落 fast 档（fail-loud 日志标注回落原因，规则十二）。</li>
 *   <li><b>输出形状</b>（对齐 CC outputSchema :56-67 + makeOutputFromSearchResponse :86-150）：
 *       {@code {query, results: [ {tool_use_id, content: [{title,url}...]}, "文本注释" ], durationSeconds}}。
 *       results 首项 = 一次搜索的 hits 块（tool_use_id = call.id()，Java 端等价 CC server-tool-use id），
 *       末项 = 注释模型文本注释（CC textAcc string 项）。</li>
 *   <li><b>tool_result 渲染</b>（对齐 CC mapToolResultToToolResultBlockParam :401-434）：
 *       "Web search results for query: ..." + Links + REMINDER（已 override，D5 收口）。</li>
 *   <li><b>配置入 DB</b>（对齐用户 2026-08-23 拍板 改动5）：websearch_engine/api_key/proxy/
 *       websearch_use_small_model 走 DB settings（{@link #readSettings()} 读链），不用 @Value 配置文件。</li>
 *   <li><b>正文获取</b>：hits 元素仅 {@code {title, url}}（对齐 CC searchHitSchema :43-46），不 extract
 *       正文；正文由主模型对 URL 发起 WebFetch 获取（WebFetchTool.ts:208-299），WebSearch 内不做正文抽取
 *       （websearch-ccalign 约束登记，AC-3）。</li>
 * </ul>
 *
 * <p><b>[G19/G20 调整 · 2026-08-22 用户拍板] 二次总结骨架（保留，模型链路已改 fast/mainLoop）</b>：
 * <ul>
 *   <li><b>搜索引擎保留自研</b>：DuckDuckGo/anysearch 现状不动（G19 确认，搜索不中断）。</li>
 *   <li><b>注释模型总结</b>：搜索结果（{@link SearchHit} 列表）→ 注释模型（复用 {@code chatWithOptions}
 *       模式，对齐 {@code HaikuToolUseSummaryGenerator}）生成<b>文本注释</b>。CC 锚点：CC 模型侧二次
 *       处理经 server tool 文本注释（results 中 string 项，makeOutputFromSearchResponse :103-143）；
 *       Java 端无官方通道，用注释模型总结等价表达该"文本注释"。</li>
 *   <li><b>输出契约</b>：results 数组 = hits 块（首项）+ 文本注释（末项）——hits 列表与注释<b>都给</b>
 *       LLM（对齐 CC outputSchema，不再只给 summary）。</li>
 *   <li><b>降级</b>：注释模型不可用/解析失败/调用失败 → 降级返回简单摘要文本（仍在 results 末项），
 *       搜索不中断；日志标注降级原因。</li>
 * </ul>
 *
 * <p><b>[websearch-event 2026-08-23] 三通道</b>：搜索详细原始 hits 有三条独立通道，互不污染：
 * <ul>
 *   <li><b>通道1 · 给 LLM</b>（<b>唯一消费源</b>：前端 2026-08-23 仅从工具输入输出 tool_result §33.2
 *       outputShape 抽取）：{@code {query, results[], durationSeconds}}（results 首项 = hits 块
 *       {@code {tool_use_id, content: [{title,url}...]}}，末项 = 注释模型文本注释），对齐 CC
 *       makeOutputFromSearchResponse :86-150。</li>
 *   <li><b>通道2 · 给前端</b>（新增，~~2026-08-23 已停用/过期~~）：{@link WebSearchResultEvent} 经
 *       {@code SimpMessagingTemplate.convertAndSend} 推送到
 *       {@code /topic/sessions/{sessionId}/websearch-results}（session 级 topic，对齐 token-warning
 *       先例）。{@code toolUseId = call.id()}（WebSearchTool 调用块 id，与消息流 tool_use 块关联；
 *       CC tool_use_id 是 server-tool-use 内部 id，WebSearchTool.ts:49，Java 无该通道）。</li>
 *   <li><b>通道3 · 落库</b>（新增，~~2026-08-23 已停用/过期~~）：{@link WebSearchResultRecord} 经
 *       {@link WebSearchResultMapper} 写入 {@code websearch_results} 表（V36），
 *       GET {@code /api/v1/sessions/{sessionId}/websearch-results} 查询。</li>
 * </ul>
 *
 * <p>通道2/3 为<b>旁路副作用</b>（AC-5 硬性约束；<b>2026-08-23 已停用</b>：execute 不再调用
 * {@code publishResults}/{@code storeResults}，方法保留 {@code @Deprecated} 供未来审计）：
 * {@code ctx == null} → warn+skip（不触碰 ctx，
 * 防 {@code execute(call, null)} 路径 NPE）；依赖未注入 / {@code convertAndSend} / {@code insert}
 * 抛异常 → 内部 catch → warn，绝不向 execute 外层 catch 逃逸（否则成功搜索会被翻成
 * {@code ToolResult.error}）。{@code wsTemplate}/{@code webSearchResultMapper} 均
 * {@code @Autowired(required=false)}——测试/孤立运行未注入 → no-op，隔离良好。
 */
@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    public static final String NAME = ToolNameConstants.WEB_SEARCH_TOOL_NAME;

    /**
     * 引擎结果条数内部上限 · 对齐 CC {@code max_uses: 8}（WebSearchTool.ts:82，API server tool
     * 固定搜索次数）语义的 Java 端输出上限——非用户输入参数（D-TR-H1-06 删 max_results）。
     */
    static final int DEFAULT_RESULTS = 8;

    /**
     * 内置默认 anysearch API key · DB settings.api_key 空/未配置时的兜底（用户 2026-08-23 拍板：
     * WebSearch 配置统一入 DB settings，api_key 空 → 内置默认 {@code as_sk_a95d63d2e77de587a95b88dd9e0de48b}）。
     */
    static final String DEFAULT_ANYSEARCH_API_KEY = "as_sk_a95d63d2e77de587a95b88dd9e0de48b";

    /**
     * fast 档注释模型固定兜底 · CC original: {@code getDefaultHaikuModel()} → haiku45
     * （model.ts:131-138）；Java 端经 {@code modelConfigResolver.resolveFastModelName(本常量)}
     * （fast→weak→固定默认 三级，ModelConfigResolver:197-223）。
     */
    private static final String FAST_FALLBACK_MODEL = "claude-haiku-4-5-20251001";

    /** 弱模型总结 querySource · 对齐 CC WebSearchTool.ts:285 {@code querySource: 'web_search_tool'}。 */
    private static final String QUERY_SOURCE = "web_search_tool";

    /**
     * 弱模型总结系统提示词 · [G19/G20 调整] 内部用现有弱模型总结搜索命中（非官方 server-tool）。
     * CC 锚点：CC 模型侧二次处理产出的文本注释（results 中 string 项）即"整体摘要"语义。
     */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are an assistant that summarizes web search results for the main model.
            Given a search query and a list of search result titles with URLs, write a concise overall
            summary of what these results cover. Highlight the most relevant findings and include the
            source URLs as markdown links [Title](URL) where appropriate.
            """;

    private final WebFetchTool webFetch;
    private final DuckDuckGoEngine duckDuckGoEngine;

    /** 测试注入：显式指定引擎（跳过 {@code @Value} 配置解析）。null = 按配置解析。 */
    final SearchEngine forcedEngine;

    /** 弱模型总结依赖 · [G19/G20 调整] 生产经 Spring 注入（{@code required=false} 测试/孤立运行可缺省 → 降级）。 */
    @Autowired(required = false)
    private LlmProviderFactory llmProviderFactory;

    /** 注释模型配置解析 · [G19/G20 + websearch-ccalign T3] 复用共享解析器（resolveFastModelName fast 档 / resolve）。 */
    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    /** 通道2 STOMP 推送 · [websearch-event 2026-08-23] {@code @Autowired(required=false)}：测试/孤立运行未注入 → 发布 no-op（warn+skip）。2026-08-23 通道2 已停用，本字段仅由 @Deprecated publishResults 引用（保留供审计）。 */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /** 通道3 DB 存库 · [websearch-event 2026-08-23] {@code @Autowired(required=false)}：测试/孤立运行未注入 → 存库 no-op（warn+skip）。2026-08-23 通道3 已停用，本字段仅由 @Deprecated storeResults 引用（保留供审计）。 */
    @Autowired(required = false)
    private WebSearchResultMapper webSearchResultMapper;

    /**
     * ObjectMapper · [websearch-event 2026-08-23] 通道3 存库序列化 hits（results 列）+
     * [websearch-ccalign T2] {@link #mapToToolResultBlockParam} 解析 data JSON。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * DB settings 读链 · [websearch-ccalign 2026-08-23] WebSearch 配置统一入 DB settings
     * （websearch_engine / api_key / proxy / websearch_use_small_model，前端可配置）。
     * {@code @Autowired(required=false)}：测试/孤立运行未注入 → {@link #readSettings()} 返回 null → 回默认。
     */
    @Autowired(required = false)
    private SettingsMapper settingsMapper;

    /**
     * [websearch-resid R-A] settings 源（接线内部裸 webFetch → duckduckgo 走 settings.proxy）。
     *
     * <p>{@code @Autowired(required=false)}：测试/孤立运行未注入 → {@link #wireWebFetchSettingsService()}
     * 不接线内部 webFetch（默认无代理，零行为变化）。
     */
    @Autowired(required = false)
    private SettingsService settingsService;

    /**
     * 主循环模型寻址 · [websearch-ccalign 2026-08-23] 对齐 CC {@code context.options.mainLoopModel}
     * （WebSearchTool.ts:280）：useSmall=false 时注释模型走会话主循环模型
     * （AgentState.currentModel，SessionAgentStateRegistry 按 sessionId 寻址）。
     * {@code @Autowired(required=false)}：测试/孤立运行未注入 → 回落 fast 档。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /** 测试注入：anysearch HttpClient（生产 null → AnySearchEngine 默认）。 */
    HttpClient anysearchHttpClient;

    public WebSearchTool() {
        this(new WebFetchTool());
    }

    public WebSearchTool(WebFetchTool webFetch) {
        this(webFetch, null);
    }

    /** 测试构造：强制指定引擎（跳过 {@code @Value} 配置解析）。 */
    WebSearchTool(WebFetchTool webFetch, SearchEngine forcedEngine) {
        this(webFetch, forcedEngine, null, null);
    }

    /**
     * 测试构造：强制指定引擎 + 弱模型总结依赖（llmProviderFactory + modelConfigResolver）。
     *
     * <p>[G19/G20 调整] 生产经 Spring 字段注入；测试经本构造注入 mock，验证弱模型总结成功
     * 与降级 fallback 路径。
     */
    WebSearchTool(WebFetchTool webFetch, SearchEngine forcedEngine,
                  LlmProviderFactory llmProviderFactory, ModelConfigResolver modelConfigResolver) {
        this.webFetch = webFetch;
        this.duckDuckGoEngine = new DuckDuckGoEngine(webFetch);
        this.forcedEngine = forcedEngine;
        this.llmProviderFactory = llmProviderFactory;
        this.modelConfigResolver = modelConfigResolver;
    }

    /**
     * [websearch-resid R-A] Spring 装配后把 settingsService 接入内部裸 new 的 webFetch
     * （duckduckgo 经 WebFetchTool.resolveSecurity 走 settings.proxy）。
     *
     * <p>效果链：Spring 实例化 {@code new WebSearchTool()}（内部 webFetch 为裸 {@code new WebFetchTool()}，
     * 其上的 {@code @Autowired} 字段全 null）→ 注入本字段 settingsService → {@code @PostConstruct}
     * 手动 {@link WebFetchTool#setSettingsService} 接入 → DuckDuckGoEngine 抓 HTML 时
     * WebFetchTool.resolveSecurity() 从 settings.proxy 构建带代理 HttpClient。
     *
     * <p>测试/孤立运行（settingsService 未注入）→ 不接线（内部 webFetch 默认无代理，零行为变化）。
     */
    @PostConstruct
    void wireWebFetchSettingsService() {
        if (settingsService != null) {
            webFetch.setSettingsService(settingsService);
            if (log.isInfoEnabled()) {
                log.info("[WebSearchTool] 内部 webFetch 已接入 settingsService（duckduckgo 抓取走 settings.proxy）");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[WebSearchTool] settingsService 未注入，内部 webFetch 保持默认无代理（测试/孤立运行）");
            }
        }
    }

    /**
     * DB settings 读链（id=1 singleton）· [websearch-ccalign 2026-08-23] WebSearch 配置统一入 DB。
     * null-safe：settingsMapper 未注入（测试/孤立运行）或读取异常 → null（调用方回默认，fail-loud warn）。
     */
    private SettingsRecord readSettings() {
        if (settingsMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("[WebSearchTool] settingsMapper 未注入（测试/孤立运行），WebSearch 配置回默认");
            }
            return null;
        }
        try {
            return settingsMapper.selectOneById(1);
        } catch (Exception e) {
            log.warn("[WebSearchTool] settings 读取失败（回默认）: err={}", e.getMessage());
            return null;
        }
    }

    /** 引擎名 · DB settings.websearchEngine（null/blank → "anysearch" 默认，用户 2026-08-23 拍板 改动5）。 */
    private String readEngine(SettingsRecord settings) {
        if (settings != null && settings.getWebsearchEngine() != null
                && !settings.getWebsearchEngine().isBlank()) {
            return settings.getWebsearchEngine().trim().toLowerCase(Locale.ROOT);
        }
        return "anysearch";
    }

    /**
     * API key · DB settings.apiKey（null/blank → 内置默认 {@link #DEFAULT_ANYSEARCH_API_KEY} 兜底，
     * 用户 2026-08-23 拍板）。anysearch 作 Bearer 认证；duckduckgo 不用 api_key。
     */
    private String readApiKey(SettingsRecord settings) {
        if (settings != null && settings.getApiKey() != null && !settings.getApiKey().isBlank()) {
            return settings.getApiKey();
        }
        return DEFAULT_ANYSEARCH_API_KEY;
    }

    /** proxy · DB settings.proxy（String host:port；null/blank → null 直连，仅 anysearch 引擎应用）。 */
    private String readProxy(SettingsRecord settings) {
        if (settings != null && settings.getProxy() != null && !settings.getProxy().isBlank()) {
            return settings.getProxy();
        }
        return null;
    }

    /**
     * base-url · [websearch-resid R-B] DB settings.websearchBaseUrl（V38 列 websearch_base_url；
     * null/blank → {@link AnySearchEngine#DEFAULT_BASE_URL} 兜底，AnySearchEngine.java:44）。
     * anysearch API base URL（POST {@code {baseUrl}/v1/search}）。
     */
    private String readBaseUrl(SettingsRecord settings) {
        if (settings != null && settings.getWebsearchBaseUrl() != null
                && !settings.getWebsearchBaseUrl().isBlank()) {
            return settings.getWebsearchBaseUrl().trim();
        }
        return AnySearchEngine.DEFAULT_BASE_URL;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search the web for the given query and return a list of search hits "
                + "(title + URL). Backend is selectable via the websearch engine configuration "
                + "(anysearch / duckduckgo).";
    }

    /** 搜索提示 · 对齐 CC WebSearchTool.ts:154 searchHint（逐字）。 */
    @Override
    public String searchHint() {
        return "search the web for current information";
    }

    /** 是否延迟执行 · 对齐 CC WebSearchTool.ts:156 shouldDefer: true。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /** 只读 · 对齐 CC WebSearchTool.ts:203-205 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 可并发 · 对齐 CC WebSearchTool.ts:200-202 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 结果落盘阈值 100K · 对齐 CC WebSearchTool.ts:155 maxResultSizeChars: 100_000。 */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /** 用户可见名 · 对齐 CC WebSearchTool.ts:160-162 userFacingName() → 'Web Search'。 */
    @Override
    public String userFacingName() {
        return "Web Search";
    }

    /** 自动分类器输入 · 对齐 CC WebSearchTool.ts:206-208 toAutoClassifierInput(input) = query。 */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        String query = readString(input, "query");
        return query == null ? "" : query;
    }

    /** 工具提示词 · 对齐 CC WebSearchTool.ts:223-225 prompt() = getWebSearchPrompt()。 */
    @Override
    public String prompt() {
        return getWebSearchPrompt();
    }

    /**
     * WebSearch 系统提示词 · 移植 CC {@code prompt.ts getWebSearchPrompt()}（WebSearchTool/prompt.ts:5-33）。
     *
     * <p>当前月份按 CC {@code getLocalMonthYear()}（constants/common.ts:28-33）：
     * {@code en-US} {@code {month: 'long', year: 'numeric'}}（e.g. "August 2026"）。
     */
    static String getWebSearchPrompt() {
        String currentMonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
                .format(LocalDate.now());
        return """
                - Allows Claude to search the web and use the results to inform responses
                - Provides up-to-date information for current events and recent data
                - Returns search result information formatted as search result blocks, including links as markdown hyperlinks
                - Use this tool for accessing information beyond Claude's knowledge cutoff
                - Searches are performed automatically within a single API call

                CRITICAL REQUIREMENT - You MUST follow this:
                  - After answering the user's question, you MUST include a "Sources:" section at the end of your response
                  - In the Sources section, list all relevant URLs from the search results as markdown hyperlinks: [Title](URL)
                  - This is MANDATORY - never skip including sources in your response
                  - Example format:

                    [Your answer here]

                    Sources:
                    - [Source Title 1](https://example.com/1)
                    - [Source Title 2](https://example.com/2)

                Usage notes:
                  - Domain filtering is supported to include or block specific websites
                  - Web search is only available in the US

                IMPORTANT - Use the correct year in search queries:
                  - The current month is %s. You MUST use this year when searching for recent information, documentation, or current events.
                  - Example: If the user asks for "latest React docs", search for "React documentation" with the current year, NOT last year
                """.formatted(currentMonthYear);
    }

    /**
     * 输入 schema · 对齐 CC WebSearchTool.ts:25-37 {@code z.strictObject({query, allowed_domains,
     * blocked_domains})}。query {@code z.string().min(2)} → {@code minLength: 2}。
     * max_results 删除（D-TR-H1-06）。
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("minLength", 2);
        query.put("description", "The search query to use");

        ObjectNode allowedDomains = props.putObject("allowed_domains");
        allowedDomains.put("type", "array");
        allowedDomains.put("items", JsonNodeFactory.instance.objectNode().put("type", "string"));
        allowedDomains.put("description", "Only include search results from these domains");

        ObjectNode blockedDomains = props.putObject("blocked_domains");
        blockedDomains.put("type", "array");
        blockedDomains.put("items", JsonNodeFactory.instance.objectNode().put("type", "string"));
        blockedDomains.put("description", "Never include search results from these domains");

        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 输出 schema · 对齐 CC {@code outputSchema}（WebSearchTool.ts:56-67）：
     * <pre>
     * { query: string,
     *   results: array[ union(
     *       {tool_use_id: string, content: [{title: string, url: string}]},   // searchResultSchema :42-52
     *       string),                                                           // 文本注释
     *   durationSeconds: number }
     * </pre>
     * required = [query, results, durationSeconds]。summary 顶层字段已删除（D2/D3，CC 无该字段）。
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string");

        // results: array[ union({tool_use_id, content:[{title,url}]}, string) ]
        ObjectNode results = props.putObject("results");
        results.put("type", "array");
        ObjectNode items = results.putObject("items");
        ArrayNode anyOf = items.putArray("anyOf");
        // ① SearchResult 块（CC searchResultSchema :42-52）
        ObjectNode searchResult = anyOf.addObject();
        searchResult.put("type", "object");
        ObjectNode srProps = searchResult.putObject("properties");
        srProps.putObject("tool_use_id").put("type", "string");
        ObjectNode content = srProps.putObject("content");
        content.put("type", "array");
        ObjectNode hit = content.putObject("items");
        hit.put("type", "object");
        ObjectNode hitProps = hit.putObject("properties");
        hitProps.putObject("title").put("type", "string");
        hitProps.putObject("url").put("type", "string");
        // ② 文本注释（string）
        anyOf.addObject().put("type", "string");

        props.putObject("durationSeconds").put("type", "number");
        schema.putArray("required").add("query").add("results").add("durationSeconds");
        return schema;
    }

    /**
     * 输入语义验证 · 对齐 CC WebSearchTool.ts:235-253 validateInput。
     * <ul>
     *   <li>query 为空 → errorCode 1 "Error: Missing query"</li>
     *   <li>allowed_domains 与 blocked_domains 同时非空 → errorCode 2
     *       "Error: Cannot specify both allowed_domains and blocked_domains in the same request"</li>
     * </ul>
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String query = readString(input, "query");
        if (query == null || query.isEmpty()) {
            return ValidationResult.fail("1", "Error: Missing query");
        }
        List<String> allowed = readStringList(input, "allowed_domains");
        List<String> blocked = readStringList(input, "blocked_domains");
        if (allowed != null && !allowed.isEmpty() && blocked != null && !blocked.isEmpty()) {
            return ValidationResult.fail("2",
                    "Error: Cannot specify both allowed_domains and blocked_domains in the same request");
        }
        return ValidationResult.pass();
    }

    /**
     * tool_result 块渲染 · 对齐 CC {@code mapToolResultToToolResultBlockParam}
     * （WebSearchTool.ts:401-434）：
     * <pre>
     * "Web search results for query: \"{query}\"\n\n"
     * + 逐项（results 数组）：
     *     string 项 → 原文 + "\n\n"
     *     对象项 content 非空 → "Links: {JSON}\n\n"（CC :419）
     *     对象项 content 空 → "No links found.\n\n"（CC :421）
     * + "\nREMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks."
     * </pre>
     * 最终 content = 整体 {@code trim()}（CC :432）。null 项防御（CC :410-412，compact/transcript 反序列化后
     * 可能出现）。data 解析失败（error 结果 / 非 JSON）→ 复用默认渲染兜底（契约不中断）。
     *
     * <p>[websearch-ccalign] D5 收口：IMP-C2 3 参签名已收敛（Tool.java:937 默认兜底仅适用未 override 工具），
     * 本 override 落地 CC 渲染（LlmAgentLoop:9719 / SubagentExecutor:5348 消费 3 参签名）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (result == null) {
            return null;
        }
        try {
            JsonNode data = (result.data() instanceof String s) ? MAPPER.readTree(s) : MAPPER.valueToTree(result.data());
            String query = data.path("query").asText("");
            StringBuilder formatted = new StringBuilder();
            formatted.append("Web search results for query: \"").append(query).append("\"\n\n");
            JsonNode results = data.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    if (r == null || r.isNull()) {
                        continue; // 对齐 CC :410-412 防御 null 项
                    }
                    if (r.isTextual()) {
                        formatted.append(r.asText()).append("\n\n");
                    } else {
                        JsonNode content = r.path("content");
                        if (content.isArray() && !content.isEmpty()) {
                            formatted.append("Links: ").append(content.toString()).append("\n\n");
                        } else {
                            formatted.append("No links found.\n\n");
                        }
                    }
                }
            }
            formatted.append("\nREMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks.");
            return new ToolResultBlockParam(toolUseId, "tool_result", formatted.toString().trim(), isError);
        } catch (Exception e) {
            log.warn("[WebSearchTool] mapToToolResultBlockParam 解析失败，复用默认渲染兜底: err={}", e.getMessage());
            return new ToolResultBlockParam(toolUseId, "tool_result",
                    ToolResult.renderToolResultPayloadText((ToolResult<?>) result), isError);
        }
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String query = readString(input, "query");
        List<String> allowed = readStringList(input, "allowed_domains");
        List<String> blocked = readStringList(input, "blocked_domains");
        if (query == null || query.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: query");
        }
        SearchEngine engine = resolveEngine();
        long start = System.nanoTime();
        try {
            List<SearchHit> hits = engine.search(new SearchRequest(query, allowed, blocked));
            // 内部上限（D-TR-H1-06 删 max_results 输入；DEFAULT_RESULTS 对齐 CC max_uses=8 语义）
            if (hits.size() > DEFAULT_RESULTS) {
                hits = new ArrayList<>(hits.subList(0, DEFAULT_RESULTS));
            }
            double durationSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
            // 原始 hits 日志记录（测试/调试；LLM 返回经 results 数组 hits 块 + 注释文本，G19/G20 保留日志）
            logRawHits(query, engine, hits, durationSeconds);
            // 通道2/3 已停用（2026-08-23 前端仅从工具输入输出 tool_result §33.2 outputShape 抽取，
            // 事件/存库不再发布/落库）——原 publishResults/storeResults 方法保留 @Deprecated 供审计
            // 注释模型二次总结（websearch-ccalign：useSmall ? fast : mainLoop，CC WebSearchTool.ts:280）；
            // 失败降级为简单摘要文本（仍在 results 末项），搜索不中断
            boolean isNonInteractive = ctx != null && ctx.isNonInteractiveSession();
            String commentary = summarizeHits(query, hits, ctx, isNonInteractive);
            return ToolResult.success(call.id(), buildOutput(query, call.id(), hits, commentary, durationSeconds));
        } catch (Exception e) {
            log.warn("[WebSearchTool] 搜索失败: query='{}' engine={} err={}",
                    query, engine.name(), e.getMessage());
            return ToolResult.error(call.id(), "search failed: " + e.getMessage());
        }
    }

    /**
     * <b>2026-08-23 前端仅从工具输入输出（tool_result §33.2 outputShape）抽取 WebSearch 格式，本通道
     * 已停用；保留方法供未来审计，execute 不再调用。</b>
     *
     * <p>通道2 · STOMP 推送 {@link WebSearchResultEvent} 到 {@code /topic/sessions/{sessionId}/websearch-results}。
     *
     * <p><b>AC-5 隔离（硬性）</b>：{@code ctx == null}（execute(call, null) 路径，WebSearchToolTest 既有直构）
     * → warn+skip（不触碰 ctx，防 NPE）；{@code wsTemplate} 未注入 / {@code convertAndSend} 抛异常 →
     * 内部 catch → warn，<b>绝不向外抛</b>——否则会被 execute 外层 catch 捕获，把成功搜索翻成
     * {@code ToolResult.error}。发布为旁路副作用，不中断搜索。
     *
     * @param call            工具调用块（{@code toolUseId = call.id()}，前端凭此与消息流 tool_use 块绑定）
     * @param ctx             工具上下文（{@code ctx.sessionId()} 为 UUID，非 null 必填）
     * @param query           本次搜索词
     * @param hits            截断后命中列表（对齐 CC searchHitSchema {@code {title, url}}）
     * @param durationSeconds 本次搜索耗时（秒）
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    private void publishResults(ToolUseBlock call, ToolUseContext ctx,
                                String query, List<SearchHit> hits, double durationSeconds) {
        if (ctx == null) {
            log.warn("[WebSearchTool] 通道2发布跳过: ctx 为 null（无 session 可路由 topic）");
            return;
        }
        try {
            String sessionId = ctx.sessionId();
            String topic = "/topic/sessions/" + sessionId + "/websearch-results";
            if (wsTemplate == null) {
                log.warn("[WebSearchTool] 通道2发布跳过: wsTemplate 未注入 sessionId={}", sessionId);
                return;
            }
            WebSearchResultEvent evt = new WebSearchResultEvent(
                    sessionId, call.id(), query, hits, durationSeconds);
            wsTemplate.convertAndSend(topic, evt);
            if (log.isInfoEnabled()) {
                log.info("[WebSearchTool] 通道2已推送: sessionId={} topic={} toolUseId={} hits={}",
                        sessionId, topic, call.id(), hits.size());
            }
        } catch (Exception e) {
            log.warn("[WebSearchTool] 通道2发布失败（不中断搜索）: query='{}' err={}", query, e.getMessage());
        }
    }

    /**
     * <b>2026-08-23 前端仅从工具输入输出（tool_result §33.2 outputShape）抽取 WebSearch 格式，本通道
     * 已停用；保留方法供未来审计，execute 不再调用。</b>
     *
     * <p>通道3 · {@link WebSearchResultRecord} 落库（sessionId/toolUseId/query/results(JSON)/durationSeconds）。
     *
     * <p><b>AC-5 隔离（硬性）</b>：{@code ctx == null} → warn+skip（无 session_id 可落库）；{@code mapper}
     * 未注入 / JSON 序列化 / {@code insert} 抛异常 → 内部 catch → warn，<b>绝不向外抛</b>（同上，防外层
     * catch 翻盘主搜索）。{@code createdAt} 不 set，由 DB {@code datetime('now')} 兜底（对齐 ChatService
     * 插 ToolCallRecord）。存库为旁路副作用，不中断搜索。
     *
     * @param call            工具调用块（{@code id = toolUseId} 作主键，单次 execute = 1 条）
     * @param ctx             工具上下文（{@code ctx.sessionId()} 为 UUID）
     * @param query           本次搜索词
     * @param hits            截断后命中列表（序列化为 JSON 数组 {@code [{title,url},...]}）
     * @param durationSeconds 本次搜索耗时（秒）
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    private void storeResults(ToolUseBlock call, ToolUseContext ctx,
                              String query, List<SearchHit> hits, double durationSeconds) {
        if (ctx == null) {
            log.warn("[WebSearchTool] 通道3存库跳过: ctx 为 null（无 session_id 可落库）");
            return;
        }
        try {
            String sessionId = ctx.sessionId();
            if (webSearchResultMapper == null) {
                log.warn("[WebSearchTool] 通道3存库跳过: webSearchResultMapper 未注入 sessionId={}", sessionId);
                return;
            }
            String resultsJson = MAPPER.writeValueAsString(hits);
            WebSearchResultRecord rec = new WebSearchResultRecord();
            rec.setId(call.id());
            rec.setSessionId(sessionId);
            rec.setToolUseId(call.id());
            rec.setQuery(query);
            rec.setResults(resultsJson);
            rec.setDurationSeconds(durationSeconds);
            webSearchResultMapper.insert(rec);
            if (log.isInfoEnabled()) {
                log.info("[WebSearchTool] 通道3已存库: sessionId={} toolUseId={} query='{}' hits={}",
                        sessionId, call.id(), query, hits.size());
            }
        } catch (Exception e) {
            log.warn("[WebSearchTool] 通道3存库失败（不中断搜索）: query='{}' err={}", query, e.getMessage());
        }
    }

    /**
     * 输出组装 · 对齐 CC {@code makeOutputFromSearchResponse}（WebSearchTool.ts:86-150）：
     * <pre>
     * { query,
     *   results: [
     *     { tool_use_id: "…", content: [{title,url}, ...] },   // 首项 = 一次搜索的 hits 块
     *     "注释模型文本注释",                                    // 末项 = 文本注释（CC textAcc → string 项）
     *   ],
     *   durationSeconds: number }
     * </pre>
     * hits 块 {@code tool_use_id} = {@code call.id()}（Java 端单次 execute = 1 次搜索，等价 CC
     * server-tool-use id，WebSearchTool.ts:126）；与通道2/3 的 {@code toolUseId = call.id()} 同键。
     * 原始 hits 列表既给 LLM（results 首项）也进通道2/3（前端事件 + 落库）。
     *
     * @param query           搜索词
     * @param toolUseId       工具调用 id（hits 块 tool_use_id）
     * @param hits            搜索命中（截断后，{@code {title,url}} 对齐 CC searchHitSchema :43-46）
     * @param commentary      注释模型文本注释（可为空 → 不追加 string 项，对齐 CC :141-143 textAcc 空不 push）
     * @param durationSeconds 本次搜索耗时（秒）
     */
    private String buildOutput(String query, String toolUseId, List<SearchHit> hits,
                               String commentary, double durationSeconds) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("query", query);
        ArrayNode results = node.putArray("results");
        // 首项：SearchResult 块（CC searchResultSchema :42-52）
        ObjectNode hitsBlock = results.addObject();
        hitsBlock.put("tool_use_id", toolUseId);
        ArrayNode content = hitsBlock.putArray("content");
        for (SearchHit hit : hits) {
            ObjectNode item = content.addObject();
            item.put("title", hit.title());
            item.put("url", hit.url());
        }
        // 末项：文本注释（CC :141-143，textAcc 非空才 push）
        if (commentary != null && !commentary.isBlank()) {
            results.add(commentary);
        }
        node.put("durationSeconds", durationSeconds);
        return node.toString();
    }

    /**
     * 注释模型二次总结 · [websearch-ccalign 2026-08-23] 复用 {@code chatWithOptions} 模式（对齐
     * {@code HaikuToolUseSummaryGenerator}），模型由 {@link #resolveCommentModelName} 决定
     * （useSmall ? fast 档 : 主循环模型，对齐 CC WebSearchTool.ts:280）。
     *
     * <p>CC 锚点：CC 模型侧二次处理经 server tool 产出的文本注释（results 中 string 项，
     * makeOutputFromSearchResponse :103-143）即"整体摘要"；Java 端无官方通道，用注释模型总结等价表达。
     *
     * <p>降级（搜索不中断）：注释模型依赖未注入 / 模型名解析失败 / 配置解析失败 / 调用失败 /
     * 返回空摘要 → 返回简单摘要兜底（仍在 results 末项），日志标注降级原因。
     *
     * @param query            搜索词
     * @param hits             搜索命中（用于生成注释文本）
     * @param ctx              工具上下文（主循环模型寻址用；null → 回落 fast 档）
     * @param isNonInteractive 是否非交互会话（透传注释模型调用 options，对齐 CC isNonInteractiveSession）
     * @return 注释模型文本；失败 → 简单摘要兜底
     */
    private String summarizeHits(String query, List<SearchHit> hits, ToolUseContext ctx,
                                 boolean isNonInteractive) {
        if (llmProviderFactory == null || modelConfigResolver == null) {
            return buildFallbackSummary(query, hits, "注释模型依赖未注入（llmProviderFactory/modelConfigResolver 为空）");
        }
        try {
            String modelName = resolveCommentModelName(ctx);
            if (modelName == null || modelName.isBlank()) {
                return buildFallbackSummary(query, hits, "注释模型名解析失败");
            }
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
                return buildFallbackSummary(query, hits, "注释模型配置解析失败 modelName=" + modelName);
            }
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                    List.of(),   // history — 单条 userPrompt（对齐 CC queryHaiku messages=[userMessage]）
                    null,        // tools — 无
                    null,        // outputFormat — 未设
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    null,        // temperature — 未设
                    QUERY_SOURCE, // querySource='web_search_tool'（对齐 CC WebSearchTool.ts:285）
                    null,        // abortController — WebSearch 无 signal 通道
                    null,        // maxTokens — 未设（provider 回落模型缺省）
                    null,        // skipCacheWrite — CC 未设（= false，写 cache）
                    Boolean.TRUE, // enablePromptCaching — 对齐 queryHaiku :75 true
                    List.of(),   // agents — []
                    Boolean.FALSE, // hasAppendSystemPrompt — false
                    List.of(),   // mcpTools — []
                    isNonInteractive); // isNonInteractiveSession 透传
            String summary = llmProviderFactory.getProvider(resolved.config(), resolved.providerType())
                    .chatWithOptions(resolved.config(), modelName, SUMMARY_SYSTEM_PROMPT,
                            buildSummaryUserPrompt(query, hits), options);
            if (summary == null || summary.isBlank()) {
                return buildFallbackSummary(query, hits, "注释模型返回空摘要");
            }
            if (log.isInfoEnabled()) {
                log.info("[WebSearchTool] 注释模型总结成功: query='{}' model={} summaryChars={} · websearch-ccalign（CC WebSearchTool.ts:280 useHaiku ? fast : mainLoop）",
                        query, modelName, summary.length());
            }
            return summary.trim();
        } catch (Exception e) {
            return buildFallbackSummary(query, hits, e.getMessage());
        }
    }

    /**
     * 注释模型名解析 · 对齐 CC WebSearchTool.ts:262-280：
     * <pre>
     * model = useHaiku ? getSmallFastModel() : context.options.mainLoopModel
     * </pre>
     * <ul>
     *   <li><b>useSmall</b>：DB settings.websearchUseSmallModel（CC original: {@code tengu_plum_vx3}
     *       feature flag，:262-265），null → false。</li>
     *   <li><b>fast 档</b>：{@code modelConfigResolver.resolveFastModelName("claude-haiku-4-5-20251001")}
     *       （fast→weak→固定默认 三级，对齐 CC getSmallFastModel model.ts:36-37 + getDefaultHaikuModel
     *       model.ts:131-138）。</li>
     *   <li><b>主循环模型</b>：CC {@code context.options.mainLoopModel} = 会话主循环模型，Java 端 =
     *       AgentState.currentModel（LlmAgentLoop.run 落盘），经 SessionAgentStateRegistry 按 sessionId 寻址。</li>
     *   <li><b>回落</b>（fail-loud，规则十二）：ctx null / registry 未注入 / currentModel null → 回落
     *       fast 档并 warn 标注回落原因。</li>
     * </ul>
     *
     * @param ctx 工具上下文（{@code ctx.sessionId()} 寻址主 AgentState）
     * @return 注释模型名（fast 档或主循环模型；不会返回 null）
     */
    private String resolveCommentModelName(ToolUseContext ctx) {
        boolean useSmall = false;
        try {
            SettingsRecord settings = readSettings();
            useSmall = settings != null && Boolean.TRUE.equals(settings.getWebsearchUseSmallModel());
        } catch (Exception e) {
            log.warn("[WebSearchTool] settings 读取失败（useSmall 按 false 处理）: err={}", e.getMessage());
        }
        if (useSmall) {
            String fast = modelConfigResolver.resolveFastModelName(FAST_FALLBACK_MODEL);
            if (log.isDebugEnabled()) {
                log.debug("[WebSearchTool] useSmall=true（CC tengu_plum_vx3, WebSearchTool.ts:262-265）→ 注释模型走 fast 档: {}",
                        fast);
            }
            return fast;
        }
        // 主循环模型（CC context.options.mainLoopModel，WebSearchTool.ts:280）
        String mainLoop = null;
        if (ctx != null && sessionAgentStateRegistry != null) {
            AgentState state = sessionAgentStateRegistry.get(ctx.sessionId());
            if (state != null && state.currentModel() != null && !state.currentModel().isBlank()) {
                mainLoop = state.currentModel();
            }
        }
        if (mainLoop != null) {
            if (log.isDebugEnabled()) {
                log.debug("[WebSearchTool] useSmall=false → 注释模型走主循环模型: {}（CC mainLoopModel, WebSearchTool.ts:280）",
                        mainLoop);
            }
            return mainLoop;
        }
        String reason = ctx == null ? "ctx 为 null（execute(call, null) 路径）"
                : sessionAgentStateRegistry == null ? "SessionAgentStateRegistry 未注入"
                : "主循环模型 currentModel 为 null/blank（非主循环上下文）";
        String fast = modelConfigResolver.resolveFastModelName(FAST_FALLBACK_MODEL);
        log.warn("[WebSearchTool] 主循环模型不可得（{}），回落 fast 档: {}（fail-loud, 不静默）", reason, fast);
        return fast;
    }

    /** 弱模型总结 user prompt · 搜索词 + 命中列表（title + url）。 */
    private static String buildSummaryUserPrompt(String query, List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search query: ").append(query).append("\n\nSearch results:\n");
        for (SearchHit hit : hits) {
            sb.append("- ").append(hit.title()).append(" (").append(hit.url()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * 降级简单摘要 · 保留原始结果给 LLM 兜底（G19/G20 拍板），搜索不中断。
     *
     * <p>渲染风格对齐 CC {@code mapToolResultToToolResultBlockParam}（WebSearchTool.ts:401-434：
     * "Web search results for query: ..." + markdown 链接）。降级原因写 warn 日志。
     */
    private String buildFallbackSummary(String query, List<SearchHit> hits, String reason) {
        log.warn("[WebSearchTool] 弱模型总结降级, 返回简单摘要兜底（搜索不中断）: query='{}' reason={}",
                query, reason);
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for query: \"").append(query).append("\"\n\n");
        for (SearchHit hit : hits) {
            sb.append("- [").append(hit.title()).append("](").append(hit.url()).append(")\n");
        }
        return sb.toString();
    }

    /** 搜索原始结果仅日志/测试记录 · 对齐 CC 输出前 raw hits 不入 LLM 返回（G19/G20 拍板）。 */
    private static void logRawHits(String query, SearchEngine engine, List<SearchHit> hits, double durationSeconds) {
        if (log.isDebugEnabled()) {
            log.debug("[WebSearchTool] 搜索原始结果(仅日志/测试，不进入 LLM 返回): query='{}' engine={} hits={} durationSeconds={}",
                    query, engine.name(),
                    hits.stream().map(h -> h.title() + " (" + h.url() + ")").toList(),
                    java.math.BigDecimal.valueOf(durationSeconds).setScale(3, java.math.RoundingMode.HALF_UP));
        }
    }

    /**
     * 按 DB settings.websearchEngine 选择后端引擎（anysearch/duckduckgo，缺省 anysearch，
     * 用户 2026-08-23 拍板 改动5 · 配置统一入 DB settings）。
     *
     * <p>api_key/proxy/base-url 同为 DB settings（websearch_engine 判断走哪个引擎）；anysearch 构造时
     * 透传 apiKey（空 → {@link #DEFAULT_ANYSEARCH_API_KEY} 兜底）+ proxy（空 → 直连，
     * AnySearchEngine 构造加 proxy 参数，HttpClient ProxySelector）+ baseUrl（V38 列
     * websearch_base_url，空 → {@link AnySearchEngine#DEFAULT_BASE_URL} 兜底）。
     * duckduckgo 走 HTML 抓取不用 api_key；proxy 经 {@link #wireWebFetchSettingsService()}
     * 接入内部 webFetch → DuckDuckGoEngine 经 WebFetchTool 走 settings.proxy（[websearch-resid R-A] 已接线）。
     *
     * <p>未知引擎名 fail-loud 回退 anysearch（warn，不静默）。
     */
    SearchEngine resolveEngine() {
        if (forcedEngine != null) {
            return forcedEngine;
        }
        SettingsRecord settings = readSettings();
        String engine = readEngine(settings);
        if ("duckduckgo".equals(engine)) {
            return duckDuckGoEngine;
        }
        if (!"anysearch".equals(engine)) {
            log.warn("[WebSearchTool] 未知搜索引擎 engine='{}'，回退 anysearch", engine);
        }
        String apiKey = readApiKey(settings);
        String proxy = readProxy(settings);
        // [websearch-resid R-B] base-url 走 DB settings（V38 列 websearch_base_url；空 → 默认兜底），不再用 @Value
        String baseUrl = readBaseUrl(settings);
        if (log.isDebugEnabled()) {
            log.debug("[WebSearchTool] 引擎=anysearch: baseUrl={} apiKeyConfigured={} proxy={}",
                    baseUrl, !DEFAULT_ANYSEARCH_API_KEY.equals(apiKey),
                    proxy == null ? "直连" : proxy);
        }
        return new AnySearchEngine(baseUrl, apiKey, proxy, anysearchHttpClient);
    }

    private static List<String> readStringList(JsonNode input, String key) {
        if (input == null || !input.has(key)) {
            return null;
        }
        JsonNode arr = input.path(key);
        if (!arr.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        arr.forEach(n -> {
            if (n.isTextual()) {
                out.add(n.asText());
            }
        });
        return out;
    }

    private static String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }
}

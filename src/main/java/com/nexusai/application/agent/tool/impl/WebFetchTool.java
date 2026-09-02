package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * WebFetchTool — 对齐 CC {@code WebFetchTool.ts}（P0-3 / IMP-H1 WebFetch SSRF 安全链移植）。
 *
 * <p><b>CC original:</b> {@code Open-ClaudeCode/src/tools/WebFetchTool/WebFetchTool.ts}（baseline
 * {@code 1992306b}）+ {@code utils.ts} + {@code preapproved.ts}（本实现委托 {@link WebFetchSecurity}
 * 与 {@link WebFetchPreapprovedHosts}）。
 *
 * <p><b>行为对齐</b>：
 * <ul>
 *   <li>inputSchema: {@code z.strictObject({url, prompt})}（WebFetchTool.ts:24-29，required=[url,prompt]）。</li>
 *   <li>outputSchema: {@code {bytes, code, codeText, result, durationMs, url}}（:32-45）。</li>
 *   <li>SSRF 安全链：{@link WebFetchSecurity}（validateURL / checkDomainBlocklist /
 *       isPermittedRedirect / 重定向管控 / 双缓存），域阻断/预检失败抛异常 → 工具 error。</li>
 *   <li>重定向不静默跟随：跨 host 重定向返回 {@code REDIRECT DETECTED} 提示（WebFetchTool.ts:217-249），
 *       模型需用重定向 URL 再次发起 WebFetch（CC 语义：一次请求=一次合法抓取，防 SSRF 重定向跳板）。</li>
 *   <li>UA 改 {@code Claude-User}（D-TR-H1-03，CC http.ts:56-58 getWebFetchUserAgent）。</li>
 *   <li>超时 60s（D-TR-H1-09，CC FETCH_TIMEOUT_MS=60_000）；max_bytes 删除（D-TR-H1-01，CC 用
 *       MAX_MARKDOWN_LENGTH/MAX_HTTP_CONTENT_LENGTH 表达截断）。</li>
 *   <li>{@code maxResultSizeChars=100_000}（WebFetchTool.ts:70）、isReadOnly/isConcurrencySafe=true
 *       （:95-100）、searchHint='fetch and extract content from a URL'（:68）。</li>
 * </ul>
 *
 * <p><b>受控残留（G20③ + websearch-ccalign T6）</b>：
 * <ul>
 *   <li><b>applyPromptToMarkdown 二次模型摘要</b>（CC utils.ts:484-530，queryHaiku）：<b>已接线</b>
 *       ——<b>[websearch-ccalign T6] 改 fast 档</b>（CC 真源 queryHaiku → {@code getSmallFastModel} = Haiku，
 *       model.ts:36-37，claude.ts:3278），经 {@code modelConfigResolver.resolveFastModelName} 解析
 *       （fast→weak→固定默认 三级）+ {@code chatWithOptions} 生成摘要；fast 档不可用/调用失败 →
 *       回退截断（抓取不中断，日志标注降级）。外部仍可经 {@link #setSecondaryModelPrompter} 覆盖
 *       prompter（测试/特殊注入）。</li>
 *   <li><b>二进制落盘</b>（[G20③]）：{@link WebFetchSecurity} 已按 CC utils.ts:435-449 落盘二进制内容
 *       （PDF 等），{@link #handleFetched} 在结果追加落盘备注（CC WebFetchTool.ts:280-285）。</li>
 * </ul>
 *
 * <p><b>[G20①] checkPermissions</b> 已补：预批准 host → Allow（CC WebFetchTool.ts:108-121）；
 * 非预批准 → Passthrough（通用管线 deny/ask/allow 规则裁决，CC :123-179 语义等价）。
 */
@Component
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    public static final String NAME = "WebFetch";

    /** 弱模型摘要 querySource · 对齐 CC WebFetchTool/utils.ts:508 {@code querySource: 'web_fetch_apply'}。 */
    private static final String QUERY_SOURCE = "web_fetch_apply";

    /**
     * SSRF 安全链 · [websearch-resid R-A] final → volatile 懒构建（{@link #resolveSecurity()}）：
     * 显式构造（测试/注入）→ 固定实例；默认构造 → 按 settings.proxy 构建（proxy 空 → 无代理）。
     */
    private volatile WebFetchSecurity security;

    /** [websearch-resid R-A] 当前 security 构建所用 proxy 源（缓存 key；null = 无代理构建）。 */
    private volatile String securityProxySource;

    /** [websearch-domaincheck] 当前 security 构建所用域预检端点源（缓存 key；null = 跳过预检构建）。 */
    private volatile String securityDomainCheckSource;

    /** [websearch-resid R-A] 显式 security 构造优先（测试/注入零行为变化；默认构造 false → resolveSecurity 生效）。 */
    private final boolean securityExplicit;

    /**
     * [websearch-resid R-A] settings 源（duckduckgo/WebFetch 读 settings.proxy）；未注入/孤立运行 → 默认无代理。
     * {@code required=false}：测试/孤立运行缺省 → {@link #currentProxy()} 返回 null（直连）。
     */
    @Autowired(required = false)
    private SettingsService settingsService;

    /** 二次模型依赖 · [G20③] 生产经 Spring 注入（{@code required=false} 测试/孤立运行可缺省 → 降级截断）。 */
    @Autowired(required = false)
    private LlmProviderFactory llmProviderFactory;

    /** 二次模型配置解析 · [G20③] 复用共享解析器（[websearch-ccalign T6] fast 档 resolveFastModelName + resolve）。 */
    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    /**
     * [G20③] 二次模型摘要调用器（可注入；null → fast 档 prompter → 均不可用则回退截断）。
     *
     * <p>对齐 CC {@code queryHaiku}（WebFetchTool/utils.ts:503-514）的 Java 注入点：
     * 入参为已组装好的 {@code makeSecondaryModelPrompt} 文本，返回模型文本响应。
     * 优先外部注入（测试/特殊接线）；未注入 → {@link #buildFastModelPrompter} 以
     * fast 档模型（CC getSmallFastModel = Haiku，model.ts:36-37）构建；均不可用 → 回退截断（抓取不中断）。
     */
    private java.util.function.Function<String, String> secondaryModelPrompter;

    /** [G20③] 二次模型摘要调用器注入（测试 / 外部注入优先，覆盖 fast 档 prompter）。 */
    public void setSecondaryModelPrompter(java.util.function.Function<String, String> secondaryModelPrompter) {
        this.secondaryModelPrompter = secondaryModelPrompter;
    }

    /**
     * [websearch-resid R-A] settings 源注入（WebSearchTool {@code @PostConstruct} 接线内部 webFetch 用；测试可用）。
     *
     * <p>供裸 {@code new WebFetchTool()}（非 Spring 装配）把 settingsService 手动接入——内部
     * {@code resolveSecurity()} 据此从 settings.proxy 构建带代理 HttpClient。
     */
    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 默认构造 · [websearch-resid R-A] 必须<b>直接赋值</b>（不可委托 {@code this(new WebFetchSecurity())}）：
     * 委托到 {@link #WebFetchTool(WebFetchSecurity)} 会把 {@link #securityExplicit} 置 true →
     * 生产路径 {@link #resolveSecurity()} 恒返回固定无代理 security → duckduckgo proxy 静默失效而测试
     * （全走显式构造）保持绿色 = 静默假绿（C9）。直接赋值 + {@code securityExplicit=false} →
     * {@code resolveSecurity()} 懒构建按 settings.proxy 生效。
     */
    public WebFetchTool() {
        this.security = new WebFetchSecurity();
        this.securityExplicit = false;
    }

    /** 测试 / 注入构造：显式传入安全链（可 mock 域预检端点与 HttpClient）→ {@link #securityExplicit}=true。 */
    public WebFetchTool(WebFetchSecurity security) {
        this.security = security;
        this.securityExplicit = true;
    }

    /**
     * 测试构造：显式安全链 + 二次模型依赖（llmProviderFactory + modelConfigResolver）→ {@link #securityExplicit}=true。
     *
     * <p>[G20③] 生产经 Spring 字段注入；测试经本构造注入 mock，验证二次模型摘要成功与降级 fallback 路径。
     */
    WebFetchTool(WebFetchSecurity security, LlmProviderFactory llmProviderFactory,
                 ModelConfigResolver modelConfigResolver) {
        this.security = security;
        this.securityExplicit = true;
        this.llmProviderFactory = llmProviderFactory;
        this.modelConfigResolver = modelConfigResolver;
    }

    /**
     * [websearch-resid R-A + websearch-domaincheck] 解析当前生效的 {@link WebFetchSecurity} · 用户「proxy 肯定接线」。
     *
     * <p><b>语义</b>：{@link #securityExplicit}=true（显式构造/测试注入）→ 恒返回注入实例
     * （既有测试零行为变化，验收 #2）；否则按 {@link #currentProxy()} + {@link #currentDomainCheckUrl()}
     * 懒构建——settings.proxy 非空 → {@link WebFetchSecurity#withProxy}（带 ProxySelector）；未注入/空 →
     * 默认无代理（直连，验收 #3）。settings.websearch_domain_check_url（V39）非空 → 预检该端点
     * （skipDomainCheck=false）；空 → 跳过预检（skipDomainCheck=true，不依赖 api.anthropic.com，
     * 用户 2026-08-23 拍板）。
     *
     * <p><b>缓存 key 契约（A3）</b>：proxy 与 domainCheckUrl 合并进 key——仅改 domainCheckUrl 必须触发
     * 重建（否则新预检端点不生效 = 静默失效）；实例复用保留（Caffeine 双缓存跨调用保留）。
     *
     * <p><b>线程安全（C1）</b>：WebFetchTool 为 Spring 单例，并发 execute 需 {@code volatile} +
     * {@code synchronized(this)} 双检锁，避免重复构建/读到半初始化 security。
     *
     * @return 当前生效的 WebFetchSecurity
     */
    private WebFetchSecurity resolveSecurity() {
        if (securityExplicit) {
            return security;
        }
        String proxy = currentProxy();
        String domainCheckUrl = currentDomainCheckUrl();
        if (security != null && Objects.equals(securityProxySource, proxy)
                && Objects.equals(securityDomainCheckSource, domainCheckUrl)) {
            return security;   // 缓存实例复用（Caffeine 双缓存跨调用保留）
        }
        synchronized (this) {  // 双检锁（C1）
            proxy = currentProxy();
            domainCheckUrl = currentDomainCheckUrl();
            if (security != null && Objects.equals(securityProxySource, proxy)
                    && Objects.equals(securityDomainCheckSource, domainCheckUrl)) {
                return security;
            }
            boolean proxyBlank = proxy == null || proxy.isBlank();
            boolean urlBlank = domainCheckUrl == null || domainCheckUrl.isBlank();
            security = (proxyBlank && urlBlank)
                    ? new WebFetchSecurity()
                    : WebFetchSecurity.withProxy(proxy, domainCheckUrl);
            securityProxySource = proxy;
            securityDomainCheckSource = domainCheckUrl;
            if (log.isInfoEnabled()) {
                log.info("[WebFetchTool] settings.proxy/websearchDomainCheckUrl 变更/懒构建，security 重建: proxy={} domainCheckUrl={}",
                        proxy == null ? "直连" : proxy,
                        domainCheckUrl == null ? "跳过预检" : domainCheckUrl);
            }
            return security;
        }
    }

    /**
     * [websearch-resid R-A] 当前 proxy 源（DB settings.proxy，{@code host:port}）· 直连语义。
     *
     * <p>{@link SettingsService} 未注入（测试/孤立运行）→ null（默认无代理）；{@code get()}
     * 异常 → warn + null（fail-loud，规则十二，不静默吞异常——配置读失败按直连处理不中断抓取）。
     *
     * @return proxy {@code host:port}；未配置/未注入/读取异常 → null（直连）
     */
    private String currentProxy() {
        if (settingsService == null) {
            return null;
        }
        try {
            return settingsService.get().proxy();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[WebFetchTool] settings 读取失败，proxy 按直连处理（fail-loud）: err={}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * [websearch-domaincheck] 当前域预检端点源（DB settings.websearch_domain_check_url，V39 列）· 跳过预检语义。
     *
     * <p>{@link SettingsService} 未注入（测试/孤立运行）→ null（默认跳过预检）；{@code get()}
     * 异常 → warn + null（fail-loud，规则十二，不静默吞异常——配置读失败按跳过预检处理不中断抓取）。
     *
     * @return 域预检端点；未配置/未注入/读取异常 → null（跳过预检）
     */
    private String currentDomainCheckUrl() {
        if (settingsService == null) {
            return null;
        }
        try {
            return settingsService.get().websearchDomainCheckUrl();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[WebFetchTool] settings 读取失败，域预检端点按跳过处理（fail-loud）: err={}", e.getMessage());
            }
            return null;
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Fetch content from a URL via HTTP GET and return the processed result. "
                + "The URL must be a fully-formed valid URL. HTTP URLs will be automatically upgraded to HTTPS. "
                + "This tool is read-only and does not modify any files. Includes a self-cleaning 15-minute cache. "
                + "When a URL redirects to a different host, the tool will inform you and provide the redirect URL "
                + "in a special format; you should then make a new WebFetch request with the redirect URL.";
    }

    /** 搜索提示 · 对齐 CC WebFetchTool.ts:68 searchHint（逐字）。 */
    @Override
    public String searchHint() {
        return "fetch and extract content from a URL";
    }

    /** 是否延迟执行 · 对齐 CC WebFetchTool.ts:71 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /** 只读 · 对齐 CC WebFetchTool.ts:98-100 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 可并发 · 对齐 CC WebFetchTool.ts:95-97 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 结果落盘阈值 100K 字符 · 对齐 CC WebFetchTool.ts:70 maxResultSizeChars: 100_000。 */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /** 用户可见名 · 对齐 CC WebFetchTool.ts:81-84 userFacingName() → 'Fetch'。 */
    @Override
    public String userFacingName() {
        return "Fetch";
    }

    /** 自动分类器输入 · 对齐 CC WebFetchTool.ts:101-103 toAutoClassifierInput。 */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        String url = input != null && input.has("url") ? input.get("url").asText() : "";
        String prompt = input != null && input.has("prompt") && !input.get("prompt").isNull()
                ? input.get("prompt").asText()
                : null;
        return (prompt != null && !prompt.isBlank()) ? url + ": " + prompt : url;
    }

    /** 输入 schema · 对齐 CC WebFetchTool.ts:24-29 {@code z.strictObject({url, prompt})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode url = props.putObject("url");
        url.put("type", "string");
        url.put("description", "The URL to fetch content from");

        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "The prompt to run on the fetched content");

        var required = schema.putArray("required");
        required.add("url");
        required.add("prompt");
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 输出 schema · 对齐 CC WebFetchTool.ts:32-45 outputSchema。 */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("bytes").put("type", "integer");
        props.putObject("code").put("type", "integer");
        props.putObject("codeText").put("type", "string");
        props.putObject("result").put("type", "string");
        props.putObject("durationMs").put("type", "integer");
        props.putObject("url").put("type", "string");
        schema.putArray("required").add("bytes").add("code").add("codeText").add("result")
                .add("durationMs").add("url");
        return schema;
    }

    /** 输入语义验证 · 对齐 CC WebFetchTool.ts:191-204 validateInput（errorCode 1 = invalid_url）。 */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String url = input != null && input.has("url") ? input.get("url").asText() : null;
        if (url == null || url.isBlank()) {
            return ValidationResult.fail("1", "Error: Invalid URL \""
                    + (url == null ? "" : url) + "\". The URL provided could not be parsed.");
        }
        try {
            java.net.URI.create(url);
            return ValidationResult.pass();
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("1", "Error: Invalid URL \"" + url
                    + "\". The URL provided could not be parsed.");
        }
    }

    /**
     * 权限表态 · 对齐 CC WebFetchTool.ts:104-180 checkPermissions。
     *
     * <p><b>[G20① 预批准 → allow]</b>：hostname+pathname 命中预批准表 → 返回 Allow
     * （decisionReason='Preapproved host'，CC :108-121）；否则返回 Passthrough——交通用权限管线
     * 的 deny/ask/allow 规则层裁决（CC :123-179 的 rule 查表 + 默认 ask 语义由管线 1a/1b/1f/3 层等价承载）。
     * 非预批准默认 ask（CC :175-179）：Java 管线 Passthrough → 第 3 层兜底 ask。
     *
     * @return Allow（预批准 host）或 Passthrough（交通用管线）
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        String url = input != null && input.has("url") ? input.get("url").asText() : null;
        if (url != null && !url.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(url);
                if (WebFetchPreapprovedHosts.isPreapprovedHost(uri.getHost(), uri.getPath())) {
                    if (log.isDebugEnabled()) {
                        log.debug("[WebFetchTool] 预批准 host → Allow: host={} path={}（CC "
                            + "WebFetchTool.ts:108-121 Preapproved host）", uri.getHost(), uri.getPath());
                    }
                    return new PermissionResult.Allow(
                        input,
                        new PermissionDecisionReason.Other("Preapproved host"),
                        null,
                        false,
                        null,
                        List.of());
                }
            } catch (IllegalArgumentException e) {
                // URL 解析失败 → 走通用管线（validateInput 会报 invalid_url）
                if (log.isDebugEnabled()) {
                    log.debug("[WebFetchTool] checkPermissions URL 解析失败, 走通用管线: {}", e.toString());
                }
            }
        }
        return new PermissionResult.Passthrough(
            "WebFetch 非预批准 host → 通用权限管线（deny/ask/allow 规则）",
            null,
            List.of(),
            null,
            null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String url = readString(input, "url");
        String prompt = readString(input, "prompt");

        if (url == null || url.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: url");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error(call.id(), "url must use http or https scheme: " + url);
        }

        long start = System.currentTimeMillis();
        try {
            // [websearch-resid R-A] 经 resolveSecurity() 解析（settings.proxy → withProxy / 默认无代理）
            Object outcome = resolveSecurity().getURLMarkdownContent(url);
            if (outcome instanceof WebFetchSecurity.RedirectInfo ri) {
                return handleRedirect(call, url, prompt, ri, start);
            }
            WebFetchSecurity.FetchedContent fc = (WebFetchSecurity.FetchedContent) outcome;
            boolean isNonInteractive = ctx != null && ctx.isNonInteractiveSession();
            return handleFetched(call, url, prompt, fc, start, isNonInteractive);
        } catch (WebFetchSecurity.DomainBlockedException e) {
            log.warn("[WebFetchTool] 域预检阻断: url={} reason={}", url, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (WebFetchSecurity.DomainCheckFailedException e) {
            log.warn("[WebFetchTool] 域预检失败: url={} reason={}", url, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (WebFetchSecurity.EgressBlockedException e) {
            log.warn("[WebFetchTool] egress 拦截: url={} reason={}", url, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (IllegalArgumentException e) {
            // validateURL 失败 / Too many redirects / Redirect missing Location / maxContentLength
            log.warn("[WebFetchTool] 抓取被安全链拒绝: url={} reason={}", url, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (Exception e) {
            log.warn("[WebFetchTool] 抓取失败: url={} err={}", url, e.getMessage());
            return ToolResult.error(call.id(), "fetch failed: " + e.getMessage());
        }
    }

    /**
     * 重定向未跟随 → REDIRECT DETECTED 提示 · 对齐 CC WebFetchTool.ts:217-249。
     *
     * <p>CC 语义：跨 host 重定向不自动跟随（SSRF 防护），把重定向信息 + 重新抓取指令返回给模型。
     */
    private AgentToolResult handleRedirect(ToolUseBlock call, String url, String prompt,
                                           WebFetchSecurity.RedirectInfo ri, long start) {
        String statusText = switch (ri.statusCode()) {
            case 301 -> "Moved Permanently";
            case 308 -> "Permanent Redirect";
            case 307 -> "Temporary Redirect";
            default -> "Found";
        };
        String message = "REDIRECT DETECTED: The URL redirects to a different host.\n\n"
                + "Original URL: " + ri.originalUrl() + "\n"
                + "Redirect URL: " + ri.redirectUrl() + "\n"
                + "Status: " + ri.statusCode() + " " + statusText + "\n\n"
                + "To complete your request, I need to fetch content from the redirected URL. "
                + "Please use WebFetch again with these parameters:\n"
                + "- url: \"" + ri.redirectUrl() + "\"\n"
                + "- prompt: \"" + (prompt == null ? "" : prompt) + "\"";

        long bytes = message.getBytes(StandardCharsets.UTF_8).length;
        ObjectNode out = buildOutput(bytes, ri.statusCode(), statusText, message,
                System.currentTimeMillis() - start, url);
        log.info("[WebFetchTool] 重定向未跟随(SSRF 管控): originalUrl={} redirectUrl={} status={}",
                ri.originalUrl(), ri.redirectUrl(), ri.statusCode());
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * 内容抓取成功 → result 计算 · 对齐 CC WebFetchTool.ts:251-298。
     *
     * <p>CC result 分支（:261-278）：预批准 host + {@code text/markdown} + 内容 &lt; MAX_MARKDOWN_LENGTH
     * → result = 原文；否则 {@code applyPromptToMarkdown}（Haiku 二次模型）。[G20③] Java 实现
     * {@link #applyPromptToMarkdown}——fast 档 prompter 注入后走真实摘要，fast 档不可用
     * 回退截断（抓取不中断）。二进制落盘备注（CC WebFetchTool.ts:280-285）：persistedPath 非 null
     * 时追加 {@code [Binary content (contentType, size) also saved to path]}。
     */
    private AgentToolResult handleFetched(ToolUseBlock call, String url, String prompt,
                                          WebFetchSecurity.FetchedContent fc, long start,
                                          boolean isNonInteractiveSession) {
        boolean isPreapproved = WebFetchPreapprovedHosts.isPreapprovedUrl(url);
        String result;
        if (isPreapproved && fc.contentType().contains("text/markdown")
                && fc.content().length() < WebFetchSecurity.MAX_MARKDOWN_LENGTH) {
            result = fc.content();
        } else {
            result = applyPromptToMarkdown(prompt, fc.content(), isPreapproved, isNonInteractiveSession);
        }

        // [G20③] 二进制内容落盘备注 · 对齐 CC WebFetchTool.ts:280-285（persistedPath 已由
        // WebFetchSecurity getURLMarkdownContent 落盘）。
        if (fc.persistedPath() != null) {
            long persistedSize = fc.persistedSize() != null ? fc.persistedSize() : fc.bytes();
            result += "\n\n[Binary content (" + fc.contentType() + ", "
                    + ToolResultStorage.formatFileSize(persistedSize)
                    + ") also saved to " + fc.persistedPath() + "]";
        }

        ObjectNode out = buildOutput(fc.bytes(), fc.code(), fc.codeText(), result,
                System.currentTimeMillis() - start, url);
        log.info("[WebFetchTool] 抓取成功: url={} code={} bytes={} preapproved={} contentType={} persisted={}",
                url, fc.code(), fc.bytes(), isPreapproved, fc.contentType(), fc.persistedPath());
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * applyPromptToMarkdown · 对齐 CC WebFetchTool/utils.ts:484-530。
     *
     * <p>流程（CC :491-529）：内容截断至 MAX_MARKDOWN_LENGTH → {@code makeSecondaryModelPrompt}
     * （prompt.ts:23-46）组装二次模型提示 → 调用二次模型（prompter）→ 返回模型文本。
     *
     * <p><b>[websearch-ccalign T6] fast 档 prompter 注入</b>：prompter 优先取外部注入
     * {@link #secondaryModelPrompter}（测试/特殊接线）；未注入 → {@link #buildFastModelPrompter}
     * 以 fast 档模型构建（对齐 CC {@code queryHaiku} → {@code getSmallFastModel} = Haiku，
     * utils.ts:503-514 + model.ts:36-37）。均不可用 → 回退截断（主 LLM 应用 prompt，抓取不中断）；
     * 调用失败 / 返回空 → 同样回退截断（warn 日志标注降级）。
     *
     * @param prompt            用户提供的处理 prompt（WebFetchTool.ts:27）
     * @param content           markdown 内容（可能超长）
     * @param isPreapprovedDomain 是否预批准域（决定 guidelines 分支，prompt.ts:28-34）
     * @param isNonInteractiveSession 是否非交互会话（透传二次模型调用 options，对齐 CC isNonInteractiveSession）
     * @return 模型摘要文本或截断内容
     */
    private String applyPromptToMarkdown(String prompt, String content, boolean isPreapprovedDomain,
                                         boolean isNonInteractiveSession) {
        String truncated = content == null ? "" : truncateToMaxMarkdown(content);
        java.util.function.Function<String, String> prompter = secondaryModelPrompter;
        if (prompter == null) {
            prompter = buildFastModelPrompter(isNonInteractiveSession);
        }
        if (prompter == null) {
            if (log.isDebugEnabled()) {
                log.debug("[WebFetchTool] 二次模型 prompter 不可用（外部未注入且 fast 档未接线），回退截断（主 LLM 应用 prompt）");
            }
            return truncated;
        }
        String modelPrompt = makeSecondaryModelPrompt(truncated, prompt, isPreapprovedDomain);
        try {
            String response = prompter.apply(modelPrompt);
            if (response != null && !response.isBlank()) {
                return response;
            }
            if (log.isWarnEnabled()) {
                log.warn("[WebFetchTool] 二次模型返回空摘要（回退截断）");
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[WebFetchTool] 二次模型摘要失败（回退截断）: err={}", e.toString());
            }
        }
        return truncated;
    }

    /**
     * [websearch-ccalign T6] 构建 fast 档二次摘要 prompter。
     *
     * <p>对齐 CC {@code queryHaiku}（WebFetchTool/utils.ts:503-514）：CC 真源 queryHaiku 用
     * {@code getSmallFastModel()}（model.ts:36-37）——即 <b>fast 档</b>（Haiku），非 weak 档。
     * Java 端经 {@code modelConfigResolver.resolveFastModelName("claude-haiku-4-5-20251001")}
     * （fast→weak→固定默认 三级，ModelConfigResolver:197-223）解析，复用 {@code chatWithOptions}
     * （对齐 HaikuToolUseSummaryGenerator）。依赖未注入 / fast 档解析失败 / 配置解析失败 → null
     * （调用方回退截断，抓取不中断）。
     *
     * @param isNonInteractiveSession 是否非交互会话（透传二次模型调用 options，对齐 CC isNonInteractiveSession）
     * @return fast 模型 prompter；不可用 → null
     */
    private java.util.function.Function<String, String> buildFastModelPrompter(boolean isNonInteractiveSession) {
        if (llmProviderFactory == null || modelConfigResolver == null) {
            if (log.isDebugEnabled()) {
                log.debug("[WebFetchTool] 二次模型依赖未注入（llmProviderFactory/modelConfigResolver 为空），回退截断");
            }
            return null;
        }
        try {
            String modelName = modelConfigResolver.resolveFastModelName("claude-haiku-4-5-20251001");
            if (modelName == null || modelName.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[WebFetchTool] fast 档模型名解析失败，二次模型回退截断");
                }
                return null;
            }
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
                if (log.isDebugEnabled()) {
                    log.debug("[WebFetchTool] 二次模型配置解析失败 modelName={}，回退截断", modelName);
                }
                return null;
            }
            LlmProvider provider = llmProviderFactory.getProvider(resolved.config(), resolved.providerType());
            LlmProvider.ChatRequestOptions options = buildSecondaryModelOptions(isNonInteractiveSession);
            return modelPrompt -> provider.chatWithOptions(resolved.config(), modelName,
                    null /* 空 systemPrompt = CC asSystemPrompt([])，toSingleOrgBlock(null) → 无 system 字段 */,
                    modelPrompt, options);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[WebFetchTool] 构建 fast 档 prompter 失败（二次模型回退截断）: err={}", e.toString());
            }
            return null;
        }
    }

    /** 弱模型摘要 chat options · 对齐 CC queryHaiku options（WebFetchTool/utils.ts:507-513）。 */
    private static LlmProvider.ChatRequestOptions buildSecondaryModelOptions(boolean isNonInteractiveSession) {
        return new LlmProvider.ChatRequestOptions(
                List.of(),    // history — 单条 modelPrompt（对齐 CC queryHaiku messages=[userMessage]）
                null,         // tools — []
                null,         // outputFormat — 未设
                LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(), // thinking disabled（queryHaiku.ts:3262）
                null,         // temperature — 未设
                QUERY_SOURCE, // querySource='web_fetch_apply'（CC utils.ts:508）
                null,         // abortController — WebFetch 无 signal 通道
                null,         // maxTokens — 未设（provider 回落模型缺省）
                null,         // skipCacheWrite — CC 未设（= false，写 cache）
                Boolean.TRUE, // enablePromptCaching — 对齐 queryHaiku :75 true
                List.of(),    // agents — []
                Boolean.FALSE, // hasAppendSystemPrompt — false
                List.of(),    // mcpTools — []
                isNonInteractiveSession); // isNonInteractiveSession 透传
    }

    /** CC makeSecondaryModelPrompt · WebFetchTool/prompt.ts:23-46（guidelines 按预批准域分支）。 */
    private static String makeSecondaryModelPrompt(
            String markdownContent, String prompt, boolean isPreapprovedDomain) {
        String guidelines = isPreapprovedDomain
                ? "Provide a concise response based on the content above. Include relevant details, "
                    + "code examples, and documentation excerpts as needed."
                : "Provide a concise response based only on the content above. In your response:\n"
                    + " - Enforce a strict 125-character maximum for quotes from any source document. "
                    + "Open Source Software is ok as long as we respect the license.\n"
                    + " - Use quotation marks for exact language from articles; any language outside of "
                    + "the quotation should never be word-for-word the same.\n"
                    + " - You are not a lawyer and never comment on the legality of your own prompts and responses.\n"
                    + " - Never produce or reproduce exact song lyrics.";
        return "\nWeb page content:\n---\n" + markdownContent + "\n---\n\n"
                + prompt + "\n\n" + guidelines + "\n";
    }

    /** 输出契约 · 对齐 CC WebFetchTool.ts:287-294 Output。 */
    private ObjectNode buildOutput(long bytes, int code, String codeText, String result,
                                   long durationMs, String url) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("bytes", bytes);
        node.put("code", code);
        node.put("codeText", codeText);
        node.put("result", result);
        node.put("durationMs", durationMs);
        node.put("url", url);
        return node;
    }

    /**
     * 清空 WebFetch URL/域名双缓存 · 对齐 CC {@code clearWebFetchCache}
     * （WebFetchTool/utils.ts:80-83 + commands/clear/caches.ts:130）。
     *
     * <p>/clear 会话重置时调用（CommandController /clear 分支接线），避免旧页面缓存
     * 跨会话残留（CC caches.ts:130 注释 "up to 50MB of cached page content"）。
     */
    public void clearWebFetchCache() {
        resolveSecurity().clearWebFetchCache();
        if (log.isDebugEnabled()) {
            log.debug("[WebFetchTool] clearWebFetchCache: url+domain 双缓存已清空（对齐 CC WebFetchTool/utils.ts:80-83）");
        }
    }

    /** 截断至 MAX_MARKDOWN_LENGTH（100k 字符），防超长内容打爆 tool_result 预算。 */
    private static String truncateToMaxMarkdown(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= WebFetchSecurity.MAX_MARKDOWN_LENGTH) {
            return content;
        }
        return content.substring(0, WebFetchSecurity.MAX_MARKDOWN_LENGTH)
                + "\n\n[Content truncated due to length...]";
    }

    private static String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }
}

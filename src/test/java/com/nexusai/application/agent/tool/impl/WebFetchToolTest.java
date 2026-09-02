package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.settings.dto.SettingsDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMP-H1 · WebFetch SSRF 安全链移植测试（P0-3）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>: 本测试锁定的是 CC SSRF 安全链的<b>安全不变量</b>：
 * <ol>
 *   <li><b>SSRF 阻断</b>：内网单段 host（localhost）/ user:pass / &gt;2000 字符被
 *       {@code validateURL} 拒绝；云元数据类域（169.254.169.254）被域预检
 *       {@code checkDomainBlocklist} 阻断（EV-H1-012/020..028）——不是"能抓内容"，而是"内网抓不到"。</li>
 *   <li><b>重定向管控</b>：跨 host / 跨协议 / 跨端口 / 带凭证重定向被 {@code isPermittedRedirect}
 *       拒绝（EV-H1-015/025，SSRF 重定向跳板）；同 host（www 变体）才放行。</li>
 *   <li><b>UA / 超时 / 输出契约</b>：UA=Claude-User（EV-H1-026）、FETCH_TIMEOUT_MS=60s（EV-H1-026）、
 *       输出 {@code {bytes,code,codeText,result,durationMs,url}}（EV-H1-002）。</li>
 *   <li><b>max_bytes 删除</b>（D-TR-H1-01）：inputSchema 不再声明 max_bytes。</li>
 * </ol>
 *
 * <p><b>HTTP mock</b>: JDK {@link HttpServer}/{@link HttpsServer} 临时端口（零新依赖）。域预检
 * 端点用本地 mock 返回 {@code can_fetch}（SSRF 测试不触网）；成功抓取 / 重定向 / UA 捕获用
 * 本地 HTTP/HTTPS 服务（自签名证书 + trust-all client，复用 /hooks/https-test.p12 惯例）。
 */
class WebFetchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer httpServer;
    private HttpsServer httpsServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // validateURL · SSRF 前置校验（纯函数，不触网）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateURL: 单段 host（localhost）拒绝 → SSRF 内网前置拦截")
    void validateURL_rejectsSingleLabelHost() {
        // WHY: CC validateURL hostname 段数 < 2 → false（utils.ts:162-166）。localhost 单段
        //      是内网 SSRF 最直接入口，若放行则工具可被诱导抓本机服务。
        assertThat(WebFetchSecurity.validateURL("http://localhost:8080/admin")).isFalse();
        assertThat(WebFetchSecurity.validateURL("http://metadata/")).isFalse();
    }

    @Test
    @DisplayName("validateURL: user:pass 拒绝 → 凭证注入防钓鱼")
    void validateURL_rejectsCredentials() {
        // WHY: CC utils.ts:156-158 拒绝 username/password（内网域名 + 凭证的组合是典型攻击载荷）。
        assertThat(WebFetchSecurity.validateURL("http://user:pass@example.com/path")).isFalse();
    }

    @Test
    @DisplayName("validateURL: 超长 URL（>2000）拒绝 → 资源消耗控制")
    void validateURL_rejectsTooLong() {
        // WHY: CC MAX_URL_LENGTH=2000（utils.ts:106, 140-142），防单请求资源耗尽。
        String longUrl = "http://example.com/" + "a".repeat(2100);
        assertThat(WebFetchSecurity.validateURL(longUrl)).isFalse();
    }

    @Test
    @DisplayName("validateURL: 合法公网域名通过")
    void validateURL_acceptsPublicHost() {
        assertThat(WebFetchSecurity.validateURL("https://example.com/path?q=1")).isTrue();
        assertThat(WebFetchSecurity.validateURL("http://docs.python.org/3/")).isTrue();
        assertThat(WebFetchSecurity.validateURL("https://169.254.169.254/latest/meta-data/")).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // isPermittedRedirect · 重定向管控（纯函数，不触网）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPermittedRedirect: 跨 host 拒绝 → 防重定向 SSRF 跳板")
    void isPermittedRedirect_rejectsCrossHost() {
        // WHY: CC utils.ts:236-239 stripWww 后 hostname 相等才放行。example.com → 169.254.169.254
        //      是经典重定向跳板（信任域名 302 指向内网），必须拒绝（EV-H1-015/025）。
        assertThat(WebFetchSecurity.isPermittedRedirect(
                "https://example.com/a", "https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(WebFetchSecurity.isPermittedRedirect(
                "https://example.com/a", "https://evil.example.org/b")).isFalse();
    }

    @Test
    @DisplayName("isPermittedRedirect: 同 host（含 www 变体 / 换路径）放行")
    void isPermittedRedirect_allowsSameHost() {
        // WHY: CC 允许加/去 www + 同 host 换 path/query（utils.ts:207-211 注释）。
        assertThat(WebFetchSecurity.isPermittedRedirect("https://example.com/a", "https://example.com/b")).isTrue();
        assertThat(WebFetchSecurity.isPermittedRedirect("https://example.com/a", "https://www.example.com/b")).isTrue();
        assertThat(WebFetchSecurity.isPermittedRedirect("https://www.example.com/a", "https://example.com/b")).isTrue();
    }

    @Test
    @DisplayName("isPermittedRedirect: 协议/端口变化拒绝")
    void isPermittedRedirect_rejectsProtocolAndPort() {
        // WHY: CC utils.ts:220-224 protocol/port 必须相同（https→http 降级是中间人向量）。
        assertThat(WebFetchSecurity.isPermittedRedirect("https://example.com/a", "http://example.com/b")).isFalse();
        assertThat(WebFetchSecurity.isPermittedRedirect("https://example.com:443/a", "https://example.com:8443/b")).isFalse();
    }

    @Test
    @DisplayName("isPermittedRedirect: 重定向目标带凭证拒绝")
    void isPermittedRedirect_rejectsCredentialsInRedirect() {
        // WHY: CC utils.ts:228-230 redirect 带 username/password 拒绝。
        assertThat(WebFetchSecurity.isPermittedRedirect("https://example.com/a", "https://user:pass@example.com/b")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // preapproved 域名表
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPreapprovedHost: 公网文档域名预批准")
    void isPreapprovedHost_publicDocs() {
        assertThat(WebFetchPreapprovedHosts.isPreapprovedHost("docs.python.org", "/3/")).isTrue();
        assertThat(WebFetchPreapprovedHosts.isPreapprovedHost("react.dev", "/")).isTrue();
    }

    @Test
    @DisplayName("isPreapprovedHost: 路径段边界（/anthropics 不匹配 /anthropics-evil）")
    void isPreapprovedHost_pathSegmentBoundary() {
        // WHY: CC preapproved.ts:159-163 强制段边界——"/anthropics" 不得匹配 "/anthropics-evil/malware"，
        //      防路径前缀伪造。github.com/anthropics 预批准但 github.com/anthropics-evil 不得命中。
        assertThat(WebFetchPreapprovedHosts.isPreapprovedHost("github.com", "/anthropics")).isTrue();
        assertThat(WebFetchPreapprovedHosts.isPreapprovedHost("github.com", "/anthropics/repo")).isTrue();
        assertThat(WebFetchPreapprovedHosts.isPreapprovedHost("github.com", "/anthropics-evil/malware")).isFalse();
    }

    @Test
    @DisplayName("isPreapprovedUrl: 非预批准域名（含内网）false")
    void isPreapprovedUrl_nonApproved() {
        assertThat(WebFetchPreapprovedHosts.isPreapprovedUrl("https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(WebFetchPreapprovedHosts.isPreapprovedUrl("https://localhost/")).isFalse();
        assertThat(WebFetchPreapprovedHosts.isPreapprovedUrl("https://example.com/")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // checkDomainBlocklist · 域预检（本地 mock，不触网）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("checkDomainBlocklist: 云元数据 IP 被预检阻断（SSRF）")
    void checkDomainBlocklist_blocksCloudMetadata() throws Exception {
        // WHY: 169.254.169.254 通过 validateURL（4 段），SSRF 拦截落在域预检层——mock 端点返回
        //      can_fetch=false 等价 api.anthropic.com 对该内部域判定阻断（EV-H1-020..028）。
        startDomainCheckServer("/domain_info", exchange -> {
            String query = exchange.getRequestURI().getQuery() == null
                    ? "" : exchange.getRequestURI().getQuery();
            String body = query.contains("169.254.169.254")
                    ? "{\"can_fetch\":false}" : "{\"can_fetch\":true}";
            respond(exchange, 200, "application/json", body);
        });
        String base = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/domain_info";
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), base, false);

        assertThat(security.checkDomainBlocklist("169.254.169.254"))
                .isEqualTo(WebFetchSecurity.DomainCheckResult.BLOCKED);
        assertThat(security.checkDomainBlocklist("example.com"))
                .isEqualTo(WebFetchSecurity.DomainCheckResult.ALLOWED);
    }

    @Test
    @DisplayName("checkDomainBlocklist: skipDomainCheck=true → 跳过预检（enterprise 语义）")
    void checkDomainBlocklist_skip() {
        // WHY: 对齐 CC settings.skipWebFetchPreflight（utils.ts:383-398）——enterprise 无法访问
        //      claude.ai 时跳过，默认 false（执行预检）。
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), "http://127.0.0.1:1/domain_info", true);
        assertThat(security.checkDomainBlocklist("169.254.169.254"))
                .isEqualTo(WebFetchSecurity.DomainCheckResult.ALLOWED);
    }

    // ════════════════════════════════════════════════════════════════════════
    // getWithPermittedRedirects · 重定向管控 E2E（本地 HTTP，直连方法无 https 升级）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getWithPermittedRedirects: 跨 host 302 不跟随 → RedirectInfo（重定向跳板拦截）")
    void getWithPermittedRedirects_crossHostRedirect_notFollowed() throws Exception {
        // WHY: SSRF 重定向跳板 = 信任域 302 → 内网。CC maxRedirects:0 + isPermittedRedirect 手动，
        //      跨 host 直接返回 RedirectInfo 交上层（工具给模型 REDIRECT DETECTED），不静默跟随。
        startHttpServer("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.example.org/pwn");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/start";
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), "http://127.0.0.1:1/domain_info", true);

        Object result = security.getWithPermittedRedirects(url, WebFetchSecurity::isPermittedRedirect, 0);

        assertThat(result).isInstanceOf(WebFetchSecurity.RedirectInfo.class);
        WebFetchSecurity.RedirectInfo ri = (WebFetchSecurity.RedirectInfo) result;
        assertThat(ri.statusCode()).isEqualTo(302);
        assertThat(ri.originalUrl()).isEqualTo(url);
        assertThat(ri.redirectUrl()).isEqualTo("http://evil.example.org/pwn");
    }

    @Test
    @DisplayName("getWithPermittedRedirects: 同 host 302 跟随 → 最终 200 内容")
    void getWithPermittedRedirects_sameHostRedirect_followed() throws Exception {
        startHttpServer("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        startHttpServer("/final", exchange -> respond(exchange, 200, "text/markdown", "# hello"));
        String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/start";
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), "http://127.0.0.1:1/domain_info", true);

        Object result = security.getWithPermittedRedirects(url, WebFetchSecurity::isPermittedRedirect, 0);

        assertThat(result).isInstanceOf(java.net.http.HttpResponse.class);
        java.net.http.HttpResponse<byte[]> resp = (java.net.http.HttpResponse<byte[]>) result;
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(new String(resp.body(), StandardCharsets.UTF_8)).isEqualTo("# hello");
    }

    @Test
    @DisplayName("getWithPermittedRedirects: UA 头 = Claude-User（EV-H1-026）")
    void getWithPermittedRedirects_uaIsClaudeUser() throws Exception {
        AtomicReference<String> capturedUa = new AtomicReference<>();
        startHttpServer("/", exchange -> {
            capturedUa.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "text/markdown", "ok");
        });
        String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), "http://127.0.0.1:1/domain_info", true);

        security.getWithPermittedRedirects(url, WebFetchSecurity::isPermittedRedirect, 0);

        assertThat(capturedUa.get()).as("UA 须以 Claude-User 开头（CC getWebFetchUserAgent，EV-H1-026）")
                .startsWith("Claude-User");
        assertThat(WebFetchSecurity.USER_AGENT).contains("support.anthropic.com");
    }

    @Test
    @DisplayName("getWithPermittedRedirects: 403 + x-proxy-error → EgressBlockedException")
    void getWithPermittedRedirects_egressBlocked() throws Exception {
        startHttpServer("/", exchange -> {
            exchange.getResponseHeaders().add("x-proxy-error", "blocked-by-allowlist");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), "http://127.0.0.1:1/domain_info", true);

        assertThatThrownBy(() ->
                security.getWithPermittedRedirects(url, WebFetchSecurity::isPermittedRedirect, 0))
                .isInstanceOf(WebFetchSecurity.EgressBlockedException.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // WebFetchTool.execute 集成 · SSRF / 输出契约 / REDIRECT DETECTED
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("execute: inputSchema 已删 max_bytes（D-TR-H1-01），required=[url,prompt]")
    void inputSchema_noMaxBytes() {
        WebFetchTool tool = new WebFetchTool();
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("properties").has("max_bytes"))
                .as("max_bytes 已删（D-TR-H1-01，CC 用 MAX_MARKDOWN_LENGTH/MAX_HTTP_CONTENT_LENGTH 表达截断）")
                .isFalse();
        assertThat(schema.path("properties").has("url")).isTrue();
        assertThat(schema.path("properties").has("prompt")).isTrue();
        assertThat(schema.path("required").isArray()).isTrue();
        java.util.List<String> required = new java.util.ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).contains("url", "prompt");
    }

    @Test
    @DisplayName("execute: 云元数据 IP 被预检阻断 → error（SSRF E2E）")
    void execute_cloudMetadataBlocked() throws Exception {
        // WHY: WebFetchTool 全链 SSRF——169.254.169.254 过 validateURL，落在域预检（mock can_fetch=false）
        //      → DomainBlockedException → 工具返回 error（EV-H1-020..028）。
        startDomainCheckServer("/domain_info", exchange -> {
            String body = exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("169.254.169.254")
                    ? "{\"can_fetch\":false}" : "{\"can_fetch\":true}";
            respond(exchange, 200, "application/json", body);
        });
        String base = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/domain_info";
        WebFetchSecurity security = new WebFetchSecurity(plainClient(), base, false);
        WebFetchTool tool = new WebFetchTool(security);

        AgentToolResult result = tool.execute(call("https://169.254.169.254/latest/meta-data/"));

        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        // 域预检阻断消息为 CC 真源 "Claude Code is unable to fetch from <domain>"（utils.ts:23 /
        // WebFetchSecurity.DomainBlockedException:649），以 "Claude Code is " 开头。[isToolErrorData 补丁]
        // 此前 isToolErrorData 仅登记 "unable to fetch"（只匹配以 unable 开头的串），阻断消息前缀不匹配
        // → 下游消费者（InboundMcpToolProvider/ProductionForkedQuery/MagicDocsService 等）把阻断误判为
        // 成功 tool_result（silent success）。补前缀后必须返回 true；contains 校验保留验证消息内容本身。
        assertThat(tr.data().toString()).as("内网云元数据必须被阻断").contains("unable to fetch");
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
                .as("WebFetch 阻断消息（Claude Code is unable to fetch）必须被 isToolErrorData 识别为错误")
                .isTrue();
    }

    @Test
    @DisplayName("execute: 重定向跨 host → REDIRECT DETECTED 输出契约（bytes/code/codeText/result/durationMs/url）")
    void execute_redirectDetected_outputContract() throws Exception {
        startHttpsServer("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://evil.example.org/pwn");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        String url = "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(trustAllClient(), "http://127.0.0.1:1/domain_info", true);
        WebFetchTool tool = new WebFetchTool(security);

        AgentToolResult result = tool.execute(call(url));

        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        JsonNode out = JSON.readTree(tr.data().toString());
        assertThat(out.has("bytes")).isTrue();
        assertThat(out.get("code").asInt()).isEqualTo(302);
        assertThat(out.get("codeText").asText()).isEqualTo("Found");
        assertThat(out.get("result").asText()).contains("REDIRECT DETECTED");
        assertThat(out.get("result").asText()).contains("evil.example.org");
        assertThat(out.has("durationMs")).isTrue();
        assertThat(out.get("url").asText()).isEqualTo(url);
    }

    @Test
    @DisplayName("execute: 成功抓取 → 输出契约 {bytes,code,codeText,result,durationMs,url}")
    void execute_success_outputContract() throws Exception {
        startHttpsServer("/", exchange -> respond(exchange, 200, "text/markdown", "# hello webfetch"));
        String url = "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(trustAllClient(), "http://127.0.0.1:1/domain_info", true);
        WebFetchTool tool = new WebFetchTool(security);

        AgentToolResult result = tool.execute(call(url));

        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        JsonNode out = JSON.readTree(tr.data().toString());
        assertThat(out.has("bytes")).isTrue();
        assertThat(out.get("bytes").asLong()).isGreaterThan(0);
        assertThat(out.get("code").asInt()).isEqualTo(200);
        assertThat(out.get("codeText").asText()).isEqualTo("OK");
        assertThat(out.get("result").asText()).contains("hello webfetch");
        assertThat(out.has("durationMs")).isTrue();
        assertThat(out.get("url").asText()).isEqualTo(url);
    }

    @Test
    @DisplayName("execute: 单段 host（localhost）→ Invalid URL error")
    void execute_localhostInvalidUrl() {
        // WHY: validateURL 前置拒绝（SSRF），工具返回 Invalid URL（errorCode 语义由 validateInput 承担）。
        WebFetchTool tool = new WebFetchTool();
        AgentToolResult result = tool.execute(call("http://localhost:8080/admin"));
        assertThat(result).isInstanceOf(ToolResult.class);
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult<?>) result).data())).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 二次模型 prompter 注入（G20③ + websearch-ccalign T6）· fast 档二次模型摘要 + 降级 fallback
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("execute(二次模型摘要成功)：resolveFastModelName fast 档 → chatWithOptions 固定摘要进 result（T6）")
    void execute_secondaryModelSummary_returnsFastSummary() throws Exception {
        // WHY: websearch-ccalign T6 目标——applyPromptToMarkdown 走真实二次模型摘要（对齐 CC queryHaiku），
        //      CC 真源 queryHaiku 用 getSmallFastModel()（model.ts:36-37）= Haiku（fast 档），
        //      非 weak 档。mock LlmProviderFactory 返回固定摘要必须原样进入 result；模型必须用 fast 档。
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), nullable(String.class), anyString(), any()))
                .thenReturn("Fixed fast model summary about fetched page");
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        // fast 档：resolveFastModelName（fast→weak→固定默认 三级）→ DB models.name
        when(resolver.resolveFastModelName("claude-haiku-4-5-20251001")).thenReturn("claude-fast-4-5");
        when(resolver.resolve("claude-fast-4-5")).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        startHttpsServer("/", exchange -> respond(exchange, 200, "text/markdown", "# hello webfetch"));
        String url = "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(trustAllClient(), "http://127.0.0.1:1/domain_info", true);
        WebFetchTool tool = new WebFetchTool(security, factory, resolver);

        AgentToolResult result = tool.execute(call(url));

        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        JsonNode out = JSON.readTree(tr.data().toString());
        assertThat(out.get("result").asText())
                .as("fast 档固定摘要必须原样进入 result（T6）")
                .isEqualTo("Fixed fast model summary about fetched page");

        // 二次模型调用参数：模型=fast 档（resolveFastModelName）、querySource='web_fetch_apply'
        // （CC utils.ts:508）、thinking disabled、userPrompt 含 makeSecondaryModelPrompt 组装文本
        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LlmProvider.ChatRequestOptions> optionsCaptor =
                ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(provider).chatWithOptions(any(), modelCaptor.capture(), nullable(String.class),
                userPromptCaptor.capture(), optionsCaptor.capture());
        assertThat(modelCaptor.getValue())
                .as("二次模型必须用 fast 档（CC getSmallFastModel = Haiku，model.ts:36-37）而非 weak 档")
                .isEqualTo("claude-fast-4-5");
        assertThat(optionsCaptor.getValue().querySource())
                .as("querySource 必须 = 'web_fetch_apply'（CC WebFetchTool/utils.ts:508）")
                .isEqualTo("web_fetch_apply");
        assertThat(optionsCaptor.getValue().thinkingConfig().type()).isEqualTo("disabled");
        assertThat(userPromptCaptor.getValue())
                .contains("Web page content:", "hello webfetch", "Summarize the content.");
    }

    @Test
    @DisplayName("execute(降级：fast 档解析失败)：resolveFastModelName 返回 null → 回退截断，抓取不中断（T6）")
    void execute_secondaryModelSummary_degradesOnFastNotResolvable() throws Exception {
        // WHY: T6 降级纪律——fast 档模型名解析失败时不得中断抓取；回退截断（主 LLM 应用 prompt），
        //      绝不触发 resolve/chatWithOptions（零二次模型调用）。
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolveFastModelName("claude-haiku-4-5-20251001")).thenReturn(null); // fast 档不可用

        startHttpsServer("/", exchange -> respond(exchange, 200, "text/markdown", "# hello webfetch"));
        String url = "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(trustAllClient(), "http://127.0.0.1:1/domain_info", true);
        WebFetchTool tool = new WebFetchTool(security, factory, resolver);

        AgentToolResult result = tool.execute(call(url));

        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).as("fast 档不可用必须抓取不中断").isFalse();
        JsonNode out = JSON.readTree(tr.data().toString());
        assertThat(out.get("result").asText())
                .as("回退截断内容（主 LLM 应用 prompt）")
                .contains("hello webfetch");
        // fast 档不可用 → 绝不触发 resolve/chatWithOptions（零二次模型调用）
        verify(resolver, never()).resolve(anyString());
        verify(factory, never()).getProvider(any(), any());
    }

    @Test
    @DisplayName("execute(降级：二次模型调用失败)：chatWithOptions 抛异常 → 回退截断，抓取不中断（T6）")
    void execute_secondaryModelSummary_degradesOnCallFailure() throws Exception {
        // WHY: T6 降级纪律——二次模型调用失败时抓取不中断，回退截断（warn 日志标注降级）。
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        when(provider.chatWithOptions(any(), anyString(), nullable(String.class), anyString(), any()))
                .thenThrow(new RuntimeException("fast model down"));
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), nullable(String.class))).thenReturn(provider);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        when(resolver.resolveFastModelName("claude-haiku-4-5-20251001")).thenReturn("claude-fast-4-5");
        when(resolver.resolve("claude-fast-4-5")).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        startHttpsServer("/", exchange -> respond(exchange, 200, "text/markdown", "# hello webfetch"));
        String url = "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/";
        WebFetchSecurity security = new WebFetchSecurity(trustAllClient(), "http://127.0.0.1:1/domain_info", true);
        WebFetchTool tool = new WebFetchTool(security, factory, resolver);

        AgentToolResult result = tool.execute(call(url));

        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).as("二次模型调用失败必须抓取不中断").isFalse();
        JsonNode out = JSON.readTree(tr.data().toString());
        assertThat(out.get("result").asText())
                .as("调用失败回退截断内容（主 LLM 应用 prompt）")
                .contains("hello webfetch");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [websearch-resid R-A] resolveSecurity · settings.proxy → ProxySelector / 直连 / 缓存复用
    // ════════════════════════════════════════════════════════════════════════

    /** 25 组件的 SettingsDto（仅 proxy 有值，其余全 null）· [R-A] resolveSecurity 测试用。 */
    private static SettingsDto settingsDtoWithProxy(String proxy) {
        return settingsDto(proxy, null);
    }

    /** 27 组件的 SettingsDto（仅 proxy + domainCheckUrl 有值，其余全 null）· [websearch-domaincheck] resolveSecurity 测试用。 */
    private static SettingsDto settingsDto(String proxy, String domainCheckUrl) {
        return new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, proxy, null, null,
                domainCheckUrl,
                null,
                null,
                null,
                // [V52] 压缩配置 12 列未设 → null · [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // permissionMode（V44 全局默认，本 fixture 未设 → null）· [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null
    }

    @Test
    @DisplayName("[R-A] resolveSecurity：settings.proxy 配置 → HttpClient ProxySelector isPresent（proxy 落到抓取链）")
    void resolveSecurity_proxyConfigured_buildsProxyClient() throws Exception {
        // WHY：用户「proxy 肯定接线」——settings.proxy 必须到达 HttpClient ProxySelector，否则
        // duckduckgo/WebFetch 抓取走直连（proxy 配置静默失效）。SSRF 链（validateURL/域预检/重定向）
        // 由 WebFetchSecurity 内部不变，仅 HttpClient 实例带代理。
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        when(settingsService.get()).thenReturn(settingsDtoWithProxy("proxy.example.com:8080"));

        WebFetchTool tool = new WebFetchTool();
        tool.setSettingsService(settingsService);

        WebFetchSecurity security =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");
        assertThat(security).as("resolveSecurity() 必须返回非 null security").isNotNull();
        HttpClient client = (HttpClient) ReflectionTestUtils.getField(security, "httpClient");
        assertThat(client.proxy()).as("settings.proxy 配置必须生效（ProxySelector 已设）").isPresent();
    }

    @Test
    @DisplayName("[R-A] resolveSecurity：settingsService 未注入 / proxy blank → HttpClient 无 ProxySelector（直连）")
    void resolveSecurity_noProxy_direct() throws Exception {
        // WHY：未注入（测试/孤立运行）或 proxy 空 → 默认无代理直连（零行为变化，验收 #3 直连侧）。
        // 显式构造路径（securityExplicit=true）不受影响（既有用例绿 = 无回归锚）。
        // settingsService 未注入
        WebFetchTool isolated = new WebFetchTool();
        WebFetchSecurity isolatedSecurity =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(isolated, "resolveSecurity");
        assertThat(((HttpClient) ReflectionTestUtils.getField(isolatedSecurity, "httpClient")).proxy())
                .as("settingsService 未注入 → 默认无代理（ProxySelector 不设）").isEmpty();

        // proxy blank → 直连
        SettingsService blankService = Mockito.mock(SettingsService.class);
        when(blankService.get()).thenReturn(settingsDtoWithProxy("   "));
        WebFetchTool blank = new WebFetchTool();
        blank.setSettingsService(blankService);
        WebFetchSecurity blankSecurity =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(blank, "resolveSecurity");
        assertThat(((HttpClient) ReflectionTestUtils.getField(blankSecurity, "httpClient")).proxy())
                .as("proxy blank → 直连（ProxySelector 不设）").isEmpty();
    }

    @Test
    @DisplayName("[R-A] resolveSecurity：proxy 变更 → security 重建；同 proxy 连续调用 → 缓存实例复用")
    void resolveSecurity_proxyChange_rebuildsAndCaches() throws Exception {
        // WHY（C1 线程安全 + 缓存语义）：同 proxy 连续调用必须复用同一 security 实例（Caffeine 双缓存
        // 跨调用保留，避免重复构建浪费）；proxy 变更（前端改 settings）必须重建，否则旧代理残留。
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        WebFetchTool tool = new WebFetchTool();
        tool.setSettingsService(settingsService);

        // proxy A → 构建一次 + 缓存复用
        when(settingsService.get()).thenReturn(settingsDtoWithProxy("proxyA.example.com:8080"));
        WebFetchSecurity first =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");
        WebFetchSecurity cached =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");
        assertThat(cached).as("同 proxy 连续调用必须复用同一 security 实例").isSameAs(first);
        assertThat(((HttpClient) ReflectionTestUtils.getField(first, "httpClient")).proxy()).isPresent();

        // proxy 变更 → 重建（新实例 + 新 ProxySelector）
        when(settingsService.get()).thenReturn(settingsDtoWithProxy("proxyB.example.com:8080"));
        WebFetchSecurity rebuilt =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");
        assertThat(rebuilt).as("proxy 变更必须重建 security（新 ProxySelector 生效）").isNotSameAs(first);
        assertThat(((HttpClient) ReflectionTestUtils.getField(rebuilt, "httpClient")).proxy()).isPresent();
    }

    @Test
    @DisplayName("[domaincheck] resolveSecurity：settings.websearchDomainCheckUrl 配置 → skipDomainCheck=false + domainCheckBaseUrl=url")
    void resolveSecurity_domainCheckUrlConfigured() throws Exception {
        // WHY（规则九）：用户「api.anthropic.com 不要预检 预检google」——配了端点必须真正预检该端点
        // （can_fetch JSON 语义），否则配置静默失效（新端点不生效）。
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        when(settingsService.get()).thenReturn(settingsDto(null, "https://example.com/domain_info"));

        WebFetchTool tool = new WebFetchTool();
        tool.setSettingsService(settingsService);

        WebFetchSecurity security =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");
        assertThat(security).as("resolveSecurity() 必须返回非 null security").isNotNull();
        assertThat((Boolean) ReflectionTestUtils.getField(security, "skipDomainCheck"))
                .as("配置了 websearchDomainCheckUrl → 必须真正预检（skipDomainCheck=false）").isFalse();
        assertThat((String) ReflectionTestUtils.getField(security, "domainCheckBaseUrl"))
                .as("domainCheckBaseUrl 必须 = settings.websearchDomainCheckUrl").isEqualTo("https://example.com/domain_info");
    }

    @Test
    @DisplayName("[domaincheck] resolveSecurity：websearchDomainCheckUrl 空/未配置 → skipDomainCheck=true（默认跳过，不依赖 api.anthropic.com）")
    void resolveSecurity_domainCheckUrlBlankOrAbsent() throws Exception {
        // WHY（规则九）：用户拍板默认不依赖 api.anthropic.com（中国网络不可达）——默认跳过必须成立，
        // 否则 WebFetch/duckduckgo 抓取又被 fail-closed 阻断。
        // 未配置（settingsService 未注入 → currentDomainCheckUrl() null）→ 默认构造跳过预检
        WebFetchTool isolated = new WebFetchTool();
        WebFetchSecurity isolatedSecurity =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(isolated, "resolveSecurity");
        assertThat((Boolean) ReflectionTestUtils.getField(isolatedSecurity, "skipDomainCheck"))
                .as("未注入/未配置 → 默认跳过预检（skipDomainCheck=true）").isTrue();

        // blank → 跳过预检
        SettingsService blankService = Mockito.mock(SettingsService.class);
        when(blankService.get()).thenReturn(settingsDto(null, "   "));
        WebFetchTool blank = new WebFetchTool();
        blank.setSettingsService(blankService);
        WebFetchSecurity blankSecurity =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(blank, "resolveSecurity");
        assertThat((Boolean) ReflectionTestUtils.getField(blankSecurity, "skipDomainCheck"))
                .as("websearchDomainCheckUrl blank → 跳过预检（skipDomainCheck=true）").isTrue();
        assertThat((String) ReflectionTestUtils.getField(blankSecurity, "domainCheckBaseUrl"))
                .as("blank 端点必须归一为 null（不预检）").isNull();
    }

    @Test
    @DisplayName("[domaincheck] resolveSecurity：仅 domainCheckUrl 变更（proxy 相同）→ security 重建（缓存 key 合并契约 A3）")
    void resolveSecurity_onlyDomainCheckUrlChange_rebuilds() throws Exception {
        // WHY（规则九）：A3 缓存 key 契约——漏合并 domainCheckUrl 进 key 则旧预检配置残留
        // （用户配了新端点却不生效 = 静默失效，本任务最易静默失效点）。
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        WebFetchTool tool = new WebFetchTool();
        tool.setSettingsService(settingsService);

        when(settingsService.get()).thenReturn(
                settingsDto("proxy.example.com:8080", "https://endpointA.example.com/domain_info"));
        WebFetchSecurity first =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");

        when(settingsService.get()).thenReturn(
                settingsDto("proxy.example.com:8080", "https://endpointB.example.com/domain_info"));
        WebFetchSecurity rebuilt =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");

        assertThat(rebuilt).as("仅 domainCheckUrl 变更（proxy 相同）必须重建 security").isNotSameAs(first);
        assertThat((String) ReflectionTestUtils.getField(rebuilt, "domainCheckBaseUrl"))
                .as("新 domainCheckUrl 必须生效").isEqualTo("https://endpointB.example.com/domain_info");
    }

    @Test
    @DisplayName("[domaincheck] resolveSecurity：proxy + domainCheckUrl 同时配置 → HttpClient 带 ProxySelector 且 skipDomainCheck=false")
    void resolveSecurity_proxyAndDomainCheckUrlCombined() throws Exception {
        // WHY：双配置叠加正确——proxy 落到抓取链，domainCheckUrl 落到预检端点（互不干扰，无折中）。
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        when(settingsService.get()).thenReturn(settingsDto("proxy.example.com:8080", "https://google.com/domain_info"));

        WebFetchTool tool = new WebFetchTool();
        tool.setSettingsService(settingsService);

        WebFetchSecurity security =
                (WebFetchSecurity) ReflectionTestUtils.invokeMethod(tool, "resolveSecurity");
        assertThat(((HttpClient) ReflectionTestUtils.getField(security, "httpClient")).proxy())
                .as("proxy 配置必须生效（ProxySelector 已设）").isPresent();
        assertThat((Boolean) ReflectionTestUtils.getField(security, "skipDomainCheck"))
                .as("domainCheckUrl 配置 → 必须预检（skipDomainCheck=false）").isFalse();
        assertThat((String) ReflectionTestUtils.getField(security, "domainCheckBaseUrl"))
                .as("domainCheckBaseUrl 必须 = 配置端点").isEqualTo("https://google.com/domain_info");
    }

    @Test
    @DisplayName("[R-A] withProxy 工厂：合法 proxy 带 ProxySelector；null/blank/非法格式 → 直连（fail-loud）")
    void withProxyFactory_validAndInvalidProxy() {
        // WHY：WebFetchSecurity.withProxy 是 resolveSecurity 的代理构建入口——合法 host:port 必须带
        // ProxySelector；null/blank/非法格式必须直连（warn + null，fail-loud 不中断抓取）。SSRF 链方法
        // 不变（仅换 HttpClient 实例）。
        WebFetchSecurity proxySec = WebFetchSecurity.withProxy("proxy.example.com:8080");
        assertThat(((HttpClient) ReflectionTestUtils.getField(proxySec, "httpClient")).proxy())
                .as("withProxy 合法 host:port 必须带 ProxySelector").isPresent();

        // 兼容 http:// 前缀（DB settings.proxy 可能带协议）
        WebFetchSecurity prefixedSec = WebFetchSecurity.withProxy("http://proxy.example.com:8080");
        assertThat(((HttpClient) ReflectionTestUtils.getField(prefixedSec, "httpClient")).proxy())
                .as("withProxy 兼容 http:// 前缀").isPresent();

        // null / blank → 直连
        assertThat(((HttpClient) ReflectionTestUtils.getField(WebFetchSecurity.withProxy(null), "httpClient")).proxy())
                .as("withProxy(null) → 直连").isEmpty();
        assertThat(((HttpClient) ReflectionTestUtils.getField(WebFetchSecurity.withProxy("   "), "httpClient")).proxy())
                .as("withProxy(blank) → 直连").isEmpty();

        // 非法格式（无端口）→ 直连（fail-loud warn）
        assertThat(((HttpClient) ReflectionTestUtils.getField(WebFetchSecurity.withProxy("no-port-host"), "httpClient")).proxy())
                .as("withProxy(非法格式) → 直连（fail-loud）").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    private static ToolUseBlock call(String url) {
        var input = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        input.put("url", url);
        input.put("prompt", "Summarize the content.");
        return new ToolUseBlock("t1", WebFetchTool.NAME, input);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 允许抛出受检异常的 handler（HttpServer handler 签名限制，测试内转译）。 */
    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static void safeAccept(ExchangeHandler handler, HttpExchange exchange) {
        try {
            handler.handle(exchange);
        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            } catch (IOException ignored) {
                // 已尽力响应
            }
        }
    }

    private void startHttpServer(String context, ExchangeHandler handler) throws IOException {
        if (httpServer == null) {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            httpServer.start();
        }
        httpServer.createContext(context, exchange -> safeAccept(handler, exchange));
    }

    private void startDomainCheckServer(String context, ExchangeHandler handler) throws IOException {
        startHttpServer(context, handler);
    }

    private void startHttpsServer(String context, ExchangeHandler handler) throws Exception {
        if (httpsServer == null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = WebFetchToolTest.class.getResourceAsStream("/hooks/https-test.p12")) {
                if (in == null) {
                    throw new IllegalStateException("缺少测试 keystore: /hooks/https-test.p12");
                }
                ks.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "changeit".toCharArray());
            SSLContext serverSsl = SSLContext.getInstance("TLS");
            serverSsl.init(kmf.getKeyManagers(), null, new SecureRandom());
            httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSsl));
            httpsServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            httpsServer.start();
        }
        httpsServer.createContext(context, exchange -> safeAccept(handler, exchange));
    }

    private static HttpClient plainClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(WebFetchSecurity.DOMAIN_CHECK_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 信任自签名测试证书 + 跳过 hostname 校验的 client（仅测试用，验证抓取/重定向/输出契约不触网 TLS）。
     *
     * <p>自签名证书（https-test.p12）无 127.0.0.1 的 IP SAN，JDK HttpClient 默认按 HTTPS endpoint
     * identification 校验 hostname 必失败——用 {@link X509ExtendedTrustManager} 全接受覆盖
     * {@code checkServerTrusted}（含 SSLEngine/Socket 变体），hostname 校验在 TLS 握手内被跳过。
     * 仅测试用：信任链全接受，hostname 校验已无安全意义。
     */
    private static HttpClient trustAllClient() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509ExtendedTrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                   java.net.Socket socket) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                   java.net.Socket socket) {
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                   javax.net.ssl.SSLEngine engine) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                   javax.net.ssl.SSLEngine engine) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(sc)
                .connectTimeout(Duration.ofMillis(WebFetchSecurity.DOMAIN_CHECK_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.OAuth401Refresher;
import com.nexusai.application.agent.oauth.AccountOAuthTokenRefresher;
import com.nexusai.application.agent.oauth.OAuthProviderConfig;
import com.nexusai.application.agent.oauth.OAuthTokenClient;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Remote Trigger 工具 · 行为契约对齐 CC {@code Open-ClaudeCode/src/tools/RemoteTriggerTool/RemoteTriggerTool.ts}.
 *
 * <p><b>F2 对齐变更（全量替换旧 fail-closed stub，不保留兼容壳/别名/双轨）</b>:
 * <ul>
 *   <li>name 从旧 stub 的下划线命名 → {@code 'RemoteTrigger'} — 对齐 CC
 *       {@code prompt.ts:1} {@code REMOTE_TRIGGER_TOOL_NAME='RemoteTrigger'}（同时修复与
 *       {@code ScheduleRemoteAgentsSkillRegistrar.java:47} 常量错配）.</li>
 *   <li>inputSchema 对齐 CC RemoteTriggerTool.ts:18-31 strictObject:
 *       {@code action} enum[list,get,create,update,run] + {@code trigger_id}
 *       regex {@code ^[\w-]+$} optional + {@code body} record optional.</li>
 *   <li>outputSchema 对齐 CC :35-40 {@code {status: number, json: string}}.</li>
 *   <li>isReadOnly = action∈{list,get}（CC :66-67）；isConcurrencySafe=true（CC :63-64）；
 *       shouldDefer=true（CC :50）；maxResultSizeChars=100_000（CC :49）；
 *       toAutoClassifierInput=`RemoteTrigger <action> <trigger_id?>`（CC :69-70）.</li>
 *   <li>execute 路由 5 action 到<b>自有触发体系</b>（ScheduleController {@code /api/v1/schedules}
 *       CRUD + {@code /{id}/run}），替代 Anthropic CCR/OAuth；20s timeout + abort signal
 *       （CC :135-143）；HTTP 错误态透传不抛（CC :142 validateStatus:()=>true）；
 *       mapToolResult `HTTP {status}\n{json}`（CC :152-158）.</li>
 * </ul>
 *
 * <p><b>偏差登记（F2）</b>:
 * <ul>
 *   <li>OPD-IMP-F2-1（FIX-3 已闭环）: CC update 用 POST base/{trigger_id}（RemoteTriggerTool.ts:120-126），
 *       Java 后端 ScheduleController 现补 POST /{id} 端点 + ScheduleService.update()，update 由 PATCH
 *       改 POST 全对齐（RV-C-03 G3/G4）。</li>
 *   <li>OPD-IMP-F2-2: CC isEnabled() 内 {@code isPolicyAllowed('allow_remote_sessions')}
 *       （RemoteTriggerTool.ts:60）无 Java 对应 policy 体系 → 登记 N/A，不硬造.
 *       门控保留 {@code @ConditionalOnProperty(nexusai.feature.agent-trigger-remote)}.</li>
 *   <li>20s timeout 用 {@code HttpClient.send} 同步阻塞，abort 仅在 send 前后查
 *       {@code ctx.abortController().isCancelled()}，无法中断 in-flight（CC axios signal 可中断）.</li>
 *   <li>OAuth Bearer 头接线（S6，对齐 CC RemoteTriggerTool.ts:79-93 + auth.ts:1360-1392）：
 *       execute 前从账号级 token 源 {@link AccountOAuthTokenService#readLatest} 读
 *       {@code Authorization: Bearer <accessToken>} 头，无 token 报错
 *       『Not authenticated with a {provider} account...』（CC :82-84）；
 *       401 时经 {@link OAuth401Refresher#handle401} 自愈刷新后重读 token，token 已变化则用新 token
 *       重发一次（对齐 CC http.ts:133-134 withOAuth401Retry 重试一次）。
 *       WF-7 provider 感知刷新：401 强制刷新走 {@link AccountOAuthTokenRefresher}（有 refresh_token
 *       的 provider 如 Google 走真实 refresh_token grant 刷新并写回存储；无 refresh_token 的 provider
 *       如 GitHub 由 OAuth401Refresher 的 null 门短路返回 false → 需重新授权，CC auth.ts:1464）。</li>
 * </ul>
 *
 * <p><b>base-url</b>: 属性 {@code nexusai.tools.remote-trigger.base-url}，缺省回退
 * {@code http://localhost:8080/api/v1/schedules}（生产应显式配置为实际 server 端口）.
 *
 * <p><b>[IMP-F2] CCR 对齐受控偏差登记（组 5-2 拍板 · 2026-08-16）</b>:
 * <ul>
 *   <li><b>后端归属（△-4）</b>: CC RemoteTriggerTool.ts:91-98 指向 claude.ai CCR API
 *       {@code https://api.anthropic.com/v1/code/triggers}（含 {@code anthropic-version}/
 *       {@code anthropic-beta 'ccr-triggers-2026-01-30'}/{@code x-organization-uuid} 头，
 *       CC :86-98 getOrganizationUUID）；Java <b>拍板保留自有 ScheduleController</b>
 *       （{@code /api/v1/schedules} + {@code /{id}/run}），CCR 端点不可达，登记为受控偏差
 *       （EV-F2-020 / TR-F2 △-4、✗-6）。</li>
 *   <li><b>401 自愈重试保留（⊕-1 / DEL-F2-01）</b>: CC 用 {@code validateStatus:()=>true} 直接透传
 *       401（RemoteTriggerTool.ts:135-143），<b>无</b>重试；Java 经 {@code OAuth401Refresher.handle401}
 *       刷新后重发一次 —— 属自有触发体系基础设施（token 刷新依赖），<b>拍板保留</b>
 *       （组 5-2 / 06-deletion-manifest DEL-F2-01 状态 🔒 保留）。</li>
 *   <li><b>searchHint（✗-1）</b>: CC :48 searchHint='manage scheduled remote agent triggers'；Java
 *       本工具未 override（Tool 基类默认 null），ToolSearch 不可命中 —— 接口层缺口归 IMP-C1
 *       基类/工具层统一处置，此处登记。</li>
 *   <li><b>isEnabled 门（△-1）</b>: CC :57-62 GB 'tengu_surreal_dali' {@code &&} policy
 *       {@code allow_remote_sessions}；Java 仅 {@code @ConditionalOnProperty(nexusai.feature.agent-trigger-remote)}
 *       属性门（无 GrowthBook/policy 体系，OPD-IMP-F2-2 登记 N/A）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "nexusai.feature.agent-trigger-remote", havingValue = "true", matchIfMissing = false)
public class RemoteTriggerTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RemoteTriggerTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对齐 CC RemoteTriggerTool.ts:49 maxResultSizeChars=100_000. */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    /** 对齐 CC RemoteTriggerTool.ts:140 timeout: 20_000（ms）. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 对齐 CC RemoteTriggerTool.ts:21-23 trigger_id regex ^[\w-]+$. */
    private static final Pattern TRIGGER_ID_PATTERN = Pattern.compile("^[\\w-]+$");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final AccountOAuthTokenService accountOAuthTokenService;
    private final String oauthProvider;
    private final OAuth401Refresher oauth401Refresher;

    /**
     * 测试/非 Spring 直接构造便捷入口：无 provider 配置（空列表）→ 回退 no-op 刷新器
     * （fail-closed：无 config 无法刷新，401 需重新授权）。
     */
    public RemoteTriggerTool(String baseUrl, AccountOAuthTokenService accountOAuthTokenService,
            String oauthProvider) {
        this(baseUrl, accountOAuthTokenService, oauthProvider, List.of());
    }

    /**
     * Spring 注入入口：{@code List<OAuthProviderConfig>} 由容器收集全部 provider 配置 bean
     * （GitHub/Google），按 {@code oauthProvider} 反查匹配实例构造真实
     * {@link AccountOAuthTokenRefresher}（provider-aware 刷新通道）；无匹配配置（provider 未接线
     * 或测试空列表）→ 回退 no-op 刷新器。
     */
    @Autowired
    public RemoteTriggerTool(
            @Value("${nexusai.tools.remote-trigger.base-url:}") String baseUrl,
            AccountOAuthTokenService accountOAuthTokenService,
            @Value("${nexusai.tools.remote-trigger.oauth-provider:github}") String oauthProvider,
            List<OAuthProviderConfig> providerConfigs) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://localhost:8080/api/v1/schedules"
                : trimTrailingSlash(baseUrl);
        this.accountOAuthTokenService = accountOAuthTokenService;
        this.oauthProvider = (oauthProvider == null || oauthProvider.isBlank()) ? "github" : oauthProvider;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        // 对齐 CC auth.ts:1360-1392 handleOAuth401Error：自建 401 自愈处理器。
        // TokenStore = readLatest(oauthProvider) 映射；TokenRefresher 按 provider 配置反查：
        // 匹配到 config → 真实 AccountOAuthTokenRefresher（数据驱动：有 refresh_token 走
        // refresh_token grant 刷新，无 refresh_token 由 OAuth401Refresher 的 null 门短路返回 false
        // 重新授权，CC auth.ts:1464）；无匹配 config → 回退 no-op（fail-closed）。
        OAuthProviderConfig config = resolveProviderConfig(this.oauthProvider, providerConfigs);
        OAuth401Refresher.TokenRefresher refresher;
        if (config != null) {
            refresher = new AccountOAuthTokenRefresher(config, new OAuthTokenClient(),
                accountOAuthTokenService);
        } else {
            refresher = () -> {
                log.warn("RemoteTrigger OAuth 强制刷新不可用：provider={} 无匹配 OAuthProviderConfig"
                        + "（配置未接线），401 需重新授权", this.oauthProvider);
                return false;
            };
        }
        this.oauth401Refresher = new OAuth401Refresher(this::readCurrentOAuthTokens, refresher);
        if (log.isDebugEnabled()) {
            log.debug("RemoteTriggerTool 构造完成: baseUrl={} oauthProvider={} 有刷新器={}（自有触发体系，OAuth Bearer 头接线）",
                    this.baseUrl, this.oauthProvider, config != null);
        }
    }

    /** 按 provider 名反查配置实例（null-safe；无匹配返回 null → 调用方回退 no-op 刷新器）。 */
    private static OAuthProviderConfig resolveProviderConfig(String provider,
            List<OAuthProviderConfig> configs) {
        if (configs == null) {
            return null;
        }
        for (OAuthProviderConfig c : configs) {
            if (c != null && provider.equals(c.provider())) {
                return c;
            }
        }
        return null;
    }

    /** 读取当前账号级 access token（对齐 CC getClaudeAIOAuthTokens()?.accessToken，auth.ts:1255）。 */
    private String readAccessToken() {
        AccountOAuthToken token = accountOAuthTokenService == null ? null
                : accountOAuthTokenService.readLatest(oauthProvider);
        return token == null ? null : token.getAccessToken();
    }

    /** 映射账号级 token 为 OAuth401Refresher.OAuthTokens（对齐 CC clearOAuthTokenCache + getClaudeAIOAuthTokensAsync）。 */
    private OAuth401Refresher.OAuthTokens readCurrentOAuthTokens() {
        AccountOAuthToken token = accountOAuthTokenService == null ? null
                : accountOAuthTokenService.readLatest(oauthProvider);
        return token == null ? null
                : new OAuth401Refresher.OAuthTokens(token.getAccessToken(), token.getRefreshToken());
    }

    private static String trimTrailingSlash(String url) {
        String t = url.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    @Override
    public String name() {
        // 对齐 CC prompt.ts:1 REMOTE_TRIGGER_TOOL_NAME='RemoteTrigger'
        return "RemoteTrigger";
    }

    @Override
    public String description() {
        // 对齐 CC prompt.ts:3-4 DESCRIPTION（后端替换为自有触发体系）
        return "Manage scheduled remote agent triggers via the backend schedule service. "
             + "Enable via nexusai.feature.agent-trigger-remote=true.";
    }

    @Override
    public JsonNode inputSchema() {
        // 对齐 CC RemoteTriggerTool.ts:18-31 z.strictObject（strictObject → additionalProperties:false）
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.set("action", JSON.createObjectNode()
                .put("type", "string")
                .put("description", "CC original: action (RemoteTriggerTool.ts:20)")
                .set("enum", JSON.createArrayNode()
                        .add("list").add("get").add("create").add("update").add("run")));
        props.set("trigger_id", JSON.createObjectNode()
                .put("type", "string")
                .put("pattern", "^[\\w-]+$")
                .put("description", "Required for get, update, and run. "
                        + "CC original: trigger_id (RemoteTriggerTool.ts:21-25)"));
        props.set("body", JSON.createObjectNode()
                .put("type", "object")
                .put("description", "JSON body for create and update. "
                        + "CC original: body (RemoteTriggerTool.ts:26-29)"));
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        // 对齐 CC RemoteTriggerTool.ts:35-40 z.object({status:z.number(),json:z.string()})
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("status").put("type", "integer");
        props.putObject("json").put("type", "string");
        schema.putArray("required").add("status").add("json");
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        // 对齐 CC RemoteTriggerTool.ts:66-67 isReadOnly = list || get
        String action = input != null ? input.path("action").asText(null) : null;
        return "list".equals(action) || "get".equals(action);
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        // 对齐 CC RemoteTriggerTool.ts:63-64 isConcurrencySafe() { return true }
        return true;
    }

    @Override
    public boolean shouldDefer(JsonNode input) {
        // 对齐 CC RemoteTriggerTool.ts:50 shouldDefer: true（常量，与 input 无关）
        return true;
    }

    @Override
    public long maxResultSizeChars() {
        // 对齐 CC RemoteTriggerTool.ts:49 maxResultSizeChars: 100_000
        return MAX_RESULT_SIZE_CHARS;
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC RemoteTriggerTool.ts:69-70
        // `RemoteTrigger ${action}${trigger_id ? ` ${trigger_id}` : ''}`
        if (input == null) {
            return "RemoteTrigger";
        }
        String action = input.path("action").asText(null);
        String triggerId = input.hasNonNull("trigger_id")
                ? input.get("trigger_id").asText() : null;
        return "RemoteTrigger " + action
                + (triggerId != null && !triggerId.isBlank() ? " " + triggerId : "");
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String action = input.path("action").asText(null);
        if (action == null || action.isBlank()) {
            return ToolResult.error(call.id(), "action is required (one of list,get,create,update,run)");
        }
        String triggerId = input.hasNonNull("trigger_id") ? input.get("trigger_id").asText() : null;
        if (triggerId != null && !TRIGGER_ID_PATTERN.matcher(triggerId).matches()) {
            // 对齐 CC RemoteTriggerTool.ts:21-24 zod regex ^[\w-]+$ 拒绝
            return ToolResult.error(call.id(),
                "trigger_id '" + triggerId + "' does not match ^[\\w-]+$");
        }
        JsonNode body = input.hasNonNull("body") ? input.get("body") : null;

        // 对齐 CC RemoteTriggerTool.ts:104-133 switch 路由 + 缺参 throw
        String method;
        String urlPath;
        String requestBody;
        switch (action) {
            case "list" -> {
                method = "GET";
                urlPath = "";
                requestBody = null;
            }
            case "get" -> {
                if (triggerId == null) return ToolResult.error(call.id(), "get requires trigger_id");
                method = "GET";
                urlPath = "/" + triggerId;
                requestBody = null;
            }
            case "create" -> {
                if (body == null) return ToolResult.error(call.id(), "create requires body");
                method = "POST";
                urlPath = "";
                requestBody = body.toString();
            }
            case "update" -> {
                if (triggerId == null) return ToolResult.error(call.id(), "update requires trigger_id");
                if (body == null) return ToolResult.error(call.id(), "update requires body");
                // 对齐 CC RemoteTriggerTool.ts:120-126 update=POST base/{trigger_id}
                // （FIX-3 修正原 OPD-IMP-F2-1 偏差映射：update 由 PATCH 改 POST）
                method = "POST";
                urlPath = "/" + triggerId;
                requestBody = body.toString();
            }
            case "run" -> {
                if (triggerId == null) return ToolResult.error(call.id(), "run requires trigger_id");
                method = "POST";
                urlPath = "/" + triggerId + "/run";
                requestBody = null;
            }
            default -> {
                return ToolResult.error(call.id(),
                    "action '" + action + "' is not one of list,get,create,update,run");
            }
        }

        // 对齐 CC RemoteTriggerTool.ts:80-85：读账号级 access token，无 token 报错
        String accessToken = readAccessToken();
        if (accessToken == null) {
            log.warn("RemoteTrigger 无 OAuth token（provider={} 未授权）: action={} path={}",
                oauthProvider, action, urlPath);
            return ToolResult.error(call.id(),
                "Not authenticated with a " + oauthProvider + " account. Run OAuth login and try again.");
        }

        // 对齐 CC RemoteTriggerTool.ts:141 abort signal: 发送前检查
        if (isAborted(ctx)) {
            return ToolResult.error(call.id(), "RemoteTrigger aborted before request (user cancelled)");
        }

        try {
            HttpResponse<String> res = sendRequest(method, urlPath, requestBody, accessToken);
            // 对齐 CC RemoteTriggerTool.ts:142 validateStatus:()=>true → 4xx/5xx 透传不抛
            int status = res.statusCode();
            String json = res.body() == null ? "" : res.body();

            if (status == 401) {
                // 对齐 CC http.ts:133-134 withOAuth401Retry：401 自愈刷新后重读 token，token 已变化则重发一次
                oauth401Refresher.handle401(accessToken);
                String refreshedToken = readAccessToken();
                if (refreshedToken != null && !refreshedToken.equals(accessToken)) {
                    res = sendRequest(method, urlPath, requestBody, refreshedToken);
                    status = res.statusCode();
                    json = res.body() == null ? "" : res.body();
                    log.info("RemoteTrigger 401 自愈重试完成: action={} method={} path={} status={}",
                        action, method, urlPath, status);
                } else {
                    log.warn("RemoteTrigger 401 自愈未恢复 token（provider={} 无 refresh_token 或刷新失败，需重新授权）: action={} path={}",
                        oauthProvider, action, urlPath);
                }
            }

            if (isAborted(ctx)) {
                return ToolResult.error(call.id(), "RemoteTrigger aborted after request (user cancelled)");
            }
            if (log.isDebugEnabled()) {
                log.debug("RemoteTrigger 请求完成: action={} method={} path={} status={} json长度={}",
                    action, method, urlPath, status, json.length());
            }
            // 数据流日志：成功/HTTP 错误态均记录（中文）
            log.info("RemoteTrigger 数据流: action={} trigger_id={} method={} status={}",
                action, triggerId == null ? "-" : triggerId, method, status);
            return ToolResult.success(call.id(), renderHttpResult(status, json));
        } catch (java.net.http.HttpTimeoutException e) {
            // 对齐 CC timeout:20_000（axios 超时抛错）
            log.warn("RemoteTrigger 超时: action={} method={} path={}（20s）", action, method, urlPath);
            return ToolResult.error(call.id(), "RemoteTrigger request timed out after 20s");
        } catch (Exception e) {
            log.warn("RemoteTrigger 请求失败: action={} method={} path={}: {}", action, method, urlPath,
                e.getMessage());
            return ToolResult.error(call.id(),
                "RemoteTrigger request failed: " + e.getMessage());
        }
    }

    /**
     * 构造并发送请求（补 {@code Authorization: Bearer <accessToken>} 头，对齐 CC RemoteTriggerTool.ts:92-93）。
     */
    private HttpResponse<String> sendRequest(String method, String urlPath, String requestBody,
            String accessToken) throws java.io.IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + urlPath))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken);
        if ("GET".equals(method)) {
            reqBuilder.GET();
        } else {
            reqBuilder.method("POST", bodyOf(requestBody != null ? requestBody : "{}"));
        }
        return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher bodyOf(String body) {
        return body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
    }

    private static boolean isAborted(ToolUseContext ctx) {
        return ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled();
    }

    /**
     * 对齐 CC RemoteTriggerTool.ts:156 content = `HTTP ${status}\n${json}`。
     */
    private static String renderHttpResult(int status, String json) {
        return "HTTP " + status + "\n" + json;
    }

    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        // 对齐 CC RemoteTriggerTool.ts:152-158 mapToolResultToToolResultBlockParam
        // { tool_use_id, type:'tool_result', content:`HTTP ${status}\n${json}` }
        if (result == null || isError || result.data() == null) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteTrigger mapToToolResultBlockParam 跳过: isError 或 data=null");
            }
            return null;
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", String.valueOf(result.data()), false);
    }
}

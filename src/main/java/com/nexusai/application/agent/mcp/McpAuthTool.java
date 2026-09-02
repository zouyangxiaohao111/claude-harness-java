package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * McpAuthTool 伪工具 · 对齐 CC tools/McpAuthTool/McpAuthTool.ts.
 *
 * <p>L1 语义: 为已安装但未认证的 MCP server 创建伪工具.
 *            调用时启动 OAuth flow (skipBrowserOpen=true) 并返回 auth URL;
 *            OAuth 完成后 (后台) 重新连接并替换 appState.mcp.tools (prefix-based).
 *            claudeai-proxy 类型不支持 — 提示用户 /mcp;
 *            非 sse/http 类型不支持 — 提示用户 /mcp.
 *
 * <p>[D3 接线] 本类实现 {@link Tool} 接口 (对齐 CC createMcpAuthTool 返回的 Tool,
 * McpAuthTool.ts:62-214): name = {@link McpStringUtils#buildMcpToolName}(server, 'authenticate'),
 * 替代被删除的 impl/McpAuthTool（CC 无此工具名的 placeholder）。needs-auth 触发点登记 WF-D-O5；
 * needs-auth 接线由 {@link McpToolPool} + {@link McpNeedsAuthCache} 承担（S3）。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: createMcpAuthTool(serverName, config) → McpAuthTool;
 *       McpAuthOutput 3 字段 (status enum + message + authUrl?);
 *       3 status: 'auth_url' / 'unsupported' / 'error';
 *       config.type 4 种: 'sse' / 'http' / 'claudeai-proxy' / 其他.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — call(input, context) →
 *       type='claudeai-proxy' → return unsupported (suggest /mcp) →
 *       type 非 sse/http → return unsupported (suggest /mcp) →
 *       sse/http → performMCPOAuthFlow + race authUrl vs completion →
 *       authUrl 非空 → return {status:auth_url, authUrl, message};
 *       否则 → return {status:auth_url, message: silent success};
 *       后台 reconnect + swap tools (prefix-based).</li>
 *   <li><b>A3</b>: 状态: NOT_CALLED → OAUTH_STARTED → (URL_CAPTURED | SILENT_OK | ERROR);
 *       后台: OAUTH_COMPLETE → RECONNECTED + TOOLS_SWAPPED.</li>
 *   <li><b>A4</b>: claudeai-proxy → 不启动 OAuth;sse/http 启动失败 → error status + suggest /mcp;
 *       捕获 oauth 抛错 → error status.</li>
 *   <li><b>A5</b>: 真实场景 — user 运行未认证 MCP server → tool 出现在列表 → 模型调用 → 拿 auth URL → 用户浏览器授权 → 后台 reconnect + 真实工具替换.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/await + AbortController → Java CompletableFuture + cancellation;
 *                    TS `Promise.race([authUrlPromise, oauthPromise.then(...)])` → CompletableFuture.anyOf;
 *                    TS `setAppState(prev => ({...}))` → 注入式 StateUpdater;
 *                    TS `lodash/reject` → Java stream filter (negate).
 */
public final class McpAuthTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpAuthTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String STATUS_AUTH_URL = "auth_url";
    public static final String STATUS_UNSUPPORTED = "unsupported";
    public static final String STATUS_ERROR = "error";

    /** OAuth flow 成功完成信号 · CC oauthPromise resolve 等价（McpAuthTool.ts:177-180 race 分支 1）。 */
    private static final Object OAUTH_DONE = new Object();

    /** OAuth flow 失败信号 · CC oauthPromise reject 等价（McpAuthTool.ts:198-205 race reject → error status）。 */
    private record OAuthFailure(String message) {}

    private final String serverName;
    private final McpServerConfig config;
    private final OAuthFlowStarter oauthFlowStarter;
    private final ReconnectRunner reconnectRunner;
    private final StateUpdater stateUpdater;
    private final DebugLogger debugLogger;
    private final ErrorLogger errorLogger;
    /** CC original: description (McpAuthTool.ts:57-60) · createMcpAuthTool 内闭包计算，name 参数化 server。 */
    private final String description;

    public McpAuthTool(String serverName, McpServerConfig config,
                        OAuthFlowStarter oauthFlowStarter,
                        ReconnectRunner reconnectRunner,
                        StateUpdater stateUpdater,
                        DebugLogger debugLogger,
                        ErrorLogger errorLogger) {
        this.serverName = Objects.requireNonNull(serverName);
        this.config = Objects.requireNonNull(config);
        this.oauthFlowStarter = Objects.requireNonNull(oauthFlowStarter);
        this.reconnectRunner = Objects.requireNonNull(reconnectRunner);
        this.stateUpdater = Objects.requireNonNull(stateUpdater);
        this.debugLogger = Objects.requireNonNull(debugLogger);
        this.errorLogger = Objects.requireNonNull(errorLogger);
        // CC McpAuthTool.ts:53-60 location = url ? `${transport} at ${url}` : transport
        String transport = config.effectiveType();
        String url = config.url();
        String location = url != null && !url.isBlank() ? transport + " at " + url : transport;
        this.description = "The `" + serverName + "` MCP server (" + location
            + ") is installed but requires authentication. "
            + "Call this tool to start the OAuth flow — you'll receive an authorization URL to share with the user. "
            + "Once the user completes authorization in their browser, the server's real tools will become available automatically.";
    }

    /** CC McpAuthOutput. */
    public record McpAuthOutput(String status, String message, String authUrl) {
        public static McpAuthOutput authUrl(String authUrl, String message) {
            return new McpAuthOutput(STATUS_AUTH_URL, message, authUrl);
        }
        public static McpAuthOutput silentSuccess(String message) {
            return new McpAuthOutput(STATUS_AUTH_URL, message, null);
        }
        public static McpAuthOutput unsupported(String message) {
            return new McpAuthOutput(STATUS_UNSUPPORTED, message, null);
        }
        public static McpAuthOutput error(String message) {
            return new McpAuthOutput(STATUS_ERROR, message, null);
        }
    }

    /** CC ScopedMcpServerConfig 最小子集. */
    public record McpServerConfig(String type, String url, String scope) {
        public String effectiveType() {
            return type != null ? type : "stdio";
        }
    }

    /** CC Tool result wrapper. */
    public record McpAuthToolResult(McpAuthOutput data) {
        public static McpAuthToolResult of(McpAuthOutput output) {
            return new McpAuthToolResult(output);
        }
    }

    /** OAuth flow starter (注入). */
    @FunctionalInterface
    public interface OAuthFlowStarter {
        /** 启动 OAuth; callback 接收 auth URL. */
        void start(String serverName, McpServerConfig config,
                   Consumer<String> onAuthUrl, Consumer<String> onComplete,
                   OAuthFlowOptions options);
    }

    public record OAuthFlowOptions(boolean skipBrowserOpen) {}

    /** Reconnect runner (注入). */
    @FunctionalInterface
    public interface ReconnectRunner {
        ReconnectResult reconnect(String serverName, McpServerConfig config);
    }

    public record ReconnectResult(McpState.Client client,
                                   List<String> tools,
                                   List<String> commands,
                                   Object resources) {}

    /** State updater (注入). */
    @FunctionalInterface
    public interface StateUpdater {
        void update(Function<McpState, McpState> updater);
    }

    @FunctionalInterface
    public interface DebugLogger { void log(String serverName, String msg); }

    @FunctionalInterface
    public interface ErrorLogger { void log(String serverName, String msg); }

    // ═══════════ Tool 接线 · 对齐 CC createMcpAuthTool 返回的 Tool (McpAuthTool.ts:62-214) ═══════════

    /** CC original: name (McpAuthTool.ts:63) buildMcpToolName(serverName, 'authenticate'). */
    @Override
    public String name() {
        return McpStringUtils.buildMcpToolName(serverName, "authenticate");
    }

    /** CC original: description (McpAuthTool.ts:57-60) — server 需认证说明. */
    @Override
    public String description() {
        return description;
    }

    /** CC original: prompt (McpAuthTool.ts:76-78) — 与 description 同一文本. */
    @Override
    public String prompt() {
        return description;
    }

    /** CC original: inputSchema (McpAuthTool.ts:23/79-81) z.object({}) — 伪工具无参数. */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    /** CC original: isMcp (McpAuthTool.ts:64) — 恒 true. */
    @Override
    public boolean isMcp() {
        return true;
    }

    /** CC original: mcpInfo (McpAuthTool.ts:65) { serverName, toolName: 'authenticate' }. */
    @Override
    public McpServerInfo mcpInfo() {
        // [IMP-E1 DC-2] CC mcpInfo 仅 2 字段（McpAuthTool.ts:65）——serverUrl 不再承载于 mcpInfo。
        return new McpServerInfo(serverName, "authenticate");
    }

    /** CC original: isEnabled (McpAuthTool.ts:66) — 恒 true. */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /** CC original: isConcurrencySafe (McpAuthTool.ts:67) — 启动 OAuth flow 不可并发. */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** CC original: isReadOnly (McpAuthTool.ts:68) — 启动 OAuth flow 非只读. */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    /** CC original: toAutoClassifierInput (McpAuthTool.ts:69) — 恒返回 serverName. */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        return serverName;
    }

    /** CC original: userFacingName (McpAuthTool.ts:70) — `${serverName} - authenticate (MCP)`. */
    @Override
    public String userFacingName() {
        return serverName + " - authenticate (MCP)";
    }

    /** CC original: maxResultSizeChars (McpAuthTool.ts:71) — 10_000. */
    @Override
    public long maxResultSizeChars() {
        return 10_000L;
    }

    /** CC original: renderToolUseMessage (McpAuthTool.ts:72) — `Authenticate ${serverName} MCP server`. */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        return "Authenticate " + serverName + " MCP server";
    }

    /** CC original: checkPermissions (McpAuthTool.ts:82-84) — 恒 allow. */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input, new PermissionDecisionReason.Other("McpAuthTool always allow"), null, false, null, List.of());
    }

    /**
     * CC original: call (McpAuthTool.ts:85-205) — input 忽略，仅用 config；返回
     * ToolResult(data = message)。3 status 分支经 {@link #call(Map, Object)} 主链落地。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock block) {
        if (log.isDebugEnabled()) {
            log.debug("McpAuthTool 执行: server={} id={}", serverName, block.id());
        }
        McpAuthToolResult result = this.call(Map.of(), null);
        McpAuthOutput out = result.data();
        if (log.isDebugEnabled()) {
            log.debug("McpAuthTool 结果: server={} status={} 有authUrl={} 消息长度={}",
                serverName, out.status(), out.authUrl() != null,
                out.message() == null ? 0 : out.message().length());
        }
        return new ToolResult<>(out.message(), null, null, null);
    }

    /**
     * CC original: mapToolResultToToolResultBlockParam (McpAuthTool.ts:207-213)
     * { tool_use_id, type:'tool_result', content: data.message }.
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        String content = result.data() == null ? "" : String.valueOf(result.data());
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /** CC call — 主链. */
    public McpAuthToolResult call(Map<String, Object> input, Object context) {
        // claudeai-proxy 不通过本工具认证
        if ("claudeai-proxy".equals(config.effectiveType())) {
            return McpAuthToolResult.of(McpAuthOutput.unsupported(
                "This is a claude.ai MCP connector. Ask the user to run /mcp and select \""
                    + serverName + "\" to authenticate."));
        }

        // 仅 sse/http 支持 OAuth from this tool
        String transport = config.effectiveType();
        if (!"sse".equals(transport) && !"http".equals(transport)) {
            String location = config.url() != null ? transport + " at " + config.url() : transport;
            return McpAuthToolResult.of(McpAuthOutput.unsupported(
                "Server \"" + serverName + "\" uses " + location
                    + " transport which does not support OAuth from this tool. "
                    + "Ask the user to run /mcp and authenticate manually."));
        }

        // 启动 OAuth flow,捕获 authUrl + completion (CC Promise.race 等价)
        // [IMP-E G2] OAuth 失败挂起修复：CC McpAuthTool.ts:126-180 oauthPromise（= performMCPOAuthFlow）
        //   失败时 reject → Promise.race reject → catch 返回 error status（:198-205）；Java 旧实现
        //   success=false 不触发 onComplete → completionFuture 永不完成 → anyOf().get() 永久挂起
        //   （P0 OPD-E1-Q1）。修复：starter 在失败时也触发 onComplete（"error:..." 信号），
        //   本方法据此返回 error status，不再阻塞。
        CompletableFuture<String> authUrlFuture = new CompletableFuture<>();
        CompletableFuture<Object> completionFuture = new CompletableFuture<>();
        try {
            oauthFlowStarter.start(serverName, config,
                url -> authUrlFuture.complete(url),
                signal -> {
                    // starter 完成信号：成功（"success"/null）或失败（"error:..."）。
                    // 值仅作信号载体（McpAuthTool 内不依赖具体文本）。
                    if (signal != null && signal.startsWith("error")) {
                        String msg = signal.startsWith("error:") ? signal.substring(6) : signal;
                        completionFuture.complete(new OAuthFailure(msg));
                    } else {
                        completionFuture.complete(OAUTH_DONE);
                    }
                },
                new OAuthFlowOptions(true));
        } catch (Exception e) {
            return McpAuthToolResult.of(McpAuthOutput.error(
                "Failed to start OAuth flow for " + serverName + ": " + e.getMessage()
                    + ". Ask the user to run /mcp and authenticate manually."));
        }

        // 后台: OAuth 成功后才 reconnect + swap tools（CC oauthPromise.then(async () => {...})，
        //   失败走 .catch logMCPError :167-172，不重连）
        completionFuture.thenAccept(signal -> {
            if (signal == OAUTH_DONE) {
                performPostOAuthReconnect();
            }
        }).exceptionally(ex -> {
            errorLogger.log(serverName,
                "OAuth flow failed after tool-triggered start: " + ex.getMessage());
            return null;
        });

        try {
            // Race: authUrlFuture OR completionFuture (silent success / error)
            // [IMP-E1 DC-1] 移除 30s 超时（TR-E1-DC-1）：CC McpAuthTool.ts:174-197 用
            //   Promise.race([authUrlPromise, oauthPromise.then(...)]) 无超时 —— 等待浏览器
            //   授权结束；Java 等价 = CompletableFuture.anyOf().get()（阻塞无超时）。
            //   浏览器授权常 >30s，原 `.get(30, SECONDS)` 会误报 "Failed to start OAuth flow"。
            Object first = CompletableFuture.anyOf(authUrlFuture, completionFuture)
                .get();
            if (first instanceof String authUrl) {
                return McpAuthToolResult.of(McpAuthOutput.authUrl(authUrl,
                    "Ask the user to open this URL in their browser to authorize the "
                        + serverName + " MCP server:\n\n" + authUrl
                        + "\n\nOnce they complete the flow, the server's tools will become available automatically."));
            }
            if (first instanceof OAuthFailure failure) {
                // [IMP-E G2] 失败先完成 → error status（CC McpAuthTool.ts:198-205）
                return McpAuthToolResult.of(McpAuthOutput.error(
                    "Failed to start OAuth flow for " + serverName + ": " + failure.message()
                        + ". Ask the user to run /mcp and authenticate manually."));
            }
            // completion 先完成 → silent success（OAUTH_DONE）
            return McpAuthToolResult.of(McpAuthOutput.silentSuccess(
                "Authentication completed silently for " + serverName
                    + ". The server's tools should now be available."));
        } catch (Exception e) {
            return McpAuthToolResult.of(McpAuthOutput.error(
                "Failed to start OAuth flow for " + serverName + ": " + e.getMessage()
                    + ". Ask the user to run /mcp and authenticate manually."));
        }
    }

    private void performPostOAuthReconnect() {
        ReconnectResult result = reconnectRunner.reconnect(serverName, config);
        String prefix = "mcp__" + serverName + "__";
        stateUpdater.update(prev -> {
            McpState updated = new McpState(prev);
            // Replace client
            updated.clients = filterAndReplace(prev.clients, c -> c.name(),
                serverName, result.client());
            // Replace tools (prefix-based)
            updated.tools = filterObjectsByPrefix((List<Object>) (List<?>) prev.tools, prefix, (List<Object>) (List<?>) result.tools());
            // Replace commands
            updated.commands = filterObjectsByPrefix((List<Object>) (List<?>) prev.commands, prefix, (List<Object>) (List<?>) result.commands());
            // Add resources
            if (result.resources() != null) {
                updated.resources = appendResources(prev.resources, serverName, result.resources());
            }
            return updated;
        });
        debugLogger.log(serverName,
            "OAuth complete, reconnected with " + result.tools().size() + " tool(s)");
    }

    // ---------- helpers ----------

    private static <T> List<T> filterAndReplace(List<T> list, Function<T, String> nameFn,
                                                  String targetName, T replacement) {
        List<T> result = new java.util.ArrayList<>();
        for (T item : list) {
            if (!targetName.equals(nameFn.apply(item))) {
                result.add(item);
            }
        }
        result.add(replacement);
        return result;
    }

    private static <T> List<T> filterObjectsByPrefix(List<T> list, String prefix, List<T> additions) {
        List<T> result = new java.util.ArrayList<>();
        for (T item : list) {
            String name = item instanceof String s ? s : null;
            if (name == null || !name.startsWith(prefix)) {
                result.add(item);
            }
        }
        result.addAll(additions);
        return result;
    }

    private static Map<String, Object> appendResources(Map<String, Object> prev, String key, Object value) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>(prev);
        m.put(key, value);
        return m;
    }

    /** MCP state (subset). */
    public static class McpState {
        public List<McpState.Client> clients = List.of();
        public List<Object> tools = List.of();
        public List<Object> commands = List.of();
        public Map<String, Object> resources = Map.of();

        public McpState() {}
        public McpState(McpState other) {
            this.clients = other.clients;
            this.tools = other.tools;
            this.commands = other.commands;
            this.resources = other.resources;
        }

        public record Client(String name) {}
    }
}

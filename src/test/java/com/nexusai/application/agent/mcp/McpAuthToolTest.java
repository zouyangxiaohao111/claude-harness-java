package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.mcp.McpAuthTool.DebugLogger;
import com.nexusai.application.agent.mcp.McpAuthTool.ErrorLogger;
import com.nexusai.application.agent.mcp.McpAuthTool.McpAuthOutput;
import com.nexusai.application.agent.mcp.McpAuthTool.McpAuthToolResult;
import com.nexusai.application.agent.mcp.McpAuthTool.McpServerConfig;
import com.nexusai.application.agent.mcp.McpAuthTool.McpState;
import com.nexusai.application.agent.mcp.McpAuthTool.OAuthFlowStarter;
import com.nexusai.application.agent.mcp.McpAuthTool.ReconnectResult;
import com.nexusai.application.agent.mcp.McpAuthTool.ReconnectRunner;
import com.nexusai.application.agent.mcp.McpAuthTool.StateUpdater;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpAuthTool 接线为 Tool 的 CC 对齐聚焦测试。
 *
 * <p>WHY：CC createMcpAuthTool 返回的 Tool（McpAuthTool.ts:49-215）在 Java 侧此前是未接线的
 * 死代码（不实现 Tool 接口），而生产注册的却是 impl/McpAuthTool（CC 无此工具名的 placeholder）。
 * 本测试锁定接线不变量：name=buildMcpToolName(server,'authenticate')、
 * 3 status 分支（claudeai-proxy unsupported / 非 sse-http unsupported / race auth_url vs silent/error）、
 * toAutoClassifierInput=serverName、userFacingName、isReadOnly/isConcurrencySafe=false、
 * maxResultSizeChars=10_000、checkPermissions=allow、mapToToolResultBlockParam→data.message。
 */
class McpAuthToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CC client.ts:2318/:2331 needs-auth 伪工具注册名 → mcp__<server>__authenticate。 */
    private static final String SERVER = "my-server";

    private McpAuthTool tool(McpServerConfig config, OAuthFlowStarter starter) {
        // 构造器对注入依赖 requireNonNull —— null 时默认空实现（未触发 OAuth 的分支不消费）
        OAuthFlowStarter effective = starter != null ? starter : (s, c, onUrl, onComplete, opts) -> { };
        ReconnectRunner reconnect = (s, c) -> new ReconnectResult(
            new McpState.Client(s), List.of(), List.of(), null);
        StateUpdater updater = ignored -> { };
        DebugLogger debug = (s, m) -> { };
        ErrorLogger error = (s, m) -> { };
        return new McpAuthTool(SERVER, config, effective, reconnect, updater, debug, error);
    }

    private ToolUseBlock call(String inputJson) throws Exception {
        JsonNode input = MAPPER.readTree(inputJson);
        return new ToolUseBlock("toolu_1", "mcp__" + SERVER + "__authenticate", input);
    }

    private static String message(AgentToolResult<?> r) {
        return (String) r.data();
    }

    // ───────────── name / mcpInfo / isMcp（McpAuthTool.ts:63-65 + mcpStringUtils.ts:50-52）─────────────

    @Test
    void name_buildMcpToolNameWithAuthenticate() {
        // WHY: client.ts:2318/:2331 注册 needs-auth 伪工具用 buildMcpToolName(server,'authenticate')
        // → mcp__<normalized_server>__authenticate；server 名经 normalizeNameForMCP 规范化
        assertEquals("mcp__my-server__authenticate", tool(config("sse"), null).name());
    }

    @Test
    void mcpInfoAndIsMcp_matchAuthenticateToolName() {
        // WHY: McpAuthTool.ts:65 mcpInfo: { serverName, toolName: 'authenticate' } — 反解析/权限匹配用
        McpAuthTool t = tool(config("sse"), null);
        assertTrue(t.isMcp(), "isMcp=true（McpAuthTool.ts:64）");
        assertEquals(SERVER, t.mcpInfo().serverName());
        assertEquals("authenticate", t.mcpInfo().toolName());
    }

    // ───────────── Tool 语义字段（McpAuthTool.ts:66-84）─────────────

    @Test
    void toAutoClassifierInput_returnsServerName() {
        // WHY: McpAuthTool.ts:69 toAutoClassifierInput: () => serverName — 分类器用 server 归属
        McpAuthTool t = tool(config("sse"), null);
        assertEquals(SERVER, t.toAutoClassifierInput(MAPPER.createObjectNode()));
    }

    @Test
    void userFacingName_isServerDashAuthenticateMcp() {
        // WHY: McpAuthTool.ts:70 userFacingName: () => `${serverName} - authenticate (MCP)`
        McpAuthTool t = tool(config("sse"), null);
        assertEquals(SERVER + " - authenticate (MCP)", t.userFacingName());
    }

    @Test
    void safetyFlags_readOnlyAndConcurrencySafeAreFalse() {
        // WHY: McpAuthTool.ts:67-68 isConcurrencySafe=false + isReadOnly=false — 触发 OAuth 有副作用
        McpAuthTool t = tool(config("sse"), null);
        assertFalse(t.isConcurrencySafe(null), "启动 OAuth flow 不可并发");
        assertFalse(t.isReadOnly(null), "启动 OAuth flow 非只读");
    }

    @Test
    void maxResultSizeChars_isTenThousand() {
        // WHY: McpAuthTool.ts:71 maxResultSizeChars: 10_000 — auth URL 文本结果小，超出阈值才落盘
        McpAuthTool t = tool(config("sse"), null);
        assertEquals(10_000L, t.maxResultSizeChars());
    }

    @Test
    void inputSchema_isEmptyObject() {
        // WHY: McpAuthTool.ts:23 inputSchema = lazySchema(() => z.object({})) — 伪工具无参数
        McpAuthTool t = tool(config("sse"), null);
        JsonNode schema = t.inputSchema();
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").isMissingNode() || schema.path("properties").isEmpty(),
            "空输入 schema（无 properties）");
    }

    @Test
    void checkPermissions_alwaysAllow() {
        // WHY: McpAuthTool.ts:82-84 checkPermissions 返回 { behavior: 'allow' } — 伪工具不设权限门
        McpAuthTool t = tool(config("sse"), null);
        PermissionResult r = t.checkPermissions(MAPPER.createObjectNode(), null);
        assertInstanceOf(PermissionResult.Allow.class, r);
    }

    @Test
    void descriptionAndPrompt_mentionServerAndAuth() {
        // WHY: McpAuthTool.ts:57-60 description = `The ${serverName} MCP server (${location}) is
        // installed but requires authentication...`；:73-78 prompt() 返回同一文本
        McpAuthTool t = tool(config("sse"), null);
        assertTrue(t.description().contains(SERVER), "描述含 server 名");
        assertTrue(t.description().contains("requires authentication"), "描述声明需认证");
        assertEquals(t.description(), t.prompt(), "prompt 与 description 同一文本");
    }

    // ───────────── 3 status 分支（McpAuthTool.ts:85-205）─────────────

    @Test
    void call_claudeaiProxy_returnsUnsupported() throws Exception {
        // WHY: McpAuthTool.ts:89-96 claudeai-proxy 走 MCPRemoteServerMenu 独立认证 → 提示 /mcp
        McpAuthTool t = tool(new McpServerConfig("claudeai-proxy", null, null), null);
        AgentToolResult<?> r = t.execute(call("{}"));
        assertFalse(LlmAgentLoop.isToolErrorData(r.data()), "status=unsupported 仍为成功 tool_result（message 提示模型）");
        assertTrue(message(r).contains("claude.ai MCP connector"), "提示用户运行 /mcp");
    }

    @Test
    void call_nonSseHttpTransport_returnsUnsupported() throws Exception {
        // WHY: McpAuthTool.ts:101-108 仅 sse/http 支持 OAuth，stdio 等其他 transport 提示 /mcp
        McpAuthTool t = tool(new McpServerConfig("stdio", null, null), null);
        AgentToolResult<?> r = t.execute(call("{}"));
        assertFalse(LlmAgentLoop.isToolErrorData(r.data()));
        assertTrue(message(r).contains("stdio"), "提示 transport 不支持 OAuth");
    }

    @Test
    void call_sseAuthUrlFirst_returnsAuthUrlMessage() throws Exception {
        // WHY: McpAuthTool.ts:174-190 race authUrlPromise vs oauthPromise — URL 先到 → status=auth_url + URL
        String authUrl = "http://localhost:4242/authorize?code=abc";
        OAuthFlowStarter starter = (s, c, onUrl, onComplete, opts) -> onUrl.accept(authUrl);
        McpAuthTool t = tool(new McpServerConfig("sse", "http://localhost:4242", null), starter);
        AgentToolResult<?> r = t.execute(call("{}"));
        assertFalse(LlmAgentLoop.isToolErrorData(r.data()));
        assertTrue(message(r).contains(authUrl), "消息包含授权 URL");
        assertTrue(message(r).contains("browser"), "提示用户浏览器授权");
    }

    @Test
    void call_sseSilentCompletion_returnsSilentSuccess() throws Exception {
        // WHY: McpAuthTool.ts:192-197 oauthPromise 先完成（XAA 缓存 token 静默认证）→ status=auth_url 无 URL
        OAuthFlowStarter starter = (s, c, onUrl, onComplete, opts) -> onComplete.accept(null);
        McpAuthTool t = tool(new McpServerConfig("sse", "http://localhost:4242", null), starter);
        AgentToolResult<?> r = t.execute(call("{}"));
        assertFalse(LlmAgentLoop.isToolErrorData(r.data()));
        assertTrue(message(r).contains("completed silently"), "静默认证成功消息");
    }

    @Test
    void call_starterThrows_returnsError() throws Exception {
        // WHY: McpAuthTool.ts:198-204 启动抛错 → catch → status=error 提示 /mcp
        OAuthFlowStarter starter = (s, c, onUrl, onComplete, opts) -> {
            throw new IllegalStateException("boom");
        };
        McpAuthTool t = tool(new McpServerConfig("sse", "http://localhost:4242", null), starter);
        AgentToolResult<?> r = t.execute(call("{}"));
        assertFalse(LlmAgentLoop.isToolErrorData(r.data()));
        assertTrue(message(r).contains("Failed to start OAuth flow"), "error 消息含失败原因");
        assertTrue(message(r).contains("boom"), "error 消息透传异常原因");
    }

    @Test
    void call_oauthFailureSignal_returnsErrorStatus() throws Exception {
        // WHY（IMP-E G2 回归锁定）：CC McpAuthTool.ts:126-180 oauthPromise 失败 reject →
        //   Promise.race reject → catch → error status（:198-205）。Java 旧实现 success=false
        //   不触发 onComplete → completionFuture 永不完成 → anyOf().get() 永久挂起（P0 OPD-E1-Q1）。
        //   G2 修复后 starter 在失败时也触发 onComplete("error:...")，本测试锁定该路径返回
        //   error status 而非挂起（若删回"不触发 onComplete"即在此阻塞 → 回归变红）。
        OAuthFlowStarter starter = (s, c, onUrl, onComplete, opts) ->
            onComplete.accept("error:authorization declined by user");
        McpAuthTool t = tool(new McpServerConfig("sse", "http://localhost:4242", null), starter);
        AgentToolResult<?> r = t.execute(call("{}"));
        assertFalse(LlmAgentLoop.isToolErrorData(r.data()), "status=error 仍为成功 tool_result（message 提示模型）");
        assertTrue(message(r).contains("Failed to start OAuth flow"), "error 消息含失败原因");
        assertTrue(message(r).contains("authorization declined by user"), "error 消息透传 OAuth 失败原因");
        assertTrue(message(r).contains("/mcp"), "提示用户运行 /mcp 手动认证");
    }

    // ───────────── 无 30s 超时（TR-E1-DC-1 · 验收标准 2）─────────────

    /**
     * 无超时意图回归测试 · 锁 CC McpAuthTool.ts:174-197 Promise.race 无超时语义。
     *
     * <p>WHY（验收标准 2）：IMP-E1 移除 `.get(30, SECONDS)`（TR-E1-DC-1），因为浏览器 OAuth
     * 授权常 &gt;30s，旧超时会误报 "Failed to start OAuth flow"。原 15 个测试无一在
     * `.get(30, SECONDS)` 被恢复时变红——本测试补该缺口。
     *
     * <p>判别窗口：31s &gt; 旧 30s 超时。OAuth 挂起（starter 只捕获 authUrl 回调、不完成 future）
     * 期间 call 必须<b>持续阻塞</b>；若 `.get(30, SECONDS)` 被恢复，call 在 30s 处抛
     * TimeoutException → 返回 status=error → isDone=true → 本测试断言失败（回归变红）。
     * authUrl 到达后才返回 auth_url 成功，锁定"等待浏览器授权结束，不提前误报"的意图。
     */
    @Test
    void call_noTimeout_blocksWhileOAuthPendingAndSucceedsOnAuthUrl() throws Exception {
        CountDownLatch starterInvoked = new CountDownLatch(1);
        AtomicReference<Consumer<String>> onAuthUrl = new AtomicReference<>();
        OAuthFlowStarter starter = (s, c, onUrl, onComplete, opts) -> {
            onAuthUrl.set(onUrl);
            starterInvoked.countDown();
        };
        McpAuthTool t = tool(new McpServerConfig("sse", "http://localhost:4242", null), starter);

        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<McpAuthToolResult> callFuture = ex.submit(() -> t.call(Map.of(), null));
        try {
            // 等 OAuth flow 被启动（authUrl 回调已捕获；此后 call 阻塞在 anyOf().get()）
            assertTrue(starterInvoked.await(5, TimeUnit.SECONDS), "OAuth flow 应被启动");
            // 无超时探测窗口：31s > 旧 30s 超时 → 恢复 `.get(30,SECONDS)` 即在此断言变红
            Thread.sleep(31_000);
            assertFalse(callFuture.isDone(),
                "OAuth 挂起 31s 后 call 仍在阻塞等待（无 30s 超时）——若已返回即 .get(30,SECONDS) 回归");
            // authUrl 到达 → call 返回 auth_url 成功（不误报 error）
            onAuthUrl.get().accept("http://localhost:4242/authorize?code=done");
            McpAuthToolResult result = callFuture.get(5, TimeUnit.SECONDS);
            assertEquals(McpAuthTool.STATUS_AUTH_URL, result.data().status());
            assertTrue(result.data().message().contains("http://localhost:4242/authorize?code=done"),
                "消息包含授权 URL");
        } finally {
            ex.shutdownNow();
        }
    }

    // ───────────── mapToToolResultBlockParam（McpAuthTool.ts:207-213）─────────────

    @Test
    void mapToToolResultBlockParam_contentIsMessage() {
        // WHY: McpAuthTool.ts:207-213 { tool_use_id, type:'tool_result', content: data.message }
        McpAuthTool t = tool(config("sse"), null);
        String msg = "Ask the user to open this URL...";
        ToolResult<?> r = new ToolResult<>(msg, null, null, null);
        ToolResultBlockParam block = t.mapToToolResultBlockParam(r, "toolu_1", false);
        assertEquals("toolu_1", block.toolUseId());
        assertEquals("tool_result", block.type());
        assertEquals(msg, block.content(), "content 为 data.message（消息字符串）");
    }

    private static McpServerConfig config(String type) {
        return new McpServerConfig(type, type.equals("sse") || type.equals("http") ? "http://localhost:4242" : null, null);
    }
}

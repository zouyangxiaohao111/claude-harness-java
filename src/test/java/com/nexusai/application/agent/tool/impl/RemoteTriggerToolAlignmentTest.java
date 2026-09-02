package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F2 · RemoteTriggerTool 行为契约对齐 CC 测试（自有触发体系 HTTP mock）。
 *
 * <p><b>WHY (意图验证)</b>: RemoteTriggerTool 从 fail-closed stub（name='remote_trigger'，
 * schema prompt/agent_id/timeout_seconds，恒 error）重构为对齐 CC
 * {@code RemoteTriggerTool.ts} 的工具（name='RemoteTrigger'，schema {action,trigger_id,body}，
 * output {status,json}，5 action 路由到自有触发端点，HTTP 错误态透传，mapToolResult
 * `HTTP {status}\n{json}`）。本测试验证的是<b>行为契约</b>（LLM 看到的 schema / 路由 /
 * 缺参错误 / HTTP 透传），而不是 stub 的"实现存在性"。
 *
 * <p><b>HTTP mock</b>: JDK {@link HttpServer} 临时端口（零新依赖），模拟自有
 * ScheduleController {@code /api/v1/schedules} CRUD + {@code /{id}/run} 端点。
 */
class RemoteTriggerToolAlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private RemoteTriggerTool tool;
    private String baseUrl;
    private AccountOAuthTokenService accountOAuthTokenService;
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicBoolean return401Once = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/schedules", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v1/schedules";
        accountOAuthTokenService = mock(AccountOAuthTokenService.class);
        // 默认注入一个 GitHub 账号 token，使既有路由/透传测试带 Bearer 头通过
        when(accountOAuthTokenService.readLatest("github"))
                .thenReturn(token("github", "alice", "test-access-token"));
        tool = new RemoteTriggerTool(baseUrl, accountOAuthTokenService, "github");
    }

    private static AccountOAuthToken token(String provider, String identity, String accessToken) {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider(provider);
        t.setIdentity(identity);
        t.setAccessToken(accessToken);
        return t;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        lastRequestPath.set(ex.getRequestURI().getPath());
        lastRequestMethod.set(ex.getRequestMethod());
        lastAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
        requestCount.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        int status;
        String body;
        if ("/api/v1/schedules".equals(path) && "GET".equals(method)) {
            if (return401Once.getAndSet(false)) {
                status = 401;
                body = "{\"error\":\"unauthorized\"}";
            } else {
                status = 200;
                body = "[{\"id\":\"sch-1\",\"name\":\"cron:*/5 * * * *\",\"kind\":\"cron\"}]";
            }
        } else if (path.endsWith("/error-500") && "GET".equals(method)) {
            status = 500;
            body = "{\"error\":\"server boom\"}";
        } else if (path.endsWith("/error-400") && "GET".equals(method)) {
            status = 400;
            body = "{\"error\":\"bad request\"}";
        } else if (path.endsWith("/not-found") && "GET".equals(method)) {
            status = 404;
            body = "{\"error\":\"not found\"}";
        } else if (path.matches("/api/v1/schedules/.+") && "GET".equals(method)) {
            status = 200;
            body = "{\"id\":\"sch-1\",\"name\":\"cron:*/5 * * * *\",\"kind\":\"cron\"}";
        } else if ("/api/v1/schedules".equals(path) && "POST".equals(method)) {
            status = 201;
            body = "{\"id\":\"sch-new\",\"name\":\"created\"}";
        } else if (path.matches("/api/v1/schedules/[^/]+") && "POST".equals(method)) {
            status = 200;
            body = "{\"id\":\"sch-1\",\"name\":\"updated\"}";
        } else if (path.endsWith("/run") && "POST".equals(method)) {
            status = 202;
            body = "{\"executed\":true,\"output\":\"ok\"}";
        } else if ("/api/v1/schedules".equals(path) && "DELETE".equals(method)) {
            status = 204;
            body = "";
        } else {
            status = 404;
            body = "{\"error\":\"not found\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static ToolUseBlock block(JsonNode input) {
        return new ToolUseBlock("call_1", "RemoteTrigger", input);
    }

    private static ToolUseBlock blockWith(String action) {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", action);
        return block(in);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> result(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    @Test
    @DisplayName("name()='RemoteTrigger'（CC prompt.ts:1）")
    void nameMatchesCc() {
        assertThat(tool.name()).isEqualTo("RemoteTrigger");
    }

    @Test
    @DisplayName("inputSchema: action enum + trigger_id regex + body record（CC :18-31）")
    void inputSchemaMatchesCc() {
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode action = schema.path("properties").path("action");
        assertThat(action.path("type").asText()).isEqualTo("string");
        assertThat(action.path("enum")).isNotNull();
        String[] enums = new String[action.path("enum").size()];
        for (int i = 0; i < enums.length; i++) {
            enums[i] = action.path("enum").get(i).asText();
        }
        assertThat(enums).containsExactly("list", "get", "create", "update", "run");
        assertThat(schema.path("properties").path("trigger_id").path("pattern").asText())
                .isEqualTo("^[\\w-]+$");
        assertThat(schema.path("properties").path("body").path("type").asText())
                .isEqualTo("object");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("action");
    }

    @Test
    @DisplayName("outputSchema: {status:number, json:string}（CC :35-40）")
    void outputSchemaMatchesCc() {
        JsonNode schema = tool.outputSchema();
        assertThat(schema.path("properties").path("status").path("type").asText())
                .isEqualTo("integer");
        assertThat(schema.path("properties").path("json").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("isReadOnly: list/get 只读，其余 false（CC :66-67）")
    void isReadOnlyByAction() {
        assertThat(tool.isReadOnly(blockWith("list").input())).isTrue();
        assertThat(tool.isReadOnly(blockWith("get").input())).isTrue();
        assertThat(tool.isReadOnly(blockWith("create").input())).isFalse();
        assertThat(tool.isReadOnly(blockWith("update").input())).isFalse();
        assertThat(tool.isReadOnly(blockWith("run").input())).isFalse();
    }

    @Test
    @DisplayName("isConcurrencySafe=true / shouldDefer=true / maxResultSizeChars=100_000（CC :63-64/:50/:49）")
    void ccStaticFlags() {
        assertThat(tool.isConcurrencySafe(null)).isTrue();
        assertThat(tool.shouldDefer(null)).isTrue();
        assertThat(tool.maxResultSizeChars()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("toAutoClassifierInput='RemoteTrigger <action> <trigger_id?>'（CC :69-70）")
    void autoClassifierInput() {
        assertThat(tool.toAutoClassifierInput(blockWith("list").input()))
                .isEqualTo("RemoteTrigger list");
        ObjectNode withId = JSON.createObjectNode();
        withId.put("action", "get");
        withId.put("trigger_id", "sch-1");
        assertThat(tool.toAutoClassifierInput(withId)).isEqualTo("RemoteTrigger get sch-1");
    }

    @Test
    @DisplayName("list → GET 200 透传")
    void listAction() {
        AgentToolResult<?> r = tool.execute(blockWith("list"));
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(result(r).data()).startsWith("HTTP 200\n");
        assertThat(result(r).data()).contains("\"id\":\"sch-1\"");
        assertThat(lastRequestMethod.get()).isEqualTo("GET");
    }

    @Test
    @DisplayName("get → GET {base}/{trigger_id} 200；缺 trigger_id → error（CC :110）")
    void getAction() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "get");
        in.put("trigger_id", "sch-1");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in)).data())).isFalse();
        assertThat(lastRequestPath.get()).endsWith("/sch-1");
        assertThat(lastRequestMethod.get()).isEqualTo("GET");

        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(blockWith("get")).data())).isTrue();
        assertThat(result(tool.execute(blockWith("get"))).data())
                .contains("get requires trigger_id");
    }

    @Test
    @DisplayName("create → POST 201；缺 body → error（CC :115）")
    void createAction() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "create");
        in.putObject("body").put("name", "cron:*/5 * * * *").put("kind", "cron");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in)).data())).isFalse();
        assertThat(result(tool.execute(block(in))).data()).startsWith("HTTP 201\n");
        assertThat(lastRequestMethod.get()).isEqualTo("POST");

        assertThat(result(tool.execute(blockWith("create"))).data())
                .contains("create requires body");
    }

    @Test
    @DisplayName("update → POST 200（CC RemoteTriggerTool.ts:120-126 update=POST base/{trigger_id}）；缺参 → error（CC :121-122）")
    void updateAction() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "update");
        in.put("trigger_id", "sch-1");
        in.putObject("body").put("name", "updated");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in)).data())).isFalse();
        assertThat(result(tool.execute(block(in))).data()).startsWith("HTTP 200\n");
        assertThat(lastRequestMethod.get()).isEqualTo("POST");

        ObjectNode noId = JSON.createObjectNode();
        noId.put("action", "update");
        noId.putObject("body").put("name", "x");
        assertThat(result(tool.execute(block(noId))).data()).contains("update requires trigger_id");

        ObjectNode noBody = JSON.createObjectNode();
        noBody.put("action", "update");
        noBody.put("trigger_id", "sch-1");
        assertThat(result(tool.execute(block(noBody))).data()).contains("update requires body");
    }

    @Test
    @DisplayName("run → POST {base}/{trigger_id}/run 202；缺 trigger_id → error（CC :128）")
    void runAction() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "run");
        in.put("trigger_id", "sch-1");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in)).data())).isFalse();
        assertThat(result(tool.execute(block(in))).data()).startsWith("HTTP 202\n");
        assertThat(lastRequestPath.get()).endsWith("/sch-1/run");
        assertThat(lastRequestMethod.get()).isEqualTo("POST");

        assertThat(result(tool.execute(blockWith("run"))).data())
                .contains("run requires trigger_id");
    }

    @Test
    @DisplayName("trigger_id 不匹配 ^[\\w-]+$ → error（CC :21-24 zod regex）")
    void invalidTriggerIdRejected() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "get");
        in.put("trigger_id", "bad id!");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in)).data())).isTrue();
        assertThat(result(tool.execute(block(in))).data()).contains("does not match");
    }

    @Test
    @DisplayName("HTTP 4xx/5xx 透传不抛（CC :142 validateStatus:()=>true）")
    void httpErrorsPassthrough() {
        ObjectNode in404 = JSON.createObjectNode();
        in404.put("action", "get");
        in404.put("trigger_id", "not-found");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in404)).data())).isFalse();
        assertThat(result(tool.execute(block(in404))).data()).startsWith("HTTP 404\n");

        ObjectNode in500 = JSON.createObjectNode();
        in500.put("action", "get");
        in500.put("trigger_id", "error-500");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in500)).data())).isFalse();
        assertThat(result(tool.execute(block(in500))).data()).startsWith("HTTP 500\n");

        ObjectNode in400 = JSON.createObjectNode();
        in400.put("action", "get");
        in400.put("trigger_id", "error-400");
        assertThat(LlmAgentLoop.isToolErrorData(tool.execute(block(in400)).data())).isFalse();
        assertThat(result(tool.execute(block(in400))).data()).startsWith("HTTP 400\n");
    }

    @Test
    @DisplayName("mapToToolResultBlockParam: {tool_use_id, type:'tool_result', content='HTTP status\\njson'}（CC :152-158）")
    void mapToolResultFormat() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "list");
        AgentToolResult<?> r = tool.execute(block(in));
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(r, "call_1", false);
        assertThat(block.toolUseId()).isEqualTo("call_1");
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(String.valueOf(block.content())).startsWith("HTTP 200\n");
    }

    @Test
    @DisplayName("abort 后执行 → error（CC :141 abort signal）")
    void abortedBeforeSend() {
        AbortController ac = new AbortController();
        ac.abort();
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            List.of(), "", ac);
        AgentToolResult<?> r = tool.execute(blockWith("list"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isTrue();
        assertThat(result(r).data()).contains("aborted");
    }

    @Test
    @DisplayName("无 token → execute 返回 error 含『Not authenticated』（CC :82-84，安全边界：不带 Bearer 头不可调触发接口）")
    void noTokenReturnsError() {
        when(accountOAuthTokenService.readLatest("github")).thenReturn(null);
        AgentToolResult<?> r = tool.execute(blockWith("list"));
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isTrue();
        assertThat(result(r).data()).contains("Not authenticated");
    }

    @Test
    @DisplayName("有 token → 请求带 Authorization: Bearer <token> 头（CC :92-93）")
    void bearerHeaderAttached() {
        AgentToolResult<?> r = tool.execute(blockWith("list"));
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-access-token");
    }

    @Test
    @DisplayName("首请求 401 → 刷新后 token 变化则用新 token 重发一次（CC http.ts withOAuth401Retry 重试一次）")
    void oauth401RetryWithRefreshedToken() {
        // 首读返回 token-1（触发 401），handle401 后重读返回 token-2
        when(accountOAuthTokenService.readLatest("github"))
                .thenReturn(token("github", "alice", "token-1"), token("github", "alice", "token-2"));
        return401Once.set(true);

        AgentToolResult<?> r = tool.execute(blockWith("list"));
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(result(r).data()).startsWith("HTTP 200\n");
        assertThat(requestCount.get()).as("401 后必须用新 token 重发一次（共 2 次请求）").isEqualTo(2);
        assertThat(lastAuthHeader.get()).as("第二次请求必须带新 token").isEqualTo("Bearer token-2");
    }
}

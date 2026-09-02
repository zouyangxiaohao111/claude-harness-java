package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S6 · RemoteTriggerTool OAuth Bearer 头 + 401 自愈接线（WIRE-SF-06 + G-8 定向测试）。
 *
 * <p><b>WHY (规则九 · 意图验证)</b>: ScheduleController 接入 {@code BearerTokenAuthFilter}（S5）后，
 * {@code /api/v1/schedules/**} 零鉴权缺口被堵死——任何请求（含触发工具）都须携带
 * {@code Authorization: Bearer <账号级 token>} 否则 401。RemoteTriggerTool 直连 base-url 调用
 * ScheduleController，<b>若不同步补 Bearer 头则触发工具整体 401</b>（WIRE-SF-06 断裂）。因此本测试
 * 锁定的不是「过滤器存在」而是「触发工具的请求确实带上了与 schedule 鉴权同一账号级 token 源的头」：
 * <ul>
 *   <li>GREEN ①：请求带 {@code Authorization: Bearer <accessToken>}（读自
 *       {@link AccountOAuthTokenService#readLatest(provider)}）；</li>
 *   <li>RED ②：无账号级 token → 拒绝发起请求并报「Not authenticated」错误（CC
 *       RemoteTriggerTool.ts:82-84 {@code getClaudeAIOAuthTokens()?.accessToken} 无 token 抛错）；</li>
 *   <li>GREEN ③：首请求 401 → {@code OAuth401Refresher.handle401} 自愈后 token 已变化 → 用新 token
 *       <b>重发一次</b>（对齐 CC http.ts:133-134 {@code withOAuth401Retry} 无条件重试一次）；</li>
 *   <li>GREEN ④：首请求 401 → 但 GitHub 无 refresh_token（G-8），token 不变 → <b>不重发</b>，
 *       401 结果透传（{@code validateStatus:()=>true} 语义），触发工具报告 401 由上层/用户重新授权。</li>
 * </ul>
 *
 * <p><b>与 {@link RemoteTriggerToolAlignmentTest} 的分工</b>：后者验证 F2 行为契约
 * （schema/5-action 路由/缺参/HTTP 透传），本类专验 <b>OAuth 接线</b>（Bearer 头 + 401 刷新重试 +
 * G-8 无 refresh 语义），两者互补、不重复（本类不含 schema/路由断言）。
 *
 * <p>HTTP mock：JDK {@link HttpServer} 临时端口模拟自有 ScheduleController，捕获
 * {@code Authorization} 头与请求次数（零新依赖，镜像 {@link RemoteTriggerToolAlignmentTest}）。
 */
class RemoteTriggerToolBearerHeaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private RemoteTriggerTool tool;
    private AccountOAuthTokenService accountOAuthTokenService;

    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicBoolean return401 = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/schedules", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api/v1/schedules";
        accountOAuthTokenService = mock(AccountOAuthTokenService.class);
        tool = new RemoteTriggerTool(baseUrl, accountOAuthTokenService, "github");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        lastAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
        requestCount.incrementAndGet();
        // 仅首次请求返回 401（模拟服务端令牌过期），重试后返回 200（刷新后令牌有效）
        boolean should401 = return401.getAndSet(false);
        int status = should401 ? 401 : 200;
        String body = should401 ? "{\"error\":\"unauthorized\"}"
                : "[{\"id\":\"sch-1\",\"name\":\"cron:*/5 * * * *\",\"kind\":\"cron\"}]";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static AccountOAuthToken token(String accessToken) {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider("github");
        t.setIdentity("alice");
        t.setAccessToken(accessToken);
        return t;
    }

    private static ToolUseBlock listBlock() {
        ObjectNode in = JSON.createObjectNode();
        in.put("action", "list");
        return new ToolUseBlock("call_1", "RemoteTrigger", in);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> result(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    @Test
    @DisplayName("GREEN ① 请求带 Authorization: Bearer <account token> 头（WIRE-SF-06 同步补头）")
    void requestCarriesBearerHeader() {
        when(accountOAuthTokenService.readLatest("github")).thenReturn(token("test-access-token"));

        AgentToolResult<?> r = tool.execute(listBlock());

        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(lastAuthHeader.get())
            .as("触发工具请求必须带与 schedule 鉴权同一账号级 token 的 Bearer 头，否则 401（WIRE-SF-06）")
            .isEqualTo("Bearer test-access-token");
    }

    @Test
    @DisplayName("RED ② 无账号级 token → 拒绝请求并报 Not authenticated（CC :82-84，不带 Bearer 头不可调触发接口）")
    void noToken_returnsNotAuthenticated() {
        when(accountOAuthTokenService.readLatest("github")).thenReturn(null);

        AgentToolResult<?> r = tool.execute(listBlock());

        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("无 token 必须拒绝（安全边界：不带 Bearer 头不可调已加鉴权的触发接口）")
            .isTrue();
        assertThat(result(r).data()).contains("Not authenticated");
        assertThat(requestCount.get())
            .as("无 token 时不得发起任何 HTTP 请求")
            .isZero();
    }

    @Test
    @DisplayName("GREEN ③ 401 → 刷新后 token 变化 → 用新 token 重发一次（对齐 CC http.ts withOAuth401Retry 重试一次）")
    void oauth401_retriesOnceWithRefreshedToken() {
        when(accountOAuthTokenService.readLatest("github"))
            .thenReturn(token("token-1"), token("token-2"));
        return401.set(true);

        AgentToolResult<?> r = tool.execute(listBlock());

        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(result(r).data()).startsWith("HTTP 200\n");
        assertThat(requestCount.get())
            .as("401 后必须用新 token 重发一次（共 2 次请求，对齐 CC http.ts:133-134）")
            .isEqualTo(2);
        assertThat(lastAuthHeader.get())
            .as("第二次请求必须带刷新后的新 token")
            .isEqualTo("Bearer token-2");
    }

    @Test
    @DisplayName("GREEN ④ 401 → GitHub 无 refresh_token（G-8）token 不变 → 不重发，401 结果透传")
    void oauth401_noRefreshToken_noRetryPassthrough() {
        // 首读与 401 自愈后重读均返回同一 token（GitHub OAuth App 无 refresh_token，恒不恢复）
        when(accountOAuthTokenService.readLatest("github")).thenReturn(token("token-1"));
        return401.set(true);

        AgentToolResult<?> r = tool.execute(listBlock());

        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("401 透传为成功结果（validateStatus:()=>true，CC :142），非 error")
            .isFalse();
        assertThat(result(r).data())
            .as("GitHub 无 refresh_token 恒不恢复 → 不重发，返回原始 401 结果由上层/用户重新授权（G-8）")
            .startsWith("HTTP 401\n");
        assertThat(requestCount.get())
            .as("token 不变（无 refresh_token）时不得重发（仅 1 次请求）")
            .isEqualTo(1);
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer token-1");
    }
}

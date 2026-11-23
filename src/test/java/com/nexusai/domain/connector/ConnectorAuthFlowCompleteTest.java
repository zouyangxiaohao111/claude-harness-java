package com.nexusai.domain.connector;

import com.nexusai.infra.exception.ConflictException;
import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.model.connector.ConnectorDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 授权回调完成后的编排（{@link ConnectorAuthFlow#completeFlow} 私有方法，反射直调）。
 *
 * <p>覆盖验收要求的「授权成功后 upsert mcp_servers」：token 交换 → ensureServer → markConnected；
 * 换 token 失败 / 同名异 URL 冲突 → markError + poll=ERROR。token endpoint 用本地 HttpServer，
 * 跑真实 {@code OAuthTokenClient}；其余依赖 mock。
 */
class ConnectorAuthFlowCompleteTest {

    private ConnectorCatalog catalog;
    private ConnectorCredentialService credentials;
    private ConnectorCallbackServer callbackServer;
    private ConnectorMcpBinding binding;
    private ConnectorAuthFlow flow;
    private HttpServer tokenServer;

    @BeforeEach
    void setUp() {
        catalog = mock(ConnectorCatalog.class);
        credentials = mock(ConnectorCredentialService.class);
        callbackServer = mock(ConnectorCallbackServer.class);
        binding = mock(ConnectorMcpBinding.class);
        flow = new ConnectorAuthFlow(catalog, credentials, callbackServer, binding);
    }

    @AfterEach
    void tearDown() {
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
    }

    @Test
    @DisplayName("授权成功 → ensureServer（upsert mcp_servers）+ markConnected 落 token")
    void successUpsertsMcpServerAndStoresTokens() throws Exception {
        String endpoint = startTokenServer(200, """
                {"access_token":"new-token","expires_in":3600,"scope":"tasks:read"}
                """);

        invokeComplete("st-ok", pending(endpoint),
                new ConnectorCallbackServer.CallbackResult("code-1", null), null);

        verify(binding).ensureServer(any(ConnectorDefinition.class));
        verify(credentials).markConnected(eq("notion"), eq("oauth2"), eq(endpoint),
                eq("tasks:read"), any(), eq("new-token"), isNull());
        stubDb("connected", null);
        assertThat(flow.poll("notion", "st-ok").state()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("token 交换失败 → markError，不绑定 mcp server，poll=ERROR")
    void tokenExchangeFailure() throws Exception {
        String endpoint = startTokenServer(400, """
                {"error":"invalid_grant"}
                """);

        invokeComplete("st-fail", pending(endpoint),
                new ConnectorCallbackServer.CallbackResult("code-1", null), null);

        verify(binding, never()).ensureServer(any());
        verify(credentials).markError(eq("notion"), eq("oauth2"), anyString());
        stubDb("error", "token 交换失败");
        assertThat(flow.poll("notion", "st-fail").state()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("同名异 URL 冲突（ensureServer 抛 Conflict）→ markError，不 markConnected，poll=ERROR")
    void bindingConflictMarksError() throws Exception {
        String endpoint = startTokenServer(200, """
                {"access_token":"new-token","expires_in":3600}
                """);
        doThrow(new ConflictException("已存在同名 MCP server（notion）且指向其它地址"))
                .when(binding).ensureServer(any());

        invokeComplete("st-conflict", pending(endpoint),
                new ConnectorCallbackServer.CallbackResult("code-1", null), null);

        verify(credentials, never()).markConnected(anyString(), anyString(), anyString(),
                any(), any(), anyString(), any());
        verify(credentials).markError(eq("notion"), eq("oauth2"), anyString());
        stubDb("error", "已存在同名 MCP server（notion）且指向其它地址");
        assertThat(flow.poll("notion", "st-conflict").message()).contains("同名");
    }

    // ── 工具 ──

    /** poll 现在读 DB：为断言提供对账后的行。 */
    private void stubDb(String status, String lastError) {
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("notion");
        c.setAuthType("oauth2");
        c.setStatus(status);
        c.setLastError(lastError);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.reconcileExpired(any())).thenReturn(c);
    }

    private void invokeComplete(String state, ConnectorCallbackServer.PendingFlow pending,
                                ConnectorCallbackServer.CallbackResult result, Throwable error)
            throws Exception {
        Method m = ConnectorAuthFlow.class.getDeclaredMethod("completeFlow",
                String.class, ConnectorDefinition.class,
                ConnectorCallbackServer.PendingFlow.class,
                ConnectorCallbackServer.CallbackResult.class, Throwable.class);
        m.setAccessible(true);
        m.invoke(flow, state, def(), pending, result, error);
    }

    private ConnectorCallbackServer.PendingFlow pending(String tokenEndpoint) {
        return new ConnectorCallbackServer.PendingFlow(
                "notion", "verifier-1", "client-1", null,
                "https://as.example.com/authorize", tokenEndpoint, null,
                "http://127.0.0.1:34118/callback", "tasks:read", false,
                System.currentTimeMillis() + 60_000L);
    }

    private static ConnectorDefinition def() {
        return new ConnectorDefinition(
                "notion", "Notion", "desc", "oauth2", "knowledge", List.of("tasks:read"),
                "https://mcp.notion.com/mcp", "https://mcp.notion.com", "http",
                null, null, null, null, false, false, false, null, null, "notion", "#000000");
    }

    private String startTokenServer(int status, String json) throws Exception {
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", ex -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        tokenServer.start();
        return "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token";
    }
}

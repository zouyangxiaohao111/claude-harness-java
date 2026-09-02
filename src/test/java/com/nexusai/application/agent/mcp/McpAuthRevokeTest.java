package com.nexusai.application.agent.mcp;

import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S03 R2-04 X-5] revokeServerTokens 全链测试 · 对齐 CC auth.ts:467-618（RFC 7009）。
 *
 * <p><b>WHY（意图验证）</b>: revoke 全链此前不存在（无服务端吊销、无 REST logout 入口
 * 依赖）。本测试断言：
 * <ol>
 *   <li>AS URL = discoveryState.authorizationServerUrl ?? serverUrl（auth.ts:482-485）→
 *       metadata 发现 → revocation_endpoint（auth.ts:495-501）</li>
 *   <li>authMethod 取 revocation_endpoint_auth_methods_supported ?? token 端点列表
 *       （auth.ts:505-517）：client_secret_post 时 body 携带 client creds</li>
 *   <li>refresh token 先、access token 后（auth.ts:523-564）</li>
 *   <li>吊销 401 → 清 body client creds 换 Bearer 回退（RFC 6749 §2.3.1，auth.ts:434-450）</li>
 *   <li>恒清本地凭据（auth.ts:575-576）；preserveStepUpState 保留 scope + discovery
 *       （auth.ts:581-617，剥离 legacy metadata）</li>
 * </ol>
 */
class McpAuthRevokeTest {

    private static final String SERVER_URL = "https://mcp.example.com/mcp";
    private static final String AS_URL = "https://as.example.com";
    private static final String REVOCATION_ENDPOINT = "https://as.example.com/revoke";

    private McpOAuthToken tokenData() {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("key-1");
        t.setServerName("srv");
        t.setServerUrl(SERVER_URL);
        t.setAccessToken("at-1");
        t.setRefreshToken("rt-1");
        t.setClientId("cid-1");
        t.setClientSecret("csec-1");
        // discoveryState 持久化 AS URL（XAA 场景 AS 与 MCP URL 异主）
        t.setDiscoveryState("{\"authorizationServerUrl\":\"" + AS_URL + "\"}");
        return t;
    }

    private McpAuth.OAuthHttpClient httpWithRevocation() {
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(eq(AS_URL), anyLong())).thenReturn(new McpAuth.AuthServerMetadata(
            AS_URL + "/authorize", AS_URL + "/token", AS_URL,
            null, null,
            REVOCATION_ENDPOINT, List.of("client_secret_post"), List.of("client_secret_basic")));
        return http;
    }

    @Test
    @DisplayName("[X-5] 全链：AS URL 优先 discoveryState + client_secret_post + refresh 先/access 后 + 恒清本地")
    void revokeServerTokens_fullChain_orderAndLocalCleanup() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(tokenData());
        McpAuth.OAuthHttpClient http = httpWithRevocation();
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null, store, null);

        mcpAuth.revokeServerTokens("srv", "key-1", SERVER_URL, false);

        // ① metadata 发现以 discoveryState AS URL 为入参（非 serverUrl）
        verify(http).fetchAuthServer(eq(AS_URL), anyLong());
        // ② 两次吊销：refresh 先、access 后（InOrder）
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(http, times(2)).revokeToken(endpointCaptor.capture(), paramsCaptor.capture(), headersCaptor.capture());
        // 顺序断言由 captor 参数隐含：get(0)=refresh_token（先）、get(1)=access_token（后）
        assertThat(paramsCaptor.getAllValues().get(0))
            .as("第一次吊销 = refresh_token，client_secret_post 模式 body 携带 client creds")
            .containsEntry("token", "rt-1")
            .containsEntry("token_type_hint", "refresh_token")
            .containsEntry("client_id", "cid-1")
            .containsEntry("client_secret", "csec-1");
        assertThat(paramsCaptor.getAllValues().get(1))
            .as("第二次吊销 = access_token")
            .containsEntry("token", "at-1")
            .containsEntry("token_type_hint", "access_token");
        // ③ 恒清本地凭据（无论服务端结果，auth.ts:575-576）
        verify(store).delete("key-1");
        verify(store).clearClientSecret("key-1");
    }

    @Test
    @DisplayName("[X-5] client_secret_basic（token 端点列表回退）→ Basic 头认证、body 无 client creds")
    void revokeServerTokens_clientSecretBasic_usesBasicHeader() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(tokenData());
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        // 无 revocation_endpoint_auth_methods_supported → 回退 token 端点列表含 basic（auth.ts:505-517）
        when(http.fetchAuthServer(eq(AS_URL), anyLong())).thenReturn(new McpAuth.AuthServerMetadata(
            AS_URL + "/authorize", AS_URL + "/token", AS_URL,
            null, null,
            REVOCATION_ENDPOINT, null, List.of("client_secret_basic")));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null, store, null);

        mcpAuth.revokeServerTokens("srv", "key-1", SERVER_URL, false);

        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(http, times(2)).revokeToken(eq(REVOCATION_ENDPOINT), paramsCaptor.capture(), headersCaptor.capture());
        assertThat(paramsCaptor.getAllValues().get(0))
            .as("basic 模式 body 不得携带 client creds（RFC 6749 §2.3.1 单一认证方法）")
            .doesNotContainKey("client_id")
            .doesNotContainKey("client_secret");
        assertThat(headersCaptor.getAllValues().get(0).get("Authorization"))
            .as("Basic 头 = base64(URL-encoded cid:csec)，auth.ts:416-419")
            .startsWith("Basic ");
    }

    @Test
    @DisplayName("[X-5] 吊销 401 → 清 body client creds 换 Bearer 回退（auth.ts:434-450）")
    void revokeServerTokens_401_fallsBackToBearer() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(tokenData());
        McpAuth.OAuthHttpClient http = httpWithRevocation();
        // 第一次吊销（refresh，post 模式）→ 401；Bearer 回退成功（void mock：doThrow → doNothing 按序）
        org.mockito.Mockito.doThrow(new McpAuth.MCPRefreshFailed(
                McpAuth.MCPRefreshFailureReason.REQUEST_FAILED, "HTTP 401: unauthorized", 401))
            .doNothing()
            .when(http).revokeToken(eq(REVOCATION_ENDPOINT), anyMap(), anyMap());
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null, store, null);

        mcpAuth.revokeServerTokens("srv", "key-1", SERVER_URL, false);

        // refresh：2 次调用（401 → Bearer 回退）；access：1 次（成功）
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(http, times(3)).revokeToken(eq(REVOCATION_ENDPOINT), paramsCaptor.capture(), headersCaptor.capture());
        Map<String, String> retryParams = paramsCaptor.getAllValues().get(1);
        Map<String, String> retryHeaders = headersCaptor.getAllValues().get(1);
        assertThat(retryParams)
            .as("Bearer 回退清 body client creds（RFC 6749 §2.3.1）")
            .doesNotContainKey("client_id")
            .doesNotContainKey("client_secret");
        assertThat(retryHeaders.get("Authorization"))
            .as("Bearer 回退用 access token（auth.ts:448-450）")
            .isEqualTo("Bearer at-1");
        // 恒清本地不因服务端 401 跳过
        verify(store).delete("key-1");
    }


    @Test
    @DisplayName("[X-5] preserveStepUpState → 保留 stepUpScope + 剥离 metadata 的 discoveryState（auth.ts:581-617）")
    void revokeServerTokens_preserveStepUpState_keepsScopeAndDiscovery() {
        McpOAuthToken t = tokenData();
        t.setStepUpScope("admin");
        t.setDiscoveryState("{\"authorizationServerUrl\":\"" + AS_URL + "\","
            + "\"resourceMetadataUrl\":\"https://mcp.example.com/meta\","
            + "\"authorizationServerMetadata\":{\"authorizationEndpoint\":\"/authorize\","
            + "\"tokenEndpoint\":\"/token\",\"issuer\":\"" + AS_URL + "\"}}");
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(t);
        McpAuth.OAuthHttpClient http = httpWithRevocation();
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, r) -> null, null, store, null);

        mcpAuth.revokeServerTokens("srv", "key-1", SERVER_URL, true);

        // 恒清本地（delete）之后 preserve 重存最小记录
        verify(store).delete("key-1");
        ArgumentCaptor<McpOAuthToken> savedCaptor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(store).save(savedCaptor.capture());
        McpOAuthToken preserved = savedCaptor.getValue();
        assertThat(preserved.getStepUpScope()).as("stepUpScope 必须保留（下次重授权复用）").isEqualTo("admin");
        assertThat(preserved.getAccessToken()).as("access token 不得保留").isEqualTo("");
        assertThat(preserved.getExpiresAt()).isEqualTo(0L);
        assertThat(preserved.getDiscoveryState())
            .as("discoveryState 仅保留 URL（剥离 legacy metadata，#30337 auth.ts:602-609）")
            .contains("authorizationServerUrl")
            .contains("resourceMetadataUrl")
            .doesNotContain("authorizationServerMetadata");
    }

    @Test
    @DisplayName("[X-5] 无 revocation_endpoint → log 不支持吊销，仍清本地；无 token → 仅清本地")
    void revokeServerTokens_noRevocationEndpoint_stillClearsLocal() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(tokenData());
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(eq(AS_URL), anyLong())).thenReturn(new McpAuth.AuthServerMetadata(
            AS_URL + "/authorize", AS_URL + "/token", AS_URL));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t2) -> null, null, store, null);

        mcpAuth.revokeServerTokens("srv", "key-1", SERVER_URL, false);

        // 无 revocation_endpoint → 不发起吊销请求（auth.ts:499-501 log-and-continue）
        verify(http, never()).revokeToken(anyString(), anyMap(), anyMap());
        // 恒清本地
        verify(store).delete("key-1");
        verify(store).clearClientSecret("key-1");
    }
}

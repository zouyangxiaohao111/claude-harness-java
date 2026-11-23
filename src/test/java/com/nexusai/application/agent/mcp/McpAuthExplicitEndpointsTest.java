package com.nexusai.application.agent.mcp;

import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [2026-11-23] 授权流的两条硬语义：
 * ① 显式 authorization/token 端点 → 跳过 discovery（表单可覆盖自动发现）；
 * ② 流程开头只清 token，不删整行——预注册 clientId 必须跨授权保留（否则永远 DCR）。
 */
class McpAuthExplicitEndpointsTest {

    @Test
    @DisplayName("显式端点 → 直接构造 metadata，不发任何 discovery 请求")
    void explicitEndpointsSkipDiscovery() {
        McpAuth.OAuthHttpClient httpClient = mock(McpAuth.OAuthHttpClient.class);
        McpAuth auth = new McpAuth(null, httpClient, u -> { }, new LoopbackCallbackHandler(),
                null, mock(McpOAuthTokenService.class), new OauthPort());
        McpAuth.OAuthServerConfig cfg = new McpAuth.OAuthServerConfig(
                "http", "https://mcp.example.com/mcp", Map.of(), null, "cid", null,
                "read write", "https://as.example.com/authorize", "https://as.example.com/token");

        McpAuth.AuthServerMetadata metadata = auth.resolveFlowMetadata(cfg);

        assertThat(metadata.authorizationEndpoint()).isEqualTo("https://as.example.com/authorize");
        assertThat(metadata.tokenEndpoint()).isEqualTo("https://as.example.com/token");
        assertThat(metadata.scope()).isEqualTo("read write");
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("流程开头 invalidateCredentials(tokens)：清 token 但保留 clientId/clientSecret")
    void flowStartKeepsClientInfo() {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        McpOAuthToken row = new McpOAuthToken();
        row.setServerKey("key-1");
        row.setServerName("srv");
        row.setClientId("cid-1");
        row.setClientSecret("secret-1");
        row.setAccessToken("old-access");
        row.setRefreshToken("old-refresh");
        row.setExpiresAt(123L);
        when(tokenStore.read("key-1")).thenReturn(row);

        McpAuth auth = new McpAuth(null, mock(McpAuth.OAuthHttpClient.class), u -> { },
                new LoopbackCallbackHandler(), null, tokenStore, new OauthPort());
        auth.invalidateCredentials("key-1", "tokens");

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(tokenStore).save(captor.capture());
        McpOAuthToken saved = captor.getValue();
        assertThat(saved.getClientId()).isEqualTo("cid-1");
        assertThat(saved.getClientSecret()).isEqualTo("secret-1");
        assertThat(saved.getAccessToken()).isEmpty();
        assertThat(saved.getRefreshToken()).isNull();
        assertThat(saved.getExpiresAt()).isEqualTo(0L);
        // 不复用整行 delete（那会把 client 一起删掉）
        verify(tokenStore, never()).delete(anyString());
    }
}

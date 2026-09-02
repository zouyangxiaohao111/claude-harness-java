package com.nexusai.application.agent.mcp;

import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S03 R2-04 X-2] invalidateCredentials 精细 scope 测试 · 对齐 CC auth.ts:1960-1995
 * （scope: all/client/tokens/verifier/discovery）。
 *
 * <p><b>WHY（意图验证）</b>: 精细失效此前不存在（_doRefresh invalid_grant 路径无清凭据能力）。
 * 断言各 scope 的落库语义：
 * <ul>
 *   <li>all → delete 整行（auth.ts:1972-1974）</li>
 *   <li>client → clientId/clientSecret 清空 + 预配置 client_secret 二级表清理（auth.ts:1975-1978）</li>
 *   <li>tokens → accessToken=''/refreshToken=null/expiresAt=0（auth.ts:1979-1983）</li>
 *   <li>discovery → discoveryState/stepUpScope 清空（auth.ts:1987-1990）</li>
 *   <li>verifier → no-op（Java code_verifier 为方法局部变量，auth.ts:1984-1986 内存态）</li>
 * </ul>
 */
class McpAuthInvalidateCredentialsTest {

    private McpOAuthToken record() {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("key-1");
        t.setServerName("srv");
        t.setAccessToken("at");
        t.setRefreshToken("rt");
        t.setExpiresAt(1234L);
        t.setClientId("cid");
        t.setClientSecret("csec");
        t.setStepUpScope("admin");
        t.setDiscoveryState("{\"authorizationServerUrl\":\"https://as.example.com\"}");
        return t;
    }

    private McpAuth authWith(McpOAuthTokenService store) {
        // NullHttpClient 为 private；mock OAuthHttpClient 保持测试隔离（本测试不触网络）
        return new McpAuth(null, org.mockito.Mockito.mock(McpAuth.OAuthHttpClient.class),
            u -> {}, (s, t) -> null, null, store, null);
    }

    @Test
    @DisplayName("[X-2] scope=all → delete 整行（auth.ts:1972-1974）")
    void invalidate_all_deletesRow() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(record());
        McpAuth mcpAuth = authWith(store);

        mcpAuth.invalidateCredentials("key-1", "all");

        verify(store).delete("key-1");
        verify(store, never()).save(org.mockito.ArgumentMatchers.any(McpOAuthToken.class));
    }

    @Test
    @DisplayName("[X-2] scope=client → clientId/clientSecret 清空 + 预配置 client_secret 二级表清理（auth.ts:1975-1978）")
    void invalidate_client_clearsClientCreds() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(record());
        McpAuth mcpAuth = authWith(store);

        mcpAuth.invalidateCredentials("key-1", "client");

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(store).save(captor.capture());
        McpOAuthToken saved = captor.getValue();
        assertThat(saved.getClientId()).as("clientId 必须清空").isNull();
        assertThat(saved.getClientSecret()).as("clientSecret 必须清空").isNull();
        assertThat(saved.getAccessToken()).as("client scope 不动 token").isEqualTo("at");
        verify(store).clearClientSecret("key-1");
    }

    @Test
    @DisplayName("[X-2] scope=tokens → accessToken=''/refreshToken=null/expiresAt=0（auth.ts:1979-1983）")
    void invalidate_tokens_clearsTokens() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(record());
        McpAuth mcpAuth = authWith(store);

        mcpAuth.invalidateCredentials("key-1", "tokens");

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(store).save(captor.capture());
        McpOAuthToken saved = captor.getValue();
        assertThat(saved.getAccessToken()).isEqualTo("");
        assertThat(saved.getRefreshToken()).as("refreshToken 必须清空（旧 token 不永留 DB，I-5）").isNull();
        assertThat(saved.getExpiresAt()).isEqualTo(0L);
        assertThat(saved.getClientId()).as("tokens scope 不动 client").isEqualTo("cid");
        verify(store, never()).clearClientSecret(anyString());
    }

    @Test
    @DisplayName("[X-2] scope=discovery → discoveryState/stepUpScope 清空（auth.ts:1987-1990）")
    void invalidate_discovery_clearsDiscovery() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(record());
        McpAuth mcpAuth = authWith(store);

        mcpAuth.invalidateCredentials("key-1", "discovery");

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(store).save(captor.capture());
        McpOAuthToken saved = captor.getValue();
        assertThat(saved.getDiscoveryState()).isNull();
        assertThat(saved.getStepUpScope()).isNull();
        assertThat(saved.getAccessToken()).as("discovery scope 不动 token").isEqualTo("at");
    }

    @Test
    @DisplayName("[X-2] scope=verifier → no-op（Java code_verifier 为方法局部变量，auth.ts:1984-1986）")
    void invalidate_verifier_noOp() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(record());
        McpAuth mcpAuth = authWith(store);

        mcpAuth.invalidateCredentials("key-1", "verifier");

        verify(store, never()).save(org.mockito.ArgumentMatchers.any(McpOAuthToken.class));
        verify(store, never()).delete(anyString());
    }

    @Test
    @DisplayName("[X-2] 无记录 → 静默跳过（不抛、不写）")
    void invalidate_noRecord_skipsSilently() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(null);
        McpAuth mcpAuth = authWith(store);

        mcpAuth.invalidateCredentials("key-1", "tokens");

        verify(store, never()).save(org.mockito.ArgumentMatchers.any(McpOAuthToken.class));
        verify(store, never()).delete(anyString());
    }
}

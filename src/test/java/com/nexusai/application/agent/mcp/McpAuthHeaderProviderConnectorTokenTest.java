package com.nexusai.application.agent.mcp;

import com.nexusai.domain.connector.ConnectorTokenProvider;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpAuthHeaderProvider} 的连接器凭据优先级（[2026-11-23] P0 运行时 Bearer 接线）。
 *
 * <p>锁死「连接器接管」的三分语义：
 * ① 有连接器 token → 用它，且<b>不读</b> mcp_oauth_tokens；
 * ② 无连接器记录 → 回退旧 mcp_oauth_tokens（不破坏现有 MCP OAuth）；
 * ③ 连接器接管但需重新授权 → 返回 null（不发 Authorization → 401），<b>不回退</b>旧源；
 * ④ 401 刷新走连接器的 forceRefresh。
 */
class McpAuthHeaderProviderConnectorTokenTest {

    private McpOAuthTokenService tokenStore;
    private McpTransport.TransportConfig config;

    @BeforeEach
    void setUp() {
        tokenStore = mock(McpOAuthTokenService.class);
        config = new McpTransport.TransportConfig(
                "https://mcp.notion.com/mcp", List.of(), Map.of(), null, "notion", "http");
    }

    @Test
    @DisplayName("连接器 token 优先，且不读旧 mcp_oauth_tokens")
    void connectorTokenWins() {
        when(tokenStore.read(anyString())).thenReturn(legacyToken());
        McpAuthHeaderProvider provider = new McpAuthHeaderProvider(
                tokenStore, stub(ConnectorTokenProvider.Result.connected("connector-token"), null));

        assertThat(provider.resolveAccessToken(config)).isEqualTo("connector-token");
        verify(tokenStore, never()).read(anyString());
    }

    @Test
    @DisplayName("无连接器记录 → 回退旧 mcp_oauth_tokens（现有 MCP OAuth 不受影响）")
    void fallsBackToLegacyWhenNotManaged() {
        when(tokenStore.read(anyString())).thenReturn(legacyToken());
        McpAuthHeaderProvider provider = new McpAuthHeaderProvider(
                tokenStore, stub(ConnectorTokenProvider.Result.notManaged(), null));

        assertThat(provider.resolveAccessToken(config)).isEqualTo("legacy-token");
        verify(tokenStore).read(anyString());
    }

    @Test
    @DisplayName("需要重新授权 → 返回 null，且不回退旧源（避免发错 token）")
    void reauthDoesNotFallBack() {
        when(tokenStore.read(anyString())).thenReturn(legacyToken());
        McpAuthHeaderProvider provider = new McpAuthHeaderProvider(
                tokenStore, stub(ConnectorTokenProvider.Result.reauthRequired("reauth_required"), null));

        assertThat(provider.resolveAccessToken(config)).isNull();
        verify(tokenStore, never()).read(anyString());
    }

    @Test
    @DisplayName("401 且 token 与失败值相同 → 调用连接器 forceRefresh")
    void refreshDelegatesToConnector() {
        McpAuthHeaderProvider provider = new McpAuthHeaderProvider(
                tokenStore, stub(ConnectorTokenProvider.Result.connected("old-token"), "new-token"));

        assertThat(provider.refreshAndGetAccessToken(config, "old-token")).isEqualTo("new-token");
    }

    @Test
    @DisplayName("连接器已换新 token（本地未过期）→ 直接返回，不强制刷新")
    void refreshPrefersResolvedNewToken() {
        McpAuthHeaderProvider provider = new McpAuthHeaderProvider(
                tokenStore, stub(ConnectorTokenProvider.Result.connected("fresh"), "forced"));

        assertThat(provider.refreshAndGetAccessToken(config, "stale")).isEqualTo("fresh");
    }

    // ── 工具 ──

    private static McpOAuthToken legacyToken() {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("legacy-key");
        t.setServerName("notion");
        t.setServerUrl("https://mcp.notion.com/mcp");
        t.setAccessToken("legacy-token");
        t.setExpiresAt(System.currentTimeMillis() + 600_000L);
        return t;
    }

    private static ConnectorTokenProvider stub(ConnectorTokenProvider.Result result, String forced) {
        return new ConnectorTokenProvider() {
            @Override
            public Result resolve(String connectorKey) {
                return result;
            }

            @Override
            public String forceRefresh(String connectorKey) {
                return forced;
            }
        };
    }
}

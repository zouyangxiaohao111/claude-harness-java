package com.nexusai.domain.mcp;

import com.nexusai.application.agent.mcp.McpOAuth;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpOAuthRequest;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [2026-11-23] create 的预注册 clientId 必须进 {@code mcp_oauth_tokens}
 * （对比报告 ④-3-2：旧实现只存 secret，clientId 只在 env 镜像 ⇒ 授权流清凭据后永远 DCR）。
 */
class McpServerServiceOAuthClientTest {

    @Test
    @DisplayName("create：clientId + clientSecret 一起写入 mcp_oauth_tokens（并保留二级 secret 回退）")
    void createPersistsClientInfo() throws Exception {
        McpServerService service = new McpServerService();
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        Field f = McpServerService.class.getDeclaredField("mcpOAuthTokenService");
        f.setAccessible(true);
        f.set(service, tokenStore);
        when(tokenStore.read(anyString())).thenReturn(null);

        McpCreateRequest req = new McpCreateRequest(
                "srv", null, null, null, true, "http",
                "https://mcp.example.com/mcp", Map.of(),
                new McpOAuthRequest("cid-1", null, null, null, "read write", null, null),
                "secret-1", "user", true);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "http");
        config.put("url", "https://mcp.example.com/mcp");
        config.put("oauth", Map.of("clientId", "cid-1", "scopes", "read write"));

        Method m = McpServerService.class.getDeclaredMethod(
                "saveClientSecretIfNeeded", McpCreateRequest.class, Map.class);
        m.setAccessible(true);
        m.invoke(service, req, config);

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(tokenStore).save(captor.capture());
        McpOAuthToken saved = captor.getValue();
        assertThat(saved.getClientId()).isEqualTo("cid-1");
        assertThat(saved.getClientSecret()).isEqualTo("secret-1");
        assertThat(saved.getServerName()).isEqualTo("srv");
        assertThat(saved.getServerUrl()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(saved.getServerKey()).startsWith("srv|");
        verify(tokenStore).saveClientSecret(eq(saved.getServerKey()), eq("secret-1"));
    }

    @Test
    @DisplayName("buildOAuth：scopes / authorizationEndpoint / tokenEndpoint 都进 env 镜像")
    void buildOAuthCarriesNewFields() throws Exception {
        McpServerService service = new McpServerService();
        Method m = McpServerService.class.getDeclaredMethod("buildOAuth", McpOAuthRequest.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> oauth = (Map<String, Object>) m.invoke(service,
                new McpOAuthRequest(null, null, null, null, "read write",
                        "https://as/auth", "https://as/token"));

        assertThat(oauth).containsEntry("scopes", "read write");
        assertThat(oauth).containsEntry("authorizationEndpoint", "https://as/auth");
        assertThat(oauth).containsEntry("tokenEndpoint", "https://as/token");
        assertThat(McpOAuth.ENV_OAUTH_MIRROR_KEY).isNotBlank();
    }
}

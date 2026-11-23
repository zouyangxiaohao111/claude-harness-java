package com.nexusai.domain.connector;

import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.model.connector.dto.ConnectorListResponse;
import com.nexusai.model.connector.dto.ManualCredentialRequest;
import com.nexusai.model.connector.dto.RevokedResponse;
import com.nexusai.model.connector.dto.StatusResponse;
import com.nexusai.model.connector.dto.TestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 连接器市场 service 层（{@link ConnectorOAuthService}）：列表过滤 / 状态与 missingScopes /
 * 手动凭据（加密 + 绑定）/ 测试连接委托 / 断开。
 */
class ConnectorOAuthServiceTest {

    private ConnectorCatalog catalog;
    private ConnectorCredentialService credentials;
    private ConnectorAuthFlow authFlow;
    private ConnectorMcpBinding binding;
    private McpConnectorProbe probe;
    private ConnectorOAuthService service;

    @BeforeEach
    void setUp() {
        catalog = mock(ConnectorCatalog.class);
        credentials = mock(ConnectorCredentialService.class);
        authFlow = mock(ConnectorAuthFlow.class);
        binding = mock(ConnectorMcpBinding.class);
        probe = mock(McpConnectorProbe.class);
        // 默认放行：在途过期对账返回原行（各用例按需覆盖）
        when(credentials.reconcileExpired(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ConnectorOAuthService(catalog, credentials, authFlow, binding, probe);
    }

    @Test
    @DisplayName("list：默认只返回 visible；includeHidden=true 才带 qcc/ardot")
    void listFiltersHidden() {
        ConnectorDefinition notion = def("notion", false, List.of());
        ConnectorDefinition qcc = def("qcc", true, List.of("mcp:tools"));
        when(catalog.visible()).thenReturn(List.of(notion));
        when(catalog.all()).thenReturn(List.of(notion, qcc));
        when(credentials.findAll()).thenReturn(List.of());

        ConnectorListResponse visible = service.list(false);
        assertThat(visible.connectors()).hasSize(1);
        assertThat(visible.connectors().get(0).key()).isEqualTo("notion");
        // [2026-11-23] catalog 的 icon/color 原样下发（前端品牌图标/配色）
        assertThat(visible.connectors().get(0).icon()).isEqualTo("notion");
        assertThat(visible.connectors().get(0).color()).isEqualTo("#000000");
        assertThat(visible.connectors().get(0).docUrl()).isEqualTo("https://doc/notion");
        assertThat(visible.connectors().get(0).authHint()).isEqualTo("提示notion");

        ConnectorListResponse all = service.list(true);
        assertThat(all.connectors()).hasSize(2);
        assertThat(all.connectors().get(1).hidden()).isTrue();
    }

    @Test
    @DisplayName("hasCredentials 三态：无行=false / 只有 client=false / 有 token=true")
    void listHasCredentialsThreeStates() {
        ConnectorDefinition notion = def("notion", false, List.of());
        when(catalog.visible()).thenReturn(List.of(notion));

        when(credentials.findAll()).thenReturn(List.of());
        assertThat(service.list(false).connectors().get(0).hasCredentials()).isFalse();

        ConnectorCredential clientOnly = new ConnectorCredential();
        clientOnly.setConnectorKey("notion");
        clientOnly.setAuthType("oauth2");
        clientOnly.setStatus("pending");
        clientOnly.setClientId("dcr-client");
        clientOnly.setClientSecretEncrypted("enc-secret");
        when(credentials.findAll()).thenReturn(List.of(clientOnly));
        assertThat(service.list(false).connectors().get(0).hasCredentials()).isFalse();

        ConnectorCredential withToken = cred("notion", System.currentTimeMillis() + 600_000L);
        when(credentials.findAll()).thenReturn(List.of(withToken));
        assertThat(service.list(false).connectors().get(0).hasCredentials()).isTrue();
    }

    @Test
    @DisplayName("list：reauth_required 原样透出（connected=false），供前端显示「需重新授权」")
    void listPassesReauthStatus() {
        ConnectorDefinition notion = def("notion", false, List.of());
        ConnectorCredential c = cred("notion", System.currentTimeMillis() + 600_000L);
        c.setStatus("reauth_required");
        when(catalog.visible()).thenReturn(List.of(notion));
        when(credentials.findAll()).thenReturn(List.of(c));

        ConnectorListResponse r = service.list(false);

        assertThat(r.connectors()).hasSize(1);
        assertThat(r.connectors().get(0).status()).isEqualTo("reauth_required");
        assertThat(r.connectors().get(0).connected()).isFalse();
    }

    @Test
    @DisplayName("status：connected + missingScopes = 期望 scope 减已授予")
    void statusComputesMissingScopes() {
        ConnectorDefinition notion = def("notion", false, List.of("tasks:read", "tasks:write"));
        ConnectorCredential c = cred("notion", System.currentTimeMillis() + 600_000L);
        c.setScope("tasks:read");
        when(catalog.require("notion")).thenReturn(notion);
        when(credentials.find("notion")).thenReturn(Optional.of(c));

        StatusResponse r = service.status("notion");

        assertThat(r.connected()).isTrue();
        assertThat(r.missingScopes()).containsExactly("tasks:write");
    }

    @Test
    @DisplayName("status：已过期 → 触发一次刷新；刷新失败后 connected=false")
    void statusRefreshesExpiredToken() {
        ConnectorDefinition notion = def("notion", false, List.of());
        ConnectorCredential c = cred("notion", System.currentTimeMillis() - 1000L);
        c.setRefreshTokenEncrypted("enc-refresh");
        when(catalog.require("notion")).thenReturn(notion);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(authFlow.tryRefresh(c, notion)).thenReturn(false);

        StatusResponse r = service.status("notion");

        verify(authFlow).tryRefresh(c, notion);
        assertThat(r.connected()).isFalse();
    }

    @Test
    @DisplayName("手动凭据：clientSource=manual，token 走加密；oauth2 有 token 时绑定 mcp server")
    void saveManualCredentials() {
        ConnectorDefinition notion = def("notion", false, List.of());
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("notion");
        c.setAuthType("oauth2");
        when(catalog.require("notion")).thenReturn(notion);
        when(credentials.ensure("notion", "oauth2")).thenReturn(c);
        when(credentials.encrypt(anyString())).thenReturn("enc");
        when(credentials.find("notion")).thenReturn(Optional.of(c));

        StatusResponse r = service.saveManualCredentials("notion",
                new ManualCredentialRequest("oauth2",
                        Map.of("clientId", "c1", "clientSecret", "s1", "accessToken", "t1")));

        verify(credentials).save(c);
        verify(binding).ensureServer(notion);
        assertThat(c.getClientSource()).isEqualTo("manual");
        assertThat(c.getAccessTokenEncrypted()).isEqualTo("enc");
        assertThat(r.connected()).isTrue();
    }

    @Test
    @DisplayName("test：把「有没有密文 token」交给 probe（probeLevel 原样返回）")
    void testDelegatesToProbe() {
        ConnectorDefinition notion = def("notion", false, List.of());
        ConnectorCredential c = cred("notion", System.currentTimeMillis() + 600_000L);
        when(catalog.require("notion")).thenReturn(notion);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.decrypt(c.getAccessTokenEncrypted())).thenReturn("tok");
        when(probe.probe(notion, Map.of(), true)).thenReturn(
                new TestResponse(true, "ok", 5L, "mcp_initialize", List.of()));

        TestResponse r = service.test("notion");

        assertThat(r.probeLevel()).isEqualTo("mcp_initialize");
        verify(probe).probe(notion, Map.of(), true);
    }

    @Test
    @DisplayName("revoke：删凭据 + 删连接器对应的 mcp server")
    void revoke() {
        when(catalog.require("notion")).thenReturn(def("notion", false, List.of()));

        RevokedResponse r = service.revoke("notion");

        assertThat(r.revoked()).isTrue();
        verify(binding).removeServer("notion");
        verify(credentials).delete("notion");
    }

    @Test
    @DisplayName("unavailable：test 不发 MCP 握手、manual credentials 直接拒绝（不产生授权/绑定）")
    void unavailableTierGuarded() {
        ConnectorDefinition news = tierDef("tencent-news", "apiKey", "gateway",
                "unavailable", "需要 Octop 网关适配器");
        when(catalog.require("tencent-news")).thenReturn(news);

        TestResponse r = service.test("tencent-news");
        assertThat(r.ok()).isFalse();
        assertThat(r.probeLevel()).isEqualTo("none");
        assertThat(r.message()).contains("网关");
        verifyNoInteractions(probe);

        assertThatThrownBy(() -> service.saveManualCredentials("tencent-news",
                new ManualCredentialRequest("apiKey", Map.of("apiKey", "k"))))
                .isInstanceOf(com.nexusai.infra.exception.ValidationException.class);
        verify(credentials, never()).save(any());
    }

    @Test
    @DisplayName("credentialOnly：test 不握手（none）；手动凭据只加密落库、不绑 mcp、不显示已连接")
    void credentialOnlyStoresCredentialOnly() {
        ConnectorDefinition docs = tierDef("tencent-docs", "personalToken", "remote",
                "credentialOnly", null);
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("tencent-docs");
        c.setAuthType("personalToken");
        when(catalog.require("tencent-docs")).thenReturn(docs);
        when(credentials.find("tencent-docs")).thenReturn(Optional.empty());

        TestResponse probeResult = service.test("tencent-docs");
        assertThat(probeResult.probeLevel()).isEqualTo("none");
        assertThat(probeResult.message()).contains("仅支持保存凭据");
        verifyNoInteractions(probe);

        when(credentials.ensure("tencent-docs", "personalToken")).thenReturn(c);
        when(credentials.encrypt(anyString())).thenReturn("enc");
        when(credentials.find("tencent-docs")).thenReturn(Optional.of(c));
        service.saveManualCredentials("tencent-docs",
                new ManualCredentialRequest("personalToken", Map.of("accessToken", "tok")));

        verify(credentials).save(c);
        verify(binding, never()).ensureServer(any());
        assertThat(c.getStatus()).isEqualTo("unconnected");
        assertThat(c.getLastError()).contains("凭据已保存");
        assertThat(c.getAccessTokenEncrypted()).isNull();
        assertThat(c.getExtraEncrypted()).isEqualTo("enc");
    }

    @Test
    @DisplayName("bearer：token 加密存 access 列并绑 mcp server（运行时由 Bearer provider 注入）")
    void bearerModeStoresAccessToken() {
        ConnectorDefinition docs = modeDef("tencent-docs", "personalToken", "bearer",
                "https://docs.qq.com/openapi/mcp", null, Map.of(), "accessToken");
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("tencent-docs");
        c.setAuthType("personalToken");
        when(catalog.require("tencent-docs")).thenReturn(docs);
        when(credentials.ensure("tencent-docs", "personalToken")).thenReturn(c);
        when(credentials.encrypt(anyString())).thenReturn("enc");
        when(credentials.find("tencent-docs")).thenReturn(Optional.of(c));

        service.saveManualCredentials("tencent-docs",
                new ManualCredentialRequest("personalToken", Map.of("accessToken", "tok-1")));

        verify(binding).ensureServer(eq(docs), any());
        verify(credentials).save(c);
        assertThat(c.getAccessTokenEncrypted()).isEqualTo("enc");
        assertThat(c.getStatus()).isEqualTo("connected");
    }

    @Test
    @DisplayName("query：token 只进整包密文（不发 Bearer），按模板绑定并置 connected")
    void queryModeStoresExtraOnly() {
        ConnectorDefinition baidu = modeDef("baidu-map", "apiKey", "query",
                null, "https://mcp.map.baidu.com/mcp?ak={ak}", Map.of(), "ak");
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("baidu-map");
        c.setAuthType("apiKey");
        when(catalog.require("baidu-map")).thenReturn(baidu);
        when(credentials.ensure("baidu-map", "apiKey")).thenReturn(c);
        when(credentials.encrypt(anyString())).thenReturn("enc");
        when(credentials.find("baidu-map")).thenReturn(Optional.of(c));

        service.saveManualCredentials("baidu-map",
                new ManualCredentialRequest("apiKey", Map.of("ak", "KEY-1")));

        verify(binding).ensureServer(eq(baidu), any());
        assertThat(c.getAccessTokenEncrypted()).isNull();
        assertThat(c.getExtraEncrypted()).isEqualTo("enc");
        assertThat(c.getStatus()).isEqualTo("connected");
    }

    @Test
    @DisplayName("query 的 /test：解密密文得到占位值并交给 probe")
    void queryTestPassesDecryptedValues() {
        ConnectorDefinition baidu = modeDef("baidu-map", "apiKey", "query",
                null, "https://mcp.map.baidu.com/mcp?ak={ak}", Map.of(), "ak");
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("baidu-map");
        c.setAuthType("apiKey");
        c.setExtraEncrypted("enc-extra");
        when(catalog.require("baidu-map")).thenReturn(baidu);
        when(credentials.find("baidu-map")).thenReturn(Optional.of(c));
        when(credentials.decrypt("enc-extra")).thenReturn("{\"ak\":\"KEY-1\"}");
        when(probe.probe(eq(baidu), eq(Map.of("ak", "KEY-1")), eq(true)))
                .thenReturn(new TestResponse(true, "ok", 3L, "mcp_initialize", List.of()));

        TestResponse r = service.test("baidu-map");

        assertThat(r.probeLevel()).isEqualTo("mcp_initialize");
        verify(probe).probe(eq(baidu), eq(Map.of("ak", "KEY-1")), eq(true));
    }

    // ── 工具 ──

    /** 官方 MCP 模板 def（canonical 30 参）。 */
    private static ConnectorDefinition modeDef(String key, String authType, String authMode,
                                               String mcpUrl, String template,
                                               Map<String, String> headers, String tokenField) {
        return new ConnectorDefinition(
                key, key, "desc", authType, "knowledge", List.of(),
                mcpUrl, null, "http", null, null, null, null,
                false, false, false, "https://doc/" + key, "提示", key, "#000000",
                "available", "remote", "supported", null, List.of(),
                true, authMode, template, headers, tokenField);
    }

    /** 显式分档的 def（24 参 canonical）。 */
    private static ConnectorDefinition tierDef(String key, String authType, String mcpMode,
                                               String tier, String reason) {
        return new ConnectorDefinition(
                key, key, "desc", authType, "knowledge", List.of(),
                null, null, "http", null, null, null, null,
                false, false, false, null, null, key, "#000000",
                "available", mcpMode, tier, reason);
    }

    private static ConnectorCredential cred(String key, long expiresAt) {
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey(key);
        c.setAuthType("oauth2");
        c.setStatus("connected");
        c.setAccessTokenEncrypted("enc-access");
        c.setExpiresAt(expiresAt);
        return c;
    }

    private static ConnectorDefinition def(String key, boolean hidden, List<String> scopes) {
        return new ConnectorDefinition(
                key, key, "desc", "oauth2", "knowledge", scopes,
                "https://mcp.example.com/mcp/" + key, "https://mcp.example.com", "http",
                null, null, null, null, false, hidden, false,
                "https://doc/" + key, "提示" + key, key, "#000000");
    }
}

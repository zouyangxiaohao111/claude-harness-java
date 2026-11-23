package com.nexusai.domain.connector;

import com.sun.net.httpserver.HttpServer;
import com.nexusai.model.connector.ConnectorCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接器加密凭据 → Bearer 供给（{@link ConnectorCredentialTokenProvider}）。
 *
 * <p>必须成立的运行时语义：①无记录→不接管（回退旧源）；②未过期→直接返回；③过期→刷新一次；
 * ④刷新失败/已标记 reauth_required→managed+null（传输层不发 token → 401 语义）。
 * 刷新用本地 HttpServer 当 token endpoint，跑真实 {@code OAuthTokenClient}。
 */
class ConnectorCredentialTokenProviderTest {

    private ConnectorCredentialService credentials;
    private ConnectorCredentialTokenProvider provider;
    private HttpServer tokenServer;
    private final AtomicInteger tokenCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        credentials = mock(ConnectorCredentialService.class);
        provider = new ConnectorCredentialTokenProvider(credentials);
    }

    @AfterEach
    void tearDown() {
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
    }

    @Test
    @DisplayName("无连接器记录 → connectorManaged=false（回退旧源）")
    void noRowNotManaged() {
        when(credentials.find("notion")).thenReturn(Optional.empty());
        ConnectorTokenProvider.Result r = provider.resolve("notion");
        assertThat(r.connectorManaged()).isFalse();
        assertThat(r.accessToken()).isNull();
    }

    @Test
    @DisplayName("未过期 → 解密返回现有 token，不触发刷新")
    void validTokenReturned() {
        ConnectorCredential c = credential("enc-access", null,
                System.currentTimeMillis() + 600_000L, "http://127.0.0.1:1/token");
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.decrypt("enc-access")).thenReturn("tok-live");

        ConnectorTokenProvider.Result r = provider.resolve("notion");
        assertThat(r.usable()).isTrue();
        assertThat(r.accessToken()).isEqualTo("tok-live");
        verify(credentials, never()).updateTokens(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("过期 → refresh_token 刷新一次并回写新 token")
    void expiredRefreshesOnce() throws Exception {
        String endpoint = startTokenServer(200, """
                {"access_token":"new-token","expires_in":3600,"scope":"tasks:read"}
                """);
        ConnectorCredential c = credential("enc-access", "enc-refresh",
                System.currentTimeMillis() - 1000L, endpoint);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.decrypt("enc-refresh")).thenReturn("refresh-1");
        when(credentials.decrypt(null)).thenReturn(null);

        ConnectorTokenProvider.Result r = provider.resolve("notion");

        assertThat(r.usable()).isTrue();
        assertThat(r.accessToken()).isEqualTo("new-token");
        assertThat(tokenCalls.get()).isEqualTo(1);
        verify(credentials).updateTokens(eq(c), eq("new-token"), isNull(), any(), eq("tasks:read"));
        verify(credentials, never()).markReauthRequired(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("刷新失败 → 标记 reauth_required，返回 managed+null（不回退旧源）")
    void refreshFailureMarksReauth() throws Exception {
        String endpoint = startTokenServer(400, """
                {"error":"invalid_grant","error_description":"expired"}
                """);
        ConnectorCredential c = credential("enc-access", "enc-refresh",
                System.currentTimeMillis() - 1000L, endpoint);
        when(credentials.find("notion")).thenReturn(Optional.of(c));
        when(credentials.decrypt("enc-refresh")).thenReturn("refresh-1");
        when(credentials.decrypt(null)).thenReturn(null);

        ConnectorTokenProvider.Result r = provider.resolve("notion");

        assertThat(r.connectorManaged()).isTrue();
        assertThat(r.accessToken()).isNull();
        assertThat(r.status()).isEqualTo("reauth_required");
        verify(credentials).markReauthRequired(eq("notion"), eq("oauth2"), anyString());
    }

    @Test
    @DisplayName("已是 reauth_required → 即使有旧 token 也返回 null（避免继续发过期 token）")
    void reauthStatusShortCircuits() {
        ConnectorCredential c = credential("enc-access", "enc-refresh",
                System.currentTimeMillis() + 600_000L, "http://127.0.0.1:1/token");
        c.setStatus("reauth_required");
        when(credentials.find("notion")).thenReturn(Optional.of(c));

        ConnectorTokenProvider.Result r = provider.resolve("notion");
        assertThat(r.connectorManaged()).isTrue();
        assertThat(r.accessToken()).isNull();
        verify(credentials, never()).decrypt(anyString());
    }

    // ── 工具 ──

    private static ConnectorCredential credential(String accessEnc, String refreshEnc,
                                                  Long expiresAt, String tokenEndpoint) {
        ConnectorCredential c = new ConnectorCredential();
        c.setConnectorKey("notion");
        c.setAuthType("oauth2");
        c.setStatus("connected");
        c.setAccessTokenEncrypted(accessEnc);
        c.setRefreshTokenEncrypted(refreshEnc);
        c.setClientId("client-1");
        c.setTokenEndpoint(tokenEndpoint);
        c.setExpiresAt(expiresAt);
        c.setRedirectUri("http://127.0.0.1:1/callback");
        return c;
    }

    private String startTokenServer(int status, String json) throws Exception {
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
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

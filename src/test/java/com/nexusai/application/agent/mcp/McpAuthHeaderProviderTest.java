package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 · McpAuthHeaderProvider Bearer token 解析（Q-01 接线）行为验证。
 *
 * <p><b>WHY (规则九)</b>: CC {@code ClaudeAuthProvider.tokens()}（auth.ts:1540-1700）是 MCP
 * SDK 每次请求前取 Bearer token 的唯一入口——Java 侧此前无此逻辑（transport 不附加
 * Authorization）。本测试锁定 CC tokens() 语义不变量：
 * <ol>
 *   <li>有未过期 token → 返回 accessToken（SDK 附加 Bearer）</li>
 *   <li>无 token → null（不发 Authorization 头，server 401 → needs-auth）</li>
 *   <li>过期且无 refreshToken → null（auth.ts:1581-1584 "expired without refresh token → undefined"）</li>
 *   <li>临期（≤300s）且有 refreshToken → 尝试主动刷新，失败回退现存 token
 *       （auth.ts:1586-1591 "refreshing before expiry… return current tokens (may be expired if
 *       refresh failed)"）</li>
 *   <li>401 刷新（handleOAuth401Error 强制刷新）失败 → null（无法恢复 → 调用方 401 降级）</li>
 * </ol>
 */
class McpAuthHeaderProviderTest {

    /** [S6 OAuth-R5] 刷新锁文件写入 nexusai 自有根（NexusaiPaths.getAppConfigHomeDir，McpAuth.java:924）；
     *  测试隔离到临时目录名（唯一 appName），避免污染真实 ~/.nexusai（Java FileLock 无法在纯内存跑）。 */
    @TempDir
    static Path lockDir;

    @BeforeAll
    static void lockDirOverride() {
        NexusaiPaths.setAppNameOverride("nexusai-test-" + lockDir.getFileName());
    }

    @AfterAll
    static void lockDirOverrideReset() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    private McpOAuthTokenService tokenStore;
    private McpAuthHeaderProvider provider;

    @BeforeEach
    void setUp() {
        tokenStore = mock(McpOAuthTokenService.class);
        provider = new McpAuthHeaderProvider(tokenStore);
    }

    /** type=http 的远程 config（headers 承载于 env，对齐 McpServerService remote upsert）。 */
    private McpTransport.TransportConfig cfg(String url) {
        return new McpTransport.TransportConfig(url, List.of(), Map.of(), null, "srv", "http");
    }

    private McpOAuthToken token(String accessToken, String refreshToken, long expiresAt) {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("srv|hash");
        t.setServerName("srv");
        t.setServerUrl("http://mcp.example.com");
        t.setAccessToken(accessToken);
        t.setRefreshToken(refreshToken);
        t.setExpiresAt(expiresAt);
        t.setClientId("cid");
        return t;
    }

    @Test
    @DisplayName("未过期 token → 返回 accessToken（transport 附加 Bearer）")
    void resolveAccessToken_returnsToken_whenNotExpired() {
        McpOAuthToken t = token("at-1", "rt-1", System.currentTimeMillis() + 3600_000L);
        when(tokenStore.read(anyString())).thenReturn(t);

        assertThat(provider.resolveAccessToken(cfg("http://mcp.example.com"))).isEqualTo("at-1");
    }

    @Test
    @DisplayName("无 token → null（不发 Authorization 头，server 401 → needs-auth）")
    void resolveAccessToken_returnsNull_whenNoToken() {
        when(tokenStore.read(anyString())).thenReturn(null);

        assertThat(provider.resolveAccessToken(cfg("http://mcp.example.com"))).isNull();
    }

    @Test
    @DisplayName("过期且无 refreshToken → null（CC tokens() expired without refresh token → undefined）")
    void resolveAccessToken_expiredWithoutRefresh_returnsNull() {
        McpOAuthToken t = token("at-exp", null, System.currentTimeMillis() - 1000L);
        when(tokenStore.read(anyString())).thenReturn(t);

        assertThat(provider.resolveAccessToken(cfg("http://mcp.example.com"))).isNull();
    }

    @Test
    @DisplayName("临期（≤300s）且有 refreshToken → 主动刷新失败回退现存 token（CC tokens() 语义）")
    void resolveAccessToken_expiringWithRefresh_fallsBackToCurrentOnRefreshFailure() {
        // 主动刷新走真实 DefaultOAuthHttpClient → 127.0.0.1:1 连接拒绝 → 失败回退现存 token
        McpOAuthToken t = token("at-exp", "rt-1", System.currentTimeMillis() + 100_000L);
        when(tokenStore.read(anyString())).thenReturn(t);

        assertThat(provider.resolveAccessToken(cfg("http://127.0.0.1:1"))).isEqualTo("at-exp");
    }

    @Test
    @DisplayName("401 强制刷新失败 → null（OAuth401Refresher.handle401 false → 无法恢复）")
    void refreshAndGetAccessToken_returnsNull_whenForceRefreshFails() {
        McpOAuthToken t = token("at-1", "rt-1", System.currentTimeMillis() + 3600_000L);
        when(tokenStore.read(anyString())).thenReturn(t);

        // failedAccessToken 与 DB 一致 → 触发强制刷新；127.0.0.1:1 无 AS → 刷新失败 → null
        assertThat(provider.refreshAndGetAccessToken(cfg("http://127.0.0.1:1"), "at-1")).isNull();
    }

    // ───────────── [OAuth-R1] step-up 检测流（403 + insufficient_scope → needsStepUp）─────────────

    @Test
    @DisplayName("[OAuth-R1] markStepUpPending 持久化 stepUpScope 到 token 存储")
    void markStepUpPending_persistsStepUpScope() {
        McpOAuthToken t = token("at-1", "rt-1", System.currentTimeMillis() + 3600_000L);
        when(tokenStore.read(anyString())).thenReturn(t);

        provider.markStepUpPending(cfg("http://mcp.example.com"), "read write");

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(tokenStore).save(captor.capture());
        assertThat(captor.getValue().getStepUpScope())
            .as("step-up scope 应写入 token 存储（供后续 OAuth 重授权复用）")
            .isEqualTo("read write");
        assertThat(captor.getValue().getServerKey()).isNotBlank();
    }

    @Test
    @DisplayName("[OAuth-R1] WWW-Authenticate scope 提取：带引号/不带引号（CC auth.ts:1365 regex）")
    void extractScopeFromWwwAuthenticate_parsesQuotedAndUnquoted() {
        // RFC 6750 §3 允许带引号（多 scope 空格分隔）与不带引号（单 scope）
        assertThat(McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(
            "Bearer scope=\"read write\", error=\"insufficient_scope\"")).isEqualTo("read write");
        assertThat(McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(
            "Bearer error=\"insufficient_scope\", scope=\"read\"")).isEqualTo("read");
        assertThat(McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(
            "Bearer scope=read, error=\"insufficient_scope\"")).isEqualTo("read");
        // 无 scope 参数 → null（CC match 无结果，auth.ts:1367 scope 为 undefined 不标记）
        assertThat(McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(
            "Bearer realm=\"x\", error=\"insufficient_scope\"")).isNull();
        assertThat(McpAuthHeaderProvider.extractScopeFromWwwAuthenticate(null)).isNull();
    }

    @Test
    @DisplayName("[OAuth-R1] step-up pending 且 scope 未覆盖 → 跳过主动刷新返回现存 token（RFC 6749 §6，token 端点不被访问）")
    void resolveAccessToken_stepUpPending_skipsProactiveRefresh() throws Exception {
        AtomicInteger tokenHits = new AtomicInteger();
        int port = freePort();
        HttpServer server = newRefreshServer(port, tokenHits);
        try {
            // 临期 + 有 refreshToken + stepUpScope=write（当前 scope=read 未覆盖）→ needsStepUp
            McpOAuthToken t = token("at-1", "rt-1", System.currentTimeMillis() + 100_000L);
            t.setScope("read");
            t.setStepUpScope("write");
            when(tokenStore.read(anyString())).thenReturn(t);

            assertThat(provider.resolveAccessToken(cfg("http://127.0.0.1:" + port + "/mcp")))
                .isEqualTo("at-1");
            assertThat(tokenHits.get())
                .as("step-up pending 时不得访问 token 端点（跳过无效刷新，CC auth.ts:1650）")
                .isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[OAuth-R1] 对照：无 step-up → 临期 token 主动刷新成功返回新 token（token 端点被访问）")
    void resolveAccessToken_nonStepUp_refreshesAgainstServer() throws Exception {
        AtomicInteger tokenHits = new AtomicInteger();
        int port = freePort();
        HttpServer server = newRefreshServer(port, tokenHits);
        try {
            McpOAuthToken t = token("at-1", "rt-1", System.currentTimeMillis() + 100_000L);
            t.setScope("read");
            when(tokenStore.read(anyString())).thenReturn(t);

            assertThat(provider.resolveAccessToken(cfg("http://127.0.0.1:" + port + "/mcp")))
                .isEqualTo("at-new");
            assertThat(tokenHits.get()).as("无 step-up 应走主动刷新").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[OAuth-R1] 401 但 step-up pending → refreshAndGetAccessToken 跳过刷新返回 null（token 端点不被访问）")
    void refreshAndGetAccessToken_stepUpPending_skipsRefresh() throws Exception {
        AtomicInteger tokenHits = new AtomicInteger();
        int port = freePort();
        HttpServer server = newRefreshServer(port, tokenHits);
        try {
            McpOAuthToken t = token("at-1", "rt-1", System.currentTimeMillis() + 100_000L);
            t.setScope("read");
            t.setStepUpScope("write");
            when(tokenStore.read(anyString())).thenReturn(t);

            assertThat(provider.refreshAndGetAccessToken(
                cfg("http://127.0.0.1:" + port + "/mcp"), "at-1")).isNull();
            assertThat(tokenHits.get())
                .as("step-up pending 时不得访问 token 端点（刷新无法提升 scope，RFC 6749 §6）")
                .isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[OAuth-R1] 对照：无 step-up → 401 强制刷新成功返回新 token（token 端点被访问）")
    void refreshAndGetAccessToken_nonStepUp_forceRefreshHitsServer() throws Exception {
        AtomicInteger tokenHits = new AtomicInteger();
        int port = freePort();
        HttpServer server = newRefreshServer(port, tokenHits);
        try {
            McpOAuthToken t1 = token("at-1", "rt-1", System.currentTimeMillis() + 100_000L);
            t1.setScope("read");
            McpOAuthToken t3 = token("at-new", "rt-new", System.currentTimeMillis() + 3600_000L);
            t3.setScope("read");
            // 读取顺序：①needsStepUp 门（t1）②handle401 readTokens（t1，强制刷新）③刷新后回读（t3）
            when(tokenStore.read(anyString())).thenReturn(t1, t1, t3);

            assertThat(provider.refreshAndGetAccessToken(
                cfg("http://127.0.0.1:" + port + "/mcp"), "at-1")).isEqualTo("at-new");
            assertThat(tokenHits.get()).as("无 step-up 应走 401 强制刷新").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /**
     * 真实 OAuth AS 服务器：PRM → AS metadata → token 端点（POST 返回 at-new）。
     * 用于证明 step-up pending 时跳过刷新（token 端点不被访问）vs 非 step-up 正常刷新。
     */
    private static HttpServer newRefreshServer(int port, AtomicInteger tokenHits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            byte[] body;
            switch (ex.getRequestURI().getPath()) {
                case "/.well-known/oauth-protected-resource" -> body = (
                    "{\"authorization_servers\":[\"http://127.0.0.1:" + port + "/as\"]}")
                    .getBytes(StandardCharsets.UTF_8);
                case "/as/.well-known/oauth-authorization-server" -> body = (
                    "{\"issuer\":\"http://127.0.0.1:" + port + "/as\","
                    + "\"authorization_endpoint\":\"http://127.0.0.1:" + port + "/authorize\","
                    + "\"token_endpoint\":\"http://127.0.0.1:" + port + "/token\"}")
                    .getBytes(StandardCharsets.UTF_8);
                case "/token" -> {
                    if (ex.getRequestMethod().equals("POST")) {
                        ex.getRequestBody().readAllBytes();
                        tokenHits.incrementAndGet();
                        body = ("{\"access_token\":\"at-new\",\"token_type\":\"Bearer\","
                            + "\"expires_in\":3600,\"refresh_token\":\"rt-new\",\"scope\":\"write\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    } else {
                        body = new byte[0];
                    }
                }
                default -> body = new byte[0];
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        return server;
    }
}

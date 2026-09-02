package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3 · McpAuth.performMCPOAuthFlow 编排（Q-01 核心）行为验证。
 *
 * <p><b>WHY (规则九)</b>: CC performMCPOAuthFlow（auth.ts:847-1342）是 OAuth 授权码流的编排入口。
 * Java 侧此前无此编排——discoverAuthServer/refreshAuthorization 有接口但无"启动回调监听 → 换 code →
 * 持久化"的串联。本测试锁定 7 步编排不变量：
 * PKCE verifier/challenge、授权 URL 参数、回调等待 + state 校验、token 交换带 code_verifier、
 * token 持久化、超时/CSRF 归因、clientId 缺失防护。
 *
 * <p>mock 场景用 Mockito 隔离网络；LoopbackCallbackHandler 与 DefaultOAuthHttpClient 用真实
 * JDK HttpServer 走端到端（loopback GET 回调 + token 端点表单 POST）。
 */
class McpAuthPerformOAuthFlowTest {

    private static final String SERVER = "srv";
    private static final String SERVER_URL = "http://mcp.example.com";
    private static final String AS_ISSUER = "https://as.example.com";

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

    /** mock OAuthHttpClient：PRM → AS metadata → 固定 tokens。 */
    private McpAuth.OAuthHttpClient mockHttp() {
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(SERVER_URL), anyLong()))
            .thenReturn(new McpAuth.ProtectedResourceMetadata(SERVER_URL, AS_ISSUER));
        when(http.fetchAuthServer(eq(AS_ISSUER), anyLong()))
            .thenReturn(new McpAuth.AuthServerMetadata(
                AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER));
        when(http.exchangeCodeForTokens(eq(AS_ISSUER + "/token"), anyMap()))
            .thenReturn(new McpAuth.Tokens("at-1", "rt-1",
                System.currentTimeMillis() + 3600_000L, "read"));
        return http;
    }

    /** 内存 SecureStorage（可断言写入内容）。 */
    private McpAuth.SecureStorage memoryStorage(AtomicReference<Map<String, Object>> store) {
        return new McpAuth.SecureStorage() {
            public Map<String, Object> read() { return new LinkedHashMap<>(store.get()); }
            public void write(Map<String, Object> d) { store.set(new LinkedHashMap<>(d)); }
        };
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    // ───────────── 主链：成功路径（mock 回调 + mock HTTP）─────────────

    @Test
    @DisplayName("成功路径：7 步编排串联，token 持久化，授权 URL 先通知 UI 再开浏览器")
    void happyPath_persistsTokensAndDeliversAuthUrl() throws Exception {
        AtomicReference<Map<String, Object>> store = new AtomicReference<>(new LinkedHashMap<>());
        AtomicReference<String> openedUrl = new AtomicReference<>();
        AtomicReference<String> notifiedUrl = new AtomicReference<>();
        McpAuth.OAuthHttpClient http = mockHttp();

        McpAuth.CallbackHandler cb = mock(McpAuth.CallbackHandler.class);
        when(cb.waitForAuthorizationCode(anyString(), eq(McpAuth.OAUTH_BROWSER_TIMEOUT_MS)))
            .thenReturn("auth-code-123");

        McpAuth mcpAuth = new McpAuth(memoryStorage(store), http,
            openedUrl::set, cb, null);
        McpAuth.OAuthServerConfig config = new McpAuth.OAuthServerConfig(
            "sse", SERVER_URL, Map.of(), null, "preconfig-client", null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(
            SERVER, config, notifiedUrl::set, false);

        assertThat(result.success()).as("编排应成功").isTrue();
        assertThat(result.tokens()).isNotNull();
        assertThat(result.errorMessage()).isNull();

        // ⑥ token 持久化到存储（SecureStorage 回退路径）
        assertThat(store.get().get("accessToken")).isEqualTo("at-1");
        assertThat(store.get().get("refreshToken")).isEqualTo("rt-1");
        assertThat(store.get().get("serverKey")).isEqualTo(
            McpOAuth.getServerKey(SERVER, "sse", SERVER_URL, Map.of()));

        // ② 授权 URL 先通知 UI，再浏览器打开，两者一致
        assertThat(notifiedUrl.get()).isNotNull().isEqualTo(openedUrl.get());
        assertThat(notifiedUrl.get()).contains("response_type=code")
            .contains("client_id=preconfig-client")
            .contains("code_challenge_method=S256");

        // ⑤ token 交换参数：code_verifier 由编排生成（RFC 7636 86 字符），grant/code/redirect 齐备
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(http).exchangeCodeForTokens(eq(AS_ISSUER + "/token"), params.capture());
        Map<String, String> p = params.getValue();
        assertThat(p).containsEntry("grant_type", "authorization_code")
            .containsEntry("code", "auth-code-123")
            .containsEntry("client_id", "preconfig-client");
        assertThat(p.get("code_verifier")).matches("[A-Za-z0-9\\-._~]{86}");
        assertThat(p.get("redirect_uri")).startsWith("http://localhost:");
    }

    @Test
    @DisplayName("[OAuth-R1] 持久化 stepUpScope → 授权 URL 携带更高 scope（CC cachedStepUpScope 复用，auth.ts:903-935）")
    void happyPath_withCachedStepUpScope_appendsScopeToAuthUrl() throws Exception {
        AtomicReference<Map<String, Object>> store = new AtomicReference<>(new LinkedHashMap<>());
        // 传输层 403+insufficient_scope 时经 markStepUpPending 持久化的 stepUpScope
        store.get().put("stepUpScope", "read write");
        AtomicReference<String> notifiedUrl = new AtomicReference<>();
        McpAuth.OAuthHttpClient http = mockHttp();

        McpAuth.CallbackHandler cb = mock(McpAuth.CallbackHandler.class);
        when(cb.waitForAuthorizationCode(anyString(), eq(McpAuth.OAUTH_BROWSER_TIMEOUT_MS)))
            .thenReturn("auth-code-123");

        McpAuth mcpAuth = new McpAuth(memoryStorage(store), http, u -> {}, cb, null);
        McpAuth.OAuthServerConfig config = new McpAuth.OAuthServerConfig(
            "sse", SERVER_URL, Map.of(), null, "preconfig-client", null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(
            SERVER, config, notifiedUrl::set, false);

        assertThat(result.success()).as("step-up 重授权应成功").isTrue();
        // ② 授权 URL 携带持久化的更高 scope（CC wwwAuthParams.scope = cachedStepUpScope，
        // auth.ts:932；RFC 3986 空格 → %20）
        assertThat(notifiedUrl.get()).contains("scope=read%20write");
    }

    @Test
    @DisplayName("[S4 OAuth-R3] AS metadata scope → 授权 URL 携带（无 step-up 缓存时回退 getScopeFromMetadata 产物，auth.ts:1427-1434）")
    void happyPath_metadataScope_passthroughToAuthUrl() throws Exception {
        AtomicReference<Map<String, Object>> store = new AtomicReference<>(new LinkedHashMap<>());
        AtomicReference<String> notifiedUrl = new AtomicReference<>();
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(SERVER_URL), anyLong()))
            .thenReturn(new McpAuth.ProtectedResourceMetadata(SERVER_URL, AS_ISSUER));
        // AS metadata 声明 scope（CC getScopeFromMetadata 产物：
        // scope/default_scope/scopes_supported.join(' ')，auth.ts:2445-2465）
        when(http.fetchAuthServer(eq(AS_ISSUER), anyLong()))
            .thenReturn(new McpAuth.AuthServerMetadata(
                AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER,
                AS_ISSUER + "/register", "read write"));
        when(http.exchangeCodeForTokens(eq(AS_ISSUER + "/token"), anyMap()))
            .thenReturn(new McpAuth.Tokens("at-s", "rt-s",
                System.currentTimeMillis() + 3600_000L, "read write"));

        McpAuth.CallbackHandler cb = mock(McpAuth.CallbackHandler.class);
        when(cb.waitForAuthorizationCode(anyString(), eq(McpAuth.OAUTH_BROWSER_TIMEOUT_MS)))
            .thenReturn("auth-code-scope");

        McpAuth mcpAuth = new McpAuth(memoryStorage(store), http, u -> {}, cb, null);
        McpAuth.OAuthServerConfig config = new McpAuth.OAuthServerConfig(
            "sse", SERVER_URL, Map.of(), null, "preconfig-client", null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(SERVER, config, notifiedUrl::set, false);

        // WHY (规则九)：CC 首次授权（无 step-up 缓存）时授权 URL scope 来自
        // clientMetadata.scope = getScopeFromMetadata(metadata)（auth.ts:1427-1434）——
        // 若 Java 只透传 cachedStepUpScope，metadata 声明的 scope 会在首次授权 URL 丢失（scope=null），
        // 与 CC 行为偏离。本测试锁定该回退链。RFC 3986 空格 → %20。
        assertThat(result.success()).as("首次授权应成功").isTrue();
        assertThat(notifiedUrl.get()).contains("scope=read%20write");
    }

    // ───────────── [OAuth-R4] metadata 发现失败非致命（走备用路径 / 可恢复错误）─────────────

    @Test
    @DisplayName("[OAuth-R4] PRM 链失败 → 备用路径 path-aware RFC 8414 发现成功（CC fetchAuthServerMetadata auth.ts:281-310）")
    void discoverAuthServer_prmChainFails_fallsBackToPathAwareDiscovery() {
        // 带路径分量的资源 URL（CC auth.ts:302-310 pathname !== '/' 才走备用路径）
        String pathUrl = SERVER_URL + "/api/mcp";
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(pathUrl), anyLong())).thenReturn(null); // PRM 失败
        // 备用路径：对资源 URL 直接做 path-aware RFC 8414（{url}/.well-known/... 探测）
        when(http.fetchAuthServer(eq(pathUrl), anyLong()))
            .thenReturn(new McpAuth.AuthServerMetadata(
                AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null);

        McpAuth.AuthServerMetadata as = mcpAuth.discoverAuthServer(pathUrl);

        // WHY (规则九)：CC RFC 9728 链失败 log + fall through 到 path-aware 备用路径
        // （auth.ts:292-300），不抛 METADATA_DISCOVERY_FAILED 硬终止——若 Java 在 PRM 失败
        // 时直接抛错，带路径分量的 legacy server 无法被发现，认证被误终止。
        assertThat(as).isNotNull();
        assertThat(as.authorizationEndpoint()).isEqualTo(AS_ISSUER + "/authorize");
        verify(http).fetchAuthServer(eq(pathUrl), anyLong());
    }

    @Test
    @DisplayName("[OAuth-R4] RFC 9728 链 AS 失败 → 备用路径 path-aware 发现（CC auth.ts:292-300 fall through）")
    void discoverAuthServer_asFetchFails_fallsBackToPathAwareDiscovery() {
        String pathUrl = SERVER_URL + "/mcp/api";
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        // PRM 成功（authorizationServer=AS_ISSUER），但 AS 发现失败
        when(http.fetchProtectedResource(eq(pathUrl), anyLong()))
            .thenReturn(new McpAuth.ProtectedResourceMetadata(pathUrl, AS_ISSUER));
        when(http.fetchAuthServer(eq(AS_ISSUER), anyLong())).thenReturn(null);
        // 备用路径对资源 URL 直接 RFC 8414 成功
        when(http.fetchAuthServer(eq(pathUrl), anyLong()))
            .thenReturn(new McpAuth.AuthServerMetadata(
                AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null);

        McpAuth.AuthServerMetadata as = mcpAuth.discoverAuthServer(pathUrl);

        assertThat(as).isNotNull();
        assertThat(as.tokenEndpoint()).isEqualTo(AS_ISSUER + "/token");
        // AS 失败必须 fall through 到备用路径，而非直接硬终止
        verify(http).fetchAuthServer(eq(AS_ISSUER), anyLong());
        verify(http).fetchAuthServer(eq(pathUrl), anyLong());
    }

    @Test
    @DisplayName("[OAuth-R4] 全部发现失败（根 URL 无备用路径）→ 返回 null 而非抛 METADATA_DISCOVERY_FAILED（CC auth.ts:304-306 返回 undefined）")
    void discoverAuthServer_allDiscoveryFails_returnsNullNotThrow() {
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(SERVER_URL), anyLong())).thenReturn(null);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(null);
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null);

        McpAuth.AuthServerMetadata as = mcpAuth.discoverAuthServer(SERVER_URL);

        // WHY (规则九)：CC fetchAuthServerMetadata 全链失败返回 undefined（auth.ts:304-306），
        // 由调用方按 CC 语义降级（refresh → metadata_discovery_failed → null / performMCPOAuthFlow
        // → sdk_auth_failed）。若 Java 仍抛 METADATA_DISCOVERY_FAILED 硬终止，调用方无法走降级路径。
        assertThat(as).isNull();
        verify(http).fetchProtectedResource(eq(SERVER_URL), anyLong());
        // 根 URL：不触发 path-aware 备用路径（CC auth.ts:305 pathname === '/' → return undefined）
        verify(http, never()).fetchAuthServer(eq(SERVER_URL), anyLong());
    }

    @Test
    @DisplayName("[OAuth-R4] performMCPOAuthFlow 发现失败 → 可恢复错误 AuthResult(SDK_AUTH_FAILED)，不抛 MCPRefreshFailed（CC auth.ts:978-999 best-effort + :1288-1289 sdk_auth_failed）")
    void performOAuthFlow_discoveryFails_returnsSdkAuthFailed() {
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(SERVER_URL), anyLong())).thenReturn(null);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(null);
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> "code", null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(SERVER,
            new McpAuth.OAuthServerConfig("sse", SERVER_URL, Map.of(), null, "cid", null),
            url -> {}, true);

        // WHY (规则九)：CC 顶层 metadata 发现失败只是 best-effort（log + continue，
        // auth.ts:978-999）；Java 无 SDK 二次发现，等价降级 = 可恢复错误 AuthResult
        //（reason=sdk_auth_failed）。若仍抛 MCPRefreshFailed，编排调用方（McpToolPool/
        // McpAuthHeaderProvider）拿不到稳定 reason，认证被硬终止。
        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).isEqualTo(McpAuth.MCPOAuthFlowErrorReason.SDK_AUTH_FAILED);
        assertThat(result.errorMessage()).contains("Failed to discover OAuth metadata");
    }

    @Test
    @DisplayName("[OAuth-R4] refreshServerToken 发现失败 → 返回 null 而非抛 MCPRefreshFailed（CC _doRefresh metadata_discovery_failed → undefined，auth.ts:2250-2254）")
    void refreshServerToken_discoveryFails_returnsNullNotThrow() {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        McpOAuthToken token = new McpOAuthToken();
        token.setRefreshToken("rt-1");
        token.setClientId("cid");
        when(tokenStore.read(anyString())).thenReturn(token);
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(SERVER_URL), anyLong())).thenReturn(null);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(null);
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t) -> null, null, tokenStore, null);

        McpAuth.Tokens tokens = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        // WHY (规则九)：CC 刷新路径 metadata 发现失败 emit metadata_discovery_failed + 返回
        // undefined（auth.ts:2250-2254），不抛硬错——调用方（McpAuthHeaderProvider.resolveAccessToken
        // /forceRefresh）把 null 视为刷新失败降级（返回现存 token / false → 401 → re-auth）。
        assertThat(tokens).isNull();
        verify(http, never()).refreshTokens(anyString(), anyMap());
    }

    // ───────────── [S6 OAuth-R5] discoveryState 持久化 + 刷新 metadata 复用 ─────────────

    @Test
    @DisplayName("[S6 OAuth-R5] saveDiscoveryState 持久化 discoveryState JSON 到 DB（对齐 CC saveDiscoveryState auth.ts:1997-2035）")
    void saveDiscoveryState_persistsToDb() {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        when(tokenStore.read(anyString())).thenReturn(null);
        McpAuth mcpAuth = new McpAuth(null, mockHttp(), u -> {}, (s, t) -> null, null, tokenStore, null);
        McpAuth.AuthServerMetadata metadata = new McpAuth.AuthServerMetadata(
            AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER,
            AS_ISSUER + "/register", "read write");

        mcpAuth.saveDiscoveryState("key-1", SERVER, SERVER_URL, null, metadata);

        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(tokenStore).save(captor.capture());
        McpOAuthToken saved = captor.getValue();
        // WHY (规则九)：CC 持久化 discoveryState 供下次刷新免重发现（auth.ts:1997-2035）——若
        // Java 只写内存不落库，跨进程/跨会话刷新仍每次全链重发现，失去 R5 价值。
        assertThat(saved.getDiscoveryState()).as("discoveryState 应落库")
            .contains("authorizationServerUrl")
            .contains(AS_ISSUER)
            .contains("authorizationEndpoint")
            .contains(AS_ISSUER + "/token");
        assertThat(saved.getServerKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("[S6 OAuth-R5] 刷新复用持久化 metadata：零发现请求直接刷新（CC _doRefresh auth.ts:2225-2230）")
    void refreshServerToken_reusesPersistedMetadata_noRediscovery() {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("key-1");
        t.setServerName(SERVER);
        t.setRefreshToken("rt-1");
        t.setClientId("cid");
        t.setExpiresAt(System.currentTimeMillis() + 100_000L);
        // 已持久化完整 metadata（authorizationServerUrl + authorizationServerMetadata）
        t.setDiscoveryState("{\"authorizationServerUrl\":\"" + AS_ISSUER + "\","
            + "\"authorizationServerMetadata\":{\"authorizationEndpoint\":\"" + AS_ISSUER
            + "/authorize\",\"tokenEndpoint\":\"" + AS_ISSUER + "/token\",\"issuer\":\""
            + AS_ISSUER + "\"}}");
        when(tokenStore.read(anyString())).thenReturn(t);
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.refreshTokens(eq(AS_ISSUER + "/token"), anyMap()))
            .thenReturn(new McpAuth.Tokens("at-new", "rt-new",
                System.currentTimeMillis() + 3600_000L, "read"));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t2) -> null, null, tokenStore, null);

        McpAuth.Tokens tokens = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        // WHY (规则九)：CC 刷新 metadata 优先级 ② 直接复用持久化 metadata（auth.ts:2225-2230
        // "Using persisted auth server metadata for refresh"）——若 Java 无视 discoveryState 全链
        // 重发现，跨会话刷新退化回每次 PRM+AS 探测（RFC 9728 两跳），R5 免重发现不成立。
        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isEqualTo("at-new");
        verify(http, never()).fetchProtectedResource(anyString(), anyLong());
        verify(http, never()).fetchAuthServer(anyString(), anyLong());
    }

    @Test
    @DisplayName("[S6 OAuth-R5] 刷新经持久化 AS URL 直连重发现（跳过 RFC 9728 PRM，CC auth.ts:2231-2240）")
    void refreshServerToken_rediscoverFromPersistedAsUrl_skipsPrm() {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("key-1");
        t.setServerName(SERVER);
        t.setRefreshToken("rt-1");
        t.setClientId("cid");
        t.setExpiresAt(System.currentTimeMillis() + 100_000L);
        // 仅持久化 authorizationServerUrl（无 metadata）→ 直连 AS URL 重发现
        t.setDiscoveryState("{\"authorizationServerUrl\":\"" + AS_ISSUER + "\"}");
        when(tokenStore.read(anyString())).thenReturn(t);
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(eq(AS_ISSUER), anyLong()))
            .thenReturn(new McpAuth.AuthServerMetadata(
                AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER));
        when(http.refreshTokens(eq(AS_ISSUER + "/token"), anyMap()))
            .thenReturn(new McpAuth.Tokens("at-new", "rt-new",
                System.currentTimeMillis() + 3600_000L, "read"));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t2) -> null, null, tokenStore, null);

        McpAuth.Tokens tokens = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        // WHY (规则九)：CC ③ 直连持久化 AS URL 做 RFC 8414（auth.ts:2236-2240）——若 Java 仍对
        // 资源 URL 走 PRM→AS 链，持久化 AS URL 形同虚设（多一跳 + 依赖资源 URL 的 PRM 支持）。
        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isEqualTo("at-new");
        verify(http).fetchAuthServer(eq(AS_ISSUER), anyLong());
        verify(http, never()).fetchProtectedResource(anyString(), anyLong());
    }

    @Test
    @DisplayName("[S6 OAuth-R5] 取锁后重读：另一进程已刷新（expiresIn>300s）→ 直接复用不重复刷新（CC auth.ts:2146-2157）")
    void refreshServerToken_anotherProcessAlreadyRefreshed_reusesTokens() {
        McpOAuthTokenService tokenStore = mock(McpOAuthTokenService.class);
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("key-1");
        t.setServerName(SERVER);
        t.setAccessToken("at-stored");
        t.setRefreshToken("rt-stored");
        t.setClientId("cid");
        t.setExpiresAt(System.currentTimeMillis() + 600_000L); // expiresIn ~600s > 300
        t.setScope("read");
        when(tokenStore.read(anyString())).thenReturn(t);
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.refreshTokens(anyString(), anyMap()))
            .thenReturn(new McpAuth.Tokens("at-should-not-be-used", "rt-x",
                System.currentTimeMillis() + 3600_000L, "read"));
        McpAuth mcpAuth = new McpAuth(null, http, u -> {}, (s, t2) -> null, null, tokenStore, null);

        McpAuth.Tokens tokens = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        // WHY (规则九)：跨进程锁的核心收益——并发进程先后取锁，后取锁者重读发现前者已刷新成功
        // 则直接复用（auth.ts:2146-2157 "Another process already refreshed tokens"），避免
        // refresh_token 被多进程并发消费（部分 AS 刷新 token 单次有效，并发刷新会互相失效）。
        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).as("应复用另一进程刷新后的 token，不重复刷新").isEqualTo("at-stored");
        verify(http, never()).refreshTokens(anyString(), anyMap());
    }

    @Test
    @DisplayName("[S6 OAuth-R5] SecureStorage 回退：saveDiscoveryState → readDiscoveryState 往返一致（CC discoveryState() auth.ts:2037-2088）")
    void discoveryState_secureStorageRoundTrip() {
        AtomicReference<Map<String, Object>> store = new AtomicReference<>(new LinkedHashMap<>());
        McpAuth mcpAuth = new McpAuth(memoryStorage(store), mockHttp(), u -> {}, (s, t) -> null, null);
        McpAuth.AuthServerMetadata metadata = new McpAuth.AuthServerMetadata(
            AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER, null, "read");

        mcpAuth.saveDiscoveryState("key-1", SERVER, SERVER_URL,
            "https://resource.example.com/meta", metadata);
        McpAuth.AuthDiscoveryState state = mcpAuth.readDiscoveryState("key-1");

        // WHY (规则九)：readDiscoveryState 必须能还原 saveDiscoveryState 的 URL + metadata——
        // 否则刷新路径（resolveMetadataForRefresh）读不到持久化值，免重发现失效。
        assertThat(state).isNotNull();
        assertThat(state.authorizationServerUrl()).isEqualTo(AS_ISSUER);
        assertThat(state.resourceMetadataUrl()).isEqualTo("https://resource.example.com/meta");
        assertThat(state.authorizationServerMetadata()).isNotNull();
        assertThat(state.authorizationServerMetadata().tokenEndpoint()).isEqualTo(AS_ISSUER + "/token");
    }

    // ───────────── 归因：state 校验 / 超时 / clientId 缺失 ─────────────

    @Test
    @DisplayName("CSRF：state 不匹配 → STATE_MISMATCH（回调层拒绝，编排归因）")
    void stateMismatch_mapsToStateMismatch() {
        McpAuth.CallbackHandler cb = mock(McpAuth.CallbackHandler.class);
        when(cb.waitForAuthorizationCode(anyString(), anyLong()))
            .thenThrow(new IllegalStateException("OAuth state mismatch - possible CSRF attack"));
        McpAuth mcpAuth = new McpAuth(null, mockHttp(), u -> {}, cb, null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(SERVER,
            new McpAuth.OAuthServerConfig("sse", SERVER_URL, Map.of(), null, "cid", null),
            url -> {}, true);

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).isEqualTo(McpAuth.MCPOAuthFlowErrorReason.STATE_MISMATCH);
        assertThat(result.errorMessage()).contains("CSRF");
    }

    @Test
    @DisplayName("超时：回调 5 分钟超时 → AUTH_TIMEOUT")
    void timeout_mapsToAuthTimeout() {
        McpAuth.CallbackHandler cb = mock(McpAuth.CallbackHandler.class);
        when(cb.waitForAuthorizationCode(anyString(), anyLong()))
            .thenThrow(new IllegalStateException("Authentication timeout"));
        McpAuth mcpAuth = new McpAuth(null, mockHttp(), u -> {}, cb, null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(SERVER,
            new McpAuth.OAuthServerConfig("sse", SERVER_URL, Map.of(), null, "cid", null),
            url -> {}, true);

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).isEqualTo(McpAuth.MCPOAuthFlowErrorReason.AUTH_TIMEOUT);
    }

    @Test
    @DisplayName("DCR 不可用：无预配置/存储 clientId 且 AS 无 registration_endpoint → REGISTRATION_FAILED（SDK Incompatible auth server）")
    void dcr_missingRegistrationEndpoint_mapsToRegistrationFailed() {
        McpAuth mcpAuth = new McpAuth(null, mockHttp(), u -> {}, (s, t) -> "code", null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(SERVER,
            new McpAuth.OAuthServerConfig("sse", SERVER_URL, Map.of(), null, null, null),
            url -> {}, true);

        // WHY：CC clientInformation() 返回 undefined（auth.ts:1508）→ SDK registerClient 需要
        // metadata.registration_endpoint（auth.js:920-922）；mockHttp() 的 3 参 AuthServerMetadata
        // registrationEndpoint=null → 抛 "Incompatible auth server" → REGISTRATION_FAILED。
        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).isEqualTo(McpAuth.MCPOAuthFlowErrorReason.REGISTRATION_FAILED);
    }

    @Test
    @DisplayName("DCR：无预配置/存储 clientId 且 AS 有 registration_endpoint → 动态注册 → token 交换用注册 client_id → client_id 持久化复用")
    void dcr_registersClientAndReusesClientId() throws Exception {
        AtomicReference<Map<String, Object>> store = new AtomicReference<>(new LinkedHashMap<>());
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchProtectedResource(eq(SERVER_URL), anyLong()))
            .thenReturn(new McpAuth.ProtectedResourceMetadata(SERVER_URL, AS_ISSUER));
        when(http.fetchAuthServer(eq(AS_ISSUER), anyLong()))
            .thenReturn(new McpAuth.AuthServerMetadata(
                AS_ISSUER + "/authorize", AS_ISSUER + "/token", AS_ISSUER,
                AS_ISSUER + "/register", "read write"));
        when(http.registerClient(eq(AS_ISSUER + "/register"), any()))
            .thenReturn(new McpAuth.ClientInfo("dcr-client-1", "dcr-secret-1", java.util.List.of()));
        when(http.exchangeCodeForTokens(eq(AS_ISSUER + "/token"), anyMap()))
            .thenReturn(new McpAuth.Tokens("at-2", "rt-2",
                System.currentTimeMillis() + 3600_000L, "read write"));

        McpAuth.CallbackHandler cb = mock(McpAuth.CallbackHandler.class);
        when(cb.waitForAuthorizationCode(anyString(), eq(McpAuth.OAUTH_BROWSER_TIMEOUT_MS)))
            .thenReturn("auth-code-dcr");

        McpAuth mcpAuth = new McpAuth(memoryStorage(store), http, u -> {}, cb, null);
        // 无预配置 clientId → 触发 RFC 7591 DCR（CC clientInformation() undefined，auth.ts:1508-1510）
        McpAuth.OAuthServerConfig config = new McpAuth.OAuthServerConfig(
            "sse", SERVER_URL, Map.of(), null, null, null);

        McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(SERVER, config, url -> {}, true);

        assertThat(result.success()).as("DCR + 授权码流应成功").isTrue();
        assertThat(result.tokens()).isNotNull();

        // ③ client_id 持久化复用：token 交换携带 DCR 注册的 client_id（CC saveClientInformation
        // 后 SDK 第二次 clientInformation() 复用，auth.ts:1513-1538）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(http).exchangeCodeForTokens(eq(AS_ISSUER + "/token"), params.capture());
        assertThat(params.getValue()).containsEntry("client_id", "dcr-client-1");

        // ③ client_id + client_secret 持久化到存储（CC saveClientInformation）
        assertThat(store.get().get("clientId")).isEqualTo("dcr-client-1");
        assertThat(store.get().get("clientSecret")).isEqualTo("dcr-secret-1");
    }

    // ───────────── LoopbackCallbackHandler 真实 loopback（③+④ 端到端）─────────────

    @Test
    @DisplayName("loopback 回调：GET /callback?code&state 匹配 → 返回 code")
    void loopbackHandler_returnsCodeOnMatchingState() throws Exception {
        int port = freePort();
        LoopbackCallbackHandler loopback = new LoopbackCallbackHandler();
        String state = McpOAuth.generateState();
        loopback.bind(port, state);

        String body = get("http://127.0.0.1:" + port + "/callback?code=abc&state=" + state);
        assertThat(body).contains("Authentication Successful");

        assertThat(loopback.waitForAuthorizationCode(state, 3_000)).isEqualTo("abc");
        loopback.close();
    }

    @Test
    @DisplayName("loopback 回调：state 不匹配 → CSRF 拒绝 + HTTP 400")
    void loopbackHandler_rejectsMismatchedState() throws Exception {
        int port = freePort();
        LoopbackCallbackHandler loopback = new LoopbackCallbackHandler();
        String state = McpOAuth.generateState();
        loopback.bind(port, state);

        get("http://127.0.0.1:" + port + "/callback?code=abc&state=WRONG");
        assertThatThrownBy(() -> loopback.waitForAuthorizationCode(state, 3_000))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OAuth state mismatch");
        loopback.close();
    }

    // ───────────── DefaultOAuthHttpClient 真实 token 交换（⑤ 端到端）─────────────

    @Test
    @DisplayName("exchangeCodeForTokens：表单 POST 携带 code_verifier，解析 RFC 6749 token 响应")
    void defaultHttpClient_exchangesCodeWithCodeVerifier() throws Exception {
        int port = freePort();
        HttpServer tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        tokenServer.createContext("/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("code_verifier=") || !body.contains("grant_type=authorization_code")) {
                ex.sendResponseHeaders(400, 0);
                ex.close();
                return;
            }
            byte[] json = ("{\"access_token\":\"at-real\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                + "\"refresh_token\":\"rt-real\",\"scope\":\"read\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(json);
            }
        });
        tokenServer.start();
        try {
            DefaultOAuthHttpClient client = new DefaultOAuthHttpClient();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", "code-1");
            params.put("redirect_uri", "http://localhost:1/callback");
            params.put("client_id", "cid");
            params.put("code_verifier", McpOAuth.generateCodeVerifier());

            McpAuth.Tokens tokens = client.exchangeCodeForTokens(
                "http://127.0.0.1:" + port + "/token", params);

            assertThat(tokens).isNotNull();
            assertThat(tokens.accessToken()).isEqualTo("at-real");
            assertThat(tokens.refreshToken()).isEqualTo("rt-real");
            assertThat(tokens.scope()).isEqualTo("read");
            assertThat(tokens.expiresAt()).isGreaterThan(System.currentTimeMillis());
        } finally {
            tokenServer.stop(0);
        }
    }

    // ───────────── DefaultOAuthHttpClient 真实 DCR（① 端到端）─────────────

    @Test
    @DisplayName("registerClient：JSON POST registration_endpoint（client_name/redirect_uris/grant_types），解析 OAuthClientInformationFull")
    void defaultHttpClient_registerClientPostsJsonAndParses() throws Exception {
        int port = freePort();
        HttpServer regServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        regServer.createContext("/register", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // WHY：RFC 7591 请求必须是 JSON（非表单），且携带 client_name/redirect_uris/grant_types
            // （SDK registerClient body = {...clientMetadata, scope?}，auth.js:933-936）——若被
            // 错误实现为 form POST，body 不会含这些 JSON 键 → 400。
            if (!body.contains("\"client_name\"") || !body.contains("\"grant_types\"")
                    || !body.contains("authorization_code") || !body.contains("\"redirect_uris\"")
                    || !body.contains("\"scope\"")) {
                ex.sendResponseHeaders(400, 0);
                ex.close();
                return;
            }
            byte[] json = ("{\"client_id\":\"reg-client-1\",\"client_secret\":\"reg-secret-1\","
                + "\"client_name\":\"Claude Code (srv)\",\"grant_types\":[\"authorization_code\",\"refresh_token\"],"
                + "\"redirect_uris\":[\"http://localhost:1234/callback\"],"
                + "\"token_endpoint_auth_method\":\"none\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(json);
            }
        });
        regServer.start();
        try {
            DefaultOAuthHttpClient client = new DefaultOAuthHttpClient();
            McpAuth.ClientRegistrationRequest req = new McpAuth.ClientRegistrationRequest(
                "Claude Code (srv)", java.util.List.of("http://localhost:1234/callback"),
                java.util.List.of("authorization_code", "refresh_token"),
                java.util.List.of("code"), "none", "read");

            McpAuth.ClientInfo info = client.registerClient(
                "http://127.0.0.1:" + port + "/register", req);

            assertThat(info).isNotNull();
            assertThat(info.clientId()).isEqualTo("reg-client-1");
            assertThat(info.clientSecret()).isEqualTo("reg-secret-1");
        } finally {
            regServer.stop(0);
        }
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body();
    }
}

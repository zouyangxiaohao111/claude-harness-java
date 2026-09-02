package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP OAuth flow · 对齐 CC services/mcp/auth.ts.
 *
 * <p>L1 语义: MCP server 动态注册 OAuth 客户端 — PKCE + browser auth + token refresh;
 *            secure storage (keychain);client registration via PRM/AS metadata.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: AUTH_REQUEST_TIMEOUT_MS=30000; 7 MCPRefreshFailureReason constant;
 *       record 集: ClientInfo + Tokens + AuthResult + AuthServerMetadata +
 *       ProtectedResourceMetadata + MCPRefreshFailureReason enum.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — discoverMetadata → registerClient → auth flow →
 *       refresh token (on 401) → update storage.</li>
 *   <li><b>A3</b>: 注入式 (tokenStorage + httpFetcher);silent failure on metadata fetch fail.</li>
 *   <li><b>A4</b>: 401 refresh 失败 → throw MCPRefreshFailed;missing client info → throw.</li>
 *   <li><b>A5</b>: 真实场景 — MCP server 添加时动态 OAuth;token 过期后 refresh.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS MCP SDK → Java 抽象 (caller wired);
 *                    TS OAuth error class → Java sealed Exception;
 *                    TS Promise → Java Supplier.
 */
public final class McpAuth {

    private static final Logger log = LoggerFactory.getLogger(McpAuth.class);

    /** [S6 OAuth-R5] discoveryState JSON 序列化（字段名对齐 CC OAuthDiscoveryState）。 */
    private static final ObjectMapper DISCOVERY_JSON = new ObjectMapper();

    public static final long AUTH_REQUEST_TIMEOUT_MS = 30_000L;
    public static final long OAUTH_BROWSER_TIMEOUT_MS = 300_000L;

    /** [S6 OAuth-R5] 跨进程刷新锁最大重试次数 · CC original: MAX_LOCK_RETRIES = 5（auth.ts:94）。 */
    public static final int MAX_LOCK_RETRIES = 5;

    /** [S03 R2-04 X-3] 刷新最大尝试次数 · CC original: MAX_ATTEMPTS = 3（auth.ts:2180）。 */
    public static final int MAX_REFRESH_ATTEMPTS = 3;

    /** [S03 R2-04 X-4] 非标准 invalid_grant 别名 · CC original: NONSTANDARD_INVALID_GRANT_ALIASES
     *  （auth.ts:147-151，Slack 系 oauth.v2.user.access 实测 invalid_refresh_token +
     *  文档 expired_refresh_token/token_expired）。归一为 invalid_grant（auth.ts:177-184）。 */
    public static final java.util.Set<String> NONSTANDARD_INVALID_GRANT_ALIASES =
        java.util.Set.of("invalid_refresh_token", "expired_refresh_token", "token_expired");

    /** CC MCPRefreshFailureReason — analytics stable enum. */
    public enum MCPRefreshFailureReason {
        METADATA_DISCOVERY_FAILED,
        NO_CLIENT_INFO,
        NO_TOKENS_RETURNED,
        INVALID_GRANT,
        TRANSIENT_RETRIES_EXHAUSTED,
        REQUEST_FAILED
    }

    public enum MCPOAuthFlowErrorReason {
        REGISTRATION_FAILED, AUTH_TIMEOUT, NETWORK_ERROR, USER_CANCELLED,
        /** CC MCPOAuthFlowErrorReason (auth.ts:1265-1291) — 编排失败原因稳定码. */
        CANCELLED, TOKEN_EXCHANGE_FAILED, STATE_MISMATCH, PROVIDER_DENIED, PORT_UNAVAILABLE,
        SDK_AUTH_FAILED, UNKNOWN
    }

    public record ClientInfo(
        String clientId, String clientSecret, java.util.List<String> redirectUris) {}

    /**
     * RFC 7591 动态客户端注册请求体 · 对齐 CC {@code ClaudeAuthProvider.clientMetadata}
     * getter（auth.ts:1417-1437）+ SDK {@code registerClient} JSON body（auth.js:928-937）。
     *
     * @param clientName             CC original: client_name = "Claude Code (${serverName})"（auth.ts:1419）
     * @param redirectUris           CC original: redirect_uris = [redirectUri]（auth.ts:1420）
     * @param grantTypes             CC original: grant_types = ['authorization_code','refresh_token']（auth.ts:1421）
     * @param responseTypes          CC original: response_types = ['code']（auth.ts:1422）
     * @param tokenEndpointAuthMethod CC original: token_endpoint_auth_method = 'none'（public client，auth.ts:1423）
     * @param scope                  CC original: clientMetadata.scope = getScopeFromMetadata(metadata)（auth.ts:1427-1434）
     */
    public record ClientRegistrationRequest(
        String clientName,
        java.util.List<String> redirectUris,
        java.util.List<String> grantTypes,
        java.util.List<String> responseTypes,
        String tokenEndpointAuthMethod,
        String scope) {}

    public record Tokens(String accessToken, String refreshToken, long expiresAt, String scope) {}

    public record AuthResult(boolean success, Tokens tokens, String errorMessage,
        MCPOAuthFlowErrorReason errorReason) {}

    /**
     * RFC 8414 Authorization Server metadata（Java 侧抽取字段）。
     *
     * @param authorizationEndpoint  CC original: authorization_endpoint
     * @param tokenEndpoint          CC original: token_endpoint
     * @param issuer                 CC original: issuer
     * @param registrationEndpoint   CC original: registration_endpoint（RFC 7591 DCR 入口，
     *                               SDK registerClient auth.js:920 必需）
     * @param scope                  CC {@code getScopeFromMetadata} 产物
     *                               （scope/default_scope/scopes_supported.join(' ')，auth.ts:2445-2465）
     * @param revocationEndpoint     CC original: revocation_endpoint（RFC 7009，auth.ts:495-501；
     *                               revokeServerTokens 吊销入口，缺失 → log 不支持吊销）
     * @param revocationEndpointAuthMethods CC original: revocation_endpoint_auth_methods_supported
     *                               （auth.ts:505-511 优先于 token 端点列表）
     * @param tokenEndpointAuthMethods      CC original: token_endpoint_auth_methods_supported
     *                               （auth.ts:509-511 回退）
     */
    public record AuthServerMetadata(
        String authorizationEndpoint, String tokenEndpoint, String issuer,
        String registrationEndpoint, String scope,
        String revocationEndpoint,
        java.util.List<String> revocationEndpointAuthMethods,
        java.util.List<String> tokenEndpointAuthMethods) {

        public AuthServerMetadata(String authorizationEndpoint, String tokenEndpoint, String issuer) {
            this(authorizationEndpoint, tokenEndpoint, issuer, null, null);
        }

        public AuthServerMetadata(String authorizationEndpoint, String tokenEndpoint, String issuer,
                String registrationEndpoint, String scope) {
            this(authorizationEndpoint, tokenEndpoint, issuer, registrationEndpoint, scope,
                null, null, null);
        }
    }

    public record ProtectedResourceMetadata(String resource, String authorizationServer) {}

    /**
     * [S6 OAuth-R5] discoveryState 持久化记录 · CC original: SDK {@code OAuthDiscoveryState}
     * （auth.ts:1997-2035 saveDiscoveryState / :2037-2088 discoveryState 读取）。
     *
     * @param authorizationServerUrl   CC original: authorizationServerUrl — AS 基础 URL；
     *                                 刷新时直连该 URL 重发现，跳过 RFC 9728 PRM 探测（auth.ts:2231-2240）
     * @param resourceMetadataUrl      CC original: resourceMetadataUrl — WWW-Authenticate
     *                                 resource_metadata（可为 null，Java 流程当前未采集）
     * @param authorizationServerMetadata 抽取的 AS metadata（authorization_endpoint/token_endpoint/
     *                                 issuer/registration_endpoint/scope）——CC 因 keychain 4096 字节
     *                                 限制仅存 URL（auth.ts:2007-2015，issue #30337）；Java DB 无此限制，
     *                                 持久化抽取值使下次刷新零请求复用（本 session 任务要求
     *                                 "发现的 AuthServerMetadata 持久化（免重发现）"）
     */
    public record AuthDiscoveryState(
        String authorizationServerUrl,
        String resourceMetadataUrl,
        AuthServerMetadata authorizationServerMetadata) {}

    /**
     * 刷新/交换失败异常 · 对齐 CC {@code MCPRefreshFailed}（含 reason）+ oauth4webapi 错误类。
     *
     * <p>[S03 R2-04 X-3] 新增 {@code httpStatus}（-1 = 非 HTTP 错误）：_doRefresh 重试判定
     * 需区分 429/5xx（可重试 transient，CC auth.ts:2331-2334）与 401（revoke Bearer 回退，
     * auth.ts:436-450）——纯 message 匹配脆弱，HTTP 状态码为稳定信号。
     */
    public static class MCPRefreshFailed extends RuntimeException {
        private final MCPRefreshFailureReason reason;
        /** HTTP 状态码；-1 = 非 HTTP 错误（超时/IO/解析）。 */
        private final int httpStatus;
        public MCPRefreshFailed(MCPRefreshFailureReason reason, String message) {
            this(reason, message, -1);
        }
        public MCPRefreshFailed(MCPRefreshFailureReason reason, String message, int httpStatus) {
            super(message);
            this.reason = reason;
            this.httpStatus = httpStatus;
        }
        public MCPRefreshFailureReason reason() { return reason; }
        public int httpStatus() { return httpStatus; }
    }

    /**
     * RFC 6749 §5.2 {@code invalid_grant}（含 Slack 系非标准别名归一后）·
     * 对齐 CC oauth4webapi {@code InvalidGrantError}（auth.ts:2289 {@code instanceof
     * InvalidGrantError} 判定 → 清凭据路径）。
     */
    public static class InvalidGrantError extends RuntimeException {
        public InvalidGrantError(String message) {
            super(message);
        }
        public InvalidGrantError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface SecureStorage {
        java.util.Map<String, Object> read();
        void write(java.util.Map<String, Object> data);
    }

    public interface OAuthHttpClient {
        /**
         * [S03 R2-04 X-5] RFC 7009 token 吊销（单次 POST）· CC original: {@code revokeToken}
         * （auth.ts:381-459）。POST {@code endpoint}（application/x-www-form-urlencoded，
         * params 含 token/token_type_hint，按 authMethod 可含 client_id/client_secret），
         * 携带 {@code headers}（Authorization: Basic 或 Bearer）。非 2xx 抛
         * {@link MCPRefreshFailed}（httpStatus 填 HTTP 状态码，供调用方 401 → Bearer
         * 回退判定，auth.ts:436-450）。
         */
        void revokeToken(String endpoint, Map<String, String> params, Map<String, String> headers);
        ProtectedResourceMetadata fetchProtectedResource(String url, long timeoutMs);
        AuthServerMetadata fetchAuthServer(String url, long timeoutMs);
        /**
         * RFC 7591 动态客户端注册 · CC original: SDK {@code registerClient}（auth.js:917-942）。
         * POST registration_endpoint（JSON body），解析 {@code OAuthClientInformationFull}
         * 返回 client_id/client_secret；非 2xx 抛 {@link MCPRefreshFailed}。
         */
        ClientInfo registerClient(String registrationEndpoint, ClientRegistrationRequest request);
        Tokens exchangeCodeForTokens(String endpoint, Map<String, String> params);
        Tokens refreshTokens(String endpoint, Map<String, String> params);
    }

    public interface BrowserOpener {
        void open(String url);
    }

    public interface CallbackHandler {
        String waitForAuthorizationCode(String state, long timeoutMs);
    }

    /**
     * performMCPOAuthFlow 的 server 配置 · 对齐 CC {@code McpSSEServerConfig | McpHTTPServerConfig}
     * 的子集（auth.ts:847-962 用到字段）+ {@code serverConfig.oauth}（callbackPort/clientId/
     * authServerMetadataUrl）。
     */
    public record OAuthServerConfig(
        /** CC serverConfig.type ("sse"/"http") */
        String type,
        /** CC serverConfig.url — MCP server 资源 URL */
        String url,
        /** CC serverConfig.headers — 参与 getServerKey 哈希 */
        Map<String, String> headers,
        /** CC serverConfig.oauth?.callbackPort — 预配置回调端口（null → findAvailablePort） */
        String configuredCallbackPort,
        /** CC serverConfig.oauth?.clientId — 预配置客户端 ID */
        String clientId,
        /** CC serverConfig.oauth?.authServerMetadataUrl — 直接 RFC 8414 metadata 文档 URL */
        String authServerMetadataUrl) {

        public OAuthServerConfig {
            headers = headers == null ? Map.of() : headers;
        }

        public static OAuthServerConfig of(String type, String url) {
            return new OAuthServerConfig(type, url, Map.of(), null, null, null);
        }
    }

    private final SecureStorage tokenStorage;
    private final OAuthHttpClient httpClient;
    private final BrowserOpener browserOpener;
    private final CallbackHandler callbackHandler;
    private final Supplier<ClientInfo> clientInfoSupplier;
    /** DB 持久化（Q-01=A 受控偏差：CC keychain → Java mcp_oauth_tokens 表）；null 时回退 SecureStorage. */
    private final McpOAuthTokenService tokenStore;
    /** 端口分配（CC oauthPort.ts findAvailablePort/buildRedirectUri）。 */
    private final OauthPort oauthPort;
    /** [S6 OAuth-R5] 内存 metadata 缓存 · CC original: ClaudeAuthProvider._metadata
     *  （auth.ts:1385-1387 setMetadata / _doRefresh 复用 auth.ts:2256）。McpAuth 实例被
     *  McpAuthHeaderProvider 全 server 共享，故按 serverKey 分键。 */
    private final Map<String, AuthServerMetadata> metadataCache = new ConcurrentHashMap<>();

    public McpAuth(SecureStorage tokenStorage, OAuthHttpClient httpClient,
            BrowserOpener browserOpener, CallbackHandler callbackHandler,
            Supplier<ClientInfo> clientInfoSupplier) {
        this(tokenStorage, httpClient, browserOpener, callbackHandler, clientInfoSupplier, null, null);
    }

    public McpAuth(SecureStorage tokenStorage, OAuthHttpClient httpClient,
            BrowserOpener browserOpener, CallbackHandler callbackHandler,
            Supplier<ClientInfo> clientInfoSupplier, McpOAuthTokenService tokenStore, OauthPort oauthPort) {
        this.tokenStorage = tokenStorage == null ? new SecureStorage() {
            public java.util.Map<String, Object> read() { return Map.of(); }
            public void write(java.util.Map<String, Object> d) {}
        } : tokenStorage;
        // 默认真实 HTTP 实现（DefaultOAuthHttpClient），使 performMCPOAuthFlow/discoverAuthServer 开箱可用
        this.httpClient = httpClient == null ? new DefaultOAuthHttpClient() : httpClient;
        this.browserOpener = browserOpener == null ? u -> {} : browserOpener;
        this.callbackHandler = callbackHandler == null
            ? (s, t) -> { throw new UnsupportedOperationException(); }
            : callbackHandler;
        this.clientInfoSupplier = clientInfoSupplier == null ? () -> null : clientInfoSupplier;
        this.tokenStore = tokenStore;
        this.oauthPort = oauthPort == null ? new OauthPort() : oauthPort;
    }

    public McpAuth() {
        this(null, null, null, null, null, null, null);
    }

    /**
     * CC discoverAuthorizationServerMetadata · 对齐 CC {@code fetchAuthServerMetadata}
     * （auth.ts:256-311）降级链。
     *
     * <p>[OAuth-R4] metadata 发现失败<b>非致命</b>：CC RFC 9728 → RFC 8414 链
     * （auth.ts:281-291）任一失败 log + fall through 到备用路径（auth.ts:292-300）；
     * 备用路径仅对<b>带路径分量</b>的资源 URL 生效（path-aware RFC 8414，
     * auth.ts:302-310，legacy server 在资源 URL 路径上 co-host metadata）；
     * 全部失败返回 null（可恢复错误，CC fetchAuthServerMetadata 返回 undefined，
     * auth.ts:304-306），由调用方按 CC 语义降级（refresh → metadata_discovery_failed →
     * null；performMCPOAuthFlow → sdk_auth_failed）。不再抛 METADATA_DISCOVERY_FAILED 硬终止。
     */
    public AuthServerMetadata discoverAuthServer(String resourceUrl) {
        // ① RFC 9728 PRM → RFC 8414 AS 链（CC auth.ts:281-291）；失败不硬终止，
        //    log + fall through 到备用路径（CC auth.ts:292-300，任意异常/缺失都回退）
        try {
            ProtectedResourceMetadata prm =
                httpClient.fetchProtectedResource(resourceUrl, AUTH_REQUEST_TIMEOUT_MS);
            if (prm != null && prm.authorizationServer() != null) {
                AuthServerMetadata as = httpClient.fetchAuthServer(
                    prm.authorizationServer(), AUTH_REQUEST_TIMEOUT_MS);
                if (as != null) {
                    return as;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] RFC 9728 链 AS 发现失败，回退备用路径: {}",
                        prm.authorizationServer());
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[McpAuth] RFC 9728 链 PRM 发现失败，回退备用路径: {}", resourceUrl);
            }
        } catch (Exception e) {
            // CC auth.ts:292-300：RFC 9728 链任意异常（网络/解析/HTTP）→ fall through
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] RFC 9728 链发现异常，回退备用路径: {}", e.toString());
            }
        }
        // ② 备用路径：仅当资源 URL 带路径分量时（CC auth.ts:302-310，url.pathname !== '/'），
        //    对资源 URL 直接做 path-aware RFC 8414（SDK discoverAuthorizationServerMetadata
        //    同款，探测 {url}/.well-known/oauth-authorization-server 等）
        if (!isRootUrl(resourceUrl)) {
            AuthServerMetadata as = httpClient.fetchAuthServer(resourceUrl, AUTH_REQUEST_TIMEOUT_MS);
            if (as != null) {
                return as;
            }
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 备用路径发现失败: {}", resourceUrl);
            }
        }
        // ③ 全部失败 → null（可恢复错误）。CC fetchAuthServerMetadata 返回 undefined
        //    （auth.ts:304-306），由调用方降级；不再抛 METADATA_DISCOVERY_FAILED。
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] OAuth metadata 发现失败 resourceUrl={}", resourceUrl);
        }
        return null;
    }

    /**
     * 带预配置 metadata URL 的发现 · CC original: {@code fetchAuthServerMetadata}
     * (auth.ts:256-311)。configuredMetadataUrl 非空 → 直取 RFC 8414 metadata 文档
     * （CC 要求必须 https；文档获取失败抛 METADATA_DISCOVERY_FAILED —— 显式配置错误，
     * CC auth.ts:263-278 同款硬失败）；否则回退 {@link #discoverAuthServer(String)}
     * 的 RFC 9728 PRM → RFC 8414 AS 链（[OAuth-R4] 该链失败返回 null 非致命，见上）。
     *
     * @param resourceUrl           MCP server 资源 URL
     * @param configuredMetadataUrl CC serverConfig.oauth?.authServerMetadataUrl（可为 null）
     */
    public AuthServerMetadata discoverAuthServer(String resourceUrl, String configuredMetadataUrl) {
        if (configuredMetadataUrl != null && !configuredMetadataUrl.isBlank()) {
            if (!configuredMetadataUrl.startsWith("https://")) {
                throw new MCPRefreshFailed(MCPRefreshFailureReason.METADATA_DISCOVERY_FAILED,
                    "authServerMetadataUrl must use https:// (got: " + configuredMetadataUrl + ")");
            }
            AuthServerMetadata as = httpClient.fetchAuthServer(configuredMetadataUrl, AUTH_REQUEST_TIMEOUT_MS);
            if (as == null) {
                throw new MCPRefreshFailed(MCPRefreshFailureReason.METADATA_DISCOVERY_FAILED,
                    "AS fetch failed: " + configuredMetadataUrl);
            }
            return as;
        }
        return discoverAuthServer(resourceUrl);
    }

    /**
     * <b>E1 对齐 CC</b>：把 {@link #refreshAuthorization} 适配为
     * {@link OAuth401Refresher.TokenRefresher}（CC {@code checkAndRefreshOAuthTokenIfNeeded(0,true)}）。
     *
     * <p>返回的 {@code TokenRefresher.forceRefresh()} 调用 {@code refreshAuthorization(serverUrl, resource)}，
     * 成功返回 true（刷新后有新 token）；任何异常（含 {@link MCPRefreshFailed}）捕获返回 false，
     * 对齐 CC auth.ts:1391 强制刷新失败语义。
     *
     * <p>用法：
     * <pre>{@code
     * McpAuth mcpAuth = ...;
     * OAuth401Refresher.TokenRefresher refresher = mcpAuth.asTokenRefresher(serverUrl, resource);
     * OAuth401Refresher oAuth401Refresher = new OAuth401Refresher(mcpAuth::asTokenStore, refresher);
     * }</pre>
     *
     * @param serverUrl MCP server OAuth URL（discoverAuthServer 入参）
     * @param resource  MCP resource（discoverAuthServer 入参）
     * @return {@link OAuth401Refresher.TokenRefresher} 实现
     */
    public com.nexusai.application.agent.api.OAuth401Refresher.TokenRefresher asTokenRefresher(
            String serverUrl, String resource) {
        return () -> {
            try {
                Tokens tokens = refreshAuthorization(serverUrl, resource);
                boolean success = tokens != null && tokens.accessToken() != null;
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] asTokenRefresher.forceRefresh 完成 success={}", success);
                }
                return success;
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] asTokenRefresher.forceRefresh 失败: {}", e.toString());
                }
                return false;
            }
        };
    }

    /**
     * <b>E1 对齐 CC</b>：把 {@code tokenStorage} 适配为
     * {@link OAuth401Refresher.TokenStore}（CC {@code getClaudeAIOAuthTokensAsync()}）。
     *
     * <p>返回的 {@code TokenStore.readCurrent()} 从 MCP 持久化存储读取 tokens，返回
     * {@link OAuth401Refresher.OAuthTokens} 快照；存储为空或无 token → 返回 null
     * （对齐 CC auth.ts:1380 无 refreshToken → false 路径）。
     *
     * @return {@link OAuth401Refresher.TokenStore} 实现
     */
    public com.nexusai.application.agent.api.OAuth401Refresher.TokenStore asTokenStore() {
        return () -> {
            Map<String, Object> stored = tokenStorage.read();
            if (stored == null || stored.isEmpty()) {
                return null;
            }
            String accessToken = (String) stored.get("accessToken");
            String refreshToken = (String) stored.get("refreshToken");
            if (accessToken == null && refreshToken == null) {
                return null;
            }
            return new com.nexusai.application.agent.api.OAuth401Refresher.OAuthTokens(
                accessToken, refreshToken);
        };
    }

    /**
     * CC refreshAuthorization（auth.ts:2090-2175）· SecureStorage 单 token 模型刷新入口。
     *
     * <p>[S03 R2-04 X-3] 旧「单次刷新无重试」路径已删除（D-S03-2）→ 复用共享
     * {@link #doRefreshLoop}（_doRefresh 核心，auth.ts:2177-2359）：MAX_ATTEMPTS=3 重试/退避
     * + invalid_grant 识别（重读存储 → 另一进程已刷新则复用，否则清凭据）。
     *
     * <p>asTokenRefresher/asTokenStore/本方法为 SecureStorage 遗留适配（0 生产消费者，
     * 候选删除登记 S10 评估）；行为契约不变：metadata 发现失败/刷新失败 → null（可恢复
     * 降级，调用方已把 null 视为刷新失败）。
     */
    public Tokens refreshAuthorization(String serverUrl, String resource) {
        ClientInfo info = clientInfoSupplier.get();
        if (info == null) {
            throw new MCPRefreshFailed(MCPRefreshFailureReason.NO_CLIENT_INFO,
                "No client info for refresh");
        }
        Map<String, Object> stored = tokenStorage.read();
        String refreshToken = (String) stored.get("refreshToken");
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new MCPRefreshFailed(MCPRefreshFailureReason.NO_TOKENS_RETURNED,
                "No refresh token in storage");
        }
        String clientId = info.clientId() == null ? "" : info.clientId();
        String clientSecret = info.clientSecret() == null ? "" : info.clientSecret();
        return doRefreshLoop(null, refreshToken, clientId, clientSecret,
            () -> discoverAuthServer(serverUrl),
            // persist：SecureStorage 单 token 模型（原实现写入语义不变）
            tokens -> {
                Map<String, Object> s = new HashMap<>(tokenStorage.read());
                s.put("accessToken", tokens.accessToken());
                s.put("refreshToken", tokens.refreshToken());
                s.put("expiresAt", tokens.expiresAt());
                s.put("scope", tokens.scope() == null ? "" : tokens.scope());
                tokenStorage.write(s);
            },
            // invalid_grant 时重读存储（CC auth.ts:2295-2316）
            () -> {
                Map<String, Object> s = tokenStorage.read();
                McpOAuthToken t = new McpOAuthToken();
                Object at = s.get("accessToken");
                Object rt = s.get("refreshToken");
                Object exp = s.get("expiresAt");
                Object scope = s.get("scope");
                t.setAccessToken(at == null ? null : at.toString());
                t.setRefreshToken(rt == null ? null : rt.toString());
                t.setExpiresAt(exp instanceof Number n ? n.longValue() : 0L);
                t.setScope(scope == null ? null : scope.toString());
                return t;
            });
    }

    /**
     * <b>DB 模式 per-server 刷新（S2 Bearer 注入 + 401-refresh 接线）</b> ·
     * 对齐 CC {@code ClaudeAuthProvider.refreshAuthorization(refreshToken)}（auth.ts:2090-2175）
     * + {@code _doRefresh}（auth.ts:2177-2359）。
     *
     * <p>performMCPOAuthFlow 把 token 持久化到 {@link McpOAuthTokenService}（DB，Q-01=A
     * keychain→DB）。本方法从 DB 读 refreshToken + clientId → metadata 解析（[S6 OAuth-R5]
     * 优先级：内存缓存 → 持久化 discoveryState metadata → 持久化 AS URL 直连重发现 → 全链发现）
     * → {@link OAuthHttpClient#refreshTokens}（RFC 6749 §6，public client →
     * token_endpoint_auth_method:none，仅带 client_id）→ {@link #persistTokens} 写回 DB。
     *
     * <p><b>[S6 OAuth-R5] 跨进程锁</b>：刷新全程持 {@code {configHome}/mcp-refresh-{key}.lock}
     * 文件锁（CC auth.ts:2094-2136），防多进程并发刷新同一 server 的 refresh_token 造成
     * token 竞态。取锁后<b>重读</b> token：若另一进程已刷新成功（expiresIn &gt; 300s）直接复用
     * 其 token 不重复刷新（CC auth.ts:2138-2163）；取锁失败 / 重试耗尽 → 不带锁继续
     * （CC auth.ts:2124-2136 "proceeding without lock"），不阻塞刷新主链路。
     *
     * <p><b>[S03 R2-04 X-3]</b> 旧「单次刷新无重试」路径已删除（D-S03-2）→ 刷新请求经
     * {@link #doRefreshLoop}（_doRefresh 共享核心）：MAX_ATTEMPTS=3 重试 + 指数退避
     * （1s/2s/4s，auth.ts:2349）仅对超时/HTTP 429/5xx 生效；invalid_grant（含 Slack 系
     * 别名归一）→ 重读 DB（另一进程已刷新 expiresIn&gt;300 → 复用，auth.ts:2301-2315）否则
     * {@link #invalidateCredentials(String, String) invalidateCredentials(serverKey, "tokens")}
     * 清凭据 + 返回 null（auth.ts:2318-2324）——旧 token 不再永留 DB（I-5）。
     *
     * <p>对比 {@link #refreshAuthorization(String, String)}（SecureStorage 单 token 模型），
     * 本方法是 MCP OAuth per-server 的正确刷新入口：token 按 serverKey 隔离，供
     * {@link McpAuthHeaderProvider}（transport Bearer 注入）消费。
     *
     * @param serverKey             {@link McpOAuth#getServerKey} 产物（transport 按 config 计算）
     * @param serverUrl             MCP server 资源 URL（discoverAuthServer 入参）
     * @param configuredMetadataUrl 预配置 RFC 8414 metadata URL（可 null → PRM→AS 发现链）
     * @return 刷新后的 {@link Tokens}（已持久化回 DB）；[OAuth-R4] metadata 发现失败 /
     *         invalid_grant 清凭据后 / 重试耗尽 → null（CC _doRefresh 返回 undefined，
     *         auth.ts:2250-2358），调用方降级（返回现存 token / false）
     * @throws MCPRefreshFailed 无 tokenStore / 无 refreshToken
     */
    public Tokens refreshServerToken(String serverKey, String serverUrl, String configuredMetadataUrl) {
        return refreshServerToken(serverKey, serverUrl, configuredMetadataUrl, false);
    }

    /**
     * 带 force 标记的 per-server 刷新 · CC original: {@code checkAndRefreshOAuthTokenIfNeeded(retryCount, force)}
     * （utils/auth.ts:1447-1516）。{@code force=true} 时<b>跳过</b>「另一进程已刷新」过期门
     * （CC auth.ts:1456 "Skip this check if force=true (server already told us token is bad)"）——
     * 401 强制刷新路径用（McpAuthHeaderProvider.forceRefresh），因为 server 已拒绝当前 token，
     * 即使本地 expiresIn&gt;300 也必须真正刷新；{@code force=false}（主动刷新，expiresIn≤300 触发）时
     * 过期门有意义：取锁后若 expiresIn&gt;300 = 另一进程已刷新，直接复用不重复刷新（CC auth.ts:2146-2157）。
     *
     * @param forceRefresh true = 401 强制刷新（跳过过期门）；false = 主动刷新（过期门启用）
     */
    public Tokens refreshServerToken(String serverKey, String serverUrl, String configuredMetadataUrl,
            boolean forceRefresh) {
        if (tokenStore == null) {
            throw new MCPRefreshFailed(MCPRefreshFailureReason.NO_TOKENS_RETURNED,
                "tokenStore not wired — McpAuthHeaderProvider 必须注入 McpOAuthTokenService");
        }
        // [S6 OAuth-R5] 跨进程刷新锁（CC refreshAuthorization auth.ts:2094-2136）；null = 不带锁继续。
        RefreshLock lock = acquireRefreshLock(serverKey);
        try {
            // ① 取锁后重读 token（另一进程可能已刷新，CC auth.ts:2138-2163）
            McpOAuthToken stored = tokenStore.read(serverKey);
            if (stored == null || stored.getRefreshToken() == null || stored.getRefreshToken().isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] refreshServerToken 无 refreshToken serverKey={}", serverKey);
                }
                throw new MCPRefreshFailed(MCPRefreshFailureReason.NO_TOKENS_RETURNED,
                    "No refresh token in DB for serverKey=" + serverKey);
            }
            long expiresAt = stored.getExpiresAt() == null ? 0L : stored.getExpiresAt();
            long expiresInSec = (expiresAt - System.currentTimeMillis()) / 1000L;
            // 过期门：仅主动刷新路径启用。force=true（401 强制刷新）时跳过——server 已拒绝当前
            // token，即使本地未过期也必须刷新（对齐 CC auth.ts:1456 注释 "Skip this check if
            // force=true (server already told us token is bad)"）。
            if (!forceRefresh && expiresInSec > 300) {
                // 另一进程已刷新成功 → 直接复用其 token，不重复刷新（CC auth.ts:2146-2157）
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] 另一进程已刷新 token（expiresIn={}s），直接复用 serverKey={}",
                        expiresInSec, serverKey);
                }
                return new Tokens(stored.getAccessToken(), stored.getRefreshToken(), expiresAt,
                    stored.getScope());
            }
            // 使用存储中最新的 refreshToken（CC auth.ts:2159-2162）
            String refreshToken = stored.getRefreshToken();
            String serverName = stored.getServerName() == null ? "" : stored.getServerName();
            String clientId = stored.getClientId() == null ? "" : stored.getClientId();
            // ②③ _doRefresh 重试循环（CC auth.ts:2177-2359）：metadata 解析（auth.ts:2222-2256）
            // → refreshTokens → persist；transient 退避重试；invalid_grant 清凭据/复用。
            return doRefreshLoop(serverKey, refreshToken, clientId, null,
                () -> resolveMetadataForRefresh(serverKey, serverUrl, configuredMetadataUrl),
                tokens -> persistTokens(serverKey, serverName, serverUrl, clientId, tokens),
                () -> tokenStore.read(serverKey));
        } finally {
            // 释放跨进程锁（CC auth.ts:2165-2173 finally release）
            if (lock != null) {
                lock.release();
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] 已释放刷新锁 serverKey={}", serverKey);
                }
            }
        }
    }

    @FunctionalInterface
    private interface MetadataResolver {
        AuthServerMetadata resolve();
    }

    @FunctionalInterface
    private interface TokenPersister {
        void persist(Tokens tokens);
    }

    @FunctionalInterface
    private interface TokenReader {
        McpOAuthToken read();
    }

    /**
     * [S03 R2-04 X-3] <b>_doRefresh 共享核心</b> · CC original: {@code ClaudeAuthProvider._doRefresh}
     * （auth.ts:2177-2359）。两刷新入口（DB 模式 {@link #refreshServerToken} / SecureStorage
     * 模式 {@link #refreshAuthorization}）共用：
     * <ol>
     *   <li><b>重试循环</b> — {@value #MAX_REFRESH_ATTEMPTS} 次；仅超时（timeout|timed out|
     *       etimedout|econnreset，auth.ts:2328-2330）或 HTTP 429/5xx（oauth4webapi
     *       ServerError/TemporarilyUnavailable/TooManyRequests，auth.ts:2331-2334）重试；
     *       退避 1000*2^(attempt-1)ms（1s/2s/4s，auth.ts:2349）</li>
     *   <li><b>invalid_grant 识别</b> — {@link InvalidGrantError}（oauth4webapi，auth.ts:2289）
     *       → 重读存储（auth.ts:2295-2298）：expiresIn&gt;300 = 另一进程已刷新 → 复用其 token
     *       （auth.ts:2301-2315）；否则 {@link #invalidateCredentials(String, String)
     *       invalidateCredentials(serverKey, "tokens")} 清凭据 + 返回 null（auth.ts:2318-2324）
     *       ——旧 token 不永留 DB（I-5）</li>
     *   <li><b>非重试失败</b> — 返回 null（CC 返回 undefined，不抛；调用方降级）
     *       [OAuth-R4] metadata 发现失败 → null（metadata_discovery_failed，auth.ts:2250-2254）</li>
     * </ol>
     *
     * @param serverKey       DB 模式 serverKey（SecureStorage 模式传 null → 不写 metadataCache）
     * @param refreshToken    待消费的 refresh_token
     * @param clientId        公共客户端 client_id（RFC 6749 §6，恒携带）
     * @param clientSecret    client_secret（仅 SecureStorage 模式携带；DB 模式 null = 不发送，
     *                        token_endpoint_auth_method:none）
     * @param metadataResolver metadata 解析（DB 模式 resolveMetadataForRefresh 缓存优先 /
     *                         SecureStorage 模式 discoverAuthServer 全链）
     * @param persister       刷新成功后的持久化回调
     * @param reReader        invalid_grant 时重读存储的回调（另一进程已刷新判定）
     * @return 刷新后的 {@link Tokens}；失败路径（metadata 失败/invalid_grant 清凭据/重试耗尽）
     *         返回 null
     */
    private Tokens doRefreshLoop(String serverKey, String refreshToken, String clientId,
            String clientSecret, MetadataResolver metadataResolver, TokenPersister persister,
            TokenReader reReader) {
        for (int attempt = 1; attempt <= MAX_REFRESH_ATTEMPTS; attempt++) {
            try {
                AuthServerMetadata as = metadataResolver.resolve();
                if (as == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] _doRefresh metadata 发现失败（metadata_discovery_failed），返回 null serverKey={}",
                            serverKey);
                    }
                    return null;
                }
                // 缓存供后续刷新复用（CC _doRefresh auth.ts:2256 this._metadata = metadata）
                if (serverKey != null) {
                    metadataCache.put(serverKey, as);
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("client_id", clientId == null ? "" : clientId);
                if (clientSecret != null && !clientSecret.isBlank()) {
                    params.put("client_secret", clientSecret);
                }
                params.put("refresh_token", refreshToken);
                params.put("grant_type", "refresh_token");
                Tokens tokens = httpClient.refreshTokens(as.tokenEndpoint(), params);
                if (tokens == null) {
                    throw new MCPRefreshFailed(MCPRefreshFailureReason.NO_TOKENS_RETURNED,
                        "Refresh returned no tokens" + (serverKey != null ? " for serverKey=" + serverKey : ""));
                }
                persister.persist(tokens);
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] _doRefresh 刷新成功 serverKey={} 有refreshToken={}",
                        serverKey, tokens.refreshToken() != null);
                }
                return tokens;
            } catch (InvalidGrantError e) {
                // invalid_grant 意味着 refresh token 本身失效/被吊销/过期；但另一进程可能已
                // 刷新成功——重读存储先查（CC auth.ts:2289-2315）
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] _doRefresh invalid_grant：{} serverKey={}", e.getMessage(), serverKey);
                }
                McpOAuthToken current = reReader.read();
                if (current != null) {
                    long exp = current.getExpiresAt() == null ? 0L : current.getExpiresAt();
                    long expiresIn = (exp - System.currentTimeMillis()) / 1000L;
                    if (expiresIn > 300) {
                        // 另一进程已刷新成功 → 复用其 token，不重复刷新（auth.ts:2301-2315）
                        if (log.isDebugEnabled()) {
                            log.debug("[McpAuth] _doRefresh invalid_grant 但另一进程已刷新（expiresIn={}s），复用 serverKey={}",
                                expiresIn, serverKey);
                        }
                        return new Tokens(current.getAccessToken(), current.getRefreshToken(), exp,
                            current.getScope());
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] _doRefresh 无有效 token，清凭据 serverKey={}", serverKey);
                }
                invalidateCredentials(serverKey, "tokens");
                return null;
            } catch (Exception e) {
                // 仅超时/HTTP 429/5xx 重试（CC auth.ts:2327-2335）；其余失败不重试
                boolean retryable = isRetryableRefreshFailure(e);
                if (!retryable || attempt >= MAX_REFRESH_ATTEMPTS) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] _doRefresh 刷新失败（{}），返回 null serverKey={}: {}",
                            retryable ? "transient_retries_exhausted" : "request_failed",
                            serverKey, e.getMessage());
                    }
                    return null;
                }
                long delayMs = 1000L * (1L << (attempt - 1)); // 1s, 2s, 4s（auth.ts:2349）
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] _doRefresh 刷新失败，{}ms 后重试（attempt {}/{}）serverKey={}: {}",
                        delayMs, attempt, MAX_REFRESH_ATTEMPTS, serverKey, e.getMessage());
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * CC _doRefresh 重试判定（auth.ts:2327-2335）：超时错误
     * （/timeout|timed out|etimedout|econnreset/i）或 HTTP 429（TooManyRequests）/
     * 5xx（ServerError/TemporarilyUnavailable）。其余（4xx 业务错误/连接拒绝等）不重试。
     */
    private static boolean isRetryableRefreshFailure(Exception e) {
        if (e instanceof MCPRefreshFailed mrf && mrf.httpStatus() > 0) {
            int status = mrf.httpStatus();
            return status == 429 || status >= 500;
        }
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof java.net.http.HttpTimeoutException
                    || cur instanceof java.net.SocketTimeoutException
                    || cur instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT)
            .matches(".*(timeout|timed out|etimedout|econnreset).*");
    }

    /**
     * [S6 OAuth-R5] 持久化 discoveryState · CC original: {@code ClaudeAuthProvider.saveDiscoveryState}
     * （auth.ts:1997-2035）。
     *
     * <p>performMCPOAuthFlow 发现 AS metadata 后调用（对齐 CC SDK auth() 内部发现后回调
     * saveDiscoveryState）；DB 模式落 {@code mcp_oauth_tokens.discovery_state} JSON 列，
     * SecureStorage 模式落 {@code discoveryState} 键。同时写内存缓存
     * {@link #metadataCache}（CC this._metadata，auth.ts:1454-1458 setMetadata）。
     *
     * <p><b>持久化范围</b>：CC 仅持久化 {authorizationServerUrl, resourceMetadataUrl}
     * （keychain 4096 字节 stdin 限制，auth.ts:2007-2015，issue #30337）；Java DB 无此限制，
     * 额外持久化抽取的 AS metadata（authorization_endpoint/token_endpoint/issuer/
     * registration_endpoint/scope），使下次刷新<b>零请求</b>复用（本 session 任务要求
     * "发现的 AuthServerMetadata 持久化（免重发现）"）。authorizationServerUrl =
     * metadata.issuer()（AS 基础 URL；issuer 缺失时回退 serverUrl）。
     *
     * @param serverKey            {@link McpOAuth#getServerKey} 产物
     * @param serverName           MCP server 名（DB 行新键时写入）
     * @param serverUrl            MCP server 资源 URL（DB 行新键时写入 + issuer 缺失回退）
     * @param resourceMetadataUrl  WWW-Authenticate resource_metadata（CC auth.ts:2028；可为 null）
     * @param metadata             已发现的 AS metadata（不可为 null）
     */
    public void saveDiscoveryState(String serverKey, String serverName, String serverUrl,
            String resourceMetadataUrl, AuthServerMetadata metadata) {
        if (metadata == null) {
            return;
        }
        String asUrl = (metadata.issuer() == null || metadata.issuer().isBlank())
            ? serverUrl : metadata.issuer();
        String json;
        try {
            json = discoveryJson(new AuthDiscoveryState(asUrl, resourceMetadataUrl, metadata));
        } catch (JsonProcessingException e) {
            log.warn("[McpAuth] saveDiscoveryState 序列化失败 serverKey={}: {}", serverKey, e.getMessage());
            return;
        }
        metadataCache.put(serverKey, metadata);
        if (tokenStore != null) {
            McpOAuthToken t = tokenStore.read(serverKey);
            if (t == null) {
                t = new McpOAuthToken();
                t.setServerKey(serverKey);
                t.setServerName(serverName);
                t.setServerUrl(serverUrl);
            }
            t.setDiscoveryState(json);
            tokenStore.save(t);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] discoveryState 已持久化到 DB serverKey={} asUrl={}", serverKey, asUrl);
            }
            return;
        }
        Map<String, Object> stored = new HashMap<>(tokenStorage.read());
        stored.put("discoveryState", json);
        tokenStorage.write(stored);
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] discoveryState 已持久化到 SecureStorage serverKey={}", serverKey);
        }
    }

    /**
     * [S6 OAuth-R5] 读取持久化 discoveryState · CC original: {@code ClaudeAuthProvider.discoveryState()}
     * （auth.ts:2037-2088）。DB 优先，SecureStorage 回退；无记录 / 解析失败返回 null。
     */
    public AuthDiscoveryState readDiscoveryState(String serverKey) {
        String json;
        if (tokenStore != null) {
            McpOAuthToken t = tokenStore.read(serverKey);
            json = t == null ? null : t.getDiscoveryState();
        } else {
            Object o = tokenStorage.read().get("discoveryState");
            json = o == null ? null : o.toString();
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return parseDiscoveryState(json);
        } catch (IOException e) {
            log.warn("[McpAuth] readDiscoveryState 解析失败 serverKey={}: {}", serverKey, e.getMessage());
            return null;
        }
    }

    /**
     * [S6 OAuth-R5] 刷新 metadata 解析优先级 · CC original: {@code _doRefresh}
     * （auth.ts:2222-2256）：
     * <ol>
     *   <li>① 内存缓存 {@link #metadataCache}（CC this._metadata，auth.ts:2222-2224）</li>
     *   <li>② 持久化 discoveryState.authorizationServerMetadata（CC auth.ts:2225-2230，
     *       "Using persisted auth server metadata for refresh"）</li>
     *   <li>③ 持久化 authorizationServerUrl → 直连该 AS 重发现，<b>跳过 RFC 9728 PRM 探测</b>
     *       （CC auth.ts:2231-2240）</li>
     *   <li>④ 全链发现（CC fetchAuthServerMetadata，auth.ts:2242-2249）</li>
     * </ol>
     */
    private AuthServerMetadata resolveMetadataForRefresh(String serverKey, String serverUrl,
            String configuredMetadataUrl) {
        AuthServerMetadata cached = metadataCache.get(serverKey);
        if (cached != null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 刷新复用内存缓存 metadata serverKey={} issuer={}",
                    serverKey, cached.issuer());
            }
            return cached;
        }
        AuthDiscoveryState state = readDiscoveryState(serverKey);
        if (state != null) {
            if (state.authorizationServerMetadata() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] 刷新复用持久化 metadata（零请求免重发现）serverKey={}", serverKey);
                }
                return state.authorizationServerMetadata();
            }
            if (state.authorizationServerUrl() != null && !state.authorizationServerUrl().isBlank()) {
                // 直连 AS URL 做 RFC 8414（SDK discoverAuthorizationServerMetadata 同款），
                // 不再对资源 URL 做 RFC 9728 PRM 探测（CC auth.ts:2236-2240）
                AuthServerMetadata as = httpClient.fetchAuthServer(
                    state.authorizationServerUrl(), AUTH_REQUEST_TIMEOUT_MS);
                if (as != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] 刷新直连持久化 AS URL 重发现 serverKey={} asUrl={}",
                            serverKey, state.authorizationServerUrl());
                    }
                    return as;
                }
            }
        }
        return discoverAuthServer(serverUrl, configuredMetadataUrl);
    }

    /**
     * [S6 OAuth-R5] 获取跨进程刷新锁 · CC original: {@code refreshAuthorization}（auth.ts:2094-2136）。
     *
     * <p>锁文件 = {@code {NexusaiPaths.getAppConfigHomeDir()}/mcp-refresh-{sanitizedKey}.lock}，
     * sanitizedKey = serverKey.replaceAll("[^a-zA-Z0-9]", "_")（CC auth.ts:2096，防非法文件名字符）。
     * Java 用 {@link FileChannel#tryLock()}（非阻塞）——另一进程持有 → 返回 null，视同 CC ELOCKED，
     * 睡 1000-2000ms 重试（CC auth.ts:2116-2122）；同 JVM 内另一线程已持有 →
     * {@link OverlappingFileLockException}，同样重试。其他错误 / 重试耗尽（{@value #MAX_LOCK_RETRIES} 次）
     * → 返回 null 不带锁继续（CC auth.ts:2124-2136 "proceeding without lock"）。
     *
     * <p><b>与 CC proper-lockfile 差异</b>：proper-lockfile 用 mtime+pid 检测崩溃残留锁
     * （onCompromised）；Java FileLock 是 OS 级锁，进程/JVM 退出自动释放，无需 stale 检测。
     *
     * @return 持有句柄（含 FileChannel + FileLock，须 {@code release()}）；null = 未获锁
     */
    private RefreshLock acquireRefreshLock(String serverKey) {
        String sanitizedKey = serverKey.replaceAll("[^a-zA-Z0-9]", "_");
        Path lockPath = Path.of(NexusaiPaths.getAppConfigHomeDir(),
            "mcp-refresh-" + sanitizedKey + ".lock");
        for (int retry = 0; retry < MAX_LOCK_RETRIES; retry++) {
            try {
                Files.createDirectories(lockPath.toAbsolutePath().getParent());
                FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("[McpAuth] 获取刷新锁成功 lockPath={}", lockPath);
                        }
                        return new RefreshLock(channel, lock, lockPath);
                    }
                    // tryLock 返回 null = 另一进程持有（CC ELOCKED，auth.ts:2116-2122）
                    channel.close();
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] 刷新锁被其他进程持有，等待重试 (attempt {}/{}) lockPath={}",
                            retry + 1, MAX_LOCK_RETRIES, lockPath);
                    }
                } catch (OverlappingFileLockException e) {
                    // 同 JVM 内重复加锁（CC lockfile 进程内 dedup 后的 ELOCKED 等价）
                    channel.close();
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] 刷新锁被同进程其他线程持有，等待重试 (attempt {}/{}) lockPath={}",
                            retry + 1, MAX_LOCK_RETRIES, lockPath);
                    }
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] 获取刷新锁失败: {}，不带锁继续（CC proceeding without lock）",
                        e.toString());
                }
                return null;
            }
            try {
                // CC auth.ts:2121 sleep(1000 + Math.random() * 1000)
                Thread.sleep(1000L + ThreadLocalRandom.current().nextLong(1001L));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] 重试 {} 次仍无法获取刷新锁，不带锁继续（CC auth.ts:2131-2136）",
                MAX_LOCK_RETRIES);
        }
        return null;
    }

    /** [S6 OAuth-R5] 跨进程刷新锁持有句柄（FileChannel + FileLock + lockPath 须同生命周期）。 */
    private static final class RefreshLock {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path lockPath;

        RefreshLock(FileChannel channel, FileLock lock, Path lockPath) {
            this.channel = channel;
            this.lock = lock;
            this.lockPath = lockPath;
        }

        /** 释放 OS 文件锁并关闭 channel；best-effort 删除锁文件（对齐 proper-lockfile unlock
         *  删除语义）。Windows 上删除被锁文件可能 AccessDenied，忽略即可——FileLock 只依赖
         *  OS 锁，锁文件残留不影响后续加锁。 */
        void release() {
            try {
                if (lock != null && lock.isValid()) {
                    lock.release();
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] 释放刷新锁异常: {}", e.toString());
                }
            }
            try {
                channel.close();
            } catch (IOException e) {
                // 关闭失败可忽略
            }
            try {
                Files.deleteIfExists(lockPath);
            } catch (Exception e) {
                // best-effort 删除失败可忽略（跨平台）
            }
        }
    }

    /** 序列化 discoveryState JSON · CC OAuthDiscoveryState 字段名（authorizationServerUrl/
     *  resourceMetadataUrl）+ 额外 authorizationServerMetadata 抽取值。 */
    private static String discoveryJson(AuthDiscoveryState state) throws JsonProcessingException {
        ObjectNode root = DISCOVERY_JSON.createObjectNode();
        root.put("authorizationServerUrl", state.authorizationServerUrl());
        if (state.resourceMetadataUrl() != null) {
            root.put("resourceMetadataUrl", state.resourceMetadataUrl());
        } else {
            root.putNull("resourceMetadataUrl");
        }
        AuthServerMetadata m = state.authorizationServerMetadata();
        if (m != null) {
            ObjectNode meta = root.putObject("authorizationServerMetadata");
            meta.put("authorizationEndpoint", m.authorizationEndpoint());
            meta.put("tokenEndpoint", m.tokenEndpoint());
            if (m.issuer() != null) {
                meta.put("issuer", m.issuer());
            }
            if (m.registrationEndpoint() != null) {
                meta.put("registrationEndpoint", m.registrationEndpoint());
            }
            if (m.scope() != null) {
                meta.put("scope", m.scope());
            }
        }
        return DISCOVERY_JSON.writeValueAsString(root);
    }

    /** 解析 discoveryState JSON → {@link AuthDiscoveryState}；字段缺失返回 null。 */
    private static AuthDiscoveryState parseDiscoveryState(String json) throws IOException {
        JsonNode root = DISCOVERY_JSON.readTree(json);
        String asUrl = root.path("authorizationServerUrl").isNull()
            ? null : root.path("authorizationServerUrl").asText(null);
        String resourceMetadataUrl = root.path("resourceMetadataUrl").isNull()
            ? null : root.path("resourceMetadataUrl").asText(null);
        AuthServerMetadata metadata = null;
        JsonNode meta = root.path("authorizationServerMetadata");
        if (!meta.isMissingNode() && !meta.isNull()) {
            metadata = new AuthServerMetadata(
                meta.path("authorizationEndpoint").asText(null),
                meta.path("tokenEndpoint").asText(null),
                meta.path("issuer").asText(null),
                meta.path("registrationEndpoint").asText(null),
                meta.path("scope").asText(null));
        }
        return new AuthDiscoveryState(asUrl, resourceMetadataUrl, metadata);
    }

    /**
     * <b>performMCPOAuthFlow 编排本体（Q-01 核心）</b> · 对齐 CC auth.ts:847-1342 标准路径（无 XAA）。
     *
     * <p>通用 RFC 6749/7636/8414 授权码流（public client + PKCE）：
     * <ol>
     *   <li>①PKCE — {@link McpOAuth#generateCodeVerifier()} + S256
     *       {@link McpOAuth#s256Challenge}</li>
     *   <li>②构建授权 URL — authorization_endpoint + client_id + redirect_uri +
     *       code_challenge + state（{@link McpOAuth#buildAuthorizationUrl}）</li>
     *   <li>③loopback 回调监听 — {@link OauthPort#findAvailablePort}/{@link OauthPort#buildRedirectUri}
     *       + {@link LoopbackCallbackHandler}（对齐 CC createServer+listen 早于浏览器打开）</li>
     *   <li>④state 校验 — 回调处理内 {@code state !== expected} → CSRF 拒绝（auth.ts:1110-1118）</li>
     *   <li>⑤授权码交换 — {@link OAuthHttpClient#exchangeCodeForTokens} POST token_endpoint 带
     *       code_verifier（RFC 7636 §4.5，真实实现 {@link DefaultOAuthHttpClient}）</li>
     *   <li>⑥token 持久化 — {@link McpOAuthTokenService}（DB，Q-01=A keychain→DB）；未注入时回退
     *       {@link SecureStorage}</li>
     *   <li>⑦5 分钟超时 — {@link #OAUTH_BROWSER_TIMEOUT_MS}（CC 5*60*1000）</li>
     * </ol>
     *
     * <p>L3 映射：CC 抛错 + telemetry reason → Java {@link AuthResult}（success/errorMessage/errorReason），
     * 失败原因码对齐 CC {@code MCPOAuthFlowErrorReason}（auth.ts:1265-1291）。
     * 无 clientInfo 且 AS 不支持 DCR（registration_endpoint 缺失）→ 返回 REGISTRATION_FAILED。
     *
     * @param serverName         MCP server 名
     * @param config             server 配置（type/url/headers/oauth 配置）
     * @param onAuthorizationUrl 授权 URL 回调（通知 UI，对齐 CC onAuthorizationUrl）
     * @param skipBrowserOpen    true 时不开浏览器（对齐 CC options.skipBrowserOpen）
     * @return AuthResult（success=true 且 tokens 已持久化；失败含 errorMessage/errorReason）
     */
    public AuthResult performMCPOAuthFlow(String serverName, OAuthServerConfig config,
            Consumer<String> onAuthorizationUrl, boolean skipBrowserOpen) {
        String serverKey = McpOAuth.getServerKey(serverName, config.type(), config.url(), config.headers());
        log.info("[McpAuth] performMCPOAuthFlow 启动 serverName={} serverKey={}",
            serverName, serverKey);

        // [OAuth-R1] step-up 复用：清除旧凭据前先读持久化 stepUpScope（CC auth.ts:903-909，
        // "The transport-attached auth provider persists scope when it receives a step-up 401，
        // so we can use it here instead of making an extra probe request"），重建授权 URL 时
        // 携带更高 scope 请求权限提升。
        String cachedStepUpScope = readStepUpScope(serverKey);
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] 复用 step-up scope serverKey={} stepUpScope={}", serverKey, cachedStepUpScope);
        }

        // CC: clearServerTokensFromLocalStorage 清除旧凭据保证全新注册（auth.ts:916）
        clearServerTokens(serverKey);

        try {
            // ③端口分配：配置回调端口优先，否则 findAvailablePort（CC auth.ts:959-962）
            int port = configuredCallbackPort(config.configuredCallbackPort());
            String redirectUri = oauthPort.buildRedirectUri(port);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 回调端口 port={} redirectUri={}", port, redirectUri);
            }

            // 发现 AS metadata（authorization_endpoint/token_endpoint）
            AuthServerMetadata metadata = discoverAuthServer(config.url(), config.authServerMetadataUrl());
            if (metadata == null) {
                // [OAuth-R4] 对齐 CC：顶层 fetchAuthServerMetadata 发现失败是 best-effort
                // （auth.ts:978-999 try/catch 后继续）；Java 侧无 SDK 二次发现，等价降级 =
                // 返回可恢复错误 AuthResult（reason=sdk_auth_failed，CC auth.ts:1288-1289
                // SDK discoverAuthorizationServerMetadata 失败归因）。不再走
                // METADATA_DISCOVERY_FAILED 硬终止。
                return errorResult(serverName,
                    "Failed to discover OAuth metadata for server '" + serverName + "'",
                    MCPOAuthFlowErrorReason.SDK_AUTH_FAILED);
            }
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 发现 OAuth metadata issuer={}", metadata.issuer());
            }

            // [S6 OAuth-R5] discoveryState 持久化：发现后即落库（对齐 CC SDK auth() 内发现后回调
            // saveDiscoveryState，auth.ts:1997-2035），下次刷新免重发现
            saveDiscoveryState(serverKey, serverName, config.url(), null, metadata);

            // ①PKCE + state
            String codeVerifier = McpOAuth.generateCodeVerifier();
            String codeChallenge = McpOAuth.s256Challenge(codeVerifier);
            String state = McpOAuth.generateState();

            // client_id 解析 · 对齐 CC clientInformation()（auth.ts:1482-1511）+ SDK DCR：
            // ①持久化存储已注册 clientId → ②预配置 config.clientId() → ③注入 Supplier →
            // ④RFC 7591 动态客户端注册（无 clientInfo 时 registerClient，auth.ts:1508-1510）
            ClientInfo clientInfo;
            try {
                clientInfo = resolveOrRegisterClient(serverName, serverKey, config, metadata, redirectUri);
            } catch (MCPRefreshFailed e) {
                // DCR 失败（AS 无 registration_endpoint / 注册请求失败）→ REGISTRATION_FAILED
                return errorResult(serverName, e.getMessage(), MCPOAuthFlowErrorReason.REGISTRATION_FAILED);
            }
            String clientId = clientInfo == null ? null : clientInfo.clientId();
            if (clientId == null || clientId.isBlank()) {
                return errorResult(serverName,
                    "No client info available for server '" + serverName + "'",
                    MCPOAuthFlowErrorReason.REGISTRATION_FAILED);
            }

            // ②构建授权 URL —— scope 透传（[S4 OAuth-R3]）
            // CC SDK 授权 URL scope 优先级（cli.js 内联 Fp1 签名：
            //   scope: z || O?.scopes_supported?.join(" ") || q.clientMetadata.scope）
            // ①z = wwwAuthParams.scope = cachedStepUpScope（step-up 复用更高 scope，auth.ts:932）；
            // ②q.clientMetadata.scope = getScopeFromMetadata(metadata)（auth.ts:1427-1434，
            //   经 SDK clientMetadata 落入授权 URL）。首次授权无 step-up 缓存 → 回退
            //   metadata.scope()（getScopeFromMetadata 产物：scope/default_scope/scopes_supported。
            //   scope 为空时 buildAuthorizationUrl 自动省略该参数）。
            String authUrlScope = (cachedStepUpScope != null && !cachedStepUpScope.isBlank())
                ? cachedStepUpScope
                : metadata.scope();
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 授权 URL scope 解析 authUrlScope={} stepUpScope={} metadataScope={}",
                    authUrlScope, cachedStepUpScope, metadata.scope());
            }
            String authUrl = McpOAuth.buildAuthorizationUrl(
                metadata.authorizationEndpoint(), clientId, redirectUri, codeChallenge, state,
                authUrlScope);

            // ③先绑定 loopback 回调监听（对齐 CC createServer+listen 早于浏览器打开，防回调丢失）
            if (callbackHandler instanceof LoopbackCallbackHandler loopback) {
                loopback.bind(port, state);
            }

            // 通知 UI + 开浏览器（CC redirectToAuthorization：先 onAuthorizationUrl 再 openBrowser）
            if (onAuthorizationUrl != null) {
                onAuthorizationUrl.accept(authUrl);
            }
            if (!skipBrowserOpen) {
                browserOpener.open(authUrl);
            } else if (log.isDebugEnabled()) {
                log.debug("[McpAuth] skipBrowserOpen=true 跳过浏览器打开");
            }

            // ③+④等待授权码（state 校验在回调处理器内）
            String authorizationCode = callbackHandler.waitForAuthorizationCode(
                state, OAUTH_BROWSER_TIMEOUT_MS);

            // ⑤授权码交换（真实 HTTP POST token_endpoint 带 code_verifier）
            Map<String, String> exchangeParams = new LinkedHashMap<>();
            exchangeParams.put("grant_type", "authorization_code");
            exchangeParams.put("code", authorizationCode);
            exchangeParams.put("redirect_uri", redirectUri);
            exchangeParams.put("client_id", clientId);
            exchangeParams.put("code_verifier", codeVerifier);
            Tokens tokens;
            try {
                tokens = httpClient.exchangeCodeForTokens(metadata.tokenEndpoint(), exchangeParams);
            } catch (Exception e) {
                return errorResult(serverName,
                    "Token exchange failed for server '" + serverName + "': " + e.getMessage(),
                    MCPOAuthFlowErrorReason.TOKEN_EXCHANGE_FAILED);
            }
            if (tokens == null) {
                return errorResult(serverName,
                    "Token exchange returned no tokens for server '" + serverName + "'",
                    MCPOAuthFlowErrorReason.TOKEN_EXCHANGE_FAILED);
            }

            // ⑥持久化 token（DB 或 SecureStorage）
            persistTokens(serverKey, serverName, config.url(), clientId, tokens);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] performMCPOAuthFlow 成功 serverName={} 有refreshToken={}",
                    serverName, tokens.refreshToken() != null);
            }
            return new AuthResult(true, tokens, null, null);
        } catch (Exception e) {
            return mapFlowError(serverName, e);
        }
    }

    /**
     * [OAuth-R4] 根 URL 判定 · CC original: auth.ts:304-306 {@code url.pathname === '/'}。
     * 根 URL 跳过 path-aware 备用发现（SDK 已探测同端点）；带路径分量（如
     * {@code http://host/api/mcp}）才走备用路径。非法 URL 保守视为根（跳过额外探测）。
     */
    private static boolean isRootUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() || path.equals("/");
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** 配置回调端口解析：CC serverConfig.oauth?.callbackPort（空/非法 → findAvailablePort）。 */
    private int configuredCallbackPort(String configured) {
        if (configured != null && !configured.isBlank()) {
            try {
                int p = Integer.parseInt(configured.trim());
                if (p > 0) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
                // 非法端口配置 → 回退 findAvailablePort
            }
        }
        return oauthPort.findAvailablePort();
    }

    /**
     * 解析或注册 MCP server 的 OAuth client 信息 · 对齐 CC {@code clientInformation()}
     * （auth.ts:1482-1511）+ SDK DCR（auth.js:917-942）。
     *
     * <p>优先级：①持久化存储已注册 clientId（DB/SecureStorage）→ ②预配置
     * {@code config.clientId()} → ③注入 {@code clientInfoSupplier} → ④RFC 7591
     * 动态客户端注册（无 clientInfo 时）。④失败抛 {@link MCPRefreshFailed}，
     * 由调用方映射 REGISTRATION_FAILED。
     *
     * @param serverName MCP server 名（CC client_name 模板）
     * @param serverKey  getServerKey 产物（client 信息持久化键）
     * @param config     server 配置（预配置 clientId）
     * @param metadata   已发现 AS metadata（registration_endpoint + scope）
     * @param redirectUri 回调 redirect_uri（注册请求 redirect_uris）
     */
    private ClientInfo resolveOrRegisterClient(String serverName, String serverKey,
            OAuthServerConfig config, AuthServerMetadata metadata, String redirectUri) {
        // ① 持久化存储（CC storedInfo，auth.ts:1488-1495）
        ClientInfo persisted = readPersistedClientInfo(serverKey);
        if (persisted != null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 复用持久化 clientId serverKey={}", serverKey);
            }
            return persisted;
        }
        // ② 预配置 clientId（CC serverConfig.oauth.clientId，auth.ts:1497-1506）
        if (config.clientId() != null && !config.clientId().isBlank()) {
            String secret = tokenStore == null ? null : tokenStore.getClientSecret(serverKey);
            return new ClientInfo(config.clientId(), secret, null);
        }
        // ③ 注入 Supplier（现有扩展点，R1 保留）
        ClientInfo supplied = clientInfoSupplier.get();
        if (supplied != null && supplied.clientId() != null && !supplied.clientId().isBlank()) {
            return supplied;
        }
        // ④ RFC 7591 DCR（CC clientInformation() undefined → SDK registerClient，auth.ts:1508-1510）
        return registerClientForServer(serverName, serverKey, config, metadata, redirectUri);
    }

    /** 读取持久化 client 信息 · CC clientInformation() storedInfo（DB 优先，SecureStorage 回退）。 */
    private ClientInfo readPersistedClientInfo(String serverKey) {
        if (tokenStore != null) {
            McpOAuthToken t = tokenStore.read(serverKey);
            if (t != null && t.getClientId() != null && !t.getClientId().isBlank()) {
                return new ClientInfo(t.getClientId(), t.getClientSecret(), null);
            }
            return null;
        }
        Map<String, Object> stored = tokenStorage.read();
        Object clientId = stored == null ? null : stored.get("clientId");
        if (clientId == null || clientId.toString().isBlank()) {
            return null;
        }
        Object secret = stored == null ? null : stored.get("clientSecret");
        return new ClientInfo(clientId.toString(), secret == null ? null : secret.toString(), null);
    }

    /**
     * RFC 7591 动态客户端注册 · 对齐 CC clientMetadata getter（auth.ts:1417-1437）+
     * SDK registerClient（auth.js:917-942）。
     *
     * <p>registration_endpoint 缺失 → 抛 "Incompatible auth server..."（SDK auth.js:920-922）；
     * 注册成功 → {@link #persistClientInfo} 落库（③ client_id 持久化复用，
     * CC saveClientInformation auth.ts:1513-1538）。
     */
    private ClientInfo registerClientForServer(String serverName, String serverKey,
            OAuthServerConfig config, AuthServerMetadata metadata, String redirectUri) {
        String registrationEndpoint = metadata.registrationEndpoint();
        if (registrationEndpoint == null || registrationEndpoint.isBlank()) {
            throw new MCPRefreshFailed(MCPRefreshFailureReason.REQUEST_FAILED,
                "Incompatible auth server: does not support dynamic client registration for server '"
                    + serverName + "'");
        }
        ClientRegistrationRequest request = new ClientRegistrationRequest(
            "Claude Code (" + serverName + ")",
            java.util.List.of(redirectUri),
            java.util.List.of("authorization_code", "refresh_token"),
            java.util.List.of("code"),
            "none",
            metadata.scope());
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] DCR 动态注册 client serverName={} registrationEndpoint={} scope={}",
                serverName, registrationEndpoint, metadata.scope());
        }
        ClientInfo registered = httpClient.registerClient(registrationEndpoint, request);
        if (registered == null || registered.clientId() == null || registered.clientId().isBlank()) {
            throw new MCPRefreshFailed(MCPRefreshFailureReason.REQUEST_FAILED,
                "DCR returned no client_id for server '" + serverName + "'");
        }
        persistClientInfo(serverKey, serverName, config.url(), registered);
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] DCR 注册成功并持久化 serverName={} clientId={}",
                serverName, registered.clientId());
        }
        return registered;
    }

    /** 持久化 client 信息（DB 或 SecureStorage）· 对齐 CC saveClientInformation（auth.ts:1513-1538）。 */
    private void persistClientInfo(String serverKey, String serverName, String serverUrl,
            ClientInfo info) {
        if (tokenStore != null) {
            // [S6 OAuth-R5] 保留已有 discoveryState/stepUpScope（CC saveClientInformation spread
            // 现有条目，auth.ts:1520-1533 "…existingData.mcpOAuth?.[serverKey]…"）
            McpOAuthToken existing = tokenStore.read(serverKey);
            McpOAuthToken t = new McpOAuthToken();
            t.setServerKey(serverKey);
            t.setServerName(serverName);
            t.setServerUrl(serverUrl);
            t.setClientId(info.clientId());
            t.setClientSecret(info.clientSecret());
            t.setDiscoveryState(existing == null ? null : existing.getDiscoveryState());
            t.setStepUpScope(existing == null ? null : existing.getStepUpScope());
            tokenStore.save(t);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] DCR client info 已持久化到 DB serverKey={}", serverKey);
            }
            return;
        }
        Map<String, Object> stored = new HashMap<>(tokenStorage.read());
        stored.put("serverKey", serverKey);
        stored.put("serverName", serverName);
        stored.put("serverUrl", serverUrl);
        stored.put("clientId", info.clientId());
        stored.put("clientSecret", info.clientSecret() == null ? "" : info.clientSecret());
        tokenStorage.write(stored);
    }

    /**
     * [OAuth-R1] 读取持久化 stepUpScope（CC cachedStepUpScope，auth.ts:908-909）。
     * DB 优先（tokenStore），SecureStorage 回退；无记录返回 null。
     *
     * <p>transport 层在收到 403+insufficient_scope 时经
     * {@link com.nexusai.application.agent.mcp.McpAuthHeaderProvider#markStepUpPending}
     * 写入该列；本方法在 performMCPOAuthFlow 清除旧凭据前读取，供授权 URL scope 参数使用。
     */
    private String readStepUpScope(String serverKey) {
        if (tokenStore != null) {
            McpOAuthToken t = tokenStore.read(serverKey);
            return t == null ? null : t.getStepUpScope();
        }
        Object stored = tokenStorage.read().get("stepUpScope");
        return stored == null ? null : stored.toString();
    }

    /**
     * 清旧凭据 · CC clearServerTokensFromLocalStorage（auth.ts:620-634）：删除整个
     * {@code mcpOAuth[serverKey]} 条目（DB delete / SecureStorage 清全部凭据字段）。
     *
     * <p>WHY：CC 在 performMCPOAuthFlow 开头清旧凭据保证「全新注册」——含 clientId/clientSecret
     * （auth.ts:913 注释 "Clear any existing stored credentials to ensure fresh client registration"）。
     * DB 模式 delete 整行天然覆盖；SecureStorage 模式需显式清 clientId/clientSecret，否则旧
     * clientId 会被 {@link #readPersistedClientInfo} 复用，偏离 CC 每次 flow 重新 DCR 语义。
     */
    private void clearServerTokens(String serverKey) {
        if (tokenStore != null) {
            tokenStore.delete(serverKey);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 已清除 DB 旧凭据 serverKey={}", serverKey);
            }
            return;
        }
        Map<String, Object> stored = new HashMap<>(tokenStorage.read());
        stored.put("accessToken", "");
        stored.put("refreshToken", "");
        stored.put("expiresAt", 0L);
        stored.put("clientId", "");
        stored.put("clientSecret", "");
        tokenStorage.write(stored);
    }

    /**
     * [S03 R2-04 X-2] 精细凭据失效 · CC original: {@code ClaudeAuthProvider.invalidateCredentials}
     * （auth.ts:1960-1995，scope: 'all' | 'client' | 'tokens' | 'verifier' | 'discovery'）。
     *
     * <ul>
     *   <li><b>all</b> — 删除整条记录（DB delete / SecureStorage 清全部凭据字段，
     *       CC auth.ts:1972-1974 delete mcpOAuth[serverKey]）</li>
     *   <li><b>client</b> — 清 clientId/clientSecret 列 + 预配置 client_secret
     *       （{@code mcp_oauth_client_config} 表，CC auth.ts:1975-1978）</li>
     *   <li><b>tokens</b> — accessToken='' / refreshToken=null / expiresAt=0
     *       （CC auth.ts:1979-1983）——_doRefresh invalid_grant 清凭据路径用</li>
     *   <li><b>discovery</b> — 清 discoveryState + stepUpScope（CC auth.ts:1987-1990）</li>
     *   <li><b>verifier</b> — no-op：Java PKCE code_verifier 为 performMCPOAuthFlow
     *       方法局部变量（无实例字段，CC auth.ts:1984-1986 this._codeVerifier 内存态）</li>
     * </ul>
     *
     * @param serverKey {@link McpOAuth#getServerKey} 产物
     * @param scope     all / client / tokens / discovery / verifier
     */
    public void invalidateCredentials(String serverKey, String scope) {
        if (scope == null) {
            return;
        }
        if (tokenStore == null) {
            // SecureStorage 单 token 模型（refreshAuthorization 路径）
            Map<String, Object> stored = new HashMap<>(tokenStorage.read());
            switch (scope) {
                case "all" -> {
                    stored.put("accessToken", "");
                    stored.put("refreshToken", "");
                    stored.put("expiresAt", 0L);
                    stored.put("clientId", "");
                    stored.put("clientSecret", "");
                    stored.remove("discoveryState");
                    stored.remove("stepUpScope");
                }
                case "client" -> {
                    stored.put("clientId", "");
                    stored.put("clientSecret", "");
                }
                case "tokens" -> {
                    stored.put("accessToken", "");
                    stored.put("refreshToken", "");
                    stored.put("expiresAt", 0L);
                }
                case "discovery" -> {
                    stored.remove("discoveryState");
                    stored.remove("stepUpScope");
                }
                case "verifier" -> {
                    // no-op：Java code_verifier 为方法局部变量（CC auth.ts:1984-1986 内存态）
                }
                default -> {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] invalidateCredentials 未知 scope={}", scope);
                    }
                    return;
                }
            }
            tokenStorage.write(stored);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] invalidateCredentials（SecureStorage）scope={}", scope);
            }
            return;
        }
        McpOAuthToken t = tokenStore.read(serverKey);
        if (t == null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] invalidateCredentials 无记录（跳过）serverKey={} scope={}", serverKey, scope);
            }
            return;
        }
        switch (scope) {
            case "all" -> tokenStore.delete(serverKey);
            case "client" -> {
                t.setClientId(null);
                t.setClientSecret(null);
                tokenStore.save(t);
                // 预配置 client_secret 二级回退（mcp_oauth_client_config 表）同步清理
                tokenStore.clearClientSecret(serverKey);
            }
            case "tokens" -> {
                t.setAccessToken("");
                t.setRefreshToken(null);
                t.setExpiresAt(0L);
                tokenStore.save(t);
            }
            case "discovery" -> {
                t.setDiscoveryState(null);
                t.setStepUpScope(null);
                tokenStore.save(t);
            }
            case "verifier" -> {
                // no-op：Java code_verifier 为方法局部变量（CC auth.ts:1984-1986 内存态）
            }
            default -> {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] invalidateCredentials 未知 scope={}", scope);
                }
                return;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpAuth] invalidateCredentials serverKey={} scope={}", serverKey, scope);
        }
    }
    /**
     * [S03 R2-04 X-5] 服务端 token 吊销全链 · CC original: {@code revokeServerTokens}
     * （auth.ts:467-618）。
     *
     * <p>流程（RFC 7009）：
     * <ol>
     *   <li>AS URL = discoveryState.authorizationServerUrl ?? serverUrl（XAA/PRM 发现时 AS
     *       与 MCP URL 异主，auth.ts:482-485）→ metadata 发现（RFC 8414）</li>
     *   <li>revocation_endpoint 缺失 → log「Server does not support token revocation」
     *       （auth.ts:499-501）；存在 → authMethod 取 revocation_endpoint_auth_methods_supported
     *       ?? token_endpoint_auth_methods_supported（auth.ts:503-517）</li>
     *   <li><b>refresh token 先</b>（长命凭据，防后续 access token 生成；多数 AS 隐式失效关联
     *       access token，auth.ts:523-524）、<b>access token 后</b>（auth.ts:545-546）；
     *       每步 log-and-continue（auth.ts:536-542/:558-563）</li>
     *   <li><b>恒清本地凭据</b>（无论服务端吊销结果，auth.ts:575-576）</li>
     *   <li>{@code preserveStepUpState=true} → 保留 stepUpScope/discoveryState（仅 URL 剥离
     *       legacy 大 metadata，auth.ts:581-617 #30337）</li>
     * </ol>
     *
     * <p>整个吊销段 best-effort（异常 log 不抛，auth.ts:567-570）；REST logout 入口接线
     * 见 {@code McpServerService.logout}（登记缺口：S03 写集不含 Controller，见
     * 09-open-decisions）。
     *
     * @param serverName        MCP server 名（日志/保留记录）
     * @param serverKey         {@link McpOAuth#getServerKey} 产物
     * @param serverUrl         MCP server 资源 URL（AS URL 回退）
     * @param preserveStepUpState true = 保留 step-up 认证态（scope + discovery）供下次重授权
     */
    public void revokeServerTokens(String serverName, String serverKey, String serverUrl,
            boolean preserveStepUpState) {
        if (serverKey == null) {
            return;
        }
        McpOAuthToken tokenData = readTokenForRevoke(serverKey);
        if (tokenData == null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] revokeServerTokens 无凭据记录 serverKey={}", serverKey);
            }
            return;
        }
        boolean hasAccess = tokenData.getAccessToken() != null && !tokenData.getAccessToken().isBlank();
        boolean hasRefresh = tokenData.getRefreshToken() != null && !tokenData.getRefreshToken().isBlank();
        if (hasAccess || hasRefresh) {
            try {
                // XAA（及 PRM 发现的 auth）AS 与 MCP URL 异主——优先持久化 discoveryState 的
                // AS URL（auth.ts:482-485 discoveryState?.authorizationServerUrl ?? serverConfig.url）
                AuthDiscoveryState discovery = readDiscoveryState(serverKey);
                String asUrl = (discovery != null && discovery.authorizationServerUrl() != null
                    && !discovery.authorizationServerUrl().isBlank())
                    ? discovery.authorizationServerUrl() : serverUrl;
                AuthServerMetadata metadata = httpClient.fetchAuthServer(asUrl, AUTH_REQUEST_TIMEOUT_MS);
                if (metadata == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuth] revokeServerTokens 无 OAuth metadata（跳过服务端吊销）server={} asUrl={}",
                            serverName, asUrl);
                    }
                } else {
                    String revocationEndpoint = metadata.revocationEndpoint();
                    if (revocationEndpoint == null || revocationEndpoint.isBlank()) {
                        if (log.isDebugEnabled()) {
                            log.debug("[McpAuth] revokeServerTokens server 不支持 token 吊销（无 revocation_endpoint）server={}",
                                serverName);
                        }
                    } else {
                        String authMethod = pickRevocationAuthMethod(metadata);
                        if (log.isDebugEnabled()) {
                            log.debug("[McpAuth] revokeServerTokens 经 {} 吊销（{}）server={}",
                                revocationEndpoint, authMethod, serverName);
                        }
                        // refresh token 先（重要——防未来 access token 生成，auth.ts:523-524）
                        if (hasRefresh) {
                            try {
                                revokeTokenRfc7009(serverName, revocationEndpoint,
                                    tokenData.getRefreshToken(), "refresh_token",
                                    tokenData.getClientId(), tokenData.getClientSecret(),
                                    tokenData.getAccessToken(), authMethod);
                            } catch (Exception e) {
                                // log-and-continue（auth.ts:536-542）
                                log.warn("[McpAuth] 吊销 refresh token 失败（继续）server={}: {}",
                                    serverName, e.getMessage());
                            }
                        }
                        // access token 后（可能已被 refresh 吊销隐式失效，auth.ts:545-546）
                        if (hasAccess) {
                            try {
                                revokeTokenRfc7009(serverName, revocationEndpoint,
                                    tokenData.getAccessToken(), "access_token",
                                    tokenData.getClientId(), tokenData.getClientSecret(),
                                    tokenData.getAccessToken(), authMethod);
                            } catch (Exception e) {
                                log.warn("[McpAuth] 吊销 access token 失败（继续）server={}: {}",
                                    serverName, e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 吊销 best-effort：log 不抛（auth.ts:567-570）
                log.warn("[McpAuth] revokeServerTokens 服务端吊销失败（best-effort）server={}: {}",
                    serverName, e.getMessage());
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[McpAuth] revokeServerTokens 无 token 可吊销 server={}", serverName);
        }
        // 恒清本地凭据，无论服务端吊销结果（CC auth.ts:575-576 clearServerTokensFromLocalStorage）
        clearServerTokens(serverKey);
        if (tokenStore != null) {
            tokenStore.clearClientSecret(serverKey);
        }
        // 重新认证时保留 step-up 认证态（scope + discovery，CC auth.ts:581-617）
        if (preserveStepUpState
                && (tokenData.getStepUpScope() != null || tokenData.getDiscoveryState() != null)) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] revokeServerTokens 保留 step-up 认证态 serverKey={}", serverKey);
            }
            if (tokenStore != null) {
                McpOAuthToken preserved = new McpOAuthToken();
                preserved.setServerKey(serverKey);
                preserved.setServerName(serverName);
                preserved.setServerUrl(serverUrl);
                preserved.setAccessToken("");
                preserved.setExpiresAt(0L);
                if (tokenData.getStepUpScope() != null) {
                    preserved.setStepUpScope(tokenData.getStepUpScope());
                }
                if (tokenData.getDiscoveryState() != null) {
                    // 剥离 legacy 大 metadata 字段（CC auth.ts:602-609，#30337）
                    preserved.setDiscoveryState(
                        stripLegacyDiscoveryState(tokenData.getDiscoveryState()));
                }
                tokenStore.save(preserved);
            } else {
                Map<String, Object> stored = new HashMap<>(tokenStorage.read());
                if (tokenData.getStepUpScope() != null) {
                    stored.put("stepUpScope", tokenData.getStepUpScope());
                }
                if (tokenData.getDiscoveryState() != null) {
                    stored.put("discoveryState",
                        stripLegacyDiscoveryState(tokenData.getDiscoveryState()));
                }
                tokenStorage.write(stored);
            }
        }
    }

    /**
     * RFC 7009 §2.1 单 token 吊销 · CC original: {@code revokeToken}（auth.ts:381-459）。
     *
     * <p>客户端认证按 RFC 6749 §2.3：{@code client_secret_basic}（Basic 头，URL-encoded）/
     * {@code client_secret_post}（body）双法（XAA 恒为 confidential client，严格 AS
     * （Okta/Stytch）拒绝 public-client 吊销，auth.ts:408-410）；仅 client_id → body
     * （auth.ts:421-422）；无 client creds → log 继续（server 可能拒绝，auth.ts:423-428）。
     * 401 → 清 body client creds 换 {@code Authorization: Bearer accessToken} 回退
     * （RFC 6749 §2.3.1 单一认证方法；非 RFC 7009 服务器兼容，auth.ts:434-450）。
     *
     * @throws MCPRefreshFailed 吊销失败（非 401 或 401 无 accessToken）——调用方 log-and-continue
     */
    private void revokeTokenRfc7009(String serverName, String endpoint, String token, String tokenTypeHint,
            String clientId, String clientSecret, String accessToken, String authMethod) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("token", token);
        params.put("token_type_hint", tokenTypeHint);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        boolean hasClientId = clientId != null && !clientId.isBlank();
        boolean hasClientSecret = clientSecret != null && !clientSecret.isBlank();
        if (hasClientId && hasClientSecret) {
            if ("client_secret_post".equals(authMethod)) {
                params.put("client_id", clientId);
                params.put("client_secret", clientSecret);
            } else {
                String basic = Base64.getEncoder().encodeToString(
                    (URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
                        + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + basic);
            }
        } else if (hasClientId) {
            params.put("client_id", clientId);
        } else if (log.isDebugEnabled()) {
            log.debug("[McpAuth] revokeTokenRfc7009 无 client_id（server 可能拒绝）server={} hint={}",
                serverName, tokenTypeHint);
        }
        try {
            httpClient.revokeToken(endpoint, params, headers);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] 成功吊销 {} server={}", tokenTypeHint, serverName);
            }
        } catch (MCPRefreshFailed e) {
            // 非 RFC 7009 服务器 401 → Bearer 回退（auth.ts:434-450）
            if (e.httpStatus() == 401 && accessToken != null && !accessToken.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] 吊销 401，换 Bearer 回退 server={} hint={}", serverName, tokenTypeHint);
                }
                // RFC 6749 §2.3.1：不得同时发送多认证方法——清 body client creds
                params.remove("client_id");
                params.remove("client_secret");
                Map<String, String> bearerHeaders = new LinkedHashMap<>(headers);
                bearerHeaders.put("Authorization", "Bearer " + accessToken);
                httpClient.revokeToken(endpoint, params, bearerHeaders);
                if (log.isDebugEnabled()) {
                    log.debug("[McpAuth] Bearer 吊销成功 server={} hint={}", serverName, tokenTypeHint);
                }
                return;
            }
            throw e;
        }
    }

    /** revoke 用 token 数据读取（DB 优先；SecureStorage 模式构造临时对象）。 */
    private McpOAuthToken readTokenForRevoke(String serverKey) {
        if (tokenStore != null) {
            return tokenStore.read(serverKey);
        }
        Map<String, Object> s = tokenStorage.read();
        Object at = s.get("accessToken");
        Object rt = s.get("refreshToken");
        if ((at == null || at.toString().isEmpty()) && (rt == null || rt.toString().isEmpty())) {
            return null;
        }
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey(serverKey);
        Object name = s.get("serverName");
        Object url = s.get("serverUrl");
        Object cid = s.get("clientId");
        Object csec = s.get("clientSecret");
        Object exp = s.get("expiresAt");
        Object scope = s.get("scope");
        Object ds = s.get("discoveryState");
        Object sus = s.get("stepUpScope");
        t.setServerName(name == null ? null : name.toString());
        t.setServerUrl(url == null ? null : url.toString());
        t.setAccessToken(at == null ? null : at.toString());
        t.setRefreshToken(rt == null ? null : rt.toString());
        t.setClientId(cid == null ? null : cid.toString());
        t.setClientSecret(csec == null ? null : csec.toString());
        t.setExpiresAt(exp instanceof Number n ? n.longValue() : 0L);
        t.setScope(scope == null ? null : scope.toString());
        t.setDiscoveryState(ds == null ? null : ds.toString());
        t.setStepUpScope(sus == null ? null : sus.toString());
        return t;
    }

    /** RFC 7009 authMethod 选择 · CC auth.ts:505-517：
     *  revocation_endpoint_auth_methods_supported ?? token_endpoint_auth_methods_supported；
     *  无 basic 有 post → post，否则 basic。 */
    private static String pickRevocationAuthMethod(AuthServerMetadata metadata) {
        java.util.List<String> methods = metadata.revocationEndpointAuthMethods() != null
            ? metadata.revocationEndpointAuthMethods()
            : metadata.tokenEndpointAuthMethods();
        if (methods != null && !methods.contains("client_secret_basic")
                && methods.contains("client_secret_post")) {
            return "client_secret_post";
        }
        return "client_secret_basic";
    }

    /** 剥离 discoveryState 中 legacy 大 metadata 字段，仅保留 URL（CC auth.ts:602-609，#30337）。 */
    private static String stripLegacyDiscoveryState(String json) {
        try {
            AuthDiscoveryState state = parseDiscoveryState(json);
            if (state == null) {
                return json;
            }
            ObjectNode root = DISCOVERY_JSON.createObjectNode();
            if (state.authorizationServerUrl() != null) {
                root.put("authorizationServerUrl", state.authorizationServerUrl());
            }
            if (state.resourceMetadataUrl() != null) {
                root.put("resourceMetadataUrl", state.resourceMetadataUrl());
            }
            return root.toString();
        } catch (IOException e) {
            return json; // 解析失败原样保留（best-effort）
        }
    }

    /** ⑥token 持久化：DB（McpOAuthTokenService）/ SecureStorage 回退。 */
    private void persistTokens(String serverKey, String serverName, String serverUrl,
            String clientId, Tokens tokens) {
        if (tokenStore != null) {
            // [S6 OAuth-R5] 保留已有 discoveryState/stepUpScope（CC saveTokens spread 现有条目，
            // auth.ts:1714-1728 "…existingData.mcpOAuth?.[serverKey]…"；否则 save 全字段覆盖
            // 会清空 discovery_state / step_up_scope 列，破坏 discoveryState 持久化）
            McpOAuthToken existing = tokenStore.read(serverKey);
            McpOAuthToken t = new McpOAuthToken();
            t.setServerKey(serverKey);
            t.setServerName(serverName);
            t.setServerUrl(serverUrl);
            t.setAccessToken(tokens.accessToken());
            t.setRefreshToken(tokens.refreshToken());
            t.setExpiresAt(tokens.expiresAt());
            t.setScope(tokens.scope());
            t.setClientId(clientId);
            t.setDiscoveryState(existing == null ? null : existing.getDiscoveryState());
            t.setStepUpScope(existing == null ? null : existing.getStepUpScope());
            tokenStore.save(t);
            if (log.isDebugEnabled()) {
                log.debug("[McpAuth] token 已持久化到 DB serverKey={}", serverKey);
            }
            return;
        }
        Map<String, Object> stored = new HashMap<>(tokenStorage.read());
        stored.put("serverKey", serverKey);
        stored.put("serverName", serverName);
        stored.put("serverUrl", serverUrl);
        stored.put("accessToken", tokens.accessToken());
        stored.put("refreshToken", tokens.refreshToken());
        stored.put("expiresAt", tokens.expiresAt());
        stored.put("scope", tokens.scope() == null ? "" : tokens.scope());
        stored.put("clientId", clientId);
        tokenStorage.write(stored);
    }

    /** 失败归因 · CC auth.ts:1265-1291 消息 → reason 映射。 */
    private AuthResult mapFlowError(String serverName, Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        MCPOAuthFlowErrorReason reason;
        if (msg.contains("Authentication timeout")) {
            reason = MCPOAuthFlowErrorReason.AUTH_TIMEOUT;
        } else if (msg.contains("OAuth state mismatch")) {
            reason = MCPOAuthFlowErrorReason.STATE_MISMATCH;
        } else if (msg.contains("OAuth error:")) {
            reason = MCPOAuthFlowErrorReason.PROVIDER_DENIED;
        } else if (msg.contains("already in use") || msg.contains("EADDRINUSE")
                || msg.contains("callback server failed") || msg.contains("No available port")) {
            reason = MCPOAuthFlowErrorReason.PORT_UNAVAILABLE;
        } else if (msg.contains("Failed to discover OAuth metadata")
                || msg.contains("AS fetch failed")
                || msg.contains("authServerMetadataUrl must use https")) {
            // [OAuth-R4] 对齐 CC auth.ts:1288-1289：SDK discoverAuthorizationServerMetadata
            // 失败归因 sdk_auth_failed（含 configuredMetadataUrl 文档获取失败/非 https）
            reason = MCPOAuthFlowErrorReason.SDK_AUTH_FAILED;
        } else {
            reason = MCPOAuthFlowErrorReason.UNKNOWN;
        }
        log.warn("[McpAuth] performMCPOAuthFlow 失败 serverName={} reason={}: {}", serverName, reason, msg);
        return new AuthResult(false, null, msg, reason);
    }

    private AuthResult errorResult(String serverName, String message, MCPOAuthFlowErrorReason reason) {
        log.warn("[McpAuth] performMCPOAuthFlow 失败 serverName={} reason={}: {}",
            serverName, reason, message);
        return new AuthResult(false, null, message, reason);
    }

    private static class NullHttpClient implements OAuthHttpClient {
        public ProtectedResourceMetadata fetchProtectedResource(String u, long t) { return null; }
        public AuthServerMetadata fetchAuthServer(String u, long t) { return null; }
        public ClientInfo registerClient(String e, ClientRegistrationRequest r) { return null; }
        public Tokens exchangeCodeForTokens(String e, Map<String, String> p) { return null; }
        public Tokens refreshTokens(String e, Map<String, String> p) { return null; }
        public void revokeToken(String e, Map<String, String> p, Map<String, String> h) {
            // no-op：NullHttpClient 不执行网络操作（测试/降级环境）
        }
    }
}
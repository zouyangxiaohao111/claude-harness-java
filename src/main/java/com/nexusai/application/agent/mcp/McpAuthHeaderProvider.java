package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.api.OAuth401Refresher;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MCP server OAuth Bearer token 提供者 · 对齐 CC MCP SDK {@code OAuthClientProvider}
 * {@code tokens()} 语义 + 401 → {@code refreshAuthorization} → retry 语义。
 *
 * <p>承载 S2「Bearer 注入 + 401-refresh 接线」（Q-01 接线）：
 * <ol>
 *   <li><b>token 读取</b> — {@link McpOAuthTokenService}（DB，Q-01=A keychain→DB）按
 *       {@link McpOAuth#getServerKey} 读 accessToken/refreshToken/expiresAt/clientId。
 *       使 McpOAuthTokenService 从 0 消费变为生产接线（transport 层消费）。</li>
 *   <li><b>主动刷新</b> — CC {@code ClaudeAuthProvider.tokens()}（auth.ts:1540-1700）：
 *       expiresIn ≤ 300s 且有 refreshToken → {@link McpAuth#refreshServerToken} 主动刷新
 *       （避免失败请求后的刷新延迟；auth.ts:1587-1591 注释 "proactively refreshing before
 *       expiry provides a smoother user experience"）。expired 且无 refreshToken → null
 *       （server 401 → needs-auth 降级）。</li>
 *   <li><b>401 刷新</b> — CC MCP SDK transport 401 → {@code refreshAuthorization} → 重试一次；
 *       复用 {@link OAuth401Refresher}（CC {@code handleOAuth401Error} 的 per-token pending
 *       去重 + 强制刷新，auth.ts:1360-1392）。per-server refresher 缓存于 {@link #refreshers}。</li>
 *   <li><b>[S5] 按需 OAuth 流</b> — {@link #performOAuthFlow}：transport 连接期 401 且 refresh
 *       无法恢复时触发 {@link McpAuth#performMCPOAuthFlow}（授权码 + PKCE + loopback 回调），
 *       对齐 CC SSE OAuth 路径 client.ts:621-660（SDK auth() 等价）。</li>
 * </ol>
 *
 * <p>serverKey 计算：{@link McpOAuth#getServerKey(serverName, type, url, headers)}；
 * remote transport 的 headers 承载于 {@link McpTransport.TransportConfig#env()}
 * （McpServerService upsert 时把远程 server 的 headers 存入 env 保留）。
 *
 * <p>McpAuth 非 Spring bean → provider 自建实例（DB tokenStore + 真实
 * {@link DefaultOAuthHttpClient} + {@link OauthPort}）。
 */
@Component
public class McpAuthHeaderProvider {

    private static final Logger log = LoggerFactory.getLogger(McpAuthHeaderProvider.class);

    /** CC tokens(): expiresIn ≤ 300s → proactive refresh（auth.ts:1590）。 */
    static final long PROACTIVE_REFRESH_THRESHOLD_SECONDS = 300L;

    /**
     * [OAuth-R1] WWW-Authenticate scope 提取 regex · CC original: auth.ts:1365
     * {@code /scope=(?:"([^"]+)"|([^\s,]+))/}（RFC 6750 §3 允许带引号/不带引号值）。
     */
    static final java.util.regex.Pattern WWW_AUTH_SCOPE_PATTERN =
        java.util.regex.Pattern.compile("scope=(?:\"([^\"]+)\"|([^\\s,]+))");

    private final McpOAuthTokenService tokenStore;
    private final McpAuth mcpAuth;
    /** per-server OAuth401Refresher（key=serverKey）：TokenStore/TokenRefresher 绑定该 server。 */
    private final Map<String, OAuth401Refresher> refreshers = new ConcurrentHashMap<>();

    public McpAuthHeaderProvider(McpOAuthTokenService tokenStore) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore is required");
        // McpAuth 自建：DB tokenStore + 真实 OAuthHttpClient（refresh/performMCPOAuthFlow 共用）
        // + LoopbackCallbackHandler（[S5] transport 按需触发 OAuth 流时 loopback 回调完成授权码）。
        this.mcpAuth = new McpAuth(null, new DefaultOAuthHttpClient(),
            u -> {}, new LoopbackCallbackHandler(), null, tokenStore, new OauthPort());
    }

    /**
     * [OAuth-R1] 标记 server step-up pending · 对齐 CC {@code wrapFetchWithStepUpDetection}
     * → {@code ClaudeAuthProvider.markStepUpPending}（auth.ts:1354-1374 + :1468-1471）。
     *
     * <p>传输层收到 HTTP 403 + {@code WWW-Authenticate: ...insufficient_scope...} 时调用：
     * 把 server 请求的更高 scope 持久化到 token 存储（DB {@code step_up_scope} 列）。此后：
     * <ul>
     *   <li>{@link #resolveAccessToken} 计算 needsStepUp → 跳过无效主动刷新（RFC 6749 §6 禁止
     *       refresh 提升 scope，刷新只会返回同 scope token 仍 403，auth.ts:1649 注释
     *       "refreshing can't elevate scope"）</li>
     *   <li>{@link McpAuth#performMCPOAuthFlow} 重授权时读取该 scope 并附加到授权 URL，
     *       请求更高权限（CC auth.ts:1884-1900 redirectToAuthorization 持久化 + auth.ts:903-935
     *       performMCPOAuthFlow 复用）</li>
     * </ul>
     *
     * @param config transport 配置（serverName/type/command/env）
     * @param scope  server 请求的更高 scope（CC wrapFetchWithStepUpDetection 提取值）
     */
    public void markStepUpPending(McpTransport.TransportConfig config, String scope) {
        if (config == null || config.serverName() == null || scope == null || scope.isBlank()) {
            return;
        }
        String serverKey = serverKey(config);
        McpOAuthToken token = tokenStore.read(serverKey);
        if (token == null) {
            // CC markStepUpPending 只置内存 _pendingStepUpScope；Java 持久化到 DB 需要一条记录
            // （无记录则建最小记录仅承载 stepUpScope，供后续 OAuth 重授权读取）
            token = new McpOAuthToken();
            token.setServerKey(serverKey);
            token.setServerName(config.serverName());
            token.setServerUrl(url(config));
        }
        token.setStepUpScope(scope);
        tokenStore.save(token);
        if (log.isDebugEnabled()) {
            log.debug("[McpAuthHeaderProvider] 标记 step-up pending serverKey={} scope={}", serverKey, scope);
        }
    }

    /**
     * [OAuth-R1] 从 WWW-Authenticate 提取 scope 值 · CC original: auth.ts:1365
     * {@code wwwAuth.match(/scope=(?:"([^"]+)"|([^\s,]+))/)}，取 group1（带引号）或 group2
     * （不带引号）。RFC 6750 §3 允许两种形式（SDK extractFieldFromWwwAuth 同款）。
     *
     * @param wwwAuthHeader WWW-Authenticate 头原始值（可为 null）
     * @return 提取的 scope；无匹配返回 null
     */
    static String extractScopeFromWwwAuthenticate(String wwwAuthHeader) {
        if (wwwAuthHeader == null) {
            return null;
        }
        java.util.regex.Matcher m = WWW_AUTH_SCOPE_PATTERN.matcher(wwwAuthHeader);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /**
     * [OAuth-R1] needsStepUp 判定 · 对齐 CC {@code ClaudeAuthProvider.tokens()}
     * （auth.ts:1625-1637）：
     * <pre>
     *   const currentScopes = tokenData.scope?.split(' ') ?? []
     *   const needsStepUp = this._pendingStepUpScope !== undefined &amp;&amp;
     *     this._pendingStepUpScope.split(' ').some(s =&gt; !currentScopes.includes(s))
     * </pre>
     * Java 把 pending scope 持久化到 DB {@code step_up_scope} 列；当前 token scope 未包含任一
     * 请求 scope → true。scope 为 null 时视作空集合（CC {@code tokenData.scope?.split(' ') ?? []}）
     * → 恒需 step-up。
     *
     * @param token 当前 token 记录（可为 null）
     * @return true = step-up pending 且当前 scope 未覆盖 → 需更高权限重授权
     */
    private static boolean needsStepUp(McpOAuthToken token) {
        if (token == null) {
            return false;
        }
        String pending = token.getStepUpScope();
        if (pending == null || pending.isBlank()) {
            return false;
        }
        String current = token.getScope();
        Set<String> currentScopes = (current == null || current.isBlank())
            ? Set.of()
            : new HashSet<>(Arrays.asList(current.trim().split("\\s+")));
        for (String s : pending.trim().split("\\s+")) {
            if (!s.isBlank() && !currentScopes.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析当前 Bearer access token · 对齐 CC {@code ClaudeAuthProvider.tokens()}
     * （auth.ts:1540-1700）。
     *
     * <p>返回非 null = 可附加 {@code Authorization: Bearer <token>}；返回 null = 无 token
     * （不发 Authorization 头，server 401 → needs-auth 降级）。
     *
     * @param config transport 配置（serverName/type/command/env）
     * @return 当前 access token；无 token / 过期无 refreshToken 返回 null
     */
    public String resolveAccessToken(McpTransport.TransportConfig config) {
        if (config == null || config.serverName() == null) {
            return null;
        }
        String serverKey = serverKey(config);
        McpOAuthToken token = tokenStore.read(serverKey);
        if (token == null || token.getAccessToken() == null || token.getAccessToken().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuthHeaderProvider] 无 accessToken serverKey={}", serverKey);
            }
            return null;
        }
        long expiresAt = token.getExpiresAt() == null ? 0L : token.getExpiresAt();
        long expiresInSec = (expiresAt - System.currentTimeMillis()) / 1000L;
        boolean hasRefresh = token.getRefreshToken() != null && !token.getRefreshToken().isBlank();
        // [OAuth-R1] needsStepUp 计算 · 对齐 CC tokens()（auth.ts:1625-1637）：step-up pending
        // 且当前 token scope 未覆盖请求 scope → true。
        boolean stepUpPending = needsStepUp(token);
        if (stepUpPending && log.isDebugEnabled()) {
            log.debug("[McpAuthHeaderProvider] needsStepUp=true serverKey={} stepUpScope={} currentScope={}",
                serverKey, token.getStepUpScope(), token.getScope());
        }
        // CC tokens(): expiresIn <= 0 且无 refreshToken → undefined（auth.ts:1581-1584）
        if (expiresInSec <= 0 && !hasRefresh) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuthHeaderProvider] token 过期且无 refreshToken，返回 null（server 将 401 → needs-auth）serverKey={}", serverKey);
            }
            return null;
        }
        // CC tokens(): expiresIn <= 300 且有 refreshToken → 主动刷新（auth.ts:1586-1591）
        // step-up pending 时跳过 —— RFC 6749 §6 禁止 refresh 提升 scope，刷新只会返回同 scope
        // token，后续仍 403（auth.ts:1650 "Skip when step-up is pending — refreshing can't
        // elevate scope"；SDK 因 refresh_token 被省略而走 PKCE 重授权）。
        if (expiresInSec <= PROACTIVE_REFRESH_THRESHOLD_SECONDS && hasRefresh && !stepUpPending) {
            try {
                McpAuth.Tokens refreshed = mcpAuth.refreshServerToken(serverKey, url(config), null);
                if (refreshed != null && refreshed.accessToken() != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpAuthHeaderProvider] 主动刷新成功（expiresIn={}s）serverKey={}", expiresInSec, serverKey);
                    }
                    return refreshed.accessToken();
                }
            } catch (Exception e) {
                log.warn("[McpAuthHeaderProvider] 主动刷新失败，返回现存 token serverKey={}: {}",
                    serverKey, e.getMessage());
            }
        }
        return token.getAccessToken();
    }

    /**
     * 401 → 强制刷新 → 新 token · 对齐 CC MCP SDK transport 401 →
     * {@code refreshAuthorization} → 重试一次 + {@code handleOAuth401Error} per-token dedup。
     *
     * @param config            transport 配置
     * @param failedAccessToken 被 401 拒绝的 access token
     * @return 刷新后的新 token；无 token / 刷新失败返回 null（调用方维持 401 降级）
     */
    public String refreshAndGetAccessToken(McpTransport.TransportConfig config, String failedAccessToken) {
        if (config == null || config.serverName() == null || failedAccessToken == null) {
            return null;
        }
        String serverKey = serverKey(config);
        // [OAuth-R1] step-up pending 时跳过刷新 —— RFC 6749 §6 禁止 refresh 提升 scope，刷新
        // 只会返回同 scope token 仍被拒（CC authInternal 因 tokens() 省略 refresh_token 而直接
        // 走 PKCE 重授权；Java 等价 = 返回 null → 调用方 401 降级 needs-auth，重授权流携带
        // stepUpScope 请求更高权限，auth.ts:1649-1690 语义）。
        McpOAuthToken current = tokenStore.read(serverKey);
        if (needsStepUp(current)) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuthHeaderProvider] 401 但 step-up pending，跳过无效刷新 serverKey={} stepUpScope={}",
                    serverKey, current == null ? null : current.getStepUpScope());
            }
            return null;
        }
        OAuth401Refresher refresher = refreshers.computeIfAbsent(serverKey, k -> new OAuth401Refresher(
            () -> readTokens(serverKey),
            () -> forceRefresh(serverKey, config)));
        boolean changed = refresher.handle401(failedAccessToken);
        if (!changed) {
            if (log.isDebugEnabled()) {
                log.debug("[McpAuthHeaderProvider] 401 刷新未产生新 token（无法恢复）serverKey={}", serverKey);
            }
            return null;
        }
        McpOAuthToken token = tokenStore.read(serverKey);
        return token == null ? null : token.getAccessToken();
    }

    /**
     * <b>[S5] 按需触发 S1 OAuth 流</b> · 对齐 CC SSE OAuth 路径 client.ts:621-660
     * （SSE transport 连接 401 → SDK auth() → performMCPOAuthFlow 等价）。
     *
     * <p>transport（SseMcpTransport 等）连接期收到 401 且 {@link #refreshAndGetAccessToken} 无法
     * 恢复（无 refreshToken / 刷新失败）时调用：走完整授权码流（PKCE + loopback 回调 + token
     * 持久化到 DB）。成功返回 success=true 且新 token 已落 DB → 调用方
     * {@code resolveAccessToken(config)} 取新 token 重试连接。
     *
     * <p>OAuthServerConfig 构造与 {@link #serverKey} 对齐：type/command(url)/headers=env，
     * 保证 performMCPOAuthFlow 写入的 serverKey 与 resolveAccessToken 读取的 serverKey 一致。
     *
     * @param config             transport 配置（serverName/type/command/env）
     * @param onAuthorizationUrl 授权 URL 回调（CC onAuthorizationUrl，可 null；通知 UI/日志）
     * @return {@link McpAuth.AuthResult}（success=false 含 errorReason/errorMessage）
     */
    public McpAuth.AuthResult performOAuthFlow(McpTransport.TransportConfig config,
            Consumer<String> onAuthorizationUrl) {
        if (config == null || config.serverName() == null) {
            return new McpAuth.AuthResult(false, null,
                "config missing for OAuth flow", McpAuth.MCPOAuthFlowErrorReason.UNKNOWN);
        }
        McpAuth.OAuthServerConfig oauthCfg = new McpAuth.OAuthServerConfig(
            config.type(), config.command(),
            // env 可能含 __mcp_oauth__ 镜像保留键 → 按 headers 语义剥除（否则保留键被当 HTTP 头发送）
            McpOAuth.headersOnly(config.env()),
            null, null, null);
        if (log.isDebugEnabled()) {
            log.debug("[McpAuthHeaderProvider] 触发 OAuth 流 serverName={} type={}",
                config.serverName(), config.type());
        }
        return mcpAuth.performMCPOAuthFlow(config.serverName(), oauthCfg, onAuthorizationUrl, false);
    }

    /** serverKey 计算 · 对齐 McpAuth.performMCPOAuthFlow 的 getServerKey（headers=env for remote）。 */
    private String serverKey(McpTransport.TransportConfig config) {
        // 剥除 __mcp_oauth__ 镜像保留键：env 承载 headers + oauth 镜像，serverKey 必须以真实
        // headers 计算（与 saveClientSecret 用 config.headers 同键，否则凭据读错行）
        Map<String, String> headers = McpOAuth.headersOnly(config.env());
        return McpOAuth.getServerKey(config.serverName(), config.type(), config.command(), headers);
    }

    private static String url(McpTransport.TransportConfig config) {
        return config.command();
    }

    /** {@link OAuth401Refresher.TokenStore} · 从 DB 读当前 tokens（CC getClaudeAIOAuthTokensAsync 等价）。 */
    private OAuth401Refresher.OAuthTokens readTokens(String serverKey) {
        McpOAuthToken token = tokenStore.read(serverKey);
        if (token == null) {
            return null;
        }
        String at = token.getAccessToken();
        String rt = token.getRefreshToken();
        if ((at == null || at.isBlank()) && (rt == null || rt.isBlank())) {
            return null;
        }
        return new OAuth401Refresher.OAuthTokens(at, rt);
    }

    /** {@link OAuth401Refresher.TokenRefresher} · 强制刷新（CC checkAndRefreshOAuthTokenIfNeeded(0,true) 等价）。 */
    private boolean forceRefresh(String serverKey, McpTransport.TransportConfig config) {
        try {
            // [S6 OAuth-R5] force=true：401 强制刷新跳过"另一进程已刷新"过期门（CC utils/auth.ts:1456
            // "Skip this check if force=true (server already told us token is bad)"）——server 已拒绝
            // 当前 token，即使本地未过期也必须真正刷新，否则 401 循环。
            McpAuth.Tokens tokens = mcpAuth.refreshServerToken(serverKey, url(config), null, true);
            boolean ok = tokens != null && tokens.accessToken() != null;
            if (log.isDebugEnabled()) {
                log.debug("[McpAuthHeaderProvider] 401 强制刷新 serverKey={} success={}", serverKey, ok);
            }
            return ok;
        } catch (Exception e) {
            log.warn("[McpAuthHeaderProvider] 401 强制刷新失败 serverKey={}: {}", serverKey, e.getMessage());
            return false;
        }
    }
}

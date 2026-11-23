package com.nexusai.domain.connector;

import com.nexusai.application.agent.mcp.DefaultOAuthHttpClient;
import com.nexusai.application.agent.mcp.McpAuth;
import com.nexusai.application.agent.mcp.McpOAuth;
import com.nexusai.application.agent.oauth.OAuthTokenClient;
import com.nexusai.application.agent.oauth.OAuthTokenResponse;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.model.connector.dto.PollResponse;
import com.nexusai.model.connector.dto.StartResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 连接器授权码 + loopback 回调流程（start / poll / 换 token）。
 *
 * <p>复用 PKCE/state（{@link McpOAuth}）、discovery + DCR（{@link DefaultOAuthHttpClient}）、
 * token 交换（{@link OAuthTokenClient}）、按 state 路由的回调监听（{@link ConnectorCallbackServer}）。
 * ⛔ 不用 {@code McpAuth.performMCPOAuthFlow}（同步阻塞 300s，形状也不匹配 start/poll HTTP）。
 */
@Component
public class ConnectorAuthFlow {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAuthFlow.class);

    static final long STATE_TTL_MS = 5 * 60 * 1000L;
    static final long OUTCOME_TTL_MS = 15 * 60 * 1000L;
    private static final long DISCOVERY_TIMEOUT_MS = 15_000L;

    private final ConnectorCatalog catalog;
    private final ConnectorCredentialService credentials;
    private final ConnectorCallbackServer callbackServer;
    private final ConnectorMcpBinding mcpBinding;
    private final OAuthTokenClient tokenClient = new OAuthTokenClient();
    private final DefaultOAuthHttpClient httpClient = new DefaultOAuthHttpClient();
    private final ExecutorService exchangeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, FlowOutcome> outcomes = new ConcurrentHashMap<>();

    public ConnectorAuthFlow(ConnectorCatalog catalog,
                             ConnectorCredentialService credentials,
                             ConnectorCallbackServer callbackServer,
                             ConnectorMcpBinding mcpBinding) {
        this.catalog = catalog;
        this.credentials = credentials;
        this.callbackServer = callbackServer;
        this.mcpBinding = mcpBinding;
    }

    /** 流程结果（内存，短 TTL）。 */
    record FlowOutcome(String state, String connectorKey, String status,
                       String message, String account, long updatedAt) {
    }

    /** discovery 产物。 */
    record AuthEndpoints(String authorizationEndpoint, String tokenEndpoint,
                         String registrationEndpoint, String scope) {
    }

    /** 发起授权：发现端点 →（DCR/内置 client）→ PKCE → 返回授权 URL + state。 */
    public StartResponse start(String key) {
        ConnectorDefinition def = catalog.require(key);
        // [2026-11-23] 30 条目录分档门：只有 supported 才能走一键授权（unavailable 置灰原因、
        // credentialOnly 只能保存凭据），避免「能点但点了报 500」的假入口。
        if (def.unavailable()) {
            throw new ValidationException(
                    "连接器「" + def.name() + "」暂不可用：" + def.unavailableMessage());
        }
        if (!def.supported()) {
            throw new ValidationException(
                    "连接器「" + def.name() + "」只支持保存凭据，暂不支持一键授权");
        }
        if (!def.isOAuth()) {
            throw new ValidationException("连接器「" + def.name() + "」不是 OAuth 授权类型，请改用手动凭据");
        }
        AuthEndpoints ep = resolveEndpoints(def);
        if (ep == null) {
            throw new BadGatewayException("无法发现 " + key + " 的 OAuth 授权端点（issuer/discovery 不可达）");
        }
        String redirectUri;
        try {
            callbackServer.ensureStarted();
            redirectUri = callbackServer.redirectUri();
        } catch (Exception e) {
            throw new BadGatewayException("无法启动 loopback 回调监听：" + e.getMessage());
        }

        ConnectorCredential existing = credentials.find(key).orElse(null);
        String clientId = existing == null ? null : existing.getClientId();
        String clientSource = existing == null ? null : existing.getClientSource();
        String clientSecret = existing == null ? null : credentials.decrypt(existing.getClientSecretEncrypted());
        // [2026-11-23] DCR 客户端把 redirect_uri 绑定在注册时：回调端口变化（34118 被占回落随机端口、
        // 或重启后端口不同）⇒ 旧 client 不可用，必须重新注册。catalog/manual client 不能重注册，
        // 保留原值（provider 侧若不接受该 redirect，会在授权页报错，由用户/运维处理）。
        String storedRedirect = existing == null ? null : existing.getRedirectUri();
        if ("dcr".equals(clientSource) && !isBlank(storedRedirect) && !storedRedirect.equals(redirectUri)) {
            log.info("[ConnectorAuth] redirect_uri 变化 {} → {}，重新注册 DCR 客户端 connector={}",
                    storedRedirect, redirectUri, key);
            clientId = null;
            clientSecret = null;
            clientSource = null;
        }
        if (isBlank(clientId) && !isBlank(def.clientId())) {
            clientId = def.clientId();
            clientSource = "catalog";
        }
        if (isBlank(clientId)) {
            McpAuth.ClientInfo info = registerClient(def, ep, redirectUri);
            clientId = info.clientId();
            clientSecret = info.clientSecret();
            clientSource = "dcr";
        }

        String scope = !def.scopeString().isBlank() ? def.scopeString() : ep.scope();
        String state = McpOAuth.generateState();
        String verifier = McpOAuth.generateCodeVerifier();
        String challenge = McpOAuth.s256Challenge(verifier);
        String authorizeUrl = McpOAuth.buildAuthorizationUrl(
                ep.authorizationEndpoint(), clientId, redirectUri, challenge, state, scope);

        long expiresAt = System.currentTimeMillis() + STATE_TTL_MS;
        ConnectorCallbackServer.PendingFlow flow = new ConnectorCallbackServer.PendingFlow(
                key, verifier, clientId, clientSecret, ep.authorizationEndpoint(), ep.tokenEndpoint(),
                ep.registrationEndpoint(), redirectUri, scope,
                Boolean.TRUE.equals(def.tokenExpires()), expiresAt);
        CompletableFuture<ConnectorCallbackServer.CallbackResult> future =
                callbackServer.register(state, flow, STATE_TTL_MS);
        credentials.markPending(key, def.authType(), state, expiresAt, redirectUri,
                clientId, clientSource, clientSecret);

        pruneOutcomes();
        outcomes.put(state, new FlowOutcome(state, key, "PENDING", null, null, System.currentTimeMillis()));
        future.whenCompleteAsync((result, error) -> completeFlow(state, def, flow, result, error),
                exchangeExecutor);

        log.info("[ConnectorAuth] 发起授权 connector={} clientId={} redirect={}", key, clientId, redirectUri);
        return new StartResponse(authorizeUrl, state, redirectUri, 2, (int) (STATE_TTL_MS / 1000));
    }

    private McpAuth.ClientInfo registerClient(ConnectorDefinition def, AuthEndpoints ep, String redirectUri) {
        if (isBlank(ep.registrationEndpoint())) {
            if (def.manualClientRequired()) {
                throw new ValidationException(
                        "连接器「" + def.name() + "」需要先填写 clientId/clientSecret（POST /credentials）");
            }
            throw new ValidationException("授权服务器未提供动态客户端注册，请先填写 clientId/clientSecret");
        }
        try {
            return httpClient.registerClient(ep.registrationEndpoint(),
                    new McpAuth.ClientRegistrationRequest(
                            "NexusAI (" + def.key() + ")",
                            List.of(redirectUri),
                            List.of("authorization_code", "refresh_token"),
                            List.of("code"),
                            "none",
                            !def.scopeString().isBlank() ? def.scopeString() : ep.scope()));
        } catch (McpAuth.MCPRefreshFailed e) {
            throw new BadGatewayException("动态客户端注册失败：" + e.getMessage());
        }
    }

    /**
     * 轮询结果 · <b>DB 是唯一真源</b>（[2026-11-23] 状态机统一）。
     *
     * <p>先 {@link ConnectorCredentialService#reconcileExpired} 对账，再按 DB status 映射：
     * connected→AUTHORIZED；error/reauth_required→ERROR；pending（state 匹配且未过期）→PENDING。
     * 内存 outcome 不再参与判定（只保留诊断），避免「poll PENDING / 目录 error」两套口径。
     */
    public PollResponse poll(String key, String state) {
        if (state == null || state.isBlank()) {
            return new PollResponse("ERROR", ConnectorCredentialService.AUTH_EXPIRED_MESSAGE, null);
        }
        ConnectorCredential c = credentials.reconcileExpired(
                credentials.find(key).orElse(null));
        if (c != null && "connected".equals(c.getStatus())) {
            return new PollResponse("AUTHORIZED", null, c.getAccount());
        }
        if (c != null && ("error".equals(c.getStatus()) || "reauth_required".equals(c.getStatus()))) {
            return new PollResponse("ERROR",
                    c.getLastError() == null || c.getLastError().isBlank()
                            ? ConnectorCredentialService.AUTH_EXPIRED_MESSAGE : c.getLastError(),
                    null);
        }
        if (c != null && "pending".equals(c.getStatus()) && state.equals(c.getState())) {
            return new PollResponse("PENDING", null, null);
        }
        return new PollResponse("ERROR", ConnectorCredentialService.AUTH_EXPIRED_MESSAGE, null);
    }

    /** 回调到达后：换 token → 绑定 mcp server → 落库。 */
    private void completeFlow(String state, ConnectorDefinition def,
                              ConnectorCallbackServer.PendingFlow flow,
                              ConnectorCallbackServer.CallbackResult result, Throwable error) {
        try {
            if (error != null) {
                fail(state, def, "授权回调失败：" + rootMessage(error));
                return;
            }
            if (result == null) {
                fail(state, def, "授权回调为空");
                return;
            }
            if (result.hasError()) {
                fail(state, def, result.error());
                return;
            }
            ConnectorOAuthProviderConfig cfg = new ConnectorOAuthProviderConfig(
                    def.key(), flow.authorizationEndpoint(), flow.tokenEndpoint(), flow.clientId(),
                    flow.clientSecret(), def.scopes(), flow.redirectUri(), flow.tokenExpires());
            Map<String, String> params = cfg.exchangeParams(
                    result.code(), flow.redirectUri(), flow.codeVerifier());
            OAuthTokenResponse tokens =
                    tokenClient.exchangeCodeForTokens(flow.tokenEndpoint(), params, cfg);
            // 先绑定 mcp server：同名冲突就让整次连接失败，而不是存了 token 却用不上
            mcpBinding.ensureServer(def);
            credentials.markConnected(def.key(), def.authType(), flow.tokenEndpoint(),
                    tokens.scope(), tokens.expiresAt(), tokens.accessToken(), tokens.refreshToken());
            outcomes.put(state, new FlowOutcome(state, def.key(), "AUTHORIZED", null, null,
                    System.currentTimeMillis()));
            log.info("[ConnectorAuth] 授权成功 connector={}", def.key());
        } catch (Exception e) {
            log.warn("[ConnectorAuth] 授权流程失败 connector={}: {}", def.key(), e.toString());
            fail(state, def, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void fail(String state, ConnectorDefinition def, String message) {
        credentials.markError(def.key(), def.authType(), message);
        outcomes.put(state, new FlowOutcome(state, def.key(), "ERROR", message, null,
                System.currentTimeMillis()));
    }

    /**
     * 用 refresh_token 刷新（status/test 在 token 过期或 401 时调用）。
     *
     * @return 刷新成功返回 true；无刷新条件/失败返回 false
     */
    boolean tryRefresh(ConnectorCredential c, ConnectorDefinition def) {
        if (c == null || isBlank(c.getTokenEndpoint()) || isBlank(c.getRefreshTokenEncrypted())) {
            return false;
        }
        String refreshToken = credentials.decrypt(c.getRefreshTokenEncrypted());
        if (isBlank(refreshToken)) {
            return false;
        }
        try {
            ConnectorOAuthProviderConfig cfg = new ConnectorOAuthProviderConfig(
                    def.key(), null, c.getTokenEndpoint(), c.getClientId(),
                    credentials.decrypt(c.getClientSecretEncrypted()), def.scopes(),
                    c.getRedirectUri(), false);
            OAuthTokenResponse tokens = tokenClient.refreshTokens(
                    c.getTokenEndpoint(), cfg.refreshParams(refreshToken), cfg);
            credentials.updateTokens(c, tokens.accessToken(), tokens.refreshToken(),
                    tokens.expiresAt(), tokens.scope());
            log.info("[ConnectorAuth] token 刷新成功 connector={}", def.key());
            return true;
        } catch (Exception e) {
            log.warn("[ConnectorAuth] token 刷新失败 connector={}: {}", def.key(), e.toString());
            // 与运行时凭据源同一状态口径：刷新失败 = 需用户重新授权（connected=false）
            credentials.markReauthRequired(def.key(), def.authType(), "token 刷新失败：" + e.getMessage());
            return false;
        }
    }

    /** discovery：catalog 显式端点优先；否则按 issuer 走 RFC 8414 well-known。 */
    AuthEndpoints resolveEndpoints(ConnectorDefinition def) {
        if (!isBlank(def.authorizationEndpoint()) && !isBlank(def.tokenEndpoint())) {
            return new AuthEndpoints(def.authorizationEndpoint(), def.tokenEndpoint(), null, null);
        }
        String entry = def.discoveryEntry();
        if (entry == null) {
            return null;
        }
        McpAuth.AuthServerMetadata meta = httpClient.fetchAuthServer(entry, DISCOVERY_TIMEOUT_MS);
        if (meta == null || isBlank(meta.authorizationEndpoint()) || isBlank(meta.tokenEndpoint())) {
            return null;
        }
        return new AuthEndpoints(meta.authorizationEndpoint(), meta.tokenEndpoint(),
                meta.registrationEndpoint(), meta.scope());
    }

    private void pruneOutcomes() {
        long cutoff = System.currentTimeMillis() - OUTCOME_TTL_MS;
        outcomes.values().removeIf(o -> o.updatedAt() < cutoff);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

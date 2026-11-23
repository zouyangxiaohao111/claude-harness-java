package com.nexusai.domain.connector;

import com.nexusai.application.agent.oauth.OAuthTokenClient;
import com.nexusai.application.agent.oauth.OAuthTokenResponse;
import com.nexusai.model.connector.ConnectorCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 连接器加密凭据实现（{@link ConnectorTokenProvider}）。
 *
 * <p>token 只从 {@code connector_credentials} 读，CryptoUtil 解密后交给传输层；
 * 过期（{@code expires_at <= now}）或有 refresh_token 时用 {@link OAuthTokenClient#refreshTokens}
 * 刷新一次并回写密文。刷新失败 → 标记 {@code reauth_required}，返回 managed+null
 * （传输层不发 Authorization → server 401 → 前端提示重新授权）。
 *
 * <p>⛔ 不在这里写 {@code mcp_oauth_tokens}（明文欠账表）；旧表只作为无连接器记录时的回退。
 */
@Service
public class ConnectorCredentialTokenProvider implements ConnectorTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCredentialTokenProvider.class);

    private final ConnectorCredentialService credentials;
    /** 复用账号域 token 客户端（form-urlencoded + provider 感知解析）。 */
    private final OAuthTokenClient tokenClient = new OAuthTokenClient();

    public ConnectorCredentialTokenProvider(ConnectorCredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public Result resolve(String connectorKey) {
        if (isBlank(connectorKey)) {
            return Result.notManaged();
        }
        ConnectorCredential c = credentials.find(connectorKey).orElse(null);
        if (c == null) {
            return Result.notManaged();
        }
        // 行存在但从未拿到 token（pending/unconnected/error 且无 refresh）→ 不接管，交回旧源
        if (isBlank(c.getAccessTokenEncrypted()) && isBlank(c.getRefreshTokenEncrypted())) {
            return Result.notManaged();
        }
        // 已被标记需要重新授权：不返回旧 token（避免把过期 token 继续发出去）
        if ("reauth_required".equals(c.getStatus())) {
            return Result.reauthRequired(c.getStatus());
        }
        boolean expired = c.getExpiresAt() != null && c.getExpiresAt() <= System.currentTimeMillis();
        if (!expired) {
            String token = credentials.decrypt(c.getAccessTokenEncrypted());
            if (!isBlank(token)) {
                return Result.connected(token);
            }
        }
        String refreshed = doRefresh(c);
        if (!isBlank(refreshed)) {
            return Result.connected(refreshed);
        }
        return Result.reauthRequired("reauth_required");
    }

    @Override
    public String forceRefresh(String connectorKey) {
        if (isBlank(connectorKey)) {
            return null;
        }
        ConnectorCredential c = credentials.find(connectorKey).orElse(null);
        if (c == null || isBlank(c.getRefreshTokenEncrypted()) || isBlank(c.getTokenEndpoint())) {
            return null;
        }
        return doRefresh(c);
    }

    /** 刷新一次并回写；成功返回新 token，失败标记 reauth_required 并返回 null。 */
    private String doRefresh(ConnectorCredential c) {
        String refreshToken = credentials.decrypt(c.getRefreshTokenEncrypted());
        if (isBlank(refreshToken) || isBlank(c.getTokenEndpoint())) {
            credentials.markReauthRequired(c.getConnectorKey(), c.getAuthType(),
                    "缺少 refresh_token 或 token_endpoint，无法刷新");
            return null;
        }
        try {
            ConnectorOAuthProviderConfig cfg = new ConnectorOAuthProviderConfig(
                    c.getConnectorKey(), null, c.getTokenEndpoint(), c.getClientId(),
                    credentials.decrypt(c.getClientSecretEncrypted()),
                    List.of(), c.getRedirectUri(), false);
            OAuthTokenResponse tokens = tokenClient.refreshTokens(
                    c.getTokenEndpoint(), cfg.refreshParams(refreshToken), cfg);
            if (tokens == null || isBlank(tokens.accessToken())) {
                credentials.markReauthRequired(c.getConnectorKey(), c.getAuthType(),
                        "刷新响应缺少 access_token");
                return null;
            }
            credentials.updateTokens(c, tokens.accessToken(), tokens.refreshToken(),
                    tokens.expiresAt(), tokens.scope());
            log.info("[ConnectorToken] token 刷新成功 connector={}", c.getConnectorKey());
            return tokens.accessToken();
        } catch (Exception e) {
            log.warn("[ConnectorToken] token 刷新失败 connector={}: {}", c.getConnectorKey(), e.toString());
            credentials.markReauthRequired(c.getConnectorKey(), c.getAuthType(),
                    "token 刷新失败：" + e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

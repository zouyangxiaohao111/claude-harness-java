package com.nexusai.domain.connector;

import com.nexusai.application.agent.oauth.OAuthProviderConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器 OAuth provider 配置 · 复用账号域 {@link OAuthProviderConfig} 抽象。
 *
 * <p>照 {@code CodexOAuthProviderConfig} 的形态：只声明 provider 语义（端点 / client / scope），
 * 不新增 Spring bean（⛔ 不注册成 bean —— {@code RemoteTriggerTool} 注入
 * {@code List<OAuthProviderConfig>}，多一个 bean 会静默改变它的刷新行为）。
 *
 * <p><b>token 生命周期声明</b>：{@code tokenExpires} 来自 catalog，缺省 false
 * （token 响应不一定带 {@code expires_in}；此时按「不过期/未知」处理，避免解析器 fail-loud
 * 把授权成功判成失败）。过期后的恢复靠 401 → refresh 或重新授权。
 */
public final class ConnectorOAuthProviderConfig implements OAuthProviderConfig {

    private final String provider;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final List<String> scopes;
    private final String redirectUri;
    private final boolean tokenExpires;

    public ConnectorOAuthProviderConfig(String provider,
                                        String authorizationEndpoint,
                                        String tokenEndpoint,
                                        String clientId,
                                        String clientSecret,
                                        List<String> scopes,
                                        String redirectUri,
                                        boolean tokenExpires) {
        this.provider = provider;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
        this.redirectUri = redirectUri;
        this.tokenExpires = tokenExpires;
    }

    @Override public String provider() { return provider; }
    @Override public String authorizationEndpoint() { return authorizationEndpoint; }
    @Override public String tokenEndpoint() { return tokenEndpoint; }
    @Override public String userInfoEndpoint() { return null; }
    @Override public String clientId() { return clientId; }
    @Override public String clientSecret() { return clientSecret; }
    @Override public List<String> scopes() { return scopes; }
    @Override public String redirectUri() { return redirectUri; }

    @Override
    public boolean accessTokenExpires() {
        return tokenExpires;
    }

    @Override
    public boolean supportsRefreshToken() {
        return true;
    }

    /** RFC 6749 §4.1.3 授权码交换参数（公开客户端 + PKCE；confidential client 才带 client_secret）。 */
    @Override
    public Map<String, String> exchangeParams(String code, String redirectUri, String codeVerifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", redirectUri);
        params.put("client_id", clientId);
        params.put("code_verifier", codeVerifier);
        if (clientSecret != null && !clientSecret.isBlank()) {
            params.put("client_secret", clientSecret);
        }
        return params;
    }

    /** RFC 6749 §6 刷新参数。 */
    public Map<String, String> refreshParams(String refreshToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) {
            params.put("client_secret", clientSecret);
        }
        return params;
    }
}

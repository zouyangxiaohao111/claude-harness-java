package com.nexusai.application.agent.oauth;

import com.nexusai.infra.llm.ProviderConfigAssembler;
import java.util.List;

/**
 * Google 订阅（Gemini Code Assist / Cloud Code）登录链路的公开常量。
 *
 * <p><b>常量来源</b>：{@code google-gemini/gemini-cli} main @
 * {@code 08e9a20f749aefcbda38622ac757899c3d6216e0}，{@code packages/core/src/code_assist/oauth2.ts}：
 * client_id :76-77、client_secret :85（Google 官方注释说明 installed-app 的 secret
 * 「not treated as a secret」，但本仓仍按敏感值处理：⛔ 不进日志 / 报告 / 错误文案）、
 * scopes :88-92。本类不持有任何用户凭据。
 *
 * <p><b>与 Claude 链的关键差异</b>：
 * <ul>
 *   <li><b>无 PKCE</b>：官方浏览器流就是裸 authorization_code + 随机 state（oauth2.ts:535-658），
 *       ⛔ 不要照搬 Claude 的 {@code code_challenge} 参数；</li>
 *   <li><b>动态端口</b>：redirect_uri = {@code http://127.0.0.1:<临时端口>/oauth2callback}
 *       （oauth2.ts:538-543、端口分配 :660-691），故 {@link #redirectUri()} 只给占位值，实际值由
 *       调用方按本次登录的端口显式传入；</li>
 *   <li><b>token 端点吃 form-urlencoded</b>（{@link OAuthTokenClient} 的形态），不是 Claude 的 JSON。</li>
 * </ul>
 */
public final class GoogleOAuthProviderConfig implements OAuthProviderConfig {

    /** 无状态常量实例（Spring 不注入本类；OAuthTokenClient 需要 provider 配置对象）。 */
    public static final GoogleOAuthProviderConfig INSTANCE = new GoogleOAuthProviderConfig();

    private GoogleOAuthProviderConfig() {
    }

    /** provider 标识（仅用于日志区分；登录态按 provider id 落库）。 */
    public static final String PROVIDER = "google-subscription";

    /** Google OAuth2 authorize 端点。 */
    public static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";

    /** Google OAuth2 token 端点（授权码交换 + refresh grant 共用，form-urlencoded）。 */
    public static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** userinfo 端点（{@code GET}，Bearer；oauth2.ts:795-801，用于取 email）。 */
    public static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo";

    /** Cloud Code 生产 host（与 infra 装配同一常量源）。 */
    public static final String CLOUD_CODE_BASE_URL = ProviderConfigAssembler.GOOGLE_CLOUD_CODE_BASE_URL;

    /** Gemini CLI 的公开 client id（installed app）。 */
    public static final String CLIENT_ID =
        "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com";

    /** installed-app 的公开 client secret（来源 oauth2.ts:85）。⛔ 不进日志/文案。 */
    private static final String CLIENT_SECRET = "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";

    /** 申请的 scope（oauth2.ts:88-92）。 */
    public static final List<String> SCOPES = List.of(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile");

    /** 回调监听地址（oauth2.ts:538）。 */
    public static final String CALLBACK_HOST = "127.0.0.1";

    /** 回调路径（oauth2.ts:543）。 */
    public static final String CALLBACK_PATH = "/oauth2callback";

    /**
     * redirect_uri 占位值（端口 0 = 未定）。
     *
     * <p>真实值必须由调用方按本次登录监听的临时端口拼出：
     * {@code http://127.0.0.1:<port>/oauth2callback}。本方法只为满足
     * {@link OAuthProviderConfig} 契约。
     */
    public static final String DEFAULT_REDIRECT_URI = "http://127.0.0.1:0/oauth2callback";

    /**
     * 登录 state 的工程 TTL（默认值，用户未指定）。
     *
     * <p>含义：从 {@code authorize} 起算，超过即视为过期链接；同时作为
     * {@code GET .../status} 中 {@code pending} 的 {@code expiresAt}。
     */
    public static final long LOGIN_STATE_TTL_MILLIS = 10 * 60 * 1000L;

    /** 同时在途的登录 state 上限（重复点「登录」互不覆盖，与 Claude 链同口径）。 */
    public static final int MAX_PENDING_STATES = 5;

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String authorizationEndpoint() {
        return AUTHORIZATION_ENDPOINT;
    }

    @Override
    public String tokenEndpoint() {
        return TOKEN_ENDPOINT;
    }

    @Override
    public String userInfoEndpoint() {
        return USERINFO_ENDPOINT;
    }

    @Override
    public String clientId() {
        return CLIENT_ID;
    }

    @Override
    public String clientSecret() {
        return CLIENT_SECRET;
    }

    @Override
    public List<String> scopes() {
        return SCOPES;
    }

    /** ⚠️ 占位值；实际 redirect_uri 由调用方按本次动态端口传入（见 {@link #DEFAULT_REDIRECT_URI}）。 */
    @Override
    public String redirectUri() {
        return DEFAULT_REDIRECT_URI;
    }
}

package com.nexusai.application.agent.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI / ChatGPT 订阅（Codex CLI）OAuth provider 配置 · 实现 {@link OAuthProviderConfig}。
 *
 * <p><b>常量来源</b>：pi 实测的 Codex CLI 授权码流程（public client + PKCE，无 client_secret）。
 * 这些是上游公开的客户端常量，不是本项目的密钥，故按实测原文硬编码；{@code clientSecret()}
 * 与 {@code userInfoEndpoint()} 返回空串（本流程不使用）。
 *
 * <p><b>PKCE</b>：授权码交换必须携带 {@code code_verifier}（见
 * {@link #exchangeParams(String, String, String)}），token 端点会校验它与 authorize 请求里的
 * {@code code_challenge} 匹配。
 *
 * <p><b>grant_type 由本类显式给出</b>：{@link OAuthTokenClient} 的 {@code grantType} 参数只用于
 * 调试日志，<b>不会</b>注入 form body（见该类 {@code postForTokens} 的实现），故 form 里必须自带
 * {@code grant_type}，不能依赖于客户端内部注入。
 */
public final class CodexOAuthProviderConfig implements OAuthProviderConfig {

    /** provider 唯一标识（登录态按 provider id 存表，与此名无关，仅用于日志/区分）。 */
    public static final String PROVIDER = "openai-codex";

    /** ChatGPT 订阅 authorize 端点。 */
    public static final String AUTHORIZATION_ENDPOINT = "https://auth.openai.com/oauth/authorize";

    /** ChatGPT 订阅 token 端点（授权码交换 + refresh grant 共用）。 */
    public static final String TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token";

    /** Codex CLI 的公开 client id（public client，无 client_secret）。 */
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";

    /** 回调地址：本应用<b>不监听</b> 1455，用户需把浏览器地址栏整条 URL 粘回前端。 */
    public static final String REDIRECT_URI = "http://localhost:1455/auth/callback";

    /** 申请的 scope（空格分隔提交）。 */
    public static final List<String> SCOPES =
        List.of("openid", "profile", "email", "offline_access");

    /** JWT 里携带 ChatGPT 账号信息的 claim 路径。 */
    public static final String AUTH_CLAIM = "https://api.openai.com/auth";

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

    /** 本流程不调用 userinfo（账号信息从 access token 的 JWT claim 里取），返回空串。 */
    @Override
    public String userInfoEndpoint() {
        return "";
    }

    @Override
    public String clientId() {
        return CLIENT_ID;
    }

    /** public client + PKCE，无 client_secret。 */
    @Override
    public String clientSecret() {
        return "";
    }

    @Override
    public List<String> scopes() {
        return SCOPES;
    }

    @Override
    public String redirectUri() {
        return REDIRECT_URI;
    }

    /**
     * RFC 6749 §4.1.3 授权码交换表单。
     *
     * <p>注意：{@code grant_type} 必须出现在这里 —— {@link OAuthTokenClient#exchangeCodeForTokens}
     * 内部只用 grant 类型打日志，不写进 body。
     */
    @Override
    public Map<String, String> exchangeParams(String code, String redirectUri, String codeVerifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", redirectUri);
        params.put("client_id", CLIENT_ID);
        params.put("code_verifier", codeVerifier);
        return params;
    }
}

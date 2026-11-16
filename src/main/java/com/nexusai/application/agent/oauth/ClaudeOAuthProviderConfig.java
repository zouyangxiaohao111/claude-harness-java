package com.nexusai.application.agent.oauth;

import java.util.List;

/**
 * Anthropic Claude 订阅（Claude Code OAuth）登录链路的公开常量。
 *
 * <p><b>常量来源</b>：pi-ai 上游实测的 Claude Code 授权码流程（public client + PKCE，无
 * client_secret）。这些都是上游公开的客户端常量，<b>不是本项目的密钥</b>，故按实测原文硬编码；
 * 本类不持有任何用户凭据。
 *
 * <p>⚠️ <b>两个刻意的不一致，⛔ 不要「修正」</b>：
 * <ol>
 *   <li>回调监听绑 {@link #CALLBACK_HOST}（{@code 127.0.0.1}），但 {@link #REDIRECT_URI} 写
 *       {@code localhost} —— 上游注册的 redirect_uri 就是 localhost，两处统一反而会
 *       {@code redirect_uri_mismatch}。</li>
 *   <li>authorize 的 {@code state} 参数<b>就是 PKCE verifier 本身</b>，不是独立的随机 state
 *       （见 {@link ClaudeOAuthService#authorizeUrl(String)}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>换 token 必须发 JSON body</b>（见 {@link ClaudeOAuthService}）。本仓
 * {@link OAuthTokenClient} 走 form-urlencoded 是 Codex / Google / GitHub 的形态，两者不可混用。
 */
public final class ClaudeOAuthProviderConfig {

    private ClaudeOAuthProviderConfig() {
    }

    /** provider 标识（仅用于日志区分；登录态按 provider id 落库，与此名无关）。 */
    public static final String PROVIDER = "claude-subscription";

    /** Claude 订阅 authorize 端点。 */
    public static final String AUTHORIZATION_ENDPOINT = "https://claude.ai/oauth/authorize";

    /** Claude 订阅 token 端点（授权码交换 + refresh grant 共用）。 */
    public static final String TOKEN_ENDPOINT = "https://platform.claude.com/v1/oauth/token";

    /** Claude Code 的公开 client id（public client，无 client_secret）。 */
    public static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    /**
     * 回调地址：<b>{@code localhost}</b>，而监听绑 {@code 127.0.0.1}
     * （见 {@link #CALLBACK_HOST}）。⚠️ 这是上游注册值，⛔ 不要为了「一致」改成 127.0.0.1。
     */
    public static final String REDIRECT_URI = "http://localhost:53692/callback";

    /** 回调监听地址（本机回环）。 */
    public static final String CALLBACK_HOST = "127.0.0.1";

    /** 回调监听端口（与 {@link #REDIRECT_URI} 的端口一致）。 */
    public static final int CALLBACK_PORT = 53692;

    /** 回调路径（与 {@link #REDIRECT_URI} 的路径一致）。 */
    public static final String CALLBACK_PATH = "/callback";

    /** 申请的 scope（空格分隔提交）。 */
    public static final List<String> SCOPES = List.of(
        "org:create_api_key",
        "user:profile",
        "user:inference",
        "user:sessions:claude_code",
        "user:mcp_servers",
        "user:file_upload");

    /**
     * access token 提前刷新窗口（5 分钟）。
     *
     * <p>过期口径与上游一致：{@code now + expires_in*1000 - 5min}
     * （见 {@link ClaudeOAuthService#expiresAtFrom(long, long)}）。
     */
    public static final long EXPIRY_SKEW_MILLIS = 5 * 60 * 1000L;
}

package com.nexusai.application.agent.oauth;

/**
 * 账号级 OAuth token 响应解析错误 · 携带 RFC 6749 §5.2 {@code error}/{@code error_description} 字段
 * 与失败归因码 {@link OAuthTokenExchangeFailure}。
 *
 * <p>对齐 CC {@code normalizeOAuthErrorBody}（services/mcp/auth.ts:157-185）的 200+error 专项解析意图：
 * HTTP 2xx 但 body 含 {@code {error}} 且非 token 响应时 rewrite 为 400 使 error-class 映射生效——
 * Java 侧由 {@link OAuthTokenResponseParser} 直接抛本异常（显式携带 errorCode + reason），
 * 避免 {@code McpAuth.MCPRefreshFailed} 的字符串匹配归因。
 *
 * @param errorCode         CC original: error（RFC 6749 §5.2，如 bad_verification_code/access_denied）
 * @param errorDescription  CC original: error_description（可 null）
 * @param reason            失败归因稳定码（PROVIDER_DENIED / TOKEN_EXCHANGE_FAILED）
 */
public final class OAuthTokenExchangeError extends RuntimeException {

    private final String errorCode;
    private final String errorDescription;
    private final OAuthTokenExchangeFailure reason;

    public OAuthTokenExchangeError(String errorCode, String errorDescription,
            OAuthTokenExchangeFailure reason) {
        super("OAuth token exchange failed"
            + (errorCode == null || errorCode.isBlank() ? "" : " (error=" + errorCode + ")")
            + (errorDescription == null || errorDescription.isBlank() ? "" : ": " + errorDescription));
        this.errorCode = errorCode;
        this.errorDescription = errorDescription;
        this.reason = reason;
    }

    /** CC original: error（RFC 6749 §5.2 error code，可 null——如 access_token 缺失时无 error 字段）。 */
    public String errorCode() {
        return errorCode;
    }

    /** CC original: error_description（可 null）。 */
    public String errorDescription() {
        return errorDescription;
    }

    /** 失败归因稳定码。 */
    public OAuthTokenExchangeFailure reason() {
        return reason;
    }
}

package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 账号级 OAuth token 响应解析器（纯函数，收 token endpoint JSON body + provider 配置）· RFC 6749 §5.1/§5.2。
 *
 * <p><b>provider 感知（本 WF 核心）</b>：expiresAt/refreshToken 的解析<b>不再</b>用「无 expires_in /
 * 无 refresh_token → 全局默认 null」的无条件分支，而是<b>先问 {@link OAuthProviderConfig}</b>——
 * {@link OAuthProviderConfig#accessTokenExpires()} 决定是否解析 expiresAt、
 * {@link OAuthProviderConfig#supportsRefreshToken()} 决定是否解析 refreshToken。
 * 故「GitHub 不过期无刷新」不再是全局默认，而是 GitHub 配置显式声明的结果。
 *
 * <p>对齐 CC first-party token 响应语义 + {@code normalizeOAuthErrorBody} 的 200+error 专项解析：
 * <ol>
 *   <li>body 含 {@code error} 字段（GitHub token endpoint 对错误返回 HTTP 200 + {@code {"error":...}}，
 *       对齐 CC normalizeOAuthErrorBody mcp/auth.ts:157-185）→ 抛 {@link OAuthTokenExchangeError}：
 *       {@code error=="access_denied"} → {@link OAuthTokenExchangeFailure#PROVIDER_DENIED}，
 *       其余（bad_verification_code/incorrect_client_credentials/invalid_grant/invalid_client/
 *       invalid_request/unsupported_grant_type/invalid_scope）→ TOKEN_EXCHANGE_FAILED；</li>
 *   <li>{@code access_token} 缺失 → 抛 TOKEN_EXCHANGE_FAILED（RFC 6749 §5.1 必需，provider-agnostic）；</li>
 *   <li>provider 声明会过期（{@code accessTokenExpires()==true}）→ 读 {@code expires_in}
 *       （缺失→fail-loud 抛 TOKEN_EXCHANGE_FAILED，绝不静默当「不过期」，对齐 CC formatTokens
 *       index.ts:178 无默认值）；否则（GitHub）expiresAt 恒 null；</li>
 *   <li>provider 声明支持刷新（{@code supportsRefreshToken()==true}）→ 读 {@code refresh_token}
 *       （可 null）；否则（GitHub）refreshToken 恒 null。</li>
 * </ol>
 *
 * <p><b>与 CC 源码冲突标注（规则七显式暴露）</b>：计划将 {@code access_denied → PROVIDER_DENIED}，
 * 但 CC 源码中 {@code access_denied} 零命中（grep 证实），且 CC {@code provider_denied} 仅由
 * <b>授权回调 error 参数</b>触发（mcp/auth.ts:1278-1280 {@code msg.includes('OAuth error:')}），
 * token endpoint 出错在 CC 归因 {@code token_exchange_failed}（mcp/auth.ts:1271-1272）。本实现
 * 按计划落地 {@code access_denied → PROVIDER_DENIED}（RFC 6749 §4.1.2.1 定义 access_denied 为
 * 「资源所有者/授权服务器拒绝」，语义上即 provider 拒绝），此映射为计划推断、需 owner 实机验证确认。
 */
public final class OAuthTokenResponseParser {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenResponseParser.class);

    private OAuthTokenResponseParser() {
    }

    /**
     * 解析 token endpoint 响应 body（provider 感知）。
     *
     * @param body   token endpoint JSON 响应（可为 null，null 等价于 access_token 缺失）
     * @param config provider 配置（决定 expiresAt/refreshToken 是否解析；不可为 null）
     * @return {@link OAuthTokenResponse}（accessToken 非空；expiresAt/refreshToken 语义由 config 决定）
     * @throws IllegalArgumentException config 为 null 时（无法判定生命周期语义，fail-loud）
     * @throws OAuthTokenExchangeError   body 含 error 字段、access_token 缺失、或
     *                                   provider 声明会过期但响应缺失 expires_in 时
     */
    public static OAuthTokenResponse parse(JsonNode body, OAuthProviderConfig config) {
        // ⓪ config 守卫：无 provider 语义声明则无法判定 expiresAt/refreshToken，fail-loud
        if (config == null) {
            throw new IllegalArgumentException(
                "[OAuthTokenResponseParser] providerConfig 不能为 null（无法判定 token 生命周期语义）");
        }

        // ① error 字段专项解析（GitHub 200+error，对齐 CC normalizeOAuthErrorBody auth.ts:157-185）
        if (body != null && body.path("error").isTextual()) {
            String errorCode = body.path("error").asText();
            String errorDescription = body.path("error_description").asText(null);
            OAuthTokenExchangeFailure reason = mapErrorCode(errorCode);
            if (log.isDebugEnabled()) {
                log.debug("[OAuthTokenResponseParser] token 响应含 error 字段：error={} reason={}",
                    errorCode, reason);
            }
            throw new OAuthTokenExchangeError(errorCode, errorDescription, reason);
        }

        // ② access_token 缺失 → TOKEN_EXCHANGE_FAILED（RFC 6749 §5.1 必需字段，provider-agnostic）
        String accessToken = body == null ? null : body.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[OAuthTokenResponseParser] token 响应缺失 access_token → TOKEN_EXCHANGE_FAILED");
            }
            throw new OAuthTokenExchangeError(null, "token 响应缺失 access_token",
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
        }

        // ③ 过期语义：先问 provider 是否声明会过期，而非「无 expires_in → 不过期」的全局默认
        Long expiresAt = null;
        boolean hasExpiresIn = body.path("expires_in").isIntegralNumber();
        if (config.accessTokenExpires()) {
            // provider 声明会过期（RFC 6749 §5.1 expires_in RECOMMENDED）→ 必须解析 expires_in
            if (!hasExpiresIn) {
                // fail-loud：缺失 expires_in 无法计算过期，绝不静默当「不过期」否则 Google token 永不刷新
                log.warn("[OAuthTokenResponseParser] provider={} 声明会过期但响应缺失 expires_in "
                        + "→ TOKEN_EXCHANGE_FAILED（对齐 CC formatTokens index.ts:178 无默认值）",
                    config.provider());
                throw new OAuthTokenExchangeError(null, "provider 声明会过期但响应缺失 expires_in",
                    OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            }
            long expiresIn = body.path("expires_in").asLong();
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        }
        // else：provider 声明不过期（GitHub）→ expiresAt 恒 null

        // ④ 刷新语义：先问 provider 是否支持刷新，而非「无 refresh_token → 无刷新」的全局默认
        String refreshToken = null;
        if (config.supportsRefreshToken()) {
            refreshToken = body.path("refresh_token").asText(null);
        }
        // else：provider 声明无刷新（GitHub）→ refreshToken 恒 null

        String tokenType = body.path("token_type").asText(null);
        String scope = body.path("scope").asText(null);

        if (log.isDebugEnabled()) {
            log.debug("[OAuthTokenResponseParser] token 响应解析完成 provider={} "
                    + "accessTokenExpires={} supportsRefreshToken={} 有refreshToken={} 有expires_in={} "
                    + "tokenType={} scope={}",
                config.provider(), config.accessTokenExpires(), config.supportsRefreshToken(),
                refreshToken != null, hasExpiresIn, tokenType, scope);
        }
        return new OAuthTokenResponse(accessToken, tokenType, scope, expiresAt, refreshToken);
    }

    /**
     * error code → 失败归因映射。access_denied 语义上=资源所有者拒绝（RFC 6749 §4.1.2.1）→
     * PROVIDER_DENIED；其余 token endpoint 错误码 → TOKEN_EXCHANGE_FAILED（对齐 CC 归因
     * 「token endpoint 出错归因 token_exchange_failed」，mcp/auth.ts:1271-1272）。
     */
    private static OAuthTokenExchangeFailure mapErrorCode(String errorCode) {
        if ("access_denied".equals(errorCode)) {
            return OAuthTokenExchangeFailure.PROVIDER_DENIED;
        }
        return OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED;
    }
}

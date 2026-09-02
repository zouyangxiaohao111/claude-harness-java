package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 账号级 OAuth token 交换/刷新客户端（provider-aware）· 沿用 {@code java.net.http.HttpClient}
 * 风格，做 form-urlencoded POST token_endpoint，产物为
 * {@link OAuthTokenResponse}（nullable {@code expiresAt}/{@code refreshToken}），而非 MCP 域的
 * {@code McpAuth.Tokens}（primitive long + 3600s 默认）。
 *
 * <p><b>与 {@code DefaultOAuthHttpClient.formPost} 的 form-urlencoded POST 逻辑重复是有意为之</b>
 * （规则七显式暴露，避免未来误合并）：
 * <ul>
 *   <li>MCP 域 {@code DefaultOAuthHttpClient.exchangeCodeForTokens/refreshTokens} 返回
 *       {@code McpAuth.Tokens}，其 {@code expiresAt} 是 primitive {@code long}（无法表达「不过期」），
 *       且 {@code parseTokens} 对缺失 {@code expires_in} 强制 3600s 默认（对齐 CC saveTokens
 *       {@code Date.now() + (tokens.expires_in || 3600) * 1000}，mcp/auth.ts:1724/1823 的 MCP 域语义）；</li>
 *   <li>账号域本客户端返回 {@link OAuthTokenResponse}，{@code expiresAt}/{@code refreshToken} 的
 *       null 语义由 {@link OAuthProviderConfig#accessTokenExpires()} /
 *       {@link OAuthProviderConfig#supportsRefreshToken()} 声明决定（经
 *       {@link OAuthTokenResponseParser} 解析），<b>不做 3600s 默认</b>。</li>
 * </ul>
 * 两层是「MCP 域 primitive-long Tokens vs 账号域 nullable OAuthTokenResponse」的分层，不可合并。
 *
 * <p><b>Content-Type: application/x-www-form-urlencoded 的 provider 正确偏离</b>（规则七显式暴露）：
 * CC {@code exchangeCodeForTokens}（services/oauth/client.ts:131）与 {@code refreshOAuthToken}
 * （client.ts:167）均硬编码 {@code Content-Type: application/json}——那是 Claude.ai first-party
 * token endpoint 专属。Google {@code https://oauth2.googleapis.com/token} 与 GitHub
 * {@code https://github.com/login/oauth/access_token} 均<b>仅接受</b>
 * {@code application/x-www-form-urlencoded}（RFC 6749 §4.1.3 默认），照抄 CC 的 JSON body 会令
 * Google 刷新 400。故本客户端统一 form-urlencoded，属 provider 正确偏离（账号 OAuth 编排层
 * 已注释标注）。
 *
 * <p><b>expires_in 缺失语义（不回归 CC 无条件 NaN）</b>：CC {@code refreshOAuthToken}
 * （client.ts:182）无条件 {@code Date.now() + expiresIn * 1000}（expires_in 缺失 → NaN），不能照抄；
 * 本客户端由 {@link OAuthTokenResponseParser} 落地「null=不过期（GitHub）/缺失即 fail-loud（会过期
 * provider，如 Google）」的 provider-aware 语义，绝不回归 CC 的无条件 expires_in 行为。
 *
 * <p>端点/params/config 均参数注入，不写死 GitHub 或 Google（未来 GitLab 等复用同一客户端）。
 */
public final class OAuthTokenClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORM = "application/x-www-form-urlencoded";

    /** CC exchangeCodeForTokens / refreshOAuthToken timeout: 15000（client.ts:128 / :167）。 */
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;

    public OAuthTokenClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public OAuthTokenClient(HttpClient httpClient) {
        this.httpClient = httpClient == null
            ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            : httpClient;
    }

    /**
     * RFC 6749 §4.1.3 授权码交换：POST token_endpoint（form-urlencoded + client_secret_post），
     * 解析为 {@link OAuthTokenResponse}（nullable expiresAt/refreshToken）。
     *
     * @param tokenEndpoint token endpoint（取自 {@link OAuthProviderConfig#tokenEndpoint()}）
     * @param params        请求体参数（grant_type/code/redirect_uri/client_id/code_verifier/
     *                      可选 client_secret；含敏感字段，本方法不打印）
     * @param config        provider 配置（决定 expiresAt/refreshToken 是否解析；不可为 null）
     * @return {@link OAuthTokenResponse}（accessToken 非空；expiresAt/refreshToken 语义由 config 决定）
     * @throws OAuthTokenExchangeError 非 2xx / 响应含 error 字段 / access_token 缺失 /
     *                                  会过期 provider 缺失 expires_in 时（fail-loud）
     */
    public OAuthTokenResponse exchangeCodeForTokens(String tokenEndpoint, Map<String, String> params,
            OAuthProviderConfig config) {
        return postForTokens(tokenEndpoint, params, config, "authorization_code");
    }

    /**
     * RFC 6749 §6 refresh_token 刷新：POST token_endpoint（form-urlencoded，携带 refresh_token），
     * 解析为 {@link OAuthTokenResponse}（nullable expiresAt/refreshToken）。
     *
     * <p><b>未接 401 自愈门</b>：本方法仅提供 raw refresh grant，CC 的 401→refresh→重试自愈链
     * （{@code checkAndRefreshOAuthTokenIfNeededImpl} + {@code handleOAuth401ErrorImpl}）属后续 WF，
     * 本 WF 不接线。
     *
     * @param tokenEndpoint token endpoint（取自 {@link OAuthProviderConfig#tokenEndpoint()}）
     * @param params        请求体参数（grant_type=refresh_token/refresh_token/client_id/scope；
     *                      含敏感字段，本方法不打印）
     * @param config        provider 配置（决定 expiresAt/refreshToken 是否解析；不可为 null）
     * @return {@link OAuthTokenResponse}（refresh_token 缺省沿用旧值语义由调用方处理）
     * @throws OAuthTokenExchangeError 非 2xx / 响应含 error 字段 / access_token 缺失 /
     *                                  会过期 provider 缺失 expires_in 时（fail-loud）
     */
    public OAuthTokenResponse refreshTokens(String tokenEndpoint, Map<String, String> params,
            OAuthProviderConfig config) {
        return postForTokens(tokenEndpoint, params, config, "refresh_token");
    }

    /**
     * form-urlencoded POST → JsonNode → {@link OAuthTokenResponseParser#parse} → {@link OAuthTokenResponse}。
     *
     * <p>错误语义（fail-loud）：非 2xx → {@link OAuthTokenExchangeError}(TOKEN_EXCHANGE_FAILED)；
     * 2xx 但 body 含 error 字段 / access_token 缺失 / 会过期 provider 缺失 expires_in →
     * 由解析器抛 {@link OAuthTokenExchangeError} 原样上抛。
     */
    private OAuthTokenResponse postForTokens(String tokenEndpoint, Map<String, String> params,
            OAuthProviderConfig config, String grantType) {
        String body = encodeForm(params);
        if (log.isDebugEnabled()) {
            // 不打印 params（含 client_secret/code/refresh_token/code_verifier 敏感字段），只打印
            // 端点 + grant 类型（CC grant_type 值）。
            log.debug("[OAuthTokenClient] token endpoint 请求 provider={} grantType={} endpoint={}",
                config.provider(), grantType, tokenEndpoint);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(TOKEN_TIMEOUT)
                .header("Content-Type", FORM)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[OAuthTokenClient] token endpoint 返回非 2xx provider={} HTTP {} endpoint={}",
                    config.provider(), resp.statusCode(), tokenEndpoint);
                throw new OAuthTokenExchangeError(null,
                    "token endpoint 返回 HTTP " + resp.statusCode() + ": " + truncate(resp.body()),
                    OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            }

            JsonNode json = JSON.readTree(resp.body());
            OAuthTokenResponse parsed = OAuthTokenResponseParser.parse(json, config);
            if (log.isDebugEnabled()) {
                // 不打印 accessToken/refreshToken 敏感值，只打印解析后的语义标志。
                log.debug("[OAuthTokenClient] token 解析完成 provider={} 有refreshToken={} 有expiresAt={}",
                    config.provider(), parsed.refreshToken() != null, parsed.expiresAt() != null);
            }
            return parsed;
        } catch (OAuthTokenExchangeError | IllegalArgumentException e) {
            // 解析器抛的 OAuthTokenExchangeError（error 字段/access_token 缺失/expires_in 缺失）
            // 与 IllegalArgumentException（config null）原样上抛，保持 fail-loud 归因不丢失。
            throw e;
        } catch (Exception e) {
            log.warn("[OAuthTokenClient] token endpoint 请求失败 provider={} endpoint={}: {}",
                config.provider(), tokenEndpoint, e.toString());
            throw new OAuthTokenExchangeError(null,
                "token endpoint 请求失败: " + e.toString(),
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
        }
    }

    /** form-urlencoded 参数编码（镜像 {@code DefaultOAuthHttpClient}，null 值编码为空串）。 */
    private static String encodeForm(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    }

    private static String enc(String v) {
        return v == null ? "" : URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }
}

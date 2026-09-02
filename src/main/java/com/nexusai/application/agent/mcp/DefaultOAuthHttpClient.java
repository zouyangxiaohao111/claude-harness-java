package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * {@link McpAuth.OAuthHttpClient} 真实 HTTP 实现 · 对齐 CC services/mcp/auth.ts 的
 * {@code fetchAuthServerMetadata}/SDK {@code discoverOAuthServerInfo}/SDK {@code exchangeAuthorizationCode}。
 *
 * <p>承载 performMCPOAuthFlow 编排所需的实际网络行为：
 * <ul>
 *   <li><b>fetchProtectedResource</b> — RFC 9728 Protected Resource Metadata 发现：
 *       GET {@code {url}/.well-known/oauth-protected-resource}，取 {@code authorization_servers[0]}</li>
 *   <li><b>fetchAuthServer</b> — RFC 8414 Authorization Server Metadata 发现：
 *       GET {@code {issuer}/.well-known/oauth-authorization-server}，OIDC
 *       {@code openid-configuration} 回退；解析 {@code authorization_endpoint/token_endpoint/issuer}</li>
 *   <li><b>registerClient</b> — RFC 7591 动态客户端注册（POST registration_endpoint）</li>
 *   <li><b>exchangeCodeForTokens</b> — RFC 6749 §4.1.3 授权码交换（POST token_endpoint，
 *       {@code application/x-www-form-urlencoded}，携带 code_verifier）</li>
 *   <li><b>refreshTokens</b> — RFC 6749 §6 刷新（POST token_endpoint，携带 refresh_token）</li>
 * </ul>
 *
 * <p>fetch* 对非 2xx/解析失败返回 null（延续 McpAuth discoverAuthServer 的静默发现设计；
 * [OAuth-R4] discoverAuthServer 全链失败返回 null 由调用方降级，不再抛
 * METADATA_DISCOVERY_FAILED）；registerClient/exchange/refresh 对非 2xx 抛
 * {@link McpAuth.MCPRefreshFailed(REQUEST_FAILED)}，由编排层映射到对应失败原因。
 */
public final class DefaultOAuthHttpClient implements McpAuth.OAuthHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultOAuthHttpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORM = "application/x-www-form-urlencoded";

    private final HttpClient httpClient;

    public DefaultOAuthHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public DefaultOAuthHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient == null
            ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            : httpClient;
    }

    // ───────────── 发现 ─────────────

    /** RFC 9728 Protected Resource Metadata：GET {url}/.well-known/oauth-protected-resource。 */
    @Override
    public McpAuth.ProtectedResourceMetadata fetchProtectedResource(String url, long timeoutMs) {
        URI wellKnown = URI.create(url).resolve("/.well-known/oauth-protected-resource");
        JsonNode json = getJson(wellKnown, timeoutMs);
        if (json == null) {
            return null;
        }
        JsonNode servers = json.path("authorization_servers");
        if (servers.isArray() && servers.size() > 0 && servers.get(0).isTextual()) {
            return new McpAuth.ProtectedResourceMetadata(url, servers.get(0).asText());
        }
        return null;
    }

    /** RFC 8414 AS Metadata：优先 doc URL 直取，issuer 时附加 well-known 路径，OIDC 回退。 */
    @Override
    public McpAuth.AuthServerMetadata fetchAuthServer(String url, long timeoutMs) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(url); // 可能是完整 metadata doc URL（configured authServerMetadataUrl）
        if (!url.contains("/.well-known/")) {
            candidates.add(url + "/.well-known/oauth-authorization-server");
            candidates.add(url + "/.well-known/openid-configuration");
        }
        for (String candidate : candidates) {
            JsonNode json = getJson(URI.create(candidate), timeoutMs);
            if (json != null && json.has("authorization_endpoint") && json.has("token_endpoint")) {
                String issuer = json.path("issuer").asText(null);
                // [S3 OAuth-R2] RFC 8414 registration_endpoint（DCR 入口，SDK registerClient auth.js:920）
                String registrationEndpoint = json.path("registration_endpoint").asText(null);
                // CC getScopeFromMetadata 产物（scope/default_scope/scopes_supported，auth.ts:2445-2465）
                String scope = extractScopeFromMetadata(json);
                // [S03 R2-04 X-5] RFC 7009 revocation_endpoint + auth methods（auth.ts:495-517）
                String revocationEndpoint = json.path("revocation_endpoint").asText(null);
                java.util.List<String> revocationAuthMethods = stringArrayOrNull(
                    json.path("revocation_endpoint_auth_methods_supported"));
                java.util.List<String> tokenAuthMethods = stringArrayOrNull(
                    json.path("token_endpoint_auth_methods_supported"));
                return new McpAuth.AuthServerMetadata(
                    json.path("authorization_endpoint").asText(),
                    json.path("token_endpoint").asText(),
                    issuer, registrationEndpoint, scope,
                    revocationEndpoint, revocationAuthMethods, tokenAuthMethods);
            }
        }
        return null;
    }

    // ───────────── 客户端注册 / 令牌 ─────────────

    /**
     * RFC 7591 动态客户端注册 · 对齐 CC clientMetadata（auth.ts:1417-1437）+
     * SDK registerClient（auth.js:917-942）。
     *
     * <p>POST registration_endpoint，Content-Type: application/json，body =
     * {@code {client_name, redirect_uris, grant_types, response_types,
     * token_endpoint_auth_method, scope?}}；响应按 {@code OAuthClientInformationFullSchema}
     * 解析（client_id 必需 + client_secret 可选）。非 2xx / 解析失败抛
     * {@link McpAuth.MCPRefreshFailed}，由编排层映射 REGISTRATION_FAILED。
     */
    @Override
    public McpAuth.ClientInfo registerClient(String registrationEndpoint,
            McpAuth.ClientRegistrationRequest request) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(registrationEndpoint))
                .timeout(Duration.ofMillis(McpAuth.AUTH_REQUEST_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    buildRegistrationBody(request), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode json = JSON.readTree(resp.body());
                // [S03 R2-04 X-4] DCR POST 同样归一（createAuthFetch 对全部 POST 响应应用
                // normalizeOAuthErrorBody，auth.ts:207/:231）：200 + error body（RFC 7591
                // §3.2.1 错误响应）→ 400 error-class 映射，不再当成功解析。
                String error = json.path("error").asText(null);
                if (json.path("client_id").asText(null) == null && error != null && !error.isBlank()) {
                    if ("invalid_grant".equals(error)
                            || McpAuth.NONSTANDARD_INVALID_GRANT_ALIASES.contains(error)) {
                        throw new McpAuth.InvalidGrantError(
                            "OAuth dynamic client registration error — " + error);
                    }
                    throw new McpAuth.MCPRefreshFailed(
                        McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                        "OAuth dynamic client registration error — HTTP 200: " + error
                            + " at " + registrationEndpoint, 400);
                }
                String clientId = json.path("client_id").asText(null);
                if (clientId == null || clientId.isBlank()) {
                    throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                        "DCR response missing client_id at " + registrationEndpoint);
                }
                String clientSecret = json.path("client_secret").asText(null);
                if (log.isDebugEnabled()) {
                    log.debug("[DefaultOAuthHttpClient] DCR 注册成功 registrationEndpoint={} 有clientSecret={}",
                        registrationEndpoint, clientSecret != null);
                }
                return new McpAuth.ClientInfo(clientId, clientSecret, request.redirectUris());
            }
            String message = "HTTP " + resp.statusCode() + ": " + truncate(resp.body());
            throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "OAuth dynamic client registration failed — " + message);
        } catch (McpAuth.MCPRefreshFailed e) {
            throw e;
        } catch (McpAuth.InvalidGrantError e) {
            // [S03 R2-04 X-4] DCR 归一产物原样透传（同 formPost）
            throw e;
        } catch (Exception e) {
            throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "OAuth dynamic client registration request failed: " + e.toString());
        }
    }

    /** 构建 RFC 7591 注册请求 JSON body · CC clientMetadata + scope（SDK registerClient body）。 */
    private static String buildRegistrationBody(McpAuth.ClientRegistrationRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("client_name", request.clientName());
        ArrayNode redirectUris = body.putArray("redirect_uris");
        for (String u : request.redirectUris()) {
            redirectUris.add(u);
        }
        ArrayNode grantTypes = body.putArray("grant_types");
        for (String g : request.grantTypes()) {
            grantTypes.add(g);
        }
        ArrayNode responseTypes = body.putArray("response_types");
        for (String r : request.responseTypes()) {
            responseTypes.add(r);
        }
        body.put("token_endpoint_auth_method", request.tokenEndpointAuthMethod());
        if (request.scope() != null && !request.scope().isBlank()) {
            body.put("scope", request.scope());
        }
        return body.toString();
    }

    /**
     * CC getScopeFromMetadata（auth.ts:2445-2465）：AS metadata 的 scope 提取 —
     * ①scope（非标准字段）→ ②default_scope（非标准字段）→ ③scopes_supported（标准 OAuth 字段，
     * 数组 join ' '）。无匹配返回 null。
     */
    private static String extractScopeFromMetadata(JsonNode json) {
        String scope = json.path("scope").asText(null);
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        String defaultScope = json.path("default_scope").asText(null);
        if (defaultScope != null && !defaultScope.isBlank()) {
            return defaultScope;
        }
        JsonNode supported = json.path("scopes_supported");
        if (supported.isArray() && supported.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < supported.size(); i++) {
                if (supported.get(i).isTextual()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(supported.get(i).asText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    /**
     * [S03 R2-04 X-4] RFC 6749 §5.2 error body 归一（200 + error 响应）· CC original:
     * {@code normalizeOAuthErrorBody}（auth.ts:157-190）。Slack 系服务器 200 + error body
     * （oauth.v2.user.access 实测 invalid_refresh_token）被当成功解析 → 误分类
     * NO_TOKENS_RETURNED（旧路径脏代码 D-S03-3）。归一：非标准别名
     * （invalid_refresh_token/expired_refresh_token/token_expired，auth.ts:147-151）→
     * {@code invalid_grant}（auth.ts:177-184）→ {@link McpAuth.InvalidGrantError}；
     * 其他 error → {@link McpAuth.MCPRefreshFailed}(REQUEST_FAILED, status=400)。
     */
    private static JsonNode normalizeTokenResponseBody(String body, String endpoint) {
        JsonNode json;
        try {
            json = JSON.readTree(body);
        } catch (Exception e) {
            throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "OAuth token endpoint returned unparseable 200 body at " + endpoint);
        }
        // 正常 token 响应（RFC 6749 §5.1，含 access_token）→ 原样返回
        if (json.path("access_token").asText(null) != null) {
            return json;
        }
        // error body（RFC 6749 §5.2）→ 归一映射
        String error = json.path("error").asText(null);
        if (error != null && !error.isBlank()) {
            if ("invalid_grant".equals(error)
                    || McpAuth.NONSTANDARD_INVALID_GRANT_ALIASES.contains(error)) {
                String description = json.path("error_description").asText(null);
                String normalized = "invalid_grant".equals(error) ? error
                    : ("Server returned non-standard error code: " + error);
                throw new McpAuth.InvalidGrantError(
                    description != null ? normalized + " — " + description : normalized);
            }
            throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "OAuth token endpoint error — HTTP 200: " + error + " at " + endpoint, 400);
        }
        // 既非 token 也非 error body（如空 body）→ 保留旧语义（parseTokens 返回 null）
        return json;
    }

    /** RFC 8414 metadata 字符串数组字段解析（revocation/token_endpoint_auth_methods_supported）。 */
    private static java.util.List<String> stringArrayOrNull(JsonNode node) {
        if (!node.isArray() || node.size() == 0) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** RFC 6749 §4.1.3 + RFC 7636 §4.5：授权码交换，POST token_endpoint 带 code_verifier。 */
    @Override
    public McpAuth.Tokens exchangeCodeForTokens(String endpoint, Map<String, String> params) {
        JsonNode json = formPost(endpoint, params, McpAuth.AUTH_REQUEST_TIMEOUT_MS, true);
        return parseTokens(json, endpoint);
    }

    /** RFC 6749 §6：refresh_token 刷新，POST token_endpoint。 */
    @Override
    public McpAuth.Tokens refreshTokens(String endpoint, Map<String, String> params) {
        JsonNode json = formPost(endpoint, params, McpAuth.AUTH_REQUEST_TIMEOUT_MS, true);
        return parseTokens(json, endpoint);
    }


    /**
     * [S03 R2-04 X-5] RFC 7009 token 吊销（单次 POST）· CC original: {@code revokeToken}
     * （auth.ts:381-459，axios.post(endpoint, params, {headers})）。
     *
     * <p>POST {@code endpoint}（application/x-www-form-urlencoded，params 含
     * token/token_type_hint[/client_id/client_secret]），携带 headers（Authorization
     * Basic/Bearer）。非 2xx 抛 {@link McpAuth.MCPRefreshFailed}（httpStatus 填状态码，
     * 供调用方 401 → Bearer 回退判定，auth.ts:436-450）。调用方（McpAuth.revokeServerTokens）
     * 对失败 log-and-continue。
     */
    @Override
    public void revokeToken(String endpoint, Map<String, String> params, Map<String, String> headers) {
        String body = params.entrySet().stream()
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(McpAuth.AUTH_REQUEST_TIMEOUT_MS))
                .header("Content-Type", FORM)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> resp = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                if (log.isDebugEnabled()) {
                    log.debug("[DefaultOAuthHttpClient] RFC 7009 吊销成功 endpoint={}", endpoint);
                }
                return;
            }
            throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "OAuth revocation endpoint error — HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body()), resp.statusCode());
        } catch (McpAuth.MCPRefreshFailed e) {
            throw e;
        } catch (Exception e) {
            throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "OAuth revocation request failed: " + e.toString());
        }
    }
    // ───────────── helpers ─────────────

    private JsonNode getJson(URI uri, long timeoutMs) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return JSON.readTree(resp.body());
            }
            if (log.isDebugEnabled()) {
                log.debug("[DefaultOAuthHttpClient] GET {} HTTP {} 忽略（发现路径静默回退）", uri, resp.statusCode());
            }
            return null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[DefaultOAuthHttpClient] GET {} 失败: {}", uri, e.toString());
            }
            return null;
        }
    }

    /**
     * 表单 POST。
     *
     * @param throwOnError 非 2xx 时是否抛 {@link McpAuth.MCPRefreshFailed}（exchange/refresh 为 true，
     *                     发现/注册为 false 返回 null）
     */
    private JsonNode formPost(String endpoint, Map<String, String> params, long timeoutMs, boolean throwOnError) {
        String body = params.entrySet().stream()
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", FORM)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                // [S03 R2-04 X-4] 200 + error body 归一（normalizeOAuthErrorBody 等价，
                // auth.ts:157-190 + createAuthFetch 对全部 POST 响应应用 :207/:231）——
                // invalid_grant/别名 → InvalidGrantError；其他 error → MCPRefreshFailed(400)。
                // 旧路径（D-S03-3）把 200+error body 当成功解析 → parseTokens 无 access_token
                // → 误分类 NO_TOKENS_RETURNED，invalid_grant 永不被识别（R2-04 X-4）。
                return normalizeTokenResponseBody(resp.body(), endpoint);
            }
            String message = "HTTP " + resp.statusCode() + ": " + truncate(resp.body());
            if (throwOnError) {
                throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                    "OAuth token endpoint error — " + message);
            }
            if (log.isDebugEnabled()) {
                log.debug("[DefaultOAuthHttpClient] POST {} HTTP {} 忽略", endpoint, resp.statusCode());
            }
            return null;
        } catch (McpAuth.MCPRefreshFailed e) {
            throw e;
        } catch (McpAuth.InvalidGrantError e) {
            // [S03 R2-04 X-4] normalize 产物必须原样透传（_doRefresh instanceof 判定依赖），
            // 不得被包装成 REQUEST_FAILED（旧路径误分类根源之一）
            throw e;
        } catch (Exception e) {
            if (throwOnError) {
                throw new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                    "OAuth token endpoint request failed: " + e.toString());
            }
            if (log.isDebugEnabled()) {
                log.debug("[DefaultOAuthHttpClient] POST {} 失败: {}", endpoint, e.toString());
            }
            return null;
        }
    }

    /** 解析 RFC 6749 §5.1 token 响应；非 2xx 时已由 formPost 抛错。 */
    private McpAuth.Tokens parseTokens(JsonNode json, String endpoint) {
        if (json == null || json.path("access_token").asText(null) == null) {
            return null;
        }
        long expiresIn = json.path("expires_in").isIntegralNumber()
            ? json.path("expires_in").asLong()
            : 3600L; // CC saveTokens: Date.now() + (tokens.expires_in || 3600) * 1000
        String accessToken = json.path("access_token").asText();
        String refreshToken = json.path("refresh_token").asText(null);
        String scope = json.path("scope").asText(null);
        McpAuth.Tokens tokens = new McpAuth.Tokens(
            accessToken, refreshToken, System.currentTimeMillis() + expiresIn * 1000L, scope);
        if (log.isDebugEnabled()) {
            log.debug("[DefaultOAuthHttpClient] token 响应 server={} 有refreshToken={} expires_in={}",
                endpoint, refreshToken != null, expiresIn);
        }
        return tokens;
    }

    private static String enc(String v) {
        return v == null ? "" : URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }
}

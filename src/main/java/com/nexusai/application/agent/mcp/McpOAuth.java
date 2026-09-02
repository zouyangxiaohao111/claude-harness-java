package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MCP OAuth 纯函数工具（对齐 CC services/mcp/auth.ts getServerKey）。
 *
 * <p><b>getServerKey</b>（CC original: auth.ts:325-338）：
 * <pre>
 *   const configJson = jsonStringify({
 *     type: serverConfig.type,
 *     url: serverConfig.url,
 *     headers: serverConfig.headers || {},
 *   })
 *   const hash = sha256(configJson).hex.substring(0, 16)
 *   return `${serverName}|${hash}`
 * </pre>
 *
 * <p>生成基于 name + 配置哈希的唯一凭据键：防同名/同配置复用凭据
 * （auth.ts 注释 "prevents credentials from being reused across different
 * servers with the same name or different configurations"）。
 *
 * <p>JSON 序列化必须稳定键序（LinkedHashMap type/url/headers，对齐 JS 对象插入序），
 * 否则同一 server 每次调用产生不同 hash 导致 DB 主键漂移。
 */
public final class McpOAuth {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * DB env 列保留键 · oauth 配置运行时镜像（plan §2.10）。
     *
     * <p>WHY：DB 无 oauth 列（V1__init_schema.sql env TEXT 承载远程 headers）；oauth 是 .mcp.json
     * 独立字段（CC config.ts:727-733），但 list/get 按 scope 回读仅覆盖 project（读 project .mcp.json）。
     * user/local scope 的远程 server oauth 会因「GET 只读 project 文件」丢失。故 applyServerConfig
     * 把 oauth 序列化入 env 此保留键，toDto 反解 —— list/get 不再依赖 scope 文件（finding 1 fix）。
     *
     * <p>保留键语义：只作 DB 内镜像，不参与「env=远程 headers」的 serverKey 计算 / OAuth 流 headers
     * 传递（凡把 env 当 headers 消费处必须先 {@link #headersOnly} 剥除本键），否则 serverKey 漂移
     * 导致凭据消费/清理错行 + 保留键被当 HTTP 头发送。
     */
    public static final String ENV_OAUTH_MIRROR_KEY = "__mcp_oauth__";

    private McpOAuth() {}

    /**
     * 把 env（远程 server 的 headers 载体）按「headers 语义」剥除保留键的只读视图。
     *
     * <p>WHY：DB env 列同时承载 headers + {@code __mcp_oauth__} 镜像；凡把 env 当 headers 消费
     * （serverKey 计算 / OAuthServerConfig.headers）必须剥除保留键，否则 serverKey 与
     * {@code saveClientSecret}（用 config.headers 计算）不同键 → 凭据读错行 + 保留键外泄为 HTTP 头。
     *
     * @param env DB env（可为 null）
     * @return 仅真实 headers 的副本；null/空 → 空 Map
     */
    public static Map<String, String> headersOnly(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (ENV_OAUTH_MIRROR_KEY.equals(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 生成 MCP server 凭据唯一键。
     *
     * @param serverName MCP server 名（CC serverName）
     * @param type       传输类型（CC serverConfig.type，如 "sse"/"http"）
     * @param url        server URL（CC serverConfig.url）
     * @param headers    请求头（CC serverConfig.headers，null → {}）
     * @return {@code serverName|sha256hex16}；hash 为稳定键序 JSON 的 sha256 前 16 hex
     */
    public static String getServerKey(String serverName, String type, String url, Map<String, String> headers) {
        LinkedHashMap<String, Object> stable = new LinkedHashMap<>();
        stable.put("type", type);
        stable.put("url", url);
        stable.put("headers", headers == null ? Map.of() : headers);
        String configJson;
        try {
            configJson = JSON.writeValueAsString(stable);
        } catch (JsonProcessingException e) {
            // 纯内存 LinkedHashMap 序列化不应失败；fail-loud
            throw new IllegalStateException("McpOAuth.getServerKey 序列化失败: " + e.getMessage(), e);
        }
        String hash = sha256Hex(configJson).substring(0, 16);
        return serverName + "|" + hash;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成 OAuth state · CC original: {@code randomBytes(32).toString('base64url')}
     * (auth.ts:1476 ClaudeAuthProvider.state())。32 字节 base64url 无填充 → 43 字符。
     * 用于防 CSRF 跨站请求伪造：授权请求携带 state，回调时必须校验回传 state 一致。
     *
     * @return 43 字符 base64url state
     */
    public static String generateState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 生成 PKCE code_verifier · CC original: MCP SDK {@code randomPKCECodeVerifier()}
     * = {@code randomBytes(64).toString('base64url')}（auth.ts startAuthorization 链路）。
     *
     * <p>RFC 7636 §4.1：code_verifier 必须为 43-128 字符的
     * {@code [A-Za-z0-9-._~]} 无保留字符集。base64url(64 字节) = 86 字符，合规。
     *
     * @return 86 字符 base64url code_verifier（无填充）
     */
    public static String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 派生 PKCE code_challenge (S256) · CC original: MCP SDK {@code getPKCEChallenge(v, "S256")}
     * = {@code base64url(SHA-256(codeVerifier))}（RFC 7636 §4.2）。
     * 发送到授权端点的 code_challenge，配合 {@code code_challenge_method=S256}。
     *
     * @param codeVerifier {@link #generateCodeVerifier()} 生成的 verifier（ASCII 无保留字符）
     * @return base64url 无填充的 SHA-256 摘要
     */
    public static String s256Challenge(String codeVerifier) {
        Objects.requireNonNull(codeVerifier, "codeVerifier 不能为 null");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 构建授权 URL · CC original: MCP SDK {@code redirectToAuthorization}
     * (auth.ts:1900 附近，startAuthorization → redirectToAuthorization)。
     *
     * <p>RFC 6749 §4.1.1 授权码请求参数 + RFC 7636 §4.3 PKCE 扩展：
     * {@code response_type=code & client_id & redirect_uri & code_challenge &
     * code_challenge_method=S256 & state [& scope]}。
     * 值经 percent-encoding（RFC 3986 query 规范，空格用 %20 而非 '+'）。
     *
     * @param authorizationEndpoint 授权端点 URL（RFC 8414 metadata authorization_endpoint）
     * @param clientId              OAuth 客户端 ID
     * @param redirectUri           loopback 回调 URI（{@code http://localhost:<port>/callback}）
     * @param codeChallenge         S256 派生 code_challenge
     * @param state                 CSRF 防跨站 state
     * @param scope                 请求 scope（可为 null，无 scope 时不带参）
     * @return 完整授权 URL
     */
    public static String buildAuthorizationUrl(String authorizationEndpoint, String clientId,
            String redirectUri, String codeChallenge, String state, String scope) {
        StringBuilder sb = new StringBuilder(authorizationEndpoint);
        sb.append(authorizationEndpoint.contains("?") ? "&" : "?");
        sb.append("response_type=").append(urlEncode("code"));
        sb.append("&client_id=").append(urlEncode(clientId));
        sb.append("&redirect_uri=").append(urlEncode(redirectUri));
        sb.append("&code_challenge=").append(urlEncode(codeChallenge));
        sb.append("&code_challenge_method=").append(urlEncode("S256"));
        sb.append("&state=").append(urlEncode(state));
        if (scope != null && !scope.isBlank()) {
            sb.append("&scope=").append(urlEncode(scope));
        }
        return sb.toString();
    }

    /** RFC 3986 严格 percent-encoding（query 值用；空格 → %20，保留 unreserved 字符）。 */
    private static String urlEncode(String value) {
        // Java 10+ URLEncoder.encode(String, Charset) 不抛受检异常（UTF-8 恒可用）
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

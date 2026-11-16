package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.OutboundProxy;
import com.nexusai.model.provider.dto.ProviderDto;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Anthropic Claude 订阅（Claude Code OAuth）登录链路。
 *
 * <p><b>流程</b>：前端调 {@link #buildAuthorizeUrl(String)} 拿授权链接 → 用户在浏览器登录 →
 * 浏览器跳到 {@code http://localhost:53692/callback?code=...&state=...} → 本机
 * {@link ClaudeOAuthCallbackServer} 收到回调后调 {@link #onCallback(String, String)}
 * 换 token 并落库。53692 被占用（监听启动失败）时仍可退回<b>手动粘贴</b>：用户把整条回调 URL
 * （或裸 code）粘回前端 → {@link #complete(String, String)}。
 *
 * <p><b>⭐ state 就是 PKCE verifier</b>：这是上游 Claude Code 的协议形态（不是笔误）。故回调里
 * 的 {@code state} 不带 provider 前缀，无法像 Codex 那样靠前缀反查 ⇒ 回调时按「待完成登录的
 * verifier」全表比对（provider 规模个位数，成本可忽略）。
 *
 * <p><b>⭐ token 端点只吃 JSON body</b>：本类自己发 JSON，<b>不复用</b>
 * {@link OAuthTokenClient}（它发 form-urlencoded，是 Codex / Google / GitHub 的形态）。
 *
 * <p><b>一次性登录状态</b>：{@code providers.oauth_state} 列存
 * {@code [{"state":"<verifier>","verifier":"<verifier>"}, ...]}（多条 = 用户重复点登录时互不覆盖），
 * 成功后立即清空，防重放。
 *
 * <p><b>不打印任何 token / code / verifier</b>：日志只记 provider id、过期时刻等非敏感信息。
 */
@Service
public class ClaudeOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeOAuthService.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** PKCE verifier 原始随机字节数（32 字节 → Base64URL 43 字符，RFC 7636 §4.1 长度区间）。 */
    private static final int VERIFIER_BYTES = 32;

    /** 最多保留的待完成登录次数（重复点「登录」不会互相覆盖 PKCE verifier）。 */
    static final int MAX_PENDING_STATES = 5;

    /** token 端点超时（与 {@link OAuthTokenClient} 同量级）。 */
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(20);

    /** 失败时回显 / 记日志的响应体截断长度。 */
    private static final int BODY_TRUNCATE = 300;

    private static final String CLAUDE_SUBSCRIBE_SUCCESS = "Claude 订阅登录成功";

    private final ProviderService providerService;

    /**
     * 53692 回调监听器（惰性取）。
     *
     * <p>为什么用 {@link ObjectProvider}：监听器要回调本服务，本服务又要启动监听器 ⇒ 循环引用。
     * ObjectProvider 在构造期只拿句柄、不实例化目标 bean，环就断了。
     * 单元测试用包内两参构造器时不注入（null ⇒ 跳过启动，只走手动粘贴）。
     */
    private ObjectProvider<ClaudeOAuthCallbackServer> callbackServers;

    /** token 端点 HttpClient（⭐ 显式挂 {@link OutboundProxy#dynamicSelector()}，见 {@link #newHttpClient()}）。 */
    private final HttpClient httpClient;

    @Autowired
    public ClaudeOAuthService(ProviderService providerService,
            ObjectProvider<ClaudeOAuthCallbackServer> callbackServers) {
        this(providerService, newHttpClient());
        this.callbackServers = callbackServers;
    }

    ClaudeOAuthService(ProviderService providerService, HttpClient httpClient) {
        this.providerService = providerService;
        this.httpClient = httpClient;
    }

    /**
     * token 端点专用 client。
     *
     * <p>⭐ 必须用 {@link OutboundProxy#dynamicSelector()} 而不是
     * {@link OutboundProxy#selectorFor(String)}：后者只对白名单域名（openai/anthropic 系）生效，
     * {@code claude.ai} / {@code platform.claude.com} 不在表内，直连会在受限网络下失败。
     * 本 client 只发 OAuth 两个端点（都在境外），无条件按设置页「出站线路」走代理即正确；
     * ⛔ 不改 {@link OutboundProxy} 本身（它有别的通道在用）。
     */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(OutboundProxy.dynamicSelector())
            .build();
    }

    // ───────────────────────────── 登录 ─────────────────────────────

    /**
     * 生成授权链接：新建 PKCE verifier（= state）→ 存 {@code oauth_state} → 启动 53692 回调监听
     * → 拼 authorize URL。
     *
     * @param providerId provider id（必须存在，否则 NotFoundException）
     * @return 完整 authorize URL（前端直接打开浏览器）
     */
    public String buildAuthorizeUrl(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        String verifier = newVerifier();
        // 保留最近 N 次待完成登录：重复点「登录」会生成新 verifier，
        // 只存最后一次时，浏览器里第一次的授权链接回来就找不到 verifier ⇒ token 端点 400/403。
        List<AuthState> pending = readPendingStates(providerId);
        pending.add(new AuthState(verifier, verifier));
        if (pending.size() > MAX_PENDING_STATES) {
            pending = new ArrayList<>(pending.subList(pending.size() - MAX_PENDING_STATES, pending.size()));
        }
        providerService.writeOAuthState(providerId, encodeStates(pending));
        // 先起监听再返回 URL：否则浏览器回调比监听启动快时，用户会看到「无法访问此网站」
        boolean listening = startCallbackServer();
        log.info("已生成 Claude 订阅授权链接 provider={} 回调监听={}", providerId,
            listening ? "已启动" : "未启动（降级为手动粘贴）");
        return authorizeUrl(verifier);
    }

    /**
     * 拼 authorize URL（纯函数，无 IO ⇒ 单测可直接断言参数）。
     *
     * <p>⚠️ {@code state} 参数值 = PKCE verifier（上游协议如此，⛔ 不要换成独立随机 state）。
     *
     * @param verifier PKCE verifier（同时作为 state）
     * @return 完整 authorize URL
     */
    static String authorizeUrl(String verifier) {
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalArgumentException("PKCE verifier 不能为空");
        }
        StringBuilder sb = new StringBuilder(ClaudeOAuthProviderConfig.AUTHORIZATION_ENDPOINT);
        sb.append('?');
        appendParam(sb, "client_id", ClaudeOAuthProviderConfig.CLIENT_ID);
        appendParam(sb, "response_type", "code");
        appendParam(sb, "redirect_uri", ClaudeOAuthProviderConfig.REDIRECT_URI);
        appendParam(sb, "scope", String.join(" ", ClaudeOAuthProviderConfig.SCOPES));
        appendParam(sb, "code_challenge", codeChallenge(verifier));
        appendParam(sb, "code_challenge_method", "S256");
        appendParam(sb, "state", verifier);
        return sb.toString();
    }

    /** 启动 53692 回调监听；未注入（单元测试）/ 启动失败（端口占用）→ false，不抛异常。 */
    private boolean startCallbackServer() {
        if (callbackServers == null) {
            return false;
        }
        try {
            return callbackServers.getObject().start();
        } catch (RuntimeException e) {
            log.warn("[ClaudeOAuth] 回调监听启动失败，降级为手动粘贴: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 53692 回调入口（{@link ClaudeOAuthCallbackServer} 收到浏览器跳转后调用）。
     *
     * <p>回调只带 {@code state}(= verifier) / {@code code}，没有 provider 上下文 ⇒ 全表反查哪条
     * 在途登录的 verifier 与它相等，再用那条 verifier 换 token。
     *
     * @param state 回调 query 里的 state（= PKCE verifier）
     * @param code  回调 query 里的授权码
     * @throws IllegalArgumentException state 为空 / 登录状态不存在或被用过 / code 为空
     * @throws OAuthTokenExchangeError  token 端点返回错误
     */
    public void onCallback(String state, String code) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("回调里没有 state 参数，请重新登录");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("回调里没有 code 参数，请重新登录");
        }
        PendingOwner owner = findPendingOwner(state);
        exchangeAndSave(owner.providerId(), code.trim(), owner.authState());
    }

    /**
     * 用用户粘回的整条回调 URL（或裸 code）完成登录。
     *
     * @param providerId provider id
     * @param codeOrUrl  整条回调 URL 或裸授权码
     * @throws IllegalArgumentException 输入为空 / 无 code / state 不匹配 / 登录状态已被使用
     * @throws OAuthTokenExchangeError  token 端点返回错误
     */
    public void complete(String providerId, String codeOrUrl) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        CallbackInput callback = parseCallback(codeOrUrl);
        AuthState authState = findAuthState(providerId, callback.state());
        exchangeAndSave(providerId, callback.code(), authState);
    }

    /** 退出登录：清凭据 + 清在途登录状态（auth_mode 回落 api_key 由 ProviderService 负责）。 */
    public void logout(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        providerService.clearOAuth(providerId);
        providerService.writeOAuthState(providerId, null);
        log.info("Claude 订阅已退出登录 provider={}", providerId);
    }

    /**
     * 全表反查持有该 state 的 provider。
     *
     * <p>为什么不能靠前缀：state 就是 verifier（上游协议），没有 provider 信息。
     */
    private PendingOwner findPendingOwner(String state) {
        List<ProviderDto> providers;
        try {
            providers = providerService.listAll();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("读取登录状态失败，请重试", e);
        }
        PendingOwner hit = null;
        for (ProviderDto p : providers) {
            if (p == null || p.id() == null) {
                continue;
            }
            List<AuthState> pending;
            try {
                pending = decodeStates(providerService.readOAuthState(p.id()));
            } catch (IllegalArgumentException e) {
                // 该 provider 的 oauth_state 损坏 → 跳过（不代表别的 provider 没有）
                continue;
            }
            for (AuthState s : pending) {
                if (!s.state().equals(state)) {
                    continue;
                }
                if (hit == null) {
                    hit = new PendingOwner(p.id(), s);
                } else {
                    log.warn("[ClaudeOAuth] 多个 provider 持有同一 state，取第一个 provider={}（忽略 provider={}）",
                        hit.providerId(), p.id());
                }
                break;
            }
        }
        if (hit == null) {
            throw new IllegalArgumentException("登录状态不存在或已被使用，请重新点击登录获取授权链接");
        }
        return hit;
    }

    /** 读该 provider 的在途登录状态；无 / 损坏 → 空列表。 */
    private List<AuthState> readPendingStates(String providerId) {
        try {
            return decodeStates(providerService.readOAuthState(providerId));
        } catch (IllegalArgumentException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 按 state 找对应 verifier；state 为空（裸 code 场景）→ 用最近一次。
     *
     * @throws IllegalArgumentException 无待完成登录 / state 不匹配（过期旧链接）
     */
    private AuthState findAuthState(String providerId, String state) {
        List<AuthState> pending = readPendingStates(providerId);
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("登录状态不存在或已被使用，请重新点击登录获取授权链接");
        }
        if (state == null || state.isBlank()) {
            return pending.get(pending.size() - 1);
        }
        for (AuthState s : pending) {
            if (s.state().equals(state)) {
                return s;
            }
        }
        throw new IllegalArgumentException(
            "回调 state 与本地登录状态不一致（可能是过期的旧链接），请重新登录");
    }

    /** 换 token + 落库 + 消费一次性 state（complete 与 onCallback 共用同一份）。 */
    private void exchangeAndSave(String providerId, String code, AuthState authState) {
        OAuthTokens tokens = exchangeAuthorizationCode(code, authState.verifier(), authState.state());
        providerService.saveOAuthCredential(providerId, ProviderService.AUTH_MODE_CLAUDE_OAUTH,
            tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt(), null);
        // 一次性登录状态：成功即消费（失败保留，允许用户修正输入后重试）
        providerService.writeOAuthState(providerId, null);
        log.info("{} provider={} expiresAt={}", CLAUDE_SUBSCRIBE_SUCCESS, providerId, tokens.expiresAt());
    }

    // ───────────────────────────── token 端点（JSON） ─────────────────────────────

    /** 授权码交换（⭐ JSON body，不是 form-urlencoded）。 */
    private OAuthTokens exchangeAuthorizationCode(String code, String verifier, String state) {
        ObjectNode body = JSON.createObjectNode();
        body.put("grant_type", "authorization_code");
        body.put("client_id", ClaudeOAuthProviderConfig.CLIENT_ID);
        body.put("code", code);
        // 上游要求把 authorize 时用的 state（= verifier）原样带回来
        body.put("state", state == null || state.isBlank() ? verifier : state);
        body.put("redirect_uri", ClaudeOAuthProviderConfig.REDIRECT_URI);
        body.put("code_verifier", verifier);
        return postToken(httpClient, body, "authorization_code");
    }

    /**
     * POST token 端点（JSON body）→ 解析为 {@link OAuthTokens}。
     *
     * <p>失败语义（fail-loud）：非 2xx → {@link OAuthTokenExchangeError}，message / 日志里
     * <b>同时带 HTTP status 与响应体（截断 300）</b> —— 只记 status 会把
     * {@code unsupported_country_region_territory} 这类真正原因丢掉。
     *
     * @param client    HttpClient（实例与刷新器共用同一套代理口径）
     * @param body      JSON 请求体（含 code / verifier 等敏感值，本方法不打印 body）
     * @param grantType 仅用于日志（authorization_code / refresh_token）
     */
    static OAuthTokens postToken(HttpClient client, ObjectNode body, String grantType) {
        String endpoint = ClaudeOAuthProviderConfig.TOKEN_ENDPOINT;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TOKEN_TIMEOUT)
                // ⭐ Claude 的 token 端点只接受 JSON body（Codex/Google 是 form-urlencoded）
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = client.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String detail = truncate(resp.body(), BODY_TRUNCATE);
                // 诊断用：状态码 + 响应体（不含 code/verifier/token —— 那是请求体，不在响应里）
                log.warn("[ClaudeOAuth] token 端点返回非 2xx grantType={} HTTP {} body={}",
                    grantType, resp.statusCode(), detail);
                throw new OAuthTokenExchangeError(errorCodeOf(resp.body()),
                    "token 端点返回 HTTP " + resp.statusCode() + ": " + detail,
                    OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            }
            return parseTokens(resp.body());
        } catch (OAuthTokenExchangeError e) {
            throw e;
        } catch (Exception e) {
            log.warn("[ClaudeOAuth] token 端点请求失败 grantType={} endpoint={}: {}",
                grantType, endpoint, e.toString());
            throw new OAuthTokenExchangeError(null,
                "token 端点请求失败: " + e.toString(),
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
        }
    }

    /**
     * 解析 token 响应（{@code {access_token, refresh_token, expires_in}}）。
     *
     * <p>access_token / expires_in 缺失 → fail-loud（不落残缺凭据：没有过期时间就无法提前刷新，
     * 结局是长期 401）；refresh_token 缺失 → 允许（刷新时沿用旧值）。
     */
    static OAuthTokens parseTokens(String responseBody) {
        JsonNode json;
        try {
            json = JSON.readTree(responseBody == null ? "" : responseBody);
        } catch (IOException e) {
            throw new OAuthTokenExchangeError(null,
                "token 响应不是合法 JSON: " + truncate(responseBody, BODY_TRUNCATE),
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
        }
        String access = json.path("access_token").asText(null);
        if (access == null || access.isBlank()) {
            throw new OAuthTokenExchangeError(null,
                "token 响应缺少 access_token: " + truncate(responseBody, BODY_TRUNCATE),
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
        }
        long expiresIn = json.path("expires_in").asLong(0);
        if (expiresIn <= 0) {
            throw new OAuthTokenExchangeError(null,
                "token 响应缺少 expires_in: " + truncate(responseBody, BODY_TRUNCATE),
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
        }
        String refresh = json.path("refresh_token").asText(null);
        if (refresh != null && refresh.isBlank()) {
            refresh = null;
        }
        return new OAuthTokens(access, refresh, expiresAtFrom(System.currentTimeMillis(), expiresIn));
    }

    /**
     * 过期时刻口径（上游）：{@code now + expires_in*1000 - 5min}。
     *
     * <p>提前 5 分钟的理由：请求可能排队 / 重试，卡到「刚好没过期」再发就会 401。
     *
     * @param nowMillis         当前时刻（epoch 毫秒；抽成参数便于单测精确断言）
     * @param expiresInSeconds  token 响应里的 {@code expires_in}（秒）
     */
    static long expiresAtFrom(long nowMillis, long expiresInSeconds) {
        return nowMillis + expiresInSeconds * 1000L - ClaudeOAuthProviderConfig.EXPIRY_SKEW_MILLIS;
    }

    /** 从错误响应体里取 RFC 6749 {@code error}（取不到 → null，仅用于日志/文案归因）。 */
    private static String errorCodeOf(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode json = JSON.readTree(responseBody);
            String error = json.path("error").asText(null);
            return error == null || error.isBlank() ? null : error;
        } catch (Exception e) {
            return null;
        }
    }

    /** token 响应解析结果（expiresAt 已按「提前 5 分钟」口径换算）。 */
    record OAuthTokens(String accessToken, String refreshToken, long expiresAt) {
    }

    // ───────────────────────────── 刷新 ─────────────────────────────

    /**
     * access token 刷新器（注册为独立 {@link ProviderService.OAuthRefresher}）。
     *
     * <p><b>为什么是独立类</b>：{@code ProviderService} 注入 {@code List<OAuthRefresher>}，
     * 若本服务既依赖 {@code ProviderService} 又实现该接口就形成 Spring 循环依赖。单独一个只依赖
     * HttpClient 的组件可彻底避免循环。
     *
     * <p>刷新锁：与 {@code CodexOAuthRefresher} 一致，<b>不加锁</b>（本仓没有 DB 悲观锁工具，
     * 单用户本地场景重复刷新只会让先到的那个 refresh token 失效、后到的失败并保留旧凭据）。
     */
    @Component
    public static class ClaudeOAuthRefresher implements ProviderService.OAuthRefresher {

        private static final Logger log = LoggerFactory.getLogger(ClaudeOAuthRefresher.class);

        private final HttpClient httpClient;

        public ClaudeOAuthRefresher() {
            this(newHttpClient());
        }

        ClaudeOAuthRefresher(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        /** 本刷新器只服务 {@code claude_oauth}（Codex 那条走默认值）。 */
        @Override
        public String authMode() {
            return ProviderService.AUTH_MODE_CLAUDE_OAUTH;
        }

        @Override
        public Refreshed refresh(String refreshToken) {
            if (refreshToken == null || refreshToken.isBlank()) {
                return null;
            }
            try {
                ObjectNode body = JSON.createObjectNode();
                body.put("grant_type", "refresh_token");
                body.put("client_id", ClaudeOAuthProviderConfig.CLIENT_ID);
                body.put("refresh_token", refreshToken);
                OAuthTokens tokens = postToken(httpClient, body, "refresh_token");
                // 刷新响应可能不带新 refresh token ⇒ 传 null，由 ProviderService 沿用旧值
                return new Refreshed(tokens.accessToken(), tokens.refreshToken(),
                    tokens.expiresAt(), null);
            } catch (RuntimeException e) {
                // 消息里只有状态码 + 响应体，不含 token
                log.warn("[ClaudeOAuth] 刷新失败: {}", e.getMessage());
                return null;
            }
        }
    }

    // ─────────────────────── PKCE / state / 回调解析 ───────────────────────

    /** PKCE verifier：32 随机字节的 Base64URL（无填充）编码，43 字符。 */
    static String newVerifier() {
        byte[] bytes = new byte[VERIFIER_BYTES];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    /** PKCE challenge = Base64URL(SHA-256(verifier))（无填充，RFC 7636 §4.2）。 */
    static String codeChallenge(String verifier) {
        if (verifier == null || verifier.isEmpty()) {
            throw new IllegalArgumentException("PKCE verifier 不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return base64Url(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** {@code oauth_state} 列的一条：state（= verifier）+ verifier。 */
    record AuthState(String state, String verifier) {
    }

    /** 在途登录的归属：哪个 provider + 哪条 state。 */
    record PendingOwner(String providerId, AuthState authState) {
    }

    /** 多条待完成登录的序列化（{@code oauth_state} 列存 JSON 数组）。 */
    static String encodeStates(List<AuthState> states) {
        ArrayNode arr = JSON.createArrayNode();
        for (AuthState s : states) {
            ObjectNode n = JSON.createObjectNode();
            n.put("state", s.state());
            n.put("verifier", s.verifier());
            arr.add(n);
        }
        return arr.toString();
    }

    /**
     * 反序列化 {@code oauth_state} 列：兼容「单对象（旧格式）」与「数组」。
     *
     * @return 有效条目列表（缺字段的条目跳过，不抛）
     * @throws IllegalArgumentException 内容不是合法 JSON
     */
    static List<AuthState> decodeStates(String json) {
        List<AuthState> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("登录状态不是合法 JSON，请重新登录", e);
        }
        if (root.isArray()) {
            for (JsonNode n : root) {
                AuthState s = authStateOf(n);
                if (s != null) {
                    out.add(s);
                }
            }
        } else {
            AuthState s = authStateOf(root);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static AuthState authStateOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String state = node.path("state").asText(null);
        String verifier = node.path("verifier").asText(null);
        if (state == null || state.isBlank() || verifier == null || verifier.isBlank()) {
            return null;
        }
        return new AuthState(state, verifier);
    }

    /** 用户粘贴内容解析结果：code 必有，state 可 null（裸 code 场景）。 */
    record CallbackInput(String code, String state) {
    }

    /**
     * 解析用户粘贴的输入：整条回调 URL → 取 query 的 code/state；裸 code → state=null。
     *
     * @throws IllegalArgumentException 输入为空 / URL 无 code（或带 error 参数）
     */
    static CallbackInput parseCallback(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("请粘贴浏览器地址栏中的完整回调 URL（或授权码）");
        }
        if (!value.contains("://")) {
            // 裸授权码
            return new CallbackInput(value, null);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("回调地址格式无法解析，请复制浏览器地址栏中的完整 URL", e);
        }
        Map<String, String> query = queryParams(uri.getRawQuery());
        String code = query.get("code");
        if (code == null || code.isBlank()) {
            String error = query.get("error");
            if (error != null && !error.isBlank()) {
                throw new IllegalArgumentException("授权未完成：provider 返回 " + error);
            }
            throw new IllegalArgumentException("回调地址里没有 code 参数，请确认复制了完整的回调 URL");
        }
        return new CallbackInput(code.trim(), query.get("state"));
    }

    // ───────────────────────────── 内部工具 ─────────────────────────────

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 追加 URL query 参数（值做百分号编码；空格编码为 %20 而非 +）。 */
    private static void appendParam(StringBuilder sb, String name, String value) {
        if (sb.charAt(sb.length() - 1) != '?' && sb.charAt(sb.length() - 1) != '&') {
            sb.append('&');
        }
        String encoded = value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20");
        sb.append(name).append('=').append(encoded);
    }

    /** 解析 query 串为 map（同名取第一个；值做百分号解码）。 */
    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
            if (!result.containsKey(decodedKey)) {
                result.put(decodedKey, URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }
}

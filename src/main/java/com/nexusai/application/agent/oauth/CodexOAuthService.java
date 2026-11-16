package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.provider.ProviderService;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
 * OpenAI / ChatGPT 订阅（Codex）登录链路。
 *
 * <p><b>流程</b>：前端调 {@link #buildAuthorizeUrl(String)} 拿授权链接 → 用户在浏览器登录 →
 * 浏览器跳到 {@code http://localhost:1455/auth/callback?code=...&state=...} → 本机
 * {@link CodexOAuthCallbackServer} 收到回调后直接调 {@link #onCallback(String, String)}
 * 换 token 并落库（用户无需复制）。若 1455 被占用（监听启动失败），仍可退回<b>手动粘贴</b>：
 * 用户把整条回调 URL（或裸 code）粘回前端 → {@link #complete(String, String)}。
 *
 * <p><b>一次性登录状态</b>：{@code providers.oauth_state} 列存
 * {@code {"state":"...","verifier":"..."}}（PKCE verifier 必须活到回调那一步，故与 state 一起
 * 存在一次性列里）。{@code state} 形如 {@code <providerId>:<32 hex>} —— 回调本身没有 provider
 * 上下文，只能靠前缀反查。成功后立即清空，防重放。
 *
 * <p><b>不打印任何 token / code / verifier</b>：日志只记 provider id、账号 id 等非敏感信息。
 */
@Service
public class CodexOAuthService {

    private static final Logger log = LoggerFactory.getLogger(CodexOAuthService.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** PKCE verifier 原始随机字节数（32 字节 → Base64URL 43 字符，符合 RFC 7636 §4.1 长度区间）。 */
    private static final int VERIFIER_BYTES = 32;

    /** state 随机字节数（16 字节 → 32 位 hex）。 */
    private static final int STATE_BYTES = 16;

    private final ProviderService providerService;
    private final OAuthProviderConfig config;
    private final OAuthTokenClient tokenClient;
    /**
     * 1455 回调监听器（惰性取）。
     *
     * <p>为什么用 {@link ObjectProvider}：监听器要回调本服务，本服务又要启动监听器 ⇒ 循环引用。
     * Boot 3 默认禁止循环依赖，ObjectProvider 在构造期只拿句柄、不实例化目标 bean，环就断了。
     * 单元测试用包内三参构造器时不注入（null ⇒ 跳过启动，只走手动粘贴）。
     */
    private ObjectProvider<CodexOAuthCallbackServer> callbackServers;

    @Autowired
    public CodexOAuthService(ProviderService providerService,
            ObjectProvider<CodexOAuthCallbackServer> callbackServers) {
        this(providerService, new CodexOAuthProviderConfig(), new OAuthTokenClient());
        this.callbackServers = callbackServers;
    }

    CodexOAuthService(ProviderService providerService, OAuthProviderConfig config,
            OAuthTokenClient tokenClient) {
        this.providerService = providerService;
        this.config = config;
        this.tokenClient = tokenClient;
    }

    // ───────────────────────────── 登录 ─────────────────────────────

    /**
     * 生成授权链接：新建 state + PKCE verifier → 存 {@code oauth_state} →
     * 启动 1455 回调监听 → 拼 authorize URL。
     *
     * @param providerId provider id（必须存在，否则 NotFoundException）
     * @return 完整 authorize URL（前端直接打开浏览器）
     */
    public String buildAuthorizeUrl(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        String verifier = newVerifier();
        String state = newState(providerId);
        // 保留最近 N 次待完成登录：用户重复点「登录」会生成新 state/verifier，
        // 若只存最后一次，浏览器里第一次的授权链接回来时 verifier 已对不上 ⇒ token 端点 403。
        List<AuthState> pending = readPendingStates(providerId);
        pending.add(new AuthState(state, verifier));
        if (pending.size() > MAX_PENDING_STATES) {
            pending = new ArrayList<>(pending.subList(pending.size() - MAX_PENDING_STATES, pending.size()));
        }
        providerService.writeOAuthState(providerId, encodeStates(pending));
        // 先起监听再返回 URL：否则浏览器回调得比监听启动快时，用户会看到「无法访问此网站」
        boolean listening = startCallbackServer();

        StringBuilder sb = new StringBuilder(config.authorizationEndpoint());
        sb.append('?');
        appendParam(sb, "client_id", config.clientId());
        appendParam(sb, "response_type", "code");
        appendParam(sb, "redirect_uri", config.redirectUri());
        appendParam(sb, "scope", String.join(" ", config.scopes()));
        appendParam(sb, "code_challenge", codeChallenge(verifier));
        appendParam(sb, "code_challenge_method", "S256");
        appendParam(sb, "state", state);
        // Codex CLI 授权时固定携带的三个额外参数（pi 实测）
        appendParam(sb, "id_token_add_organizations", "true");
        appendParam(sb, "codex_cli_simplified_flow", "true");
        appendParam(sb, "originator", "codex_cli_rs");
        log.info("已生成 ChatGPT 订阅授权链接 provider={} 回调监听={}", providerId,
            listening ? "已启动" : "未启动（降级为手动粘贴）");
        return sb.toString();
    }

    /** 启动 1455 回调监听；未注入（单元测试）/ 启动失败（端口占用）→ false，不抛异常。 */
    private boolean startCallbackServer() {
        if (callbackServers == null) {
            return false;
        }
        try {
            return callbackServers.getObject().start();
        } catch (RuntimeException e) {
            log.warn("[CodexOAuth] 回调监听启动失败，降级为手动粘贴: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 用用户粘回的整条回调 URL（或裸 code）完成登录：校验 state → 换 token → 解析账号 → 落库。
     *
     * @param providerId provider id
     * @param codeOrUrl  整条回调 URL 或裸授权码
     * @throws IllegalArgumentException 输入为空 / 无 code / state 不匹配 / 登录状态已被使用 /
     *                                  token 里解析不出 chatgpt_account_id
     * @throws OAuthTokenExchangeError  token 端点返回错误
     */
    public void complete(String providerId, String codeOrUrl) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        CallbackInput callback = parseCallback(codeOrUrl);
        // 整条 URL 自带 state → 按该 state 找对应 verifier；裸 code → 用最近一次
        AuthState authState = findAuthState(providerId, callback.state());
        exchangeAndSave(providerId, callback.code(), authState);
    }

    /**
     * 1455 回调入口（{@link CodexOAuthCallbackServer} 收到浏览器跳转后调用）。
     *
     * <p>回调只带 {@code state}/{@code code}，没有 provider 上下文 ⇒ 从 state 前缀反查
     * provider，再与库里那条一次性 state 做<b>全串相等</b>校验（防 CSRF / 防旧链接重放）。
     *
     * @param state 回调 query 里的 state（形如 {@code <providerId>:<32 hex>}）
     * @param code  回调 query 里的授权码
     * @throws IllegalArgumentException state 格式不对 / 登录状态不存在或被用过 / state 不匹配 /
     *                                  code 为空 / token 里解析不出 chatgpt_account_id
     * @throws OAuthTokenExchangeError  token 端点返回错误
     */
    public void onCallback(String state, String code) {
        String providerId = providerIdFromState(state);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("回调里没有 code 参数，请重新登录");
        }
        AuthState authState = findAuthState(providerId, state);
        exchangeAndSave(providerId, code.trim(), authState);
    }

    /** 读库里那条一次性登录状态；不存在/已消费 → IllegalArgumentException。 */
    /** 最多保留的待完成登录次数（用户重复点「登录」时不会互相覆盖 PKCE verifier）。 */
    static final int MAX_PENDING_STATES = 5;

    /** 读取待完成登录列表（无 / 损坏 → 空列表，由调用方决定报错文案）。 */
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

    /** 换 token + 解析账号 + 落库 + 消费一次性 state（complete 与 onCallback 共用同一份）。 */
    private void exchangeAndSave(String providerId, String code, AuthState authState) {
        Map<String, String> params =
            config.exchangeParams(code, config.redirectUri(), authState.verifier());
        OAuthTokenResponse tokens =
            tokenClient.exchangeCodeForTokens(config.tokenEndpoint(), params, config);

        String accountId = accountIdFromAccessToken(tokens.accessToken());
        providerService.saveOAuthCredential(providerId, tokens.accessToken(),
            tokens.refreshToken(), tokens.expiresAt(), accountId);
        // 一次性登录状态：成功即消费（失败保留，允许用户修正输入后重试）
        providerService.writeOAuthState(providerId, null);
        log.info("ChatGPT 订阅登录成功 provider={} accountId={}", providerId, accountId);
    }

    /** 退出登录：清凭据 + 清在途登录状态。 */
    public void logout(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        providerService.clearOAuth(providerId);
        providerService.writeOAuthState(providerId, null);
        log.info("ChatGPT 订阅已退出登录 provider={}", providerId);
    }

    // ───────────────────────────── 刷新 ─────────────────────────────

    /**
     * access token 刷新器（注册为独立 {@link ProviderService.OAuthRefresher}）。
     *
     * <p><b>为什么是独立类（不是让 {@link CodexOAuthService} 自己实现）</b>：
     * {@code ProviderService} 里有一个 {@code @Autowired(required=false) OAuthRefresher} 字段，
     * 若本服务既依赖 {@code ProviderService} 又实现该接口，就形成 Spring 循环依赖。单独一个只依赖
     * {@link OAuthTokenClient} 的组件可彻底避免循环（故无需 {@code @Lazy}）。
     */
    @Component
    public static class CodexOAuthRefresher implements ProviderService.OAuthRefresher {

        private static final Logger log = LoggerFactory.getLogger(CodexOAuthRefresher.class);

        private final OAuthProviderConfig config;
        private final OAuthTokenClient tokenClient;

        public CodexOAuthRefresher() {
            this(new CodexOAuthProviderConfig(), new OAuthTokenClient());
        }

        CodexOAuthRefresher(OAuthProviderConfig config, OAuthTokenClient tokenClient) {
            this.config = config;
            this.tokenClient = tokenClient;
        }

        @Override
        public Refreshed refresh(String refreshToken) {
            if (refreshToken == null || refreshToken.isBlank()) {
                return null;
            }
            try {
                Map<String, String> params = new LinkedHashMap<>();
                // OAuthTokenClient 不自动补 grant_type，必须显式给（同授权码交换）
                params.put("grant_type", "refresh_token");
                params.put("refresh_token", refreshToken);
                params.put("client_id", config.clientId());

                OAuthTokenResponse tokens =
                    tokenClient.refreshTokens(config.tokenEndpoint(), params, config);
                if (tokens.expiresAt() == null) {
                    // provider 声明会过期，解析器保证非 null；真为 null 说明响应异常，不落残缺凭据
                    log.warn("[CodexOAuth] 刷新结果缺过期时间，放弃本次刷新（保留旧凭据）");
                    return null;
                }
                // 刷新响应可能不带账号信息；解不出就沿用库里旧值（ProviderService 侧处理 null）
                String accountId = null;
                try {
                    accountId = accountIdFromAccessToken(tokens.accessToken());
                } catch (IllegalArgumentException ignored) {
                    // 非 JWT 或没有 chatgpt_account_id：账号 id 不是刷新必需项
                }
                return new Refreshed(tokens.accessToken(), tokens.refreshToken(),
                    tokens.expiresAt(), accountId);
            } catch (RuntimeException e) {
                log.warn("[CodexOAuth] 刷新失败: {}", e.getMessage());
                return null;
            }
        }
    }

    // ─────────────────────── PKCE / state / JWT 工具 ───────────────────────

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
            // JVM 必带 SHA-256，走到这里说明运行环境异常
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** CSRF state：16 随机字节的 hex（32 字符）。 */
    static String newState() {
        byte[] bytes = new byte[STATE_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 带 provider 前缀的 state：{@code <providerId>:<32 hex>}。
     *
     * <p>为什么要前缀：1455 回调没有 provider 上下文，只能从 state 反查是哪个 provider 发起的登录。
     */
    static String newState(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id 不能为空");
        }
        return providerId + ':' + newState();
    }

    /**
     * 从带前缀的 state 取 providerId（第一个 {@code ':'} 之前的部分）。
     *
     * @throws IllegalArgumentException state 为空 / 没有前缀 / 前缀为空（按 fail-loud 处理，
     *                                  上层回调端点据此返回 400）
     */
    static String providerIdFromState(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("回调缺少 state 参数，请重新登录");
        }
        int sep = state.indexOf(':');
        if (sep <= 0) {
            throw new IllegalArgumentException("state 缺少 provider 前缀（可能是旧版授权链接），请重新登录");
        }
        if (sep == state.length() - 1) {
            throw new IllegalArgumentException("state 前缀后缺少随机串，请重新登录");
        }
        return state.substring(0, sep);
    }

    /** {@code oauth_state} 列的内容：state + PKCE verifier。 */
    record AuthState(String state, String verifier) {
    }

    /** 序列化为 JSON（存 {@code oauth_state} 列）。 */
    static String encodeState(AuthState authState) {
        ObjectNode node = JSON.createObjectNode();
        node.put("state", authState.state());
        node.put("verifier", authState.verifier());
        return node.toString();
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
     * 反序列化 {@code oauth_state} 列：兼容「单对象（旧格式）」与「数组（新格式）」。
     *
     * @return 有效条目列表（缺字段的条目跳过，不抛）
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
                if (s != null) out.add(s);
            }
        } else {
            AuthState s = authStateOf(root);
            if (s != null) out.add(s);
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

    /** 反序列化 {@code oauth_state} 列；内容不完整 → IllegalArgumentException。 */
    static AuthState decodeState(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("登录状态为空，请重新登录");
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("登录状态不是合法 JSON，请重新登录", e);
        }
        String state = node.path("state").asText(null);
        String verifier = node.path("verifier").asText(null);
        if (state == null || state.isBlank() || verifier == null || verifier.isBlank()) {
            throw new IllegalArgumentException("登录状态缺少 state/verifier，请重新登录");
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

    /**
     * 从 access token（JWT）的 payload 取 ChatGPT 账号 id。
     *
     * <p>claim 路径：{@code "https://api.openai.com/auth"} → {@code chatgpt_account_id}。
     * 解不出 → {@link IllegalArgumentException}（fail-loud：不存缺账号信息的凭据）。
     */
    static String accountIdFromAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("access_token 为空，无法解析 ChatGPT 账号信息");
        }
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2 || parts[1].isEmpty()) {
            throw new IllegalArgumentException("access_token 不是 JWT 形态，无法解析 ChatGPT 账号信息");
        }
        JsonNode payload;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            payload = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("access_token 的 JWT payload 不是合法 Base64URL", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("access_token 的 JWT payload 不是合法 JSON", e);
        }
        String accountId = payload.path(CodexOAuthProviderConfig.AUTH_CLAIM)
            .path("chatgpt_account_id").asText(null);
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException(
                "access_token 缺少 " + CodexOAuthProviderConfig.AUTH_CLAIM
                    + " 下的 chatgpt_account_id，无法确认订阅账号");
        }
        return accountId;
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
}

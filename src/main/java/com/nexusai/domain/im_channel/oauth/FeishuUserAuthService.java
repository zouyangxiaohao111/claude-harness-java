package com.nexusai.domain.im_channel.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书「以用户本人名义发消息」的 OAuth device flow（纯 HTTP）。
 *
 * <h2>三步（来自 larksuite/cli 源码，MIT）</h2>
 * <pre>
 * ① POST {accounts}/oauth/v1/device_authorization
 *      Authorization: Basic base64(appId:appSecret)
 *      form: client_id=&lt;appId&gt; &amp; scope=&lt;scopes&gt; offline_access
 *   → {device_code, user_code, verification_uri_complete, expires_in, interval}
 *
 * ② 用户在浏览器打开 verification_uri_complete 并点「同意」
 *
 * ③ POST {accounts}/oauth/v1/token
 *      Authorization: Basic base64(appId:appSecret)
 *      form: grant_type=urn:ietf:params:oauth:grant-type:device_code &amp; device_code=… &amp; client_id=…
 *   → {access_token, refresh_token, expires_in, scope}
 * </pre>
 *
 * <h2>⚠️ accounts 域不是 open-apis 域</h2>
 * 两者不是同一个域名：OAuth 在 {@code accounts.feishu.cn}，消息/通讯录在
 * {@code open.feishu.cn}。打错会得到 <b>404 page not found</b>（不是 403），
 * 看起来像「端点不存在」，很容易误判成"这个应用不支持 device flow"。
 *
 * <h2>⚠️ 应用必须已有 im:message.send_as_user</h2>
 * 权限是**应用级**的，由管理员在开放平台开通；没有时飞书在<b>授权页</b>就直接拒
 * （{@code 20027 当前应用权限不足}），连 token 都拿不到。代码里没有「自动申请权限」的
 * API（bot 调 scope-apply 恒 {@code 212002}）—— 只能把申请链接/授权链接递给用户。
 */
@Service
public class FeishuUserAuthService {

    private static final Logger log = LoggerFactory.getLogger(FeishuUserAuthService.class);

    /** accounts 域（OAuth）。 */
    static final String ACCOUNTS = "https://accounts.feishu.cn";
    static final String PATH_DEVICE_AUTHORIZATION = "/oauth/v1/device_authorization";
    /**
     * ⚠️ token 端点在 accounts 域下的 **open-apis 路径**（lark-cli 的 {@code PathOAuthTokenV2}）。
     *
     * <p>真机实测（2026-11-18）：
     * <pre>
     *   accounts.feishu.cn/oauth/v1/token                     → 404 page not found   ← 我曾猜这个
     *   accounts.feishu.cn/open-apis/authen/v2/oauth/token    → 400 invalid_grant（device_code 无效）
     *   accounts.feishu.cn/oauth/v1/access_token              → 200 20001 empty grant_type（不认 device flow）
     * </pre>
     * 「device_code is invalid」说明端点**认这个 grant_type**，只是我给的 code 是假的。
     */
    static final String PATH_TOKEN = "/open-apis/authen/v2/oauth/token";
    /** 查「我是谁」用 open-apis 域。 */
    static final String OPEN_APIS = "https://open.feishu.cn";
    static final String PATH_USER_INFO = "/open-apis/authen/v1/user_info";

    /**
     * 申请的全部 scope。
     *
     * <h2>为什么一次申请齐（发 + 读）</h2>
     * user token 携带的是**授权那一刻**的权限集合，我们后来加到常量里的新权限
     * **不会**自动进入旧 token ⇒ 只能让用户重新授权一次。
     * 所以宁可首次就申请全（多出来的权限用不上也不影响功能），减少「又缺一个、再授权一次」。
     *
     * <h2>逐项用途</h2>
     * <ul>
     *   <li>{@code im:message} —— 发消息（send_as_user 的前置）</li>
     *   <li>{@code im:message.send_as_user} —— **以你本人名义**发（对方看到的是你）</li>
     *   <li>{@code im:message.group_msg:get_as_user} —— 读群历史（用户身份）</li>
     *   <li>{@code im:message.p2p_msg:get_as_user} —— 读单聊历史（用户身份）</li>
     *   <li>{@code im:chat:read} —— 列群 / 会话信息</li>
     *   <li>{@code search:message} —— 按人/关键词搜消息（服务端过滤，比本地过滤快得多）</li>
     *   <li>{@code offline_access} —— 拿 refresh_token（自动续期，否则每 2 小时要重新授权）</li>
     * </ul>
     *
     * ⚠️ 读全量类的权限（{@code group_msg:get_as_user} 等）**通常需要管理员审批**，
     * 用户自己勾不上；申请时飞书可能只授予其中一部分 ⇒ 所以 {@code poll} 必须把
     * **实际授予的 scope** 存下来，用于判断「这张 token 够不够用」。
     */
    static final String DEFAULT_SCOPE = String.join(" ",
            "im:message",
            "im:message.send_as_user",
            "im:message.group_msg:get_as_user",
            "im:message.p2p_msg:get_as_user",
            "im:chat:read",
            "search:message",
            "offline_access",
            // 通讯录：拿到「谁在说话」的昵称。飞书拉历史/入站事件都不带昵称，
            // 只能靠这两个 scope 反查（lark-cli 的 shortcuts/contact/contact_get_user.go：
            // user 身份走 POST /contact/v3/users/basic_batch，需 basic_profile:readonly；
            // 应用身份走 GET /contact/v3/users/:id，需 base + contact.base）。
            // ⚠️ 即使有 scope，能否查到还取决于「应用通讯录可见范围」（管理员设的）。
            "contact:user.basic_profile:readonly",
            "contact:user.base:readonly",
            "contact:contact.base:readonly");

    /**
     * 缺少这些 scope 会导致「对应功能明确不可用」的清单（用于给用户看「缺什么」）。
     *
     * <p>为什么不拿 {@link #DEFAULT_SCOPE} 直接比：飞书可能授予我们没申请过的权限，
     * 也可能因为管理员未批而少给；真正要判的是「**功能必需项**是否都在」，
     * 而不是「是否和申请列表完全一致」。
     */
    private static final java.util.List<String> REQUIRED_SCOPES = java.util.List.of(
            "im:message.send_as_user",
            "im:message.group_msg:get_as_user",
            "im:message.p2p_msg:get_as_user",
            // [G2] 「拉全部会话 + 历史消息」必需：列本人所在群 / 读消息 / 搜索。
            // 旧 token 不含这三项 ⇒ status.missingScopes 非空 ⇒ 前端提示「重新授权」。
            "im:chat:read",
            "im:message",
            "search:message");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImChannelService channelService;

    @Autowired
    public FeishuUserAuthService(ImChannelService channelService) {
        this.channelService = channelService;
    }

    /** 申请 device code 的结果。 */
    public record StartResult(String deviceCode, String userCode, String verificationUri,
                              String verificationUriComplete, int interval, int expiresIn, String scope) {}

    /** 轮询结果：PENDING / AUTHORIZED / ERROR。 */
    public record PollResult(String state, String openId, String message) {
        static PollResult pending() { return new PollResult("PENDING", null, null); }
        static PollResult authorized(String openId) { return new PollResult("AUTHORIZED", openId, null); }
        static PollResult error(String msg) { return new PollResult("ERROR", null, msg); }
    }

    /**
     * 第一步：申请 device code。
     *
     * @param instanceId 渠道实例（用它的 appId/appSecret 做 Basic auth）
     * @throws ValidationException 实例不存在 / 凭证非法 / 平台拒绝
     */
    public StartResult start(String instanceId) {
        Credential c = credentialOf(instanceId);
        String form = "client_id=" + enc(c.appId())
                + "&scope=" + enc(DEFAULT_SCOPE);
        JsonNode root = postForm(ACCOUNTS + PATH_DEVICE_AUTHORIZATION, c, form, "申请 device code");

        String deviceCode = root.path("device_code").asText("");
        if (deviceCode.isBlank()) {
            // 飞书这里最常见的是 20027（应用没有 send_as_user）或 invalid_client
            String err = root.path("error").asText("");
            String desc = root.path("error_description").asText(root.path("msg").asText(""));
            throw new ValidationException("飞书拒绝授权申请：" + (err.isBlank() ? desc : err + " - " + desc));
        }
        int interval = root.path("interval").asInt(5);
        StartResult r = new StartResult(
                deviceCode,
                root.path("user_code").asText(""),
                root.path("verification_uri").asText(""),
                root.path("verification_uri_complete").asText(""),
                interval <= 0 ? 5 : interval,
                root.path("expires_in").asInt(600),
                DEFAULT_SCOPE);
        log.info("[FeishuOAuth] 已申请 device code instance={} userCode={} expiresIn={}",
                instanceId, r.userCode(), r.expiresIn());
        return r;
    }

    /**
     * 第二步：轮询换 token；成功后**立刻落库**（含 open_id）。
     *
     * <p>成功判据是拿到 {@code access_token}，不是 HTTP 200（飞书业务错误常配 200）。
     * {@code authorization_pending} = 用户还没点，属于正常中间态。
     */
    public PollResult poll(String instanceId, String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return PollResult.error("缺少 deviceCode");
        }
        Credential c = credentialOf(instanceId);
        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&device_code=" + enc(deviceCode)
                + "&client_id=" + enc(c.appId());
        JsonNode root = postFormAllowError(ACCOUNTS + PATH_TOKEN, c, form, "换取 user token");

        String accessToken = root.path("access_token").asText("");
        String err = root.path("error").asText("");
        if (accessToken.isBlank()) {
            // ⚠️ 飞书把「还没点同意」用 **HTTP 400** 返回（code 20094 / authorization_pending），
            // 如果按 HTTP 状态当失败，用户明明还在授权页上就会被判「授权失败」。
            if ("authorization_pending".equals(err)) {
                return PollResult.pending();
            }
            // 客户端轮询过快：同样属于「还没完成」，继续等即可
            if ("slow_down".equals(err)) {
                return PollResult.pending();
            }
            String desc = root.path("error_description").asText(root.path("msg").asText(""));
            return PollResult.error((err.isBlank() ? "飞书未返回 access_token" : err + " - " + desc));
        }

        // 过期时间：expires_in 秒（飞书给的是相对秒数）
        int expiresIn = root.path("expires_in").asInt(0);
        String expiresAt = expiresIn > 0
                ? OffsetDateTime.now().plusSeconds(expiresIn).toString()
                : null;

        // 查「授权的是谁」——没有 open_id 就没法用这个 token 给自己/别人定位
        //
        // 顺便把 name 也取下来（V314）：这是**唯一**能拿到昵称的地方 ——
        // 入站事件不带昵称、单聊 chat_id 没名字、应用身份查通讯录在权限范围为空时
        // 只返回 open_id/union_id（三条都真机验过）。user token 查的是「授权人自己」，
        // 不受应用通讯录范围限制。
        String openId = null;
        String displayName = null;
        try {
            JsonNode me = getJson(OPEN_APIS + PATH_USER_INFO, accessToken, "查当前授权用户");
            JsonNode data = me.path("data");
            openId = data.path("open_id").asText("");
            if (openId.isBlank()) {
                openId = null;
            }
            String name = data.path("name").asText("");
            if (!name.isBlank()) {
                displayName = name;
            }
            log.info("[FeishuOAuth] user_info 字段集 instance={} keys={} name={}",
                    instanceId, data.fieldNames().hasNext() ? joinFieldNames(data) : "[]", displayName);
        } catch (RuntimeException e) {
            // 查不到身份不该让整个授权失败：token 本身是有效的
            log.warn("[FeishuOAuth] 查 user_info 失败（token 仍然有效）instance={} err={}",
                    instanceId, e.toString());
        }

        // 飞书 token 响应带 scope（本次**实际授予**的集合，可能少于申请的）——
        // 存下来才能判断「这张 token 缺哪些能力」，而不是等某次调用报权限错。
        String grantedScope = root.path("scope").asText("");
        channelService.saveUserToken(instanceId,
                accessToken,
                root.path("refresh_token").asText(""),
                expiresAt,
                openId,
                displayName,
                grantedScope);
        log.info("[FeishuOAuth] 实际授予 scope instance={} scope={}", instanceId, grantedScope);
        log.info("[FeishuOAuth] 用户授权完成 instance={} openId={} expiresAt={}",
                instanceId, openId, expiresAt);
        return PollResult.authorized(openId);
    }

    /**
     * 这张 token 是否具备「全部功能必需」的 scope。
     *
     * @return 缺少的 scope 列表；空 = 齐全（或无法判断时按「齐全」返回 —— 见下）
     *
     * <p>⚠️ 没有 scope 记录（V316 之前授权的老数据）时返回**空列表**（当作齐全）：
     * 这时我们无法判断它缺不缺，误报会逼用户白授权一次；真缺的会在调用时降级，
     * 由 {@code FeishuSendStrategy} / fetcher 的降级提示兜住。
     */
    public java.util.List<String> missingScopes(String instanceId) {
        java.util.Optional<String> granted = channelService.userScopeOf(instanceId);
        if (granted.isEmpty()) {
            return java.util.List.of();
        }
        java.util.Set<String> have = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList(granted.get().split("\\s+")));
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String need : REQUIRED_SCOPES) {
            if (!have.contains(need)) {
                missing.add(need);
            }
        }
        return missing;
    }

    /**
     * 当前授权状态（前端开关旁显示「已授权 / 未授权」）。
     *
     * @return {@code {authorized, openId}}；未授权时 openId 为 null
     */
    public Map<String, Object> status(String instanceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<String> token = channelService.userTokenOf(instanceId);
        out.put("authorized", token.isPresent());
        out.put("openId", channelService.userOpenIdOf(instanceId).orElse(null));
        return out;
    }

    /** 取消授权（前端「取消授权」按钮）。 */
    public void revoke(String instanceId) {
        channelService.clearUserToken(instanceId);
    }

    // ═════════════════════ 内部 ═════════════════════

    private record Credential(String appId, String appSecret) {}

    private Credential credentialOf(String instanceId) {
        String cred = channelService.credentialOf(instanceId)
                .orElseThrow(() -> new ValidationException("渠道实例不存在或没有凭证：" + instanceId));
        try {
            JsonNode c = JSON.readTree(cred);
            String appId = firstText(c, "appId", "app_id");
            String appSecret = firstText(c, "appSecret", "app_secret");
            if (appId.isBlank() || appSecret.isBlank()) {
                throw new ValidationException("飞书凭证缺少 appId/appSecret");
            }
            return new Credential(appId, appSecret);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("飞书凭证不是合法 JSON");
        }
    }

    private static JsonNode postForm(String url, Credential c, String form, String what) {
        String basic = Base64.getEncoder().encodeToString(
                (c.appId() + ":" + c.appSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Basic " + basic)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() / 100 != 2) {
                throw new ValidationException(what + " 失败：HTTP " + resp.statusCode() + " " + snippet(raw));
            }
            return JSON.readTree(raw);
        } catch (ValidationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException(what + " 被中断");
        } catch (Exception e) {
            // ⛔ 不拼 e.getMessage()（可能含请求片段）
            throw new ValidationException(what + " 失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 换票专用：**400 也解析 body**。
     *
     * <p>设备码流程里「还没点同意」= HTTP 400 + {@code error=authorization_pending}，
     * 这是正常中间态而非失败。用 {@link #postForm} 会把整个 body 丢掉、直接抛
     * 「换取 user token 失败：HTTP 400 …」，用户明明在授权页上却被判失败（2026-11-23 真机报障）。
     */
    private static JsonNode postFormAllowError(String url, Credential c, String form, String what) {
        String basic = Base64.getEncoder().encodeToString(
                (c.appId() + ":" + c.appSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Basic " + basic)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() / 100 == 2 || resp.statusCode() == 400) {
                return JSON.readTree(raw);
            }
            throw new ValidationException(what + " 失败：HTTP " + resp.statusCode() + " " + snippet(raw));
        } catch (ValidationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException(what + " 被中断");
        } catch (Exception e) {
            throw new ValidationException(what + " 失败：" + e.getClass().getSimpleName());
        }
    }

    private static JsonNode getJson(String url, String bearer, String what) {
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + bearer)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return JSON.readTree(resp.body() == null ? "{}" : resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException(what + " 被中断");
        } catch (Exception e) {
            throw new ValidationException(what + " 失败：" + e.getClass().getSimpleName());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return "";
    }

    private static String snippet(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** 把 JsonNode 的字段名拼成逗号串（诊断「到底返回了哪些字段」用）。 */
    private static String joinFieldNames(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        node.fieldNames().forEachRemaining(n -> {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        });
        return sb.toString();
    }
}

package com.nexusai.apis.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImContactCandidate;
import com.nexusai.domain.im_channel.catalog.impl.FeishuContactSearchStrategy;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【排障探针】飞书通讯录与发送的形态探测。
 *
 * <h2>要回答的问题</h2>
 * <ol>
 *   <li>一键创建的飞书应用，<b>应用通讯录范围</b>里到底有谁？（管理员没配 ⇒ 空列表）</li>
 *   <li>{@code GET /contact/v3/users} 返回哪些字段？{@code open_id} 拿得到吗？</li>
 *   <li>{@code receive_id_type=open_id} 能不能真的发到人？</li>
 * </ol>
 *
 * <h2>⚠️ 只在 dev profile 注册（安全边界）</h2>
 * 本类能**绕过所有业务校验直接给飞书发消息**，因此不能出现在生产/场景版里。
 * 只有本地开发（{@code application.yml} 的 {@code spring.profiles.default=dev}）才注册。
 *
 * <h2>⛔ 诊断必须打全量字段</h2>
 * 企微那次只打自选字段，得出「两个会话无法区分」的错误结论，被用户截图推翻。
 * 这里 {@code /contacts} 会把第一条用户记录的全部字段名和值都吐出来（值做长度截断）。
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/im/probe/feishu")
public class FeishuProbeController {

    private static final Logger log = LoggerFactory.getLogger(FeishuProbeController.class);

    /** open-apis 域：会话/通讯录/发消息。 */
    private static final String BASE = "https://open.feishu.cn";
    /** accounts 域：OAuth（device_authorization / token / app registration）。
     *  ⚠️ 与 BASE 不是同一个域名 —— 打错会得到 404 page not found。 */
    private static final String ACCOUNTS = "https://accounts.feishu.cn";
    /** 诊断里允许出现的最长单值长度（防止整段 JSON 塞爆响应）。 */
    private static final int VAL_LIMIT = 120;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ImChannelService channelService;
    @Autowired private FeishuTokenService tokenService;
    @Autowired private FeishuContactSearchStrategy contactStrategy;

    /**
     * 通讯录探测：先打原始接口（打印全量字段），再走一遍策略（打印命中）。
     *
     * <p>body：{@code {"instanceId":"imc-...","keyword":"张","rawLimit":3}}
     */
    @PostMapping("/contacts")
    public Map<String, Object> contacts(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String instanceId = body.get("instanceId");
        String keyword = body.get("keyword") == null ? "" : body.get("keyword");
        try {
            String token = tokenOf(instanceId);

            // ① 原始接口：只拉一页，打印字段形状
            JsonNode data = getJson(token, BASE + "/open-apis/contact/v3/users"
                    + "?page_size=100&user_id_type=open_id", "拉通讯录原始页");
            JsonNode items = data.path("items");
            int total = items.isArray() ? items.size() : 0;
            out.put("rawCount", total);
            out.put("hasMore", data.path("has_more").asBoolean(false));
            out.put("firstFieldNames", fieldNames(items.path(0)));
            List<Map<String, Object>> samples = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode u : items) {
                    if (samples.size() >= 3) break;
                    samples.add(flatten(u));
                }
            }
            out.put("samples", samples);
            if (total == 0) {
                out.put("hint", "应用通讯录范围为空。这不是「搜索失败」——"
                        + "管理员需在开放平台把应用的通讯录权限范围配上，才能看到人。");
            }

            // ② 走策略：看过滤后命中几个
            List<ImContactCandidate> hits = contactStrategy.search(instanceId, keyword);
            List<Map<String, Object>> hitViews = new ArrayList<>();
            for (ImContactCandidate c : hits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", c.userId());
                m.put("name", c.name());
                m.put("alias", c.alias());
                m.put("departments", c.departments());
                hitViews.add(m);
            }
            out.put("strategyHits", hitViews);
            out.put("strategyHitCount", hitViews.size());
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
            log.warn("[FeishuProbe] 通讯录探测失败", e);
        }
        return out;
    }

    /**
     * 发送探测：按 id 形态自动选 {@code receive_id_type}，真发一条。
     *
     * <p>body：{@code {"instanceId":"imc-...","receiveId":"ou_xxx","text":"探针"}}
     */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String instanceId = body.get("instanceId");
        String receiveId = body.get("receiveId");
        String text = body.getOrDefault("text", "飞书探针消息");
        try {
            String token = tokenOf(instanceId);
            // 与 FeishuSendStrategy.receiveIdTypeOf 同一规则（那边是包私有，探针在 dev profile 里复刻三行）
            String idType = receiveIdTypeOf(receiveId);
            out.put("receiveId", receiveId);
            out.put("receiveIdType", idType);

            var inner = JSON.createObjectNode();
            inner.put("text", text);
            var req = JSON.createObjectNode();
            req.put("receive_id", receiveId);
            req.put("msg_type", "text");
            req.put("content", inner.toString());   // 🔴 content 必须是字符串

            JsonNode root = postJson(token, BASE + "/open-apis/im/v1/messages?receive_id_type=" + idType,
                    req.toString(), "发消息");
            out.put("platformCode", root.path("code").asInt(-1));
            out.put("platformMsg", root.path("msg").asText(""));
            out.put("messageId", root.path("data").path("message_id").asText(""));
            out.put("ok", root.path("code").asInt(-1) == 0);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
            log.warn("[FeishuProbe] 发送探测失败", e);
        }
        return out;
    }

    /**
     * 会话探测：机器人已加入的会话（含 chat_id / chat_mode / 名字）。
     *
     * <p>body：{@code {"instanceId":"imc-..."}}
     */
    @PostMapping("/chats")
    public Map<String, Object> chats(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String token = tokenOf(body.get("instanceId"));
            JsonNode data = getJson(token,
                    BASE + "/open-apis/im/v1/chats?page_size=100", "拉会话列表");
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode c : data.path("items")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chat_id", c.path("chat_id").asText(""));
                m.put("name", c.path("name").asText(""));
                m.put("chat_mode", c.path("chat_mode").asText(""));
                m.put("chat_type", c.path("chat_type").asText(""));
                items.add(m);
            }
            out.put("total", items.size());
            out.put("items", items);
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /**
     * 查**单个会话**的信息（补名字用）· body：{@code {"instanceId":"imc-...","chatId":"oc_..."}}
     *
     * <h2>要回答的问题</h2>
     * 入站事件只给 {@code chat_id} 不给会话名 ⇒ 想补名字就得另查。
     * 本端点验 {@code GET /open-apis/im/v1/chats/{chat_id}} —— 飞书文档说它
     * **只支持群聊**，单聊大概率 404 或 name 为空。先看真值再决定用哪条路。
     */
    @PostMapping("/chat-info")
    public Map<String, Object> chatInfo(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String token = tokenOf(body.get("instanceId"));
            String chatId = body.get("chatId");
            JsonNode data = getJson(token,
                    BASE + "/open-apis/im/v1/chats/" + URLEncoder.encode(chatId, StandardCharsets.UTF_8),
                    "查群信息");
            out.put("ok", true);
            out.put("raw", data);
            out.put("name", data.path("name").asText(""));
            out.put("chat_mode", data.path("chat_mode").asText(""));
            out.put("chat_type", data.path("chat_type").asText(""));
            out.put("owner_id", data.path("owner_id").asText(""));
            out.put("user_count", data.path("user_count").asText(""));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /**
     * 查**单个用户**的名字（单聊补名字的正路）·
     * body：{@code {"instanceId":"imc-...","openId":"ou_..."}}
     *
     * <h2>为什么单聊必须走这里</h2>
     * 飞书单聊的 {@code chat_id}（{@code oc_...}）没有名字 —— 名字在**对方这个人**身上。
     * 入站事件带了 {@code sender.sender_id.open_id} ⇒ 用它在通讯录里反查 {@code name}。
     * ⚠️ 需要应用有通讯录读取权限；一键建的应用**默认没有**，权限范围为空时会返回空列表/无权限。
     */
    @PostMapping("/user-info")
    public Map<String, Object> userInfo(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String token = tokenOf(body.get("instanceId"));
            String openId = body.get("openId");
            JsonNode data = getJson(token,
                    BASE + "/open-apis/contact/v3/users/" + URLEncoder.encode(openId, StandardCharsets.UTF_8)
                            + "?user_id_type=open_id",
                    "查用户");
            out.put("ok", true);
            out.put("raw", data);
            JsonNode u = data.path("user");
            out.put("name", u.path("name").asText(""));
            out.put("en_name", u.path("en_name").asText(""));
            out.put("open_id", u.path("open_id").asText(""));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /**
     * 用**已授权的 user token** 查 {@code authen/v1/user_info}（打全量字段）。
     *
     * <h2>要回答的问题</h2>
     * 入站补名字到底有没有戏 —— 应用身份查通讯录拿不到 {@code name}（真机验过），
     * 那 user token 查自己呢？这个端点就是不重新授权也能看到答案的地方。
     */
    @PostMapping("/me")
    public Map<String, Object> me(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String instanceId = body.get("instanceId");
            String userToken = channelService.userTokenOf(instanceId)
                    .orElseThrow(() -> new IllegalStateException("该实例没有可用的 user token（先授权）"));
            JsonNode data = getJson(userToken, BASE + "/open-apis/authen/v1/user_info", "查授权人资料");
            out.put("ok", true);
            out.put("raw", data);
            out.put("name", data.path("data").path("name").asText(""));
            out.put("open_id", data.path("data").path("open_id").asText(""));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    // ═════════════════════ OAuth（以用户本人名义发）═════════════════════

    /**
     * 放开应用通讯录权限范围（排障用）。
     *
     * <h2>为什么需要它</h2>
     * 即使拿到了 {@code contact:user.base:readonly} 这类 scope，
     * {@code GET /contact/v3/users/:id} 仍可能只返回 {@code open_id}/{@code union_id}、没有 {@code name} ——
     * 因为还有一层「应用通讯录权限范围」（默认可能是空/仅自己）。
     * 这个范围**有 API 可改**（不是只能控制台）：
     * <pre>
     * PATCH /open-apis/application/v6/applications/{app_id}/contacts_range
     *   {"contacts_range_type": "all"}   // all = 全部成员；some = 部分；equal_to_availability = 跟可用性走
     * </pre>
     * ⚠️ 该接口要求「更新应用通讯录权限范围」权限，**仅自建应用**可用。
     *
     * @param body {@code {instanceId, rangeType?}} —— rangeType 缺省 {@code all}
     */
    @PostMapping("/contacts-range")
    public Map<String, Object> contactsRange(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String instanceId = body.get("instanceId");
            String rangeType = body.getOrDefault("rangeType", "all");
            Cred cred = credOf(instanceId);
            String token = tokenService.tokenFor(instanceId, cred.appId(), cred.appSecret());
            String payload = JSON.writeValueAsString(Map.of("contacts_range_type", rangeType));
            JsonNode root = patchJson(token,
                    BASE + "/open-apis/application/v6/applications/" + cred.appId() + "/contacts_range",
                    payload, "更新通讯录权限范围");
            out.put("platformCode", root.path("code").asInt(-1));
            out.put("platformMsg", root.path("msg").asText(""));
            out.put("rangeType", rangeType);
            out.put("ok", root.path("code").asInt(-1) == 0);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("[FeishuProbe] 更新通讯录范围失败: {}", e.toString());
        }
        return out;
    }

    /**
     * 查应用信息：确认这个一键建的应用**有没有** {@code im:message.send_as_user}。
     *
     * <p>body：{@code {"instanceId":"imc-..."}}
     */
    @PostMapping("/appinfo")
    public Map<String, Object> appInfo(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String instanceId = body.get("instanceId");
            Cred cred = credOf(instanceId);
            out.put("appId", cred.appId());
            String token = tokenService.tokenFor(instanceId, cred.appId(), cred.appSecret());
            JsonNode root = getJson(token,
                    BASE + "/open-apis/application/v6/applications/" + cred.appId() + "?lang=zh_cn",
                    "查应用信息");
            out.put("platformCode", root.path("code").asInt(-1));
            out.put("platformMsg", root.path("msg").asText(""));
            JsonNode app = root.path("data").path("app");
            out.put("appName", app.path("app_name").asText(""));
            out.put("fieldNames", fieldNames(app));
            Map<String, Object> appView = flatten(app);
            // scopes 是诊断核心（要看清有没有 im:message.send_as_user），⛔ 不能按 120 字截断
            appView.put("scopes", app.path("scopes").asText(""));
            out.put("app", appView);
            out.put("ok", root.path("code").asInt(-1) == 0);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /**
     * 第一步：申请 device code。
     *
     * <p>body：{@code {"instanceId":"imc-...","scope":"im:message im:message.send_as_user"}}
     * <p>返回 {@code verificationUriComplete} ⇒ 在浏览器里打开、点「同意」。
     */
    @PostMapping("/oauth/start")
    public Map<String, Object> oauthStart(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String instanceId = body.get("instanceId");
            Cred cred = credOf(instanceId);
            String scope = body.get("scope");
            if (scope == null || scope.isBlank()) {
                // 用户身份发消息：飞书要求同时具备这两个
                scope = "im:message im:message.send_as_user";
            }
            if (!scope.contains("offline_access")) {
                scope = scope + " offline_access";
            }
            String form = "client_id=" + URLEncoder.encode(cred.appId(), StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
            String basic = Base64.getEncoder().encodeToString(
                    (cred.appId() + ":" + cred.appSecret()).getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(
                            URI.create(ACCOUNTS + "/oauth/v1/device_authorization"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Basic " + basic)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = resp.body() == null ? "" : resp.body();
            out.put("httpStatus", resp.statusCode());
            out.put("raw", snippet(raw));
            JsonNode root = JSON.readTree(raw);
            out.put("deviceCode", root.path("device_code").asText(""));
            out.put("userCode", root.path("user_code").asText(""));
            out.put("verificationUri", root.path("verification_uri").asText(""));
            out.put("verificationUriComplete", root.path("verification_uri_complete").asText(""));
            out.put("interval", root.path("interval").asInt(5));
            out.put("expiresIn", root.path("expires_in").asInt(0));
            out.put("scope", scope);
            out.put("ok", !root.path("device_code").asText("").isBlank());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /**
     * 第二步：轮询换 user token，拿到后**立刻试发一条**（验证 send_as_user 权限真的生效）。
     *
     * <p>body：{@code {"instanceId":"imc-...","deviceCode":"v1:...","receiveId":"ou_或oc_","text":"..."}}
     */
    @PostMapping("/oauth/poll")
    public Map<String, Object> oauthPoll(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String instanceId = body.get("instanceId");
            Cred cred = credOf(instanceId);
            String deviceCode = body.get("deviceCode");
            if (deviceCode == null || deviceCode.isBlank()) {
                out.put("ok", false);
                out.put("error", "缺少 deviceCode");
                return out;
            }
            String form = "grant_type=" + URLEncoder.encode(
                            "urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                    + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(cred.appId(), StandardCharsets.UTF_8);
            String basic = Base64.getEncoder().encodeToString(
                    (cred.appId() + ":" + cred.appSecret()).getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(
                            URI.create(ACCOUNTS + "/open-apis/authen/v2/oauth/token"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Basic " + basic)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = resp.body() == null ? "" : resp.body();
            out.put("httpStatus", resp.statusCode());
            JsonNode root = JSON.readTree(raw);
            String accessToken = root.path("access_token").asText("");
            out.put("error", root.path("error").asText(""));
            out.put("errorDescription", root.path("error_description").asText(""));
            out.put("scope", root.path("scope").asText(""));
            out.put("tokenType", root.path("token_type").asText(""));
            out.put("hasAccessToken", !accessToken.isBlank());
            if (accessToken.isBlank()) {
                out.put("raw", snippet(raw));
                out.put("ok", false);
                return out;
            }

            // 拿到 user token ⇒ 先查「我是谁」，再用自己的 open_id 发一条
            // （会话列表此刻是空的，所以不能靠 chat_id；给自己发 = 机器人↔你的私聊）
            String receiveId = body.get("receiveId");
            if (receiveId == null || receiveId.isBlank()) {
                try {
                    JsonNode me = getJson(accessToken, BASE + "/open-apis/authen/v1/user_info", "查当前用户");
                    JsonNode d = me.path("data");
                    receiveId = d.path("open_id").asText("");
                    out.put("myOpenId", receiveId);
                    out.put("myName", d.path("name").asText(""));
                    out.put("myUserInfoCode", me.path("code").asInt(-1));
                } catch (Exception e) {
                    out.put("userInfoError", e.toString());
                }
            }
            if (receiveId != null && !receiveId.isBlank()) {
                String text = body.getOrDefault("text", "【探针】这条是以用户本人名义发出的");
                String idType = receiveIdTypeOf(receiveId);
                var inner = JSON.createObjectNode();
                inner.put("text", text);
                var req = JSON.createObjectNode();
                req.put("receive_id", receiveId);
                req.put("msg_type", "text");
                req.put("content", inner.toString());
                JsonNode sendRoot = postJson(accessToken,
                        BASE + "/open-apis/im/v1/messages?receive_id_type=" + idType,
                        req.toString(), "以用户身份发消息");
                out.put("sendReceiveIdType", idType);
                out.put("sendCode", sendRoot.path("code").asInt(-1));
                out.put("sendMsg", sendRoot.path("msg").asText(""));
                out.put("sendMessageId", sendRoot.path("data").path("message_id").asText(""));
            }
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /** 实例凭证（appId / appSecret）。 */
    private record Cred(String appId, String appSecret) {}

    private Cred credOf(String instanceId) throws ImSendException {
        String cred = channelService.credentialOf(instanceId)
                .orElseThrow(() -> new ImSendException("IM_INSTANCE_NOT_FOUND",
                        "渠道实例不存在或未配置凭证：" + instanceId));
        try {
            JsonNode c = JSON.readTree(cred);
            return new Cred(
                    c.path("appId").asText(c.path("app_id").asText("")),
                    c.path("appSecret").asText(c.path("app_secret").asText("")));
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "飞书凭证不是合法 JSON");
        }
    }

    // ═════════════════════ 内部 ═════════════════════

    /** 与 {@code FeishuSendStrategy.receiveIdTypeOf} 同一规则（那边包私有，探针复刻以便对照） */
    private static String receiveIdTypeOf(String peerId) {
        String v = peerId == null ? "" : peerId.trim();
        if (v.startsWith("ou_")) return "open_id";
        if (v.indexOf('@') > 0 || v.endsWith("@")) return "email";
        return "chat_id";
    }

    private String tokenOf(String instanceId) throws ImSendException {        String cred = channelService.credentialOf(instanceId)
                .orElseThrow(() -> new ImSendException("IM_INSTANCE_NOT_FOUND",
                        "渠道实例不存在或未配置凭证：" + instanceId));
        try {
            JsonNode c = JSON.readTree(cred);
            return tokenService.tokenFor(instanceId,
                    c.path("appId").asText(c.path("app_id").asText("")),
                    c.path("appSecret").asText(c.path("app_secret").asText("")));
        } catch (ImSendException e) {
            throw e;
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "飞书凭证不是合法 JSON");
        }
    }

    private JsonNode getJson(String token, String url, String what) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return unwrap(resp, what);
    }

    private JsonNode patchJson(String token, String url, String body, String what) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return unwrap(resp, what);
    }

    private JsonNode postJson(String token, String url, String body, String what) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return unwrap(resp, what);
    }

    /** 判成功看 {@code code==0}（飞书业务错误常配 HTTP 200）；返回整个 root 供调用方看 code。 */
    private JsonNode unwrap(HttpResponse<String> resp, String what) throws Exception {
        String raw = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(what + " HTTP " + resp.statusCode() + "：" + snippet(raw));
        }
        return JSON.readTree(raw);
    }

    /** 打印对象的全部字段名（诊断口径：⛔ 不能只打自选字段）。 */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null && node.isObject()) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) names.add(it.next());
        }
        return names;
    }

    /** 把一条记录压平成「字段 → 长度截断后的值」，供人眼扫。 */
    private static Map<String, Object> flatten(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return m;
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            JsonNode v = node.get(k);
            String s = v.isContainerNode() ? v.toString() : v.asText("");
            m.put(k, s.length() <= VAL_LIMIT ? s : s.substring(0, VAL_LIMIT) + "…");
        }
        return m;
    }

    private static String snippet(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}

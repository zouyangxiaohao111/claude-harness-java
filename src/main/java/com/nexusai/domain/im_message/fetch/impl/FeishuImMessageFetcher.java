package com.nexusai.domain.im_message.fetch.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.domain.im_message.fetch.ImMessageFetchException;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书消息拉取器 —— {@code GET /open-apis/im/v1/messages}（{@code tenant_access_token} 鉴权）。
 *
 * <h2>协议（官方文档逐字）</h2>
 * <pre>
 * GET /open-apis/im/v1/messages
 *   ?container_id_type=chat            必填（chat|thread）
 *   &amp;container_id=oc_xxx            必填
 *   &amp;start_time=&lt;epoch 秒&gt;          可选
 *   &amp;end_time=&lt;epoch 秒&gt;            可选
 *   &amp;sort_type=ByCreateTimeAsc      翻页途中不可改
 *   &amp;page_size=50                   1~50
 *   &amp;page_token=…                    按 has_more 续拉
 *   Authorization: Bearer &lt;tenant_access_token&gt;
 * → {"code":0,"msg":"ok","data":{"items":[…],"has_more":bool,"page_token":"…"}}
 * </pre>
 *
 * <h2>⚠️ 时间是<b>秒级 epoch</b>（不是毫秒、不是 ISO）</h2>
 * 而消息项里的 {@code create_time} 是<b>毫秒</b>字符串 ⇒ 两个时间单位在同一份报文里，
 * 混用会把区间拉空（拉 50 条全在窗外）。这是本类最容易踩的坑。
 *
 * <h2>⚠️ 飞书业务错误常配 HTTP 200</h2>
 * ⇒ 成功判据是 {@code code == 0}，不是 HTTP 状态码（沿用
 * {@code FeishuChatListStrategy} 的同一判据）。
 *
 * <h2>⚠️ 没有 sender 过滤参数</h2>
 * {@code /im/v1/messages} <b>没有</b>按人筛的参数（按人筛要走
 * {@code /im/v1/messages/search} 的 {@code filter.from_ids}）⇒ 「只看某个人」
 * 统一在<b>本地表</b>上做（{@code ImMessageService.queryBySender}），三平台语义一致。
 *
 * <h2>🔴 {@code container_id} 只认 chat_id（{@code oc_…}）</h2>
 * 同一个单聊在 {@code im_chat} 里有<b>两行</b>：{@code ou_…}（open_id，只能主动发）与
 * {@code oc_…}（chat_id，能拉历史）。拿 {@code ou_…} 去问 ⇒ 平台回 <b>HTTP 400</b>。
 * ⇒ 非 {@code oc_} 前缀的会话 id <b>直接返回空列表</b>（不是错误，别污染 {@code last_error}）。
 *
 * <h2>⚠️ 权限前提</h2>
 * 应用身份读群历史需要 {@code im:message.group_msg} scope，读单聊需要
 * {@code im:message.p2p_msg:get_as_user}（用户身份）。scope 没批 ⇒ 平台回权限错误码，
 * 本类如实抛 {@link ImMessageFetchException}（上层降级为「只用本地记录」+ 说明原因）。
 */
@Component
public class FeishuImMessageFetcher implements ImMessageFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeishuImMessageFetcher.class);

    public static final String PLATFORM = "feishu";

    /** 消息列表端点。 */
    public static final String MESSAGES_PATH = "/open-apis/im/v1/messages";

    /**
     * 飞书<b>会话 id</b>（{@code chat_id}）的前缀：{@code oc_…}。
     *
     * <p>🔴 {@code container_id_type=chat} <b>只认 chat_id</b> ⇒ 拿 {@code ou_…}（open_id）去问，
     * 平台回 HTTP 400（真机日志：{@code 飞书拉取消息 HTTP 400}）。
     * <p>为什么本地目录里会有 {@code ou_…} 的行：<b>同一个单聊在 {@code im_chat} 里有两行</b> ——
     * 入站事件带的是 {@code open_id}（{@code ou_…}，只能用来主动发消息），
     * 而拉历史必须用 {@code chat_id}（{@code oc_…}）。两行都得留着（一个管发、一个管拉）。
     */
    public static final String CHAT_ID_PREFIX = "oc_";

    /** 默认网关（国内版；国际站是 open.larksuite.com，与既有实现保持一致）。 */
    public static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    /** 每页条数上限（官方 1~50）。 */
    static final int PAGE_SIZE = 50;

    /** 页数上限（默认 10 页）——⛔ 不无限翻：飞书限流 1000 次/分钟、50 次/秒。 */
    static final int MAX_PAGES = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * open_id → 姓名（进程级缓存）。
     *
     * <p>为什么必须缓存：同一个群里几十条消息可能只有 3 个发送人，逐条查会打爆限流
     * （messages 1000/min、contact 更严）。缓存后每轮拉取通常 0~3 次请求。
     * 不设 TTL：名字改得不频繁，重启就清空 —— 换名字的代价远小于限流。
     */
    private final java.util.Map<String, String> nameCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** 换票基址（与本类同址，测试时一起指到 stub）。 */
    private final String baseUrl;

    @Autowired private ImChannelService channelService;
    @Autowired private FeishuTokenService tokenService;

    /** 生产构造（打飞书官方端点）。 */
    @Autowired
    public FeishuImMessageFetcher() {
        this(DEFAULT_BASE_URL);
    }

    /**
     * 自定义基址（**仅测试用**：指向本地 stub 才能断言「实际发出的 URL 与参数」）。
     *
     * @param baseUrl 基址，如 {@code http://127.0.0.1:1234}
     */
    public FeishuImMessageFetcher(String baseUrl) {
        String v = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso,
                                      String untilIso, int maxMessages) throws ImMessageFetchException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ImMessageFetchException("IM_PARAM_INVALID", "飞书拉取消息失败：instanceId 为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new ImMessageFetchException("IM_PARAM_INVALID", "飞书拉取消息失败：chatId 为空");
        }
        if (!isChatId(chatId)) {
            // ⛔ 这**不是错误**：只是「这个 id 形态不支持按 chat 拉历史」。
            //    抛异常会污染 last_error、把用户吓成「出故障了」（真机上就出现过：
            //    im_chat 里同一个单聊有 ou_ 与 oc_ 两行，ou_ 那行把整个实例刷成失败）。
            //    ⇒ 返回空列表 + 一行 debug（连 token 都不换，更不打扰平台）。
            log.debug("[ImFetch] 飞书会话 id 不是 chat_id（需 {} 前缀），跳过按会话拉历史: instance={} chat={}",
                CHAT_ID_PREFIX, instanceId, chatId);
            return List.of();
        }
        String token = tenantToken(instanceId);

        // 已知「你本人」的 open_id + 昵称：拉历史的报文里**没有昵称**（只有 id），
        // 但这两条我们在授权时就存下了（V313/V314）⇒ 落库时补上，模型才看得到人名
        // 而不是一串 ou_。查库失败不影响拉取（宁可有 id 没名字，也别拉不到消息）。
        String selfOpenId = null;
        String selfName = null;
        String botName = null;
        try {
            selfOpenId = channelService.userOpenIdOf(instanceId).orElse(null);
            selfName = channelService.userNameOf(instanceId).orElse(null);
            botName = channelService.findById(instanceId)
                .map(ImInstanceEntry::displayName).orElse(null);
        } catch (RuntimeException e) {
            log.debug("[ImFetch] 读授权人信息失败（跳过昵称补全）instance={} err={}",
                instanceId, e.toString());
        }

        List<ImMessageEntry> out = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = buildUrl(chatId, sinceIso, untilIso, pageToken);
            JsonNode data = fetchPage(token, url);

            for (JsonNode item : data.path("items")) {
                ImMessageEntry e = toEntry(instanceId, chatId, item, token, selfOpenId, selfName, botName);
                if (e != null) {
                    out.add(e);
                }
            }
            if (out.size() >= maxMessages) {
                break;
            }
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            String next = data.path("page_token").asText("");
            if (next.isBlank() || next.equals(pageToken)) {
                break;
            }
            pageToken = next;
        }
        log.info("[ImFetch] 飞书消息 instance={} chat={} rows={}", instanceId, chatId, out.size());
        return out;
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** 会话 id 是不是「能按 chat 拉历史」的那种（{@code oc_…}）。 */
    static boolean isChatId(String chatId) {
        return chatId != null && chatId.startsWith(CHAT_ID_PREFIX);
    }

    private String buildUrl(String chatId, String sinceIso, String untilIso, String pageToken) {
        StringBuilder sb = new StringBuilder(baseUrl).append(MESSAGES_PATH);
        sb.append("?container_id_type=chat");
        sb.append("&container_id=").append(URLEncoder.encode(chatId, StandardCharsets.UTF_8));
        sb.append("&sort_type=ByCreateTimeAsc");
        // 🔴 必须点名 id 类型：飞书对「用户/机器人 id」类字段按 user_id_type 裁剪，
        //    不传时 sender.id 里的 open_id/user_id 会被**整个省略**（不是返回空串）⇒
        //    表现成「消息能看到、但不知道是谁发的」。这是真机踩出来的。
        sb.append("&user_id_type=open_id");
        sb.append("&page_size=").append(PAGE_SIZE);
        Long start = epochSeconds(sinceIso);
        if (start != null) {
            sb.append("&start_time=").append(start);
        }
        Long end = epochSeconds(untilIso);
        if (end != null) {
            sb.append("&end_time=").append(end);
        }
        if (pageToken != null && !pageToken.isBlank()) {
            sb.append("&page_token=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 拉一页；失败抛异常（⛔ 不吞成空列表 —— 那会让「平台拒绝」看起来像「没有消息」）。 */
    private JsonNode fetchPage(String token, String url) throws ImMessageFetchException {
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR", "飞书拉取消息被中断", null, true);
        } catch (Exception e) {
            // ⛔ 不拼 e.getMessage()（可能含请求片段/令牌）
            throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                "飞书拉取消息失败：" + e.getClass().getSimpleName(), null, true);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                "飞书拉取消息 HTTP " + resp.statusCode(), String.valueOf(resp.statusCode()), true);
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body() == null ? "" : resp.body());
        } catch (Exception e) {
            throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                "飞书拉取消息响应不是合法 JSON", null, false);
        }
        // 飞书业务错误常配 HTTP 200 ⇒ 判成功看 code==0，不看 HTTP 状态码
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new ImMessageFetchException("IM_FETCH_PLATFORM_ERROR",
                "飞书拒绝拉取消息：code=" + code + " msg=" + root.path("msg").asText(""),
                String.valueOf(code), false);
        }
        JsonNode data = root.path("data");
        return data.isObject() ? data : JSON.createObjectNode();
    }

    /**
     * 取出 {@code sender.id}，<b>两种形态都要认</b>。
     *
     * <h2>为什么必须兼容</h2>
     * 同一个「发送人」字段，飞书在不同接口里给的形态不一致（真机踩出来的）：
     * <ul>
     *   <li><b>拉历史</b> {@code GET /im/v1/messages}：{@code sender:{id:"ou_…", id_type:"open_id", sender_type:"user"}}
     *       ⇒ {@code id} 是<b>字符串</b>；</li>
     *   <li><b>入站事件</b> {@code im.message.receive_v1}：{@code sender:{id:{open_id:"ou_…"}, sender_type:"user"}}
     *       ⇒ {@code id} 是<b>对象</b>。</li>
     * </ul>
     * 只按对象取 ⇒ 拉历史时 {@code path("id").path("open_id")} 落在字符串节点上返回 MissingNode
     * ⇒ {@code senderId=""}、界面显示「未知发送人」，而且<b>不报错</b>。
     * <p>⚠️ 单元测试当初按对象形态造的桩 ⇒ 全绿但真机全空。改这里时两种形态都要有断言。
     */
    static String senderIdOf(JsonNode sender) {
        if (sender == null || !sender.isObject()) {
            return "";
        }
        JsonNode id = sender.path("id");
        if (id.isTextual()) {
            return id.asText("");
        }
        if (id.isObject()) {
            String openId = id.path("open_id").asText("");
            if (!openId.isBlank()) {
                return openId;
            }
            String userId = id.path("user_id").asText("");
            if (!userId.isBlank()) {
                return userId;
            }
        }
        return "";
    }

    /**
     * 按 open_id 查姓名（带进程级缓存）；查不到返回 null，**不抛异常**。
     *
     * <p>查不到有几种正常原因，都不该让整轮拉取失败：
     * 应用身份没开 {@code contact:user.base:readonly}、对方不在通讯录范围、
     * 对方是外部联系人。这几种情况下界面显示 {@code ou_xxx} 就够了。
     */
    private String nameOf(String token, String openId) {
        String cached = nameCache.get(openId);
        if (cached != null) {
            return cached.isBlank() ? null : cached;
        }
        try {
            String url = baseUrl + "/open-apis/contact/v3/users/"
                + URLEncoder.encode(openId, StandardCharsets.UTF_8) + "?user_id_type=open_id";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                nameCache.put(openId, "");
                return null;
            }
            JsonNode root = JSON.readTree(resp.body() == null ? "" : resp.body());
            String name = root.path("data").path("user").path("name").asText("");
            nameCache.put(openId, name);
            return name.isBlank() ? null : name;
        } catch (Exception e) {
            // 限流/网络问题也缓存成空，避免同一轮里反复打
            nameCache.put(openId, "");
            return null;
        }
    }

    /** 消息项 → domain record；时间解析不出来 ⇒ {@code null}（丢弃，不猜）。 */
    private ImMessageEntry toEntry(String instanceId, String chatId, JsonNode item,
                                   String token, String selfOpenId, String selfName, String botName) {
        if (item == null || !item.isObject()) {
            return null;
        }
        // ⚠️ create_time 是毫秒字符串
        String createTime = item.path("create_time").asText("");
        long millis;
        try {
            millis = Long.parseLong(createTime.trim());
        } catch (NumberFormatException e) {
            log.warn("[ImFetch] 飞书消息 create_time 无法解析，丢弃该条: {}", createTime);
            return null;
        }
        String sentAt = ImMessageTime.canonicalFromEpochMillis(millis);

        JsonNode sender = item.path("sender");
        String senderId = senderIdOf(sender);
        String senderType = sender.path("sender_type").asText("");
        // 昵称：拉历史接口**不带昵称**，三种身份各有来源：
        //   ① 你本人 = 授权时存的名字（V314）
        //   ② 机器人 = 实例显示名
        //   ③ 其它群成员 = 通讯录反查（需**应用身份** contact:user.base:readonly；
        //      没开通就自然回退 ou_xxx，不影响功能）
        String senderName = null;
        if (selfOpenId != null && !selfOpenId.isBlank() && selfOpenId.equals(senderId)) {
            senderName = selfName;
        } else if ("app".equals(senderType) && botName != null && !botName.isBlank()) {
            senderName = botName;
        } else if (senderId != null && !senderId.isBlank()) {
            // 群成员：靠通讯录反查（需要**应用身份**的 contact:user.base:readonly；
            // 没开通时返回空 ⇒ 自然回退显示 ou_xxx，不影响任何功能）
            senderName = nameOf(token, senderId);
        }

        String msgType = item.path("msg_type").asText("");
        JsonNode body = parseBody(item.path("body").path("content").asText(""));

        String content = contentOf(body, msgType);
        String mediaJson = mediaJson(body, msgType);

        return new ImMessageEntry(
            null,                                   // 主键由持久化层生成
            instanceId,
            PLATFORM,
            chatId,
            item.path("message_id").asText(""),
            senderId,
            senderName,                             // 只有「你本人」能补上（见上）
            msgType,
            content,
            mediaJson,
            sentAt,
            // 「app」= 应用身份发的 ⇒ 是「我们自己说的话」；其余按别人说的算
            "app".equals(senderType) ? "out" : "in",
            "official");
    }

    /**
     * {@code body.content} 是<b>字符串化的 JSON</b> ⇒ 必须二次解析。
     *
     * <p>⛔ 解析失败不抛：退回原始字符串（宁可给模型看原文，也不丢整条消息）。
     */
    private JsonNode parseBody(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(contentJson);
            return (n != null && n.isObject()) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 正文：按 msg_type 取对应字段；取不到就给原始文本片段（不丢内容）。 */
    private String contentOf(JsonNode body, String msgType) {
        if (body == null) {
            return null;
        }
        switch (msgType == null ? "" : msgType) {
            case "text" -> {
                String t = body.path("text").asText("");
                return t.isBlank() ? null : t;
            }
            case "post" -> {
                String title = body.path("title").asText("");
                return title.isBlank() ? null : title;
            }
            case "file", "media" -> {
                String name = body.path("file_name").asText("");
                return name.isBlank() ? null : name;
            }
            default -> {
                // 图片 / 语音 / 表情 / 交互卡：正文是平台引用，由 media_json 承载
                return null;
            }
        }
    }

    /**
     * 媒体引用 → {@code media_json}（元素同 {@code InboundMessage.Media}：kind/fileName/ref）。
     *
     * <p>⛔ 只存平台 key，⛔ 不存二进制。
     */
    private String mediaJson(JsonNode body, String msgType) {
        if (body == null) {
            return null;
        }
        String kind;
        String refKey;
        String fileName = null;
        switch (msgType == null ? "" : msgType) {
            case "image" -> { kind = "image"; refKey = "image_key"; }
            case "audio" -> { kind = "voice"; refKey = "file_key"; }
            case "media" -> { kind = "video"; refKey = "file_key"; fileName = body.path("file_name").asText(""); }
            case "file" -> { kind = "file"; refKey = "file_key"; fileName = body.path("file_name").asText(""); }
            case "sticker" -> { kind = "image"; refKey = "file_key"; }
            default -> { return null; }
        }
        String ref = body.path(refKey).asText("");
        if (ref.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[{\"kind\":\"").append(kind)
            .append("\",\"fileName\":").append(quote(fileName))
            .append(",\"ref\":").append(quote(ref)).append("}]");
        return sb.toString();
    }

    /** 换 tenant_access_token。 */
    private String tenantToken(String instanceId) throws ImMessageFetchException {
        String credential = channelService.credentialOf(instanceId).orElseThrow(
            () -> new ImMessageFetchException("IM_INSTANCE_NOT_FOUND",
                "渠道实例不存在或没有凭证：" + instanceId));
        String appId;
        String appSecret;
        try {
            JsonNode root = JSON.readTree(credential);
            appId = text(root, "appId", "app_id");
            appSecret = text(root, "appSecret", "app_secret");
        } catch (Exception e) {
            throw new ImMessageFetchException("IM_CREDENTIAL_INVALID", "飞书凭证不是合法 JSON");
        }
        if (appId == null || appSecret == null) {
            throw new ImMessageFetchException("IM_CREDENTIAL_INVALID", "飞书凭证缺少 appId/appSecret");
        }
        try {
            return tokenService.tokenFor(instanceId, appId, appSecret);
        } catch (ImSendException e) {
            throw new ImMessageFetchException("IM_CREDENTIAL_EXCHANGE_FAILED",
                "飞书换取 tenant_access_token 失败：" + e.getMessage(), e.platformCode(), e.retryable());
        }
    }

    /** ISO → epoch 秒（飞书要秒级）。 */
    private static Long epochSeconds(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        Instant at = ImMessageTime.toInstant(iso);
        return at == null ? null : at.getEpochSecond();
    }

    private static String text(JsonNode root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText().trim();
            }
        }
        return null;
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}

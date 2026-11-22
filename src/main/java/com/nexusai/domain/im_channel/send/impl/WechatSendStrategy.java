package com.nexusai.domain.im_channel.send.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.send.AbstractImSendStrategy;
import com.nexusai.domain.im_channel.send.ImMediaRef;
import com.nexusai.domain.im_channel.send.ImMediaUploader;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.SendCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 微信 iLink 主动发消息（纯 JDK HttpClient，零第三方 SDK）。
 *
 * <h2>协议出处</h2>
 * 上游开源 SDK {@code github.com/CloudSen/wx-aibot-java-sdk}（Apache-2.0）的
 * {@code io.github.cloudsen.ai.weixin.WeixinClient#sendTextMessage}；
 * 本仓另有一份等价实现 {@code nexusai-im-channel} 的
 * {@code com.nexusai.im.weixin.WeixinHttpClient#sendText}（{@code buildMsg} + {@code sendMessage}）。
 * 两份逐字核对过：端点、请求体字段名与取值一致。
 *
 * <h2>请求</h2>
 * <pre>
 * POST {baseUrl}/ilink/bot/sendmessage
 * Content-Type: application/json
 * {
 *   "msg": {
 *     "from_user_id": "",
 *     "to_user_id": "&lt;peerId&gt;",
 *     "client_id": "nexusai_&lt;nanoTime&gt;",
 *     "message_type": 2,          // BOT
 *     "message_state": 2,         // FINISH
 *     "context_token": "&lt;replyToken&gt;",
 *     "item_list": [ {"type": 1, "text_item": {"text": "..."}} ]
 *   },
 *   "base_info": { "channel_version": "1.0.0" }
 * }
 * → {"message_id": "..."}
 * </pre>
 *
 * <p>{@code item_list} 是<b>数组</b>：纯文本时放 1 个文本 item；
 * 带附件时放「1 个文本 item + N 个附件 item」（见下方「附件」小节）。
 *
 * <h2>三条硬约束（每条都对应一个真实踩过的坑）</h2>
 * <ul>
 *   <li><b>PIT-WX-008</b>：{@code context_token} 为空 ⇒ 平台「返回成功但对方收到<b>空白消息</b>」。
 *       所以必须 fail-closed：模板方法先拦一道（返回 SKIPPED，一个请求都不发），
 *       本类里再防御一次（抛 {@link ImSendException}）—— 模板那道是"能力边界"，
 *       这道是"协议前提"，两道都留着才不会被将来的改动捅穿。</li>
 *   <li><b>PIT-WX-024</b>：响应里<b>没有 {@code message_id} 即视为失败</b>。
 *       ⛔ 绝不能拿自己生成的 {@code client_id} 冒充成功 —— 上游 SDK 恰好就是这么干的
 *       （{@code WeixinClient#sendMessage} 直接 {@code return normalized.clientId()}），
 *       于是出站语音出现过「假成功」：调用方以为发出去了，对方根本没收到。</li>
 *   <li><b>PIT-WX-007</b>：请求体必须<b>嵌在 {@code msg} 里</b>；扁平结构平台会回 {@code ret=-2}。</li>
 * </ul>
 *
 * <h2>为什么本类不碰 {@code botToken}</h2>
 * iLink 的发消息鉴权靠 {@code context_token}（会话级），{@code bot_token} 是机器人级凭证，
 * 主要用于长轮询收消息。因此：{@code botToken} <b>缺失不算错</b>，
 * 只有 {@code baseUrl} 与 {@code replyToken} 是硬前提。
 * 但凭证里若带了 {@code botToken}，仍按 SDK 习惯带上 {@code Authorization} 头 ——
 * 多带一个头不会让请求失败，少带则可能被网关拒。
 *
 * <h2>附件（图片 / 文件）</h2>
 * 微信的媒体不是"先上传拿 key、再发消息"两步，而是
 * <b>上传拿回一套 CDN 下载描述 → 把这段描述原样塞进 {@code item_list}</b>。
 * 所以本类与 {@link WechatMediaUploader} 的分工是：
 * <ul>
 *   <li>{@link WechatMediaUploader} 负责「申请地址 → 加密 → 传 CDN → 组 item 节点」，
 *       返回的 {@link ImMediaRef#raw()} 就是**完整的 item 节点**（含 {@code type}）；</li>
 *   <li>本类负责把「正文 item + 所有附件 item」放进**同一条** {@code sendmessage}
 *       —— 微信的一条消息支持多个 item，不需要为每个附件发一次请求。</li>
 * </ul>
 *
 * <p>⛔ <b>敏感值</b>：{@code context_token} 与 {@code bot_token} 都能让人冒充我们发消息，
 * 二者绝不进日志、不进异常消息（本类的异常只带 HTTP 状态码与平台码）。
 * 附件 item 里含 {@code aes_key}，同样不落日志（{@link ImMediaRef#toString()} 已做掩码）。
 */
@Component
public class WechatSendStrategy extends AbstractImSendStrategy {

    private static final Logger log = LoggerFactory.getLogger(WechatSendStrategy.class);

    /** 发消息端点（相对 baseUrl）。 */
    private static final String SEND_PATH = "/ilink/bot/sendmessage";

    /** 单条文本上限（与设计文档 §4 能力表一致）。 */
    private static final int MAX_TEXT_CHARS = 4096;

    /** 连接与请求超时都取 15s（与上游 SDK 的 apiTimeout 同量级）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** {@code base_info.channel_version}：上游 SDK 固定带这个字段。 */
    private static final String CHANNEL_VERSION = "1.0.0";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;

    /**
     * 媒体上传器（可被 Spring 覆盖成容器里的单例）。
     *
     * <p>🔴 为什么用<b>字段 + setter</b>而不是构造器注入：构造器注入会让本类
     * 强依赖 {@link WechatMediaUploader} 这个 bean，而媒体通道是**可选能力**
     * （没有它也能发纯文本）⇒ 用 {@code @Autowired(required = false)} 的 setter 注入，
     * 缺 bean 时不阻塞启动。
     *
     * <p>默认给一个自建实例（复用本类已有的 {@link HttpClient}），
     * 这样脱离 Spring 单独 new 出来时附件能力依然可用。
     */
    private volatile ImMediaUploader mediaUploader;

    /**
     * 生产构造：自建连接池（HttpClient 线程安全，共享一个即可）。
     *
     * <p>🔴 本类有<b>两个</b>构造器 ⇒ 必须显式标 {@code @Autowired}，
     * 否则 Spring 只能靠"有默认构造器就用默认的"这条兜底规则 —— 将来有人删掉默认构造器
     * 就会变成装配期 {@code NoSuchMethodException}（启动才炸，单测发现不了）。
     */
    @Autowired
    public WechatSendStrategy() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** 测试构造：可注入替身客户端。 */
    WechatSendStrategy(HttpClient http) {
        this.http = http != null ? http : HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.mediaUploader = new WechatMediaUploader(this.http, WechatMediaUploader.DEFAULT_CDN_BASE_URL);
    }

    /**
     * Spring 注入媒体上传器（可选）。
     *
     * <p>⛔ 注入的是<b>具体类型</b>而不是接口：将来飞书 / 企微也会有各自的
     * {@code ImMediaUploader} 实现，按接口注入会变成"多个候选"而启动失败。
     */
    @Autowired(required = false)
    void setMediaUploader(WechatMediaUploader uploader) {
        if (uploader != null) {
            this.mediaUploader = uploader;
        }
    }

    /** 本平台的媒体通道（模板方法用它把附件传上去）。 */
    @Override
    protected ImMediaUploader mediaUploader() {
        return mediaUploader;
    }

    @Override
    public String platform() {
        return "wechat";
    }

    /**
     * 能力声明：可主动发，但<b>必须有入站带来的 {@code context_token}</b>；不支持群聊。
     *
     * <p>为什么 {@code supportsGroup=false}：iLink 的会话标识是<b>对端 userId</b>，
     * 没有"群 id"这个东西 —— 群聊在 iLink 里表现为多个单聊会话。
     */
    @Override
    public SendCapability capability() {
        return SendCapability.needsReplyToken(MAX_TEXT_CHARS, false);
    }

    @Override
    public String doSend(ImSendContext ctx, String text) throws ImSendException {
        // ── ① 协议前提：context_token 为空绝不能发（PIT-WX-008）──
        // 模板方法已拦过一次（SKIPPED），这里是第二道防线：
        // 万一将来有人把能力检查改坏，这里也不会发出空白消息。
        String contextToken = ctx.replyToken();
        if (contextToken == null || contextToken.isBlank()) {
            throw new ImSendException("IM_REPLY_TOKEN_MISSING", "微信缺少 context_token");
        }

        // ── ② 正文兜底（模板已保证非空，这里再挡一次，免得发出空消息）──
        if (text == null || text.isBlank()) {
            throw new ImSendException("IM_PARAM_INVALID", "消息内容为空，未发送");
        }

        // ── ③ 解析凭证（非法 JSON ⇒ 抛异常，绝不 NPE）──
        WechatCredential cred = WechatCredential.parse(ctx.credential());

        // ── ④ 组装请求体（PIT-WX-007：必须嵌在 msg 里）──
        ArrayNode items = JSON.createArrayNode();
        addTextItem(items, text);
        String body = buildBody(ctx.peerId(), contextToken, items);

        // ── ⑤ 发 + 解析响应 ──
        String messageId = post(cred.baseUrl(), body, cred.botToken());
        if (log.isDebugEnabled()) {
            // ⛔ 只打 peer 与 message_id，不打 token / botToken。
            log.debug("[WechatSend] 已发送 peer={} msgId={}", ctx.peerId(), messageId);
        }
        return messageId;
    }

    /**
     * 带附件发送：<b>正文与所有附件放进同一条 {@code sendmessage}</b>。
     *
     * <p>为什么能一条消息带多个 item：iLink 的 {@code item_list} 本来就是数组，
     * 上游 SDK 发文本是"数组里放一个 text_item"、发媒体是"数组里放一个 media item" ——
     * 平台按 item 逐个渲染，所以"文本 + 图片 + 文件"可以**一次**发出，
     * 不需要为每个附件发一次请求（也避免了多次请求各自消耗一条 context_token）。
     *
     * <p>🔴 附件 item 由 {@link WechatMediaUploader} 组装（里面含 {@code aes_key}
     * 等 CDN 下载描述），本类**只做搬运**：原样 {@code add} 进 {@code item_list}，
     * 不解析、不改写、不落日志。
     */
    @Override
    protected String doSendWithMedia(ImSendContext ctx, String text, List<ImMediaRef> media)
            throws ImSendException {
        // ── ① 协议前提：context_token 为空绝不能发（PIT-WX-008）──
        // 与 doSend 同一道防线：带附件也不能绕过。
        String contextToken = ctx.replyToken();
        if (contextToken == null || contextToken.isBlank()) {
            throw new ImSendException("IM_REPLY_TOKEN_MISSING", "微信缺少 context_token");
        }

        // ── ② item_list = [正文 item] + [附件 item…] ──
        ArrayNode items = JSON.createArrayNode();
        if (text != null && !text.isBlank()) {
            // 正文为空时**不放**空 text_item：平台会渲染出一个空白气泡，
            // 那正是 PIT-WX-008 要避免的现象（附件-only 发送要的是干净的附件消息）。
            addTextItem(items, text);
        }
        if (media != null) {
            for (ImMediaRef ref : media) {
                if (ref == null) {
                    continue;
                }
                JsonNode raw = ref.raw();
                if (raw == null || !raw.isObject()) {
                    // 上传器给了非法结构 = 实现有 bug ⇒ 明确失败，不静默丢附件。
                    throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信附件引用格式非法");
                }
                items.add((ObjectNode) raw);
            }
        }
        if (items.size() == 0) {
            throw new ImSendException("IM_PARAM_INVALID", "没有可发送的内容");
        }

        // ── ③ 解析凭证 + 组装 + 发（与 doSend 走同一套 body/post）──
        WechatCredential cred = WechatCredential.parse(ctx.credential());
        String body = buildBody(ctx.peerId(), contextToken, items);
        String messageId = post(cred.baseUrl(), body, cred.botToken());
        if (log.isDebugEnabled()) {
            // ⛔ 只打 peer / message_id / item 个数，不打 item 内容（含 aes_key）。
            log.debug("[WechatSend] 已发送(带附件) peer={} msgId={} items={}",
                    ctx.peerId(), messageId, items.size());
        }
        return messageId;
    }

    /** 正文 item（{@code item_list[].type == 1}）。 */
    private static void addTextItem(ArrayNode items, String text) {
        ObjectNode item = items.addObject();
        item.put("type", 1);           // 文本
        item.putObject("text_item").put("text", text);
    }

    // ───────────────────────── 内部 ─────────────────────────

    /**
     * 构造请求体 JSON（字段与上游 SDK 逐字一致）。
     *
     * @param items {@code item_list} 的全部节点（正文 item + 附件 item；顺序即展示顺序）
     */
    private static String buildBody(String toUserId, String contextToken, ArrayNode items) throws ImSendException {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode msg = root.putObject("msg");
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        // client_id 由**客户端**生成（上游 SDK 生成 "openclaw-weixin-<hex>"）；
        // 这里用纳秒时间戳保证同一进程内不重复。
        msg.put("client_id", "nexusai_" + System.nanoTime());
        msg.put("message_type", 2);    // BOT
        msg.put("message_state", 2);   // FINISH
        msg.put("context_token", contextToken);
        // ⛔ PIT-WX-007：请求体必须嵌在 msg 里；item_list 可以有多个 item。
        msg.set("item_list", items);
        // ⛔ base_info 必须存在（上游 SDK 每个端点都带）；内容按 SDK 取 channel_version。
        root.putObject("base_info").put("channel_version", CHANNEL_VERSION);
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信请求体序列化失败");
        }
    }

    /**
     * 发请求并取回 {@code message_id}。
     *
     * <p>⛔ 失败判定顺序：先看平台错误码（{@code errcode}/{@code ret}），再看 {@code message_id}。
     * 反过来会让"平台明确报错"被笼统地报成"没返回 message_id"，排查时看不到真实原因。
     */
    private String post(String baseUrl, String body, String botToken) throws ImSendException {
        URI uri;
        try {
            uri = URI.create(baseUrl + SEND_PATH);
        } catch (IllegalArgumentException e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "微信 baseUrl 不是合法 URI");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                // 上游 SDK 的通用信封（applyJsonApiHeaders）：AuthorizationType + 防重放的 X-WECHAT-UIN。
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", randomUin())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (botToken != null && !botToken.isBlank()) {
            // 有才带：没有 botToken 不是错误（见类注释）。
            builder.header("Authorization", "Bearer " + botToken.trim());
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 网络类失败值得重试（超时/连接重置/对端抖动）。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 sendmessage 网络异常：" + e.getClass().getSimpleName(), null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 sendmessage 被中断", null, true);
        }

        int status = resp.statusCode();
        if (status / 100 != 2) {
            // 5xx = 对端临时故障（可重试）；4xx = 请求本身有问题（重试无意义）。
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 sendmessage HTTP " + status, String.valueOf(status), status >= 500);
        }

        JsonNode node = parseBody(resp.body());
        int err = node.path("errcode").asInt(0);
        int ret = node.path("ret").asInt(0);
        int code = err != 0 ? err : ret;
        if (code != 0) {
            String detail = node.path("errmsg").asText("");
            // -2 = 限流（上游 SDK 映射为 RATE_LIMITED）⇒ 值得重试；其余按永久错误。
            boolean retryable = code == -2;
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 sendmessage 被平台拒绝（code=" + code
                            + (detail.isBlank() ? "" : "：" + truncate(detail, 120)) + "）",
                    String.valueOf(code), retryable);
        }

        // 🔴 PIT-WX-024：没有 message_id 就是没发出去。
        // ⛔ 绝不拿 client_id 冒充成功（上游 SDK 那样做过 ⇒ 出站语音「假成功」）。
        String messageId = node.path("message_id").asText("").trim();
        if (messageId.isEmpty()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR",
                    "微信 sendmessage 未返回 message_id（对方很可能收不到）", null, false);
        }
        return messageId;
    }

    /** 解析响应体；不是 JSON ⇒ 抛异常（不返回 null 让上层 NPE）。 */
    private static JsonNode parseBody(String raw) throws ImSendException {
        if (raw == null || raw.isBlank()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 sendmessage 响应为空");
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 sendmessage 响应不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new ImSendException("IM_SEND_PLATFORM_ERROR", "微信 sendmessage 响应不是 JSON 对象");
        }
        return node;
    }

    /** X-WECHAT-UIN = base64(十进制随机数) —— 每次随机，防重放（与上游 SDK 同做法）。 */
    private static String randomUin() {
        long v = Integer.toUnsignedLong(ThreadLocalRandom.current().nextInt());
        return Base64.getEncoder().encodeToString(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

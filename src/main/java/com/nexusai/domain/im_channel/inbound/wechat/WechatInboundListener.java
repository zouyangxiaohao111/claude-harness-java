package com.nexusai.domain.im_channel.inbound.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.inbound.ImInboundListener;
import com.nexusai.domain.im_channel.inbound.InboundMessage;
import com.nexusai.model.im_channel.ImChatEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink 入站监听（{@code /ilink/bot/getupdates} 长轮询）。
 *
 * <h2>🔴🔴 一、最要紧的一条：{@code getupdates} 是<b>单消费者</b>语义</h2>
 * <p>取走的就没了 —— 服务端游标一旦推进，别的轮询者<b>永远</b>看不到这条消息，
 * 而且没有 ack / 未确认重投（不像 MQ）。
 * <p>代价是实测过的（PIT-WX-006）：用户发了 3 条（「不好」「不好」「收到了吗」），
 * 前两条被一次诊断用的 {@code curl} 吃掉后<b>永久消失</b>。
 * <p>所以本类做了四件事来保证「只有一个消费者」：
 * <ol>
 *   <li><b>拿到新游标先落库</b>（{@link ImChannelService#saveCursor}），
 *       <b>再</b>去解析与投递消息 —— 顺序反了就会出现「消息处理到一半崩了、
 *       游标没存 ⇒ 下次重复；或游标存了但消息没处理 ⇒ 消息永久丢失」。</li>
 *   <li><b>同一实例只允许一个轮询线程</b>：{@link #start(String, InboundHandler)}
 *       里发现已在跑就直接返回，<b>不</b>起第二个线程。</li>
 *   <li><b>进程内全局登记表</b>（{@link #ACTIVE_POLLERS}）：哪怕有人绕过
 *       {@code ImInboundManager} 手工 new 了第二个监听器，
 *       第二个也会因为「这个实例已有人在轮询」而拒绝启动。</li>
 *   <li><b>绝不用空游标做诊断</b>：本类没有健康检查端点，
 *       唯一的 {@code getupdates} 调用方就是那个唯一的轮询线程。</li>
 * </ol>
 *
 * <h2>协议出处（以源码为准，不是以口述为准）</h2>
 * <p>三份独立实现逐字一致，本类照抄：
 * <ul>
 *   <li>上游开源 SDK {@code github.com/CloudSen/wx-aibot-java-sdk}（Apache-2.0）的
 *       {@code io.github.cloudsen.ai.weixin.WeixinClient#getUpdates}：</li>
 *   <li>本仓另一份等价实现 {@code /usr/local/code/nexusai-im-channel} 的
 *       {@code com.nexusai.im.weixin.WeixinHttpClient#getUpdates}；</li>
 *   <li>腾讯 WeKnora 的 {@code internal/im/wechat/wechat-longpoll.go}。</li>
 * </ul>
 * <pre>
 * POST {baseUrl}/ilink/bot/getupdates
 * Content-Type: application/json
 * AuthorizationType: ilink_bot_token
 * Authorization: Bearer &lt;botToken&gt;
 * X-WECHAT-UIN: &lt;base64(十进制随机数)&gt;     // 防重放
 * iLink-App-ClientVersion: 1
 * {
 *   "get_updates_buf": "&lt;上一轮的游标&gt;",
 *   "base_info": { "channel_version": "1.0.0" }
 * }
 * → { "ret": 0, "msgs": [ … ], "get_updates_buf": "&lt;新游标&gt;" }
 * </pre>
 *
 * <h2>为什么请求是 POST 而不是 GET</h2>
 * 游标放在<b>请求体</b>里（{@code get_updates_buf}），方法与上面三份实现一致。
 * 端点清单里唯一用 GET + query 的是扫码那两步（{@code get_bot_qrcode} /
 * {@code get_qrcode_status}），业务端点全是 POST JSON。
 *
 * <h2>为什么单次请求超时给到 40s（而不是更短）</h2>
 * 服务端长轮询窗口约 35s（PIT-WX-009：客户端配再长也没用，服务端有硬上限）。
 * <p>🔴 <b>不能</b>为了「好打断」把超时压到 5s：请求被客户端掐断时，
 * 服务端可能已经把消息投递到这条即将消失的连接上 ⇒ <b>那条消息就丢了</b>。
 * 所以本类的做法是：<b>请求超时给足（35s 窗口 + 5s 余量）</b>，
 * 而「打断」靠 {@link Thread#interrupt()}（{@code HttpClient.send} 可中断，
 * 会取消在途请求），停止时立刻生效。
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>{@code -14} = 登录态失效（扫码换新 bot 后旧 bot 立刻失效，PIT-WX-010）
 *       ⇒ <b>停止轮询</b>并把实例标成 error，<b>不空转</b>（空转只会刷日志、耗连接）。</li>
 *   <li>其它非 0 ⇒ 按失败处理，退避重试（1s→2s→…→30s）。</li>
 * </ul>
 *
 * <h2>⚠️ 敏感值</h2>
 * {@code botToken}（机器人级凭证）与 {@code context_token}（会话级凭证，
 * 拿到它就能冒充我们给对端发消息）<b>绝不进日志、不进异常消息</b>。
 * 媒体引用里的 {@code aes_key} 同理。
 *
 * <h2>为什么 {@code context_token} 必须落库</h2>
 * 微信主动发消息<b>必须</b>带对方上次来信里的 {@code context_token}，
 * 空 token 会让平台「返回成功但对方收到空白消息」（PIT-WX-008）。
 * 所以每条入站消息都要把 token 存进 {@code im_chat.reply_token}（V310），
 * {@code ImSendService} 投递前再取出来用。丢了它 ⇒ 这个会话再也发不出去。
 *
 * <h2>归一约定</h2>
 * <ul>
 *   <li>{@code peerId = from_user_id}（谁发的就是会话对端）；iLink <b>没有群聊</b>
 *       （PIT-WX-003 / PIT-WX-005）⇒ 一律按 p2p 处理。</li>
 *   <li>{@code peerName / senderName} = null：iLink 没有通讯录 API，拿不到昵称
 *       （参考实现 {@code WeixinNormalizer} 同样置 null）。</li>
 *   <li>{@code text} = {@code item_list} 里 {@code type=1} 的 {@code text_item.text}；
 *       非文本 item（图片/语音/文件/视频）⇒ {@code text=null} 且进 {@code media}。</li>
 * </ul>
 */
@Component
@Scope("prototype")
public class WechatInboundListener implements ImInboundListener {

    private static final Logger log = LoggerFactory.getLogger(WechatInboundListener.class);

    /** 平台标识（与 {@code ImChannelService.PLATFORMS} 对齐）。 */
    private static final String PLATFORM = "wechat";

    /** 长轮询端点（相对 baseUrl）。 */
    private static final String GET_UPDATES_PATH = "/ilink/bot/getupdates";

    /** 扫码接口没给 baseurl 时的缺省基址（与 {@code WechatQrStrategy} 同源）。 */
    private static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    /** {@code base_info.channel_version}：上游 SDK 每个端点都带这个字段。 */
    private static final String CHANNEL_VERSION = "1.0.0";

    /** 连接超时（与 {@code WechatSendStrategy} 同量级）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 单次请求超时 = 服务端长轮询窗口(35s) + 余量(5s)。
     *
     * <p>🔴 别为了「好打断」把它调小：请求被客户端掐断 ⇒ 服务端可能已经把消息
     * 投递到这条即将消失的连接上 ⇒ 那条消息就丢了。打断靠 {@code interrupt()}。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(40);

    /** {@code -14} = 登录态失效（需重新扫码）。 */
    private static final int CODE_SESSION_DEAD = -14;

    /** 退避上限（1s→2s→4s→…→30s）。 */
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** {@link #stop()} 等待轮询线程退出的上限（略大于单次请求超时）。 */
    private static final long JOIN_TIMEOUT_MS = 5_000L;

    /**
     * 🔴 进程内「一个实例只有一个轮询者」登记表（PIT-WX-006）。
     *
     * <p>为什么需要它：{@code ImInboundManager} 自己也会保证「一个 instanceId 一个监听器」，
     * 但那是<b>对象级</b>的保证。真正危险的是「旧的监听器线程还没死透，
     * 新的监听器又起来了」—— 两个线程同时对同一个 bot 调 {@code getupdates}，
     * 消息会被随机分给其中一个，而另一个线程的游标会把对方的消息「吃掉」。
     * <p>登记项在轮询线程真正退出时才释放（见 {@link #pollLoop} 的 finally），
     * 所以「线程还活着」与「登记项还在」始终一致。
     */
    private static final ConcurrentMap<String, WechatInboundListener> ACTIVE_POLLERS = new ConcurrentHashMap<>();

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImChannelService channelService;
    private final ImChatService chatService;
    private final HttpClient http;

    /** 运行标志（volatile + 原子语义：跨线程可见）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 轮询线程（用于 interrupt + join）。 */
    private volatile Thread worker;

    /** 当前绑定的实例 id（停止时释放全局登记项用）。 */
    private volatile String instanceId;

    /**
     * 是否已经进入「登录态失效」的终态。
     *
     * <p>用途：{@code stop()} 与线程退出时都要回写实例状态，
     * 但<b>不能</b>把「登录态失效(-14)，需重新扫码」这个错误态覆盖成「已停止」——
     * 覆盖了用户就看不出「要去重新扫码」。
     */
    private volatile boolean errorState;

    @Autowired
    public WechatInboundListener(ImChannelService channelService, ImChatService chatService) {
        this(channelService, chatService,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /** 测试接缝：注入替身 {@link HttpClient}（可指向本地 stub server）。 */
    WechatInboundListener(ImChannelService channelService, ImChatService chatService, HttpClient http) {
        this.channelService = channelService;
        this.chatService = chatService;
        this.http = http != null ? http : HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    @Override
    public String platform() {
        return PLATFORM;
    }

    /**
     * 启动长轮询（<b>不阻塞</b>，内部起线程）。
     *
     * <p>🔴 本方法对「已经在轮询」是<b>幂等</b>的：直接返回，<b>不</b>起第二个线程 ——
     * 两个轮询者会互相抢消息（PIT-WX-006）。
     *
     * @throws IllegalStateException 凭证缺失/非法，或该实例已有别的轮询者在跑
     */
    @Override
    public void start(String instanceId, InboundHandler handler) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为空");
        }
        // ① 幂等：已在跑就直接返回（绝不起第二个线程）
        if (running.get()) {
            log.info("[WechatInbound] 已在轮询中，忽略重复 start: instance={}", instanceId);
            return;
        }
        // ② 凭证（缺 botToken ⇒ 长轮询根本无法鉴权 ⇒ 明确失败，不起线程）
        Credential cred = readCredential(instanceId);
        // ③ 全局单消费者登记（PIT-WX-006）
        if (!claimPollerSlot(instanceId)) {
            throw new IllegalStateException(
                    "该微信实例已有轮询者在跑（getupdates 是单消费者语义，不能并发轮询）: instance=" + instanceId);
        }
        running.set(true);
        this.instanceId = instanceId;
        this.errorState = false;   // 重新启动 ⇒ 之前的「登录态失效」结论作废
        try {
            Thread t = new Thread(() -> pollLoop(instanceId, handler, cred),
                    "wechat-inbound-" + instanceId);
            t.setDaemon(true);
            worker = t;
            t.start();
        } catch (RuntimeException e) {
            // 线程没起来 ⇒ 释放登记项，别把槽位占死
            running.set(false);
            releasePollerSlot(instanceId);
            throw e;
        }
        log.info("[WechatInbound] 已启动长轮询: instance={} baseUrl={}", instanceId, cred.baseUrl());
    }

    /**
     * 停止（幂等）。
     *
     * <p>⛔ 不用 {@code Thread.stop}（会释放 monitor、破坏不变量），
     * 用 {@code interrupt()}：{@code HttpClient.send} 可中断，会取消在途的长轮询请求。
     */
    @Override
    public void stop() {
        running.set(false);
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String id = this.instanceId;
        if (id != null) {
            releasePollerSlot(id);
            if (!errorState) {
                markStatus(id, "disconnected", "微信入站轮询已停止");
            }
        }
        log.info("[WechatInbound] 已停止: instance={}", id);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ───────────────────────── 轮询循环 ─────────────────────────

    /**
     * 轮询主循环。
     *
     * <p>🔴 每轮的顺序<b>不可颠倒</b>：
     * <pre>
     *   取消息 → ① 存游标 → ② 存回复令牌 → ③ 归一 → ④ 回调上层
     * </pre>
     * 游标必须在处理消息<b>之前</b>落库：服务端游标一旦推进，消息就只在我们手里。
     */
    private void pollLoop(String instanceId, InboundHandler handler, Credential cred) {
        String cursor = readCursor(instanceId);
        int failures = 0;
        try {
            while (running.get()) {
                try {
                    JsonNode resp = fetchUpdates(cred, cursor);

                    // ① 先落库游标（PIT-WX-006：先存，才谈得上「不丢」）
                    String next = text(resp, "get_updates_buf");
                    if (next != null && !next.isBlank()) {
                        cursor = next;
                        saveCursor(instanceId, next);
                    }

                    // ② 再逐条处理（PIT-WX-020：一次可能返回多条，顺序 = 发送顺序）
                    JsonNode msgs = resp.path("msgs");
                    if (msgs != null && msgs.isArray()) {
                        for (JsonNode raw : msgs) {
                            deliver(instanceId, handler, raw);
                        }
                    }
                    failures = 0;
                } catch (SessionExpiredException e) {
                    // -14：登录态失效 ⇒ 停止轮询，不空转（PIT-WX-010：重扫会换新 bot）
                    errorState = true;
                    log.error("[WechatInbound] 微信登录态失效(-14)，停止轮询，需重新扫码: instance={}", instanceId);
                    markStatus(instanceId, "error", "微信登录态失效(-14)，需重新扫码");
                    // PIT-WX-002：-14 之后要能「清游标重扫」——
                    // 重新扫码会换成一个**新 bot**（游标空间也换了），
                    // 带着旧 bot 的游标去问新 bot 会拿不到消息（甚至被平台拒）。
                    saveCursor(instanceId, "");
                    running.set(false);
                } catch (InterruptedException e) {
                    // 被 stop() 打断 ⇒ 正常退出
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    failures++;
                    log.warn("[WechatInbound] 轮询失败(第 {} 次): instance={} err={}",
                            failures, instanceId, e.toString());
                    if (!sleepBackoff(failures)) {
                        break;   // 等待期间被 stop() 打断
                    }
                }
            }
        } finally {
            // 登记项跟着线程走：线程真退出才释放（否则会出现两个轮询者并存）
            releasePollerSlot(instanceId);
            if (running.get()) {
                // 走到这里说明线程是**自己**死的（异常退出）⇒ 别让界面显示「正常停止」
                log.error("[WechatInbound] 轮询线程异常退出: instance={}", instanceId);
                markStatus(instanceId, "error", "微信入站轮询异常退出");
                running.set(false);
            }
        }
    }

    /**
     * 调一次长轮询。
     *
     * @throws SessionExpiredException 平台回 {@code -14}（登录态失效）
     * @throws IOException              网络/协议错误（可重试）
     */
    private JsonNode fetchUpdates(Credential cred, String cursor)
            throws IOException, InterruptedException, SessionExpiredException {
        ObjectNode body = JSON.createObjectNode();
        body.put("get_updates_buf", cursor == null ? "" : cursor);
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(cred.baseUrl() + GET_UPDATES_PATH))
                    .header("Content-Type", "application/json")
                    // 上游 SDK 的通用信封：AuthorizationType + 防重放的 X-WECHAT-UIN。
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + cred.botToken())
                    .header("X-WECHAT-UIN", randomUin())
                    // 扫码那两步用的客户端版本头；业务端点带上无害，缺了也不影响。
                    .header("iLink-App-ClientVersion", "1")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("getupdates 请求地址或请求体不合法", e);
        } catch (IOException e) {
            throw new IOException("getupdates 请求体序列化失败", e);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            throw e;                      // 交给上层：这是 stop() 的正常打断
        } catch (IOException e) {
            throw new IOException("getupdates 网络异常：" + e.getClass().getSimpleName(), e);
        }

        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            // 令牌被拒 ⇒ 不可能自愈 ⇒ 当登录态失效处理（否则会带着死令牌一直空转）
            throw new SessionExpiredException();
        }
        if (status / 100 != 2) {
            throw new IOException("getupdates HTTP " + status);
        }

        JsonNode node = parseBody(resp.body());
        int err = node.path("errcode").asInt(0);
        int ret = node.path("ret").asInt(0);
        int code = err != 0 ? err : ret;
        if (code == CODE_SESSION_DEAD) {
            throw new SessionExpiredException();
        }
        if (code != 0) {
            // errmsg 是平台给的文字（不含敏感值），带上便于排查
            throw new IOException("getupdates 被平台拒绝（code=" + code
                    + (text(node, "errmsg") == null ? "" : "：" + truncate(text(node, "errmsg"), 120)) + "）");
        }
        return node;
    }

    // ───────────────────────── 消息归一与投递 ─────────────────────────

    /**
     * 处理一条原始消息（<b>绝不抛异常</b>：一条坏消息不能打断整轮轮询）。
     */
    private void deliver(String instanceId, InboundHandler handler, JsonNode raw) {
        try {
            if (raw == null || !raw.isObject()) {
                return;
            }
            // ① 机器人自己的回显（message_type=2）⇒ 丢弃，否则会自激（自己发→自己收→再发…）
            int messageType = raw.path("message_type").asInt(1);
            if (messageType == 2) {
                return;
            }
            // ② 没有对端 ⇒ 异常报文，丢弃
            String from = text(raw, "from_user_id");
            if (from == null || from.isBlank()) {
                return;
            }
            // ③ item_list 缺失/不是数组 ⇒ 没有内容，丢弃
            JsonNode items = raw.path("item_list");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return;
            }

            StringBuilder textBuf = new StringBuilder();
            List<InboundMessage.Media> media = new ArrayList<>();
            for (JsonNode item : items) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                int type = item.path("type").asInt(0);
                switch (type) {
                    case 1 -> {
                        String t = item.path("text_item").path("text").asText("");
                        if (t != null && !t.isEmpty()) {
                            if (textBuf.length() > 0) {
                                textBuf.append('\n');
                            }
                            textBuf.append(t);
                        }
                    }
                    case 2, 3, 4, 5 -> media.add(mediaOf(item, type));
                    default -> {
                        // 未知 item 类型：忽略（原始报文仍保留在 InboundMessage.raw 里）
                    }
                }
            }
            String messageText = textBuf.length() == 0 ? null : textBuf.toString();
            if (messageText == null && media.isEmpty()) {
                return;     // 既没文本也没媒体 ⇒ 没有可投递的内容
            }

            long timestampMs = raw.path("create_time_ms").asLong(0L);
            if (timestampMs <= 0) {
                timestampMs = System.currentTimeMillis();
            }
            String messageId = text(raw, "message_id");

            InboundMessage msg = new InboundMessage(
                    instanceId, PLATFORM,
                    from,            // peerId = 谁发的就是会话对端（iLink 无群聊）
                    null,            // peerName：iLink 没有通讯录 API，拿不到
                    from,            // senderId
                    null,            // senderName：同上
                    "p2p",
                    messageId,
                    messageText,
                    media,
                    timestampMs,
                    raw);

            // ④ 回复令牌必须落库（这是主动发消息的唯一凭据，丢了就再也发不出去）
            saveReplyToken(instanceId, from, text(raw, "context_token"), messageText, timestampMs);

            // ⑤ 交给上层分流（Router）。⚠️ handler 抛异常也不能打断轮询。
            try {
                handler.onMessage(msg);
            } catch (Throwable e) {
                log.error("[WechatInbound] 上层处理消息异常（已吞，继续轮询）: instance={} peer={}",
                        instanceId, from, e);
            }
        } catch (Throwable e) {
            log.warn("[WechatInbound] 单条消息处理失败（已吞，继续轮询）: instance={} err={}",
                    instanceId, e.toString());
        }
    }

    /**
     * 把平台的 item 节点折成 {@link InboundMessage.Media}。
     *
     * <p>{@code kind} 按契约只取 {@code IMAGE} / {@code FILE}；
     * 语音/视频在契约里没有对应类型 ⇒ 记成 FILE，
     * 但<b>原始 item 类型保留在 {@code ref} 的 JSON 里</b>（下载器自己看得懂）。
     *
     * <p>{@code ref} = 平台给的完整 item 节点 JSON（含 {@code media.encrypt_query_param}、
     * {@code aeskey}、文件名、大小）—— 这就是微信侧「CDN 下载描述」，
     * 与出站时 {@code WechatMediaUploader} 造的 item 结构对称。
     */
    private static InboundMessage.Media mediaOf(JsonNode item, int type) {
        String kind = (type == 2) ? "IMAGE" : "FILE";
        String nodeName = switch (type) {
            case 2 -> "image_item";
            case 3 -> "voice_item";
            case 4 -> "file_item";
            case 5 -> "video_item";
            default -> "";
        };
        String fileName = nodeName.isEmpty() ? null : text(item.path(nodeName), "file_name");
        if (fileName != null && fileName.isBlank()) {
            fileName = null;
        }
        // ⛔ ref 里含 aes_key（敏感值）⇒ 不进日志、不进异常消息。
        return new InboundMessage.Media(kind, fileName, item.toString());
    }

    /**
     * 落库 {@code context_token}（微信主动发消息的硬前提）。
     *
     * <p>为什么需要先 upsert 聊天行：{@link ImChatService#saveReplyToken} 是按
     * <b>chatId</b> 写的，而 chatId 只有把聊天行建出来才有。
     * upsert 是幂等的（(instance_id, peer_id) 唯一），上层 Router 之后还会再 upsert 一次，
     * 结果一致、不会覆盖 {@code reply_token}（{@code upsert} 明确不动这一列）。
     */
    private void saveReplyToken(String instanceId, String peerId, String contextToken,
                                String messageText, long timestampMs) {
        if (contextToken == null || contextToken.isBlank()) {
            // 没有 token 就没有可存的东西（平台偶尔不给）；不影响本条消息投递。
            return;
        }
        try {
            String preview = (messageText == null || messageText.isBlank())
                    ? "[非文本消息]" : truncate(messageText, 100);
            ImChatEntry chat = chatService.upsert(instanceId, PLATFORM, peerId, null, "p2p", null,
                    preview, isoTime(timestampMs), ImChatService.SOURCE_INBOUND);
            if (chat != null && chat.id() != null && !chat.id().isBlank()) {
                // 微信的 token 由对端轮换，没有过期时间概念 ⇒ expiresAt 传 null。
                chatService.saveReplyToken(chat.id(), contextToken, null);
            }
        } catch (RuntimeException e) {
            // 存不进去 ⇒ 后续主动发消息会因为缺 token 而失败（PIT-WX-008：空 token 会发空白消息）
            log.warn("[WechatInbound] 回复令牌落库失败（该会话后续主动发会失败）: instance={} peer={} err={}",
                    instanceId, peerId, e.toString());
        }
    }

    // ───────────────────────── 凭证 / 游标 ─────────────────────────

    /**
     * 读并校验凭证。
     *
     * <p>🔴 {@code botToken} 缺失是<b>硬错误</b>：iLink 的长轮询只有 bot 级鉴权，
     * 没有它连请求都发不出去 ⇒ 与其起一个注定失败的线程，不如启动时就明确失败。
     */
    private Credential readCredential(String instanceId) {
        String raw;
        try {
            raw = channelService.credentialOf(instanceId).orElse(null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取微信凭证失败: instance=" + instanceId, e);
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("微信实例没有凭证，无法轮询: instance=" + instanceId);
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("微信凭证不是合法 JSON: instance=" + instanceId, e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("微信凭证不是 JSON 对象: instance=" + instanceId);
        }
        String token = text(root, "botToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("微信凭证缺 botToken（长轮询收消息必须带机器人令牌）: instance=" + instanceId);
        }
        String base = text(root, "baseUrl");
        if (base == null || base.isBlank()) {
            base = DEFAULT_BASE_URL;
        }
        base = stripTrailingSlash(base.trim());
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("微信 baseUrl 不是合法 URI: instance=" + instanceId, e);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException("微信 baseUrl 不是 http/https 绝对地址: instance=" + instanceId);
        }
        return new Credential(base, token);
    }

    /** 读游标（进程重启后从这里续读，否则服务端已推进 ⇒ 中间消息永久丢失）。 */
    private String readCursor(String instanceId) {
        try {
            String c = channelService.cursorOf(instanceId);
            return c == null ? "" : c;
        } catch (RuntimeException e) {
            log.warn("[WechatInbound] 读游标失败（按空游标起轮）: instance={} err={}", instanceId, e.toString());
            return "";
        }
    }

    /** 存游标（失败不抛：内存里的游标已经前进了，抛了反而丢消息）。 */
    private void saveCursor(String instanceId, String cursor) {
        try {
            channelService.saveCursor(instanceId, cursor);
        } catch (RuntimeException e) {
            log.error("[WechatInbound] 游标落库失败（重启后会重复拉到这一条）: instance={} err={}",
                    instanceId, e.toString());
        }
    }

    // ───────────────────────── 单消费者保证 ─────────────────────────

    /**
     * 抢占「本实例的轮询权」。
     *
     * @return true = 抢到了；false = 已有人在轮询（拒绝启动）
     */
    private boolean claimPollerSlot(String id) {
        boolean[] claimed = new boolean[1];
        ACTIVE_POLLERS.compute(id, (k, prev) -> {
            if (prev == null || prev == this) {
                claimed[0] = true;
                return this;
            }
            if (!prev.pollingAlive()) {
                // 上一个监听器的线程已经死了（异常退出 / 被 stop 掉）⇒ 可以接管
                claimed[0] = true;
                return this;
            }
            // 🔴 真的有人在轮询 ⇒ 拒绝（两个轮询者会互相抢消息，PIT-WX-006）
            claimed[0] = false;
            return prev;
        });
        return claimed[0];
    }

    /** 释放登记项（幂等）。 */
    private void releasePollerSlot(String id) {
        if (id != null) {
            ACTIVE_POLLERS.remove(id, this);
        }
    }

    /** 轮询线程是否还活着。 */
    private boolean pollingAlive() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private void markStatus(String id, String status, String detail) {
        if (id == null) {
            return;
        }
        try {
            channelService.markStatus(id, status, detail);
        } catch (RuntimeException e) {
            log.warn("[WechatInbound] 回写实例状态失败: instance={} err={}", id, e.toString());
        }
    }

    /** 退避：1s→2s→4s→…→30s；被 interrupt 时返回 false（调用方退出循环）。 */
    private static boolean sleepBackoff(int attempt) {
        long ms = Math.min(1_000L << Math.min(attempt - 1, 5), MAX_BACKOFF_MS);
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 解析响应体；不是 JSON ⇒ 抛异常（不返回 null 让上层 NPE）。 */
    private static JsonNode parseBody(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("getupdates 响应为空");
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IOException("getupdates 响应不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IOException("getupdates 响应不是 JSON 对象");
        }
        return node;
    }

    /** 取字符串字段；缺失 / null ⇒ null。 */
    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s == null ? null : s;
    }

    /** X-WECHAT-UIN = base64(十进制随机数) —— 每次随机，防重放（与上游 SDK 同做法）。 */
    private static String randomUin() {
        long v = Integer.toUnsignedLong(ThreadLocalRandom.current().nextInt());
        return Base64.getEncoder().encodeToString(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? DEFAULT_BASE_URL : out;
    }

    private static String isoTime(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ───────────────────────── 内部类型 ─────────────────────────

    /**
     * 凭证（botToken 是敏感值 ⇒ {@code toString} 做了掩码，防止有人顺手 log 出去）。
     */
    private record Credential(String baseUrl, String botToken) {
        @Override
        public String toString() {
            return "Credential[baseUrl=" + baseUrl + ", botToken=***]";
        }
    }

    /** {@code -14}：登录态失效（需重新扫码）。 */
    private static final class SessionExpiredException extends Exception {
        SessionExpiredException() {
            super("微信登录态失效(-14)");
        }
    }
}

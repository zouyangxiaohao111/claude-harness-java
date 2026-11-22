package com.nexusai.domain.im_channel.inbound.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.UserId;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.inbound.ImInboundListener;
import com.nexusai.domain.im_channel.inbound.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞书入站监听（长连接 / WebSocket）。
 *
 * <h2>为什么用官方 SDK 而不是自己开 WS</h2>
 * 飞书的长连接协议**没有公开文档**（官方原文：「长连接是飞书 SDK 内提供的能力」），
 * 帧是 protobuf。手写等于逆向私有协议 —— 平台改一次就断。所以只把 SDK 当**传输层**用：
 * 它负责建连、心跳、重连、解密验签，我们负责把事件变成 {@link InboundMessage}。
 * <p>⛔ 没用 SDK 的 {@code LarkChannel} 高层封装：它的归一化 + 策略门与本仓
 * {@code ImInboundRouter} + {@code ImSendService} 重复，接进来会变成两套分流逻辑。
 *
 * <h2>为什么要 {@code @Scope("prototype")}</h2>
 * 一个 {@code Client} 实例 = 一条长连接 = 一个飞书应用。用户可能建多个飞书机器人，
 * 单例 Bean 装不住（第二个实例启动会把第一个的状态覆盖掉）。由
 * {@code ImInboundManager} 每个实例 {@code getIfAvailable} 拿一个新对象。
 *
 * <h2>群聊为什么要判 @</h2>
 * 企微是**平台侧**只推被 @ 的消息，飞书不是：订阅了 {@code im.message.receive_v1}
 * 就收到群里**每一条**消息。不判 @ 的话机器人会对着别人的聊天乱回。
 * 判据是 {@code mentions[].mentioned_type == "bot"}；⚠️ 这个值在某些事件里可能缺省，
 * 所以日志会把全量 mentions 打出来，真机对不齐时按日志收紧。
 */
@Component
@Scope("prototype")
public class FeishuInboundListener implements ImInboundListener {

    private static final Logger log = LoggerFactory.getLogger(FeishuInboundListener.class);

    private final ObjectMapper json = new ObjectMapper();

    /**
     * ⚠️ {@code required=false}：启动装配顺序上它可能还没就绪，
     * 但更重要的是**测试/无渠道环境不该因为缺它而起不来**。
     */
    @Autowired(required = false)
    private ImChannelService channelService;

    /** 换票 + 机器人名（{@code bot/v3/info} 的 app_name）。 */
    @Autowired(required = false)
    private com.nexusai.domain.im_channel.send.FeishuTokenService feishuTokenService;
    @Autowired(required = false)
    private com.nexusai.domain.im_channel.FeishuChatNameResolver nameResolver;

    /**
     * 授权用户（V313/V314）：open_id + 昵称。
     *
     * <p>为什么在 start 时读一次就够：这两个值只在「用户重新授权」时变，
     * 而重新授权不会重连 WS。真要同步可以直接重启监听（成本极低）。
     */
    private volatile String authorizedOpenId;
    private volatile String authorizedName;
    /**
     * 本实例（= 这个机器人自己）的显示名，例如「智能bot」。
     *
     * <p>为什么入站要它：p2p 单聊的**会话名是「对方」**。当你本人在跟机器人说话时，
     * 对方就是<b>机器人</b> ⇒ 会话名应该显示机器人叫什么，而不是你自己的名字。
     * （飞书事件不带会话名，`GET /im/v1/chats` 也不返回单聊 ⇒ 只能用实例名兜。）
     */
    private volatile String instanceDisplayName;
    /**
     * 本机器人自己的名字（{@code bot/v3/info} 的 app_name，如「智能bot」）。
     *
     * <p>私聊的会话名用它：私聊的「对方」就是机器人本身。拿不到才退回实例名，
     * 再拿不到由 {@code ImChatService} 落「飞书私聊 · 尾号 xxxx」兜底。
     */
    private volatile String botDisplayName;

    /** 当前连接（{@code com.lark.oapi.ws.Client}）。null = 未启动/已停。 */
    private volatile WsTransport transport;
    /** 已完成「预热重连」的实例 id（首连假活规避只做一次；换实例才重置）。 */
    private volatile String warmReconnectedFor;
    private volatile boolean running;
    /** 本监听器负责的实例 id（stop 时要回写状态，必须记住）。 */
    private volatile String instanceId;
    /** 建连工厂：生产用飞书 SDK；测试注入桩，避免真连平台。 */
    private TransportFactory transportFactory = FeishuInboundListener::sdkTransport;

    /** 长连接传输层的最小面：只为「可测试 + 状态可观测」而抽象。 */
    interface WsTransport {
        void start();
        void awaitReady(long timeoutMs) throws Exception;
        void close();
    }

    /** 按应用凭证创建传输层（含重连回调）。 */
    @FunctionalInterface
    interface TransportFactory {
        WsTransport create(String appId, String appSecret, EventDispatcher dispatcher,
                           Runnable onReconnecting, Runnable onReconnected);
    }

    @Override
    public String platform() {
        return "feishu";
    }

    @Override
    public void start(String instanceId, InboundHandler handler) {
        // 幂等：已在跑就直接返回（Manager 也会判，但这里不能只依赖它）
        if (running && transport != null) {
            return;
        }
        if (channelService == null) {
            throw new IllegalStateException("ImChannelService 未注入，无法启动飞书入站");
        }
        String credentialJson = channelService.credentialOf(instanceId)
                .orElseThrow(() -> new IllegalStateException("实例 " + instanceId + " 没有凭证"));
        Credential cred = parseCredential(credentialJson);

        // ⚠️ SDK 的 handler 是 abstract class（不是函数式接口）⇒ 必须写匿名类
        com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler receiveHandler =
                new com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        // 官方要求 3 秒内处理完且不抛异常，否则会重推。
                        // Router 内部是「轻量落库 + 异步入队」，但这里仍兜一层，绝不让异常漏进 SDK 收包循环。
                        try {
                            InboundMessage msg = toInbound(instanceId, event);
                            if (msg != null) {
                                handler.onMessage(msg);
                            }
                        } catch (RuntimeException e) {
                            log.warn("[ImInbound] 飞书事件归一/分发失败 instance={} : {}", instanceId, e.toString());
                        }
                    }
                };
        // 「这条 p2p 是不是你本人在跟机器人说话」——只有那种情况才有名字可填。
        // 别人私聊机器人时 open_id 不同 ⇒ 不能拿授权人的名字冒充对方。
        try {
            this.authorizedOpenId = channelService.userOpenIdOf(instanceId).orElse(null);
            this.authorizedName = channelService.userNameOf(instanceId).orElse(null);
            // 机器人自己的名字（p2p 会话名用它，因为对方是机器人）
            this.instanceDisplayName = channelService.findById(instanceId)
                    .map(com.nexusai.model.im_channel.ImInstanceEntry::displayName)
                    .orElse(null);
            // 机器人名（私聊会话名用它）：start 时取一次，换票失败不影响收消息
            if (feishuTokenService != null && nameResolver != null) {
                try {
                    String token = feishuTokenService.tokenFor(instanceId, cred.appId(), cred.appSecret());
                    this.botDisplayName = nameResolver.fetchBotName(token);
                } catch (Exception ignore) {
                    this.botDisplayName = null;   // 回退到实例名 / 尾号
                }
            }
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 读取授权用户信息失败（会话名会退回 id）instance={} err={}",
                    instanceId, e.toString());
        }

        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(receiveHandler)
                .build();

        // 状态先写「连接中」：DB 只有 connected/disconnected/error 三态，
        // 用一个说明字段表达过渡，避免 UI 在握手完成前假装已连接。
        this.instanceId = instanceId;
        markStatus(instanceId, "disconnected", "正在连接飞书长连接…");
        WsTransport t = transportFactory.create(cred.appId(), cred.appSecret(), dispatcher,
                // 重连开始：连接已断，说明原因；重连成功由 SDK 的 onReconnected 回写
                () -> markStatus(instanceId, "disconnected", "连接断开，正在重连…"),
                () -> markStatus(instanceId, "connected", "飞书长连接已恢复"));
        this.transport = t;
        this.running = true;
        try {
            t.start();   // 异步建连；失败会自己按 autoReconnect 重试
            log.info("[ImInbound] 飞书长连接已启动 instance={} appId={}", instanceId, cred.appId());
        } catch (RuntimeException e) {
            this.running = false;
            this.transport = null;
            markStatus(instanceId, "error", "飞书长连接启动失败");
            throw e;
        }
        // SDK 的 onReconnected **只在重连成功时**触发，首次握手结果要靠 awaitReady 才拿得到。
        // 放在后台线程里等：不能阻塞「启动接收」这个 HTTP 请求。
        Thread readyWatcher = new Thread(() -> {
            try {
                t.awaitReady(15_000);
                if (this.transport == t && running) {
                    markStatus(instanceId, "connected", "飞书长连接已连接");
                    // [首连假活规避] 实测：进程启动后**第一次**建好的长连接会出现「平台侧
                    // 推送 SUCCESS、本端却收不到事件」——SDK 的 readyFuture 已完成，但平台
                    // 仍把事件路由到上一条已断的连接（旧进程遗留的注册）。用户在 UI 里
                    // 断开→启动一次后立即恢复。这里就绪后自动做一次同样的「预热重连」，
                    // 把本进程的连接重新注册为最新，避免首次进入静默。
                    // ⚠️ 每个实例只做一次（换实例才重置），防止无限重连。
                    if (!instanceId.equals(warmReconnectedFor)) {
                        warmReconnectedFor = instanceId;
                        log.info("[ImInbound] 飞书长连接首次就绪 → 预热重连一次 instance={}", instanceId);
                        WsTransport old = this.transport;
                        this.transport = null;   // 让下面的 start() 不被幂等判断拦下
                        this.running = false;
                        if (old != null) {
                            try { old.close(); } catch (RuntimeException ignore) { }
                        }
                        start(instanceId, handler);
                    } else {
                        // [预热重连可观测] 重建后的连接就绪 —— 与上面「首次就绪」成对，
                        // 日志里能看出「触发 → 重建 → 就绪」三步是否走完。
                        log.info("[ImInbound] 飞书预热重连后已就绪 instance={}", instanceId);
                    }
                }
            } catch (Exception e) {
                if (this.transport == t) {
                    // SDK 仍会按 autoReconnect 重试；这里先把失败摆到卡片上，
                    // 后续重连成功会被 onReconnected 覆盖成 connected。
                    markStatus(instanceId, "error", "飞书长连接失败：" + brief(e));
                    log.warn("[ImInbound] 飞书首次握手未完成 instance={} err={}", instanceId, e.toString());
                }
            }
        }, "feishu-ws-ready-" + instanceId);
        readyWatcher.setDaemon(true);
        readyWatcher.start();
    }

    @Override
    public void stop() {
        WsTransport t = this.transport;
        String id = this.instanceId;
        this.transport = null;
        this.instanceId = null;
        this.running = false;
        if (t != null) {
            try {
                t.close();
            } catch (RuntimeException e) {
                log.debug("[ImInbound] 关闭飞书长连接时异常（忽略）: {}", e.toString());
            }
        }
        if (id != null) {
            markStatus(id, "disconnected", "已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 生产传输层：飞书 SDK 的 {@code ws.Client}。
     *
     * <p>为什么包一层：SDK 的首次连接结果没有回调（{@code onReconnected} 只在重连成功时触发），
     * 只能通过 {@code awaitReady} 得知；包一层后测试也能注入桩，不会真连平台。
     */
    private static WsTransport sdkTransport(String appId, String appSecret, EventDispatcher dispatcher,
                                            Runnable onReconnecting, Runnable onReconnected) {
        com.lark.oapi.ws.Client c = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .onReconnecting(onReconnecting)
                .onReconnected(onReconnected)
                .build();
        return new WsTransport() {
            @Override public void start() { c.start(); }
            @Override public void awaitReady(long timeoutMs) throws Exception { c.awaitReady(timeoutMs); }
            @Override public void close() { c.close(); }
        };
    }

    /**
     * 回写实例连接状态（DB 失败不影响连接本身）。
     *
     * <p>⛔ 这是飞书连接状态的**唯一写入口**：启动中/已连接/断线重连/失败/停止都走这里，
     * 前端只读 {@code im_channel_instance.status}，不另造判断。
     */
    private void markStatus(String id, String status, String detail) {
        if (id == null || channelService == null) {
            return;
        }
        try {
            channelService.markStatus(id, status, detail);
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 回写飞书连接状态失败 instance={} status={} err={}",
                    id, status, e.toString());
        }
    }

    /** 异常摘要：只取类型与短消息，避免把堆栈/凭证带进用户可见的状态字段。 */
    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    // ═════════════════════ 归一化 ═════════════════════

    /**
     * 飞书事件 → {@link InboundMessage}。
     *
     * @return null 表示「不该处理的」（非文本非媒体、群里没 @ 机器人、机器人自己发的）
     */
    InboundMessage toInbound(String instanceId, P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null) {
            return null;
        }
        EventMessage m = event.getEvent().getMessage();
        EventSender sender = event.getEvent().getSender();
        if (m == null) {
            return null;
        }
        // 机器人自己发的消息不要回环（飞书会把 bot 自己发的也推回来）
        if (sender != null && "app".equalsIgnoreCase(sender.getSenderType())) {
            return null;
        }
        String chatType = m.getChatType();          // p2p | group
        boolean isGroup = "group".equalsIgnoreCase(chatType);
        if (isGroup && !mentionedBot(m)) {
            log.info("[ImInbound] 飞书群消息未 @ 机器人，跳过 chat={} msg={}", m.getChatId(), m.getMessageId());
            return null;
        }

        String peerId = m.getChatId();               // ✅ 飞书单聊也有 chat_id（企微单聊没有）
        String contentJson = m.getContent();
        JsonNode content = parseJsonObject(contentJson);
        String msgType = m.getMessageType() == null ? "" : m.getMessageType();

        String text = null;
        List<InboundMessage.Media> media = new ArrayList<>();
        switch (msgType) {
            case "text" -> text = stripAtPrefix(textOf(content, "text"), m);
            case "post" -> text = stripAtPrefix(plainTextOfPost(content), m);
            case "image" -> {
                String key = textOf(content, "image_key");
                if (!key.isEmpty()) {
                    media.add(new InboundMessage.Media("IMAGE", null, key));
                }
            }
            case "file", "audio", "media" -> {
                String key = textOf(content, "file_key");
                if (!key.isEmpty()) {
                    String name = textOf(content, "file_name");
                    media.add(new InboundMessage.Media("FILE", name.isEmpty() ? null : name, key));
                }
            }
            default -> {
                // sticker / share_chat / system ... 目前不处理，但留日志方便后面补
                log.info("[ImInbound] 飞书未支持的消息类型，跳过 type={} msg={}", msgType, m.getMessageId());
                return null;
            }
        }
        if ((text == null || text.isBlank()) && media.isEmpty()) {
            return null;
        }

        UserId sid = sender == null ? null : sender.getSenderId();
        String senderOpenId = sid == null || sid.getOpenId() == null ? "" : sid.getOpenId();

        // 「这条 p2p 是不是你本人在跟机器人说话」——它同时决定两件事：
        //   ① 发送人名字（气泡上「谁说的」）⇒ 你本人 = authorizedName；
        //   ② 会话名（列表标题「这是跟谁的会话」）⇒ 对方是**机器人**，用实例名。
        // ⛔ 这两件事不能共用一个值：把「我」的名字当会话名，会话列表就会显示成
        //    「用户361874」——用户看到的却是「智能bot」，两边对不上（2026-11-19 修）。
        boolean isAuthorizedUser = !isGroup && senderOpenId != null && !senderOpenId.isBlank()
                && senderOpenId.equals(authorizedOpenId);
        String senderName = isAuthorizedUser ? authorizedName : null;
        // 私聊的「对方」永远是机器人 ⇒ 会话名用**机器人自己的名字**（如「智能bot」），
        // 拿不到才退回实例名（如「飞书」），仍拿不到由 upsert 落尾号兜底。
        // ⛔ 不能去查「对方这个人」：p2p 里除机器人外的成员是**用户本人**。
        String chatPeerName = isGroup ? null : botNameOrDefault();

        return new InboundMessage(
                instanceId,
                "feishu",
                peerId,
                chatPeerName,               // 会话名（p2p ⇒ 对方；对方是机器人时 = 实例名）
                senderOpenId,
                senderName,                 // 发送者昵称（p2p 且是你本人时可得）
                isGroup ? "group" : "p2p",
                m.getMessageId(),
                text,
                media,
                parseTimeMs(m.getCreateTime()),
                null);                      // ⛔ 不保留 raw：SDK 事件对象无法安全转 JsonNode，且我们已取全需要的字段
    }

    /** 私聊会话名：机器人名 > 实例名 > null（交给 upsert 写尾号兜底）。 */
    private String botNameOrDefault() {
        String bot = botDisplayName;
        if (bot != null && !bot.isBlank()) {
            return bot;
        }
        return instanceDisplayName;
    }

    /**
     * 群里是否 @ 了机器人。
     *
     * <p>飞书 mention 的 {@code mentioned_type} 为 {@code bot} 时即「@ 了机器人」。
     * ⚠️ 某些场景该字段缺省 —— 那时退回「有 mention 且 open_id 非空」的宽松判定，
     * 宁可多收一点也不要漏掉真 @（漏了用户会觉得机器人「不理我」）。
     */
    private boolean mentionedBot(EventMessage m) {
        MentionEvent[] mentions = m.getMentions();
        if (mentions == null || mentions.length == 0) {
            return false;
        }
        for (MentionEvent me : mentions) {
            if (me == null) {
                continue;
            }
            if ("bot".equalsIgnoreCase(me.getMentionedType())) {
                return true;
            }
        }
        // 宽松兜底：真机上 mentioned_type 缺失时，有 mention 就当被 @（多收不会错，漏收才是问题）
        return true;
    }

    /**
     * 剥掉正文里的 {@code @_user_1} 之类占位符。
     *
     * <p>飞书把 @ 渲染成 {@code @_user_N} 写在 text 里（真实昵称在 mentions 里），
     * 不剥的话模型会看到一串无意义占位符。
     */
    String stripAtPrefix(String text, EventMessage m) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text.replaceAll("@_user_\\d+\\s*", "").trim();
        // 用户在群里可能是「@机器人 帮我看看」⇒ 剥完剩下的就是正文
        return out.isEmpty() ? text.trim() : out;
    }

    /** post 富文本取纯文本（title + 各段 text 标签）。 */
    private String plainTextOfPost(JsonNode content) {
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String title = textOf(content, "title");
        if (!title.isEmpty()) {
            sb.append(title);
        }
        JsonNode paragraphs = content.get("content");
        if (paragraphs != null && paragraphs.isArray()) {
            for (JsonNode para : paragraphs) {
                if (!para.isArray()) {
                    continue;
                }
                for (JsonNode el : para) {
                    String tag = textOf(el, "tag");
                    if ("text".equals(tag) || "a".equals(tag)) {
                        sb.append(textOf(el, "text"));
                    } else if ("at".equals(tag)) {
                        // @ 人：用 user_name 比 user_id 可读
                        String name = textOf(el, "user_name");
                        if (!name.isEmpty()) {
                            sb.append('@').append(name).append(' ');
                        }
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = json.readTree(raw);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String textOf(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    /** 飞书 {@code create_time} 是毫秒字符串。 */
    private long parseTimeMs(String createTime) {
        if (createTime == null || createTime.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Long.parseLong(createTime.trim());
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 解析实例凭证（与 {@code FeishuSendStrategy} 同一套键名：appId/appSecret，兼容下划线）。
     */
    Credential parseCredential(String credentialJson) {
        JsonNode root = parseJsonObject(credentialJson);
        if (root == null) {
            throw new IllegalStateException("飞书凭证不是合法 JSON 对象");
        }
        String appId = textOf(root, root.has("appId") ? "appId" : "app_id");
        String appSecret = textOf(root, root.has("appSecret") ? "appSecret" : "app_secret");
        if (appId.isEmpty() || appSecret.isEmpty()) {
            throw new IllegalStateException("飞书凭证缺少 appId 或 appSecret");
        }
        return new Credential(appId, appSecret);
    }

    /** 飞书应用凭证。 */
    record Credential(String appId, String appSecret) {}
}

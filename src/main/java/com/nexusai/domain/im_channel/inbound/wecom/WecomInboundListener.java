package com.nexusai.domain.im_channel.inbound.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.inbound.ImInboundListener;
import com.nexusai.domain.im_channel.inbound.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 企业微信 WebSocket 长连接入站监听。
 *
 * <h2>它负责什么 / 不负责什么</h2>
 * <ul>
 *   <li><b>负责</b>：建连、鉴权、心跳、读超时、重连（{@link WecomWsClient}）
 *       + 把企微帧归一成 {@link InboundMessage}；</li>
 *   <li><b>不负责</b>：消息分流、落库、进 agent —— 那些是上层
 *       {@link com.nexusai.domain.im_channel.inbound.ImInboundRouter} 的事。</li>
 * </ul>
 *
 * <h2>🔴 单实例前提（务必读）</h2>
 * <b>一个监听器对象只服务一个 {@code instanceId}</b>。
 * <p>这不是我拍脑袋的约束，而是上游 {@code ImInboundManager} 的硬要求：
 * 它用 {@code ObjectProvider<ImInboundListener>} 取实现，而 {@code @Scope("prototype")}
 * 才保证「每个渠道实例拿到一个<em>新</em>的监听器对象」——
 * 否则第二个实例启动时会覆盖第一个的连接状态（两个实例的入站消息会串）。
 * <p>因此本类标了 {@code @Scope("prototype")}。<b>若被改成单例</b>，
 * 必须同时把内部状态改成按 instanceId 分桶，否则会出现「A 实例的消息投到 B 实例」。
 * <p>即便如此，本类仍做了防御：{@link #start(String, InboundHandler)} 开头会先
 * {@link #stop()} 一次 ⇒ 万一同一个对象被换实例复用，也只会留下「后一个实例」，
 * 不会留下僵尸连接（PIT-WC-011 同族：状态错乱 ⇒ 重复建连）。
 *
 * <h2>凭证从哪来</h2>
 * 走 {@link ImChannelService#credentialOf(String)}（返回<em>解密后</em>的 JSON）。
 * ⛔ 本类**不自己解密密文**、⛔ 不读 {@code ~/.config/wecom/credentials.enc}、
 * ⛔ 不把 botSecret 写进任何日志或异常消息。
 *
 * <h2>归一规则（企微帧 → {@link InboundMessage}）</h2>
 * <pre>
 * peerId    = chatid（群会话 id）；单聊时 chatid 是空串 ⇒ 回落 from.userid（PIT-WC-008）
 * chatKind  = chattype 权威（single|group）；缺失时才按 chatid 是否存在推断
 * text      = 文本内容（群聊会剥掉 "@机器人 " 前缀）
 * media     = 图片/文件/视频的平台引用（非文本消息 text=null）
 * messageId = msgid；缺失时退化用 req_id（去重键变弱，但不丢消息）
 * timestamp = create_time（★ 单位是「秒」，要 ×1000）
 * raw       = 原始整帧（⛔ 不进日志、不落库）
 * </pre>
 *
 * <h2>线程模型</h2>
 * 入站回调在 okhttp 的 IO 线程上被调用 ⇒ {@link InboundHandler#onMessage} 抛异常会打断收包循环，
 * 所以本类**吞掉所有异常并记日志**（见 {@code Callback#onFrame}）。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class WecomInboundListener implements ImInboundListener {

    private static final Logger log = LoggerFactory.getLogger(WecomInboundListener.class);

    /** 平台标识（与 {@code ImChannelService.PLATFORMS} 对齐）。 */
    private static final String PLATFORM = "wecom";

    /** 凭证里的 bot id 字段名（主用；{@code botid} 是平台侧的另一种拼写）。 */
    private static final String CREDENTIAL_FIELD_BOT_ID = "botId";
    /** 凭证里的 bot secret 字段名（主用；{@code secret} 是平台侧的另一种拼写）。 */
    private static final String CREDENTIAL_FIELD_BOT_SECRET = "botSecret";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ImChannelService channelService;

    /** 当前连接（null = 未运行）。 */
    private volatile WecomWsClient client;
    /** 当前服务的实例 id（null = 未运行）。 */
    private volatile String instanceId;

    @Override
    public String platform() {
        return PLATFORM;
    }

    /**
     * 启动监听（<b>不阻塞</b>：内部起线程 + 异步建连）。
     *
     * @param instanceId 渠道实例 id（用来取凭证 + 日志定位）
     * @param handler    收到归一消息时的回调
     * @throws IllegalArgumentException instanceId / handler 为空
     * @throws IllegalStateException    凭证缺失或非法（上游 {@code ImInboundManager} 会捕获并记日志）
     */
    @Override
    public void start(String instanceId, InboundHandler handler) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为空");
        }
        // 防御：同一对象被复用/换实例 ⇒ 先停掉旧的，避免僵尸连接
        stop();

        Credential credential = resolveCredential(instanceId);
        this.instanceId = instanceId;
        WecomWsClient c = new WecomWsClient(
                credential.botId(), credential.botSecret(), new Callback(instanceId, handler));
        this.client = c;
        c.start();
        log.info("[ImInbound] 企微入站监听已启动 instance={}", instanceId);
    }

    /**
     * 停止监听（幂等）。
     *
     * <p>会关掉 WS（打断阻塞中的读）并释放传输层资源；线程随之退出。
     */
    @Override
    public void stop() {
        WecomWsClient c = this.client;
        String inst = this.instanceId;
        this.client = null;
        this.instanceId = null;
        if (c != null) {
            try {
                c.close();
            } catch (Throwable t) {
                log.warn("[ImInbound] 企微入站停止时异常（忽略）instance={} err={}", inst, t.toString());
            }
        }
        if (inst != null) {
            markStatus(inst, "disconnected", "已停止");
            log.info("[ImInbound] 企微入站监听已停止 instance={}", inst);
        }
    }

    @Override
    public boolean isRunning() {
        WecomWsClient c = this.client;
        return c != null && c.isRunning();
    }

    // ═══════════════════════ 凭证 ═══════════════════════

    /**
     * 取凭证并解析 {@code botId} / {@code botSecret}。
     *
     * <p>🔴 <b>PIT-WC-004</b>：企微智能机器人的凭证是 {@code bot_id} + {@code bot_secret}
     * （不是 corpId/agentId/secret/token/aesKey）。
     * <p>⛔ 任何异常消息都**不带**凭证内容（只带 instanceId）。
     */
    private Credential resolveCredential(String instanceId) {
        Optional<String> opt;
        try {
            opt = channelService.credentialOf(instanceId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取企微凭证失败: " + instanceId, e);
        }
        if (opt.isEmpty() || opt.get().isBlank()) {
            throw new IllegalStateException("企微实例未配置凭证: " + instanceId);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(opt.get());
        } catch (Exception e) {
            // ⛔ 不把原始 JSON 贴进消息（里面可能有别的敏感字段）
            throw new IllegalStateException("企微凭证不是合法 JSON: " + instanceId);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("企微凭证不是 JSON 对象: " + instanceId);
        }
        String botId = text(root, CREDENTIAL_FIELD_BOT_ID);
        if (botId == null) {
            botId = text(root, "botid");
        }
        String botSecret = text(root, CREDENTIAL_FIELD_BOT_SECRET);
        if (botSecret == null) {
            botSecret = text(root, "secret");
        }
        if (botId == null) {
            throw new IllegalStateException("企微凭证缺少 botId: " + instanceId);
        }
        if (botSecret == null) {
            throw new IllegalStateException("企微凭证缺少 botSecret: " + instanceId);
        }
        return new Credential(botId, botSecret);
    }

    /** 凭证（⛔ 不覆写 toString，避免 secret 被顺手打出来）。 */
    private record Credential(String botId, String botSecret) {
    }

    // ═══════════════════════ WS 回调 ═══════════════════════

    /**
     * 客户端回调适配。
     *
     * <p>⚠️ 全部方法都在 okhttp 的 IO 线程上被调用 ⇒ 抛异常会打断收包循环 ⇒ 一律吞掉。
     */
    private final class Callback implements WecomWsClient.FrameListener {

        private final String instanceId;
        private final InboundHandler handler;

        Callback(String instanceId, InboundHandler handler) {
            this.instanceId = instanceId;
            this.handler = handler;
        }

        @Override
        public void onFrame(WecomWsClient.InboundFrame frame) {
            try {
                if (!WecomWsProtocol.CMD_MSG_CALLBACK.equals(frame.cmd())) {
                    // 事件帧（进群、卡片点击…）本仓暂不消费 ⇒ 丢弃而不是误当消息
                    if (log.isDebugEnabled()) {
                        log.debug("[ImInbound] 企微事件帧暂不处理 instance={} cmd={}",
                                instanceId, frame.cmd());
                    }
                    return;
                }
                InboundMessage msg = normalize(instanceId, frame);
                if (msg == null) {
                    return;
                }
                // ⚠️ handler 抛异常会打断收包循环 ⇒ 这里兜住
                handler.onMessage(msg);
            } catch (Throwable t) {
                log.error("[ImInbound] 企微入站消息处理失败 instance={} cmd={}",
                        instanceId, frame.cmd(), t);
            }
        }

        @Override
        public void onAuthenticated() {
            markStatus(instanceId, "connected", "企微长连接已鉴权");
            log.info("[ImInbound] 企微长连接已鉴权 instance={}", instanceId);
        }

        @Override
        public void onDisconnected(String reason) {
            markStatus(instanceId, "disconnected", reason);
            if (log.isInfoEnabled()) {
                log.info("[ImInbound] 企微长连接断开 instance={} reason={}", instanceId, reason);
            }
        }

        @Override
        public void onReconnecting(int attempt, long delayMillis) {
            if (log.isInfoEnabled()) {
                log.info("[ImInbound] 企微长连接重连中 instance={} attempt={} delay={}ms",
                        instanceId, attempt, delayMillis);
            }
        }

        @Override
        public void onError(Throwable error) {
            // ⛔ 错误消息里不含凭证（鉴权失败只带 errcode/errmsg）
            log.warn("[ImInbound] 企微长连接错误 instance={} err={}", instanceId, error.toString());
        }
    }

    /** 回写实例连接状态（DB 失败不该影响连接本身）。 */
    private void markStatus(String id, String status, String detail) {
        if (id == null || channelService == null) {
            return;
        }
        try {
            channelService.markStatus(id, status, detail);
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 回写企微连接状态失败 instance={} status={} err={}",
                    id, status, e.toString());
        }
    }

    // ═══════════════════════ 归一 ═══════════════════════

    /**
     * 企微 {@code aibot_msg_callback} 帧 → {@link InboundMessage}。
     *
     * <p>帧形状（实测，参考 {@code 90-report/weknora-reference/WECOM-PROTOCOL-实测.md} §4）：
     * <pre>
     * { "cmd":"aibot_msg_callback",
     *   "headers":{"req_id":"..."},
     *   "body":{
     *     "msgid":"...", "aibotid":"...",
     *     "chatid":"...",              // 群聊=群 id；★ 单聊是空串（PIT-WC-008）
     *     "chattype":"single|group",
     *     "msgtype":"text|image|file|voice|video|mixed|stream",
     *     "create_time":1695199269,    // ★ 单位是「秒」
     *     "from":{"userid":"..."},
     *     "text":{"content":"..."},
     *     "image":{"url":"...","aeskey":"..."},
     *     "file":{"url":"...","aeskey":"..."},
     *     "voice":{"content":"..."},   // 已转写文本
     *     "mixed":{"msg_item":[{"msgtype":"text|image", ...}]} } }
     * </pre>
     *
     * @param instanceId 渠道实例 id（用回调自己持有的那份 ⇒ 与 stop() 无竞态）
     * @return 归一消息；<b>定位不到会话 ⇒ 返回 null</b>（异常报文，丢弃）
     */
    private InboundMessage normalize(String instanceId, WecomWsClient.InboundFrame frame) {
        JsonNode body = frame.body();
        if (body == null || !body.isObject()) {
            return null;
        }
        String senderId = text(body.at("/from/userid"));
        String chatId = text(body.path("chatid"));
        String chatType = text(body.path("chattype"));

        // [id-probe] 排障：企微单聊的 chatid 与 sessions/list 的 chat_id 对不上
        //   （入站 chatid 空 ⇒ 回落 from.userid；但 userid 不能当 chat_id 发消息，企微回 40073）。
        //   全量打印**所有顶层 key 与 from 的所有 key**（含值，但业务内容脱敏）——
        //   之前只挑字段打印，导致「私聊本人」与「bot 会话」两条帧看起来一模一样，
        //   无法定位真正的区分字段。
        if (log.isInfoEnabled()) {
            StringBuilder keys = new StringBuilder();
            body.fieldNames().forEachRemaining(k -> keys.append(k).append(','));
            StringBuilder fromKeys = new StringBuilder();
            JsonNode from = body.path("from");
            if (from.isObject()) {
                from.fieldNames().forEachRemaining(k -> fromKeys.append(k).append(','));
            }
            log.info("[ImInbound][id-probe] topKeys=[{}] fromKeys=[{}] "
                            + "chatid={} chattype={} msgtype={} msgid={} "
                            + "response_url={}",
                    keys, fromKeys,
                    text(body.path("chatid")),
                    text(body.path("chattype")),
                    text(body.path("msgtype")),
                    text(body.path("msgid")),
                    truncate(text(body.path("response_url")), 200));
            // 除内容类字段外，整帧原样打出来（便于发现未预料的区分字段）
            log.info("[ImInbound][id-probe] frame={}", redact(body).toString());
        }

        // 🔴 PIT-WC-008：单聊回调 chatid 是空串 ⇒ 会话标识必须回落 from.userid
        String peerId = firstNonBlank(chatId, senderId);
        if (peerId == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ImInbound] 企微帧缺少会话标识，丢弃 instance={}", instanceId);
            }
            return null;
        }
        boolean group = resolveGroup(chatType, chatId);

        String msgType = text(body.path("msgtype"));
        List<InboundMessage.Media> media = new ArrayList<>();
        String content = extractText(body, msgType, media, group);

        // messageId：拿不到就用 req_id（去重键退化，但不丢消息）
        String messageId = firstNonBlank(text(body.path("msgid")), frame.reqId());

        // 🔴 create_time 是「秒」，不是毫秒
        long createTimeSec = body.path("create_time").asLong(0L);
        long timestampMs = createTimeSec > 0 ? createTimeSec * 1000L : 0L;

        return new InboundMessage(
                instanceId,
                PLATFORM,
                peerId,
                null,                       // peerName：企微回调不带群名
                senderId,
                null,                       // senderName：企微回调不带发送者昵称（与微信一致）
                group ? "group" : "p2p",
                messageId,
                content,
                media,
                timestampMs,
                frame.raw());               // raw = 原始整帧（⛔ 不进日志/不落库）
    }

    /**
     * 会话类型判定。
     *
     * <p>为什么以 {@code chattype} 为准而不是「chatid 有没有」：
     * 离线 fixture {@code wecom-msg-callback-single.json} 的 chatid <b>有值</b>但
     * {@code chattype=single} ⇒ 只看 chatid 会把单聊误判成群聊（进而误判「权限回复」只在私聊接受那条约束）。
     */
    private static boolean resolveGroup(String chatType, String chatId) {
        if ("group".equalsIgnoreCase(chatType)) {
            return true;
        }
        if ("single".equalsIgnoreCase(chatType)) {
            return false;
        }
        // chattype 缺失 ⇒ 按 chatid 是否存在推断
        return chatId != null;
    }

    /**
     * 取正文 + 媒体。
     *
     * @param group 群聊时剥掉正文开头的 {@code @机器人} 前缀
     */
    private static String extractText(JsonNode body, String msgType,
                                      List<InboundMessage.Media> media, boolean group) {
        StringBuilder sb = new StringBuilder();

        // 文本正文（任何 msgtype 都可能带，先取）
        String direct = text(body.at("/text/content"));
        if (direct != null) {
            sb.append(group ? stripAtMention(direct) : direct);
        }
        if (msgType == null) {
            return sb.length() == 0 ? null : sb.toString();
        }

        switch (msgType) {
            case "image" -> addMedia(media, body.path("image"), "IMAGE", null);
            case "file" -> addMedia(media, body.path("file"), "FILE", "file_name");
            // 视频按「文件」归类：本仓 MediaKind 只有 IMAGE/FILE/UNKNOWN（刻意只有三种），
            // 原始 msgtype 保留在 ref 里，下游要区分时自己看。
            case "video" -> addMedia(media, body.path("video"), "FILE", "file_name");
            case "voice" -> {
                // 企微语音消息自带转写文本 ⇒ 当正文处理
                String transcribed = text(body.at("/voice/content"));
                if (transcribed != null && sb.length() == 0) {
                    sb.append(transcribed);
                }
            }
            case "mixed" -> {
                JsonNode items = body.at("/mixed/msg_item");
                if (items.isArray()) {
                    for (JsonNode it : items) {
                        String itemType = text(it.path("msgtype"));
                        String itemText = text(it.at("/text/content"));
                        if (itemText != null) {
                            if (sb.length() > 0) {
                                sb.append(' ');
                            }
                            sb.append(group ? stripAtMention(itemText) : itemText);
                        } else if ("image".equals(itemType)) {
                            addMedia(media, it.path("image"), "IMAGE", null);
                        } else if ("file".equals(itemType)) {
                            addMedia(media, it.path("file"), "FILE", "file_name");
                        } else if ("video".equals(itemType)) {
                            addMedia(media, it.path("video"), "FILE", "file_name");
                        }
                    }
                }
            }
            default -> {
                // text / stream / 未知类型：上面已取 text.content
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 收集一条媒体。
     *
     * <p>企微媒体节点形如 {@code {"url":"...","aeskey":"..."}}：
     * url 5 分钟有效、内容 AES 加密 ⇒ 两者都放进 {@code ref}（JSON 字符串），
     * 由下载器自己解释。
     * <p>⚠️ <b>PIT-WC-014</b>：{@code aeskey} 的 IV 来源**尚未验证**
     * （参考实现假设 IV = key 前 16 字节）⇒ 本类只负责「把引用带下去」，**不负责解密**。
     */
    private static void addMedia(List<InboundMessage.Media> out, JsonNode node,
                                 String kind, String fileNameField) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String url = text(node.path("url"));
        if (url == null) {
            return;
        }
        String aesKey = text(node.path("aeskey"));
        String fileName = fileNameField == null ? null : text(node.path(fileNameField));

        ObjectNode ref = MAPPER.createObjectNode();
        ref.put("url", url);
        if (aesKey != null) {
            ref.put("aeskey", aesKey);
        }
        out.add(new InboundMessage.Media(kind, fileName, ref.toString()));
    }

    /**
     * 剥离群聊正文开头的 {@code @机器人} 前缀。
     *
     * <p>依据：{@code 90-report/IM接入API文档.md} §2.4 实测样例
     * {@code "text":{"content":"@机器人名 你好"}} 并注明「⚠️ 带 @机器人 前缀，需剥离」；
     * 且企微<b>只在群聊</b>带这个前缀、单聊不带（同 WeKnora {@code wecom-longconn.go}）。
     * <p>策略照搬 WeKnora {@code stripAtMentionBasic}：双空格切分 → 启发式扫描
     * （空格后是 {@code /} 或 CJK）→ 退化为切第一个 @ 词。
     */
    static String stripAtMention(String content) {
        String s = content.trim();
        if (!s.startsWith("@")) {
            return s;
        }
        // 策略 1：部分企微客户端在 @ 与正文之间插两个空格
        int two = s.indexOf("  ");
        if (two > 0) {
            return s.substring(two + 2).trim();
        }
        // 策略 2：启发式 —— 机器人名是 ASCII 词，正文以 / 或非 ASCII(CJK) 开头
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == ' ' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '/' || next >= 0x80) {
                    return s.substring(i + 1).trim();
                }
            }
        }
        // 策略 3：退化为切掉第一个 @ 词
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(sp + 1).trim() : s;
    }

    // ═══════════════════════ 工具 ═══════════════════════

    /** 取文本字段（缺失/null/空白 ⇒ null）。 */
    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isValueNode()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 取字符串字段（只接受真正的字符串节点；⛔ 不把数字/对象当成凭证）。 */
    private static String text(JsonNode root, String field) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode v = root.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        return (a == null || a.isBlank()) ? (b == null || b.isBlank() ? null : b) : a;
    }

    /** 截断（日志用）。 */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 排障用：把业务内容字段脱敏后再打印。
     *
     * <p>只抹掉「内容/媒体」这类可能含敏感信息的字段值，**结构与其他字段全部保留** ——
     * 目标就是看清平台到底给了哪些可用于区分会话的 key。
     */
    private static JsonNode redact(JsonNode body) {
        if (body == null || !body.isObject()) {
            return body;
        }
        com.fasterxml.jackson.databind.node.ObjectNode copy = body.deepCopy();
        for (String k : new String[]{"text", "image", "file", "voice", "video", "mixed", "quote", "response_url"}) {
            if (copy.has(k)) {
                copy.put(k, "<redacted>");
            }
        }
        return copy;
    }
}

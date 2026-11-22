package com.nexusai.domain.im_channel.inbound;

import com.nexusai.application.agent.permission.StompChannelPermissionCallbacks;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入站消息分流（三平台共用）。
 *
 * <h2>三条路（必须分开，⛔ 不能一股脑塞给 agent）</h2>
 * <ol>
 *   <li><b>权限回复</b>（{@code yes abcde} / {@code no abcde}）⇒ 直接回传竞速决策，
 *       <b>不进 agent</b>。CC 原文：{@code the inbound "yes abc123" is intercepted BEFORE
 *       enqueue, so it never reaches Claude as a conversation turn}。</li>
 *   <li><b>普通聊天</b> ⇒ 落 {@code im_chat} 目录 + 入队触发 agent。</li>
 *   <li><b>命令</b>（{@code /xxx}）⇒ 暂不处理（记 debug 日志）。</li>
 * </ol>
 *
 * <h2>🔴 安全约束：权限回复只在私聊里接受</h2>
 * 群里任何人都能发 {@code yes abcde} —— 若不限 p2p，一个群里随便谁都能替你把
 * 「允许执行这个命令」批下来。CC 的渠道是私聊为主的 IM，我们是群/私聊都有，
 * 所以这条限制必须显式加上。
 *
 * <h2>为什么这里不查会话</h2>
 * 「这条消息属于哪个任务会话」由入队后的 drain 决定（与扫描同一套）。
 * 本类只做「消息属于谁、该走哪条路」的判定，不做任务编排。
 */
@Component
public class ImInboundRouter {

    private static final Logger log = LoggerFactory.getLogger(ImInboundRouter.class);

    /**
     * 权限回复格式：<b>动作 + 短 id</b>。
     *
     * <p>短 id 规则见 {@code ChannelPermission.shortRequestId}（5 个小写字母，a-z 去掉 l），
     * 这里放宽到 3~10 位字母数字：命中会更宽松，但 {@code resolve} 内部按 id 精确匹配，
     * 放宽不会误批（匹配不到就是 miss）。
     */
    private static final Pattern PERMISSION_REPLY = Pattern.compile(
            "^\\s*(yes|no|allow|deny)\\s+([a-z0-9]{3,10})\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * [B4] 裸回执（只有动作词，没有编号）。
     *
     * <p>安全约束：多条待答时不能猜 —— 猜错一条 = 批错一个工具调用。此时**不 claim**，
     * 回一条「当前有 N 个待确认，请回复 yes &lt;编号&gt;」并吞掉本条（⛔ 不能当聊天送进 agent）。
     * 仅当本会话恰好 1 条待答时才允许裸回执。
     */
    private static final Pattern BARE_REPLY = Pattern.compile(
            "^\\s*(yes|no|allow|deny)\\s*$", Pattern.CASE_INSENSITIVE);

    @Autowired private ImChannelService channelService;
    @Autowired private ImChatService chatService;
    @Autowired private NotificationQueue notificationQueue;

    /** [B4] 裸回执的提示要能真的发回用户（微信需 inbound 刚落库的 reply_token）。 */
    @Autowired(required = false)
    private com.nexusai.domain.im_channel.ImSendService imSendService;

    /** 渠道权限竞速的真正参赛者（v4 生产实现）；未注入 ⇒ 只记日志不参与竞速。 */
    @Autowired(required = false)
    private StompChannelPermissionCallbacks channelPermissionCallbacks;

    /**
     * 已处理过的平台消息 id（{@code 平台:messageId → 收到时刻}）。
     *
     * <h2>为什么必须去重</h2>
     * 三个平台都是**至少一次**投递语义，不是恰好一次：
     * <ul>
     *   <li>飞书：事件 3 秒内没处理完/抛异常就**超时重推**（官方文档明写）；</li>
     *   <li>企微 WS：断线重连后平台可能补发；</li>
     *   <li>微信 {@code getupdates}：游标推进失败会重复返回同一批。</li>
     * </ul>
     * 不去重的后果是**一句话唤醒两轮 agent** —— 用户会看到重复回答，
     * 更糟的是「发送类」回复会真的发两遍（对外可见的错误）。
     *
     * <h2>为什么不用 Caffeine/Guava</h2>
     * 只需要「最近 N 分钟内的 id 集合」，一条 {@code ConcurrentHashMap} 足够；
     * 引一个缓存库换不来可读性。清理靠写入时顺带做（见 {@link #markProcessed}）。
     *
     * <p>⚠️ 只存 id 不存内容：内存占用由 {@link #DEDUP_MAX} 封顶。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> processed =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 去重窗口：超过这么久的老 id 可以忘掉（平台重推不会隔半小时）。 */
    private static final long DEDUP_WINDOW_MS = 30 * 60_000L;
    /** 上限保护：超了就先清一轮过期的，仍超就整体清空（宁可丢去重也不能 OOM）。 */
    private static final int DEDUP_MAX = 5000;

    /**
     * 分流一条入站消息。
     *
     * <p>⚠️ 本方法**不抛异常**：它在平台收包线程里被调用，抛出去会打断收包循环。
     */
    public void route(InboundMessage msg) {
        if (msg == null) {
            return;
        }
        try {
            // ── ⓪ 平台重推去重（必须在落库之前：重复消息不该刷新「最近消息」）──
            if (isDuplicate(msg)) {
                log.info("[ImInbound] 重复投递已忽略 platform={} msgId={} peer={}",
                        msg.platform(), msg.messageId(), msg.peerId());
                return;
            }
            // ── ① 权限回复（不进 agent）──
            if (tryPermissionReply(msg)) {
                return;
            }
            // ── ② 落聊天目录（入站带来的会话）──
            String chatRowId = upsertChat(msg);
            // ── ③ 普通聊天 → 入队触发 agent（按接线解析会话）──
            enqueueForAgent(msg, chatRowId);
            evictExpired();
        } catch (RuntimeException e) {
            // 单条处理失败不能拖垮收包循环
            log.error("[ImInbound] 分流失败 platform={} instance={} peer={}",
                    msg.platform(), msg.instanceId(), msg.peerId(), e);
        }
    }

    /**
     * 是否已经处理过这条平台消息。
     *
     * <p>判据只认 {@code 平台 + messageId}：不同平台可能撞 id，
     * 而同一平台内 messageId 唯一。没有 messageId 的消息（微信 iLink 入站就没有）
     * **一律放行** —— 宁可重复处理，也不能因为「没 id」把正常消息全丢掉。
     *
     * @return true = 重复，调用方应直接返回
     */
    private boolean isDuplicate(InboundMessage msg) {
        String messageId = msg.messageId();
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        return processed.putIfAbsent(msg.platform() + ":" + messageId, System.currentTimeMillis()) != null;
    }

    /** 顺带清理过期 id（在上层调用 {@link #isDuplicate} 之后由定时/写入触发都可，这里由写入触发）。 */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        if (processed.size() > DEDUP_MAX) {
            processed.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);
            if (processed.size() > DEDUP_MAX) {
                processed.clear();   // 极端情况：整体清空也不影响正确性（只会漏挡住一条重推）
            }
        }
    }

    /**
     * 尝试把消息当作「权限回复」处理。
     *
     * @return true = 已作为权限回复处理（调用方不要再进 agent）
     */
    private boolean tryPermissionReply(InboundMessage msg) {
        // 🔴 只在私聊接受：群里谁都能回 yes，等于谁都能替你授权
        if (msg.isGroup()) {
            return false;
        }
        if (!msg.hasText()) {
            return false;
        }
        Matcher m = PERMISSION_REPLY.matcher(msg.text());
        if (!m.matches()) {
            // [B4] 裸 yes/no：本会话恰好 1 条待答才放行；多条 ⇒ 拒绝 + 提示带编号。
            Matcher bare = BARE_REPLY.matcher(msg.text());
            if (!bare.matches()) {
                return false;
            }
            return tryBarePermissionReply(msg, bare.group(1));
        }
        String action = m.group(1).toLowerCase(Locale.ROOT);
        String requestId = m.group(2).toLowerCase(Locale.ROOT);
        String behavior = ("yes".equals(action) || "allow".equals(action)) ? "allow" : "deny";

        if (channelPermissionCallbacks == null) {
            log.warn("[ImInbound] 收到权限回复但通道回调未接线，忽略: requestId={} behavior={}",
                    requestId, behavior);
            return true;   // 仍然吞掉：它是权限回复，不该进 agent
        }
        // [P0.1] 带来源连接：生产实现会校验「这条回执来自本会话绑定的哪个渠道连接」
        boolean accepted = channelPermissionCallbacks.resolveFrom(
            requestId, behavior, msg.platform(), msg.instanceId(), msg.peerId());
        if (accepted) {
            if (log.isInfoEnabled()) {
                log.info("[ImInbound] 权限回复 platform={} requestId={} behavior={} accepted=true",
                        msg.platform(), requestId, behavior);
            }
            return true;
        }
        // [F4] 迟到/重复回执：请求已被桌面或其它端裁决（或短 id 早已失效）。
        //   ⛔ 不能静默——用户会以为自己批了或拒了，实际没有任何效果。
        log.warn("[ImInbound] 权限回复已过期或已被处理（忽略，不改任何状态）platform={} "
                        + "requestId={} behavior={}",
                msg.platform(), requestId, behavior);
        // accepted=false 说明该 id 不存在或已被别的 racer 抢先 resolve ——
        // 这是正常竞速结果，不是错误（本地弹窗那边会收到 permission-resolved 并关闭）。
        return true;
    }

    /**
     * [B4] 裸 {@code yes/no} 的判定与处理。
     *
     * <p>规则（用户裁定）：
     * <ul>
     *   <li>本会话 0 条待答 ⇒ 不是权限回复，按普通聊天处理（维持旧行为）；</li>
     *   <li>恰好 1 条 ⇒ 允许裸回执，直接对那一条 resolve；</li>
     *   <li>多条 ⇒ **拒绝**（不猜），回「当前有 N 个待确认，请回复 {动作} &lt;编号&gt;」并吞掉本条。</li>
     * </ul>
     * 找不到本会话（未接线）时同样视为「无待答」，不吞普通聊天。
     *
     * @return true = 已作为权限回复处理（调用方不要再进 agent）
     */
    private boolean tryBarePermissionReply(InboundMessage msg, String actionWord) {
        if (channelPermissionCallbacks == null) {
            return false;
        }
        String sessionId = boundSessionOf(msg);
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        java.util.List<String> pending =
                channelPermissionCallbacks.pendingShortIdsForSession(sessionId);
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        String behavior = ("yes".equalsIgnoreCase(actionWord) || "allow".equalsIgnoreCase(actionWord))
                ? "allow" : "deny";
        if (pending.size() > 1) {
            String action = "allow".equals(behavior) ? "yes" : "no";
            String hint = "当前有 " + pending.size() + " 个待确认，请回复 " + action + " <编号>";
            replyTextQuietly(msg, hint);
            log.info("[ImInbound] 裸回复被拒绝：当前有 {} 个待确认（要求带编号）platform={} session={}",
                    pending.size(), msg.platform(), sessionId);
            return true;   // 吞掉：裸 yes/no 不能当聊天送进 agent
        }
        String shortId = pending.get(0);
        boolean accepted = channelPermissionCallbacks.resolveFrom(
                shortId, behavior, msg.platform(), msg.instanceId(), msg.peerId());
        if (log.isInfoEnabled()) {
            log.info("[ImInbound] 裸回复命中唯一待确认 platform={} shortId={} behavior={} accepted={}",
                    msg.platform(), shortId, behavior, accepted);
        }
        return true;
    }

    /** 本聊天接线到的任务会话（{@code im_chat.bound_session_id}）；查不到 ⇒ null。 */
    private String boundSessionOf(InboundMessage msg) {
        if (msg.instanceId() == null || msg.instanceId().isBlank()
                || msg.peerId() == null || msg.peerId().isBlank()) {
            return null;
        }
        try {
            return chatService.findByInstanceAndPeer(msg.instanceId(), msg.peerId())
                    .map(com.nexusai.model.im_channel.ImChatEntry::boundSessionId)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 查询接线失败 instance={} peer={} err={}",
                    msg.instanceId(), msg.peerId(), e.toString());
            return null;
        }
    }

    /** [B4] 提示尽量送达；失败只 warn（绝不因为提示发不出去而把权限流程改判）。 */
    private void replyTextQuietly(InboundMessage msg, String text) {
        if (imSendService == null) {
            log.warn("[ImInbound] 待确认提示未发送：ImSendService 未接线 text={}", text);
            return;
        }
        try {
            var chat = chatService.findByInstanceAndPeer(msg.instanceId(), msg.peerId());
            if (chat.isEmpty()) {
                log.warn("[ImInbound] 待确认提示未发送：聊天目录不存在 instance={} peer={}",
                        msg.instanceId(), msg.peerId());
                return;
            }
            var results = imSendService.send(java.util.List.of(chat.get().id()), text);
            var first = (results != null && !results.isEmpty()) ? results.get(0) : null;
            if (first == null || first.status()
                    != com.nexusai.domain.im_channel.send.ImSendResult.Status.SENT) {
                log.warn("[ImInbound] 待确认提示未送达 chat={} code={} reason={}",
                        chat.get().id(),
                        first == null ? "IM_SEND_UNKNOWN" : first.errorCode(),
                        first == null ? "无投递结果" : first.reason());
            }
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 待确认提示发送异常（不影响权限流程）chat-peer={} err={}",
                    msg.peerId(), e.toString());
        }
    }

    /**
     * 入站 upsert 聊天目录（{@code source=inbound}）。
     *
     * <p>为什么不覆盖已有的 {@code source}：{@link ImChatService#upsert} 只在首次建立时定
     * source，入站不会把「扫描来的」改成「入站」（数据来源的历史事实不该被改写）。
     */
    private String upsertChat(InboundMessage msg) {
        if (msg.instanceId() == null || msg.peerId() == null) {
            return null;
        }
        String name = msg.peerName() != null && !msg.peerName().isBlank()
                ? msg.peerName()
                : (msg.isGroup() ? null : msg.senderName());
        String preview = msg.hasText() ? truncate(msg.text(), 100) : "[非文本消息]";
        String at = java.time.OffsetDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(
                                msg.timestampMs() > 0 ? msg.timestampMs() : System.currentTimeMillis()),
                        java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        try {
            var chat = chatService.upsert(msg.instanceId(), msg.platform(), msg.peerId(), name,
                    msg.isGroup() ? "group" : "p2p", null, preview, at,
                    ImChatService.SOURCE_INBOUND);
            return chat == null ? null : chat.id();
        } catch (RuntimeException e) {
            log.warn("[ImInbound] upsert 聊天失败 peer={} err={}", msg.peerId(), e.toString());
            return null;
        }
    }

    /**
     * 普通消息入队，触发 agent。
     *
     * <p><b>会话从接线来</b>：{@code im_chat.bound_session_id} 是「聊天 ↔ 任务」的唯一权威
     * （V305）。未接线 ⇒ 不唤醒 agent（用户裁定：会话没绑渠道就不激活）。
     * 与「消息扫描」的差异：扫描的会话来自渠道实例的会话目录，入站的来自用户手工接线。
     */
    private void enqueueForAgent(InboundMessage msg, String chatRowId) {
        if (!msg.hasText()) {
            // 非文本消息目前不触发 agent（媒体下载另开一期，见 InboundMessage.Media 注释）
            if (log.isDebugEnabled()) {
                log.debug("[ImInbound] 非文本消息，暂不触发 agent: peer={} media={}",
                        msg.peerId(), msg.media().size());
            }
            return;
        }
        // 🔴 按接线解析会话：这条消息该进哪个任务？
        //   「聊天 ↔ 任务」的唯一权威是 im_chat.bound_session_id（V305）。
        //   没接线 ⇒ **不唤醒 agent**（用户裁定：会话没绑渠道就不激活）。
        //   ⛔ 不要退化成「随便找个会话」或「进无会话上下文」——那会让 agent
        //      在一个用户看不见的地方跑起来，产出没人看到、还可能乱发消息。
        String sessionId = resolveBoundSession(chatRowId);
        if (sessionId == null || sessionId.isBlank()) {
            if (log.isInfoEnabled()) {
                log.info("[ImInbound] 该聊天未接线到任何任务，不唤醒 agent "
                        + "（去「IM 频道」把聊天接到任务）: platform={} peer={}",
                        msg.platform(), msg.peerId());
            }
            return;
        }

        String command = buildCommand(msg);
        // 🔴 notifyChats = 本条消息来的那个聊天：用户从飞书/企微/微信问我，
        //    我调 SendUserMessage 回话就该回**那里**，而不是只留在 App 里。
        //    不传的话 SendUserMessage 的投递分支直接跳过（BriefTool:486 判空）
        //    ⇒ 用户在 IM 里问一句、永远等不到回答。
        // 形态是 JSON 数组字符串（队列项跨线程边界，必须是不可变值）。
        String notifyChats = chatRowId == null ? null : "[\"" + chatRowId + "\"]";
        NotificationQueue.QueueItem item = new NotificationQueue.QueueItem(
                command, "prompt", NotificationQueue.Priority.LATER, null,
                "msg-" + UUID.randomUUID().toString().substring(0, 8), true,
                NotificationQueue.WORKLOAD_CRON, false, null,
                sessionId, null, null, null, null, null,
                notifyChats);
        notificationQueue.enqueue(item);
        if (log.isInfoEnabled()) {
            log.info("[ImInbound] 已入队触发 agent: platform={} peer={} session={} textLen={}",
                    msg.platform(), msg.peerId(), sessionId, msg.text().length());
        }
    }

    /** 该聊天接到了哪个任务（{@code im_chat.bound_session_id}）；未接线/查询失败 ⇒ null。 */
    private String resolveBoundSession(String chatRowId) {
        if (chatRowId == null || chatRowId.isBlank()) {
            return null;
        }
        try {
            return chatService.findById(chatRowId)
                    .map(com.nexusai.model.im_channel.ImChatEntry::boundSessionId)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 查询接线失败 chatId={} err={}", chatRowId, e.toString());
            return null;
        }
    }

    /** 把入站消息包装成给 agent 的 prompt（带来源元信息，否则 agent 不知道谁在哪说的）。 */
    private static String buildCommand(InboundMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>")
          .append("这是一条来自 ").append(platformLabel(msg.platform())).append(" 的入站消息。\n")
          .append("会话：").append(msg.peerName() == null ? msg.peerId() : msg.peerName())
          .append(msg.isGroup() ? "（群聊）" : "（私聊）").append('\n');
        if (msg.senderName() != null && !msg.senderName().isBlank()) {
            sb.append("发送者：").append(msg.senderName()).append('\n');
        }
        sb.append("</system-reminder>\n\n").append(msg.text());
        return sb.toString();
    }

    private static String platformLabel(String p) {
        if (p == null) return "IM";
        return switch (p) {
            case "wechat" -> "微信";
            case "feishu" -> "飞书";
            case "wecom" -> "企业微信";
            case "dingtalk" -> "钉钉";
            default -> p;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

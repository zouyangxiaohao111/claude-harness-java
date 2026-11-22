package com.nexusai.application.agent.messaging;

import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨会话「消息未投递」失败通知 · 落库优先、STOMP 只是即时提示。
 *
 * <h2>WHY 必须落库</h2>
 * CC 实测：过期/拒绝/接收方关闭后，发送方收到的是<b>写进 transcript 的一条 warning</b>
 * （并可能起一轮），发送方离线/重启后仍看得到。纯 STOMP 事件在发送方离线时彻底丢失
 * （本仓 peer 投递链已有「live==null → 只记日志」的丢弃点），所以：
 * <ol>
 *   <li>先往<b>发送方会话</b>写一条 meta 消息（role=user / author=接收方会话 /
 *       subtype={@code agent_message} / isMeta=true / structured_output 带
 *       {@code peer_failure{messageId, reason, receiverSessionId}}）；</li>
 *   <li>写入成功后才推 {@link PeerMessageFailureEvent} 做即时提示；</li>
 *   <li>发送方会话已删除 ⇒ 不写、不推，只记日志（结算本身仍成功）。</li>
 * </ol>
 *
 * <h2>一次性</h2>
 * 进程内按 messageId 去重（同一条消息不可能既 rejected 又 expired）；消息行 id 也由
 * messageId 确定性派生（{@code peer-failure-<messageId>}）+ 落库幂等 ⇒ 重启/重试不会产生第二行。
 *
 * <h2>不唤醒</h2>
 * ⛔ 不进 {@code NotificationQueue}、不触发模型推理；模型可见的只是这条历史消息本身，
 * 文案明确要求「不要在未获提示时重发」。
 */
@Component
public class PeerFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(PeerFailureNotifier.class);

    /** 确认卡超时（对话框默认 5 分钟）到期。 */
    public static final String REASON_EXPIRED = "expired";
    /** 用户显式拒绝。 */
    public static final String REASON_USER_DECLINED = "user-declined";
    /** 接收方会话关闭/删除（CC：会话一退，holding 消息全部结转 expired）。 */
    public static final String REASON_RECEIVER_CLOSED = "receiver-closed";
    /** 接收方 pending 超过 100 条，淘汰最旧；也用于收件队列 ≥ 50 拒新（CC {@code queue-full}）。 */
    public static final String REASON_QUEUE_FULL = "queue-full";

    /**
     * 接收侧令牌桶耗尽（CC {@code rate-limited}，原文 {@code sender exceeded the peer message rate limit}）。
     *
     * <p>与 CC 同口径：发送方<b>先</b>拿到成功 + message_id，接收侧准入失败后异步回执告知 dropped；
     * 本仓把该回执落成发送方会话里的一条 meta 通知（离线/重启后仍可见）。
     */
    public static final String REASON_RATE_LIMITED = "rate-limited";

    /** 相同正文去重丢弃（CC {@code duplicate}，原文 {@code identical to the previous message from this sender}）。 */
    public static final String REASON_DUPLICATE = "duplicate";

    /** 自动循环刹车暂停（CC {@code aVn/lVn} loop-paused；本仓发送前即拒，无队列丢弃）。 */
    public static final String REASON_RELAY_BUDGET = "relay-budget";

    /**
     * 批准时入站策略已变为 refuse（CC 回执 {@code refused}）：批准无法生效，消息被丢弃。
     *
     * <p>严格 CC 语义：批准即结算 —— 策略在批准瞬间拒绝时行直接终态（expired），
     * ⛔ 不保留「已批准待重试」。
     */
    public static final String REASON_REFUSED = "refused";

    /**
     * 批准后投递未达成（CC 回执 {@code dropped}）：限速 / 队容满 / 未接线 / 未确认等。
     *
     * <p>具体失败原因保留在日志 detail 里；状态集合不新增 dropped 值（仍是 expired 终态）。
     */
    public static final String REASON_DROPPED = "dropped";

    /** 批准时发送方会话已不可达（消息未投递）。 */
    public static final String REASON_SOURCE_NOT_LIVE = "source-not-live";

    /** 失败通知载荷（值全部来自服务端落库行/路由层，⛔ 不来自模型正文）。 */
    public record FailureNotice(String messageId, String sourceSessionId, String targetSessionId,
                                String targetTitle, String reason) {}

    @Autowired(required = false)
    private MessageService messageService;

    @Autowired(required = false)
    private SessionService sessionService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    /** 已成功通知的 messageId（进程内去重；DB 行 id 幂等是第二道保险）。 */
    private final Set<String> notified = ConcurrentHashMap.newKeySet();

    /** 测试/接线用 setter。 */
    public void setMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    public void setSessionService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 写一条失败通知到发送方会话，成功后再推 STOMP 事件（过期/关闭/淘汰路径用）。
     *
     * @param notice 失败信息（messageId + 收发双方 + 原因）；null/关键字段缺失 ⇒ false
     * @return true = 通知已落库（STOMP 失败不影响返回值）；false = 未落库（已删除/未接线/重复）
     */
    public boolean notifyFailure(FailureNotice notice) {
        if (!persistFailure(notice)) {
            return false;
        }
        push(notice);
        return true;
    }

    /**
     * 只落库、不推 STOMP（拒绝路径用：STOMP 由 {@code PeerRuntimeAdapter} 的
     * {@code peer.message.rejected} 事件负责，避免同一操作推两条相似提示）。
     *
     * <p><b>不依赖发送方在线</b>：会话记录在就能写（发送方离线/重启后仍可见）；
     * 已删除 ⇒ 返回 false 且只记日志。同一 messageId 只成功一次。
     *
     * @param notice 失败信息
     * @return true = 本次真的写入了一行；false = 重复/已删除/未接线/异常
     */
    public boolean persistFailure(FailureNotice notice) {
        if (notice == null || notice.messageId() == null || notice.messageId().isBlank()
            || notice.sourceSessionId() == null || notice.sourceSessionId().isBlank()) {
            return false;
        }
        if (!notified.add(notice.messageId())) {
            return false;
        }
        boolean written = false;
        try {
            if (!sourceSessionExists(notice.sourceSessionId())) {
                log.info("PEER FAILURE 通知跳过：发送方会话已删除/不存在 source={} messageId={} reason={}"
                        + "（结算已生效，仅记日志）",
                    notice.sourceSessionId(), notice.messageId(), notice.reason());
            } else {
                written = writeNotice(notice);
            }
        } catch (RuntimeException e) {
            log.warn("PEER FAILURE 通知落库异常（结算已生效，仅记日志）source={} messageId={} reason={} err={}",
                notice.sourceSessionId(), notice.messageId(), notice.reason(), e.toString());
        }
        if (!written) {
            // 未落库 ⇒ 允许后续结算重试（DB 行 id 幂等兜底，不会写出第二行）。
            notified.remove(notice.messageId());
            return false;
        }
        return true;
    }

    /** 发送方会话是否仍存在（删除后不得再写通知；查不到按「不存在」处理，不抛）。 */
    private boolean sourceSessionExists(String sourceSessionId) {
        SessionService svc = sessionService;
        if (svc == null) {
            return true; // 未接线：交给 MessageService 的 NotFound 兜底
        }
        try {
            svc.getById(sourceSessionId);
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            log.debug("PEER FAILURE 会话存在性检查失败（按存在处理，交给落库兜底）source={} err={}",
                sourceSessionId, e.toString());
            return true;
        }
    }

    private boolean writeNotice(FailureNotice notice) {
        MessageService svc = messageService;
        if (svc == null) {
            log.warn("PEER FAILURE 通知未落库：MessageService 未接线 source={} messageId={}",
                notice.sourceSessionId(), notice.messageId());
            return false;
        }
        // 确定性 id：同一 messageId 只可能有一行（落库侧幂等分支同样按 id 判重）。
        String noticeId = "peer-failure-" + notice.messageId();
        svc.createQueuedUserMessage(
            notice.sourceSessionId(), noticeId, render(notice), OffsetDateTime.now(),
            true /* isMeta：UI 隐藏，模型可见 */, null /* queuedOrigin：不是排队消息，不触发 run */,
            null, null,
            notice.targetSessionId() /* author = 接收方会话 */,
            "agent_message",
            structuredOutput(notice));
        log.info("PEER FAILURE 通知已落发送方会话 source={} messageId={} reason={} target={}",
            notice.sourceSessionId(), notice.messageId(), notice.reason(), notice.targetSessionId());
        return true;
    }

    private void push(FailureNotice notice) {
        SimpMessagingTemplate template = messagingTemplate;
        if (template == null) {
            log.debug("PEER FAILURE 事件未推送：STOMP 模板未接线（DB 已是真源）source={} messageId={}",
                notice.sourceSessionId(), notice.messageId());
            return;
        }
        try {
            template.convertAndSend("/topic/sessions/" + notice.sourceSessionId() + "/stream",
                new PeerMessageFailureEvent(notice, render(notice)));
        } catch (RuntimeException e) {
            log.warn("PEER FAILURE 事件推送失败（通知已落库，不回滚）source={} messageId={} err={}",
                notice.sourceSessionId(), notice.messageId(), e.toString());
        }
    }

    /**
     * structured_output 投影：{@code peer_failure{messageId, reason, receiverSessionId, receiverTitle}}。
     *
     * <p>前端/读侧可按 {@code peer_failure} 识别「这是失败通知，不是待确认来信」。
     */
    private static Map<String, Object> structuredOutput(FailureNotice notice) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("messageId", notice.messageId());
        failure.put("reason", notice.reason());
        failure.put("receiverSessionId", notice.targetSessionId());
        failure.put("receiverTitle", notice.targetTitle());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peer_failure", java.util.Collections.unmodifiableMap(failure));
        return out;
    }

    /**
     * 模型可见文案：CC 原文（expired 为实测原句）+ 显式「不要在未获提示时重发」。
     *
     * <p>⛔ 不用中文改写上游原文；原因前缀用英文短句，避免模型把「通知」误读成「可重试」。
     */
    static String render(FailureNotice notice) {
        String head = switch (notice.reason() == null ? "" : notice.reason()) {
            case REASON_EXPIRED ->
                "Your held message expired without approval and was not delivered to the recipient's "
                    + "Claude session.";
            case REASON_USER_DECLINED ->
                "The recipient user declined your message; it was not delivered to their Claude session.";
            case REASON_RECEIVER_CLOSED ->
                "The recipient's Claude session closed before your held message was approved, "
                    + "so it was not delivered.";
            case REASON_QUEUE_FULL ->
                // CC 原文（偏移 325455069）：this session has too many undelivered peer
                // messages queued
                "The recipient's session dropped your message: this session has too many "
                    + "undelivered peer messages queued.";
            case REASON_RATE_LIMITED ->
                // CC 原文（偏移 324034215）：sender exceeded the peer message rate limit
                "The recipient's session dropped your message: sender exceeded the peer message "
                    + "rate limit.";
            case REASON_DUPLICATE ->
                // CC 原文（偏移 324035701）：identical to the previous message from this sender
                "The recipient's session dropped your message: identical to the previous message "
                    + "from this sender.";
            case REASON_RELAY_BUDGET ->
                // CC 原文（偏移 336254506）：this session keeps answering other sessions, which
                // looks like an automatic loop
                "Not delivered: this session keeps answering other sessions, which looks like an "
                    + "automatic loop.";
            case REASON_REFUSED ->
                // CC 批准失败原文：That held message wasn't delivered (the recipient is no longer
                // accepting peer messages), so your approval couldn't apply and the message was dropped.
                "The recipient is no longer accepting peer messages, so your approval could not "
                    + "apply and the message was dropped.";
            case REASON_DROPPED ->
                // CC 批准失败原文：That held message wasn't delivered ... your approval couldn't
                // apply and the message was dropped.
                "That held message wasn't delivered, so your approval could not apply and the "
                    + "message was dropped.";
            case REASON_SOURCE_NOT_LIVE ->
                "The sender's Claude session is no longer live, so your approval could not apply "
                    + "and the message was dropped.";
            default ->
                "Your held message was not approved and was not delivered to the recipient's Claude session.";
        };
        String who = notice.targetTitle() == null || notice.targetTitle().isBlank()
            ? notice.targetSessionId() : notice.targetTitle();
        return head + "\n\n(message id: " + notice.messageId() + "; recipient: " + who + ")\n"
            + "Do not resend it unprompted: wait until the recipient asks for it.";
    }
}

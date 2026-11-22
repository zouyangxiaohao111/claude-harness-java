package com.nexusai.application.agent.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.eventbus.ws.StreamEvent;

import java.time.Instant;

/**
 * 跨会话「你的消息被拒」通知 · 服务端 → 发送方会话（STOMP 推送）。
 *
 * <p><b>topic</b>: {@code /topic/sessions/{sourceSessionId}/stream}（会话级单 topic）。
 *
 * <p><b>不触发模型推理</b>：本事件只做 UI 展示 —— ⛔ 不入 {@code NotificationQueue}、不写会话历史、
 * 不唤醒发送方会话（已退出/已删除的发送方不会因本通知起一轮 run）。
 *
 * <p><b>不盖 seq</b>：与 cron 旁路事件同款（旁路必达、不参与缺口检测）——前端对 seq 缺失的帧
 * 直接派发，不触发 repairGap 重拉。
 *
 * <p><b>载荷</b>：messageId（被拒消息）/ targetSessionId（拒绝方会话）/ targetTitle（可 null）/
 * sourceSessionId（通知投递目标）/ reason（user-declined）/ rejectedAt / text（可直接展示的一句文案）。
 * ⛔ 不含任何 endpoint token。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PeerMessageRejectedEvent extends StreamEvent {

    /** 事件类型（前端按此分发）。 */
    public static final String TYPE = "peer.message.rejected";

    private final String messageId;
    private final String targetSessionId;
    private final String targetTitle;
    private final String sourceSessionId;
    private final String reason;
    private final Instant rejectedAt;
    private final String text;

    public PeerMessageRejectedEvent(PeerMessageRouter.RejectionNotice notice) {
        super(TYPE, notice.sourceSessionId(), null);
        this.messageId = notice.messageId();
        this.targetSessionId = notice.targetSessionId();
        this.targetTitle = notice.targetTitle();
        this.sourceSessionId = notice.sourceSessionId();
        this.reason = notice.reason();
        this.rejectedAt = notice.rejectedAt();
        this.text = render(notice);
    }

    /** UI 可直接展示的一句文案（title 缺失时用会话 id，⛔ 不伪造标题）。 */
    private static String render(PeerMessageRouter.RejectionNotice notice) {
        String who = notice.targetTitle() == null || notice.targetTitle().isBlank()
            ? notice.targetSessionId() : notice.targetTitle();
        return "你发送给「" + who + "」的消息 " + notice.messageId() + " 已被拒绝（用户拒绝）。";
    }

    public String getMessageId() {
        return messageId;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public String getTargetTitle() {
        return targetTitle;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public String getText() {
        return text;
    }
}

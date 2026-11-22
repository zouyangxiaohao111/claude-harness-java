package com.nexusai.application.agent.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.eventbus.ws.StreamEvent;

import java.time.Instant;

/**
 * 跨会话「你的待确认消息未投递」失败通知 · 服务端 → 发送方会话（STOMP 推送）。
 *
 * <p><b>topic</b>: {@code /topic/sessions/{sourceSessionId}/stream}（会话级单 topic）。
 *
 * <p><b>先落库、后推送</b>：真正的权威通知是发送方会话记录里的一条 meta 消息
 * （见 {@link PeerFailureNotifier}）；本事件只是即时提示。⛔ 推送失败不影响已落库的通知。
 *
 * <p><b>不触发模型推理</b>：旁路事件，不入 {@code NotificationQueue}、不唤醒发送方。
 *
 * <p><b>不盖 seq</b>：与 cron / 拒绝通知同款（缺 seq 的帧前端直接派发，不触发 gap 修复）。
 *
 * <p><b>载荷</b>：messageId（未投递消息）/ targetSessionId（接收方会话）/ targetTitle（可 null）/
 * sourceSessionId（通知目标）/ reason（expired / user-declined / receiver-closed / queue-full）/
 * occurredAt / text（可直接展示的一句文案）。⛔ 不含任何 endpoint token。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PeerMessageFailureEvent extends StreamEvent {

    /** 事件类型（前端按此分发；纯提示，不改变会话状态）。 */
    public static final String TYPE = "peer.message.failed";

    private final String messageId;
    private final String targetSessionId;
    private final String targetTitle;
    private final String sourceSessionId;
    private final String reason;
    private final Instant occurredAt;
    private final String text;

    public PeerMessageFailureEvent(PeerFailureNotifier.FailureNotice notice, String text) {
        super(TYPE, notice.sourceSessionId(), null);
        this.messageId = notice.messageId();
        this.targetSessionId = notice.targetSessionId();
        this.targetTitle = notice.targetTitle();
        this.sourceSessionId = notice.sourceSessionId();
        this.reason = notice.reason();
        this.occurredAt = Instant.now();
        this.text = text;
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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getText() {
        return text;
    }
}

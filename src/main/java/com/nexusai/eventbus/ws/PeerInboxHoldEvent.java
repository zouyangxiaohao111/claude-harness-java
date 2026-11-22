package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 跨会话待收件实时事件 · {@code peer.inbox.hold}。
 *
 * <p><b>目的地</b>：{@code /topic/sessions/{targetSessionId}/peer-inbox}（按目标会话隔离）。
 * 与 {@code message.insert} 分开：hold 不是历史消息行，不唤醒模型、不进任何可消费队列。
 *
 * <p><b>时机</b>：hold <b>落库成功之后</b>才发布（{@code DbHeldPeerStore.hold}）；发布失败/无订阅者
 * 只影响「多快看到」，DB 已是真源（列表接口 + 轮询可补齐）。⛔ 不把发布失败伪装成已发布。
 *
 * <p><b>载荷</b>：字段与前端 {@code PeerHeldItemDto} 同形（sourceSessionId / sourceTitle /
 * sourceKind / fromMode / summary / body / reason / createdAt / seenAt / status /
 * admission / admissionReason）；正文是用户数据，以纯文本展示。⛔ 不含任何 endpoint token。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PeerInboxHoldEvent extends StreamEvent {

    public static final String TYPE = "peer.inbox.hold";

    private final String messageId;
    private final String targetSessionId;
    private final String sourceSessionId;
    private final String sourceTitle;
    private final String sourceKind;
    private final String fromMode;
    private final String summary;
    private final String body;
    private final String reason;
    private final String status;
    private final Instant createdAt;
    private final Instant seenAt;
    /** 入站准入方式（auto / approved；pending 行恒 null = 未准入）。 */
    private final String admission;
    /** 准入理由（mode-match / self-sent / user-approved / explicit-accept；可 null）。 */
    private final String admissionReason;

    public PeerInboxHoldEvent(String targetSessionId, String messageId, String sourceSessionId,
                              String sourceTitle, String sourceKind, String fromMode,
                              String summary, String body, String reason, String status,
                              Instant createdAt, Instant seenAt,
                              String admission, String admissionReason) {
        super(TYPE, targetSessionId, null);
        this.targetSessionId = targetSessionId;
        this.messageId = messageId;
        this.sourceSessionId = sourceSessionId;
        this.sourceTitle = sourceTitle;
        this.sourceKind = sourceKind;
        this.fromMode = fromMode;
        this.summary = summary;
        this.body = body;
        this.reason = reason;
        this.status = status;
        this.createdAt = createdAt;
        this.seenAt = seenAt;
        this.admission = admission;
        this.admissionReason = admissionReason;
    }

    /** 兼容构造（admission = null / admissionReason = null）。 */
    public PeerInboxHoldEvent(String targetSessionId, String messageId, String sourceSessionId,
                              String sourceTitle, String sourceKind, String fromMode,
                              String summary, String body, String reason, String status,
                              Instant createdAt, Instant seenAt) {
        this(targetSessionId, messageId, sourceSessionId, sourceTitle, sourceKind, fromMode,
            summary, body, reason, status, createdAt, seenAt, null, null);
    }

    public String getMessageId() {
        return messageId;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public String getFromMode() {
        return fromMode;
    }

    public String getSummary() {
        return summary;
    }

    public String getBody() {
        return body;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @JsonProperty("seenAt")
    public Instant getSeenAt() {
        return seenAt;
    }

    /** 入站准入方式：auto / approved；null = 未准入（pending）或未知。 */
    public String getAdmission() {
        return admission;
    }

    /** 准入理由：mode-match / self-sent / user-approved / explicit-accept；null = 未知。 */
    public String getAdmissionReason() {
        return admissionReason;
    }
}

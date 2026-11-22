package com.nexusai.repository.peer.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * 跨会话待收件（hold）持久化行 · 对齐 V322__peer_held_message.sql + V323__peer_held_approved.sql。
 *
 * <p><b>幂等键</b>：message_id（= PeerEnvelope.messageId）。写侧 INSERT OR IGNORE，
 * 同一 messageId 重复 hold 不产生第二行、不覆盖已有 seen_at/status。
 *
 * <p><b>时间</b>：created_at / seen_at 存 ISO-8601 字符串（{@code Instant.toString()}），
 * 与既有 JSON 接口时间格式一致；读侧按 Instant 解析。
 */
@Table("peer_held_message")
public class PeerHeldMessageRecord {

    @Id
    private String messageId;
    private String targetSessionId;
    private String sourceSessionId;
    private String sourceTitle;
    private String sourceKind;
    private String fromName;
    private String fromMode;
    private String fromAddress;
    /** JSON 数组字符串（hop 段逐个 24 hex）。 */
    private String hopChain;
    private String summary;
    private String body;
    private String reason;
    /** pending / accepted / rejected / expired（⛔ 严格 CC 语义下没有 approved 中间态）。 */
    private String status;
    private Long sourceEndpointEpoch;
    /** -1 = 重启后跳过代次比对（按 sessionId 重新 resolve）。 */
    private Long targetEndpointEpoch;
    /** 0/1。 */
    private Integer notifyWhenIdle;
    private String createdAt;
    /** NULL = 未读。 */
    private String seenAt;
    /** 用户批准的时刻（ISO-8601；V323 列；只在批准结算时写：accepted / 失败终态，旧行/未批准为 NULL）。 */
    private String approvedAt;
    /**
     * 入站准入方式（V325）：{@code auto} = 服务端自动通过；{@code approved} = 用户手动批准。
     * NULL = 未准入（pending 行）/旧行。
     */
    private String admission;
    /** 准入理由（V325）：mode-match / self-sent / user-approved / explicit-accept；NULL = 未知。 */
    private String admissionReason;
    /** 最后一次被待收件列表展示的时刻（V327，epoch millis）；NULL = 从未展示。 */
    private Long lastShownAt;
    /**
     * 到期时刻（V327，epoch millis）= last_shown_at + 对话框超时（默认 300000）。
     * NULL = 未被展示 ⇒ 不计时；到期未处理即结算 expired。
     */
    private Long pendingExpiresAt;

    public String getAdmission() {
        return admission;
    }

    public void setAdmission(String admission) {
        this.admission = admission;
    }

    public String getAdmissionReason() {
        return admissionReason;
    }

    public void setAdmissionReason(String admissionReason) {
        this.admissionReason = admissionReason;
    }

    public Long getLastShownAt() {
        return lastShownAt;
    }

    public void setLastShownAt(Long lastShownAt) {
        this.lastShownAt = lastShownAt;
    }

    public Long getPendingExpiresAt() {
        return pendingExpiresAt;
    }

    public void setPendingExpiresAt(Long pendingExpiresAt) {
        this.pendingExpiresAt = pendingExpiresAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public void setTargetSessionId(String targetSessionId) {
        this.targetSessionId = targetSessionId;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public void setSourceTitle(String sourceTitle) {
        this.sourceTitle = sourceTitle;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getFromMode() {
        return fromMode;
    }

    public void setFromMode(String fromMode) {
        this.fromMode = fromMode;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getHopChain() {
        return hopChain;
    }

    public void setHopChain(String hopChain) {
        this.hopChain = hopChain;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSourceEndpointEpoch() {
        return sourceEndpointEpoch;
    }

    public void setSourceEndpointEpoch(Long sourceEndpointEpoch) {
        this.sourceEndpointEpoch = sourceEndpointEpoch;
    }

    public Long getTargetEndpointEpoch() {
        return targetEndpointEpoch;
    }

    public void setTargetEndpointEpoch(Long targetEndpointEpoch) {
        this.targetEndpointEpoch = targetEndpointEpoch;
    }

    public Integer getNotifyWhenIdle() {
        return notifyWhenIdle;
    }

    public void setNotifyWhenIdle(Integer notifyWhenIdle) {
        this.notifyWhenIdle = notifyWhenIdle;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(String seenAt) {
        this.seenAt = seenAt;
    }

    public String getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(String approvedAt) {
        this.approvedAt = approvedAt;
    }
}

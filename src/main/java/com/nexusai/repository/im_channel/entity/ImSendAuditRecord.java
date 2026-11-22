package com.nexusai.repository.im_channel.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * {@code im_send_audit} 实体（V312）· IM 对外发言审计。
 *
 * <p><b>为什么要单独一张审计表</b>：IM 的「对外发言」分两种身份 —— 机器人名义（通知）与
 * 用户本人名义（AI 代替用户说话）。后者一旦发错，是**用户的社会信誉问题**而不是技术问题，
 * 必须能事后回答「这句话是谁让 AI 发的、发给谁、什么时间、实际是什么身份」。
 *
 * <p>⚠️ {@code identity}（实际身份）与 {@code requested_identity}（请求身份）分两列：
 * 「请求 USER 但被降级成 BOT」正是最需要被查出来的事件 —— 那意味着用户以为是自己说的话，
 * 实际是机器人说的。
 *
 * <p>⚠️ {@code textPreview} 只留前 200 字：审计表是「谁说了什么」的索引，不是内容仓库。
 */
@Table("im_send_audit")
public class ImSendAuditRecord {

    @Id private String id;

    /** 目标聊天的 {@code im_chat.id}（聊天不存在时为空）。 */
    private String targetChatId;
    /** 平台侧会话标识（群 id / 对端 id）。 */
    private String peerId;
    /** wechat | feishu | wecom | dingtalk。 */
    private String platform;
    /** 实际使用的身份：BOT | USER。 */
    private String identity;
    /** 请求的身份：BOT | USER。 */
    private String requestedIdentity;
    /** 正文摘要（最多 200 字）。 */
    private String textPreview;
    /** 附件数量。 */
    private Integer attachmentCount;
    /** SENT | SKIPPED | FAILED。 */
    private String status;
    /** 失败码。 */
    private String errorCode;
    /** 失败 / 降级原因。 */
    private String reason;
    /** 创建时间（ISO-8601）。 */
    private String createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTargetChatId() { return targetChatId; }
    public void setTargetChatId(String v) { this.targetChatId = v; }
    public String getPeerId() { return peerId; }
    public void setPeerId(String v) { this.peerId = v; }
    public String getPlatform() { return platform; }
    public void setPlatform(String v) { this.platform = v; }
    public String getIdentity() { return identity; }
    public void setIdentity(String v) { this.identity = v; }
    public String getRequestedIdentity() { return requestedIdentity; }
    public void setRequestedIdentity(String v) { this.requestedIdentity = v; }
    public String getTextPreview() { return textPreview; }
    public void setTextPreview(String v) { this.textPreview = v; }
    public Integer getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(Integer v) { this.attachmentCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String v) { this.errorCode = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
}

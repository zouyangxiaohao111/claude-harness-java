package com.nexusai.repository.im_message.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.im_message.ImMessageEntry;

/**
 * MyBatis-Flex 持久化记录：{@code im_message} 表行（V315）· 三平台统一消息表。
 *
 * <p>命名：camelCase → snake 精确映射（instanceId→instance_id、chatId→chat_id、
 * messageId→message_id、dedupKey→dedup_key、senderId→sender_id、senderName→sender_name、
 * msgType→msg_type、mediaJson→media_json、sentAt→sent_at、fetchedAt→fetched_at）。
 *
 * <p>⚠️ 时间列一律 String（ISO-8601 文本），与本仓 {@code im_chat} / {@code im_scan_channel} 同口径，
 * ⛔ 不引 LocalDateTime（会让 MyBatis-Flex 的类型处理器与 SQLite 的 TEXT 列打架）。
 *
 * <p>⚠️ {@code sent_at} 会被<b>字典序比较</b>（区间过滤、取最大值、排序全靠它）
 * ⇒ 写入方必须统一成同一 ISO 形态（同一时区偏移）；时区混写会让排序与区间过滤静默错乱。
 */
@Table("im_message")
public class ImMessageRecord {

    @Id private String id;

    private String instanceId;
    private String platform;
    private String chatId;
    /** 平台消息 id；⚠️ 企微恒为 null（ChatMessage 没有 messageId）。 */
    private String messageId;

    /**
     * 幂等键（UNIQUE(instance_id, dedup_key)）· <b>不进 domain record</b>。
     *
     * <p>⚠️ 它<b>不能</b>把 instanceId 拼进去：唯一索引已经是 (instance_id, dedup_key)，
     * 拼进去等于让同一实例的同一平台消息算出两个不同的键（索引失去意义）。
     */
    private String dedupKey;

    private String senderId;
    private String senderName;
    private String msgType;
    private String content;
    private String mediaJson;
    private String sentAt;
    /** in | out。 */
    private String direction;
    /** official | local。 */
    private String origin;

    /** 抓取时间（ISO-8601）· <b>不进 domain record</b>（排障信息，不是业务语义）。 */
    private String fetchedAt;

    public ImMessageEntry toDomain() {
        return new ImMessageEntry(id, instanceId, platform, chatId, messageId,
            senderId, senderName, msgType, content, mediaJson,
            sentAt, direction, origin);
    }

    /**
     * 从 domain record 还原持久化记录（<b>纯映射</b>）。
     *
     * <p>⚠️ {@code dedupKey} / {@code fetchedAt} 刻意留空 —— 它们是持久化细节，
     * 由 {@code ImMessageService} 统一补齐（去重键散落到各处 = 幂等迟早失效）。
     * 同理 {@code direction} / {@code origin} 的默认值也由服务层补（NOT NULL 列）。
     */
    public static ImMessageRecord fromDomain(ImMessageEntry e) {
        ImMessageRecord r = new ImMessageRecord();
        r.setId(e.id());
        r.setInstanceId(e.instanceId());
        r.setPlatform(e.platform());
        r.setChatId(e.chatId());
        r.setMessageId(e.messageId());
        r.setSenderId(e.senderId());
        r.setSenderName(e.senderName());
        r.setMsgType(e.msgType());
        r.setContent(e.content());
        r.setMediaJson(e.mediaJson());
        r.setSentAt(e.sentAt());
        r.setDirection(e.direction());
        r.setOrigin(e.origin());
        return r;
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }
    public String getPlatform() { return platform; }
    public void setPlatform(String v) { this.platform = v; }
    public String getChatId() { return chatId; }
    public void setChatId(String v) { this.chatId = v; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String v) { this.messageId = v; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String v) { this.dedupKey = v; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String v) { this.senderId = v; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String v) { this.senderName = v; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String v) { this.msgType = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getMediaJson() { return mediaJson; }
    public void setMediaJson(String v) { this.mediaJson = v; }
    public String getSentAt() { return sentAt; }
    public void setSentAt(String v) { this.sentAt = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public String getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(String v) { this.fetchedAt = v; }
}

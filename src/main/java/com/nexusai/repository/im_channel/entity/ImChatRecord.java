package com.nexusai.repository.im_channel.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.im_channel.ImChatEntry;

/**
 * MyBatis-Flex 持久化记录：{@code im_chat} 表行（V305）· 产品层聊天目录。
 *
 * <p>命名：camelCase → snake 精确映射（instanceId→instance_id、peerId→peer_id、
 * peerName→peer_name、chatKind→chat_kind、memberCount→member_count、
 * lastMessagePreview→last_message_preview、lastMessageAt→last_message_at、
 * boundSessionId→bound_session_id、bindMode→bind_mode、boundAt→bound_at）。
 */
@Table("im_chat")
public class ImChatRecord {

    @Id private String id;
    private String instanceId;
    private String platform;
    private String peerId;
    private String peerName;
    /** V319 · 平台给的昵称（可空：微信拿不到、飞书可能无通讯录权限）。 */
    private String nickName;
    private String chatKind;
    private Integer memberCount;
    private String lastMessagePreview;
    private String lastMessageAt;
    private String boundSessionId;
    private String bindMode;
    private String boundAt;

    /**
     * 入站带来的会话令牌（V310）· 微信 iLink 主动发消息**必须**带它，否则会发出空白消息。
     *
     * <p>⛔ 敏感值：{@link #toDomain()} 刻意<b>不</b>包含它 —— 它能让别人冒充我们发消息。
     * 只在 {@code ImSendService} 内部经 {@code ImChatService.replyTokenOf()} 读取。
     */
    private String replyToken;

    /** 令牌过期时间（ISO-8601）；null = 无过期概念 / 尚未写入。 */
    private String replyTokenExpiresAt;

    /** 数据来源：inbound | scanned（本地微信扫描）| listed（平台列表拉取）。 */
    private String source;

    /** 最后一条**出站**消息时间（区分"对方说了话"与"我说了话"）。 */
    private String lastOutboundAt;

    /**
     * V335 · 机器人是否在**该会话**里（群聊的入站可达性）。
     *
     * <p>{@code null} = 尚未判定过 ⇒ 读侧按「可达」处理（上线首日不误隐藏旧行）。
     * <p>权威来源：bot 身份的 {@code GET /im/v1/chats}（= 机器人作为成员的群）。
     * p2p 与微信恒为 true —— 单聊不进群列表，微信登录账号本身就是机器人。
     */
    private Boolean botPresent;

    /** 最近一次判定的时刻（ISO-8601）；null = 从未判定。 */
    private String botPresentAt;

    private String createdAt;
    private String updatedAt;

    public ImChatEntry toDomain() {
        return new ImChatEntry(id, instanceId, platform, peerId, peerName, chatKind,
            memberCount, lastMessagePreview, lastMessageAt, boundSessionId, bindMode, boundAt,
            createdAt, updatedAt, nickName, botPresent);
    }

    public static ImChatRecord fromDomain(ImChatEntry e) {
        ImChatRecord r = new ImChatRecord();
        r.setId(e.id());
        r.setInstanceId(e.instanceId());
        r.setPlatform(e.platform());
        r.setPeerId(e.peerId());
        r.setPeerName(e.peerName());
        r.setNickName(e.nickName());
        r.setChatKind(e.chatKind());
        r.setMemberCount(e.memberCount());
        r.setLastMessagePreview(e.lastMessagePreview());
        r.setLastMessageAt(e.lastMessageAt());
        r.setBoundSessionId(e.boundSessionId());
        r.setBindMode(e.bindMode());
        r.setBoundAt(e.boundAt());
        r.setBotPresent(e.botPresent());
        r.setCreatedAt(e.createdAt());
        r.setUpdatedAt(e.updatedAt());
        return r;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
    public String getPeerName() { return peerName; }
    public void setPeerName(String peerName) { this.peerName = peerName; }
    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }
    public String getChatKind() { return chatKind; }
    public void setChatKind(String chatKind) { this.chatKind = chatKind; }
    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }
    public String getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(String lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public String getBoundSessionId() { return boundSessionId; }
    public void setBoundSessionId(String boundSessionId) { this.boundSessionId = boundSessionId; }
    public String getBindMode() { return bindMode; }
    public void setBindMode(String bindMode) { this.bindMode = bindMode; }
    public String getBoundAt() { return boundAt; }
    public void setBoundAt(String boundAt) { this.boundAt = boundAt; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    // ── V310 新增列（reply_token 等）──
    // ⚠️ replyToken 不进 toDomain()：它是能让别人冒充发消息的敏感值。

    public String getReplyToken() { return replyToken; }
    public void setReplyToken(String replyToken) { this.replyToken = replyToken; }
    public String getReplyTokenExpiresAt() { return replyTokenExpiresAt; }
    public void setReplyTokenExpiresAt(String replyTokenExpiresAt) { this.replyTokenExpiresAt = replyTokenExpiresAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getLastOutboundAt() { return lastOutboundAt; }
    public void setLastOutboundAt(String lastOutboundAt) { this.lastOutboundAt = lastOutboundAt; }
    public Boolean getBotPresent() { return botPresent; }
    public void setBotPresent(Boolean botPresent) { this.botPresent = botPresent; }
    public String getBotPresentAt() { return botPresentAt; }
    public void setBotPresentAt(String botPresentAt) { this.botPresentAt = botPresentAt; }
}

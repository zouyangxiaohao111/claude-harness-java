package com.nexusai.repository.im_channel.entity;

/**
 * 扫描命中的微信消息（只读投影，不是表实体）。
 *
 * <p>扫描规则执行时按「会话 + 关键词 + 时间窗」筛 {@code wechat_message}，
 * 这里只取生成审批项需要的字段（正文摘要 / 发送人 / 时间 / 所属会话展示名）。
 */
public class WechatMessageHit {

    private String id;
    private String conversationId;
    private String displayName;
    private String sender;
    private Long msgTime;
    private String contentPreview;
    private String searchText;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public Long getMsgTime() { return msgTime; }
    public void setMsgTime(Long msgTime) { this.msgTime = msgTime; }
    public String getContentPreview() { return contentPreview; }
    public void setContentPreview(String contentPreview) { this.contentPreview = contentPreview; }
    public String getSearchText() { return searchText; }
    public void setSearchText(String searchText) { this.searchText = searchText; }
}

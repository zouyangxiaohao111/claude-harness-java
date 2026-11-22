package com.nexusai.repository.im_channel.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * 只读映射：{@code wechat_conversation}（V301，属于 wechatlog 域）。
 *
 * <p><b>为什么 IM 域要读这张表</b>：扫描规则的「监控会话」选择器要列出
 * 「群名 + 累计条数 + wxid」，数据源就是本机微信同步产物。这是<b>只读</b>用途 ——
 * ⛔ 写入方仍然只有 wechatlog 的同步任务，本类不做任何 insert/update，
 * 避免同一张表出现第二个写者。
 *
 * <p>⚠️ 表结构由 V301 固化；若 wechatlog 侧将来改列，本类要同步（编译期不会报错，
 * 因为只用到少数字段 —— 已用最小字段集降低耦合）。
 */
@Table("wechat_conversation")
public class WechatConversationRecord {

    @Id private String id;
    private String accountWxid;
    private String conversationId;
    private String displayName;
    private Integer isGroup;
    private Long lastMessageTime;
    private Integer messageCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccountWxid() { return accountWxid; }
    public void setAccountWxid(String accountWxid) { this.accountWxid = accountWxid; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Integer getIsGroup() { return isGroup; }
    public void setIsGroup(Integer isGroup) { this.isGroup = isGroup; }
    public Long getLastMessageTime() { return lastMessageTime; }
    public void setLastMessageTime(Long lastMessageTime) { this.lastMessageTime = lastMessageTime; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
}

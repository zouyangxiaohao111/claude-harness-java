package com.nexusai.repository.im_message.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * {@code wechat_message}（V301）只读投影 · 供 IM 消息拉取层读本机微信记录。
 *
 * <h2>为什么单独建一个实体（而不是复用 wechatlog 的写法）</h2>
 * ① {@code com.nexusai.wechatlog} 是<b>写入方</b>（同步任务），本仓其它会话正在改它；
 * 读侧另建一个只读投影 ⇒ 不动别人的包，也不开这张表的写口（保持「只读 mapper」惯例）。
 * ② 微信的 {@code msg_time} 是 <b>epoch 秒</b>（不是 ISO 文本），与 {@code im_message.sent_at}
 *    的形态不同 ⇒ 需要一个专门的行类型来承接，再由拉取层归一化。
 *
 * <h2>⚠️ {@code id} 列不能当 messageId 用</h2>
 * {@code WeChatMessageIndexer} 写入时 {@code id = UUID.randomUUID()} ⇒ <b>每次重新同步都是新的</b>
 * ⇒ 拿它当幂等键会导致「同一条微信消息每次同步都插一条新记录」（静默重复）。
 * ⇒ 幂等键必须用<b>稳定</b>的 {@code (account_wxid, conversation_id, server_id, sort_seq)}。
 */
@Table("wechat_message")
public class WechatMessageRow {

    @Id private String id;

    /** 账号 wxid（一台机器可能有多个账号目录）。 */
    private String accountWxid;
    /** 会话标识（单聊对方 wxid / 群 xxx@chatroom）。 */
    private String conversationId;
    /** 微信自己的消息 id（server_id）。 */
    private Long serverId;
    /** 排序序号（10 位时间戳 + 3 位序号）。 */
    private Long sortSeq;
    /** 消息时间（Unix 秒）。 */
    private Long msgTime;
    /** 发送者 wxid。 */
    private String sender;
    /** 归一化类型（1 文本 / 3 图片 / 34 语音 / 49 应用消息 / 10000 系统 …）。 */
    private Integer msgType;
    /** 给人看的一小段（截断）。 */
    private String contentPreview;
    /** 是否本人发送（1 = 是）。 */
    private Integer isSelf;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getAccountWxid() { return accountWxid; }
    public void setAccountWxid(String v) { this.accountWxid = v; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String v) { this.conversationId = v; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long v) { this.serverId = v; }
    public Long getSortSeq() { return sortSeq; }
    public void setSortSeq(Long v) { this.sortSeq = v; }
    public Long getMsgTime() { return msgTime; }
    public void setMsgTime(Long v) { this.msgTime = v; }
    public String getSender() { return sender; }
    public void setSender(String v) { this.sender = v; }
    public Integer getMsgType() { return msgType; }
    public void setMsgType(Integer v) { this.msgType = v; }
    public String getContentPreview() { return contentPreview; }
    public void setContentPreview(String v) { this.contentPreview = v; }
    public Integer getIsSelf() { return isSelf; }
    public void setIsSelf(Integer v) { this.isSelf = v; }
}

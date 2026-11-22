package com.nexusai.wechatlog.model;

/**
 * 一条微信消息（已归一化）。
 *
 * <h2>字段来源（微信 4.x）</h2>
 * <table>
 *   <tr><th>字段</th><th>来源</th></tr>
 *   <tr><td>{@code conversationId}</td><td>{@code Msg_<md5(会话)>} 表名反推的会话 wxid</td></tr>
 *   <tr><td>{@code createTime}</td><td>{@code create_time}（10 位秒级时间戳）</td></tr>
 *   <tr><td>{@code sender}</td><td>{@code Name2Id.user_name}（join {@code real_sender_id}）</td></tr>
 *   <tr><td>{@code type}</td><td>{@code local_type & 0xFFFFFFFF}（高位是子类型标记，必须掩掉）</td></tr>
 *   <tr><td>{@code content}</td><td>{@code message_content}（可能 zstd 压缩）</td></tr>
 * </table>
 *
 * <h2>⚠️ {@code local_type} 的高位问题</h2>
 * 上游（rmqg 的 how-it-works）明确写着：<i>「{@code local_type} 的高位打包了子类型 ——
 * 真实类型是 {@code local_type & 0xFFFFFFFF}」</i>。如果不掩掉高位，判断「是不是文本(1)」
 * 会永远失败，表现为「一条消息都读不出来」。
 *
 * @param conversationId 会话 id（单聊是对方 wxid，群聊是 {@code xxx@chatroom}）
 * @param serverId       服务端消息 id（可用于去重与关联语音）
 * @param sortSeq        排序序号（10 位时间戳 + 3 位序号）
 * @param createTime     创建时间（Unix 秒）
 * @param sender         发送者 wxid（群聊里可区分是谁说的）
 * @param type           归一化后的消息类型（见 {@code WeChatMessageType}）
 * @param content        可读文本内容（已解压、已剥发送者前缀）
 * @param rawContent     原始载荷（XML / 压缩前字节），便于后续按需解析卡片等
 * @param isSelf         是否本人发送
 */
public record WeChatMessage(
        String conversationId,
        long serverId,
        long sortSeq,
        long createTime,
        String sender,
        int type,
        String content,
        String rawContent,
        boolean isSelf) {

    /** 是否群聊消息。 */
    public boolean isGroup() {
        return conversationId != null && conversationId.endsWith("@chatroom");
    }
}

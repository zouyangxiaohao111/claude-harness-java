package com.nexusai.domain.im_channel.catalog;

/**
 * 平台**实时**拉回来的一个会话候选（尚未落库）。
 *
 * <h2>为什么不直接复用 {@code ImChatEntry}</h2>
 * {@code ImChatEntry} 是**本地目录**的行（有内部 id、接线信息、来源），
 * 而本类是**平台侧的事实**（只有 peer 标识、名字、类型、最后消息时间）。
 * 两者职责不同：候选落库后才变成目录行。分开可以避免"平台返回的 null 字段把库里已有值冲掉"。
 *
 * @param peerId        平台侧会话标识（企微：加密 chat_id；飞书：chat_id；微信：wxid）
 * @param peerName      会话名（平台给才有）
 * @param chatKind      {@code p2p} | {@code group}
 * @param lastMessageAt 最后消息时间（ISO-8601；平台给才有）
 */
public record ImChatCandidate(
        String peerId,
        String peerName,
        String chatKind,
        String lastMessageAt) {
}

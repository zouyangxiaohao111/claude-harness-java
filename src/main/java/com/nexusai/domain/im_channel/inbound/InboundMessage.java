package com.nexusai.domain.im_channel.inbound;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 统一入站消息（三平台归一后）。
 *
 * <h2>为什么先归一</h2>
 * 三个平台的入站帧形状完全不同（企微 WS 帧 / 微信 {@code msgs[]} / 飞书 event），
 * 但**下游只关心五件事**：谁发的、发到哪个会话、说了什么、带没带文件、什么时候。
 * 把差异关在各自的 Listener 里，Router 与 agent 永远只看到这一种形状。
 *
 * <h2>为什么保留 {@code raw}</h2>
 * 排障时「平台到底发了什么」是唯一可信的事实。归一字段丢了信息（比如企微的
 * {@code req_id} 回复必须原样带回、飞书的 {@code message_id} 撤回要用），
 * 所以原始帧必须一起带下来 —— ⛔ 但**不要**把它写进日志或落库（可能含敏感内容）。
 *
 * @param instanceId 渠道实例 id（哪个 bot 收到的）
 * @param platform   wechat | feishu | wecom | dingtalk
 * @param peerId     会话 id（群聊=群 id / 私聊=对端 id）—— 与 {@code im_chat.peer_id} 同口径
 * @param peerName   会话名（群名 / 对方昵称，平台给才有）
 * @param senderId   发送者 id（群里≠peerId）
 * @param senderName 发送者显示名
 * @param chatKind   p2p | group
 * @param messageId  平台消息 id（可空；微信 iLink 的入站没有稳定 id）
 * @param text       纯文本内容；非文本消息（图片/文件）为 null 或空
 * @param media      媒体引用（图片/文件的平台描述）；无媒体时为空列表
 * @param timestampMs 平台消息时间（epoch 毫秒）；平台没给则取收到时间
 * @param raw        平台原始帧（⛔ 不进日志、不落库）
 */
public record InboundMessage(
        String instanceId,
        String platform,
        String peerId,
        String peerName,
        String senderId,
        String senderName,
        String chatKind,
        String messageId,
        String text,
        List<Media> media,
        long timestampMs,
        JsonNode raw) {

    public InboundMessage {
        media = media == null ? List.of() : List.copyOf(media);
    }

    /** ⛔ 不打印 raw（可能含敏感内容）。 */
    @Override
    public String toString() {
        return "InboundMessage[platform=" + platform
                + ", instance=" + instanceId
                + ", peer=" + peerId
                + ", sender=" + senderId
                + ", textLen=" + (text == null ? 0 : text.length())
                + ", media=" + media.size()
                + "]";
    }

    /**
     * 媒体引用。
     *
     * <p>为什么不直接给 URL：三个平台拿到的东西不一样（企微是 {@code media_id}、
     * 飞书是 {@code file_key}、微信是 CDN 下载参数），下载器各自解释。
     *
     * @param kind     IMAGE | FILE
     * @param fileName 文件名（平台给才有）
     * @param ref      平台侧引用（media_id / file_key / CDN 描述 JSON 字符串）
     */
    public record Media(String kind, String fileName, String ref) {}

    /** 是否有可读文本。 */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** 是否来自群聊。 */
    public boolean isGroup() {
        return "group".equalsIgnoreCase(chatKind);
    }
}

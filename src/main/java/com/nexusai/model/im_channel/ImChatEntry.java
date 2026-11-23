package com.nexusai.model.im_channel;

/**
 * IM 聊天目录条目（domain record）· 对应界面「IM 频道 › 任务」tab。
 *
 * <p><b>与 {@code ChannelBindingEntry} 的分层差异</b>（不要混用）：
 * <ul>
 *   <li>{@code ChannelBindingEntry}（V255）= MCP 通道归属，粒度是<b>渠道</b>
 *       （wechat / feishu），回答"消息投给哪个会话"；</li>
 *   <li>本 record（V305） = 产品层<b>聊天</b>目录，回答"这是哪个聊天、聊到哪了、接到哪个任务"。</li>
 * </ul>
 * 一个渠道下有多个聊天，所以渠道级绑定无法替代聊天级接线。
 *
 * @param chatKind  p2p | group
 * @param bindMode  auto（ChannelSend 首次发送自动接线）| manual
 */
public record ImChatEntry(
    String id,
    String instanceId,
    String platform,
    String peerId,
    String peerName,
    String chatKind,
    Integer memberCount,
    String lastMessagePreview,
    String lastMessageAt,
    String boundSessionId,
    String bindMode,
    String boundAt,
    String createdAt,
    String updatedAt,
    /**
     * V319 · 平台给的**昵称**（对方改过的名字）。
     *
     * <p>与 {@code peerName} 并存而不是替换：peerName 混着「群名 / 对方显示名 /
     * 退化成的平台 id」，是列表标题语义；这个只存平台明确给出的昵称。
     * <p>可空：企微靠 contact search 补、飞书靠通讯录 scope、**微信 iLink 永远拿不到**。
     * <p>展示请走 {@link #displayName()}（昵称 → peerName → peerId 依次回退）。
     */
    String nickName,
    /**
     * V335 · 机器人是否在该会话里（入站可达性）。
     *
     * <p>{@code null} = 尚未判定 ⇒ 读侧按「可达」处理；
     * {@code false} = 确认机器人不在该群（飞书「本人所在群」同步进来的那些）。
     * <p>p2p 与微信恒为 {@code true}（单聊不进 bot 群列表；微信登录账号即机器人）。
     */
    Boolean botPresent
) {
    /** 旧 15 参签名：botPresent 缺省为 null（未判定 ⇒ 按可达处理）。 */
    public ImChatEntry(String id, String instanceId, String platform, String peerId, String peerName,
                       String chatKind, Integer memberCount, String lastMessagePreview,
                       String lastMessageAt, String boundSessionId, String bindMode, String boundAt,
                       String createdAt, String updatedAt, String nickName) {
        this(id, instanceId, platform, peerId, peerName, chatKind, memberCount, lastMessagePreview,
            lastMessageAt, boundSessionId, bindMode, boundAt, createdAt, updatedAt, nickName, null);
    }

    /** 旧 14 参签名：nickName 缺省为 null（读侧回退 peerName）。 */
    public ImChatEntry(String id, String instanceId, String platform, String peerId, String peerName,
                       String chatKind, Integer memberCount, String lastMessagePreview,
                       String lastMessageAt, String boundSessionId, String bindMode, String boundAt,
                       String createdAt, String updatedAt) {
        this(id, instanceId, platform, peerId, peerName, chatKind, memberCount, lastMessagePreview,
            lastMessageAt, boundSessionId, bindMode, boundAt, createdAt, updatedAt, null, null);
    }

    /**
     * 给人看的名字：昵称 → peerName → peerId。
     *
     * <p>为什么要三级回退：微信拿不到昵称、飞书可能因为没有通讯录权限也拿不到，
     * 这时退到 peerName（可能已有群名），再退到 id —— **永不为空**。
     */
    public String displayName() {
        if (nickName != null && !nickName.isBlank()) return nickName;
        if (peerName != null && !peerName.isBlank()) return peerName;
        return peerId;
    }

    /** 是否已接线到某个任务。 */
    public boolean bound() {
        return boundSessionId != null && !boundSessionId.isBlank();
    }

    /**
     * 是否在列表里默认可见。
     *
     * <p>规则：可达（{@code botPresent == null} 视为可达）**或**已接线。
     * 已接线但机器人被移出群的会话必须仍然可见 —— 否则用户看不到、也没法解绑。
     */
    public boolean visibleInList() {
        return botPresent == null || botPresent || bound();
    }
}

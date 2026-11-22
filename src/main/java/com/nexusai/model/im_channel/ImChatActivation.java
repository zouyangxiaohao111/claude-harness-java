package com.nexusai.model.im_channel;

/**
 * 某个聊天能否**主动发消息**的状态（只读、非敏感）。
 *
 * <h2>为什么需要它</h2>
 * 微信 iLink 主动发消息必须带对方上次来信里的 {@code context_token}，而这个 token
 * 只能从入站消息拿到 ⇒ <b>没先给机器人发过消息的人，通知发不出去</b>（策略层返回
 * {@code SKIPPED / IM_REPLY_TOKEN_MISSING}）。用户看不到这个前提就会以为「绑好了就能收通知」，
 * 于是绑定/开无人值守之后静默失效。本 record 只回答一个问题：
 * <b>这个聊天现在能不能主动发？</b>
 *
 * <p>⛔ 只有一个布尔 —— <b>绝不</b>把 {@code reply_token} 本身放进来（那是能让别人冒充我们
 * 发消息的凭证，不能随 DTO 序列化到前端/日志）。
 *
 * @param chatId             聊天目录 id（前端按它对齐列表项）
 * @param platform           wechat / feishu / wecom / dingtalk
 * @param instanceId         渠道实例 id
 * @param activationRequired 该平台是否要求「对方先说话」才能主动发（目前只有微信 true）
 * @param activated          当前是否已具备主动发条件（微信 = 已有可用 context_token）
 * @param hint               给用户看的说明；无话可说（已激活 / 平台无此约束）⇒ null
 */
public record ImChatActivation(
    String chatId,
    String platform,
    String instanceId,
    boolean activationRequired,
    boolean activated,
    String hint
) {
    /** 微信未激活时给用户的一句话（前端可直接展示，不二次拼装）。 */
    public static final String WECHAT_INACTIVE_HINT = "微信渠道需先发一条消息激活，否则通知发不出去";

    /** 是否「需要激活但还没激活」——前端据此标渠道项 + 绑定后提示。 */
    public boolean blockedByActivation() {
        return activationRequired && !activated;
    }
}

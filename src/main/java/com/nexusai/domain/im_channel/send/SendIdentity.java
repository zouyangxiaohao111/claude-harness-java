package com.nexusai.domain.im_channel.send;

/**
 * 这条消息以**谁的名义**发出去。
 *
 * <h2>为什么这是一个显式维度，而不是"能发就行"</h2>
 * 企微的两个发送端点身份完全不同（官方 schema 逐字）：
 * <ul>
 *   <li>{@code /cli/message/aibot/send} ——「<b>以智能机器人身份</b>向指定单聊/群聊会话发送消息」</li>
 *   <li>{@code /cli/message/send} —— 以<b>授权真人</b>身份发（对方看到的发送者是本人）</li>
 * </ul>
 * 两者对<b>第三方</b>的社会后果完全不同：一个是「机器人通知我」，另一个是「某人本人在跟我说话」。
 * 所以身份必须由调用方**显式声明**，⛔ 绝不由代码按场景猜测（猜错 = AI 冒充真人对别人说话）。
 *
 * <h2>能力差异（决定能不能选这个身份）</h2>
 * <ul>
 *   <li>{@link #USER}：企微 {@code message/send} 的 {@code msg_type} enum <b>只有 text</b>
 *       ⇒ 以用户名义<b>发不了媒体</b>；飞书/微信/钉钉根本没有用户身份端点。</li>
 *   <li>{@link #BOT}：所有平台都支持，媒体也支持。</li>
 * </ul>
 * 平台不支持时由策略层 {@code canSendAsUser()} 声明，模板方法据此**显式失败或降级**，
 * 并把实际使用的身份写进 {@link ImSendResult#identity()} —— 让 AI 知道"这条到底是谁说的"。
 */
public enum SendIdentity {

    /** 以机器人身份（默认）。用户在 IM 里看到的是机器人发的。 */
    BOT,

    /** 以授权真人的身份（需要平台支持 + 用户显式授权）。对方看到的是"你本人"发的。 */
    USER;

    public boolean isUser() {
        return this == USER;
    }
}

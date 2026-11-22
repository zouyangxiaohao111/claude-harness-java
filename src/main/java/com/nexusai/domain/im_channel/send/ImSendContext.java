package com.nexusai.domain.im_channel.send;

/**
 * 模板方法交给策略的**只读上下文**（策略不查库、不碰 Service，只拿这些值）。
 *
 * <h2>为什么把凭证放进来而不是让策略自己去取</h2>
 * 解密凭证是**单一入口**（{@code ImChannelService.credentialOf}）。若每个策略自己取，
 * 就会出现 3 处解密调用，将来改成密钥轮换要改 3 个地方，且很难保证"凭证不进日志"。
 *
 * <p>⛔ {@code credential} 与 {@code replyToken} 是敏感值：
 * 不得进入任何 DTO / 日志 / 异常消息；本类刻意**不覆写 toString()**（避免误打出）。
 *
 * @param instanceId 渠道实例 id（日志定位用）
 * @param platform   wechat | feishu | wecom | dingtalk
 * @param peerId     平台侧聊天标识（wxid / oc_xxx / chatid）
 * @param chatKind   p2p | group
 * @param credential 解密后的根凭证 JSON（格式由各平台定义）
 * @param replyToken 入站带来的会话令牌；只有微信需要，其余为 null
 * @param identity   以谁的名义发（{@link SendIdentity}）；默认 {@code BOT}
 */
public record ImSendContext(
        String instanceId,
        String platform,
        String peerId,
        String chatKind,
        String credential,
        String replyToken,
        SendIdentity identity) {

    /**
     * 兼容构造器：老调用点（6 参）默认按<b>机器人身份</b>发。
     *
     * <p>为什么保留：机器人身份是默认值，绝大多数调用点（通知/扫描结果）本来就该是它；
     * 让"以用户名义"成为必须显式写出来的动作，可以避免误用。
     */
    public ImSendContext(String instanceId, String platform, String peerId, String chatKind,
                         String credential, String replyToken) {
        this(instanceId, platform, peerId, chatKind, credential, replyToken, SendIdentity.BOT);
    }

    public ImSendContext {
        // 构造即校验：所有字段都是发消息的硬前提，缺一个就没法发。
        // ⛔ 这里抛的是 IllegalArgumentException（编程错误），不是业务失败——
        //    真正的"聊天不存在"在 Service 层就已拦掉，走到这里说明调用方写错了。
        require(instanceId, "instanceId");
        require(platform, "platform");
        require(peerId, "peerId");
        require(credential, "credential");
        // 不传 = BOT（默认身份），避免 NPE 让"没写"变成"发不出去"
        if (identity == null) {
            identity = SendIdentity.BOT;
        }
    }

    /** 是否群聊目标。 */
    public boolean isGroup() {
        return "group".equalsIgnoreCase(chatKind);
    }

    /** 是否以**用户本人**名义发送。 */
    public boolean asUser() {
        return identity != null && identity.isUser();
    }

    /** 换身份（返回副本；调用方不应改已有上下文）。 */
    public ImSendContext withIdentity(SendIdentity id) {
        return new ImSendContext(instanceId, platform, peerId, chatKind, credential, replyToken, id);
    }

    /** 是否有可用的入站令牌（微信主动发的前提）。 */
    public boolean hasReplyToken() {
        return replyToken != null && !replyToken.isBlank();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空（发送需要它定位目标）");
        }
    }
}

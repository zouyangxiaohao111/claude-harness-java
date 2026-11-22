package com.nexusai.domain.im_channel.send;

/**
 * 一个平台的发送能力声明（**不发请求**就能回答的静态事实）。
 *
 * <h2>为什么把能力单独建模</h2>
 * 模板方法在发请求**之前**用它判定「该不该发」。这带来两个好处：
 * <ol>
 *   <li><b>不会假装成功</b>：钉钉没有主动发送通道，就不去调一个注定失败的接口；</li>
 *   <li><b>不会发坏消息</b>：微信缺 {@code context_token} 时会发出**空白消息**（PIT-WX-008），
 *       必须在发之前拦住。</li>
 * </ol>
 * 若把这些判断散落在各 {@code doSend} 里，新增平台时很容易漏掉一条。
 *
 * @param canSendProactively 能否在无入站上下文时主动发；false ⇒ 该平台只支持「回复」
 * @param needsReplyToken    是否依赖入站带来的会话令牌（微信 = true）
 * @param supportsGroup      是否支持群聊目标
 * @param maxTextChars       单条文本上限（超出由模板截断并标注）；不支持发送时可为 0
 * @param unsupportedReason  {@code canSendProactively=false} 时必填，给人看的原因
 */
public record SendCapability(
        boolean canSendProactively,
        boolean needsReplyToken,
        boolean supportsGroup,
        int maxTextChars,
        String unsupportedReason) {

    /** 常规可主动发送的平台。 */
    public static SendCapability proactive(int maxTextChars, boolean supportsGroup) {
        return new SendCapability(true, false, supportsGroup, maxTextChars, null);
    }

    /** 需要入站令牌才能发的平台（微信）。 */
    public static SendCapability needsReplyToken(int maxTextChars, boolean supportsGroup) {
        return new SendCapability(true, true, supportsGroup, maxTextChars, null);
    }

    /** 完全没有主动发送通道的平台（钉钉）。{@code reason} 必填 —— 见类注释。 */
    public static SendCapability unsupported(String reason) {
        // ⛔ 不做兜底：没有原因的能力声明等于没声明，调用方只会看到"没发出去"而不知道下一步。
        //    constructor 会在这里抛 IllegalArgumentException，把问题暴露在开发期。
        return new SendCapability(false, false, false, 0, reason);
    }

    /** 自检：声明不支持却漏写原因 = 调用方会拿到一句无信息量的话。 */
    public SendCapability {
        if (!canSendProactively && (unsupportedReason == null || unsupportedReason.isBlank())) {
            throw new IllegalArgumentException("canSendProactively=false 时必须给 unsupportedReason");
        }
        if (canSendProactively && maxTextChars <= 0) {
            throw new IllegalArgumentException("可发送平台的 maxTextChars 必须 > 0");
        }
    }
}

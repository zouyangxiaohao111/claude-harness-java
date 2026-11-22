package com.nexusai.domain.im_channel.send;

/**
 * 「把一条消息发到这个平台的这个聊天」的策略接口（每平台一个实现）。
 *
 * <h2>职责边界（很重要）</h2>
 * 策略<b>只负责协议</b>：把 {@link ImSendContext} + 文本变成一次平台 HTTP 调用，并解析响应。
 * <b>不</b>负责：校验输入、查聊天目录、解密凭证、更新实例状态、决定要不要发。
 * 那些是 {@link AbstractImSendStrategy}（模板方法）的事。
 *
 * <p>这样切的好处：新增平台时只需要写"这个平台怎么发"，不会因为漏了某条通用校验
 * 而出现行为不一致（例如某个平台忘了拦空文本）。
 *
 * <h2>能力声明</h2>
 * {@link #capability()} 必须是**静态事实**（不查库、不发请求）。模板方法用它做发前判定。
 */
public interface ImSendStrategy {

    /** 平台标识，取值同 {@code ImChannelService.PLATFORMS}。 */
    String platform();

    /** 本平台的发送能力（静态声明）。 */
    SendCapability capability();

    /**
     * 本平台能否**以用户本人名义**发送（静态声明，默认 false）。
     *
     * <p>为什么默认 false：绝大多数平台只有机器人身份端点（飞书 tenant token / 微信 bot /
     * 钉钉），"能发"不等于"能以用户名义发"。子类显式覆写为 true 才能被调用方选中。
     */
    default boolean canSendAsUser() {
        return false;
    }

    /**
     * 发送（校验 / 能力检查 / 文本归一 / 异常归一都在实现里完成）。
     *
     * <p>为什么把 {@code send} 放在接口上、而不让调用方强转成
     * {@code AbstractImSendStrategy}：调用方只拿到 {@code ImSendStrategy}，
     * 强转就把「模板方法」的实现细节泄漏到了调用方，将来加一个不继承模板的实现就会 ClassCastException。
     * 放接口上，任何策略（包括兜底的 Unsupported）都能被统一调用。
     *
     * <p>⛔ 实现必须保证**不抛异常**、**不返回 null**：批量投递里一个目标失败不能中断整批。
     */
    ImSendResult send(ImSendContext ctx, String text);

    /**
     * 带附件的发送（文本可空 —— 只发文件是合法的）。
     *
     * <p>流程比纯文本多一步：**先把附件上传到平台**（拿 image_key / media_id / CDN 描述），
     * 再带着这些引用调发送。上传与发送是两次独立往返，所以失败语义分开：
     * 单个附件传失败**不影响**正文发出（结果里用 {@link ImSendResult#attachmentErrors()} 单独列）。
     *
     * <p>⛔ 实现必须保证**不抛异常**、**不返回 null** —— 理由同 {@link #send(ImSendContext, String)}。
     */
    ImSendResult send(ImSendContext ctx, String text, java.util.List<ImSendAttachment> attachments);

    /**
     * 真正发送（模板方法已做完校验 / 能力检查 / 文本归一）。
     *
     * @param ctx  只读上下文（含已解密的凭证）
     * @param text 已按 {@link SendCapability#maxTextChars()} 归一过的文本，保证非空
     * @return 平台消息 id；平台不返回 id 时可返回 null（如企微）
     * @throws ImSendException 平台拒绝或网络失败；{@code message} 中不得含凭证
     */
    String doSend(ImSendContext ctx, String text) throws ImSendException;
}

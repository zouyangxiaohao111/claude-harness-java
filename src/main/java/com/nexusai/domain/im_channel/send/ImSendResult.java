package com.nexusai.domain.im_channel.send;

import java.util.List;

/**
 * 一次投递的结果（每个目标一条）。
 *
 * <h2>为什么用 status 三态而不是 boolean</h2>
 * 「跳过」和「失败」对用户的意义完全不同：
 * <ul>
 *   <li>{@code SENT}   —— 平台接受了，消息真的发出去了；</li>
 *   <li>{@code SKIPPED}—— 我们**主动没发**（平台不支持 / 缺会话令牌）。这不是错误，
 *       而是能力边界。用户在微信里说一句话就能让下一次变 SENT；</li>
 *   <li>{@code FAILED} —— 尝试发了但平台拒绝 / 网络失败。</li>
 * </ul>
 * 用 boolean 会把「跳过」和「失败」混成一类，前端只能显示一个红叉，用户无从判断该怎么办。
 *
 * <p>⛔ 约定：{@code SKIPPED} 时**一定**有 {@code reason}（给人看的下一步动作），
 * 否则 UI 只能显示"没发出去"而不知道该干什么。
 */
public record ImSendResult(
        Status status,
        String messageId,
        String errorCode,
        String reason,
        /** 附件级失败（文本已发出、但某些附件没发出去）；空 = 全部成功或无附件。 */
        List<String> attachmentErrors,
        /**
         * 这条消息**实际以谁的名义**发出。
         *
         * <p>为什么必须回传：调用方请求 {@code USER} 时平台可能不支持 ⇒ 降级为 {@code BOT}。
         * 如果不告诉调用方，AI 会以为"我说的话是用户本人说的"，而对方看到的其实是机器人 ——
         * 这种静默降级比直接失败更危险（用户会背一个自己没说过的话）。
         */
        SendIdentity identity,
        /** 身份相关说明（如「附件以机器人身份发出」「平台不支持用户身份，已降级」）；无则 null。 */
        String identityNote) {

    public enum Status { SENT, SKIPPED, FAILED }

    public ImSendResult {
        attachmentErrors = attachmentErrors == null ? List.of() : List.copyOf(attachmentErrors);
        if (identity == null) {
            identity = SendIdentity.BOT;
        }
    }

    /** 兼容构造器（老调用点默认 BOT 身份、无说明）。 */
    public ImSendResult(Status status, String messageId, String errorCode, String reason,
                        List<String> attachmentErrors) {
        this(status, messageId, errorCode, reason, attachmentErrors, SendIdentity.BOT, null);
    }

    /** 平台接受，消息已发出（企微可能没有 messageId）。 */
    public static ImSendResult sent(String messageId) {
        return new ImSendResult(Status.SENT, messageId, null, null, List.of());
    }

    /**
     * 文本发出去了，但有附件没上传成功。
     *
     * <p>为什么仍然算 {@code SENT}：收信人已经看到正文了，把整体判成失败会让调用方以为
     * 「一条都没发出去」而重发 ⇒ 对方收到重复正文。附件失败单独列出来给人看。
     */
    public static ImSendResult sent(String messageId, List<String> attachmentErrors) {
        return new ImSendResult(Status.SENT, messageId, null, null, attachmentErrors);
    }

    /** 主动未发（能力边界），{@code reason} 必填。 */
    public static ImSendResult skipped(String errorCode, String reason) {
        return new ImSendResult(Status.SKIPPED, null, errorCode,
                reason == null || reason.isBlank() ? "该平台当前无法主动发送" : reason, List.of());
    }

    /** 尝试失败，{@code reason} 必填。 */
    public static ImSendResult failed(String errorCode, String reason) {
        return new ImSendResult(Status.FAILED, null, errorCode,
                reason == null || reason.isBlank() ? "平台拒绝发送" : reason, List.of());
    }

    /** 带身份信息的成功结果（模板方法在降级/混合身份时用）。 */
    public static ImSendResult sent(String messageId, List<String> attachmentErrors,
                                    SendIdentity identity, String identityNote) {
        return new ImSendResult(Status.SENT, messageId, null, null, attachmentErrors, identity, identityNote);
    }

    /** 是否真的发出去了（{@code SKIPPED} 与 {@code FAILED} 都算没发）。 */
    public boolean ok() {
        return status == Status.SENT;
    }

    /** 是否全部成功（发送成功且附件也都没问题）。 */
    public boolean fullyOk() {
        return status == Status.SENT && attachmentErrors.isEmpty();
    }

    /** HTTP 语义：成功发送或主动跳过都算「可接受」，只有 FAILED 需要标红。 */
    public boolean acceptable() {
        return status != Status.FAILED;
    }
}

package com.nexusai.domain.im_channel.send;

/**
 * 发送失败（策略层唯一允许抛出的异常）。
 *
 * <h2>为什么不让策略抛受检异常或 RuntimeException</h2>
 * 模板方法需要把「平台拒绝」「网络失败」「编程错误」分开处理：
 * <ul>
 *   <li>平台拒绝 / 网络失败 → 归一成 {@link ImSendResult#failed}（业务结果，不崩）</li>
 *   <li>编程错误（空 peerId 之类）→ 在 {@link ImSendContext} 构造时就抛 {@code IllegalArgumentException}，
 *       属于 bug，不该被当成"发送失败"吞掉</li>
 * </ul>
 * 所以本异常只承载**前两类**，并带上平台原始码供排查。
 *
 * <p>⛔ {@code message} 里**不得**包含凭证 / token（各策略构造时自己保证）。
 */
public class ImSendException extends Exception {

    /** 统一错误码（取值见 error-catalog：IM_SEND_*）。 */
    private final String code;

    /** 平台原始错误码（企微 errcode / 飞书 code / 微信 ret），无则为 null。 */
    private final String platformCode;

    /** 是否值得重试（网络超时 = true；参数错误 = false）。 */
    private final boolean retryable;

    public ImSendException(String code, String message) {
        this(code, message, null, false);
    }

    public ImSendException(String code, String message, String platformCode, boolean retryable) {
        super(message);
        this.code = code == null || code.isBlank() ? "IM_SEND_PLATFORM_ERROR" : code;
        this.platformCode = platformCode;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public String platformCode() {
        return platformCode;
    }

    public boolean retryable() {
        return retryable;
    }
}

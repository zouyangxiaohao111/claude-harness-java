package com.nexusai.domain.im_message.fetch;

/**
 * 拉取消息失败（平台拒绝 / 网络故障 / 凭证问题 / 参数非法）。
 *
 * <p>与 {@code ImSendException} 分开：发送失败是「业务结果」，拉取失败是「这次没拿到数据」，
 * 两者的处置完全不同（发送失败要回传给用户；拉取失败应当<b>降级为只用本地记录</b>并说明原因，
 * 而不是让整个读消息工具失败）。
 *
 * <p>⛔ message 里不得包含 token / secret —— 各实现自己保证（沿用本仓凭证安全惯例）。
 */
public class ImMessageFetchException extends Exception {

    /**
     * 统一错误码：<b>会话 id 形态不对，平台压根不认这个 chat_id</b>。
     *
     * <p>⛔ 它<b>不是</b>「平台挂了」：企微回 {@code 40073 非法的 'chat_id'} 的真实原因是
     * 「这个 id 压根不是 chat_id」—— 入站事件带的是<b>明文 from.userid</b>（只能收、不能拉），
     * 或者库里残留着历史测试数据。
     * <p>⇒ 上层要能据此给用户一句可执行的话（「去把它清理掉或换成正确的 id」），
     * 而不是笼统的「拉取失败」；同时也<b>不能静默吞掉</b>（用户得知道有会话拉不了）。
     */
    public static final String CODE_INVALID_CHAT_ID = "IM_FETCH_INVALID_CHAT_ID";

    /** 统一错误码（见本仓 error-catalog 的 {@code IM_FETCH_*}）。 */
    private final String code;
    /** 平台原始错误码（企微 errcode / 飞书 code），无则 null。 */
    private final String platformCode;
    /** 是否值得重试（网络超时 = true；参数/权限错误 = false）。 */
    private final boolean retryable;

    public ImMessageFetchException(String code, String message) {
        this(code, message, null, false);
    }

    public ImMessageFetchException(String code, String message, String platformCode, boolean retryable) {
        super(message);
        this.code = (code == null || code.isBlank()) ? "IM_FETCH_PLATFORM_ERROR" : code;
        this.platformCode = platformCode;
        this.retryable = retryable;
    }

    public ImMessageFetchException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = (code == null || code.isBlank()) ? "IM_FETCH_PLATFORM_ERROR" : code;
        this.platformCode = null;
        this.retryable = true;
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

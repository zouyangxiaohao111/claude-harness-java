package com.nexusai.infra.exception;

/**
 * 上游网关/第三方接口不可用 → 502。
 *
 * <p>WHY: 本后端代调外部插件市场（自建 MinIO / 静态 HTTP）或其它上游接口失败/非 200 时，
 * 应 fail-loud 返回 502（Bad Gateway）并附中文错误信息，绝不静默降级或让上游异常漏成 500
 * （对上游代理语义 502 更准确）。
 *
 * @see com.nexusai.apis.market.ExternalMarketController
 */
public class BadGatewayException extends RuntimeException {
    public BadGatewayException(String message) { super(message); }

    public BadGatewayException(String message, Throwable cause) { super(message, cause); }
}

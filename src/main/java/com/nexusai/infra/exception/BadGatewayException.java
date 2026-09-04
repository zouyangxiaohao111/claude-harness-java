package com.nexusai.infra.exception;

/**
 * 上游网关/第三方接口不可用 → 502。
 *
 * <p>WHY: 本后端作为「腾讯 workbuddy 技能市场」的服务器端代理，代调远端接口失败/非 200/401
 * （凭证过期或限流）时应 fail-loud 返回 502（Bad Gateway）并附中文错误信息，绝不静默降级
 * 或让远端异常漏成 500（对上游代理语义 502 更准确）。
 *
 * @see com.nexusai.apis.market.WorkbuddyMarketController
 */
public class BadGatewayException extends RuntimeException {
    public BadGatewayException(String message) { super(message); }

    public BadGatewayException(String message, Throwable cause) { super(message, cause); }
}

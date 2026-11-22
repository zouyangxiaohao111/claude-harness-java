package com.nexusai.domain.im_channel.qr;

import org.springframework.stereotype.Component;

/**
 * 钉钉接入。
 *
 * <p><b>没有扫码路径</b>（四家里唯一）：只能填凭证（AppKey / AppSecret / 机器人编码）。
 * 所以 {@link #supportsQr()} 返回 false —— 前端据此给钉钉显示凭证表单而不是二维码，
 * 而不是"给一个扫不了的空码"。
 *
 * <h2>接入时的实现要点</h2>
 * <ul>
 *   <li>两套 SDK，且<b>不能用 Jackson</b>（钉钉客户端的序列化有特殊约定）</li>
 *   <li>{@code conversationType} 是字符串 {@code "1"/"2"}，不是数字</li>
 * </ul>
 */
@Component
public class DingtalkQrStrategy implements QrProvisioningStrategy {

    @Override
    public String platform() {
        return "dingtalk";
    }

    @Override
    public boolean supportsQr() {
        return false;
    }

    @Override
    public QrSession start(String displayName) {
        return QrProvisioningStrategy.unsupported("钉钉", "钉钉只能填 AppKey/AppSecret，没有扫码授权路径");
    }

    @Override
    public QrPollResult poll(String sessionId) {
        return new QrPollResult(QrPollResult.State.UNSUPPORTED, null,
            "钉钉没有扫码流程，请直接用凭证表单接入");
    }
}

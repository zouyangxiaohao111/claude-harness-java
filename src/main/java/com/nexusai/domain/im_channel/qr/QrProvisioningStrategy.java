package com.nexusai.domain.im_channel.qr;

import java.util.Map;

/**
 * 「扫码接入一个渠道」的策略接口（每个平台一套协议，行为不同但对外同形）。
 *
 * <h2>为什么要分层</h2>
 * 四个平台的扫码语义完全不同，但调用方（IM 页「添加渠道」抽屉）只关心三件事：
 * <b>拿到码 → 等扫完 → 拿到凭证建实例</b>。把差异关在各自的实现里，
 * 上层永远不需要知道"微信是 iLink 轮询、企微是扫码即建机器人、飞书是 Device Code、钉钉没有"。
 *
 * <h2>为什么现在只有骨架</h2>
 * channel 接入还没做（用户裁定：先设计策略，接入时再填实现）。
 * 骨架里把每个平台的<b>真实协议要点</b>写在类注释上，接入时照着填即可，
 * 不会出现"当初为什么这样设计"的二次考古。
 */
public interface QrProvisioningStrategy {

    /** 本策略负责的平台（取值同 {@code ImChannelService.PLATFORMS}）。 */
    String platform();

    /** 该平台是否支持扫码接入（钉钉当前不支持 ⇒ {@link #start} 直接抛明确错误）。 */
    boolean supportsQr();

    /**
     * 发起一次扫码会话。
     *
     * @param displayName 用户给这个渠道起的名字（部分平台建机器人时要用）
     * @return 二维码内容（前端渲染成图）+ 用于轮询的会话 id
     */
    QrSession start(String displayName);

    /**
     * 查一次扫码状态。
     *
     * @param sessionId {@link #start} 返回的会话 id
     * @return 状态；{@code SCANNED} 时 {@link QrPollResult#credential()} 才有值
     */
    QrPollResult poll(String sessionId);

    /** 扫码成功后的凭证（交给 {@code ImChannelService.createInstance} 加密落库）。 */
    record Provisioned(String displayName, String credentialJson) {}

    /**
     * @param sessionId   轮询句柄（也用于 {@link #poll}）
     * @param qrContent   二维码内容（URL 或原始串）；{@code UNSUPPORTED} 时为 null
     * @param expiresAt   过期时间（ISO-8601）；无过期概念时为 null
     * @param hint        给用户看的一句话（例如"用企业微信扫，扫完会自动建机器人"）
     */
    record QrSession(String sessionId, String qrContent, String expiresAt, String hint) {}

    /**
     * @param state       PENDING | SCANNED | EXPIRED | UNSUPPORTED
     * @param provisioned state=SCANNED 时的凭证；其余为 null
     * @param message     给用户看的说明（失败原因 / 补充动作）
     */
    record QrPollResult(State state, Provisioned provisioned, String message) {
        public enum State { PENDING, SCANNED, EXPIRED, UNSUPPORTED }
    }

    /** 各实现的公共小工具：把"不支持"表达成明确结果，而不是静默返回空。 */
    static QrSession unsupported(String platform, String why) {
        return new QrSession(null, null, null, platform + " 不支持扫码接入：" + why);
    }

    /** 便于实现侧组装凭证 JSON（键名由各平台自己定）。 */
    static String toJson(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "\\\"")).append("\":\"")
              .append(e.getValue() == null ? "" : e.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
              .append('"');
        }
        return sb.append('}').toString();
    }
}

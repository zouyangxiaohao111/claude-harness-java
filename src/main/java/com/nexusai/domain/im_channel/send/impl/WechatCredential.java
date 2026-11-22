package com.nexusai.domain.im_channel.send.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.send.ImSendException;

import java.net.URI;

/**
 * 微信凭证解析（**发消息策略与媒体上传器共用一份**）。
 *
 * <h2>为什么单独抽出来</h2>
 * 凭证 JSON 的形状只有一处定义（扫码落地时写下的
 * {@code {"botToken":…,"ilinkBotId":…,"baseUrl":…,"ilinkUserId":…}}），
 * 但**两个类都要读它**：发消息策略要 {@code baseUrl}+{@code botToken}，
 * 媒体上传器也要同样的两项（上传接口没有 {@code context_token} 可用，
 * {@code botToken} 是它唯一的鉴权凭证）。
 * 两处各写一份解析 = 将来改默认基址 / 改校验规则时必然漏改一处 ⇒ 这里收敛成单一真源。
 *
 * <h2>解析失败一律抛 {@link ImSendException}</h2>
 * ⛔ 不让 Jackson 的异常裸奔出去：凭证是**配置**，坏了就是配置坏了，
 * 要给出人能懂的原因（"凭证不是合法 JSON"），而不是抛一个
 * {@code JsonParseException} 让上层看不懂。错误码统一 {@code IM_CREDENTIAL_INVALID}
 * （参数类错误，重试无意义）。
 *
 * <p>⛔ <b>敏感值</b>：{@code botToken} 是机器人级凭证，<b>绝不进日志、不进异常消息</b>。
 * 本记录刻意<b>不覆写 {@code toString()}</b> —— record 默认的 toString 会把全部字段打出来，
 * 一旦有人顺手 {@code log.debug("{}", cred)} 就泄漏了。
 */
record WechatCredential(String baseUrl, String botToken) {

    /**
     * 扫码接口没给 baseurl 时的缺省基址（与 {@code WechatQrStrategy} 一致）。
     *
     * <p>🔴 缺省值必须与扫码端点同源：{@code WechatQrStrategy} 用的是
     * {@code https://ilinkai.weixin.qq.com}，两边不一致会出现
     * "扫码成功但发不出消息"这种极难排查的现象。
     */
    static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 解析凭证 JSON。
     *
     * <p>🔴 {@code botToken} 缺失<b>不算错</b>：iLink 发消息靠 {@code context_token} 鉴权，
     * {@code bot_token} 只在长轮询收消息时才是必需的。所以这里返回 {@code null}，
     * 由<b>各自的使用方</b>决定它是不是硬前提（媒体上传器那边是硬前提）。
     *
     * @param credentialJson 解密后的凭证 JSON
     * @return 凭证（{@code botToken} 可能为 null）
     * @throws ImSendException 凭证为空 / 不是合法 JSON / 不是对象 / baseUrl 不合法
     */
    static WechatCredential parse(String credentialJson) throws ImSendException {
        if (credentialJson == null || credentialJson.isBlank()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "微信凭证为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(credentialJson);
        } catch (Exception e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "微信凭证不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "微信凭证不是 JSON 对象");
        }

        String baseUrl = text(root, "baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        baseUrl = stripTrailingSlash(baseUrl.trim());

        // 只做格式校验，不做连通性校验（那要发请求，不属于"解析凭证"）。
        // ⛔ 必须要求 http/https 绝对地址：URI.create("abc") 是合法的（相对 URI），
        // 但拼上端点后发请求会抛 IllegalArgumentException ⇒ 用户只会看到一句"内部错误"。
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "微信 baseUrl 不是合法 URI");
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ImSendException("IM_CREDENTIAL_INVALID", "微信 baseUrl 不是 http/https 绝对地址");
        }

        String botToken = text(root, "botToken");   // 可为 null
        return new WechatCredential(baseUrl, botToken);
    }

    /** 取字符串字段；缺失 / null ⇒ null（调用方决定缺省）。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s == null ? null : s;
    }

    /** 去掉结尾的 "/"（baseUrl 自带斜杠时会拼出 "//ilink/..."）。 */
    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? DEFAULT_BASE_URL : out;
    }
}

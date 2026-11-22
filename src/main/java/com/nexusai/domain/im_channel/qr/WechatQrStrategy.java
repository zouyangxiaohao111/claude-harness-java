package com.nexusai.domain.im_channel.qr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信（iLink Bot）扫码登录 —— 纯 HTTP，不依赖 CLI / SDK。
 *
 * <h2>协议来源</h2>
 * {@code github.com/CloudSen/wx-aibot-java-sdk}（Apache-2.0）的
 * {@code WeixinClient.startQrLogin / pollQrLoginStatus}：
 * <pre>
 * ① GET {base}/ilink/bot/get_bot_qrcode?bot_type=3
 *    → {qrcode, qrcode_img_content}
 * ② GET {base}/ilink/bot/get_qrcode_status?qrcode=…   （header iLink-App-ClientVersion: 1）
 *    → {status, bot_token, ilink_bot_id, baseurl, ilink_user_id}
 *    status = confirmed | expired | 其它（继续轮询）
 * </pre>
 * base = {@code https://ilinkai.weixin.qq.com}，bot_type 默认 {@code "3"}。
 *
 * <h2>⚠️ 两条硬约束</h2>
 * <ul>
 *   <li><b>每次扫码 = 建一个新 bot，旧 bot 立刻 -14</b> ⇒ 绝不能把重扫当重连手段（UI 已警示）</li>
 *   <li>{@code getupdates} 返回即消费（无 ack）⇒ 一个 bot 只能一个轮询者，游标必须落库
 *       （这是运行时收消息的事，与扫码无关，但同一张实例表要存 {@code last_cursor}）</li>
 * </ul>
 */
@Component
public class WechatQrStrategy implements QrProvisioningStrategy {

    private static final Logger log = LoggerFactory.getLogger(WechatQrStrategy.class);

    private static final String BASE = "https://ilinkai.weixin.qq.com";
    /** SDK 默认值；不同 bot 形态用不同数字，暂固定 3。 */
    private static final String BOT_TYPE = "3";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ObjectMapper json = new ObjectMapper();
    /** qrcode → 开始时间（仅用于提示过期；实际过期以服务端 status 为准）。 */
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    @Override
    public String platform() {
        return "wechat";
    }

    @Override
    public boolean supportsQr() {
        return true;
    }

    @Override
    public QrSession start(String displayName) {
        try {
            String url = BASE + "/ilink/bot/get_bot_qrcode?bot_type="
                + URLEncoder.encode(BOT_TYPE, StandardCharsets.UTF_8);
            JsonNode d = getJson(url, null);
            String qrcode = d.path("qrcode").asText("");
            String img = d.path("qrcode_img_content").asText("");
            if (qrcode.isEmpty()) {
                throw new IllegalStateException("微信未返回 qrcode: " + d);
            }
            startedAt.put(qrcode, System.currentTimeMillis());
            log.info("[WechatQr] 已生成扫码会话 qrcodeLen={} imgLen={}", qrcode.length(), img.length());
            return new QrSession(qrcode, img, null,
                "用微信扫一扫；扫完手机上确认登录");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("发起微信扫码失败：" + e.getMessage(), e);
        }
    }

    @Override
    public QrPollResult poll(String sessionId) {
        if (sessionId == null || !startedAt.containsKey(sessionId)) {
            return new QrPollResult(QrPollResult.State.EXPIRED, null, "扫码会话不存在，请重新扫码");
        }
        try {
            String url = BASE + "/ilink/bot/get_qrcode_status?qrcode="
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
            JsonNode d = getJson(url, Map.of("iLink-App-ClientVersion", "1"));
            String status = d.path("status").asText("");

            if ("confirmed".equalsIgnoreCase(status)) {
                String botToken = d.path("bot_token").asText("");
                String botId = d.path("ilink_bot_id").asText("");
                String baseUrl = d.path("baseurl").asText(BASE);
                String userId = d.path("ilink_user_id").asText("");
                if (botToken.isEmpty() || botId.isEmpty()) {
                    log.warn("[WechatQr] confirmed 但缺 bot_token/ilink_bot_id: {}", d);
                    return new QrPollResult(QrPollResult.State.EXPIRED, null,
                        "扫码成功但没拿到凭证，请重新扫码");
                }
                startedAt.remove(sessionId);
                Map<String, String> cred = new LinkedHashMap<>();
                cred.put("botToken", botToken);
                cred.put("ilinkBotId", botId);
                cred.put("baseUrl", baseUrl);
                if (!userId.isEmpty()) cred.put("ilinkUserId", userId);
                log.info("[WechatQr] 扫码完成 botId={} baseUrl={} tokenLen={}",
                    botId, baseUrl, botToken.length());
                return new QrPollResult(QrPollResult.State.SCANNED,
                    new Provisioned(null, QrProvisioningStrategy.toJson(cred)),
                    "已创建微信机器人 " + botId);
            }
            if ("expired".equalsIgnoreCase(status)) {
                startedAt.remove(sessionId);
                return new QrPollResult(QrPollResult.State.EXPIRED, null, "二维码已过期，请重新扫码");
            }
            return new QrPollResult(QrPollResult.State.PENDING, null, null);
        } catch (Exception e) {
            log.warn("[WechatQr] 轮询失败: {}", e.toString());
            return new QrPollResult(QrPollResult.State.PENDING, null, "轮询失败，稍后重试");
        }
    }

    private JsonNode getJson(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET();
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> r = HTTP.send(b.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return json.readTree(r.body());
    }
}

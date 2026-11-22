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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信扫码建机器人（官方 CLI {@code auth init} 的等价物，纯 HTTP）。
 *
 * <h2>协议来源</h2>
 * 不是文档、不是猜：官方 CLI 开源（{@code WecomTeam/wecom-cli}，Rust，MIT），
 * 实现全在 {@code crates/wecom-cli/src/auth/qrcode.rs}；下面三个端点均已 curl 实测。
 *
 * <h2>四个边界（都踩过）</h2>
 * <ul>
 *   <li>必须用浏览器打开 {@code auth_url} —— 展示页是 SPA，终端 ASCII 二维码扫不出来</li>
 *   <li>{@code scode} 就是轮询凭据，每次生成不同、旧的立刻作废 ⇒ 生成后要<b>马上</b>交给前端</li>
 *   <li>{@code secret} 只在 {@code bot_info} 里出现一次 ⇒ 必须立刻落库（加密）</li>
 *   <li>{@code status=success} 但缺字段要当失败 —— "接口成功 ≠ 能发消息"</li>
 * </ul>
 */
@Component
public class WecomQrStrategy implements QrProvisioningStrategy {

    private static final Logger log = LoggerFactory.getLogger(WecomQrStrategy.class);

    private static final String BASE = "https://work.weixin.qq.com/ai/qc";
    /** 官方 CLI 固定值。 */
    private static final String SOURCE = "wecom_cli_external";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ObjectMapper json = new ObjectMapper();
    /** scode → 会话（进程内即可：扫码是一次性、短时动作，重启后重扫）。 */
    private final Map<String, String> authUrlByScode = new ConcurrentHashMap<>();

    @Override
    public String platform() {
        return "wecom";
    }

    @Override
    public boolean supportsQr() {
        return true;
    }

    @Override
    public QrSession start(String displayName) {
        String url = BASE + "/generate?source=" + SOURCE + "&plat=" + platCode();
        try {
            JsonNode data = getJson(url).path("data");
            String scode = data.path("scode").asText("");
            String authUrl = data.path("auth_url").asText("");
            if (scode.isEmpty() || authUrl.isEmpty()) {
                throw new IllegalStateException("企微未返回 scode/auth_url: " + data);
            }
            authUrlByScode.put(scode, authUrl);
            log.info("[WecomQr] 已生成扫码会话 scode={} plat={}", scode, platCode());
            return new QrSession(scode, authUrl, null,
                "用企业微信扫码（或点下面的按钮在浏览器打开），扫完这里会自动继续");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("发起企微扫码失败：" + e.getMessage(), e);
        }
    }

    @Override
    public QrPollResult poll(String sessionId) {
        if (sessionId == null || !authUrlByScode.containsKey(sessionId)) {
            return new QrPollResult(QrPollResult.State.EXPIRED, null, "扫码会话不存在或已过期，请重新扫码");
        }
        try {
            String url = BASE + "/query_result?scode="
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
            JsonNode data = getJson(url).path("data");
            String status = data.path("status").asText("init");

            if ("success".equals(status)) {
                JsonNode bot = data.path("bot_info");
                String botId = bot.path("botid").asText("");
                String secret = bot.path("secret").asText("");
                // 缺字段必须当失败：secret 拿不到就等于没建成
                if (botId.isEmpty() || secret.isEmpty()) {
                    log.warn("[WecomQr] status=success 但 bot_info 缺字段: {}", data);
                    return new QrPollResult(QrPollResult.State.EXPIRED, null,
                        "扫码成功但没拿到凭证，请重新扫码");
                }
                authUrlByScode.remove(sessionId);
                Map<String, String> cred = new LinkedHashMap<>();
                cred.put("botId", botId);
                cred.put("botSecret", secret);
                log.info("[WecomQr] 扫码完成 scode={} botId={} secretLen={}",
                    sessionId, botId, secret.length());
                return new QrPollResult(QrPollResult.State.SCANNED,
                    new Provisioned(null, QrProvisioningStrategy.toJson(cred)), "已创建机器人");
            }
            // init / 其它中间态都当"还没扫完"
            return new QrPollResult(QrPollResult.State.PENDING, null, null);
        } catch (Exception e) {
            log.warn("[WecomQr] 轮询失败 scode={}: {}", sessionId, e.toString());
            return new QrPollResult(QrPollResult.State.PENDING, null, "轮询失败，稍后重试");
        }
    }

    /** 官方 {@code get_plat_code()}：macOS=1 · Windows=2 · Linux=3 · 其它=0。 */
    private static int platCode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return 1;
        if (os.contains("win")) return 2;
        if (os.contains("nux") || os.contains("nix")) return 3;
        return 0;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " from " + url);
        }
        return json.readTree(r.body());
    }
}

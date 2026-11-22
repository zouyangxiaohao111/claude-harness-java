package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.send.FeishuTokenService;
import com.nexusai.domain.im_channel.send.ImSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 「测试连接」的**真探测**：用未保存的凭证换一张 tenant_access_token，再尽最大努力读机器人信息。
 *
 * <h2>为什么必须真的打一次</h2>
 * 旧实现只弹一句「连接成功」——用户以为配好了，直到启动接收才发现凭证错。
 * 校验凭证唯一可靠的办法就是让飞书说「这张票换不换给你」。
 *
 * <h2>成功/失败的判定</h2>
 * <ul>
 *   <li>换票成功 = 凭证有效（这是硬判据）；</li>
 *   <li>机器人名称（{@code /bot/v3/info}）与会话数（{@code /im/v1/chats}）是**加分信息**：
 *       没开机器人能力 / 没进群都会失败，但凭证仍然是对的 ⇒ 不让它们把结果判成失败。</li>
 * </ul>
 *
 * <h2>安全</h2>
 * ⛔ 不记录、不回显 appSecret 与 token；异常只带飞书返回的 code/msg（已由
 * {@link FeishuTokenService} 做截断与掩码）。探测票缓存在 {@code probe:<appId>} 这个
 * 合成键下，不与真实实例的票混用。
 */
@Component
public class ImCredentialProbeService {

    private static final Logger log = LoggerFactory.getLogger(ImCredentialProbeService.class);

    static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    private final String baseUrl;
    private final FeishuTokenService tokenService;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ImCredentialProbeService() {
        this(DEFAULT_BASE_URL);
    }

    /** 测试用：指向本地 stub，避免真连飞书。 */
    public ImCredentialProbeService(String baseUrl) {
        String v = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
        this.tokenService = new FeishuTokenService(this.baseUrl);
    }

    /** 探测结果（前缀给前端展示用；⛔ 不含任何凭证/票）。 */
    public record Result(boolean ok, String code, String message, String botName, String chatVisible) {}

    /**
     * 飞书凭证探测。
     *
     * @param appId     飞书应用 id
     * @param appSecret 飞书应用密钥（只用于换票，不落日志）
     */
    public Result probeFeishu(String appId, String appSecret) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            return new Result(false, "INVALID_CREDENTIAL", "请先填写 App ID 与 App Secret", null, null);
        }
        String token;
        try {
            // 合成维度：同一应用重复点「测试连接」复用同一张票（换票有配额）
            token = tokenService.tokenFor("probe:" + appId, appId, appSecret);
        } catch (ImSendException e) {
            log.info("[ImProbe] 飞书凭证探测失败 appId={} : {}", mask(appId), e.getMessage());
            return new Result(false, "TOKEN_EXCHANGE_FAILED", e.getMessage(), null, null);
        }

        String botName = null;
        try {
            JsonNode r = get(token, "/open-apis/bot/v3/info");
            if (r != null && r.path("code").asInt(-1) == 0) {
                botName = blankToNull(r.path("bot").path("app_name").asText(""));
            }
        } catch (RuntimeException e) {
            // 没开机器人能力 / 网络抖动都只影响这条加分信息
            log.debug("[ImProbe] 读机器人信息失败（忽略）: {}", e.toString());
        }

        String chatVisible = null;
        try {
            JsonNode r = get(token, "/open-apis/im/v1/chats?page_size=1");
            if (r != null && r.path("code").asInt(-1) == 0) {
                JsonNode data = r.path("data");
                int n = data.path("items").isArray() ? data.path("items").size() : 0;
                boolean hasMore = data.path("has_more").asBoolean(false);
                chatVisible = hasMore ? (n + "+") : String.valueOf(n);
            }
        } catch (RuntimeException e) {
            log.debug("[ImProbe] 读会话数失败（忽略）: {}", e.toString());
        }

        StringBuilder msg = new StringBuilder("凭证有效");
        if (botName != null) {
            msg.append(" · 机器人：").append(botName);
        }
        if (chatVisible != null) {
            msg.append(" · 可见群聊 ").append(chatVisible);
        }
        return new Result(true, "0", msg.toString(), botName, chatVisible);
    }

    /** 只读 GET；非 2xx / 不可解析返回 null（调用方都是 best-effort）。 */
    private JsonNode get(String token, String path) {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2 || resp.body() == null) {
                return null;
            }
            return json.readTree(resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 日志里只留前缀，避免整段 appId 进日志。 */
    private static String mask(String appId) {
        return appId.length() <= 10 ? appId : appId.substring(0, 10) + "…";
    }
}

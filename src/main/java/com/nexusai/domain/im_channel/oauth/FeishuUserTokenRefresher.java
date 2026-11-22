package com.nexusai.domain.im_channel.oauth;

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
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 飞书 user_access_token 的**续期**（RFC 6749 §6 refresh_token grant）。
 *
 * <h2>为什么必须要有它</h2>
 * user token 只有 2 小时（{@code expires_in:7200}）。授权时我们申请的 scope 里带了
 * {@code offline_access}，飞书因此下发 {@code refresh_token} —— 那个 scope 的唯一用途
 * 就是「以后不用再让用户点一次同意」。⛔ 存了 refresh_token 却不刷新，
 * 等于把「授权一次」变成「每 2 小时授权一次」，是纯缺陷。
 *
 * <h2>为什么不依赖 ImChannelService</h2>
 * 读写令牌、解密凭证都在 {@code ImChannelService}；如果本类反向依赖它，
 * 就会形成构造期循环（Service → Refresher → Service）。所以本类是**纯函数式**的：
 * 收 appId/appSecret/refreshToken，返回新三元组，不做任何落库。
 * 写回由调用方（{@code ImChannelService}）完成。
 *
 * <h2>飞书的两个坑（都按 device flow 的实测形态处理）</h2>
 * <ol>
 *   <li>换票端点仍是 {@code accounts 域 + /open-apis/authen/v2/oauth/token}；
 *       客户端认证用 Basic（与 device flow 一致）；</li>
 *   <li>刷新响应**可能不带 refresh_token**（飞书/Google 都这样）⇒
 *       必须**保留旧的 refresh_token**，否则下次刷新就没有凭据了。
 *       这是最常见的 refresh 实现 bug。</li>
 * </ol>
 */
@Component
public class FeishuUserTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(FeishuUserTokenRefresher.class);

    /** 与 {@link FeishuUserAuthService#ACCOUNTS} / {@link FeishuUserAuthService#PATH_TOKEN} 同源。 */
    private static final String TOKEN_URL = "https://accounts.feishu.cn/open-apis/authen/v2/oauth/token";

    private final String tokenUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public FeishuUserTokenRefresher() {
        this(TOKEN_URL);
    }

    /** [测试注入] 指向本地 stub —— ⛔ 单测绝不能打真飞书。 */
    FeishuUserTokenRefresher(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    /**
     * 刷新结果。
     *
     * @param accessToken  新的 user token
     * @param refreshToken 新的 refresh token；**响应没给时为传入的旧值**（不是 null）
     * @param expiresAt    新的 ISO-8601 过期时间；响应没给 expires_in 时为 null
     */
    public record Refreshed(String accessToken, String refreshToken, String expiresAt) {}

    /**
     * 用 refresh_token 换一张新的 user token。
     *
     * @throws RefreshException 刷新被拒（refresh_token 过期/被吊销/网络失败）——
     *                          调用方据此判定「需要用户重新授权」
     */
    public Refreshed refresh(String appId, String appSecret, String refreshToken)
            throws RefreshException {
        if (isBlank(appId) || isBlank(appSecret) || isBlank(refreshToken)) {
            throw new RefreshException("凭证不完整，无法刷新（appId/appSecret/refreshToken 缺一）");
        }
        String form = "grant_type=" + enc("refresh_token")
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(appId);
        String basic = Base64.getEncoder()
                .encodeToString((appId + ":" + appSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic " + basic)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new RefreshException("构造刷新请求失败: " + e.getMessage());
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefreshException("刷新请求被中断");
        } catch (Exception e) {
            throw new RefreshException("刷新请求失败: " + e.getClass().getSimpleName());
        }

        JsonNode root;
        try {
            root = json.readTree(resp.body() == null ? "{}" : resp.body());
        } catch (Exception e) {
            throw new RefreshException("刷新响应不是 JSON（HTTP " + resp.statusCode() + "）");
        }
        String accessToken = root.path("access_token").asText("");
        if (accessToken.isBlank()) {
            // ⚠️ 飞书业务错误常配 HTTP 200 ⇒ 不能只看状态码
            String err = root.path("error").asText(root.path("code").asText(""));
            String desc = root.path("error_description").asText(root.path("msg").asText(""));
            throw new RefreshException("飞书拒绝刷新: "
                    + (err.isBlank() ? "HTTP " + resp.statusCode() : err + (desc.isBlank() ? "" : " - " + desc)));
        }

        // 🔴 响应没给新 refresh_token ⇒ 必须沿用旧的（大多数实现会在这里把令牌置空，
        //    导致「刷新成功一次，之后再也刷不了」）
        String newRefresh = root.path("refresh_token").asText("");
        if (newRefresh.isBlank()) {
            newRefresh = refreshToken;
        }
        int expiresIn = root.path("expires_in").asInt(0);
        String expiresAt = expiresIn > 0
                ? OffsetDateTime.now().plusSeconds(expiresIn).toString()
                : null;

        log.info("[FeishuOAuth] 用户令牌已自动续期 expiresIn={} 收到新refreshToken={}",
                expiresIn, !newRefresh.equals(refreshToken));
        return new Refreshed(accessToken, newRefresh, expiresAt);
    }

    /** 刷新失败（含「refresh_token 已失效」）⇒ 上层应提示用户重新授权。 */
    public static class RefreshException extends Exception {
        public RefreshException(String message) {
            super(message);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

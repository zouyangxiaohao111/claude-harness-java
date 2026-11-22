package com.nexusai.domain.im_channel.qr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书「一键创建应用」（官方 lark-cli {@code config init --new} 的纯 HTTP 等价物）。
 *
 * <h2>协议来源：官方开源源码，不是逆向</h2>
 * {@code github.com/larksuite/cli} → {@code internal/auth/app_registration.go}。三步：
 * <pre>
 * ① POST {accounts}/oauth/v1/app/registration   （form-urlencoded）
 *    action=begin & archetype=PersonalAgent & auth_method=client_secret
 *    & request_user_info="open_id tenant_brand"
 *    → device_code / user_code / verification_uri_complete / expires_in(3600) / interval(5)
 * ② 浏览器打开 verification_uri_complete，用户点「同意」（飞书那边**直接把应用建好**）
 * ③ POST 同上  action=poll & device_code=…  → client_id / client_secret
 * </pre>
 *
 * <p>⚠️ <b>必须 form-urlencoded，不是 JSON</b> —— 用 JSON 会返回
 * {@code 20100 auth method is unsupported}，这正是我最初踩的坑。
 *
 * <p>国内 {@code accounts.feishu.cn} / 国际 {@code accounts.larksuite.com}。
 */
@Component
public class FeishuQrStrategy implements QrProvisioningStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuQrStrategy.class);

    private static final String DEFAULT_ACCOUNTS = "https://accounts.feishu.cn";
    private static final String PATH = "/oauth/v1/app/registration";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ObjectMapper json = new ObjectMapper();
    /** device_code → accounts 基址（poll 要打到同一个域）。 */
    private final Map<String, String> accountsByDeviceCode = new ConcurrentHashMap<>();

    @Override
    public String platform() {
        return "feishu";
    }

    @Override
    public boolean supportsQr() {
        return true;
    }

    @Override
    public QrSession start(String displayName) {
        String accounts = DEFAULT_ACCOUNTS;
        String form = "action=begin"
            + "&archetype=PersonalAgent"
            + "&auth_method=client_secret"
            + "&request_user_info=" + enc("open_id tenant_brand");
        try {
            JsonNode d = postForm(accounts + PATH, form);
            String deviceCode = d.path("device_code").asText("");
            String complete = d.path("verification_uri_complete").asText("");
            if (deviceCode.isEmpty() || complete.isEmpty()) {
                throw new IllegalStateException("飞书未返回 device_code/verification_uri: " + d);
            }
            int expiresIn = d.path("expires_in").asInt(600);
            accountsByDeviceCode.put(deviceCode, accounts);
            log.info("[FeishuQr] 已生成注册会话 userCode={} expiresIn={}s",
                d.path("user_code").asText(""), expiresIn);
            return new QrSession(deviceCode, complete, null,
                "已打开飞书授权页：点「同意」即可，飞书会自动创建一个应用并把凭证返回");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("发起飞书应用创建失败：" + e.getMessage(), e);
        }
    }

    @Override
    public QrPollResult poll(String sessionId) {
        String accounts = accountsByDeviceCode.get(sessionId);
        if (accounts == null) {
            return new QrPollResult(QrPollResult.State.EXPIRED, null, "注册会话不存在或已过期，请重新发起");
        }
        try {
            JsonNode d = postForm(accounts + PATH,
                "action=poll&device_code=" + enc(sessionId));
            String clientId = d.path("client_id").asText("");
            String clientSecret = d.path("client_secret").asText("");
            if (clientId.isEmpty() || clientSecret.isEmpty()) {
                // 未授权 / 中间态：继续轮询（官方语义：没有完整凭证就不是错误）
                String err = d.path("error").asText("");
                if ("expired_token".equals(err) || "invalid_grant".equals(err)) {
                    accountsByDeviceCode.remove(sessionId);
                    return new QrPollResult(QrPollResult.State.EXPIRED, null,
                        d.path("error_description").asText("授权已过期，请重新发起"));
                }
                return new QrPollResult(QrPollResult.State.PENDING, null, null);
            }
            accountsByDeviceCode.remove(sessionId);
            Map<String, String> cred = new LinkedHashMap<>();
            cred.put("appId", clientId);
            cred.put("appSecret", clientSecret);
            log.info("[FeishuQr] 应用创建完成 appId={} secretLen={}", clientId, clientSecret.length());
            JsonNode ui = d.path("user_info");
            String name = ui.path("name").asText("");
            return new QrPollResult(QrPollResult.State.SCANNED,
                new Provisioned(name.isBlank() ? null : name + " 的飞书应用",
                    QrProvisioningStrategy.toJson(cred)),
                "已创建应用 " + clientId);
        } catch (Exception e) {
            log.warn("[FeishuQr] 轮询失败: {}", e.toString());
            return new QrPollResult(QrPollResult.State.PENDING, null, "轮询失败，稍后重试");
        }
    }

    private JsonNode postForm(String url, String form) throws Exception {
        HttpResponse<String> r = HTTP.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return json.readTree(r.body());
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}

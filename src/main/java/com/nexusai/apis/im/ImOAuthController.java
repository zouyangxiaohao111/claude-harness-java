package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.oauth.FeishuUserAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「以用户本人名义发消息」的授权端点（飞书 device flow）。
 *
 * <h2>为什么单独一个控制器，不塞进 ImQrController</h2>
 * {@code ImQrController} 管的是「建渠道实例」（扫码建应用），本控制器管的是
 * 「给已存在的实例授权一个真人身份」。两者都是 device flow，但产物完全不同
 * （一个是 appId/appSecret，一个是 user_access_token），混在一起会让
 * 「谁授权了谁」变得难以追踪。
 *
 * <h2>⚠️ 授权链接必须自动打开浏览器</h2>
 * 前端拿到 {@code start} 的响应后，用户还要去浏览器点「同意」。如果只把 URL 打印出来，
 * 用户得手动复制粘贴 —— 这一步在别的平台（企微扫码）踩过，这里直接由后端用系统命令打开。
 * 打不开也不致命：响应里仍然带 {@code verificationUriComplete}，前端可以展示给用户复制。
 */
@RestController
@RequestMapping("/api/v1/im/oauth")
public class ImOAuthController {

    private static final Logger log = LoggerFactory.getLogger(ImOAuthController.class);

    @Autowired private FeishuUserAuthService feishuAuth;
    @Autowired private com.nexusai.domain.im_channel.ImChannelService channelService;
    @Autowired private com.nexusai.domain.im_channel.send.FeishuTokenService feishuTokenService;

    /**
     * 发起授权：返回 device code 与授权链接。
     *
     * <p>默认**不**自动打开浏览器：用户 2026-11-23 要求先在我们的右侧抽屉里选
     * 「自动授权 / 管理员授权」，再由前端用应用内窗口打开授权页。
     * 需要旧行为时显式传 {@code ?autoOpenBrowser=true}。
     *
     * @return {@code {deviceCode, userCode, verificationUriComplete, interval, expiresIn, browserOpened}}
     */
    @PostMapping("/{instanceId}/start")
    public Map<String, Object> start(@PathVariable String instanceId,
                                     @RequestParam(defaultValue = "false") boolean autoOpenBrowser) {
        FeishuUserAuthService.StartResult r = feishuAuth.start(instanceId);
        boolean opened = autoOpenBrowser && openBrowser(r.verificationUriComplete());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceCode", r.deviceCode());
        out.put("userCode", r.userCode());
        out.put("verificationUriComplete", r.verificationUriComplete());
        out.put("interval", r.interval());
        out.put("expiresIn", r.expiresIn());
        out.put("scope", r.scope());
        out.put("browserOpened", opened);
        return out;
    }

    /**
     * 轮询授权结果。前端按 {@code interval} 秒调一次。
     *
     * @return {@code {state: PENDING|AUTHORIZED|ERROR, openId?, message?}}
     */
    @GetMapping("/{instanceId}/poll")
    public Map<String, Object> poll(@PathVariable String instanceId,
                                    @RequestParam String deviceCode) {
        FeishuUserAuthService.PollResult r = feishuAuth.poll(instanceId, deviceCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", r.state());
        out.put("openId", r.openId());
        out.put("message", r.message());
        return out;
    }

    /**
     * 授权状态 + **这张 token 缺哪些 scope**。
     *
     * <p>为什么要暴露 missingScopes：我们后续会给 {@code DEFAULT_SCOPE} 加权限
     * （如读历史消息），但旧 token 不会自动获得 ⇒ 表现成「某功能莫名不可用」。
     * 前端拿到这份清单就能明确提示「需要重新授权以启用新能力」，
     * 而不是等某次调用失败才暴露、且用户不知道要做什么。
     *
     * @return {@code {authorized, openId, missingScopes:[]}}
     */
    @GetMapping("/{instanceId}/status")
    public Map<String, Object> status(@PathVariable String instanceId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(feishuAuth.status(instanceId));
        // 空数组 = 齐全，或无法判断（V316 之前授权的老数据）⇒ 前端不必提示
        out.put("missingScopes", feishuAuth.missingScopes(instanceId));
        return out;
    }

    /**
     * 飞书的**通讯录读取权限**有没有开通（决定能不能把 {@code ou_xxx} 换成人名）。
     *
     * <h2>为什么单独查</h2>
     * 这是**应用身份权限**（tenant_access_token 那一套），跟用户 OAuth 授权拿到的 scope 是两回事：
     * 用户把授权页点穿也不会让它生效。判定方式最直接 —— 拿应用 token 查一次自己的 open_id，
     * <ul>
     *   <li>返回 {@code name} ⇒ 已开通；</li>
     *   <li>{@code code:0} 但没有 {@code name} ⇒ 没开通（字段级权限缺失，不是范围问题 ——
     *       范围问题会报 {@code 41050 no user authority}）。</li>
     * </ul>
     *
     * <p>⚠️ 没开通**不影响任何功能**：消息照收照发，只是飞书对方显示成 {@code ou_xxx}。
     *
     * @return {@code {granted, openId, missingScope, applyUrl}}
     */
    @GetMapping("/{instanceId}/contacts-permission")
    public Map<String, Object> contactsPermission(@PathVariable String instanceId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("granted", false);
        // 拿 name 需要应用身份开通「获取用户基本信息」
        out.put("missingScope", "contact:user.base:readonly");
        try {
            String openId = channelService.userOpenIdOf(instanceId).orElse(null);
            out.put("openId", openId);
            String credJson = channelService.credentialOf(instanceId).orElse(null);
            if (credJson == null) {
                out.put("error", "渠道凭证不存在");
                return out;
            }
            com.fasterxml.jackson.databind.JsonNode cred =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(credJson);
            String appId = cred.path("appId").asText("");
            if (!appId.isBlank()) {
                out.put("applyUrl", "https://open.feishu.cn/app/" + appId
                        + "/auth?q=contact:user.base:readonly");
            }
            if (openId == null || openId.isBlank()) {
                out.put("error", "还没有可用的授权 open_id（先完成一次授权）");
                return out;
            }
            String token = feishuTokenService.tokenFor(instanceId, appId,
                    cred.path("appSecret").asText(""));
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "https://open.feishu.cn/open-apis/contact/v3/users/"
                                    + java.net.URLEncoder.encode(openId, java.nio.charset.StandardCharsets.UTF_8)
                                    + "?user_id_type=open_id"))
                            .timeout(java.time.Duration.ofSeconds(12))
                            .header("Authorization", "Bearer " + token)
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            var root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.body() == null ? "" : resp.body());
            out.put("platformCode", root.path("code").asInt(-1));
            String name = root.path("data").path("user").path("name").asText("");
            out.put("name", name);
            out.put("granted", !name.isBlank());
        } catch (Exception e) {
            // 查不到不该让页面报错 —— 这只是一个「能不能显示人名」的提示
            out.put("error", e.getClass().getSimpleName());
            log.warn("[ImOAuth] 查飞书通讯录权限失败 instance={} err={}", instanceId, e.toString());
        }
        return out;
    }

    /** 取消授权（用户点「取消授权」）。 */
    @DeleteMapping("/{instanceId}")
    public Map<String, Object> revoke(@PathVariable String instanceId) {
        feishuAuth.revoke(instanceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revoked", true);
        return out;
    }

    /**
     * 用系统命令打开浏览器。
     *
     * <p>为什么不用 {@code java.awt.Desktop}：本机实测它会让进程 exit 134（AWT 初始化失败）。
     * 外部命令是可靠的：macOS {@code open}、Windows {@code rundll32}、Linux {@code xdg-open}。
     *
     * @return 是否成功发起（打不开不抛异常 —— 用户仍可手动复制链接）
     */
    private static boolean openBrowser(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.redirectErrorStream(true);
            pb.start();
            return true;
        } catch (Exception e) {
            log.warn("[ImOAuth] 打不开浏览器（用户可手动打开）err={}", e.toString());
            return false;
        }
    }
}

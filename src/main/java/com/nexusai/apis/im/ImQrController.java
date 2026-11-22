package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.qr.QrProvisioningRegistry;
import com.nexusai.domain.im_channel.qr.QrProvisioningStrategy;
import com.nexusai.domain.im_channel.qr.QrProvisioningStrategy.Provisioned;
import com.nexusai.domain.im_channel.qr.QrProvisioningStrategy.QrPollResult;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「扫码 / 一键创建」的通用入口（按平台分发到 {@link QrProvisioningStrategy}）。
 *
 * <h2>为什么建实例放在后端</h2>
 * 扫码成功后拿到的 secret 是**明文凭证**，绝不能经前端中转（日志、内存快照、浏览器插件都可能泄漏）。
 * 所以 {@code poll} 一旦拿到 SCANNED，就在这里直接 {@link ImChannelService#create} 加密落库，
 * 前端只拿到 instanceId。
 */
@RestController
@RequestMapping("/api/v1/im/qr")
public class ImQrController {

    @Autowired private QrProvisioningRegistry registry;
    @Autowired private ImChannelService channelService;

    /**
     * 发起扫码。
     *
     * @return {@code {sessionId, qrContent, hint}}；qrContent 可能是 URL（企微/飞书）或二维码内容（微信）
     */
    @PostMapping("/{platform}/start")
    public Map<String, Object> start(@PathVariable String platform,
                                     @RequestBody(required = false) Map<String, Object> body) {
        String displayName = body == null ? null : str(body, "displayName");
        QrProvisioningStrategy strategy = registry.forPlatform(platform);
        QrProvisioningStrategy.QrSession sess = strategy.start(displayName);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("sessionId", sess.sessionId());
        ok.put("qrContent", sess.qrContent());
        ok.put("expiresAt", sess.expiresAt());
        ok.put("hint", sess.hint());
        return ok;
    }

    /**
     * 轮询扫码状态；一旦扫完就在后端建实例（凭证不经前端）。
     *
     * @return {@code {state, instanceId?, message?}}；state ∈ PENDING / SCANNED / EXPIRED / UNSUPPORTED
     */
    @GetMapping("/{platform}/{sessionId}/poll")
    public Map<String, Object> poll(@PathVariable String platform, @PathVariable String sessionId) {
        QrProvisioningStrategy strategy = registry.forPlatform(platform);
        QrPollResult r = strategy.poll(sessionId);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("state", r.state().name());
        ok.put("message", r.message());
        if (r.state() == QrPollResult.State.SCANNED && r.provisioned() != null) {
            Provisioned p = r.provisioned();
            String name = p.displayName() == null || p.displayName().isBlank()
                ? defaultName(platform) : p.displayName();
            ImInstanceEntry inst = channelService.create(platform, name, p.credentialJson(), null);
            ok.put("instanceId", inst.id());
            ok.put("displayName", inst.displayName());
        }
        return ok;
    }

    private static String defaultName(String platform) {
        return switch (platform) {
            case "wecom" -> "企业微信机器人";
            case "feishu" -> "飞书机器人";
            case "wechat" -> "微信机器人";
            default -> platform + " 机器人";
        };
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}

package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImInstanceIdentityService {

    private static final Logger log = LoggerFactory.getLogger(ImInstanceIdentityService.class);
    private static final long TTL_MS = 10 * 60_000L;

    public record Identity(boolean ok, String platform, String botName, String botId,
                           String authorizerId, String chatVisible, String message) {}

    private record Cached(Identity identity, long expiresAt) {}

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired private ImChannelService channelService;
    @Autowired private ImCredentialProbeService feishuProbe;
    @Autowired private WecomCliTokenService wecomTokenService;
    @Autowired private WecomCliClient wecomCliClient;

    /** 读机器人身份（默认命中 10 分钟缓存）。 */
    public Identity resolve(String instanceId) {
        return resolve(instanceId, false);
    }

    /**
     * 读机器人身份。
     *
     * @param refresh true = 忽略缓存重新探测（接入页「重新识别」用）
     */
    public Identity resolve(String instanceId, boolean refresh) {
        if (instanceId == null || instanceId.isBlank()) {
            return new Identity(false, null, null, null, null, null, "instanceId 不能为空");
        }
        if (!refresh) {
            Cached c = cache.get(instanceId);
            if (c != null && c.expiresAt() > System.currentTimeMillis()) {
                return c.identity();
            }
        }
        Identity identity = doResolve(instanceId);
        cache.put(instanceId, new Cached(identity, System.currentTimeMillis() + TTL_MS));
        return identity;
    }

    private Identity doResolve(String instanceId) {
        Optional<ImInstanceEntry> instOpt = channelService.findById(instanceId);
        if (instOpt.isEmpty()) {
            return new Identity(false, null, null, null, null, null, "渠道实例不存在");
        }
        ImInstanceEntry inst = instOpt.get();
        Optional<String> credOpt = channelService.credentialOf(instanceId);
        if (credOpt.isEmpty()) {
            return new Identity(false, inst.platform(), null, null, null, null, "凭证缺失");
        }
        JsonNode cred;
        try {
            cred = json.readTree(credOpt.get());
        } catch (Exception e) {
            return new Identity(false, inst.platform(), null, null, null, null, "凭证不是合法 JSON");
        }
        return switch (inst.platform() == null ? "" : inst.platform()) {
            case "feishu" -> resolveFeishu(cred);
            case "wecom" -> resolveWecom(instanceId, cred);
            case "wechat" -> new Identity(true, "wechat", null, null, null, null,
                    "微信机器人身份=扫码登录的账号；会话要等对方先发消息才能确定");
            default -> new Identity(true, inst.platform(), null, null, null, null,
                    "该平台暂不支持机器人身份探测");
        };
    }


    /** 飞书：token -> bot/v3/info（机器人名）+ im/v1/chats（可见群数）。 */
    private Identity resolveFeishu(JsonNode cred) {
        String appId = text(cred, "appId", "app_id");
        String appSecret = text(cred, "appSecret", "app_secret");
        if (appId == null || appSecret == null) {
            return new Identity(false, "feishu", null, appId, null, null, "飞书凭证不完整（appId/appSecret）");
        }
        var r = feishuProbe.probeFeishu(appId, appSecret);
        return new Identity(r.ok(), "feishu", r.botName(), appId, null, r.chatVisible(), r.message());
    }

    /**
     * 企微：CLI token -> {@code identity whoami}（授权人 ID）。
     *
     * <p>官方 CLI 命令是 {@code wecom-cli identity whoami}；这里按 CLI 网关的
     * {@code /cli/&lt;service&gt;/&lt;method&gt;} 形态调用（{@code /cli/identity/whoami}）。
     * 若平台改名，方法返回「凭证有效但授权人未取到」，不会判成凭证失败。
     */
    private Identity resolveWecom(String instanceId, JsonNode cred) {
        String botId = text(cred, "botId", "bot_id");
        String botSecret = text(cred, "botSecret", "bot_secret");
        if (botId == null || botSecret == null) {
            return new Identity(false, "wecom", null, botId, null, null, "企微凭证不完整（botId/botSecret）");
        }
        String error = null;
        JsonNode result = null;
        try {
            String token = wecomTokenService.tokenFor(instanceId, botId, botSecret);
            var r = wecomCliClient.call(token, "/cli/identity/whoami", json.createObjectNode());
            if (r != null && r.ok()) {
                result = r.result();
            } else if (r != null) {
                error = "whoami 被拒: " + r.errcode() + " " + truncate(r.errmsg());
            }
        } catch (Exception e) {
            error = "whoami 调用失败: " + e.getMessage();
        }
        String authorizer = firstAuthorizer(result);
        if (authorizer == null) {
            log.info("[ImIdentity] 企微 whoami 未返回授权人（凭证有效）instance={} err={}", instanceId, error);
            return new Identity(true, "wecom", null, botId, null, null,
                    "凭证有效（Bot " + shortId(botId) + "）；授权人未取到" + (error == null ? "" : "：" + error));
        }
        return new Identity(true, "wecom", null, botId, authorizer, null,
                "已识别机器人 Bot " + shortId(botId) + "，授权人 " + authorizer);
    }

    /** whoami 的字段名各版本可能不同：按候选键 + 一层嵌套防御性取值。 */
    private static String firstAuthorizer(JsonNode result) {
        if (result == null) {
            return null;
        }
        String direct = text(result, "authorizer_id", "authorizerId", "authorizer",
                "userid", "user_id", "open_id");
        if (direct != null) {
            return direct;
        }
        for (String nested : new String[]{"identity", "authorizer", "data"}) {
            JsonNode n = result.path(nested);
            if (n.isObject()) {
                String v = text(n, "authorizer_id", "authorizerId", "userid", "user_id", "open_id", "id");
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String k : keys) {
            String v = node.path(k).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** 日志/界面只展示短 id（保留前缀，足够辨认，不整段刷屏）。 */
    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.length() <= 10 ? id : id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }
}

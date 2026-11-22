package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IM 渠道实例 REST 端点（V302）· 对应界面「IM 频道 › 频道」tab。
 *
 * <p><b>只吐实例，不吐凭证</b>：{@link ImInstanceEntry} 本身不含 credential 字段，
 * 所以这些端点<b>不可能</b>把 token / secret 漏给前端。凭证只在
 * {@code ImChannelService.credentialOf} 内部读取（发消息/建连时）。
 *
 * <p><b>不在这里做接线</b>：实例 ↔ 会话的归属走 {@code /api/v1/channels}（V255）。
 * 两套端点分开是刻意的 —— 实例不归属会话，任何会话都能用任一实例发消息。
 */
@RestController
@RequestMapping("/api/v1/im/instances")
public class ImChannelController {

    @Autowired private ImChannelService channelService;
    @Autowired(required = false) private com.nexusai.domain.im_channel.ImChatService imChatService;
    @Autowired(required = false) private com.nexusai.domain.session.SessionModeService sessionModeService;
    @Autowired private com.nexusai.domain.im_channel.ImCredentialProbeService probeService;
    @Autowired private com.nexusai.domain.im_channel.ImInstanceIdentityService identityService;

    /**
     * 全部实例（频道 tab 列表 + 输入框渠道选择器共用）。
     *
     * <p>同一平台下有多个同名实例时（例如两个都叫「飞书」），按创建顺序加序号
     * 「飞书 1 / 飞书 2」——否则用户在列表和选择器里分不清点的是哪一个。
     *
     * <p>⚠️ 只修饰<b>返回给前端</b>的名字：{@code channelService.findById} 等内部读取
     * 仍是原名（会话名、日志、编辑回填都不该被序号污染）。
     */
    @GetMapping
    public List<ImInstanceEntry> list() {
        return withDuplicateSequence(channelService.list());
    }

    /**
     * 同名实例按出现顺序加序号（仅当同平台内该名字出现 &gt;1 次）。
     *
     * <p>入参顺序即 {@code created_at} 升序（{@code ImChannelService.list()} 的排序），
     * 所以「1、2、3」= 创建先后。包级可见以便单测。
     */
    static List<ImInstanceEntry> withDuplicateSequence(List<ImInstanceEntry> all) {
        if (all == null || all.isEmpty()) {
            return all;
        }
        // platform → (displayName → 次数)
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (ImInstanceEntry e : all) {
            counts.computeIfAbsent(e.platform(), k -> new LinkedHashMap<>())
                    .merge(e.displayName(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<ImInstanceEntry> out = new java.util.ArrayList<>(all.size());
        for (ImInstanceEntry e : all) {
            String name = e.displayName();
            int total = counts.getOrDefault(e.platform(), Map.of()).getOrDefault(name, 1);
            if (total <= 1) {
                out.add(e);
                continue;
            }
            String key = e.platform() + "\u0000" + name;
            int seq = seen.merge(key, 1, Integer::sum);
            out.add(new ImInstanceEntry(e.id(), e.platform(), name + " " + seq,
                    e.status(), e.statusDetail(), e.lastActiveAt(), e.configJson(),
                    e.createdAt(), e.updatedAt()));
        }
        return out;
    }

    /**
     * 机器人身份（接入页 / 渠道选择器展示用）。
     *
     * <p>飞书：bot/v3/info 的 app_name + 可见群数；企微：Bot ID + whoami 授权人；
     * 微信：仅说明「登录账号即机器人，会话等对方先发消息」。
     * {@code refresh=true} 忽略 10 分钟缓存重新探测。
     */
    @GetMapping("/{id}/identity")
    public Map<String, Object> identity(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean refresh) {
        var r = identityService.resolve(id, Boolean.TRUE.equals(refresh));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        putIfPresent(out, "platform", r.platform());
        putIfPresent(out, "botName", r.botName());
        putIfPresent(out, "botId", r.botId());
        putIfPresent(out, "authorizerId", r.authorizerId());
        putIfPresent(out, "chatVisible", r.chatVisible());
        putIfPresent(out, "message", r.message());
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }

    /**
     * 新增实例。
     *
     * <p>请求体：{@code {platform, displayName, credential, configJson?}}。
     * {@code credential} 是平台凭证 JSON 原样字符串（微信 botToken / 企微 botId+secret /
     * 飞书 appId+secret / 钉钉 clientId+secret），本层加密后落库。
     */
    @PostMapping
    public ImInstanceEntry create(@RequestBody Map<String, Object> body) {
        String platform = str(body, "platform");
        String displayName = str(body, "displayName");
        String credential = str(body, "credential");
        String configJson = str(body, "configJson");
        if (credential == null || credential.isBlank()) {
            throw new ValidationException("credential 不能为空");
        }
        return channelService.create(platform, displayName, credential, configJson);
    }

    /**
     * 测试连接（**不落库**）：校验凭证能不能真的换到平台令牌。
     *
     * <p>⛔ 旧行为是前端直接弹「连接成功」，用户会以为配好了、直到启动接收才发现凭证错。
     * 这里由后端拿未保存的凭证真打一次换票；成功只回机器人名 / 可见群聊数（不含凭证与票），
     * 失败回平台码与中文解释。
     */
    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String platform = str(body, "platform");
        String credential = str(body, "credential");
        if (!"feishu".equals(platform)) {
            out.put("ok", false);
            out.put("code", "UNSUPPORTED");
            out.put("message", "该平台暂不支持测试连接；保存后点「启动接收」验证");
            return out;
        }
        com.fasterxml.jackson.databind.JsonNode cred = null;
        try {
            cred = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(credential == null ? "" : credential);
        } catch (Exception ignored) {
            // 下面统一按非法凭证返回
        }
        if (cred == null || !cred.isObject()) {
            out.put("ok", false);
            out.put("code", "INVALID_CREDENTIAL");
            out.put("message", "凭证不是合法 JSON");
            return out;
        }
        var r = probeService.probeFeishu(
                textOr(cred, "appId", "app_id"), textOr(cred, "appSecret", "app_secret"));
        out.put("ok", r.ok());
        out.put("code", r.code());
        out.put("message", r.message());
        if (r.botName() != null) {
            out.put("botName", r.botName());
        }
        if (r.chatVisible() != null) {
            out.put("chatVisible", r.chatVisible());
        }
        return out;
    }

    private static String textOr(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 改显示名 / 平台开关（不轮换凭证）。 */
    @PutMapping("/{id}")
    public ImInstanceEntry update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return channelService.update(id, str(body, "displayName"), str(body, "configJson"));
    }

    /** 轮换凭证（重新扫码 / 换 Secret）。 */
    @PutMapping("/{id}/credential")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        String credential = str(body, "credential");
        if (credential == null || credential.isBlank()) {
            throw new ValidationException("credential 不能为空");
        }
        channelService.rotateCredential(id, credential);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("rotated", true);
        return ResponseEntity.ok(ok);
    }

    /**
     * 断开/删除实例（幂等）。
     *
     * <p>[V330] 删实例前先解绑该实例下的聊天（否则 {@code bound_session_id} 悬挂，
     * 「本会话是否还有渠道」永远为真，R14 收敛不触发）；删除后对受影响会话收敛模式。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        List<String> affectedSessions = imChatService == null
                ? List.of() : imChatService.unbindByInstance(id);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("removed", channelService.delete(id));
        if (sessionModeService != null) {
            for (String sessionId : affectedSessions) {
                sessionModeService.onChannelClosed(sessionId);
            }
        }
        return ResponseEntity.ok(ok);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}

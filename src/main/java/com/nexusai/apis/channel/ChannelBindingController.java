package com.nexusai.apis.channel;

import com.nexusai.domain.mcp_channel_binding.ChannelBindingService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.mcp_channel_binding.ChannelBindingEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部渠道 ↔ 会话 绑定 REST 端点（V255）。
 *
 * <p><b>为什么要有这组端点</b>：MCP 连接是<b>全局单例</b>（按 serverName），
 * 而消息必须投给<b>某个具体会话</b>（多会话）⇒ 中间缺一张绑定表。
 * 本端点就是这张表的写入面：用户（或 AI 工具）在这里声明「渠道 X 归会话 Y」。
 *
 * <p><b>互斥语义</b>（用户裁定 2026-11-03）：
 * <ul>
 *   <li>互斥键 = <b>渠道 id</b>，不是 serverName ⇒ 同渠道全局只能绑一个会话；</li>
 *   <li>同一会话可同时绑多个不同渠道（微信 + 飞书 + 企微并存）。</li>
 * </ul>
 *
 * <p><b>冲突处理</b>：绑定到已被别的会话占用的渠道 ⇒ <b>409</b> + 冲突详情
 * （不静默抢占）。前端可据此弹「是否接管」确认；带 {@code force=true} 才抢占。
 */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelBindingController {

    @Autowired private ChannelBindingService channelBindingService;

    /**
     * 全部绑定条目（渠道面板用）。
     *
     * @param sessionId 可选：只查某会话已绑定的渠道；不传 = 全部
     */
    @GetMapping
    public List<ChannelBindingEntry> list(@RequestBody(required = false) Map<String, String> body) {
        return channelBindingService.listAll();
    }

    /** 某会话已绑定的渠道（会话工作台「渠道」入口用）。 */
    @GetMapping("/sessions/{sessionId}")
    public List<ChannelBindingEntry> listBySession(@PathVariable String sessionId) {
        return channelBindingService.listBySession(sessionId);
    }

    /**
     * 绑定渠道到会话。
     *
     * <p>请求体：{@code {channelId, serverName, sessionId, force?}}。
     * <ul>
     *   <li>无冲突 → 200 + 绑定条目；</li>
     *   <li>有冲突且未 force → <b>409</b> + {@code {error, conflict:{channelId, sessionId, serverName}}}；</li>
     *   <li>有冲突且 force=true → 200（抢占旧的）。</li>
     * </ul>
     */
    @PostMapping("/bindings")
    public ResponseEntity<Map<String, Object>> bind(@RequestBody Map<String, Object> body) {
        String channelId = str(body, "channelId");
        String serverName = str(body, "serverName");
        String sessionId = str(body, "sessionId");
        boolean force = Boolean.TRUE.equals(body.get("force"));
        if (channelId == null || channelId.isBlank()) {
            throw new ValidationException("channelId 不能为空");
        }
        if (serverName == null || serverName.isBlank()) {
            throw new ValidationException("serverName 不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("sessionId 不能为空");
        }

        ChannelBindingService.BindResult result = channelBindingService.bind(channelId, serverName, sessionId, force);
        if (result.conflicted()) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("error", "channel already bound to another session");
            conflict.put("conflict", Map.of(
                "channelId", result.conflict().channelId(),
                "sessionId", result.conflict().sessionId(),
                "serverName", result.conflict().serverName()));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("binding", result.entry());
        return ResponseEntity.ok(ok);
    }

    /**
     * 解绑渠道（幂等）。
     *
     * @param sessionId 可选：校验归属（不匹配则不解绑）；不传 = 强制解绑
     */
    @DeleteMapping("/bindings/{channelId}")
    public ResponseEntity<Map<String, Object>> unbind(@PathVariable String channelId,
                                                      @RequestBody(required = false) Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        boolean removed = channelBindingService.unbind(channelId, sessionId);
        Map<String, Object> body0 = new LinkedHashMap<>();
        body0.put("removed", removed);
        return ResponseEntity.ok(body0);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}

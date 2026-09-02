package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.security.ChannelPermission;
import com.nexusai.eventbus.ws.ChannelPermissionRequestEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Channel 竞速回调的 STOMP/WebSocket 生产实现 · 对齐 CC services/mcp/channelPermissions.ts
 * （Telegram / iMessage / Discord 权限中继）。
 *
 * <p><b>WHY</b>（[canUseTool v4] 修复 v3 对抗复验缺口①）：v3 的
 * {@link ChannelPermissionCallbacks} 是接口、生产无 @Component 实现 → WebSocketPermissionPrompter
 * {@code @Autowired(required=false)} 注入 null → startChannelRace 直接 return，通道中继竞速永不
 * 参与。用户明确要求"限制就开放"。本类把通道表面接到 Java 既有的 STOMP/WebSocket 通道：
 * <ol>
 *   <li>{@link #sendRequest} → 出站推送到
 *       {@code /topic/sessions/{sessionId}/permission-channel-requests}（通道表面展示）</li>
 *   <li>用户通道回复 "yes tbxkq" → 通道 server 解析 → SEND
 *       {@code /app/sessions/{sessionId}/permission-channel-response} →
 *       {@link com.nexusai.apis.permission.PermissionController} 调 {@link #resolve} → 竞速 future
 *       complete（claim 守卫在 prompter 侧）</li>
 * </ol>
 *
 * <p>短 ID 生成（{@link #shortRequestId}）复用 {@link ChannelPermission}（security 包既有 CC
 * channelPermissions.ts 移植：FNV-1a 5 字母 a-z 去 'l' + blocklist 重 hash），避免双实现漂移。
 *
 * @see ChannelPermissionCallbacks
 * @see WebSocketPermissionPrompter#startChannelRace
 * @since canUseTool v4
 *
 * <p><b>[impl-I-3 T6 · Q-36]</b> STOMP 是 Java web 传输面替换（行为对齐 CC channel 权限中继、
 * 传输载体用 STOMP 代替 MCP notification）——非删除项（deletion-manifest KEEP-05），
 * 不做 STOMP→MCP 传输面迁移。channel 竞速整体门控
 * {@code nexusai.mcp.channels-permission-relay-enabled}（默认 false）+ 合格 channel server
 * 判定见 {@link WebSocketPermissionPrompter#startChannelRace}。
 */
@Component
public class StompChannelPermissionCallbacks implements ChannelPermissionCallbacks {

    private static final Logger log = LoggerFactory.getLogger(StompChannelPermissionCallbacks.class);

    private final SimpMessagingTemplate ws;
    private final ChannelPermission channelPermission;
    private final Map<String, Consumer<ChannelResponse>> pending = new ConcurrentHashMap<>();

    /**
     * @param ws                       STOMP 推送模板 · {@code @Autowired(required=false)} 容错 — 无 WebSocket
     *                                 场景（测试 / 纯后端）注入 null，sendRequest 退化为 warn + 不推送（race
     *                                 可无害继续但无通道表面 → 用户/本地决策兜底）。
     * @param channelPermissionFeature [OPD-WF8-02-07] ChannelPermission.isEnabled 门控（可配置、
     *                                 默认关闭，对齐 CC channelPermissions.ts:36-38 默认 false）。
     */
    @Autowired
    public StompChannelPermissionCallbacks(@Autowired(required = false) SimpMessagingTemplate ws,
                                           @Autowired(required = false) ChannelPermissionFeature channelPermissionFeature) {
        this.ws = ws;
        // 通道中继启用由外部 feature 门控；此处只复用 ID hash 与回调机制（对齐 CC
        // createChannelPermissionCallbacks 的 pending map + resolve）。
        // [OPD-WF8-02-07] isEnabled 门控配置化（feature 未注入 → 默认关闭，对齐 CC 默认 false）。
        this.channelPermission = new ChannelPermission(
            () -> channelPermissionFeature != null && channelPermissionFeature.isEnabled());
    }

    /** 单参兼容构造器（测试直构）· channelPermissionFeature 未注入 → isEnabled 默认关闭。 */
    public StompChannelPermissionCallbacks(SimpMessagingTemplate ws) {
        this(ws, null);
    }

    /** 出站 channel 请求 topic · {@code /topic/sessions/{sessionId}/permission-channel-requests}。 */
    public static String topicFor(String sessionId) {
        return "/topic/sessions/" + sessionId + "/permission-channel-requests";
    }

    @Override
    public String shortRequestId(String toolUseId) {
        return channelPermission.shortRequestId(toolUseId);
    }

    @Override
    public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
        // key 转小写 — 对齐 CC channelPermissions.ts:216-226（onResponse lowercase key，
        // resolve 同侧 lowercase，使 mixed-case 调用方也不会静默 miss）
        pending.put(requestId.toLowerCase(), handler);
        return () -> pending.remove(requestId.toLowerCase());
    }

    @Override
    public void sendRequest(String sessionId, String requestId, String toolName,
                            String description, JsonNode displayInput) {
        if (ws == null) {
            log.warn("CHANNEL sendRequest skipped: SimpMessagingTemplate 未注入 (无 WebSocket 通道) requestId={}",
                requestId);
            return;
        }
        // input_preview 截断到 200 字符 — CC truncateForPreview（channelPermissions.ts:160-167），
        // phone-sized JSON 预览（Write(5KB-file) 不刷屏）。
        ChannelPermissionRequestEvent event = ChannelPermissionRequestEvent.of(
            sessionId, requestId, toolName, description,
            truncateForPreview(displayInput));
        try {
            ws.convertAndSend(topicFor(sessionId), event);
            if (log.isInfoEnabled()) {
                log.info("CHANNEL sendRequest → topic={} requestId={} tool={} sessionId={}",
                    topicFor(sessionId), requestId, toolName, sessionId);
            }
        } catch (Exception e) {
            // fail-loud: 推送失败让 prompter 的 catch 按 graceful degradation 处理
            log.error("CHANNEL sendRequest STOMP push failed: requestId={} err={}",
                requestId, e.toString());
            throw e;
        }
    }

    @Override
    public boolean resolve(String requestId, String behavior, String fromServer) {
        String key = requestId != null ? requestId.toLowerCase() : null;
        Consumer<ChannelResponse> resolver = key == null ? null : pending.remove(key);
        if (resolver == null) {
            if (log.isDebugEnabled()) {
                log.debug("CHANNEL resolve miss (unknown/already resolved): requestId={}",
                    requestId);
            }
            return false;
        }
        // delete-before-call — resolver 抛错/重入时条目已移除（CC channelPermissions.ts:232-236）
        resolver.accept(new ChannelResponse(behavior, fromServer));
        if (log.isInfoEnabled()) {
            log.info("CHANNEL resolve: requestId={} behavior={} fromServer={}",
                requestId, behavior, fromServer);
        }
        return true;
    }

    /** CC truncateForPreview — JSON 预览 200 字符（channelPermissions.ts:160-167）。 */
    private static String truncateForPreview(JsonNode input) {
        try {
            String s = input == null ? "" : input.toString();
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        } catch (Exception e) {
            return "(unserializable)";
        }
    }
}

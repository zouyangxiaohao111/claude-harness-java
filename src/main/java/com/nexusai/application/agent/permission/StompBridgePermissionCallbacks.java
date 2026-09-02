package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.eventbus.ws.BridgePermissionRequestEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridge 竞速回调的 STOMP/WebSocket 生产实现 · 对齐 CC bridgePermissionCallbacks.ts
 * （server-side permission request → CCR / claude.ai 式远程弹窗）。
 *
 * <p><b>WHY</b>（[canUseTool v4] 修复 v3 对抗复验缺口①）：v3 的
 * {@link BridgePermissionCallbacks} 是接口、生产无 @Component 实现 → WebSocketPermissionPrompter
 * {@code @Autowired(required=false)} 注入 null → startBridgeRace 直接 return，CCR 远程弹窗竞速
 * 永不参与。本类把远程表面接到 Java 既有的 STOMP/WebSocket 通道：
 * <ol>
 *   <li>{@link #sendRequest} → 出站推送到
 *       {@code /topic/sessions/{sessionId}/permission-bridge-requests}（远程表面订阅）</li>
 *   <li>远程表面用户 allow/deny → SEND
 *       {@code /app/sessions/{sessionId}/permission-bridge-response} →
 *       {@link com.nexusai.apis.permission.PermissionController} 调 {@link #resolve} → 竞速 future
 *       complete（claim 守卫在 prompter 侧，首个 racer 胜出）</li>
 *   <li>[RV-07] {@link #sendResponse} / {@link #cancelRequest} → 本地 racer 胜出时出站推送
 *       dismiss（对齐 CC bridgePermissionCallbacks.ts:20 sendResponse + interactiveHandler.ts:140-192
 *       onAbort/onAllow/onReject 的 sendResponse + cancelRequest）</li>
 * </ol>
 *
 * <p>pending resolver map 的 delete-before-call 语义对齐 CC channelPermissions.ts:228-238
 * （resolve 后重入返回 false，重复事件 / 网络 dup 被忽略）。
 *
 * @see BridgePermissionCallbacks
 * @see WebSocketPermissionPrompter#startBridgeRace
 * @since canUseTool v4
 */
@Component
public class StompBridgePermissionCallbacks implements BridgePermissionCallbacks {

    private static final Logger log = LoggerFactory.getLogger(StompBridgePermissionCallbacks.class);

    private final SimpMessagingTemplate ws;
    private final Map<String, Consumer<BridgeResponse>> pending = new ConcurrentHashMap<>();
    /** [RV-07] requestId → sessionId（sendResponse/cancelRequest 出站 dismiss topic 路由）。 */
    private final Map<String, String> requestSessions = new ConcurrentHashMap<>();

    /**
     * @param ws STOMP 推送模板 · {@code @Autowired(required=false)} 容错 — 无 WebSocket 场景
     *           （测试 / 纯后端）注入 null，sendRequest 退化为 warn + 不推送（不抛，race 可无害
     *           继续但无远程表面 → 用户/本地决策兜底）。
     */
    @Autowired
    public StompBridgePermissionCallbacks(@Autowired(required = false) SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    /** 出站 bridge 请求 topic · {@code /topic/sessions/{sessionId}/permission-bridge-requests}。 */
    public static String topicFor(String sessionId) {
        return "/topic/sessions/" + sessionId + "/permission-bridge-requests";
    }

    /** [RV-07] 出站 bridge dismiss topic · {@code /topic/sessions/{sessionId}/permission-bridge-dismiss}。 */
    public static String dismissTopicFor(String sessionId) {
        return "/topic/sessions/" + sessionId + "/permission-bridge-dismiss";
    }

    @Override
    public void sendRequest(String sessionId, String requestId, String toolName, JsonNode displayInput,
                            String toolUseId, String description,
                            List<PermissionUpdate> suggestions, String blockedPath) {
        if (sessionId != null) {
            requestSessions.put(requestId, sessionId);
        }
        if (ws == null) {
            log.warn("BRIDGE sendRequest skipped: SimpMessagingTemplate 未注入 (无 WebSocket 通道) requestId={}",
                requestId);
            return;
        }
        BridgePermissionRequestEvent event = BridgePermissionRequestEvent.of(
            sessionId, requestId, toolName, displayInput, toolUseId,
            description, suggestions, blockedPath);
        try {
            ws.convertAndSend(topicFor(sessionId), event);
            if (log.isInfoEnabled()) {
                log.info("BRIDGE sendRequest → topic={} requestId={} tool={} sessionId={}",
                    topicFor(sessionId), requestId, toolName, sessionId);
            }
        } catch (Exception e) {
            // fail-loud: 推送失败让 prompter 的 catch 按 graceful degradation 处理（不挂 tool batch）
            log.error("BRIDGE sendRequest STOMP push failed: requestId={} err={}",
                requestId, e.toString());
            throw e;
        }
    }

    @Override
    public Runnable onResponse(String requestId, Consumer<BridgeResponse> handler) {
        pending.put(requestId, handler);
        return () -> {
            pending.remove(requestId);
            if (log.isDebugEnabled()) {
                log.debug("BRIDGE onResponse 退订: requestId={}", requestId);
            }
        };
    }

    @Override
    public void cancelRequest(String requestId) {
        // 对齐 CC bridgeCallbacks.cancelRequest（interactiveHandler.ts:144/168）：移除 pending
        // 让远程表面不再有 resolve 通道；[RV-07] 同时出站推送 dismiss 让远程表面关闭弹窗。
        if (pending.remove(requestId) != null) {
            if (log.isInfoEnabled()) {
                log.info("BRIDGE cancelRequest: requestId={} (pending resolver removed)", requestId);
            }
        }
        pushDismiss(requestId, "deny", "cancelled");
    }

    @Override
    public void sendResponse(String requestId, BridgeResponse response) {
        if (response == null) {
            if (log.isDebugEnabled()) {
                log.debug("BRIDGE sendResponse: response is null requestId={}", requestId);
            }
            return;
        }
        // 本地 racer 胜出 → 移除 pending resolver（远程表面不再能 resolve）+ 出站 dismiss
        if (pending.remove(requestId) != null) {
            if (log.isDebugEnabled()) {
                log.debug("BRIDGE sendResponse: requestId={} (pending resolver removed)", requestId);
            }
        }
        pushDismiss(requestId, response.behavior(), response.message());
    }

    /**
     * [RV-07] 出站 dismiss 推送 · 对齐 CC sendResponse 语义（CLI → CCR dismiss）。
     *
     * <p>payload = {@code {requestId, behavior, message, updatedInput, updatedPermissions}}；
     * sessionId 经 {@link #requestSessions} 由 requestId 反查。ws 未注入 → warn 不推送。
     */
    private void pushDismiss(String requestId, String behavior, String message) {
        String sessionId = requestSessions.get(requestId);
        if (sessionId == null) {
            if (log.isDebugEnabled()) {
                log.debug("BRIDGE dismiss: 无 sessionId 路由 requestId={}", requestId);
            }
            return;
        }
        if (ws == null) {
            if (log.isDebugEnabled()) {
                log.debug("BRIDGE dismiss: SimpMessagingTemplate 未注入, 跳过 requestId={}", requestId);
            }
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("behavior", behavior);
        if (message != null) {
            payload.put("message", message);
        }
        try {
            ws.convertAndSend(dismissTopicFor(sessionId), payload);
            if (log.isInfoEnabled()) {
                log.info("BRIDGE dismiss → topic={} requestId={} behavior={}",
                    dismissTopicFor(sessionId), requestId, behavior);
            }
        } catch (Exception e) {
            log.warn("BRIDGE dismiss push failed: requestId={} err={}", requestId, e.toString());
        } finally {
            requestSessions.remove(requestId);
        }
    }

    @Override
    public boolean resolve(String requestId, BridgeResponse response) {
        Consumer<BridgeResponse> resolver = pending.remove(requestId);
        if (resolver == null) {
            if (log.isDebugEnabled()) {
                log.debug("BRIDGE resolve miss (unknown/already resolved): requestId={}",
                    requestId);
            }
            return false;
        }
        // delete-before-call — resolver 抛错/重入时条目已移除（CC channelPermissions.ts:232-236）
        resolver.accept(response);
        if (log.isInfoEnabled()) {
            log.info("BRIDGE resolve: requestId={} behavior={}",
                requestId, response != null ? response.behavior() : "(null)");
        }
        return true;
    }
}

package com.nexusai.application.agent.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * CCR 远程会话协调器 · 对齐 CC remote/RemoteSessionManager.ts 实际行为。
 *
 * <p><b>职责</b>（RemoteSessionManager.ts:95-324）:
 * <ul>
 *   <li>WebSocket 订阅（{@link SessionsWebSocket}）— 接收 SDKMessage / control_request /
 *       control_cancel_request / control_response 流；</li>
 *   <li>HTTP POST（{@link RemoteSessionsApi#sendEventToRemoteSession}）— 发送用户消息（TS :219-242）；</li>
 *   <li>权限请求/响应流（pendingPermissionRequests map + respondToPermissionRequest，TS :186-214/:247-282）；</li>
 *   <li>interrupt 信号（cancelSession → control_request subtype=interrupt，TS :294-297）。</li>
 * </ul>
 *
 * <p><b>消息路由</b>（TS :146-184）:
 * <ul>
 *   <li>{@code control_request} → {@link #handleControlRequest}（can_use_tool → onPermissionRequest；
 *       不支持的 subtype → 发 error control_response 防服务端挂起，TS :198-213）；</li>
 *   <li>{@code control_cancel_request} → 清理 pending + onPermissionCancelled（TS :159-172）；</li>
 *   <li>{@code control_response} → 仅日志（ack，TS :175-178）；</li>
 *   <li>其他（isSDKMessage）→ onMessage 转发（TS :180-183）。</li>
 * </ul>
 *
 * <p><b>Java idiom</b>: TS discriminated union → 统一 {@link SessionsWebSocket.SessionsMessage}
 * （type + payload Map）；TS Promise（sendEventToRemoteSession）→ Java 注入式
 * {@link RemoteSessionsApi}；可注入 {@link WebSocketFactory} 以测试驱动消息路由。
 */
public final class RemoteSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RemoteSessionManager.class);

    /** CC RemoteSessionConfig（TS :50-62）— sessionId + getAccessToken + orgUuid + hasInitialPrompt + viewerOnly。 */
    public record RemoteSessionConfig(
        String sessionId,
        Supplier<String> getAccessToken,
        String orgUuid,
        boolean hasInitialPrompt,
        boolean viewerOnly) {
        public RemoteSessionConfig {
            if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为 null");
            Objects.requireNonNull(getAccessToken);
            Objects.requireNonNull(orgUuid);
        }
    }

    /** CC RemotePermissionResponse（TS :40-48）= allow{updatedInput} | deny{message}。 */
    public sealed interface RemotePermissionResponse permits Allow, Deny {
        String behavior();
    }

    /** CC allow 变体 — behavior:'allow' + updatedInput。 */
    public record Allow(Map<String, Object> updatedInput) implements RemotePermissionResponse {
        public String behavior() { return "allow"; }
    }

    /** CC deny 变体 — behavior:'deny' + message。 */
    public record Deny(String message) implements RemotePermissionResponse {
        public String behavior() { return "deny"; }
    }

    /** CC RemoteSessionCallbacks（TS :64-85）。 */
    public interface Callbacks {
        /** 收到 SDKMessage 时触发（TS :66-67） */
        void onMessage(Map<String, Object> message);

        /** 收到 CCR 权限请求时触发（TS :68-71）— request 为 can_use_tool 内层对象 */
        void onPermissionRequest(Map<String, Object> request, String requestId);

        /** 服务端取消挂起权限请求时触发（TS :72-76） */
        default void onPermissionCancelled(String requestId, String toolUseId) {
        }

        default void onConnected() {
        }

        default void onDisconnected() {
        }

        default void onReconnecting() {
        }

        default void onError(Exception error) {
        }
    }

    /** 可注入 WebSocket 工厂（测试用）— 缺省构造真实 SessionsWebSocket。 */
    @FunctionalInterface
    public interface WebSocketFactory {
        SessionsWebSocket create(RemoteSessionConfig config, SessionsWebSocket.Callbacks wsCallbacks);
    }

    private final RemoteSessionConfig config;
    private final Callbacks callbacks;
    private final RemoteSessionsApi sessionsApi;
    private final WebSocketFactory webSocketFactory;
    /** CC pendingPermissionRequests（TS :97-98）— request_id → SDKControlPermissionRequest */
    private final Map<String, Map<String, Object>> pendingPermissionRequests = new ConcurrentHashMap<>();

    private volatile SessionsWebSocket websocket;

    /** CC 构造（config, callbacks）— Java 侧额外注入 sendEventToRemoteSession 传输。 */
    public RemoteSessionManager(RemoteSessionConfig config, Callbacks callbacks, RemoteSessionsApi sessionsApi) {
        this(config, callbacks, sessionsApi, null);
    }

    /** 全注入构造（测试用）。 */
    public RemoteSessionManager(RemoteSessionConfig config, Callbacks callbacks, RemoteSessionsApi sessionsApi,
                                WebSocketFactory webSocketFactory) {
        this.config = Objects.requireNonNull(config);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.sessionsApi = Objects.requireNonNull(sessionsApi);
        this.webSocketFactory = webSocketFactory == null ? defaultFactory() : webSocketFactory;
    }

    // ────────────────────────────────────────────────────────────────────
    // 连接生命周期
    // ────────────────────────────────────────────────────────────────────

    /** CC connect（TS :108-141）— 建 WebSocket 并订阅。 */
    public void connect() {
        log.info("RemoteSessionManager.connect: 连接远程会话 {}", config.sessionId());
        SessionsWebSocket.Callbacks wsCallbacks = new SessionsWebSocket.Callbacks() {
            @Override
            public void onMessage(SessionsWebSocket.SessionsMessage message) {
                handleMessage(message);
            }

            @Override
            public void onClose() {
                callbacks.onDisconnected();
            }

            @Override
            public void onError(Exception error) {
                callbacks.onError(error);
            }

            @Override
            public void onConnected() {
                callbacks.onConnected();
            }

            @Override
            public void onReconnecting() {
                callbacks.onReconnecting();
            }
        };
        this.websocket = webSocketFactory.create(config, wsCallbacks);
        this.websocket.connect();
    }

    /**
     * CC handleMessage（TS :146-184）— 按 type 路由 control_* / SDKMessage。
     */
    private void handleMessage(SessionsWebSocket.SessionsMessage message) {
        String type = message.type();
        // CC :153-157 — control_request（权限提示）
        if ("control_request".equals(type)) {
            handleControlRequest(message.payload());
            return;
        }
        // CC :159-172 — control_cancel_request（服务端取消挂起权限提示）
        if ("control_cancel_request".equals(type)) {
            String requestId = String.valueOf(message.payload().get("request_id"));
            Map<String, Object> pending = pendingPermissionRequests.get(requestId);
            pendingPermissionRequests.remove(requestId);
            String toolUseId = pending != null ? String.valueOf(pending.get("tool_use_id")) : null;
            log.debug("RemoteSessionManager.handleMessage: 权限请求已取消 {}", requestId);
            callbacks.onPermissionCancelled(requestId, toolUseId);
            return;
        }
        // CC :174-178 — control_response（ack 仅日志）
        if ("control_response".equals(type)) {
            log.debug("RemoteSessionManager.handleMessage: 收到 control_response");
            return;
        }
        // CC :180-183 — isSDKMessage 转发
        if (isSDKMessage(message)) {
            callbacks.onMessage(message.payload());
        }
    }

    /**
     * CC handleControlRequest（TS :189-214）— can_use_tool → pending + onPermissionRequest；
     * 其他 subtype → 发 error control_response（防服务端挂起等待回复）。
     *
     * <p>[WF-11 · DC-WF8-02 / OPD-WF8-02-02] 对齐 CC remotePermissionBridge.ts 消费链
     * （useRemoteSession.ts:338 {@code findToolByName(...) ?? createToolStub(...)}）：
     * 远端工具（CCR 容器上运行、本地未加载）经 {@link RemotePermissionBridge#createToolStub(String)}
     * 建 stub 渲染 input 摘要，供权限弹窗展示。
     */
    private void handleControlRequest(Map<String, Object> message) {
        String requestId = String.valueOf(message.get("request_id"));
        Object innerObj = message.get("request");
        if (!(innerObj instanceof Map<?, ?> inner)) {
            sendErrorResponse(requestId, "Unsupported control request subtype: unknown");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> innerMap = (Map<String, Object>) inner;
        String subtype = String.valueOf(innerMap.get("subtype"));
        if ("can_use_tool".equals(subtype)) {
            log.debug("RemoteSessionManager.handleControlRequest: 收到权限请求 tool={}", innerMap.get("tool_name"));
            pendingPermissionRequests.put(requestId, innerMap);
            // [WF-11] 远端未知工具 → createToolStub → renderToolUseMessage（CC remotePermissionBridge.ts 消费链）
            String display = renderPermissionRequestToolUseMessage(innerMap);
            if (log.isDebugEnabled()) {
                log.debug("RemoteSessionManager.handleControlRequest: 远端工具展示摘要 tool={} display={}",
                    innerMap.get("tool_name"), display);
            }
            callbacks.onPermissionRequest(innerMap, requestId);
        } else {
            log.debug("RemoteSessionManager.handleControlRequest: 不支持的 subtype={}", subtype);
            sendErrorResponse(requestId, "Unsupported control request subtype: " + subtype);
        }
    }

    /**
     * [WF-11 · DC-WF8-02 / OPD-WF8-02-02] 为远端工具解析最小 Tool stub · 对齐 CC
     * {@code findToolByName(...) ?? createToolStub(...)}（remotePermissionBridge.ts:53，
     * useRemoteSession.ts:338）——本地未知远端工具（CCR 容器 MCP 工具）回落 stub。
     *
     * @param toolName 远端工具名
     * @return 最小 Tool stub（{@link RemotePermissionBridge#createToolStub(String)}）
     */
    public com.nexusai.application.agent.tool.Tool resolveRemoteToolStub(String toolName) {
        return RemotePermissionBridge.createToolStub(toolName);
    }

    /**
     * [WF-11 · DC-WF8-02 / OPD-WF8-02-02] 渲染远端权限请求的 input 摘要 · 对齐 CC
     * {@code createToolStub(...).renderToolUseMessage(input)}（remotePermissionBridge.ts:57-71）。
     *
     * @param request can_use_tool 内层对象（含 tool_name / input）
     * @return 单行展示摘要（空 input → ""）
     */
    public String renderPermissionRequestToolUseMessage(Map<String, Object> request) {
        String toolName = request != null ? String.valueOf(request.get("tool_name")) : "";
        Object input = request != null ? request.get("input") : null;
        com.nexusai.application.agent.tool.Tool stub;
        try {
            stub = RemotePermissionBridge.createToolStub(toolName);
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("RemoteSessionManager.renderPermissionRequestToolUseMessage: 空工具名 → 空摘要");
            }
            return "";
        }
        return stub.renderToolUseMessage(toJsonNode(input));
    }

    private static com.fasterxml.jackson.databind.JsonNode toJsonNode(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof com.fasterxml.jackson.databind.JsonNode node) {
            return node;
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(input);
    }

    /** CC :204-212 — 未识别 subtype 的 error control_response。 */
    private void sendErrorResponse(String requestId, String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "control_response");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("subtype", "error");
        inner.put("request_id", requestId);
        inner.put("error", error);
        response.put("response", inner);
        SessionsWebSocket ws = websocket;
        if (ws != null) {
            ws.sendControlResponse(response);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 发送 / 权限响应 / 中断
    // ────────────────────────────────────────────────────────────────────

    /**
     * CC sendMessage（TS :219-242）— 用户消息经 HTTP POST
     * {@link RemoteSessionsApi#sendEventToRemoteSession} 送达 CCR。
     *
     * @param content CC RemoteMessageContent（String 或 content block 数组）
     * @return 发送成功与否（CC 返回 boolean）
     */
    public boolean sendMessage(Object content) {
        log.debug("RemoteSessionManager.sendMessage: 发送消息到会话 {}", config.sessionId());
        boolean ok = sessionsApi.sendEventToRemoteSession(config.sessionId(), content, null);
        if (!ok) {
            log.warn("RemoteSessionManager.sendMessage: 发送消息到会话 {} 失败", config.sessionId());
        }
        return ok;
    }

    /**
     * CC respondToPermissionRequest（TS :247-282）— 对挂起权限请求发送 allow/deny 响应。
     * 无对应 pending 请求 → 仅日志（CC :252-259）。
     */
    public void respondToPermissionRequest(String requestId, RemotePermissionResponse result) {
        Map<String, Object> pending = pendingPermissionRequests.get(requestId);
        if (pending == null) {
            log.warn("RemoteSessionManager.respondToPermissionRequest: 无待处理权限请求 id={}", requestId);
            return;
        }
        pendingPermissionRequests.remove(requestId);

        // CC :263-275 — control_response { subtype:'success', request_id, response:{behavior, ...} }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "control_response");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("subtype", "success");
        inner.put("request_id", requestId);
        Map<String, Object> resultBody = new LinkedHashMap<>();
        resultBody.put("behavior", result.behavior());
        if (result instanceof Allow allow) {
            resultBody.put("updatedInput", allow.updatedInput());
        } else if (result instanceof Deny deny) {
            resultBody.put("message", deny.message());
        }
        inner.put("response", resultBody);
        response.put("response", inner);

        log.debug("RemoteSessionManager.respondToPermissionRequest: 发送权限响应 {}", result.behavior());
        SessionsWebSocket ws = websocket;
        if (ws != null) {
            ws.sendControlResponse(response);
        }
    }

    /** CC isConnected（TS :287-289）。 */
    public boolean isConnected() {
        SessionsWebSocket ws = websocket;
        return ws != null && ws.isConnected();
    }

    /** CC cancelSession（TS :294-297）— 发送 interrupt control_request 取消当前请求。 */
    public void cancelSession() {
        log.debug("RemoteSessionManager.cancelSession: 发送中断信号");
        SessionsWebSocket ws = websocket;
        if (ws != null) {
            Map<String, Object> interrupt = new LinkedHashMap<>();
            interrupt.put("subtype", "interrupt");
            ws.sendControlRequest(interrupt);
        }
    }

    /** CC getSessionId（TS :302-304）。 */
    public String getSessionId() {
        return config.sessionId();
    }

    /** CC disconnect（TS :309-314）— 关 WS + 清 pending。 */
    public void disconnect() {
        log.debug("RemoteSessionManager.disconnect: 断开连接");
        SessionsWebSocket ws = websocket;
        if (ws != null) {
            ws.close();
            websocket = null;
            pendingPermissionRequests.clear();
        }
    }

    /** CC reconnect（TS :320-323）— 强制重连（订阅陈旧时用）。 */
    public void reconnect() {
        log.debug("RemoteSessionManager.reconnect: 重连 WebSocket");
        SessionsWebSocket ws = websocket;
        if (ws != null) {
            ws.reconnect();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    /** CC isSDKMessage（TS :22-34）— 非 control_* 消息即 SDKMessage。 */
    static boolean isSDKMessage(SessionsWebSocket.SessionsMessage message) {
        String type = message.type();
        return !"control_request".equals(type)
            && !"control_response".equals(type)
            && !"control_cancel_request".equals(type);
    }

    private static WebSocketFactory defaultFactory() {
        return (config, wsCallbacks) -> new SessionsWebSocket(
            config.sessionId(), config.orgUuid(), config.getAccessToken(), wsCallbacks);
    }

    /** CC createRemoteSessionConfig（TS :329-343）— OAuth token 通道就绪后由调用方构造。 */
    public static RemoteSessionConfig createRemoteSessionConfig(String sessionId,
                                                                Supplier<String> getAccessToken,
                                                                String orgUuid,
                                                                boolean hasInitialPrompt,
                                                                boolean viewerOnly) {
        return new RemoteSessionConfig(sessionId, getAccessToken, orgUuid, hasInitialPrompt, viewerOnly);
    }
}

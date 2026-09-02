package com.nexusai.application.agent.server;

import com.nexusai.application.agent.remote.RemotePermissionBridge;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct-connect session manager · 对齐 CC server/directConnectManager.ts.
 *
 * <p>CC source: server/directConnectManager.ts (213 LOC).
 * DirectConnectSessionManager: WebSocket 双向通信,permission 请求转发,SDK message 中继.
 * - connect() 建立 WS 连接
 * - sendMessage(content) → boolean (WS open 才发)
 * - respondToPermissionRequest(requestId, result) → send control_response
 * - sendInterrupt() → send control_request
 * - disconnect() / isConnected()
 */
public final class DirectConnectSessionManager {

    private static final Logger log = LoggerFactory.getLogger(DirectConnectSessionManager.class);

    public record DirectConnectConfig(String serverUrl, String sessionId, String wsUrl, String authToken) {}
    public record DirectConnectCallbacks(
        Consumer<Map<String, Object>> onMessage,
        BiConsumer<Map<String, Object>, String> onPermissionRequest,
        Runnable onConnected,
        Runnable onDisconnected,
        Consumer<Throwable> onError
    ) {}

    private final DirectConnectConfig config;
    private final DirectConnectCallbacks callbacks;
    private final WebSocketFactory wsFactory;
    private final MessageParser messageParser;
    private final MessageSerializer messageSerializer;
    private final Supplier<String> uuidSupplier;
    private final LoggerSupplier debugLogger;

    private volatile WebSocketWrapper ws;

    public DirectConnectSessionManager(DirectConnectConfig config,
                                         DirectConnectCallbacks callbacks,
                                         WebSocketFactory wsFactory,
                                         MessageParser messageParser,
                                         MessageSerializer messageSerializer,
                                         Supplier<String> uuidSupplier,
                                         LoggerSupplier debugLogger) {
        this.config = Objects.requireNonNull(config);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.wsFactory = Objects.requireNonNull(wsFactory);
        this.messageParser = Objects.requireNonNull(messageParser);
        this.messageSerializer = Objects.requireNonNull(messageSerializer);
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier);
        this.debugLogger = debugLogger;
    }

    @FunctionalInterface public interface WebSocketFactory {
        WebSocketWrapper create(String url, java.util.Map<String, String> headers, WebSocketListener listener);
    }
    @FunctionalInterface public interface MessageParser { Object parse(String line); }
    @FunctionalInterface public interface MessageSerializer { String stringify(Object obj); }
    @FunctionalInterface public interface LoggerSupplier { void log(String msg); }
    public static LoggerSupplier stdoutLogger() { return msg -> {}; }

    public interface WebSocketWrapper {
        void send(String message);
        boolean isOpen();
        void close();
    }

    public void connect() {
        java.util.Map<String, String> headers = new LinkedHashMap<>();
        if (config.authToken() != null && !config.authToken().isEmpty()) {
            headers.put("authorization", "Bearer " + config.authToken());
        }
        ws = wsFactory.create(config.wsUrl(), headers, new WebSocketListener() {
            @Override public void onOpen() {
                if (callbacks.onConnected() != null) callbacks.onConnected().run();
            }
            @Override public void onMessage(String data) {
                handleIncoming(data);
            }
            @Override public void onClose() {
                if (callbacks.onDisconnected() != null) callbacks.onDisconnected().run();
            }
            @Override public void onError(Throwable t) {
                if (callbacks.onError() != null) callbacks.onError().accept(
                    new RuntimeException("WebSocket connection error"));
            }
        });
    }

    @SuppressWarnings("unchecked")
    void handleIncoming(String data) {
        String[] lines = data.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            Object raw;
            try { raw = messageParser.parse(line); }
            catch (Exception e) { continue; }
            if (!(raw instanceof Map)) continue;
            Map<String, Object> parsed = (Map<String, Object>) raw;
            if (!isStdoutMessage(parsed)) continue;

            Object type = parsed.get("type");
            if ("control_request".equals(type)) {
                Map<String, Object> req = (Map<String, Object>) parsed.get("request");
                Object subtype = req != null ? req.get("subtype") : null;
                if ("can_use_tool".equals(subtype)) {
                    String requestId = (String) parsed.get("request_id");
                    // [WF-11 · DC-WF8-02 / OPD-WF8-02-02] 对齐 CC remotePermissionBridge.ts 消费链
                    //   （useDirectConnect.ts:94 findToolByName ?? createToolStub）：远端未知工具
                    //   经 createToolStub → renderToolUseMessage 渲染 input 摘要，供权限弹窗展示。
                    String display = renderPermissionRequestToolUseMessage(req);
                    if (debugLogger != null) {
                        debugLogger.log("[DirectConnect] can_use_tool tool=" + req.get("tool_name")
                            + " display=" + display);
                    }
                    callbacks.onPermissionRequest().accept(req, requestId);
                } else {
                    if (debugLogger != null) {
                        debugLogger.log("[DirectConnect] Unsupported control request subtype: " + subtype);
                    }
                    sendErrorResponse((String) parsed.get("request_id"),
                        "Unsupported control request subtype: " + subtype);
                }
                continue;
            }

            if ("control_response".equals(type) || "keep_alive".equals(type)
                || "control_cancel_request".equals(type) || "streamlined_text".equals(type)) continue;

            if ("system".equals(type)) {
                Object subtype = parsed.get("subtype");
                if ("post_turn_summary".equals(subtype)) continue;
            }
            if ("streamlined_tool_use_summary".equals(type)) {
                // [W9-02 OPD-TS-31] 入站联动 · 不再 skip（CC server/directConnectManager.ts:108
                // 仍跳过，用户拍板 OPD-TS-31 超越 CC：路由到 onMessage 正常消费，供注入上下文）。
                // 消息形状（coreSchemas.ts:1387-1394）：{type:'streamlined_tool_use_summary',
                //   tool_summary, session_id, uuid}。
                if (log.isDebugEnabled()) {
                    log.debug("DirectConnect streamlined_tool_use_summary 路由 onMessage: tool_summary={} · OPD-TS-31",
                        parsed.get("tool_summary"));
                }
            }
            callbacks.onMessage().accept(parsed);
        }
    }

    @SuppressWarnings("unchecked")
    boolean isStdoutMessage(Map<String, Object> value) {
        return value != null
            && value.get("type") instanceof String
            && !((String) value.get("type")).isEmpty();
    }

    /**
     * [WF-11 · DC-WF8-02 / OPD-WF8-02-02] 为远端工具解析最小 Tool stub · 对齐 CC
     * {@code findToolByName(...) ?? createToolStub(...)}（remotePermissionBridge.ts:53，
     * useDirectConnect.ts:94）——本地未知远端工具回落 stub。
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

    public boolean sendMessage(Object content) {
        if (ws == null || !ws.isOpen()) return false;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "user");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("role", "user");
        inner.put("content", content);
        message.put("message", inner);
        message.put("parent_tool_use_id", null);
        message.put("session_id", "");
        ws.send(messageSerializer.stringify(message));
        return true;
    }

    @SuppressWarnings("unchecked")
    public void respondToPermissionRequest(String requestId, Map<String, Object> result) {
        if (ws == null || !ws.isOpen()) return;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subtype", "success");
        response.put("request_id", requestId);
        Object behavior = result.get("behavior");
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("behavior", behavior);
        if ("allow".equals(behavior)) {
            responseBody.put("updatedInput", result.get("updatedInput"));
        } else {
            responseBody.put("message", result.get("message"));
        }
        response.put("response", responseBody);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("type", "control_response");
        outer.put("response", response);
        ws.send(messageSerializer.stringify(outer));
    }

    public void sendInterrupt() {
        if (ws == null || !ws.isOpen()) return;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "control_request");
        request.put("request_id", uuidSupplier.get());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subtype", "interrupt");
        request.put("request", body);
        ws.send(messageSerializer.stringify(request));
    }

    void sendErrorResponse(String requestId, String error) {
        if (ws == null || !ws.isOpen()) return;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subtype", "error");
        response.put("request_id", requestId);
        response.put("error", error);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("type", "control_response");
        outer.put("response", response);
        ws.send(messageSerializer.stringify(outer));
    }

    public void disconnect() {
        if (ws != null) { ws.close(); ws = null; }
    }

    public boolean isConnected() { return ws != null && ws.isOpen(); }

    public interface WebSocketListener {
        void onOpen();
        void onMessage(String data);
        void onClose();
        void onError(Throwable t);
    }
}

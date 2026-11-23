package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP 协议客户端 · 只做「initialize → session/new → session/prompt → session/cancel」
 * 这条主链，以及 server→client 的 {@code session/update}（通知）与
 * {@code session/request_permission}（请求）两条回调。
 *
 * <p>方法名与参数形状取自 ACP schema（{@code @agentclientprotocol/sdk}）：
 * <pre>
 *   initialize   {protocolVersion, clientCapabilities}            → {protocolVersion, agentCapabilities}
 *   session/new  {cwd, mcpServers}                                → {sessionId, configOptions}
 *   session/prompt {sessionId, prompt:[{type:"text",text}]}       → {stopReason}
 *   session/cancel {sessionId}                                    （通知，无响应）
 *   session/update {sessionId, update:{sessionUpdate,...}}        （通知）
 *   session/request_permission {sessionId, toolCall, options[]}   → {outcome:{...}}（请求）
 * </pre>
 *
 * <p>⛔ 不向对端宣告任何可选 client 能力（没有 fs / terminal / elicitation），
 * 外部 agent 必须自给自足 —— 与 DSH 的 {@code clientCapabilities:{}} 对齐。
 */
public final class AcpClient {

    private static final Logger log = LoggerFactory.getLogger(AcpClient.class);

    /** 本客户端支持的 ACP 协议版本。对端返回更高版本时拒绝连接（不猜测兼容性）。 */
    public static final int PROTOCOL_VERSION = 1;

    /** session/update 的消费接口。 */
    @FunctionalInterface
    public interface UpdateListener {
        void onUpdate(JsonNode update);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AcpTransport transport;
    private volatile UpdateListener updateListener;
    private volatile AcpPermissionBridge permissionBridge;
    private volatile String sessionId;

    public AcpClient(AcpTransport transport) {
        this.transport = transport;
        transport.setNotificationHandler(this::onNotification);
        transport.setRequestHandler(this::onRequest);
    }

    public void setUpdateListener(UpdateListener listener) {
        this.updateListener = listener;
    }

    public void setPermissionBridge(AcpPermissionBridge bridge) {
        this.permissionBridge = bridge;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * 握手 + 版本协商。
     *
     * @return initialize 的 result 节点
     * @throws AcpException 对端版本高于本客户端，或响应缺 protocolVersion
     */
    public JsonNode initialize(long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("clientCapabilities", Map.of());
        JsonNode result = transport.request("initialize", params, timeoutMs);
        int negotiated = result == null ? -1 : result.path("protocolVersion").asInt(-1);
        if (negotiated < 0) {
            throw new AcpException("initialize 响应缺少 protocolVersion（对端未按 ACP 协议应答）");
        }
        if (negotiated > PROTOCOL_VERSION) {
            throw new AcpException("对端 ACP 协议版本 " + negotiated
                    + " 高于本客户端支持的 " + PROTOCOL_VERSION + "，拒绝连接");
        }
        log.info("[AcpClient] initialize 完成: negotiated={} agent={}", negotiated,
                result.path("agentInfo").path("name").asText("?"));
        return result;
    }

    /** 建立一次会话。返回 sessionId（同一次委派内的后续 prompt/cancel 都用它）。 */
    public String newSession(Path cwd, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cwd", cwd != null ? cwd.toAbsolutePath().toString()
                : System.getProperty("user.dir", "."));
        params.put("mcpServers", new ArrayList<>());
        JsonNode result = transport.request("session/new", params, timeoutMs);
        String id = result == null ? null : result.path("sessionId").asText(null);
        if (id == null || id.isBlank()) {
            throw new AcpException("session/new 响应缺少 sessionId");
        }
        this.sessionId = id;
        log.info("[AcpClient] session/new 完成: sessionId={}", id);
        return id;
    }

    /**
     * 发一次任务并等待终态。
     *
     * @return session/prompt 的 result 节点（含 stopReason）
     */
    public JsonNode prompt(String text, long timeoutMs) {
        if (sessionId == null) {
            throw new AcpException("session/prompt 前必须先 session/new");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId);
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text == null ? "" : text);
        blocks.add(block);
        params.put("prompt", blocks);
        return transport.request("session/prompt", params, timeoutMs);
    }

    /** 尽力取消当前轮（通知，无响应）；进程收尾仍以 {@link AcpProcess} 为准。 */
    public void cancel() {
        String id = sessionId;
        if (id == null) {
            return;
        }
        try {
            transport.notify("session/cancel", Map.of("sessionId", id));
        } catch (RuntimeException e) {
            log.debug("[AcpClient] session/cancel 发送失败（进程可能已退出）: {}", e.getMessage());
        }
    }

    private void onNotification(String method, JsonNode params) {
        if (!"session/update".equals(method)) {
            if (log.isDebugEnabled()) {
                log.debug("[AcpClient] 忽略未知通知 method={}", method);
            }
            return;
        }
        JsonNode update = params == null ? null : params.get("update");
        if (update == null || update.isNull()) {
            return;
        }
        UpdateListener listener = this.updateListener;
        if (listener != null) {
            listener.onUpdate(update);
        }
    }

    private JsonNode onRequest(String method, JsonNode params) {
        if ("session/request_permission".equals(method)) {
            AcpPermissionBridge bridge = this.permissionBridge;
            if (bridge == null) {
                // 没接审批桥 ⇒ 一律拒绝（绝不放行）
                return cancelledOutcome();
            }
            return bridge.decide(params);
        }
        throw new AcpRpcException(AcpRpcException.METHOD_NOT_FOUND,
                "未实现的 ACP client 方法: " + method);
    }

    /** 构造「用户选择了某个选项」的响应。 */
    public static JsonNode selectedOutcome(String optionId) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode outcome = root.putObject("outcome");
        outcome.put("outcome", "selected");
        outcome.put("optionId", optionId);
        return root;
    }

    /** 构造「取消 / 拒绝」的响应。 */
    public static JsonNode cancelledOutcome() {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("outcome").put("outcome", "cancelled");
        return root;
    }
}

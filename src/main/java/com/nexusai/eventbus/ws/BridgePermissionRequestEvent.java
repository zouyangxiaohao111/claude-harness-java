package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionUpdate;
import java.time.Instant;
import java.util.List;

/**
 * Bridge 权限请求事件 · 服务端 → 远程表面（CCR / claude.ai 式远程弹窗）STOMP 推送。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/permission-bridge-requests}
 *
 * <p><b>WHY</b>（[canUseTool v4] bridge 竞速生产参与）：v3 对抗复验缺口① — bridge 回调接口
 * 生产无 @Component 实现，注入 null → startBridgeRace 直接 return，CCR 远程弹窗竞速永不参与。
 * 本事件是 {@link com.nexusai.application.agent.permission.StompBridgePermissionCallbacks#sendRequest}
 * 的出站载荷 — 远程表面订阅 topic 收到请求，弹窗允许/拒绝后回传
 * {@link BridgePermissionResponseEvent}（STOMP inbound）。
 *
 * <p>字段对齐 CC bridgePermissionCallbacks.ts sendRequest 参数（requestId / toolName / input /
 * toolUseId / description / permissionSuggestions / blockedPath）+ interactiveHandler.ts:245-253。
 *
 * @see BridgePermissionResponseEvent
 * @see com.nexusai.application.agent.permission.StompBridgePermissionCallbacks
 * @see com.nexusai.application.agent.permission.WebSocketPermissionPrompter#startBridgeRace
 * @since canUseTool v4
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BridgePermissionRequestEvent {

    private final String sessionId;
    private final String requestId;
    private final String toolName;
    private final JsonNode displayInput;
    private final String toolUseId;
    private final String description;
    private final List<PermissionUpdate> suggestions;
    private final String blockedPath;
    private final long timestampMs;

    /**
     * @param sessionId   目标 session（出站 topic 路由）
     * @param requestId   bridge 请求 ID（随机 UUID）
     * @param toolName    工具名
     * @param displayInput 展示用输入（CC displayInput = updatedInput ?? ctx.input）
     * @param toolUseId   工具调用 ID（关联取消）
     * @param description 弹窗描述（CC tool.description(input) 产物）
     * @param suggestions 建议的权限更新（可为 null）
     * @param blockedPath 被阻断路径（可为 null）
     */
    public BridgePermissionRequestEvent(String sessionId,
                                        String requestId,
                                        String toolName,
                                        JsonNode displayInput,
                                        String toolUseId,
                                        String description,
                                        List<PermissionUpdate> suggestions,
                                        String blockedPath,
                                        long timestampMs) {
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.toolName = toolName;
        this.displayInput = displayInput;
        this.toolUseId = toolUseId;
        this.description = description;
        this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        this.blockedPath = blockedPath;
        this.timestampMs = timestampMs;
    }

    /** 当前时间戳便捷工厂。 */
    public static BridgePermissionRequestEvent of(String sessionId,
                                                  String requestId,
                                                  String toolName,
                                                  JsonNode displayInput,
                                                  String toolUseId,
                                                  String description,
                                                  List<PermissionUpdate> suggestions,
                                                  String blockedPath) {
        return new BridgePermissionRequestEvent(
            sessionId, requestId, toolName, displayInput, toolUseId,
            description, suggestions, blockedPath, Instant.now().toEpochMilli());
    }

    public String getSessionId() { return sessionId; }
    public String getRequestId() { return requestId; }
    public String getToolName() { return toolName; }
    public JsonNode getDisplayInput() { return displayInput; }
    public String getToolUseId() { return toolUseId; }
    public String getDescription() { return description; }
    public List<PermissionUpdate> getSuggestions() { return suggestions; }
    public String getBlockedPath() { return blockedPath; }
    public long getTimestampMs() { return timestampMs; }
}

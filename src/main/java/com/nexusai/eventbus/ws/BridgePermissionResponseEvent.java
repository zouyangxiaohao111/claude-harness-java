package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Bridge 权限响应事件 · 远程表面 → 服务端 STOMP 发送（inbound）。
 *
 * <p>destination: {@code /app/sessions/{sessionId}/permission-bridge-response}
 * （{@code /app/...} 是 STOMP SEND 前缀，映射到
 * {@code @MessageMapping("/sessions/{sessionId}/permission-bridge-response")}）。
 *
 * <p><b>WHY</b>（[canUseTool v4]）：远程表面（CCR / claude.ai 式弹窗）收到
 * {@link BridgePermissionRequestEvent} 后用户允许/拒绝 → 本事件 SEND → 服务端
 * {@link com.nexusai.apis.permission.PermissionController} 调
 * {@code StompBridgePermissionCallbacks.resolve(requestId, response)} 完成竞速 future —
 * 对齐 CC bridgePermissionCallbacks.ts {@code sendResponse}（server → CC 回传通道）。
 *
 * <p>字段对齐 CC BridgePermissionResponse（bridgePermissionCallbacks.ts:3-8）：
 * behavior（require）+ message? + updatedInput? + updatedPermissions?。
 *
 * <p>[S16] updatedPermissions 为原始 JSON 数组（前端回传建议批准列表，CC 形状
 * {@code {type, destination, rules, behavior}} 判别联合）——由
 * {@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter} 的
 * {@code parseUpdatedPermissions} 转成 {@code PermissionUpdate} 后 apply + persist
 * （避免 Jackson 对 sealed interface 的多态反序列化歧义）。
 *
 * @see BridgePermissionRequestEvent
 * @see com.nexusai.apis.permission.PermissionController
 * @since canUseTool v4
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BridgePermissionResponseEvent {

    private final String requestId;
    private final String behavior;
    private final String message;
    private final JsonNode updatedInput;

    /** [S16] 远程表面批准的权限更新（原始 JSON 数组，CC 形状；可为 null = 未批准规则变更）。 */
    private final List<JsonNode> updatedPermissions;

    /**
     * @JsonCreator + @JsonProperty 让 Jackson 反序列化 STOMP inbound 消息
     * （MappingJackson2MessageConverter 依赖此入口，对齐 MessagePermissionResponseEvent）。
     */
    @JsonCreator
    public BridgePermissionResponseEvent(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("behavior") String behavior,
            @JsonProperty("message") String message,
            @JsonProperty("updatedInput") JsonNode updatedInput,
            @JsonProperty("updatedPermissions") List<JsonNode> updatedPermissions) {
        this.requestId = requestId;
        this.behavior = behavior;
        this.message = message;
        this.updatedInput = updatedInput;
        this.updatedPermissions = updatedPermissions;
    }

    public String getRequestId() { return requestId; }
    public String getBehavior() { return behavior; }
    public String getMessage() { return message; }
    public JsonNode getUpdatedInput() { return updatedInput; }
    public List<JsonNode> getUpdatedPermissions() { return updatedPermissions; }
}

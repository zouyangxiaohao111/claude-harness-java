package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Channel 权限响应事件 · 通道表面 → 服务端 STOMP 发送（inbound）。
 *
 * <p>destination: {@code /app/sessions/{sessionId}/permission-channel-response}
 * （映射到 {@code @MessageMapping("/sessions/{sessionId}/permission-channel-response")}）。
 *
 * <p><b>WHY</b>（[canUseTool v4]）：通道 server 解析用户回复 "yes tbxkq" → 发 structured
 * event（notifications/claude/channel/permission 等价）→ 服务端
 * {@link com.nexusai.apis.permission.PermissionController} 调
 * {@code StompChannelPermissionCallbacks.resolve(requestId, behavior, fromServer)} 完成竞速 —
 * 对齐 CC channelPermissions.ts {@code resolve}（delete-before-call，重复事件被忽略）。
 *
 * <p>字段对齐 CC ChannelPermissionResponse（channelPermissions.ts:40-44）：
 * behavior（allow/deny）+ fromServer（来源通道名）。
 *
 * @see ChannelPermissionRequestEvent
 * @see com.nexusai.apis.permission.PermissionController
 * @since canUseTool v4
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelPermissionResponseEvent {

    private final String requestId;
    private final String behavior;
    private final String fromServer;

    /**
     * @JsonCreator + @JsonProperty 让 Jackson 反序列化 STOMP inbound 消息。
     */
    @JsonCreator
    public ChannelPermissionResponseEvent(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("behavior") String behavior,
            @JsonProperty("fromServer") String fromServer) {
        this.requestId = requestId;
        this.behavior = behavior;
        this.fromServer = fromServer;
    }

    public String getRequestId() { return requestId; }
    public String getBehavior() { return behavior; }
    public String getFromServer() { return fromServer; }
}

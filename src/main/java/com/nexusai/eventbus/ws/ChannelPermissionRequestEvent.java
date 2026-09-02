package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Channel 权限请求事件 · 服务端 → 通道表面（Telegram / iMessage / Discord）STOMP 推送。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/permission-channel-requests}
 *
 * <p><b>WHY</b>（[canUseTool v4] channel 竞速生产参与）：v3 对抗复验缺口① — channel 回调接口
 * 生产无 @Component 实现，注入 null → startChannelRace 直接 return，通道中继竞速永不参与。
 * 本事件是 {@link com.nexusai.application.agent.permission.StompChannelPermissionCallbacks#sendRequest}
 * 的出站载荷 — 通道表面收到后展示 "yes/deny" 回复，用户回复 "yes tbxkq" 由通道 server 解析 →
 * {@link ChannelPermissionResponseEvent}（STOMP inbound）→ resolve 完成竞速。
 *
 * <p>载荷对齐 CC interactiveHandler.ts:334-354（ChannelPermissionRequestParams）：
 * request_id / tool_name / description / input_preview（CC 传 RAW parts，server 按平台格式化）。
 *
 * @see ChannelPermissionResponseEvent
 * @see com.nexusai.application.agent.permission.StompChannelPermissionCallbacks
 * @since canUseTool v4
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelPermissionRequestEvent {

    private final String sessionId;
    private final String requestId;
    private final String toolName;
    private final String description;
    private final String inputPreview;
    private final long timestampMs;

    /**
     * @param sessionId    目标 session（出站 topic 路由）
     * @param requestId    短请求 ID（shortRequestId(toolUseId) 产物）
     * @param toolName     工具名
     * @param description  弹窗描述（CC tool.description(input) 产物）
     * @param inputPreview 截断到 200 字符的 JSON 预览（CC truncateForPreview，phone-sized）
     */
    public ChannelPermissionRequestEvent(String sessionId,
                                         String requestId,
                                         String toolName,
                                         String description,
                                         String inputPreview,
                                         long timestampMs) {
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.toolName = toolName;
        this.description = description;
        this.inputPreview = inputPreview;
        this.timestampMs = timestampMs;
    }

    /** 当前时间戳便捷工厂。 */
    public static ChannelPermissionRequestEvent of(String sessionId,
                                                   String requestId,
                                                   String toolName,
                                                   String description,
                                                   String inputPreview) {
        return new ChannelPermissionRequestEvent(
            sessionId, requestId, toolName, description, inputPreview,
            Instant.now().toEpochMilli());
    }

    public String getSessionId() { return sessionId; }
    public String getRequestId() { return requestId; }
    public String getToolName() { return toolName; }
    public String getDescription() { return description; }
    public String getInputPreview() { return inputPreview; }
    public long getTimestampMs() { return timestampMs; }
}

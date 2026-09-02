package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 流式事件基类 · 所有 server → client 事件都继承此类
 *
 * <p>协议约定（见 {@code docs/api/websocket.md}）：
 * <ul>
 *   <li>JSON 格式：{@code {type, sessionId, userMessageId?, assistantMessageId?, timestamp, ...eventFields}}</li>
 *   <li>所有消息级事件发布到会话级单 topic {@code /topic/sessions/{sessionId}/stream}（对齐 CC 会话
 *       单一事件流；前端按事件 {@code userMessageId}/{@code assistantMessageId} 路由归组）</li>
 *   <li>{@code timestamp} 用 ISO-8601 字符串序列化为 {@code "ts"}（兼容前端 JS 解析）</li>
 * </ul>
 *
 * <p>注：使用抽象类而非 sealed interface，是因为 Spring/Jackson 对 record 子类化
 * 在 polymorphic deserialization 上需要更多配置；这里 server 端只负责序列化，
 * 客户端负责反序列化，子类型用具体类更简单。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class StreamEvent {

    /** 事件类型 · 如 "message.chunk" / "message.complete" */
    private final String type;

    /** 会话 ID */
    private final String sessionId;

    /** 用户消息 ID（触发本轮响应的 user message） */
    @JsonProperty("userMessageId")
    private final String userMessageId;

    /** 服务端时间戳（毫秒）—— 序列化时输出为 "ts" 以兼容前端 */
    @JsonProperty("ts")
    private final long timestamp;

    protected StreamEvent(String type, String sessionId, String userMessageId) {
        this.type = type;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public String getType() { return type; }
    public String getSessionId() { return sessionId; }
    public String getUserMessageId() { return userMessageId; }
    public long getTimestamp() { return timestamp; }
}
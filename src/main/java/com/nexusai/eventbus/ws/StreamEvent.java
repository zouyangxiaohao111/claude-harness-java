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

    /**
     * [2026-10-22 流事件序号 · 对齐 dsh seq] 会话内单调递增事件序号（1 起）。
     *
     * <p>前端据此检测**丢帧缺口**（收到 seq > 已见最大 + 1）→ 缓冲后续事件并重拉尾页补齐
     * （dsh `acceptLiveEvent` / `repairGap`，runtime/src/client/sessions/session.ts:679-733）。
     * 0 = 未分配（非渠道/测试直构）。
     */
    @JsonProperty("seq")
    private long seq;

    protected StreamEvent(String type, String sessionId, String userMessageId) {
        this.type = type;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public String getType() { return type; }
    public long getSeq() { return seq; }
    /** 由 {@link StreamEventSeq} 在发送前统一赋值（幂等：已分配则不覆盖）。 */
    public void setSeq(long seq) { this.seq = seq; }

    public String getSessionId() { return sessionId; }
    public String getUserMessageId() { return userMessageId; }
    public long getTimestamp() { return timestamp; }
}
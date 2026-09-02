package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.eventbus.ws.StreamEvent;

/**
 * 流式响应分片：模型每次吐出的文本增量
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic；assistantMessageId 供前端归位）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageChunkEvent extends StreamEvent {

    /** 当前 assistant 消息 ID（同一 stream 多 chunk 共享） */
    private final String assistantMessageId;

    /** 文本增量片段 */
    private final String delta;

    /** 可选 · 推理模式（think）的推理增量 */
    private final String reasoning;

    public MessageChunkEvent(String sessionId, String userMessageId,
                             String assistantMessageId, String delta, String reasoning) {
        super("message.chunk", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.delta = delta;
        this.reasoning = reasoning;
    }

    public static MessageChunkEvent of(String sessionId, String userMessageId,
                                       String assistantMessageId, String delta) {
        return new MessageChunkEvent(sessionId, userMessageId, assistantMessageId, delta, null);
    }

    public static MessageChunkEvent ofReasoning(String sessionId, String userMessageId,
                                                String assistantMessageId, String reasoning) {
        return new MessageChunkEvent(sessionId, userMessageId, assistantMessageId, null, reasoning);
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public String getDelta() { return delta; }
    public String getReasoning() { return reasoning; }
}
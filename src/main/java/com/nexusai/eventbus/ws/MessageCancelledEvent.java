package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户取消 · 流被客户端主动中断
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageCancelledEvent extends StreamEvent {

    private final String assistantMessageId;

    public MessageCancelledEvent(String sessionId, String userMessageId, String assistantMessageId) {
        super("message.cancelled", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
    }

    public static MessageCancelledEvent of(String sessionId, String userMessageId,
                                           String assistantMessageId) {
        return new MessageCancelledEvent(sessionId, userMessageId, assistantMessageId);
    }

    public String getAssistantMessageId() { return assistantMessageId; }
}
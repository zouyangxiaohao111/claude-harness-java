package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 错误事件 · 模型调用失败 / 流中断 / 鉴权失败等
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageErrorEvent extends StreamEvent {

    private final String assistantMessageId;
    private final String code;       // e.g. "llm_timeout" / "provider_unavailable"
    private final String message;    // human-readable error description

    public MessageErrorEvent(String sessionId, String userMessageId,
                             String assistantMessageId, String code, String message) {
        super("message.error", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.code = code;
        this.message = message;
    }

    public static MessageErrorEvent of(String sessionId, String userMessageId,
                                       String assistantMessageId, String code, String message) {
        return new MessageErrorEvent(sessionId, userMessageId, assistantMessageId, code, message);
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
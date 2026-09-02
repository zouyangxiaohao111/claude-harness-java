package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.eventbus.ws.StreamEvent;

import java.util.Map;

/**
 * 工具调用开始 · Phase 5 v1 不主动发出，DTO 占位
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageToolCallEvent extends StreamEvent {

    private final String assistantMessageId;
    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> arguments;

    public MessageToolCallEvent(String sessionId, String userMessageId,
                                String assistantMessageId, String toolCallId,
                                String toolName, Map<String, Object> arguments) {
        super("message.tool_call", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getArguments() { return arguments; }
}
package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具调用结果 · Phase 5 v1 不主动发出，DTO 占位
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageToolResultEvent extends StreamEvent {

    private final String assistantMessageId;
    private final String toolCallId;
    private final String result;     // truncated result preview
    private final Boolean isError;
    /** 工具调用耗时（毫秒）；前端「调用 N 次工具 · 耗时 7s」用 */
    private final Long durationMs;

    public MessageToolResultEvent(String sessionId, String userMessageId,
                                  String assistantMessageId, String toolCallId,
                                  String result, Boolean isError) {
        this(sessionId, userMessageId, assistantMessageId, toolCallId, result, isError, null);
    }

    public MessageToolResultEvent(String sessionId, String userMessageId,
                                  String assistantMessageId, String toolCallId,
                                  String result, Boolean isError, Long durationMs) {
        super("message.tool_result", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.toolCallId = toolCallId;
        this.result = result;
        this.isError = isError;
        this.durationMs = durationMs;
    }

    public String getAssistantMessageId() { return assistantMessageId; }
    public String getToolCallId() { return toolCallId; }
    public String getResult() { return result; }
    public Boolean getIsError() { return isError; }
    public Long getDurationMs() { return durationMs; }
}
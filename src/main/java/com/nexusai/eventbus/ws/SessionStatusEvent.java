package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 会话状态变化 · 模型思考中 / 流式生成中 / 空闲
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 * （v1 仍沿用 stream topic；v2 可拆出 {@code /topic/sessions/{id}/status}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionStatusEvent extends StreamEvent {

    /** thinking | streaming | idle */
    private final String status;

    public SessionStatusEvent(String sessionId, String userMessageId, String status) {
        super("session.status", sessionId, userMessageId);
        this.status = status;
    }

    public static SessionStatusEvent of(String sessionId, String userMessageId, String status) {
        return new SessionStatusEvent(sessionId, userMessageId, status);
    }

    public String getStatus() { return status; }
}
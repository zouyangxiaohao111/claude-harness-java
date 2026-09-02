package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.eventbus.ws.StreamEvent;

/**
 * 会话标题生成完成 · 由首条用户消息触发
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic）
 *
 * <p>注：协议文档里 title 事件也可发布到 session-level topic（{@code /topic/sessions/{id}/status}），
 * v1 为简单起见统一发到 stream topic。前端订阅时两条 topic 都应监听。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionTitleEvent extends StreamEvent {

    private final String title;

    public SessionTitleEvent(String sessionId, String userMessageId, String title) {
        super("session.title", sessionId, userMessageId);
        this.title = title;
    }

    public static SessionTitleEvent of(String sessionId, String userMessageId, String title) {
        return new SessionTitleEvent(sessionId, userMessageId, title);
    }

    public String getTitle() { return title; }
}
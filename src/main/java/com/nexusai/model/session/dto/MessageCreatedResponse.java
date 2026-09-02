package com.nexusai.model.session.dto;

/**
 * POST /api/v1/sessions/{id}/messages 响应 · 包含订阅 stream topic。
 *
 * <p>[streamTopic-session-level] streamTopic 为<b>会话级</b>单 topic：前端建会话/发消息时订阅一次
 * {@code /topic/sessions/{id}/stream} 即收全部消息流事件（chunk/tool_call/tool_result/complete/error/
 * cancelled），按事件携带的 {@code userMessageId}/{@code assistantMessageId} 路由到对应气泡
 * （对齐 CC 会话单一事件流 + message_start.message.id 归属，CC Web UI 对单会话仅一次 subscribe）。
 *
 * @param userMessageId     用户消息 id
 * @param assistantMessageId 助手消息 id（Phase 4 stub = "msg-stub-pending"）
 * @param streamTopic       会话级流式 topic，形如 "/topic/sessions/{id}/stream"
 * @param queued            [queue-first B6] 是否入队等待（turn 运行中再发 → true；前端不乐观插入
 *                          原文，交给排队框，等当前轮结束后经 queue.drained 补充）
 */
public record MessageCreatedResponse(
    String userMessageId,
    String assistantMessageId,        // Phase 4 stub = "msg-stub-pending"
    String streamTopic,               // 会话级流式 topic，形如 "/topic/sessions/{id}/stream"（对齐 CC 会话单一事件流）
    boolean queued                    // [queue-first B6] busy → true（排队等待补充）
) {}

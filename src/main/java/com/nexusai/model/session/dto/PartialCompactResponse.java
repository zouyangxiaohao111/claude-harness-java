package com.nexusai.model.session.dto;

import java.util.List;

/**
 * POST /api/v1/sessions/{sessionId}/partial-compact 响应 · 对齐 CC REPL.tsx:4950-4972
 * 与前端对接 §7.2 返回：{@code 重组后的消息列表 + 新 conversationId}。
 *
 * <p><b>语义</b>:
 * <ul>
 *   <li>{@code messages} —— 压缩后新消息列表（boundary → ordered → attachments → hooks，
 *       REPL.tsx:4952 {@code postCompact}；Java 已归一化 id/sessionId，前端可直接 setMessages）</li>
 *   <li>{@code conversationId} —— 新 conversationId（REPL.tsx:4971
 *       {@code setConversationId(randomUUID())}，前端 Messages row key 刷新）</li>
 * </ul>
 */
public record PartialCompactResponse(
    /** 重组后的消息列表（对齐 CC REPL.tsx:4964 setMessages(postCompact)） */
    List<ChatMessageDto> messages,
    /** 新 conversationId（对齐 CC REPL.tsx:4971 setConversationId(randomUUID())） */
    String conversationId
) {}

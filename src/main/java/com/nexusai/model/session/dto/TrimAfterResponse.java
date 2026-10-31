package com.nexusai.model.session.dto;

/**
 * DELETE /api/v1/sessions/{sessionId}/messages/after/{messageId} 响应（对话裁剪）。
 *
 * <p><b>WHY 不用 {@link PartialCompactResponse}（轻量契约变更）</b>: 裁剪端点原先回传
 * <b>全量剩余消息 DTO 列表</b>（会话万级消息时 = 几万条 JSON）—— 序列化 + 网络传输本身就是裁剪
 * 卡顿的一环。裁剪后前端改为<b>重拉窗口</b>（GET /messages/turns 按轮分页，毫秒级），
 * 故本端点只需回传两个轻量事实：
 * <ul>
 *   <li>{@code conversationId} —— 旋转后的新 conversationId（对齐 CC REPL.tsx:3673
 *       {@code setConversationId(randomUUID())}；前端作消息 row key 前缀触发整列表 remount）</li>
 *   <li>{@code deletedCount} —— 本次裁掉的<b>消息条数</b>（pivot 起全部，含 pivot 自身）</li>
 * </ul>
 *
 * <p>⚠️ <b>破坏性契约变更</b>: 该端点不再返回 {@code messages}。{@link PartialCompactResponse}
 * 本身<b>未被改动</b>（partial 压缩端点仍在用）—— 本端点改用本 record，避免牵连其它端点。
 */
public record TrimAfterResponse(
    /** 旋转后的新 conversationId（对齐 CC REPL.tsx:3673 setConversationId(randomUUID())） */
    String conversationId,
    /** 本次裁掉的消息条数（pivot 起全部，含 pivot 自身） */
    int deletedCount
) {}

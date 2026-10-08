package com.nexusai.model.session.dto;

import java.time.OffsetDateTime;

/**
 * [dialog-ops-pivot] 对话操作弹窗（压缩/裁剪）候选用户消息 · 轻量出站（无正文全文、无 tool_calls）。
 *
 * <p>语义 = 「当前上下文可见的用户消息」：最后一条 compact_boundary 之后 + preservedSegment 重挂
 * + snip 剔除（判定复用 {@code BoundaryReader.getMessagesAfterCompactBoundary}，与模型所见同源）。
 *
 * @param id            消息 id（partial-compact / trim 的 pivot 实参）
 * @param createdAt     创建时间
 * @param previewSource 正文前 2048 字符（与前端 PREVIEW_SOURCE_CHARACTERS 同值单点约定；
 *                      前端投影成一行 60 字）
 * @param removedAfter  该消息作为裁剪恢复点时将被删除的非 meta 行数（<b>含自身</b>；
 *                      口径 = {@code /messages/count} 同条件 is_meta IS NULL OR != 1）
 */
public record PivotCandidateDto(String id, OffsetDateTime createdAt, String previewSource, int removedAfter) {}

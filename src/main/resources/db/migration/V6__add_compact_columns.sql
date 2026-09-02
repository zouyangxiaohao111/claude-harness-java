-- ===================================================================
-- V6: Add compact-related columns (OD-14 partial compact API D-1)
-- 前置缺口（grep 自验 V1__init_schema.sql:44-72 无这些列）：
--   * sessions.conversation_id   —— 落 REPL.tsx:4971 setConversationId(randomUUID())
--     （partial 压缩后新 conversationId；V6 后为 NULL = 初始未定，前端可用 sessionId 兜底）
--   * messages.subtype          —— BoundaryReader.isCompactBoundaryMessage 按
--     subtype='compact_boundary' 判别（BoundaryReader.java:57-61）、
--     PartialCompactConversation.isCompactSummaryMessage 按 subtype 判别（:407-411）
--   * messages.structured_output —— summary 消息的 summarizeMetadata（JSON 文本）
-- SQLite 一次 ALTER 仅支持单列，故拆三条 ALTER。
-- 新列默认 NULL：存量消息 subtype=null → BoundaryReader 判定不受影响（非 boundary）。
-- ===================================================================

ALTER TABLE sessions ADD COLUMN conversation_id TEXT;
ALTER TABLE messages ADD COLUMN subtype TEXT;
ALTER TABLE messages ADD COLUMN structured_output TEXT;

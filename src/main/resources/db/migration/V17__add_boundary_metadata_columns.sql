-- ===================================================================
-- V17: boundary 元数据列（原 V13 重编号：与 master V13__add_mcp_needs_auth_cache 冲突，merge 2026-08-14）（IMP2-14 △-6/△-16 序列化闭环）
-- 前置缺口（grep 自验 V6__add_compact_columns.sql 无这些列）：
--   * messages.compact_metadata       —— boundary compactMetadata JSON 形状
--     （trigger/preTokens/userContext/messagesSummarized/
--       preCompactDiscoveredTools/preservedSegment，CC messages.ts:4540-4546）
--   * messages.microcompact_metadata  —— microcompact_boundary microcompactMetadata
--     （trigger/preTokens/tokensSaved/compactedToolIds/clearedAttachmentUUIDs，
--       CC messages.ts:4567-4574）
--   * messages.logical_parent_uuid    —— boundary logicalParentUuid（CC messages.ts:4551-4553）
-- 新列默认 NULL：存量消息无 boundary 元数据 → 读回 null → 读侧判定不受影响。
-- SQLite 一次 ALTER 仅支持单列，故拆三条 ALTER。
-- ===================================================================

ALTER TABLE messages ADD COLUMN compact_metadata TEXT;
ALTER TABLE messages ADD COLUMN microcompact_metadata TEXT;
ALTER TABLE messages ADD COLUMN logical_parent_uuid TEXT;

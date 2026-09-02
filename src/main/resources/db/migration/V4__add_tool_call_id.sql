-- ===================================================================
-- V4: Add 'tool_call_id' column to messages (Phase 6·s02)
-- For role=tool messages, this links back to tool_calls.id.
-- Optional for non-tool messages (NULL by default).
-- ===================================================================

ALTER TABLE messages ADD COLUMN tool_call_id TEXT;
CREATE INDEX idx_messages_tool_call ON messages(tool_call_id);

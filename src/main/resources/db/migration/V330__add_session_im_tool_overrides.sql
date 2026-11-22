-- V330: add_session_im_tool_overrides
-- IM 工具增量开关独立存储，避免把 disabled_tools 的动态默认集固化为显式全集。
-- null 表示没有增量覆盖；SendUserMessage 仍仅由 brief_mode 表达。
-- 本列由 SessionMapper 专用原子 SQL 管理，不参与 SessionRecord 的全行更新。
ALTER TABLE sessions ADD COLUMN im_tool_overrides TEXT;

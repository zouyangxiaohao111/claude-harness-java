-- ===================================================================
-- V328: sessions_derived_session（SessionCreate 派生会话溯源）
--
-- 背景（SessionCreate 工具 · 派生会话）：
--   模型可以创建一个同级 top-level 会话（peer），之后用 SendMessage 继续对话。
--   为了做防护（单父派生数上限、派生深度上限）并在左栏区分「派生的会话」，
--   需要把「这条会话是由哪个会话创建的」落库。
--
-- 语义：
--   derived_from_session_id — 创建者（父）会话 id；null = 用户自己建的普通会话。
--   derived_depth           — 派生深度：用户建的会话 = null/0，父=0 派生的会话 = 1，
--                             再派生 = 2（SessionCreate 拒绝更深，见 SessionCreateTool）。
--   两列都只由 SessionCreateTool 写入，读侧按 nullable 判定（⛔ 不靠标题/文本猜）。
--
-- 索引：按 parent 统计派生数（每个父会话的派生上限）走本索引。
--
-- 版本号说明：目录最大 V327、真库 flyway_schema_history 最大 V326 ⇒ 取 V328。
--   ⛔ 不修改历史迁移。
-- ===================================================================
ALTER TABLE sessions ADD COLUMN derived_from_session_id TEXT;
ALTER TABLE sessions ADD COLUMN derived_depth INTEGER;

CREATE INDEX IF NOT EXISTS idx_sessions_derived_from
    ON sessions(derived_from_session_id);

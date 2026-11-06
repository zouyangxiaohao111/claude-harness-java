-- ===================================================================
-- V276 · 产物/来源归属到「轮次」（用户消息）
--
-- 背景（2026-11-05 用户裁定）：
--   原先 session_artifact 的唯一键是 (session_id, kind, title)，
--   同一份文件在**不同轮**被改过时会被 UPSERT 成同一行 ⇒ 产物「搬家」到最新一轮，
--   历史轮次看起来凭空少了一个文件。
--   ⇒ 改成「一轮一行」：同一文件在第 3 轮生成、第 7 轮修改 = 两行，各归各轮。
--
-- ⚠️ 为什么用表达式索引 COALESCE(source_message_id, '')：
--   SQLite 的唯一索引里 **NULL 不等于 NULL** ⇒ 历史行（归属未知）留 NULL 时，
--   同一 (session, kind, title) 可以插无数行。把 NULL 折成空串才受约束。
--   代码侧 upsert 的查询条件也按同一口径（NULL → IS NULL，非空 → = ?）。
--
-- ⚠️ 历史行不猜：source_message_id 保持 NULL（如实标注「归属未知」），
--   不按时间戳/标题去反推 —— 那会伪造归属，比没有归属更难排查。
-- ===================================================================

-- 旧唯一键（同会话内 kind+title 唯一，跨轮会互相覆盖）
DROP INDEX IF EXISTS uk_session_artifact;

-- 新唯一键：同会话 + 同一轮 + kind + title 唯一（不同轮各自一行）
CREATE UNIQUE INDEX IF NOT EXISTS uk_session_artifact_turn
    ON session_artifact (session_id, COALESCE(source_message_id, ''), kind, title);

-- 按消息取产物（消息级列表 / 轮次分组展示）
CREATE INDEX IF NOT EXISTS idx_session_artifact_msg
    ON session_artifact (session_id, source_message_id, kind);

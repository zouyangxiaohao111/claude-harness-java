-- V329: add_sessions_unattended_brief_mode
--
-- 背景：
--   无人值守（R15/R16）与会话 Brief（R19）需要**会话级显式状态**：
--   - 现状「无人值守」是 im_chat.bound_session_id 的隐式副作用（绑定渠道即摘 Ask），
--     既不能手动开关，也会把交互式会话误判成无人值守（与 CC tfi() 不一致）。
--   - 现状 Brief 只有进程级 kill-switch（nexusai.brief.enabled），没有会话状态，
--     导致 SendUserMessage 无法按会话 opt-in。
--   本迁移为 sessions 增加两列承载（对齐 V31 effort_level / V33 bare_mode / V75 coordinator_mode
--   的会话列范式；Web 多会话禁止用进程/ThreadLocal 存会话态）。
--
-- 语义：
--   unattended_mode  INTEGER 可空：1=本会话显式无人值守；0/null=否。
--   brief_mode       INTEGER 可空：1=本会话 Brief（SendUserMessage）开启；0/null=否。
--   两列都只按 sessionId 读写，跨会话互不影响；null 视为「未开启」。
--
-- 影响：sessions 表新增两列；均带默认 NULL，旧行读侧按 0 处理，可回滚（DROP COLUMN）。
-- 注意：迁移编号只增不减，已发布迁移的编号与文件名永不改动。

ALTER TABLE sessions ADD COLUMN unattended_mode INTEGER;
ALTER TABLE sessions ADD COLUMN brief_mode INTEGER;

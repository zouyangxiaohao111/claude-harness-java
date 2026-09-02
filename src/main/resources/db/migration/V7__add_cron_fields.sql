-- ===================================================================
-- V7: Add cron fields (CRON-B1) — schedules.created_at / schedules.permanent
-- 对齐 CC CronTask 契约（grep 自验 Open-ClaudeCode/src/utils/cronTasks.ts）：
--   * created_at —— CC original: CronTask.createdAt (cronTasks.ts:37)
--     必填 number（epoch ms），写盘必有 (cronTasks.ts:208 createdAt: Date.now())；
--     missed 判定锚点 nextCronRunMs(t.cron, t.createdAt) (cronTasks.ts:455)。
--   * permanent  —— CC original: CronTask.permanent (cronTasks.ts:57) 可选布尔，
--     豁免 recurringMaxAgeMs 自动过期 (cronScheduler.ts:59 !t.permanent)。
-- SQLite 一次 ALTER 仅支持单列，故拆两条 ALTER。
-- 新列默认 NULL：存量行 created_at=NULL → 暂不老化（直至 CRON-B2 写入 createdAt）。
-- permanent 默认 0=非豁免，与 CC 缺省语义一致，不破坏既有 insert/update。
-- agent_id 预留位归收尾 D4（本 session 不加，避免与 WF-D 写集重叠）。
-- ===================================================================

ALTER TABLE schedules ADD COLUMN created_at INTEGER;
ALTER TABLE schedules ADD COLUMN permanent INTEGER NOT NULL DEFAULT 0;

-- ===================================================================
-- V8: Add schedule scope columns (CRON-B2) — schedules.scope / schedules.session_id
-- 对齐 CC CronTask 契约（grep 自验 Open-ClaudeCode/src/utils/cronTasks.ts）：
--   * scope     —— CC original: CronTask.durable (cronTasks.ts:57)。durable=false →
--     session-scoped（addSessionCronTask 仅存进程内存，cronTasks.ts:211-213）；
--     durable=true → 落盘（writeCronTasks strip durable，cronTasks.ts:175）。
--     Java 侧用户拍板维持 SQLite 只补字段（OPD-Cron-02）：SESSION 任务仍落库 +
--     Quartz 注册（修 R-1 僵尸），scope 列落库使重启后 selectAll 可辨识。
--     取值：PERSISTENT | SESSION（CronCreateTool:160 durable ? PERSISTENT : SESSION）。
--   * session_id —— CC original: CronTask.agentId (cronTasks.ts:213 + state.ts:1298)。
--     Java 工具层把 teammate agentId 映射为 ctx.sessionId（CronCreateTool:163-170）。
--     PERSISTENT 任务为 NULL。
-- SQLite 一次 ALTER 仅支持单列，故拆两条 ALTER。
-- 新列默认 NULL：存量行 scope=NULL → listAll/lookupScope 回退 sessionJobs 内存索引兜底。
-- agent_id 预留位归收尾 D4（本 session 不加，避免与 WF-D 写集重叠）。
-- ===================================================================

ALTER TABLE schedules ADD COLUMN scope TEXT;
ALTER TABLE schedules ADD COLUMN session_id TEXT;

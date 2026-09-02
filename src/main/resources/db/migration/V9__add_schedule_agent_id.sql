-- ===================================================================
-- V9: Add schedule agent_id column (CRON-D4) — schedules.agent_id
-- 对齐 CC CronTask 契约（grep 自验 Open-ClaudeCode/src/utils/cronTasks.ts）：
--   * agent_id —— CC original: CronTask.agentId (cronTasks.ts:69 字段声明；
--     cronTasks.ts:212 addSessionCronTask 按 agentId 条件透传
--     { ...task, ...(agentId ? { agentId } : {}) })。
--     CC 语义：agentId 仅存于 durable=false（session-only）任务（CronCreateTool.ts:126
--     getTeammateContext()?.agentId；durable 路径 push task 不含 agentId，cronTasks.ts:215-217）。
--     Java 侧用户拍板（OPD-D4-GAP-5 方案 A）：ScheduleRecord.agentId 落库 +
--     CronCreateTool create 填充 + ScheduleService.toDto 透传。
-- SQLite 一次 ALTER 仅支持单列。
-- 新列默认 NULL：存量行 agent_id=NULL → fire 侧走 lead 入队路径（CC onFireTask
-- useScheduledTasks.ts:92 if(task.agentId) 不成立）。
-- ===================================================================

ALTER TABLE schedules ADD COLUMN agent_id TEXT;

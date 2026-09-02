-- ===================================================================
-- V21: Drop schedules.enabled column and idx_schedules_enabled (IMPL-07 / CAND-1)
-- 对齐 CC CronTask 无任务级 enabled 概念（grep 自验 Open-ClaudeCode/src/utils/cronTasks.ts）：
--   * CronTask 类型仅 {id,cron,prompt,createdAt,lastFiredAt?,recurring?,permanent?,
--     durable?,agentId?}，无 enabled 字段（cronTasks.ts:30-70）；
--   * 文件格式注释 '{ tasks: [{ id, cron, prompt, createdAt, recurring?, permanent? }] }'
--     亦无 enabled（cronTasks.ts:10）；
--   * cronScheduler.ts 唯一 enabled 命中为 getScheduledTasksEnabled()（全局调度器
--     启停开关，bootstrap/state.ts:1272-1276，非任务字段）；
--   * addCronTask 写入路径（cronTasks.ts:194-219 append + writeCronTasks :165-182
--     落盘形状）不写任何 enabled 字段。
-- Java 侧该字段链零执行语义消费（写入后 registerSchedule/buildTrigger/TestJob/
-- reconcile/missed 均不读 getEnabled），属纯 Java 独有死字段 → 整链删除
-- （ScheduleRecord.enabled / ScheduleDto.enabled / 两个 Request.enabled / create
-- 写入 / toDto 透传 / CronCreateTool 位置参数）。
-- V20 预告兑现：V20__drop_schedules_name_unique.sql:19 注释明言
-- 'enabled/permanent 保留原 DEFAULT，归 IMPL-07 后续处理，本次不动'。
-- SQLite 不支持 DROP COLUMN 前版本依赖（沿用 V20 表重建模式，单 ALTER 约束）：
--   新表 schedules_new（15 列 = V20 的 16 列去 enabled）
--   → INSERT INTO ... SELECT 显式 15 列（列名/顺序逐列对照，防生产数据丢失）
--   → DROP TABLE schedules（连带旧表所属 idx_schedules_enabled 一并删除）
--   → ALTER TABLE schedules_new RENAME TO schedules
--   → 不再重建 idx_schedules_enabled。
-- ===================================================================

CREATE TABLE schedules_new (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  kind              TEXT NOT NULL,              -- 'cron'|'once'|'interval'
  cron              TEXT,
  interval_seconds  INTEGER,
  run_at            TEXT,
  command           TEXT NOT NULL,
  description       TEXT,
  last_run_at       TEXT,
  last_run_status   TEXT,
  created_at        INTEGER,
  permanent         INTEGER NOT NULL DEFAULT 0,
  scope             TEXT,
  session_id        TEXT,
  agent_id          TEXT
);

INSERT INTO schedules_new (id, name, kind, cron, interval_seconds, run_at, command,
                           description, last_run_at, last_run_status,
                           created_at, permanent, scope, session_id, agent_id)
SELECT id, name, kind, cron, interval_seconds, run_at, command,
       description, last_run_at, last_run_status,
       created_at, permanent, scope, session_id, agent_id
FROM schedules;

DROP TABLE schedules;

ALTER TABLE schedules_new RENAME TO schedules;

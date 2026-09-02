-- ===================================================================
-- V20: Drop UNIQUE constraint on schedules.name (IMPL-06 / NEW-5)
-- 对齐 CC addCronTask 无 name/无去重（grep 自验 Open-ClaudeCode/src/utils/cronTasks.ts）：
--   * CronTask 类型仅 {id,cron,prompt,createdAt,lastFiredAt?,recurring?,permanent?,
--     durable?,agentId?}，无 name 字段（cronTasks.ts:30-70）；
--   * addCronTask 按 id 追加，无任何 name 写入/去重检查（cronTasks.ts:194-219）
--     → 同 cron 二次创建 CC 两条均 fire（removeCronTasks 仅按 idSet 过滤，:231-248，
--     任务身份语义 = id，无 name 维度）。
-- 用户拍板 NEW-5（09-open-decisions.md）+ deletion-manifest.md DEL-UNIQUE：
--   * V1:117 的 UNIQUE 约束删除（Flyway 历史文件不可改，V1 原文保留为基线，
--     本迁移在 fresh DB 上生效 → 新库无 UNIQUE）；
--   * name 保留 NOT NULL，退化为 Java REST 契约展示字段（ScheduleCreateRequest
--     @Size(max=64) 必填），无唯一性语义；
--   * REST 同名不再撞 DataIntegrityViolation（旧 500 → 同 name 不同 id 均 201 落库，
--     重复创建走正常路径）。
-- SQLite 不支持 DROP CONSTRAINT（一次 ALTER 仅支持单操作），故表重建：
--   新表 schedules_new（16 列 = V1:115-127 的 11 列 + V7 created_at/permanent +
--   V8 scope/session_id + V9 agent_id；name TEXT NOT NULL 去 UNIQUE；
--   enabled/permanent 保留原 DEFAULT，归 IMPL-07 后续处理，本次不动）
--   → INSERT INTO ... SELECT 显式 16 列（列名/顺序逐列对照，防生产数据丢失）
--   → DROP TABLE schedules（连带旧表所属 idx_schedules_enabled 一并删除）
--   → ALTER TABLE schedules_new RENAME TO schedules
--   → 重建 CREATE INDEX idx_schedules_enabled。
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
  enabled           INTEGER NOT NULL DEFAULT 1,
  last_run_at       TEXT,
  last_run_status   TEXT,
  created_at        INTEGER,
  permanent         INTEGER NOT NULL DEFAULT 0,
  scope             TEXT,
  session_id        TEXT,
  agent_id          TEXT
);

INSERT INTO schedules_new (id, name, kind, cron, interval_seconds, run_at, command,
                           description, enabled, last_run_at, last_run_status,
                           created_at, permanent, scope, session_id, agent_id)
SELECT id, name, kind, cron, interval_seconds, run_at, command,
       description, enabled, last_run_at, last_run_status,
       created_at, permanent, scope, session_id, agent_id
FROM schedules;

DROP TABLE schedules;

ALTER TABLE schedules_new RENAME TO schedules;

CREATE INDEX idx_schedules_enabled ON schedules(enabled);

-- ===================================================================
-- V306: 定时任务「执行上下文形态」+ 通知目标 + 运行记录
--
-- 背景：定时任务原先只有 scope（SESSION / DURABLE）两态，无法表达
--   「每次触发用哪个会话」。用户 2026-11-12 定了四种形态（A/B/C/D）：
--
--     A 绑定已有会话   —— 写进用户选的那个会话，共享其历史
--     B 每次触发新建   —— 每次一个全新会话，零历史，30 天后清理
--     C 新建后绑定住   —— 首次触发造一个专属会话，之后一直用它；
--                        **任务删除时才删会话**（不适用 30 天规则）
--     D 不绑定会话     —— 后台静默执行，不产生会话
--
-- ⚠️ 为什么用独立列 session_mode 而不是给 scope 加枚举值：
--   1) ScheduleService.lookupScope 遇未知 scope 值会**静默回落 DURABLE**
--      （末行 return ScheduleScope.DURABLE），加了新值也不报错；
--   2) sweepSessionTasksAtStartup 会删「scope=SESSION 且会话不活跃」的行
--      —— 新形态若复用 SESSION，重启一次任务就没了。
--   scope 表达「生命周期」，session_mode 表达「执行上下文形态」，两者正交。
--
-- ⚠️ 缺省 REUSE（= 现在的 DURABLE 行为），存量行语义零变化。
--
-- notify_chats：通知目标，存 **im_chat.id 数组**（聊天级，不是平台名）。
--   只存渠道名（wechat）不知道发给张三还是刘工 —— 这正是旧表
--   mcp_channel_binding 只到渠道级的老问题（该表已废弃，出站权威源 = im_chat）。
--   「通知本人」= im_chat 里 chat_kind='p2p' 且 peer 是用户自己那条。
--   两级选择（先渠道再聊天），均可多选。
--
-- notify_channel_ids：冗余存渠道 id，供列表展示省一次 join。
--
-- ⚠️ 本文件注释不得出现 Flyway 占位符字面量（application.yml 未关 placeholder-replacement）。
-- ===================================================================

-- SQLite 一次 ALTER 仅支持单列
ALTER TABLE schedules ADD COLUMN session_mode TEXT DEFAULT 'REUSE';
ALTER TABLE schedules ADD COLUMN notify_chats TEXT;
ALTER TABLE schedules ADD COLUMN notify_channel_ids TEXT;

CREATE INDEX IF NOT EXISTS idx_schedules_session_mode ON schedules(session_mode);

-- -------------------------------------------------------------------
-- 运行记录：每次触发一行
--
-- 为什么不是 QRTZ 表：QRTZ_JOB_DETAILS 只有 job 定义（无结果列），
-- QRTZ_FIRED_TRIGGERS 只存「正在执行」的触发且 Quartz 触发完即删行
-- —— 它是运行时锁表，不是历史表。实测建表脚本在
-- backend/src/main/resources/org/quartz/impl/jdbcjobstore/tables_sqlite.sql。
--
-- 为什么需要它：现状 lastRunStatus='ok' 是**入队那一刻**写的
-- （TestJob.applyFireLifecycle → ScheduleService.markFired），
-- agent loop 之后才异步跑 ⇒ 跑崩了仍显示「成功」（假信号）。
-- 本表记录 run 真正结束时的结果，并保留输出摘要供「运行记录」展示。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schedule_runs (
  id                  TEXT PRIMARY KEY,
  schedule_id         TEXT NOT NULL,           -- 指向 schedules.id（任务删除后置空保留历史）
  schedule_name       TEXT NOT NULL,           -- 冗余存任务名（任务删了也能看懂）

  session_mode        TEXT NOT NULL,           -- 本轮实际用的形态（降级后的值，非创建时配置）
  trigger_source      TEXT NOT NULL DEFAULT 'quartz',  -- quartz | manual（runNow）

  status              TEXT NOT NULL,           -- running | ok | error | cancelled
  started_at          TEXT NOT NULL,
  finished_at         TEXT,
  duration_ms         INTEGER,

  session_id          TEXT,                    -- 本轮实际用的会话（D 形态为空）
  prompt              TEXT,                    -- 本轮执行的 prompt（截断后）
  output_summary      TEXT,                    -- 结果摘要（AgentState.lastAssistant 截断）
  error               TEXT,                    -- 失败原因（AgentState.lastError / 异常）

  notify_result_json  TEXT,                    -- [{"chatId","chatName","platform","ok","error"}]

  created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_schedule_runs_schedule ON schedule_runs(schedule_id);
CREATE INDEX IF NOT EXISTS idx_schedule_runs_started  ON schedule_runs(started_at);
-- 30 天清理器按 started_at 扫
CREATE INDEX IF NOT EXISTS idx_schedule_runs_status   ON schedule_runs(status);

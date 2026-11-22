-- ===================================================================
-- V303: IM 扫描规则表（微信聊天记录定时扫描）
--
-- 背景：用户要求「定时扫描本机微信记录 → 命中关键词 → 推给我确认 → 确认后 AI 执行」。
--   规则 = 什么时候看（freq）× 看哪里（sessions + keywords + window）×
--          怎么找我（targets + confirm_mode）× 谁来干（exec_mode + exec_session_id）。
--
-- ⚠️ 五个要素全部**显式配置**，不做默认猜测（用户裁定）：
--   规则会触发真实动作（唤醒 agent、往 IM 发消息），任何一项靠猜都可能误发/误执行。
--   因此本表除 created_at/updated_at 外，没有「留空就猜一个」的列。
--
-- ⚠️ sessions_json 存的是「用户勾选的会话标识」：
--   来自已同步的本机微信记录（wechat_conversation.conversation_id 或 display_name）。
--   使用 JSON 数组而非关联表：规则与会话是「一份配置里的多值」，不需要反查/级联删除。
--
-- ⚠️ 执行目标（exec_mode）是设计评审时补的字段：
--   扫描规则是**全局配置**、不属于任何会话，所以必须显式指定命中后由谁处理。
--   new      = 每次扫描产物开一个独立任务（默认）
--   existing = 追加进 exec_session_id 指向的既有会话
--
-- ⚠️ 本文件注释不得出现 Flyway 占位符字面量（application.yml 未关 placeholder-replacement）。
-- ===================================================================
CREATE TABLE IF NOT EXISTS im_scan_rule (
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  enabled          INTEGER NOT NULL DEFAULT 1,

  -- 频率
  freq_kind        TEXT NOT NULL DEFAULT 'daily',        -- daily | hourly | weekly | custom
  freq_value       TEXT,                                 -- '09:00' / '2h' / 'MON 09:00'
  cron_expr        TEXT,                                 -- freq_kind=custom 时使用

  -- 看哪里
  sessions_json    TEXT NOT NULL DEFAULT '[]',           -- ["美式小家庭","上市集团"]
  keywords_json    TEXT NOT NULL DEFAULT '[]',           -- ["合同","截止"]；空数组 = 有新增就命中
  window_kind      TEXT NOT NULL DEFAULT '24h',          -- 24h | 7d | all

  -- 怎么找我
  targets_json     TEXT NOT NULL DEFAULT '[]',           -- [{"platform":"wechat"}]
  confirm_mode     TEXT NOT NULL DEFAULT 'rich',         -- rich | text

  -- 谁来干
  exec_mode        TEXT NOT NULL DEFAULT 'new',          -- new | existing
  exec_session_id  TEXT,
  timeout_minutes  INTEGER NOT NULL DEFAULT 120,         -- 超时未确认 → expired，不自动执行

  -- 运行态
  last_run_at      TEXT,
  last_hit_count   INTEGER,

  created_at       TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at       TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_im_scan_rule_enabled ON im_scan_rule(enabled);

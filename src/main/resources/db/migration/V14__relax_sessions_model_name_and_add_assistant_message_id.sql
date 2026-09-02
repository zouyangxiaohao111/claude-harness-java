-- ===================================================================
-- V14: 修复 Bug1(messages 缺 assistant_message_id 列) + Bug2(sessions.model_name NOT NULL)
-- 合并 T1.1 + T2.1 于同一版本文件，避免版本号冲突。
-- ===================================================================
-- ⚠️ 必须脱离事务执行：应用 JDBC URL 硬编码 foreign_keys=on（application.yml:39/101），
-- 且 messages/session_files 带 ON DELETE CASCADE 指向 sessions。若本脚本在事务内执行，
-- PRAGMA foreign_keys=OFF 为静默 no-op，DROP TABLE sessions 将触发隐式 DELETE 级联
-- 清空全部 messages 与 session_files（SQLite 实测复现）。
-- 脱离事务的指令不在本文件内（--executeInTransaction=false 头不是合法机制），
-- 由同名伴生文件 V14__relax_sessions_model_name_and_add_assistant_message_id.sql.conf
-- 提供（内容 executeInTransaction=false，裸 key=value）。见 §1.3 与 §4 第 2 条验证。

PRAGMA foreign_keys=OFF;

-- T1.1: messages 加列（对齐 CC sourceToolAssistantUUID 落库；MyBatis-Flex
-- camelCase→snake_case 自动映射，全字段 insert 命中该列，null 可插）
ALTER TABLE messages ADD COLUMN assistant_message_id TEXT;

-- T2.1: sessions 表重建放宽 model_name 为可空（SQLite 不能 ALTER DROP NOT NULL，
-- 采用 新建+COPY+DROP+RENAME 四步；保留 V1:44-56 全部列 + V6 conversation_id）
CREATE TABLE sessions_new (
  id              TEXT PRIMARY KEY,
  model_tag       TEXT NOT NULL,
  model_name      TEXT,                          -- 放宽：NOT NULL → 可空（对齐 CC session 不持久化每 session model）
  title           TEXT NOT NULL,
  time            TEXT NOT NULL,
  session_group   TEXT NOT NULL,
  tab_id          TEXT,
  main_project_id TEXT,
  message_count   INTEGER NOT NULL DEFAULT 0,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now')),
  conversation_id TEXT                            -- V6 已加列，重建必须保留
);

INSERT INTO sessions_new (id, model_tag, model_name, title, time, session_group,
                          tab_id, main_project_id, message_count, created_at, updated_at,
                          conversation_id)
SELECT id, model_tag, model_name, title, time, session_group,
       tab_id, main_project_id, message_count, created_at, updated_at,
       conversation_id
FROM sessions;

DROP TABLE sessions;
ALTER TABLE sessions_new RENAME TO sessions;
CREATE INDEX idx_sessions_updated ON sessions(updated_at DESC);

-- 恢复 FK 强制（本脚本脱离事务执行，PRAGMA 状态会保留在连接上；该连接回收回 Hikari 池
-- 后，URL 里的 foreign_keys=on 只在新建连接生效，故此处必须显式恢复，防应用期 FK 静默关闭）
PRAGMA foreign_keys=ON;

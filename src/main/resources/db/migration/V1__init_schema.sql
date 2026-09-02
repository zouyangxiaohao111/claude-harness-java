-- ===================================================================
-- V1: NexusAI 初始化 schema
-- 11 张业务表 + 1 张工具调用记录表
-- 时间统一用 TEXT（ISO 8601 / datetime('now')），与 java.time.String 字段映射
-- ===================================================================

-- -------- providers --------
CREATE TABLE providers (
  id              TEXT PRIMARY KEY,
  name            TEXT UNIQUE NOT NULL,
  type            TEXT NOT NULL DEFAULT 'openai_compatible',
  base_url        TEXT NOT NULL,
  api_key_hash    TEXT NOT NULL,                -- SHA-256
  api_key_masked  TEXT NOT NULL,                -- 'sk-****4f2a'
  extra_headers   TEXT,                         -- JSON
  enabled         INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_providers_enabled ON providers(enabled);

-- -------- models --------
CREATE TABLE models (
  id              TEXT PRIMARY KEY,
  provider_id     TEXT NOT NULL,
  name            TEXT NOT NULL,
  alias           TEXT,
  tag             TEXT NOT NULL,                -- 'DS' | 'CL' | 'GP' | 'QW'
  description     TEXT,
  type            TEXT NOT NULL DEFAULT 'chat',
  max_tokens      INTEGER NOT NULL DEFAULT 65536,
  temperature     REAL DEFAULT -1,              -- -1 sentinel
  top_p           REAL,                         -- null = use provider default
  context_window  INTEGER NOT NULL DEFAULT 512000,
  think           TEXT DEFAULT '',              -- JSON (unified w/ extraBody)
  enabled         INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
);
CREATE INDEX idx_models_provider ON models(provider_id);
CREATE INDEX idx_models_tag ON models(tag);

-- -------- sessions --------
CREATE TABLE sessions (
  id                TEXT PRIMARY KEY,
  model_tag         TEXT NOT NULL,
  model_name        TEXT NOT NULL,
  title             TEXT NOT NULL,
  time              TEXT NOT NULL,
  session_group     TEXT NOT NULL,
  tab_id            TEXT,
  main_project_id   TEXT,
  message_count     INTEGER NOT NULL DEFAULT 0,
  created_at        TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at        TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_sessions_updated ON sessions(updated_at DESC);

-- -------- messages --------
CREATE TABLE messages (
  id              TEXT PRIMARY KEY,
  session_id      TEXT NOT NULL,
  role            TEXT NOT NULL,                -- 'user'|'assistant'|'system'|'tool'
  author          TEXT,
  content         TEXT NOT NULL,
  reasoning       TEXT,
  finish_reason   TEXT,
  input_tokens    INTEGER,
  output_tokens   INTEGER,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_messages_session ON messages(session_id, created_at);

-- -------- skills --------
CREATE TABLE skills (
  id              TEXT PRIMARY KEY,
  name            TEXT UNIQUE NOT NULL,
  description     TEXT,
  enabled         INTEGER NOT NULL DEFAULT 1,
  builtin         INTEGER NOT NULL DEFAULT 0,
  config          TEXT                          -- JSON
);

-- -------- mcp_servers --------
CREATE TABLE mcp_servers (
  id              TEXT PRIMARY KEY,
  name            TEXT UNIQUE NOT NULL,
  command         TEXT NOT NULL,
  args            TEXT,                         -- JSON array
  env             TEXT,                         -- JSON object
  status          TEXT NOT NULL DEFAULT 'stopped',  -- 'running'|'stopped'|'error'
  last_error      TEXT,
  pid             INTEGER,
  enabled         INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_mcp_status ON mcp_servers(status);

-- -------- database_connections --------
CREATE TABLE database_connections (
  id              TEXT PRIMARY KEY,
  name            TEXT UNIQUE NOT NULL,
  type            TEXT NOT NULL,                -- 'postgres'|'mysql'|'sqlite'|'mongodb'
  host            TEXT NOT NULL,
  port            INTEGER NOT NULL,
  database        TEXT NOT NULL,
  user            TEXT,
  password_hash   TEXT,
  status          TEXT NOT NULL DEFAULT 'disconnected',
  last_error      TEXT
);

-- -------- schedules --------
CREATE TABLE schedules (
  id                TEXT PRIMARY KEY,
  name              TEXT UNIQUE NOT NULL,
  kind              TEXT NOT NULL,              -- 'cron'|'once'|'interval'
  cron              TEXT,
  interval_seconds  INTEGER,
  run_at            TEXT,
  command           TEXT NOT NULL,
  description       TEXT,
  enabled           INTEGER NOT NULL DEFAULT 1,
  last_run_at       TEXT,
  last_run_status   TEXT
);
CREATE INDEX idx_schedules_enabled ON schedules(enabled);

-- -------- projects --------
CREATE TABLE projects (
  id                TEXT PRIMARY KEY,
  name              TEXT UNIQUE NOT NULL,
  path              TEXT NOT NULL,
  branch            TEXT DEFAULT 'main',
  dirty             INTEGER DEFAULT 0,
  agents            INTEGER DEFAULT 0,
  last_indexed_at   TEXT,
  bound             INTEGER NOT NULL DEFAULT 0
);

-- -------- session_files --------
CREATE TABLE session_files (
  id              TEXT PRIMARY KEY,
  session_id      TEXT NOT NULL,
  path            TEXT NOT NULL,
  status          TEXT NOT NULL,                -- 'modified'|'added'|'deleted'|'renamed'
  additions       INTEGER DEFAULT 0,
  deletions       INTEGER DEFAULT 0,
  old_rev         TEXT,
  new_rev         TEXT,
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_session_files_session ON session_files(session_id);

-- -------- settings (singleton, id=1) --------
CREATE TABLE settings (
  id                  INTEGER PRIMARY KEY CHECK (id = 1),
  theme               TEXT NOT NULL DEFAULT 'light',
  font_size           TEXT NOT NULL DEFAULT 'medium',
  accent              TEXT NOT NULL DEFAULT '#CC785C',
  animations_enabled  INTEGER NOT NULL DEFAULT 1,
  main_model_id       TEXT,
  fast_model_id       TEXT
);
INSERT INTO settings (id) VALUES (1);

-- -------- tool_calls --------
CREATE TABLE tool_calls (
  id              TEXT PRIMARY KEY,
  message_id      TEXT NOT NULL,
  tool_name       TEXT NOT NULL,
  arguments       TEXT,                         -- JSON
  result          TEXT,
  is_error        INTEGER NOT NULL DEFAULT 0,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
);
CREATE INDEX idx_tool_calls_message ON tool_calls(message_id);
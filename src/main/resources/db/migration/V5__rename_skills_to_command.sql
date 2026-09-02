-- ===================================================================
-- V4: 重命名 skills → command 并新增 22 列 · 对齐 CC command.ts 28 字段模型
--
-- 回滚：RENAME TABLE command TO skills（反向执行）
-- 注意：SQLite 不支持 DROP COLUMN，需重建表（V5）
-- ===================================================================

-- 1. 重命名旧表
ALTER TABLE skills RENAME TO command_old;

-- 2. 以新 schema 重建 command 表（28 列）
CREATE TABLE command (
  id                        TEXT PRIMARY KEY,
  name                      TEXT UNIQUE NOT NULL,
  description               TEXT,
  version                   TEXT,
  source                    TEXT NOT NULL DEFAULT 'user',         -- builtin|user|plugin|mcp|bundled
  aliases                   TEXT,                                 -- JSON array
  argument_hint             TEXT,
  when_to_use               TEXT,
  is_hidden                 INTEGER NOT NULL DEFAULT 0,
  is_sensitive              INTEGER NOT NULL DEFAULT 0,
  immediate                 INTEGER NOT NULL DEFAULT 0,
  user_invocable            INTEGER NOT NULL DEFAULT 1,
  disable_model_invocation  INTEGER NOT NULL DEFAULT 0,
  kind                      TEXT,                                 -- 'workflow' 或 NULL
  -- PromptCommand（技能运行时）
  context                   TEXT DEFAULT 'inline',                -- inline|fork
  agent                     TEXT,
  allowed_tools             TEXT,                                 -- JSON array
  model                     TEXT,
  effort                    TEXT,
  paths                     TEXT,                                 -- JSON array (glob patterns)
  hooks                     TEXT,                                 -- JSON (HooksSettings)
  progress_message          TEXT,
  -- 内容与路径
  content                   TEXT,
  content_path              TEXT,
  base_dir                  TEXT,                                 -- CC skillRoot
  -- 状态（向后兼容 skill）
  enabled                   INTEGER NOT NULL DEFAULT 1,
  builtin                   INTEGER NOT NULL DEFAULT 0,
  config                    TEXT                                  -- JSON（向后兼容 skill.config）
);

CREATE INDEX idx_command_source ON command(source);
CREATE INDEX idx_command_enabled ON command(enabled);

-- 3. 从旧表迁移数据（仅 7 个共有列）
INSERT INTO command (id, name, description, enabled, builtin, config, content_path)
  SELECT id, name, description, enabled, builtin, config, path FROM command_old;

-- 4. 删除旧表
DROP TABLE command_old;

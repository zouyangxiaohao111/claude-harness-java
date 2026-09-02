-- ===================================================================
-- V36: websearch_results 表（WebSearch 详细原始搜索结果 · 供前端审计/查询）
--
-- 背景（用户 2026-08-23）：给 LLM 只有弱模型总结 {query,summary,durationSeconds}，
--   详细原始 hits 不进 LLM 返回；本表持久化每次搜索的原始结果，供前端
--   GET /api/v1/sessions/{sessionId}/websearch-results 查询/审计。
--
-- 注：命名取 V36。本分支返工 R2（2026-08-23）已将 disabled_tools 迁移由 V34 重排为
--   V35__add_sessions_disabled_tools.sql（对齐 master 63bab408 同款重排，消除本分支的双 V34 地雷）；
--   故本分支迁移集 = V34__add_settings_auto_memory_config.sql + V35__add_sessions_disabled_tools.sql
--   + 本表 V36，与 master 迁移集一致（V36 为分支新增）。
--
-- 字段约定（MyBatis-Flex map-underscore-to-camel-case，同 tool_calls 既有约定）：
--   id               ↔ id（主键 = toolUseId，ToolUseBlock.id()，单次 execute = 1 条）
--   session_id       ↔ sessionId
--   tool_use_id      ↔ toolUseId（= WebSearchTool 调用块 id call.id()，与消息流 tool_use 块关联）
--   query            ↔ query
--   results          ↔ results（JSON 数组，元素对齐 CC searchHitSchema {title,url}）
--   duration_seconds ↔ durationSeconds
--   created_at       默认 datetime('now')
-- ===================================================================
CREATE TABLE websearch_results (
  id               TEXT PRIMARY KEY,
  session_id       TEXT NOT NULL,
  tool_use_id      TEXT NOT NULL,
  query            TEXT NOT NULL,
  results          TEXT NOT NULL,        -- JSON array of {title, url}
  duration_seconds REAL NOT NULL,
  created_at       TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_websearch_results_session ON websearch_results(session_id, created_at);
CREATE INDEX idx_websearch_results_tool_use ON websearch_results(tool_use_id);

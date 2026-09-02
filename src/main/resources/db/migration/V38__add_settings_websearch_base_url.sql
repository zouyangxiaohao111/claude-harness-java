-- ===================================================================
-- V38: settings 表新增 websearch_base_url 列
-- [websearch-resid R-B] base-url DB 化（anysearch API base URL）。
--   空 → WebSearchTool 读链兜底 AnySearchEngine.DEFAULT_BASE_URL
--   （https://api.anysearch.com，AnySearchEngine.java:44）。
--   与 V37 同风格：websearch_base_url ↔ websearchBaseUrl（String；SQLite VARCHAR → TEXT 亲和）。
--   当前最新迁移 V37，新增 V38 无版本冲突。
-- ===================================================================
ALTER TABLE settings ADD COLUMN websearch_base_url VARCHAR(255);

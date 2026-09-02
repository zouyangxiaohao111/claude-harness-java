-- Provider / Model 表补 max_context_tokens 列（ProviderRecord/ModelRecord.maxContextTokens 映射）
-- SQLite 无 IF NOT EXISTS for column；Flyway 版本化保证只跑一次
ALTER TABLE providers ADD COLUMN max_context_tokens INTEGER;
ALTER TABLE models ADD COLUMN max_context_tokens INTEGER;

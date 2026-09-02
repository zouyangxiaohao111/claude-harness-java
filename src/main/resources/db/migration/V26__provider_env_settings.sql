-- ===================================================================
-- V25: Provider 环境设置（对齐 CC settings 模型档位）
-- ① settings 表新增模型档位列（weak/medium/strong/subagent modelId + 自动压缩窗口）：
--    * autoCompactWindow 对齐 CC Open-ClaudeCode/src/services/compact/autoCompact.ts:40-42
--      （env CLAUDE_CODE_AUTO_COMPACT_WINDOW 解析，数字窗口，null=未配置）；
--    * weak/medium/strong/subagent 档位模型选择沿用 snake_case（V24 max_context_tokens 同款），
--      Java 端 camelCase 映射（weakModelId/mediumModelId/strongModelId/subagentModelId/
--      autoCompactWindow）。
-- ② providers 表删除 max_context_tokens（V24 误加列；本次打通目标列 =
--    models.max_context_tokens，models 表保留不动）。
-- 实现决策（显式冲突暴露，规则一/规则七）：
--    任务指令要求按 V20 表重建方式删列，前提"SQLite 低版本不支持 DROP COLUMN"与事实不符：
--    * pom.xml sqlite-jdbc 3.46.0.0（SQLite 3.46 >= 3.35.0），官方支持 DROP COLUMN；
--    * V19__drop_sessions_original_cwd.sql 已用 ALTER TABLE DROP COLUMN 成功删 sessions 列
--      （sessions 同为 FK 父表：messages/session_files ON DELETE CASCADE 引用），
--      且 V19 注释明确记录仓库规范"SQLite ALTER TABLE DROP COLUMN 支持（>= 3.35.0），
--      无需脱离事务重建表"；
--    * JDBC URL 带 foreign_keys=on（application.yml:39）：表重建的 DROP TABLE providers
--      会触发隐式 DELETE FROM providers → ON DELETE CASCADE 级联删除全部 models 行
--      （生产数据丢失）；PRAGMA foreign_keys=OFF 在 Flyway 事务内为 no-op，无法救场。
--    择一（优先更新更经测试的方案）：直接 ALTER TABLE providers DROP COLUMN——
--    SQLite >= 3.35 官方支持，FK 安全（不触发级联），零数据丢失，与 V19 仓库既有规范一致。
-- ===================================================================

-- ---------- settings 表：新增模型档位列（SQLite 一次 ALTER 仅单操作，逐列执行） ----------
ALTER TABLE settings ADD COLUMN weak_model_id TEXT;
ALTER TABLE settings ADD COLUMN medium_model_id TEXT;
ALTER TABLE settings ADD COLUMN strong_model_id TEXT;
ALTER TABLE settings ADD COLUMN subagent_model_id TEXT;
ALTER TABLE settings ADD COLUMN auto_compact_window INTEGER;

-- ---------- providers 表：删除 max_context_tokens ----------
ALTER TABLE providers DROP COLUMN max_context_tokens;

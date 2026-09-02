-- ===================================================================
-- V31: effort 从全局 settings 迁移到会话级 sessions
--
-- 背景：用户拍板（Web 多会话 vs CC 单会话）——effort 必须会话级。
--   CC resolveAppliedEffort = env ?? appState.effortValue ?? getDefaultEffortForModel
--   （effort.ts:152-167），getDisplayedEffortLevel 兜底 'high'（:178）。
--   Java 端复刻：settings.effortLevel 全局单值 → sessions.effort_level 会话级持久化。
--
-- 1) sessions 表加 effort_level（TEXT 可空，null = 未配置，解析走模型默认/无 override）：
--    会话级 effort 持久化。Java 端 camelCase 字段 effortLevel，
--    MyBatis-Flex 自动 snake_case↔camelCase 转换（同 SessionRecord 既有列约定）。
-- 2) settings 表删 effort_level（V29__add_settings_effort_level.sql 加的，回滚删除）：
--    全局单值不再承载 effort。SQLite ALTER TABLE DROP COLUMN 支持（SQLite >= 3.35.0，
--    sqlite-jdbc 3.46.0.0），同 V19__drop_sessions_original_cwd.sql /
--    V30__drop_models_context_window.sql 既有先例。
-- ===================================================================
ALTER TABLE sessions ADD COLUMN effort_level TEXT;
ALTER TABLE settings DROP COLUMN effort_level;

-- ===================================================================
-- V29: settings 表新增 effort_level 列
-- 对齐 CC effort.tsx:16-22 updateSettingsForSource('userSettings', { effortLevel }) 持久化：
--   仅落字符串档位（effort.ts:95-105 toPersistableEffort：low/medium/high 恒可持久化，
--   max 仅 USER_TYPE=ant 可持久化；数字值/auto 会话级不落盘）。
-- TEXT 可空（null = 未配置，effort 注入走模型默认/无 override）。
-- Java 端 camelCase 映射（effortLevel），MyBatis-Flex 自动 snake_case↔camelCase 转换。
-- ===================================================================
ALTER TABLE settings ADD COLUMN effort_level TEXT;

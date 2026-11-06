-- [external-agent 2026-11-04] 子代理外部运行时配置
-- 两列含义（三态，与 channels_enabled 同款语义）：
--   subagent_default_runtime : NULL = 未配置（= 用内置，不启用外部运行时）
--                              非空 = 默认外部运行时 id（claude / codex / opencode / pi）
--   subagent_external_enabled: NULL = 未配置（按 yml 兜底）
--                              true/false = 用户显式开关（false 必须压过 yml 的 true）
ALTER TABLE settings ADD COLUMN subagent_default_runtime VARCHAR(64);
ALTER TABLE settings ADD COLUMN subagent_external_enabled BOOLEAN;

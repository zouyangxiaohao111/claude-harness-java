-- [external-agent 2026-11-04] 扩展 CLI 运行时配置：对齐 OpenDesign「你的 CLI」卡片
-- 新增七列：
--   bin             自定义可执行文件绝对路径（覆盖 PATH 探测；如 D:/tools/codex.exe）
--   model           选定的模型 id（空 = CLI 默认）
--   reasoning       推理强度 id（空 = CLI 默认）
--                   codex=model_reasoning_effort / pi=--thinking / opencode=--variant
--   secrets_json    AES/GCM 加密的 JSON：{"CODEX_API_KEY":"sk-...","OPENAI_API_KEY":"sk-..."}
--                   ⛔ 用 JSON 而不是多列：各家 key 变量名不同、且 codex/claude 各有 2 个，
--                      以后接新 CLI 不需要再改表结构。
--   last_test_ok    上次「测试连接」是否成功
--   last_tested_at  上次测试时间
--   last_test_message 上次测试的失败原因（截断 500 字）
-- 旧的 api_key_encrypted / api_key_masked / api_key_hash 保留（读兼容），新写入走 secrets_json。
ALTER TABLE cli_runtime_config ADD COLUMN bin TEXT;
ALTER TABLE cli_runtime_config ADD COLUMN model TEXT;
ALTER TABLE cli_runtime_config ADD COLUMN reasoning TEXT;
ALTER TABLE cli_runtime_config ADD COLUMN secrets_json TEXT;
ALTER TABLE cli_runtime_config ADD COLUMN last_test_ok BOOLEAN;
ALTER TABLE cli_runtime_config ADD COLUMN last_tested_at TEXT;
ALTER TABLE cli_runtime_config ADD COLUMN last_test_message TEXT;

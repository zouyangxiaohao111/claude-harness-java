-- ===================================================================
-- V27: settings 表新增 max_output_tokens / fallback_model_id 列
-- ① max_output_tokens（INTEGER 可空）· CC envValidation.ts:9-38 CLAUDE_CODE_MAX_OUTPUT_TOKENS
--    有界 override 迁移为 settings 配置：>0 生效、> 模型 upperLimit 封顶到 upperLimit、
--    null 用模型默认（等价 CC env 未设置分支）。Java 端 camelCase 映射（maxOutputTokens）。
-- ② fallback_model_id（TEXT 可空）· 回落模型（F4 用，一并建列）。
--    Java 端 camelCase 映射（fallbackModelId）。
-- 列名沿用 snake_case（V25 weak_model_id / V26 同款），Java 端 camelCase 映射。
-- ===================================================================

ALTER TABLE settings ADD COLUMN max_output_tokens INTEGER;
ALTER TABLE settings ADD COLUMN fallback_model_id TEXT;

-- ===================================================================
-- V28: settings 模型档位列全名化（RENAME _model_id → _model_name + 存量 id 清除 + 新增档位列）
-- 背景（用户拍板 B：全名化 + tts/asr 分开）：
--   前端已传模型全名（fullName = providerName/modelName，ModelPickerModal.tsx:115），
--   settingsTierModelName 双路径（id + 全名）删除 id 路径只留全名。
-- ① 列 RENAME（SQLite ALTER TABLE RENAME COLUMN 逐列执行，SQLite >= 3.25 支持，
--    仓库 sqlite-jdbc 3.46.0.0；Java 端 camelCase 映射 *ModelId → *ModelName，
--    MyBatis-Flex 自动 snake_case↔camelCase 转换）：
--    weak/medium/strong/subagent 四列源自 V26（V26__provider_env_settings.sql:26-29）、
--    main/fast 两列源自 V1（V1__init_schema.sql:163-164）、
--    fallback 列源自 V27（V27__add_settings_max_output_tokens.sql:12）。
-- ② 存量 id 值清除（用户拍板清除，不迁移成全名）：
--    models.id = 'model-'+8hex（ModelService.java:57 generateId("model")）、
--    providers.id = 'prov-'+8hex（ProviderService.java:87 generateId("prov")）。
--    值若为 id 形态（LIKE 'model-%' / 'prov-%'，即任务描述的 'mod-%'/'prov-%'）→ 置 NULL。
--    简单 LIKE 判定（用户拍板），不做全名迁移；settings 为 singleton（id=1）单行。
-- ③ 新增档位列：multimodal / tts / asr 全名列（用户拍板 tts/asr 分开）。
-- ===================================================================

-- ---------- ① 列 RENAME（SQLite 一次 ALTER 仅单操作，逐列执行） ----------
ALTER TABLE settings RENAME COLUMN weak_model_id TO weak_model_name;
ALTER TABLE settings RENAME COLUMN medium_model_id TO medium_model_name;
ALTER TABLE settings RENAME COLUMN strong_model_id TO strong_model_name;
ALTER TABLE settings RENAME COLUMN subagent_model_id TO subagent_model_name;
ALTER TABLE settings RENAME COLUMN main_model_id TO main_model_name;
ALTER TABLE settings RENAME COLUMN fast_model_id TO fast_model_name;
ALTER TABLE settings RENAME COLUMN fallback_model_id TO fallback_model_name;

-- ---------- ② 存量 id 值清除（逐列清除：仅 id 形态置 NULL，全名值保留） ----------
--    per-column 而非"任一列命中清空全部"：仅清掉 id 形态存量，全名值（如 'openai/gpt-4o'）不受影响。
UPDATE settings SET weak_model_name = NULL
 WHERE weak_model_name LIKE 'model-%' OR weak_model_name LIKE 'prov-%';
UPDATE settings SET medium_model_name = NULL
 WHERE medium_model_name LIKE 'model-%' OR medium_model_name LIKE 'prov-%';
UPDATE settings SET strong_model_name = NULL
 WHERE strong_model_name LIKE 'model-%' OR strong_model_name LIKE 'prov-%';
UPDATE settings SET subagent_model_name = NULL
 WHERE subagent_model_name LIKE 'model-%' OR subagent_model_name LIKE 'prov-%';
UPDATE settings SET main_model_name = NULL
 WHERE main_model_name LIKE 'model-%' OR main_model_name LIKE 'prov-%';
UPDATE settings SET fast_model_name = NULL
 WHERE fast_model_name LIKE 'model-%' OR fast_model_name LIKE 'prov-%';
UPDATE settings SET fallback_model_name = NULL
 WHERE fallback_model_name LIKE 'model-%' OR fallback_model_name LIKE 'prov-%';

-- ---------- ③ 新增档位全名列（multimodal / tts / asr） ----------
ALTER TABLE settings ADD COLUMN multimodal_model_name TEXT;
ALTER TABLE settings ADD COLUMN tts_model_name TEXT;
ALTER TABLE settings ADD COLUMN asr_model_name TEXT;

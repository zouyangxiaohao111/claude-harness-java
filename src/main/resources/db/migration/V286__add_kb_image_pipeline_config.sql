-- ===================================================================
-- V286: 知识库图片管线三项配置（caption 开关 / OCR 开关 / 管线方案）
--
-- 对齐源站 WeKnora：
--   * image_pipeline_default.go:59-76  —— 默认管线用 caption / OCR 两个开关决定
--     是否各跑一次视觉模型调用；
--   * image_pipeline_smartocr.go       —— smartocr 管线先做一次「观察」
--     （一次调用同时产出描述 + 是否含文字），只对含文字的图再跑 OCR。
--
-- 形态口径：
--   * 开关用 INTEGER 0/1（本仓 SQLite 既有布尔列一律这种形态，如 is_pinned /
--     is_temporary），NOT NULL + DEFAULT 1 = 老库默认与改造前行为一致
--     （改造前恒跑 caption + OCR，相当于两个开关都开）。
--   * image_pipeline 存 TEXT 枚举：default | smartocr；DEFAULT 'default'
--     同样保证老库升级后零行为变化。
--
-- 只加列、不改已有列、不删数据；SQLite ALTER TABLE ADD COLUMN 带常量
-- 非空默认值时，既有行的该列就是该默认值（无需回填 UPDATE）。
-- ===================================================================

ALTER TABLE kb_knowledge_base ADD COLUMN image_caption_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE kb_knowledge_base ADD COLUMN image_ocr_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE kb_knowledge_base ADD COLUMN image_pipeline TEXT NOT NULL DEFAULT 'default';

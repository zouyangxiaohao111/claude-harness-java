-- ===================================================================
-- V273: 知识库域配置独立成表 `kb_config`（批次 R0 · 包收拢前置）
--
-- ⭐ 用户 2026-11-05 最终裁定：
--   「单开一张 kb_config 中，然后上面比如模型的都是从 settings 表中拿的」
--   → 随后修正为：「8 个 kb_* 字段全部搬到 kb_config，settings 一个不留」，
--     其中两个模型字段去掉 kb_ 前缀 ⇒ embedding_model_id / rerank_model_id。
--
-- ⇒ 本表接管 settings 上原有的全部 8 个知识库域列：
--   kb_embedding_model_id       → embedding_model_id
--   kb_rerank_model_id          → rerank_model_id
--   kb_retriever_engine         → retriever_engine          （V268 加）
--   default_storage_backend_id  → default_storage_backend_id（V271 加）
--   kb_memory_enabled           → memory_enabled            （V272 原本要加，改由本表承载）
--   kb_memory_write_mode        → memory_write_mode
--   kb_memory_max_items         → memory_max_items
--   kb_memory_interest_threshold→ memory_interest_threshold
--
-- 为什么必须搬（不是洁癖）：
--   ① settings 是**跨功能共用**的单行，多会话并发 PUT 会整体覆盖 —— W8 已实际丢过字段；
--   ② 知识库将来整块拆出去时，配置要跟着走，不能留在公共表里。
--
-- ⚠️ 本迁移**只复制值，然后删除 settings 的列**（用户明确要求 settings 一个不留）。
--    删除不可逆，但 §复制 先做，且复制是「子查询取值」——
--    settings 里没有 id=1 行时写入 NULL（= 未配置），不会丢信息。
--
-- ⚠️ 顺序依赖：V268 加 kb_retriever_engine、V271 加 default_storage_backend_id，
--    都在本迁移之前 ⇒ 复制时这两列一定存在。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_config (
    -- 单行表：id 恒为 'kb'
    id                          TEXT PRIMARY KEY,

    -- ── 检索 / 存储 ────────────────────────────────────────
    retriever_engine            TEXT,     -- 原 settings.kb_retriever_engine（V268）
    default_storage_backend_id  TEXT,     -- 原 settings.default_storage_backend_id（V271）

    -- ── 模型全局默认（去 kb_ 前缀）───────────────────────
    -- ⚠️ 语义是「全局默认」，不是「唯一」：
    --    每库覆盖已有 kb_knowledge_base.embedding_model_id / rerank_model_id。
    --    NULL = 未显式配置 ⇒ 运行时**自动选用**同类型第一个启用模型（见 KbConfigService）。
    embedding_model_id          TEXT,
    rerank_model_id             TEXT,

    -- ── 长期记忆 ──────────────────────────────────────────
    memory_enabled              INTEGER,  -- NULL = 未配置 ⇒ 按 true
    memory_write_mode           TEXT,     -- explicit_only（默认）| auto
    memory_max_items            INTEGER,  -- NULL/≤0 = 默认 200
    memory_interest_threshold   INTEGER,  -- NULL/≤0 = 默认 3

    -- ── 审计六列（对齐 zjky BaseDO，与其余 kb_* 表一致）──────────
    creator                     TEXT NOT NULL DEFAULT '',
    create_time                 TEXT NOT NULL DEFAULT (datetime('now')),
    updater                     TEXT NOT NULL DEFAULT '',
    update_time                 TEXT NOT NULL DEFAULT (datetime('now')),
    deleted                     INTEGER NOT NULL DEFAULT 0,
    tenant_id                   INTEGER NOT NULL DEFAULT 0
);

-- 单行占位（OR IGNORE：重复执行安全）
INSERT OR IGNORE INTO kb_config (id) VALUES ('kb');

-- 从 settings 复制现值。
-- ⚠️ 用子查询而不是 INSERT...SELECT：settings 里若没有 id=1 行，
--    子查询返回 NULL ⇒ 写入 NULL（= 未配置），而不是「整行不插」导致 kb_config 缺行。
-- ⚠️ memory 四列不复制：它们在 V272 已改为不写 settings，历史上也从未被写入过。
UPDATE kb_config SET
    retriever_engine           = (SELECT kb_retriever_engine         FROM settings WHERE id = 1),
    default_storage_backend_id = (SELECT default_storage_backend_id  FROM settings WHERE id = 1),
    embedding_model_id         = (SELECT kb_embedding_model_id       FROM settings WHERE id = 1),
    rerank_model_id            = (SELECT kb_rerank_model_id          FROM settings WHERE id = 1)
WHERE id = 'kb';

-- settings 上的 4 个知识库域列 → 删除（用户 2026-11-05：「settings 一个不留」）。
-- ⚠️ SQLite 3.35+ 的 DROP COLUMN；这几列没有索引/视图/触发器/生成列引用，
--    且非 PK / 非 UNIQUE ⇒ 可直接删。
ALTER TABLE settings DROP COLUMN kb_embedding_model_id;
ALTER TABLE settings DROP COLUMN kb_rerank_model_id;
ALTER TABLE settings DROP COLUMN kb_retriever_engine;
ALTER TABLE settings DROP COLUMN default_storage_backend_id;

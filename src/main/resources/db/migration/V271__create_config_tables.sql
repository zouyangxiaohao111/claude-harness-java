-- ===================================================================
-- V271: 配置类（WeKnora 复刻 · 批次 W8）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   migrations/versioned/000032_vector_stores.up.sql          → vector_stores
--   migrations/versioned/000068_storage_backends.up.sql       → storage_backends
--                                                              + tenants.default_storage_backend_id
--                                                              + knowledge_bases.storage_backend_id
--   internal/types/vectorstore.go:665 GetVectorStoreTypes()   → 7 种引擎的字段 schema
--   internal/types/storagebackend.go                          → local / minio / cos
--
-- ⭐ 用户 2026-11-05 裁定：
--   ① VectorStore = 「实例层覆盖类型层」——W1 的 retriever_engine 保留为「没绑实例时的类型默认」，
--      绑了实例就按实例的连接配置走。⛔ 不推翻 W1 已真机验证的链路。
--   ② 存储后端「跟其他的一样 保留接口兼容，如果有 minio 那直接无缝接入，没有就本地」。
--   ③ 默认存储后端放 settings 单行（本仓没有 tenants 表，不为此新建）。
--
-- ⚠️ 本迁移不动 kb_knowledge_base.vector_store_id / storage_backend_id ——
--    这两列 V256 建表时就已经有了（照抄源侧的最终列形态），只是至今没有写入方。
-- ===================================================================


-- ───────────────────────────────────────────────────────────────
-- 1. vector_stores —— 向量库实例（同类型可多个，各自独立连接配置）
--
-- 源侧 000032 的列：id/name/engine_type/connection_config/index_config/tenant_id/deleted_at
-- 本仓按 D-7..D-13 换成审计六列（无 deleted_at，用 INTEGER 0/1 逻辑删）。
--
-- ⚠️ 名称唯一性用**部分索引**（WHERE deleted = 0）：
--    逻辑删掉的旧行不该挡住同名新建 —— 用户删了再建同名 Store 是正常操作。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_vector_store (
    id                 TEXT PRIMARY KEY,

    name               TEXT NOT NULL,
    -- 引擎类型（7 种可注册实例，见 KbVectorStoreTypes）
    -- ⚠️ 不含 postgres / sqlite：源侧明确排除（它们只能绑应用默认库连接，
    --    同一实例上再建第二个没有任何隔离效果，会让用户以为数据分开了）
    engine_type        TEXT NOT NULL,

    -- 连接配置 JSON（host/port/api_key/...；字段 schema 见类型元数据端点）
    -- ⚠️ 敏感字段（api_key/password）写入时**原样存**，读取时按 sensitive 标记脱敏。
    connection_config  TEXT NOT NULL DEFAULT '{}',
    -- 索引配置 JSON（collection_name/shards/...）
    index_config       TEXT NOT NULL DEFAULT '{}',

    -- 审计六列（对齐 zjky BaseDO / TenantBaseDO）
    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_vector_store_name
    ON kb_vector_store (name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_vector_store_engine
    ON kb_vector_store (engine_type);


-- ───────────────────────────────────────────────────────────────
-- 2. storage_backends —— 存储后端实例
--
-- 源侧 000068 的列：id/name/provider/config/source/status/legacy_alias/deleted_at
--
-- ⭐ `source`：'user' = 用户建的；'env' = 由环境变量/配置注入（本仓的本地磁盘属于这类）。
-- ⭐ `status`：'active' | 'disabled'（源侧同）。
-- ⚠️ `legacy_alias` 本仓不用（源侧是给「老部署按 provider 名兼容」的过渡字段），
--    但列保留 —— 与源侧列形态对齐，将来对接时不用再改表。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_storage_backend (
    id                 TEXT PRIMARY KEY,

    name               TEXT NOT NULL,
    -- local | minio | cos
    provider           TEXT NOT NULL,

    -- 配置 JSON（本地是 root 目录；minio/cos 是 endpoint/bucket/密钥）
    config             TEXT NOT NULL DEFAULT '{}',

    source             TEXT NOT NULL DEFAULT 'user',
    status             TEXT NOT NULL DEFAULT 'active',
    legacy_alias       INTEGER NOT NULL DEFAULT 0,

    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_storage_backend_name
    ON kb_storage_backend (name) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 3. settings：默认存储后端
--
-- 源侧放在 tenants.default_storage_backend_id。
-- 本仓没有 tenants 表（单机、D-8「暂不接入 framework」）⇒ 落 settings 单行。
--
-- ⚠️ 留 NULL 而不是预填本地后端 id：
--    本地后端是**启动时按配置生成**的，id 每次安装都不同，没法写进迁移。
--    NULL = 「还没选」，解析时回落「source='env' 的本地后端」。
-- ───────────────────────────────────────────────────────────────
ALTER TABLE settings ADD COLUMN default_storage_backend_id TEXT;

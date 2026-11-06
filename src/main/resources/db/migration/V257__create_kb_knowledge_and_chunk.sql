-- ===================================================================
-- V257: kb_knowledge + kb_chunk —— 知识条目与分块（WeKnora 复刻 · 批次 B2）
-- ⚠️ 原编 V255，因 master 已提交 V255__create_mcp_channel_binding.sql（channel 批次）
--    撞号 ⇒ 2026-11-04 改号 V257（真库当时最高版本 253，两版均未被应用过）。
--
-- 源头：腾讯 WeKnora v0.9.0（revision 80e8345）`migrations/versioned/` 里
--       knowledges / chunks 两张表的**最终形态**（不是初始建表！）。
--
--   knowledges：初始 22 列 → 最终 29 列
--     000001 +tag_id +summary_status
--     000009 +last_faq_import_result
--     000025 +channel
--     000056 +pending_subtasks_count
--     000063 -tag_id（改为多对多，见 knowledge_tag_relations）
--     000078 +custom_metadata
--     000079 +folder_path
--     000101 +profile
--
--   chunks：初始 19 列 → 最终 32 列
--     000001 +metadata +tag_id +status +content_hash
--     000003 +flags
--     000010 +seq_id
--     000033 +video_info
--     000078 +source_content +content_revision +index_status +last_editor_id +context_header
--     000114 +source_locators
--
-- 保真度：BEHAVIOR_EQUIVALENT
--   JSONB → TEXT / BOOLEAN → INTEGER 0/1 / uuid_generate_v4() → 应用层 UUID
--   TIMESTAMP → TEXT（yyyy-MM-dd HH:mm:ss，见 O-7）
--
-- audit 字段（严格照 zjky BaseDO / TenantBaseDO，2026-11-03 用户裁定）：
--   源侧 created_at / updated_at / deleted_at / tenant_id
--   → create_time / update_time / deleted / tenant_id（+ 新增 creator / updater）
--   ⚠️ knowledges 另有**业务时间列 processed_at**（处理完成时刻），它不是 audit 字段，
--      保留原名 processed_at（源侧语义：解析完成时间）。
--
-- 表前缀 kb_：与 NexusAI 现有 89 个迁移的表名不冲突。
-- ⚠️ 本文件注释不得出现「美元符 + 左大括号」的 Flyway 占位符字面量。
-- ===================================================================

-- ───────────────────────────────────────────────────────────────
-- kb_knowledge：知识条目（一个上传的文档 / 一个 URL / 一条手工记录）
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_knowledge (
    id                    TEXT PRIMARY KEY,

    -- ── 归属 ─────────────────────────────────────────────────────
    knowledge_base_id     TEXT NOT NULL,

    -- ── 基础 ─────────────────────────────────────────────────────
    -- 类型（源侧 VARCHAR(50) NOT NULL，无 DB 默认值；业务取值见 TYPE_* 常量）
    type                  TEXT NOT NULL,
    title                 TEXT NOT NULL,
    description           TEXT,
    -- 来源（URL / 文件路径 / 'manual' 等，源侧 VARCHAR(2048) NOT NULL）
    source                TEXT NOT NULL,
    -- 解析状态：unprocessed | pending | processing | finalizing | completed | failed | deleting
    parse_status          TEXT NOT NULL DEFAULT 'unprocessed',
    -- 启用状态：enabled | disabled
    enable_status         TEXT NOT NULL DEFAULT 'enabled',

    -- ── 模型 ─────────────────────────────────────────────────────
    embedding_model_id    TEXT,

    -- ── 文件信息（可空；手工条目/URL 无文件）───────────────────────
    file_name             TEXT,
    file_type             TEXT,
    file_size             INTEGER,
    file_path             TEXT,
    file_hash             TEXT,
    -- 存储占用字节（源侧 NOT NULL DEFAULT 0）
    storage_size          INTEGER NOT NULL DEFAULT 0,

    -- ── 后处理（source 000001 / 000056）──────────────────────────
    -- 摘要状态：none | pending | processing | completed | failed
    summary_status        TEXT DEFAULT 'none',
    -- 待完成子任务计数（后处理槽位账，源 000056）
    pending_subtasks_count INTEGER NOT NULL DEFAULT 0,
    -- FAQ 上次导入结果 JSON（源 000009）
    last_faq_import_result TEXT,

    -- ── 来源渠道（源 000025）────────────────────────────────────
    channel               TEXT NOT NULL DEFAULT 'web',

    -- ── 结构化（全部 JSONB → TEXT）──────────────────────────────
    metadata              TEXT,
    -- 用户自定义元数据（源 000078，默认 '{}'）
    custom_metadata       TEXT NOT NULL DEFAULT '{}',
    -- 知识库画像（源 000101）
    profile               TEXT,

    -- ── 文件夹（源 000079）──────────────────────────────────────
    folder_path           TEXT NOT NULL DEFAULT '',

    -- ── 业务时间（非 audit）─────────────────────────────────────
    processed_at          TEXT,

    -- ── 错误 ────────────────────────────────────────────────────
    error_message         TEXT,

    -- ── audit 字段（zjky BaseDO / TenantBaseDO）─────────────────
    creator               TEXT DEFAULT '',
    create_time           TEXT NOT NULL DEFAULT (datetime('now')),
    updater               TEXT DEFAULT '',
    update_time           TEXT NOT NULL DEFAULT (datetime('now')),
    deleted               INTEGER NOT NULL DEFAULT 0,
    tenant_id             INTEGER NOT NULL DEFAULT 0
);

-- 文档列表主路径：某知识库下未删 + 按创建时间倒序
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_list
    ON kb_knowledge (knowledge_base_id, deleted, create_time);

-- 按解析状态筛选（前端「处理中」面板）
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_parse_status
    ON kb_knowledge (knowledge_base_id, parse_status);

-- 按文件夹浏览
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_folder
    ON kb_knowledge (knowledge_base_id, folder_path);

-- 文件去重（同一知识库内同 hash 视为同一文档；源侧靠应用层判重，此处加索引支撑）
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_file_hash
    ON kb_knowledge (knowledge_base_id, file_hash);


-- ───────────────────────────────────────────────────────────────
-- kb_chunk：分块
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_chunk (
    id                     TEXT PRIMARY KEY,

    -- ── 归属 ─────────────────────────────────────────────────────
    knowledge_base_id      TEXT NOT NULL,
    knowledge_id           TEXT NOT NULL,

    -- ── 内容 ─────────────────────────────────────────────────────
    content                TEXT NOT NULL,
    -- 该块在文档内的序号（0 起）
    chunk_index            INTEGER NOT NULL,
    is_enabled             INTEGER NOT NULL DEFAULT 1,
    -- 在原文中的**字符（rune）**区间：End - Start == 内容 rune 数
    start_at               INTEGER NOT NULL,
    end_at                 INTEGER NOT NULL,

    -- ── 顺序链与父子（源侧只有这两种块间关系）─────────────────────
    pre_chunk_id           TEXT,
    next_chunk_id          TEXT,
    parent_chunk_id        TEXT,
    -- 分块类型：text | image | table | ...
    chunk_type             TEXT NOT NULL DEFAULT 'text',

    -- ── 多模态与关联（JSONB → TEXT）──────────────────────────────
    image_info             TEXT,
    relation_chunks        TEXT,
    indirect_relation_chunks TEXT,
    -- 视频信息（源 000033）
    video_info             TEXT,

    -- ── 编修（源 000078）────────────────────────────────────────
    -- 用户编辑前的原文（默认 ''）
    source_content         TEXT NOT NULL DEFAULT '',
    -- 内容版本号（默认 0；每次编辑 +1）
    content_revision       INTEGER NOT NULL DEFAULT 0,
    -- 索引状态：ready | pending | stale（默认 ready）
    index_status           TEXT NOT NULL DEFAULT 'ready',
    last_editor_id         TEXT NOT NULL DEFAULT '',
    -- 标题面包屑（embedding 时前置，不进 content —— 保住位置不变式）
    context_header         TEXT NOT NULL DEFAULT '',

    -- ── 状态与标识（源 000001 / 000003 / 000010）────────────────
    status                 INTEGER NOT NULL DEFAULT 0,
    content_hash           TEXT,
    -- 位标志（源 000003，默认 1）
    flags                  INTEGER NOT NULL DEFAULT 1,
    -- 全局单调序号（源 000010；SQLite 用自增 INTEGER 承接）
    seq_id                 INTEGER,
    -- 源 000001 加；000063 后被 knowledge_tag_relations 取代，但列仍在
    tag_id                 TEXT,

    -- ── 结构化（JSONB → TEXT）──────────────────────────────────
    metadata               TEXT,
    -- 源位置定位信息（源 000114）
    source_locators        TEXT,

    -- ── audit 字段（zjky BaseDO / TenantBaseDO）─────────────────
    creator                TEXT DEFAULT '',
    create_time            TEXT NOT NULL DEFAULT (datetime('now')),
    updater                TEXT DEFAULT '',
    update_time            TEXT NOT NULL DEFAULT (datetime('now')),
    deleted                INTEGER NOT NULL DEFAULT 0,
    tenant_id              INTEGER NOT NULL DEFAULT 0
);

-- 取某文档全部分块（按序）
CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc
    ON kb_chunk (knowledge_id, deleted, chunk_index);

-- 取某知识库全部分块（检索召回用）
CREATE INDEX IF NOT EXISTS idx_kb_chunk_kb
    ON kb_chunk (knowledge_base_id, deleted, is_enabled);

-- 父块展开（命中子块时回查父块）
CREATE INDEX IF NOT EXISTS idx_kb_chunk_parent
    ON kb_chunk (parent_chunk_id);

-- 按索引状态筛选（重建索引任务）
CREATE INDEX IF NOT EXISTS idx_kb_chunk_index_status
    ON kb_chunk (knowledge_base_id, index_status);

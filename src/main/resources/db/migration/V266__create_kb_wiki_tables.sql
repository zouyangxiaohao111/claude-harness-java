-- ===================================================================
-- V266: kb_wiki_page / kb_wiki_folder / kb_wiki_page_issue
--        + kb_knowledge_base.wiki_config
--        （WeKnora Wiki 复刻 · 批次 W2-1）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   000037_wiki_and_indexing.up.sql  → wiki_pages / wiki_folders / wiki_page_issues
--   000061_wiki_page_hierarchy.up.sql → wiki_pages 的 folder 层级字段
--
-- 目标侧差异（都是 SQLite 能力边界，不是简化）：
--   · JSONB → TEXT（存 JSON 字符串）。⚠️ 不能依赖 SQLite 的 json_* 函数做查询，
--     凡是需要「按 JSON 内某个值筛」的地方，一律改成独立列或 LIKE 前缀匹配。
--   · TIMESTAMP WITH TIME ZONE → TEXT，格式统一 'yyyy-MM-dd HH:mm:ss'（D-12）。
--   · 部分索引（WHERE deleted_at IS NULL）→ SQLite 支持，但逻辑删除列是 INTEGER，
--     所以写 WHERE deleted = 0。
--
-- ⚠️ 逻辑删除口径：D-13 —— INTEGER 0/1，**没有 deleted_at 列**（zjky BaseDO 形式）。
-- ===================================================================

-- ───────────────────────────────────────────────────────────────
-- 1) 知识库级配置：wiki_config
--
-- ⚠️ **本列不在这里加**：V256 建 kb_knowledge_base 时已按源侧 000037
--    （`+wiki_config +indexing_strategy`）建好，再 ALTER 一次会
--    `duplicate column name: wiki_config` ⇒ 新库迁移直接失败（已实测）。
--    本文件只建 wiki 的三张表。
--
-- 源侧是 JSONB，本仓同样存 JSON 文本。字段语义见 WikiConfig：
--   auto_generate            是否自动生成（⚠️ 缺省/缺失 = true，见 KbWikiConfig）
--   synthesis_model_id       写页面的模型（空 = 用 summary_model_id）
--   max_pages_per_ingest     单次入库最多生成/更新多少页（0 = 不限）
--   extraction_granularity   focused / standard / exhaustive
--   content_instructions     文风与侧重（不影响引用）
--   extraction_instructions  抽取时保留哪些概念（不影响输出格式）
--   ingest_batch_size / ingest_map_parallel / ingest_reduce_parallel / ingest_max_inflight
--                            并发与批大小（0 = 用默认值）
-- ───────────────────────────────────────────────────────────────

-- ───────────────────────────────────────────────────────────────
-- 2) wiki_pages → kb_wiki_page
--
-- ⭐ 两条不变量（写错会静默出错）：
--   · chunk_refs 存「证据 chunk id」；⛔ 正文里永远不许出现 chunk id。
--   · folder_id 是归属的**单一真源**；category_path / wiki_path / depth 是它的
--     冗余投影（写时同步），只为免 JOIN 读。⛔ 不要让两者各自演化。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_wiki_page (
    id                 TEXT PRIMARY KEY,
    knowledge_base_id  TEXT NOT NULL,

    -- 页面寻址键：'entity/acme-corp' / 'concept/rag' / 'summary/<knowledgeId>'
    -- ⚠️ 同一知识库内唯一（仅未删除范围内）
    slug               TEXT NOT NULL,
    title              TEXT NOT NULL DEFAULT '',

    -- summary / entity / concept / index / synthesis / comparison
    page_type          TEXT NOT NULL DEFAULT 'summary',
    -- draft / published / archived
    status             TEXT NOT NULL DEFAULT 'published',

    content            TEXT NOT NULL DEFAULT '',
    -- 一行摘要（索引用）
    summary            TEXT NOT NULL DEFAULT '',

    -- 语义父页（可空）；目录归属看 folder_id
    parent_slug        TEXT NOT NULL DEFAULT '',

    -- 目录归属单一真源（空串 = 根）
    folder_id          TEXT NOT NULL DEFAULT '',
    -- 以下三列是 folder 链的冗余投影，写时同步
    category_path      TEXT DEFAULT '[]',      -- JSON 数组
    wiki_path          TEXT NOT NULL DEFAULT '',
    depth              INTEGER NOT NULL DEFAULT 0,
    sort_order         INTEGER NOT NULL DEFAULT 0,

    -- 来源文档 ID 列表（JSON 数组）
    source_refs        TEXT DEFAULT '[]',
    -- 证据 chunk ID 列表（JSON 数组）· ⛔ 不进正文
    chunk_refs         TEXT DEFAULT '[]',
    -- 双向链接缓存（JSON 数组）
    in_links           TEXT DEFAULT '[]',
    out_links          TEXT DEFAULT '[]',

    page_metadata      TEXT DEFAULT '{}',      -- JSON 对象
    aliases            TEXT DEFAULT '[]',      -- JSON 数组

    version            INTEGER NOT NULL DEFAULT 1,

    -- ── audit 字段（zjky BaseDO / TenantBaseDO）─────────────────
    creator            TEXT DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- ⭐ slug 在「未删除」范围内唯一：部分索引（SQLite 支持 WHERE 子句）
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_wiki_page_kb_slug
    ON kb_wiki_page (knowledge_base_id, slug) WHERE deleted = 0;

-- 列表 / 索引页按库取
CREATE INDEX IF NOT EXISTS idx_kb_wiki_page_kb
    ON kb_wiki_page (knowledge_base_id, deleted);

-- 按类型筛（entity / concept / summary…）
CREATE INDEX IF NOT EXISTS idx_kb_wiki_page_type
    ON kb_wiki_page (knowledge_base_id, page_type, deleted);

-- 按目录取子页（目录树展开）
CREATE INDEX IF NOT EXISTS idx_kb_wiki_page_folder
    ON kb_wiki_page (knowledge_base_id, folder_id, deleted);

-- 反查：哪些页面引用了这个文档（删文档时的撤稿定位）
CREATE INDEX IF NOT EXISTS idx_kb_wiki_page_slug
    ON kb_wiki_page (slug);


-- ───────────────────────────────────────────────────────────────
-- 3) wiki_folders → kb_wiki_folder
--
-- 树形目录。parent_id 空串 = 根；path 是物化路径（如 '地点/地址'），
-- depth 是层数（源侧限制 ≤2 级，但表不强制 —— 限制在服务层）。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_wiki_folder (
    id                 TEXT PRIMARY KEY,
    knowledge_base_id  TEXT NOT NULL,
    parent_id          TEXT NOT NULL DEFAULT '',
    name               TEXT NOT NULL,
    path               TEXT NOT NULL DEFAULT '',
    depth              INTEGER NOT NULL DEFAULT 0,
    sort_order         INTEGER NOT NULL DEFAULT 0,

    creator            TEXT DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_wiki_folder_kb
    ON kb_wiki_folder (knowledge_base_id, deleted);

-- 同级同名不该有两个目录（源侧 taxonomy 规划会优先复用已有名）
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_wiki_folder_kb_parent_name
    ON kb_wiki_folder (knowledge_base_id, parent_id, name) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 4) wiki_page_issues → kb_wiki_page_issue
--
-- Lint 问题。severity 源侧在 WikiLintIssueSeverity 里定义（error/warning/info）；
-- status: pending / resolved / ignored。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_wiki_page_issue (
    id                 TEXT PRIMARY KEY,
    knowledge_base_id  TEXT NOT NULL,
    slug               TEXT NOT NULL,
    -- 问题类型（对应 KbWikiLinter 的检查项）
    issue_type         TEXT NOT NULL,
    -- error / warning / info
    severity           TEXT NOT NULL DEFAULT 'warning',
    description        TEXT NOT NULL,
    -- 疑似相关的文档 ID 列表（JSON 数组），便于人工核查
    suspected_knowledge_ids TEXT,
    -- pending / resolved / ignored
    status             TEXT NOT NULL DEFAULT 'pending',
    reported_by        TEXT NOT NULL DEFAULT 'lint',

    creator            TEXT DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- 按库取待处理问题（lint 面板）
CREATE INDEX IF NOT EXISTS idx_kb_wiki_issue_kb_status
    ON kb_wiki_page_issue (knowledge_base_id, status, deleted);

-- 按页面取问题
CREATE INDEX IF NOT EXISTS idx_kb_wiki_issue_slug
    ON kb_wiki_page_issue (knowledge_base_id, slug, deleted);

-- ===================================================================
-- V267: kb_wiki_page_revision —— 页面版本快照（WeKnora Wiki 复刻 · W2-1）
--
-- 源侧对照：000075_wiki_page_revisions.up.sql
--
-- 为什么单独一张表而不是给 kb_wiki_page 加「历史 JSON」列：
--   · 版本是 append-only 证据链，混进主表会让「读当前页」被迫带上全部历史；
--   · 回滚只需复制一行的 content/summary/aliases 回主表，不必解析 JSON。
--
-- ⚠️ 本表 append-only：⛔ 不做 UPDATE，只在每次页面合并时插入一条。
--   回滚会**再插一条新版本**（而不是删旧版本）—— 历史必须可追溯。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_wiki_page_revision (
    id                 TEXT PRIMARY KEY,
    knowledge_base_id  TEXT NOT NULL,
    page_id            TEXT NOT NULL,
    slug               TEXT NOT NULL,

    -- 该快照对应的页面版本号（对应 kb_wiki_page.version）
    version            INTEGER NOT NULL,

    title              TEXT NOT NULL DEFAULT '',
    page_type          TEXT NOT NULL DEFAULT 'summary',
    status             TEXT NOT NULL DEFAULT 'published',
    content            TEXT NOT NULL DEFAULT '',
    summary            TEXT NOT NULL DEFAULT '',
    aliases            TEXT DEFAULT '[]',      -- JSON 数组

    -- 这一版是谁写的：'auto'（自动生成）/ 'manual'（用户编辑）/ 'rollback'
    edit_source        TEXT NOT NULL DEFAULT '',
    editor_id          TEXT NOT NULL DEFAULT '',
    -- 这一版产生的时刻（= 当时主表的 update_time）
    edited_at          TEXT NOT NULL DEFAULT (datetime('now')),

    creator            TEXT DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- 按页面取版本列表（升序展示历史）
CREATE INDEX IF NOT EXISTS idx_kb_wiki_revision_page
    ON kb_wiki_page_revision (page_id, version, deleted);

-- 按 slug 取（源侧端点路径是 /wiki/revisions/{slug}）
CREATE INDEX IF NOT EXISTS idx_kb_wiki_revision_slug
    ON kb_wiki_page_revision (knowledge_base_id, slug, version, deleted);

-- 同一页面同一版本只应有一条快照（重试插入时应被此索引挡住）
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_wiki_revision_page_version
    ON kb_wiki_page_revision (page_id, version) WHERE deleted = 0;

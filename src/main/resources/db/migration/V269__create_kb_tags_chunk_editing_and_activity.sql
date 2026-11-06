-- ===================================================================
-- V269: 标签 / 分块编辑修订 / 分块图片投影 / 活动日志（WeKnora 复刻 · 批次 W4）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   · knowledge_tags            000001（知识库级标签定义）
--   · knowledge_tag_relations   000063（知识 ↔ 标签多对多；同时 DROP 了 knowledges.tag_id）
--   · chunk_revisions           000078（分块编辑历史，append-only）
--   · chunk_images              000113（chunk.image_info 的查询投影）
--   · audit_logs                000044（活动流；本仓对应 kb_audit_log）
--
-- 目标侧取舍：
--   ① 源侧 `chunks` 上的编辑相关列（source_content / content_revision /
--      index_status / last_editor_id / context_header）**V257 已建**，本迁移不重复加。
--   ② 源侧 chunk_images 用触发器从 image_info 增量同步。本仓没有 SQLite 触发器
--      （并发写 + 触发器会让 Flex 的写事务更难诊断），改为**应用层重投影**：
--      每次写 chunk 的 image_info 后调用 KbChunkImageProjector.重新投影该 chunk。
--      投影表仍是「查询加速用」，正文真相仍在 kb_chunk.image_info。
--   ③ audit_logs 源侧是全租户通用表；本仓只服务知识库活动流 ⇒ 命名 kb_audit_log，
--      列与源侧对齐（前端契约不变）。id 用 INTEGER 自增，因为源侧 `after_id` 游标是整数。
--
-- 时间列一律 TEXT + 定长 `yyyy-MM-dd HH:mm:ss`（O-7 / D-12）。
-- 本文件注释不得出现「美元符 + 左大括号」的 Flyway 占位符字面量。
-- ===================================================================

-- ── 1. 知识库标签定义 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_knowledge_tag (
    id                 TEXT PRIMARY KEY,
    knowledge_base_id  TEXT NOT NULL,

    name               TEXT NOT NULL DEFAULT '',
    color              TEXT NOT NULL DEFAULT '',
    sort_order         INTEGER NOT NULL DEFAULT 0,
    -- 源侧 seq_id：新建时取当前最大值 +1，保证稳定排序
    seq_id             INTEGER NOT NULL DEFAULT 0,

    -- ── audit 字段（zjky BaseDO / TenantBaseDO）─────────────────
    creator            TEXT DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- 同一知识库内标签名唯一（仅未删行）。源侧未加唯一约束，但重名会让打标结果歧义。
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_knowledge_tag_kb_name
    ON kb_knowledge_tag (knowledge_base_id, name)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_kb_knowledge_tag_kb
    ON kb_knowledge_tag (knowledge_base_id, deleted, sort_order, seq_id);


-- ── 2. 知识 ↔ 标签（多对多）────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_knowledge_tag_relation (
    knowledge_id       TEXT NOT NULL,
    tag_id             TEXT NOT NULL,
    -- 冗余：按知识库统计打标情况时免 join
    knowledge_base_id  TEXT NOT NULL,

    created_at         TEXT NOT NULL DEFAULT (datetime('now')),
    tenant_id          INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (knowledge_id, tag_id)
);

CREATE INDEX IF NOT EXISTS idx_kb_ktr_knowledge
    ON kb_knowledge_tag_relation (knowledge_id);

CREATE INDEX IF NOT EXISTS idx_kb_ktr_tag
    ON kb_knowledge_tag_relation (tag_id);

CREATE INDEX IF NOT EXISTS idx_kb_ktr_kb
    ON kb_knowledge_tag_relation (knowledge_base_id);


-- ── 3. 分块编辑修订（append-only）──────────────────────────────────
-- 源侧 000078。每次「更新分块 / 回滚分块」都先写一条快照，再把新内容写回 kb_chunk。
-- ⚠️ append-only：回滚 = 新增一条 revision，不删除历史（与 kb_wiki_page_revision 同口径）。
CREATE TABLE IF NOT EXISTS kb_chunk_revision (
    id                 TEXT PRIMARY KEY,
    knowledge_base_id  TEXT NOT NULL,
    knowledge_id       TEXT NOT NULL,
    chunk_id           TEXT NOT NULL,

    -- 版本号从 1 起；同一 chunk 内唯一
    revision           INTEGER NOT NULL,
    content            TEXT NOT NULL DEFAULT '',
    is_enabled         INTEGER NOT NULL DEFAULT 1,

    editor_id          TEXT NOT NULL DEFAULT '',
    -- user | rollback | reparse（源侧只有 user/rollback，reparse 是本仓补充）
    edit_source        TEXT NOT NULL DEFAULT 'user',
    edited_at          TEXT NOT NULL DEFAULT (datetime('now')),

    -- ── audit 字段 ─────────────────────────────────────────────
    creator            TEXT DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_chunk_revision_chunk_rev
    ON kb_chunk_revision (chunk_id, revision);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_revision_chunk
    ON kb_chunk_revision (chunk_id, revision DESC);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_revision_knowledge
    ON kb_chunk_revision (knowledge_id, revision DESC);


-- ── 4. 分块图片投影（chunk.image_info 的查询视图）───────────────────
-- ⚠️ 只做查询加速：真相仍在 kb_chunk.image_info。投影由应用层重算，允许短暂滞后。
CREATE TABLE IF NOT EXISTS kb_chunk_image (
    chunk_id           TEXT NOT NULL,
    image_index        INTEGER NOT NULL,

    knowledge_base_id  TEXT NOT NULL,
    knowledge_id       TEXT NOT NULL,
    chunk_type         TEXT NOT NULL DEFAULT '',

    -- url → original_url → "<chunk_id>#<index>"：去重键（同源侧）
    image_key          TEXT NOT NULL,
    url                TEXT NOT NULL DEFAULT '',
    original_url       TEXT NOT NULL DEFAULT '',
    caption            TEXT NOT NULL DEFAULT '',
    ocr_text           TEXT NOT NULL DEFAULT '',
    -- 观测属性 map（源侧 image_info[i].attrs.attrs）
    attrs              TEXT NOT NULL DEFAULT '{}',

    is_enabled         INTEGER NOT NULL DEFAULT 1,
    status             INTEGER NOT NULL DEFAULT 0,

    created_at         TEXT,
    updated_at         TEXT,
    tenant_id          INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (chunk_id, image_index)
);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_image_listing
    ON kb_chunk_image (knowledge_base_id, tenant_id, created_at, chunk_id, image_index);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_image_copies
    ON kb_chunk_image (knowledge_base_id, tenant_id, image_key, updated_at, chunk_id, image_index);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_image_knowledge
    ON kb_chunk_image (knowledge_id);


-- ── 5. 活动日志（知识库活动流）─────────────────────────────────────
-- 源侧 audit_logs：全租户通用。本仓只服务知识库域，列与之对齐。
-- ⚠️ append-only：只插入，不更新。保留 deleted/tenant_id 只为审计口径一致。
CREATE TABLE IF NOT EXISTS kb_audit_log (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,

    scope_type         TEXT NOT NULL DEFAULT 'knowledge_base',
    scope_id           TEXT NOT NULL DEFAULT '',
    target_type        TEXT NOT NULL DEFAULT '',
    target_id          TEXT NOT NULL DEFAULT '',
    target_user_id     TEXT NOT NULL DEFAULT '',

    actor_user_id      TEXT NOT NULL DEFAULT '',
    actor_role         TEXT NOT NULL DEFAULT '',

    -- 动作枚举（字符串，见 KbActivityActions）：create / update / delete /
    -- reparse / upload / copy / pin / move / tag / chunk_edit / chunk_revert ...
    action             TEXT NOT NULL DEFAULT '',
    -- success | failure | denied
    outcome            TEXT NOT NULL DEFAULT 'success',

    request_method     TEXT NOT NULL DEFAULT '',
    request_path       TEXT NOT NULL DEFAULT '',
    details            TEXT NOT NULL DEFAULT '{}',

    created_at         TEXT NOT NULL DEFAULT (datetime('now')),
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- 活动流按知识库倒序翻页（after_id 游标）
CREATE INDEX IF NOT EXISTS idx_kb_audit_log_scope
    ON kb_audit_log (scope_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_kb_audit_log_action
    ON kb_audit_log (scope_id, action, id DESC);

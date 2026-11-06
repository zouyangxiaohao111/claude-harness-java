-- ===================================================================
-- V265: kb_citation —— 知识库问答的引用落库（WeKnora 复刻 · 批次 B5 第④段）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   `chat_pipeline/references.go` 把检索结果编号后交给模型；
--   `chat_pipeline/citation_sources.go` 把模型答复里出现的引用回填成可点来源。
--
-- 目标侧取舍：
--   · 源侧把引用塞进 message 的 metadata（JSON 列）。本仓另建表，原因：
--     ① 引用需要「按消息查」「按块反查哪些答案用了它」两个方向，JSON 列两者都要全表扫；
--     ② 引用是**证据链**，一旦写入不应被后续编辑覆盖 ⇒ 独立 append-only 表更符合语义。
--   · ⚠️ 本表 **append-only**：只在问答完成时批量插入，不做 UPDATE（重新生成 = 新一批行）。
--     `deleted` 字段保留（zjky 审计口径一致），但业务上不删。
--
-- 表名与源侧 `citations` 不同，避免与将来的通用引用表撞名。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_citation (
    id                TEXT PRIMARY KEY,

    -- 归属（按消息查 / 按会话清理）
    session_id        TEXT NOT NULL,
    assistant_message_id TEXT NOT NULL,

    -- 引用序号：与拼进提示词的编号一一对应（1 起）
    --   ⚠️ 这是**模型答复里 [n] 锚点**的落点。序号重复 = 引用错位，故加唯一约束。
    ref_index         INTEGER NOT NULL,

    -- 被引用的块（chunk 可能因重解析被删 ⇒ 不加外键，允许悬空；前端点不开时降级提示）
    chunk_id          TEXT NOT NULL,
    knowledge_id      TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,

    -- 快照：标题与正文片段（块被删/被改后，历史引用仍能显示当时的证据）
    --   ⚠️ 冗余是有意的 —— 引用是**当时**的证据，不该随后续编辑变化。
    knowledge_title   TEXT,
    snippet           TEXT,

    -- 分块位置（前端「跳转到第 N 段」用）
    chunk_index       INTEGER,

    -- ── audit 字段（zjky BaseDO / TenantBaseDO）─────────────────
    creator           TEXT DEFAULT '',
    create_time       TEXT NOT NULL DEFAULT (datetime('now')),
    updater           TEXT DEFAULT '',
    update_time       TEXT NOT NULL DEFAULT (datetime('now')),
    deleted           INTEGER NOT NULL DEFAULT 0,
    tenant_id         INTEGER NOT NULL DEFAULT 0
);

-- 按消息取全部引用（问答完成后前端拉一次）
CREATE INDEX IF NOT EXISTS idx_kb_citation_message
    ON kb_citation (assistant_message_id, ref_index);

-- 按会话清理 / 按会话统计引用质量
CREATE INDEX IF NOT EXISTS idx_kb_citation_session
    ON kb_citation (session_id, deleted);

-- 按块反查：哪些答案引用过这个块（评估召回质量用）
CREATE INDEX IF NOT EXISTS idx_kb_citation_chunk
    ON kb_citation (chunk_id, deleted);

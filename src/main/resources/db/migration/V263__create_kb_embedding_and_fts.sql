-- ===================================================================
-- V263: kb_embedding + kb_chunk_fts —— 向量与关键词索引（WeKnora 复刻 · 批次 B3）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   `embeddings` 表（11 列，`halfvec` + ParadeDB BM25 + chinese_lindera 分词）
--   `internal/application/service/knowledgebase_search_fusion.go` 的 RRF 融合
--
-- 目标侧差异（都是 SQLite 能力边界，不是简化）：
--   · halfvec → BLOB（float32 小端字节序列）。SQLite 无向量索引，余弦相似度在应用层算。
--   · ParadeDB BM25 → SQLite FTS5 虚拟表。
--   · ★★ 分词器必须用 trigram，不能用默认 unicode61。
--       实测（sqlite-jdbc 3.46.0.0）：unicode61 把中文整段当一个 token
--       ⇒ MATCH '污水' 对内容「农村污水处理设施」**零命中**；
--       trigram 才能做中文子串匹配。这是中文场景的硬性要求。
--       ⚠️ trigram 要求查询串 ≥3 个字符（源码固定 3-gram），≤2 字查询走
--       LIKE 回落（由检索层处理，不在 DDL 里表达）。
--
-- 保真度：BEHAVIOR_EQUIVALENT（打分算法不同，排序不可能逐条一致 —— 见 O-4）
-- ===================================================================

-- ───────────────────────────────────────────────────────────────
-- kb_embedding：向量表
--
-- 为什么独立表而不是往 kb_chunk 加列：
--   源侧就是独立表（`source_id` + `source_type` 多态），且一个 chunk 可能有
--   多个模型/多种用途的向量（正文 / 摘要 / 问题）。加列会锁死「一块一向量」。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_embedding (
    id            TEXT PRIMARY KEY,

    -- 归属（便于按库/按文档批量删，免 JOIN）
    knowledge_base_id TEXT NOT NULL,
    knowledge_id      TEXT NOT NULL,

    -- 多态指向（对齐源侧 source_id + source_type）
    source_id     TEXT NOT NULL,
    -- 当前只有 'chunk'；未来可能有 'knowledge_summary' / 'chunk_question'
    source_type   TEXT NOT NULL DEFAULT 'chunk',

    -- 用哪个模型算的（换模型后旧向量不可比 ⇒ 必须能区分）
    model_id      TEXT NOT NULL,
    -- 该模型产出的维度（冗余存一份，便于校验与分组，免查模型表）
    dimensions    INTEGER NOT NULL,

    -- float32 小端字节序列（长度 = dimensions * 4）
    vector        BLOB NOT NULL,

    -- 向量化时用的原文（= chunk.embeddingContent()：context_header + 正文）
    -- ⚠️ 留这份是为了排查「向量和文本对不上」，不是检索必需
    content_hash  TEXT,

    -- ── audit 字段（zjky BaseDO / TenantBaseDO）─────────────────
    creator       TEXT DEFAULT '',
    create_time   TEXT NOT NULL DEFAULT (datetime('now')),
    updater       TEXT DEFAULT '',
    update_time   TEXT NOT NULL DEFAULT (datetime('now')),
    deleted       INTEGER NOT NULL DEFAULT 0,
    tenant_id     INTEGER NOT NULL DEFAULT 0
);

-- 按块取向量（检索回填 / 重算）
CREATE INDEX IF NOT EXISTS idx_kb_embedding_source
    ON kb_embedding (source_type, source_id, deleted);

-- 按文档/按库批量删（重解析、删文档）
CREATE INDEX IF NOT EXISTS idx_kb_embedding_knowledge
    ON kb_embedding (knowledge_id, deleted);

-- 按模型分组（换模型时找出需重算的行）
CREATE INDEX IF NOT EXISTS idx_kb_embedding_model
    ON kb_embedding (model_id, deleted);


-- ───────────────────────────────────────────────────────────────
-- kb_chunk_fts：关键词检索（FTS5 · trigram 分词）
--
-- 设计取舍：
--   · external content（content='kb_chunk'）能省存储，但要求 kb_chunk 是普通表
--     且 rowid 对齐；本仓 kb_chunk 主键是 TEXT UUID ⇒ 用**独立的镜像表**
--     （contentless 也不行：需要回读原文做高亮与长度统计）。
--   · 因此这里存 chunk_id + 两份文本：content（正文）与 header（上下文头）。
--     两列分开存，检索时可对 header 加权（源侧 embeddingContent 也是先进 header）。
-- ───────────────────────────────────────────────────────────────
CREATE VIRTUAL TABLE IF NOT EXISTS kb_chunk_fts USING fts5(
    chunk_id UNINDEXED,
    knowledge_id UNINDEXED,
    knowledge_base_id UNINDEXED,
    header,
    content,
    tokenize = 'trigram'
);

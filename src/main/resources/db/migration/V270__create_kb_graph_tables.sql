-- V270: 知识图谱抽取产物（WeKnora 复刻 · 批次 W7）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   `internal/application/service/graph.go` 的 graphBuilder（实体/关系抽取、PMI 权重、度数）
--   `internal/application/repository/retriever/neo4j/repository.go`（持久化到 Neo4j）
--   `internal/application/service/graph_search.go`（对外 GraphSearchResult 契约）
--
-- 目标侧差异（本机无 Neo4j，是有意取舍不是简化）：
--   · Neo4j 节点/关系 → 两张 SQLite 表。理由：本仓全部数据都在 SQLite，
--     引入 Neo4j 会让部署多一个必需的外部服务；而图谱的查询模式（按 kb 查全部实体 +
--     按名字匹配）用索引表完全够用。
--   · 源侧 `apoc.merge.node(..., {name, kg: knowledge_id})` 的「同文档同名合并」
--     在本表用 (knowledge_id, name) 唯一约束表达。
--   · ⚠️ 跨文档不合并（与源侧一致）：同名实体在两个文档里是两行。
--     这不是缺陷 —— 合并会把「A 文档的定义」和「B 文档的定义」搅成一个。
--
-- ⚠️ 关系的两端存 **name** 而不是 id：源侧 LLM 返回的就是 title 字符串，
--    而实体 id 是本仓生成的 UUID。存 name 才能让「关系引用了不存在的实体」
--    这类情况被显式检出（见 KbGraphService 的 dangling 处理），
--    id 则会在实体被重抽后全部失效。

-- ── 图谱实体 ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_graph_entity (
    id                 TEXT PRIMARY KEY,

    knowledge_base_id  TEXT NOT NULL,
    -- 实体属于哪篇文档（源侧 `kg` 就是 knowledge_id）
    knowledge_id       TEXT NOT NULL,

    -- 实体名（源侧 Entity.Title）· 同一文档内唯一
    name               TEXT NOT NULL,
    -- 实体类型（源侧限定 11 种，见 KbGraphPrompts.ENTITY_TYPES）
    entity_type        TEXT NOT NULL DEFAULT 'Concept',
    -- 一句话描述（LLM 产出，同源语言）
    description        TEXT,

    -- 属性列表 JSON 数组（源侧 Entity.Attributes）
    attributes         TEXT,
    -- 出现过的分块 ID JSON 数组（源侧 Entity.ChunkIDs）· 图谱检索的证据来源
    chunk_ids          TEXT,
    -- 出现次数（跨块累加）
    frequency          INTEGER NOT NULL DEFAULT 1,
    -- 度数（入度 + 出度，由 calculateDegrees 计算）
    degree             INTEGER NOT NULL DEFAULT 0,

    -- 审计六列（对齐 zjky BaseDO / TenantBaseDO）
    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- ⭐ 同文档同名唯一：重抽同一篇文档应当「替换」而不是「叠加」
--    部分索引（WHERE deleted = 0）让逻辑删的旧行不挡住新行
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_graph_entity_kb_doc_name
    ON kb_graph_entity (knowledge_id, name) WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_kb_graph_entity_kb
    ON kb_graph_entity (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kb_graph_entity_doc
    ON kb_graph_entity (knowledge_id);
CREATE INDEX IF NOT EXISTS idx_kb_graph_entity_name
    ON kb_graph_entity (knowledge_base_id, name);


-- ── 图谱关系 ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_graph_relation (
    id                 TEXT PRIMARY KEY,

    knowledge_base_id  TEXT NOT NULL,
    knowledge_id       TEXT NOT NULL,

    -- 两端用实体 **名字**（见文件头说明）
    source_name        TEXT NOT NULL,
    target_name        TEXT NOT NULL,
    relation_type      TEXT NOT NULL DEFAULT '',
    description        TEXT,

    -- 强度 5..10（源侧 Relationship.Strength，由 LLM 按固定档位判定）
    strength           INTEGER NOT NULL DEFAULT 5,
    -- 最终权重 1..10（PMI 0.6 + 强度 0.4 归一后缩放）
    weight             REAL NOT NULL DEFAULT 1.0,
    -- 两端实体度数之和（源侧 CombinedDegree）
    combined_degree    INTEGER NOT NULL DEFAULT 0,

    chunk_ids          TEXT,

    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- ⭐ 同文档内 (source, target) 唯一：重复抽取应当累加强度而不是产生平行边
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_graph_relation_doc_edge
    ON kb_graph_relation (knowledge_id, source_name, target_name) WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_kb_graph_relation_kb
    ON kb_graph_relation (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_kb_graph_relation_doc
    ON kb_graph_relation (knowledge_id);

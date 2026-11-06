-- ===================================================================
-- V256: kb_knowledge_base —— 知识库主表（WeKnora 复刻 · 批次 B1a）
-- ⚠️ 原编 V254，因 master 已提交 V254__add_settings_channels_enabled.sql（channel 批次）
--    撞号 ⇒ 2026-11-04 改号 V256（真库当时最高版本 253，两版均未被应用过）。
--
-- 源头：腾讯 WeKnora v0.9.0（revision 80e8345）`migrations/versioned/` 里
--       knowledge_bases 表的**最终形态**。⚠️ 不是初始 13 列！
--       初始建表在 000000_init.up.sql，另有 12 个迁移在加/删列，逐条核过：
--         000001 +is_temporary +type +faq_config +question_generation_config -rerank_model_id
--         000004 -vlm_model_id
--         000014 +storage_provider_config
--         000016 +is_pinned +pinned_at
--         000031 +asr_config
--         000036 +vector_store_id
--         000037 +wiki_config +indexing_strategy
--         000043 +creator_id
--         000068 +storage_backend_id
--         000080 +auto_tag_config
--         000101 +profile_config +generated_profile
--       ⇒ 最终 30 列（含 id / created_at / updated_at / deleted_at）。
--
-- ⚠️ 本表比初版多出的关键修正：
--   · rerank_model_id 源侧已 DROP（000001）⇒ 本表**不建**这一列
--   · type / is_temporary / is_pinned / creator_id / vector_store_id / storage_backend_id 等
--     都是后续迁移加的，初版没有 ⇒ 必须建，否则与 API 契约对不上
--
-- 保真度：BEHAVIOR_EQUIVALENT（跨数据库不可能逐字节一致）
--   PostgreSQL JSONB          → SQLite TEXT（存 JSON 字符串）
--   uuid_generate_v4()        → 应用层生成 UUID
--   TIMESTAMP WITH TIME ZONE  → TEXT（yyyy-MM-dd HH:mm:ss）
--   BOOLEAN                   → INTEGER 0/1（SQLite 无 boolean）
--
-- audit 字段：严格照 zjky 框架 BaseDO / TenantBaseDO（2026-11-03 用户裁定）
--   反编译真源 com.zjky.pro.framework.mybatis.core.dataobject.BaseDO
--   + com.zjky.pro.framework.tenant.core.db.TenantBaseDO：
--     create_time / update_time / creator / updater / deleted / tenant_id
--   源侧的 created_at / updated_at / deleted_at / tenant_id 被映射成：
--     created_at  → create_time
--     updated_at  → update_time
--     deleted_at  → deleted (INTEGER 0/1，软删只记「已删」不记何时删)
--     tenant_id   → tenant_id（保留）
--   另新增源侧没有的 creator / updater（zjky BaseDO 要求）。
--
-- ⚠️ 时间格式铁律（实测 sqlite-jdbc 3.46.0.0）：
--   LocalDateTime 直接写库会产出 "2026-11-03T09:05"（T 分隔、秒可省略），
--   而 SQLite datetime('now') 产出 "2026-11-03 12:26:09"（空格分隔）。
--   空格 0x20 < T 0x54 ⇒ 混存时 ORDER BY create_time 结果错误
--   （实测：09:05 排在 12:26:09 之后）。⇒ 写入必须经 KbTimeFormat 统一格式化。
--   证据见 docs/zjkycode/specs/weknora-replica/OPEN_ISSUES.md O-7。
--
-- ⚠️ 本文件注释不得出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （V73/V78/V92..V253 同款警告）。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    -- ── 主键 ───────────────────────────────────────────────────────
    -- 应用层生成的 UUID（36 字符）；源侧 uuid_generate_v4()
    id                      TEXT PRIMARY KEY,

    -- ── 基础 ───────────────────────────────────────────────────────
    name                    TEXT NOT NULL,
    description             TEXT,
    -- 类型：document | faq | wiki（空 = document）。源 000001 加
    type                    TEXT NOT NULL DEFAULT 'document',
    -- 临时知识库（对 UI 隐藏）。源 000001 加
    is_temporary            INTEGER NOT NULL DEFAULT 0,

    -- ── 配置（全部 JSONB → TEXT）────────────────────────────────────
    -- 分块配置（默认值逐字等于源侧）
    chunking_config         TEXT NOT NULL DEFAULT '{"chunk_size": 512, "chunk_overlap": 50, "split_markers": ["\n\n", "\n", "。"], "keep_separator": true}',
    -- 图片处理配置
    image_processing_config TEXT NOT NULL DEFAULT '{"enable_multimodal": false, "model_id": ""}',
    -- 旧版对象存储配置（源侧列名 cos_config，gorm tag 仍映射到此）
    cos_config              TEXT NOT NULL DEFAULT '{}',
    -- VLM 配置
    vlm_config              TEXT NOT NULL DEFAULT '{}',
    -- 抽取配置（图抽取提示词等），可空
    extract_config          TEXT,
    -- FAQ 配置。源 000001 加
    faq_config              TEXT,
    -- 问题生成配置。源 000001 加
    question_generation_config TEXT,
    -- 自动打标配置。源 000080 加
    auto_tag_config         TEXT,
    -- 知识库画像配置与生成结果。源 000101 加
    profile_config          TEXT,
    generated_profile       TEXT,
    -- ASR 配置。源 000031 加
    asr_config              TEXT,
    -- 对象存储 provider 选择（凭据在工作区级）。源 000014 加
    storage_provider_config TEXT,
    -- Wiki 配置与索引策略。源 000037 加
    wiki_config             TEXT,
    indexing_strategy       TEXT,

    -- ── 模型绑定 ───────────────────────────────────────────────────
    -- ⚠️ 源侧 rerank_model_id 已被 000001 DROP ⇒ 本表不建该列
    embedding_model_id      TEXT NOT NULL,
    summary_model_id        TEXT NOT NULL,

    -- ── 外部资源绑定（源侧均无外键约束）─────────────────────────────
    -- 绑定的向量库。源 000036 加
    vector_store_id         TEXT,
    -- 绑定的存储后端。源 000068 加
    storage_backend_id      TEXT,
    -- 创建者 ID。源 000043 加（RBAC 用）
    creator_id              TEXT,

    -- ── 置顶（源 000016 加）────────────────────────────────────────
    is_pinned               INTEGER NOT NULL DEFAULT 0,
    pinned_at               TEXT,

    -- ── audit 字段（严格照 zjky BaseDO / TenantBaseDO）───────────────
    creator                 TEXT DEFAULT '',
    create_time             TEXT NOT NULL DEFAULT (datetime('now')),
    updater                 TEXT DEFAULT '',
    update_time             TEXT NOT NULL DEFAULT (datetime('now')),
    -- 逻辑删除：0 = 未删，1 = 已删（SQLite 无 bit 类型）
    deleted                 INTEGER NOT NULL DEFAULT 0,
    -- 租户编号：zjky 框架本身需要；NexusAI 单机场景恒 0
    tenant_id               INTEGER NOT NULL DEFAULT 0
);

-- 列表查询主路径：租户 + 未删 + 置顶优先 + 创建时间倒序
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_base_list
    ON kb_knowledge_base (tenant_id, deleted, is_pinned, create_time);

-- 按类型筛选（document / faq / wiki）
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_base_type
    ON kb_knowledge_base (tenant_id, deleted, type);

-- 按名称检索
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_base_name
    ON kb_knowledge_base (name);

-- 按创建者筛选（RBAC 用）
CREATE INDEX IF NOT EXISTS idx_kb_knowledge_base_creator
    ON kb_knowledge_base (creator_id);

-- ===================================================================
-- V275: 评测与知识库共享（批次 W11）
--
-- ⭐ 用户 2026-11-05 裁定：
--   ① 评测 = **A 基于知识库真跑**（⛔ 不复刻源侧「仓库内 parquet 样例数据集」——
--      本机没有那份文件，且源侧 DatasetService.GetDatasetByID 忽略入参恒返回同一份，
--      照抄只能产出恒空结果或造假数字）。
--   ② 知识库共享 = **C 建表但无接收方**：表与端点照做，
--      ⚠️ 但本仓是单机单用户（W9 已裁「单机单主体」），**没有任何组织/第二用户**，
--      所以共享记录写进去也不会产生跨用户效果。这一点在 OPEN_ISSUES 如实登记，
--      ⛔ 不假装能跨用户共享。
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   internal/application/service/evaluation.go（457 行，内存任务 + parquet 样例）
--   internal/types/evaluation.go（EvaluationTask / MetricResult）
--   migrations/versioned/000012_organizations.up.sql:57 → kb_shares
-- ===================================================================


-- ───────────────────────────────────────────────────────────────
-- 1. kb_eval_dataset —— 评测数据集（QA 对）
--
-- 源侧从 parquet 读，本仓落表：用户可以自己维护问题与标准答案。
-- ⚠️ 标准答案允许为空（只评测检索质量时不需要答案）。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_eval_dataset (
    id                 TEXT PRIMARY KEY,

    name               TEXT NOT NULL,
    description        TEXT,

    -- 目标知识库（评测固定在这个库上跑）
    knowledge_base_id  TEXT NOT NULL,

    -- 审计六列（对齐 zjky BaseDO）
    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_eval_dataset_name
    ON kb_eval_dataset (name) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 2. kb_eval_case —— 单条 QA 对
--
-- ⚠️ `expect_chunk_ids` 是 JSON 数组：标准答案对应的 chunk id 列表。
--    为空 = 只评测生成质量（不评检索召回）。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_eval_case (
    id                 TEXT PRIMARY KEY,

    dataset_id         TEXT NOT NULL,
    question           TEXT NOT NULL,
    -- 标准答案（可空 = 只评检索）
    expected_answer    TEXT,
    -- JSON 数组：期望命中的 chunk id（可空 = 不评召回）
    expect_chunk_ids   TEXT NOT NULL DEFAULT '[]',
    -- 排序序号（评测按它跑，结果可复现）
    seq                INTEGER NOT NULL DEFAULT 0,

    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_eval_case_dataset
    ON kb_eval_case (dataset_id, seq) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 3. kb_eval_task —— 一次评测任务与结果
--
-- ⚠️ 源侧任务只存内存（进程重启即丢）。本仓落表 —— 评测通常要跑几十秒到几分钟，
--    结果丢了用户就得重跑（而且要与上一次对比）。
--
-- status: pending | running | completed | failed
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_eval_task (
    id                 TEXT PRIMARY KEY,

    dataset_id         TEXT NOT NULL,
    knowledge_base_id  TEXT NOT NULL,

    status             TEXT NOT NULL DEFAULT 'pending',
    error_message      TEXT,

    total              INTEGER NOT NULL DEFAULT 0,
    finished           INTEGER NOT NULL DEFAULT 0,

    -- 汇总指标（JSON）：recall@k / mrr / avgScore 等
    metrics            TEXT NOT NULL DEFAULT '{}',
    -- 逐条结果（JSON 数组）：每条的问题、是否命中、分数
    details            TEXT NOT NULL DEFAULT '[]',

    started_at         TEXT,
    finished_at        TEXT,

    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_eval_task_dataset
    ON kb_eval_task (dataset_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_eval_task_status
    ON kb_eval_task (status) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 4. kb_share —— 资源分享（⚠️ 本仓无接收方，见文件头裁定 ②）
--
-- 源侧有两张同构的表：kb_shares 与 agent_shares
-- （000012_organizations.up.sql，字段完全相同）。
-- 本仓合并成一张，用 resource_type 区分：
--   resource_type = 'knowledge_base' | 'agent'
--
-- 源侧字段：knowledge_base_id / organization_id / shared_by_user_id /
--           source_tenant_id / permission(admin|editor|viewer)
--
-- 本仓保留 organization_id 列（写固定的本地组织 id），
-- 但**没有第二个用户/组织** ⇒ 共享记录不会产生任何跨用户效果。
-- 保留这张表的价值：将来接入多用户时不用改表。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_share (
    id                 TEXT PRIMARY KEY,

    -- knowledge_base | agent（源侧对应 kb_shares / agent_shares 两张表）
    resource_type      TEXT NOT NULL DEFAULT 'knowledge_base',
    resource_id        TEXT NOT NULL,

    -- 本仓恒为本地组织（'local'）；接入多用户后才有真实值
    organization_id    TEXT NOT NULL DEFAULT 'local',
    shared_by_user_id  TEXT NOT NULL DEFAULT 'local_user',
    source_tenant_id   INTEGER NOT NULL DEFAULT 0,
    -- admin | editor | viewer
    permission         TEXT NOT NULL DEFAULT 'viewer',

    creator            TEXT NOT NULL DEFAULT '',
    create_time        TEXT NOT NULL DEFAULT (datetime('now')),
    updater            TEXT NOT NULL DEFAULT '',
    update_time        TEXT NOT NULL DEFAULT (datetime('now')),
    deleted            INTEGER NOT NULL DEFAULT 0,
    tenant_id          INTEGER NOT NULL DEFAULT 0
);

-- 同资源同组织只能有一条（部分唯一，逻辑删后允许重建）
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_share_resource_org
    ON kb_share (resource_type, resource_id, organization_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_share_resource
    ON kb_share (resource_type, resource_id) WHERE deleted = 0;

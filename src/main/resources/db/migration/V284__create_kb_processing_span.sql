-- ===================================================================
-- V284: 知识库处理阶段 span 表 `kb_processing_span`（Trace R1 批 1）
--
-- ⭐ 本表对应源站 WeKnora 的 `knowledge_processing_spans`
--    （000055_knowledge_processing_spans.up.sql；name 由 000066 扩到 255）。
--
-- 为什么需要它：一篇文档要走 解析 / 分块 / 向量化 / 图片理解 / 后处理
-- 五个阶段，出问题时用户只能看到一个笼统的「处理失败」，
-- 看不出「这篇文档到底卡在哪一阶段」。本表把每个阶段的
-- 起止时间、状态、输入输出、错误信息落成 span 树
-- （parent_span_id 串起来），供 Trace 时间线（前端）与排查使用。
--
-- 形态口径：
--   * 时间列用 TEXT（本仓统一 yyyy-MM-dd HH:mm:ss），对齐 V274 等 kb_* 表；
--   * SQLite 无 JSONB ⇒ input/output/metadata 存 JSON 文本（TEXT）；
--   * 审计六列对齐 zjky BaseDO（与其余 kb_* 表一致）；
--   * name 不截断：源站已扩到 255，SQLite TEXT 无长度上限。
--
-- 唯一键 (knowledge_id, attempt, span_id)：同一文档同一 attempt 下
-- span_id 唯一；重跑文档 = attempt+1，旧 attempt 的行原样保留，
-- 这正是源站 repo 的 Upsert / ListByAttempt 语义前提。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_processing_span (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 关联 kb_knowledge.id
    knowledge_id    TEXT    NOT NULL,
    -- 处理轮次：同一文档重跑 = attempt+1，旧轮的行保留
    attempt         INTEGER NOT NULL DEFAULT 1,
    -- span 唯一标识（本仓用 UUID）
    span_id         TEXT    NOT NULL,
    -- 父 span（root 的 parent 为空）
    parent_span_id  TEXT,
    -- 展示名（源站 VARCHAR(255)，000066 扩过；此处不截断）
    name            TEXT    NOT NULL,
    -- root / stage / group / subspan / generation
    kind            TEXT    NOT NULL,
    -- pending/running/done/failed/skipped/cancelled
    status          TEXT    NOT NULL,
    -- 输入快照（JSON 文本）
    input           TEXT,
    -- 输出快照（JSON 文本）
    output          TEXT,
    -- 附加元数据（JSON 文本）
    metadata        TEXT,
    error_code      TEXT,
    error_message   TEXT,
    error_detail    TEXT,
    -- yyyy-MM-dd HH:mm:ss
    started_at      TEXT,
    finished_at     TEXT,
    -- 耗时（毫秒）
    duration_ms     INTEGER,
    -- ── 审计六列（对齐 zjky BaseDO，与其余 kb_* 表一致）──────────
    creator         TEXT NOT NULL DEFAULT '',
    create_time     TEXT NOT NULL DEFAULT (datetime('now')),
    updater         TEXT NOT NULL DEFAULT '',
    update_time     TEXT NOT NULL DEFAULT (datetime('now')),
    deleted         INTEGER NOT NULL DEFAULT 0,
    tenant_id       INTEGER NOT NULL DEFAULT 0
);

-- 同一文档 + 同一 attempt 下 span_id 唯一（源站 repo Upsert 的判据）
CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_span_attempt_span
    ON kb_processing_span (knowledge_id, attempt, span_id);
-- 按 attempt 拉整棵 span 树
CREATE INDEX IF NOT EXISTS idx_kb_span_knowledge_attempt
    ON kb_processing_span (knowledge_id, attempt);
-- 「当前有哪些阶段在跑」的巡检
CREATE INDEX IF NOT EXISTS idx_kb_span_status_started
    ON kb_processing_span (status, started_at);
-- 级联遍历子 span（局部索引：只有非空的才建）
CREATE INDEX IF NOT EXISTS idx_kb_span_parent
    ON kb_processing_span (parent_span_id) WHERE parent_span_id IS NOT NULL;


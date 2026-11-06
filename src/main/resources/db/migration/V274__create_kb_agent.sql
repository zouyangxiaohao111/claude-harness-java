-- ===================================================================
-- V274: 自定义智能体扩展表 `kb_agent`（批次 W10）
--
-- ⭐ 用户 2026-11-05 裁定（本表的设计前提）：
--   「这个 Agent 定义只是定义一些额外的东西。系统提示词啊啥的，都写在 MD 中，
--     kb_agent 中存的是 agent id 嘛，直接查到是哪个专家，他本质应该是这个，
--     最终本质上是 MD，其他的字段只是拓展。」
--
-- ⇒ **MD 是本体，本表只存 MD 装不下的扩展**：
--   存：知识库检索范围 / 检索参数 / 引用开关
--   不存：系统提示词、模型、工具清单、permissionMode —— 这些一律在 agents/<id>.md
--
-- ⚠️ 为什么必须这样分：两边都存同一项就一定会分叉。用户改了 MD 没生效，
--    或改了这里被 MD 覆盖，都无从判断哪个算数。
--
-- ⚠️ 主键 = MD 的 agentType（`name:` + 文件名），不是独立 UUID：
--    用户原话「kb_agent 中存的是 agent id，直接查到是哪个专家」。
--    保证「同一个标识贯穿 MD 与 DB」，不需要第二张映射表。
--
-- 删除语义：界面删除智能体时**同时删 MD 文件与本行**。
--    若 MD 被手工删掉 ⇒ 本行成为孤儿，列表接口如实标 `md_missing: true`
--    （⛔ 不静默忽略、也不自动清理 —— 用户可能只是临时移走文件）。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_agent (
    -- 智能体标识 = agents/<id>.md 的 agentType
    id                          TEXT PRIMARY KEY,

    -- ── 知识库检索范围（源侧 custom_agents.config 的 KBSelectionMode 那组）──
    -- all = 全部可检索库；selected = 只用 knowledge_bases 里的；none = 不检索
    kb_selection_mode           TEXT NOT NULL DEFAULT 'all',
    -- JSON 数组（库 id 列表；kb_selection_mode='selected' 时才看）
    knowledge_bases             TEXT NOT NULL DEFAULT '[]',
    -- 仅在用户 @ 明确提及时才检索知识库（源侧 retrieve_kb_only_when_mentioned）
    retrieve_kb_only_when_mentioned INTEGER NOT NULL DEFAULT 0,

    -- ── 检索参数（源侧同名配置）──────────────────────────────
    embedding_top_k             INTEGER,
    keyword_threshold           REAL,
    vector_threshold            REAL,
    rerank_top_k                INTEGER,
    rerank_threshold            REAL,

    -- ── 回答行为 ────────────────────────────────────────────
    -- 最终回答是否带知识/网页来源引用（null = 默认开，对齐源侧 nil⇒true）
    citation_enabled            INTEGER,

    -- ── 审计六列（对齐 zjky BaseDO，与其余 kb_* 表一致）──────────
    creator                     TEXT NOT NULL DEFAULT '',
    create_time                 TEXT NOT NULL DEFAULT (datetime('now')),
    updater                     TEXT NOT NULL DEFAULT '',
    update_time                 TEXT NOT NULL DEFAULT (datetime('now')),
    deleted                     INTEGER NOT NULL DEFAULT 0,
    tenant_id                   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_agent_selection
    ON kb_agent (kb_selection_mode) WHERE deleted = 0;

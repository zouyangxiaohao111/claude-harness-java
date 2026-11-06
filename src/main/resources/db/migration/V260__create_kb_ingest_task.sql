-- ===================================================================
-- V260: kb_ingest_task —— 知识入库任务队列（WeKnora 复刻 · 批次 B2c）
--
-- ⚠️ 原计划 V258，因并发会话已提交
--    V258__add_settings_goal_checkin_minutes.sql
--    V259__add_settings_subagent_runtime.sql
--    撞号 ⇒ 2026-11-04 改号 V260（真库当时最高版本 259）。
--
-- 源侧对照：WeKnora 用 Redis + asynq 做文档处理队列
--   （internal/application/service/knowledge_process.go ProcessDocument）。
-- 本仓是单机 SQLite、无 Redis ⇒ 用「表 + 定时轮询」等价承接。
--
-- 语义对齐（逐条核过源侧）：
--   state：queued | running | done | failed | dead
--     · queued  = 待认领
--     · running = 已被某个 worker 认领（认领 = 带条件的 UPDATE，见 Mapper）
--     · done    = 处理成功
--     · failed  = 本次失败，仍可重试（retry_count < max_retry 时会被改回 queued）
--     · dead    = 超过重试上限，永久失败（源侧 asynq dead-letter 等价物）
--   attempt：与 kb_knowledge.parse_attempt 对应。旧 attempt 的任务即使还在跑，
--            也必须放弃 —— 否则会把上一轮的 chunk / 计数写进新轮。
--            （源侧 attemptSuperseded，knowledge_process.go:3827）
--
-- 与源侧的差异（如实登记）：
--   · 源侧 asynq 支持延迟/优先级队列；本表只有 created_at 顺序，无优先级。
--   · 源侧任务 payload 是 JSON；本表也放 TEXT JSON，但当前只用于 noop / 子任务参数。
--
-- ⚠️ 时间列形态：created_at / started_at / finished_at 一律由 SQLite
--    datetime('now') 或 KbTimeFormat.now() 写入（yyyy-MM-dd HH:mm:ss 定长），
--    保证 ORDER BY 字典序 == 时间序（O-7）。
-- ===================================================================

CREATE TABLE IF NOT EXISTS kb_ingest_task (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,

    -- 关联的知识条目（kb_knowledge.id）
    knowledge_id  TEXT NOT NULL,

    -- 任务类型：parse | noop（后续批次会加 summary | question | graph | wiki）
    task_type     TEXT NOT NULL,

    -- 见文件头状态机
    state         TEXT NOT NULL DEFAULT 'queued',

    -- 与 kb_knowledge.parse_attempt 对应（陈旧任务识别）
    attempt       INTEGER NOT NULL DEFAULT 0,

    retry_count   INTEGER NOT NULL DEFAULT 0,
    max_retry     INTEGER NOT NULL DEFAULT 3,

    error_message TEXT,
    -- 任务参数（JSON 文本；当前只放 noop 的说明串）
    payload       TEXT,

    created_at    TEXT NOT NULL DEFAULT (datetime('now')),
    started_at    TEXT,
    finished_at   TEXT
);

-- 认领主路径：按 state 过滤 + 按入队时间升序（最老的先跑）
CREATE INDEX IF NOT EXISTS idx_kb_ingest_task_claim
    ON kb_ingest_task (state, created_at);

-- 按知识条目查其全部任务（重解析时作废旧任务 / 前端展示进度）
CREATE INDEX IF NOT EXISTS idx_kb_ingest_task_knowledge
    ON kb_ingest_task (knowledge_id, state);


-- ───────────────────────────────────────────────────────────────
-- kb_knowledge 增列：parse_attempt
--
-- 源侧把 attempt 放在内存 tracker 里（Lite 模式）；本实现必须落库，
-- 否则进程重启后旧任务无法识别（会把上一轮结果写进新轮）。
--
-- SQLite 允许 ADD COLUMN 带 NOT NULL + 非 NULL 默认值；
-- 已有行自动补 0（= 初始尝试）。
-- ───────────────────────────────────────────────────────────────
ALTER TABLE kb_knowledge ADD COLUMN parse_attempt INTEGER NOT NULL DEFAULT 0;

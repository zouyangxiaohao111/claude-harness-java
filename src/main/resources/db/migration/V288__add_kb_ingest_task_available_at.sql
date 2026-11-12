-- ===================================================================
-- V288: kb_ingest_task.available_at —— 失败重试的指数退避（对齐源站 asynq）
--
-- 背景：源站 WeKnora 用 Redis + asynq 做文档处理队列，失败重试由 asynq 按
--       **指数退避**延后投递（asynq 的 RetryDelayFunc 默认就是 n*2 递增）。
--       本仓 V260 的队列表只有 created_at 顺序，没有任何「下次可认领时间」概念
--       ⇒ markFailure 直接把 state 改回 queued，worker 下一轮（1.5s 轮询）
--       立刻又把它捞起来，3 次重试可以在 3 秒内全部打完 —— 对 429 / 上游超时 /
--       5xx 这类「需要等一会儿才会好」的失败完全无效，只是把重试上限白白耗光。
--
-- 本列语义：available_at = 该任务最早可被认领的时刻（秒精度 TEXT 串）。
--   · NULL  → 等价「立即可认领」（兼容旧行；认领 SQL 显式放行 NULL）；
--   · 认领 SQL = state = 'queued' AND (available_at IS NULL OR available_at <= now)；
--   · markFailure（未超上限）写 now + min(base * 2^(n-1), max)，默认 base=2s、max=300s
--     （可配 nexusai.knowledge.ingest.retry-base-ms / retry-max-ms）；
--   · requeue（SQLITE_BUSY 锁冲突）写 now —— 瞬时冲突必须尽快重试，⛔ 不退避。
--
-- 时间形态与既有列一致：yyyy-MM-dd HH:mm:ss（KbTimeFormat.now()），定长
-- ⇒ 字符串字典序 == 时间序，可以直接用 <= 比较。
--
-- 回填：已有行一律置为 created_at（过去的时刻）⇒ 迁移后立即可认领，
-- 不会因为新增一列把线上积压的 queued 任务全部冻住。
-- ===================================================================

ALTER TABLE kb_ingest_task ADD COLUMN available_at TEXT;

UPDATE kb_ingest_task SET available_at = created_at WHERE available_at IS NULL;

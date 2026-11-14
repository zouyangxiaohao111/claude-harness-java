-- V293: 入库流水线六池并发配置（可持久化 + 可热调）
--
-- <p>存 JSON 文本，形如：
--   {"parse":8,"postprocess":2,"enrichment":12,"wiki":8,"maintenance":4,"shared":6,"maxTasksPerTick":5}
--
-- <p>null / 空串 = 未配置 → 回落 application.yml 的 nexusai.knowledge.ingest.* 默认值
-- （8/2/12/8/4/6 + max-tasks-per-tick 5）。
-- 读写走 /api/v1/settings/ingest-concurrency（GET/PUT）；PUT 后由 KbIngestWorker 热调池
-- （旧池保留到在途归零再 shutdown，⛔ 不中断在途任务）。
ALTER TABLE settings ADD COLUMN ingest_concurrency_config TEXT;

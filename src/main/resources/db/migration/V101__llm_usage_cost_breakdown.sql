-- ===================================================================
-- V101: llm_usage_calls 新增「费用分项 + 分项来源」列，并为 started_at 加区间索引
--
-- 编号：本轮原定 V100 —— 但 V100 已被 V100__add_settings_stream_idle_timeout_ms.sql 占用
--   （该文件 2026-10-12 从撞号的 V78 改到「下一可用号」，同款编号规则），且 100 在开发库
--   （target/flex-dbtest/flex.db 的 flyway_schema_history）**已应用**（success=1）⇒ 两条 V100 会让
--   Flyway 扫描期直接报「Found more than one migration with version 100」拒绝启动。
--   故本文件取下一可用号 V101，⛔ 不改既有迁移（已应用迁移字节冻结）。DDL 与设计不变。
--
--   ⚠️ 后人排号时不要用「ls 后取字典序尾部」判断最大号：『V100__』排在『V10__』之后、
--     但排在『V9x/V99』**之前**（字典序 '1' < '9'），用 tail 看尾部会把 V100 漏掉 ——
--     本文件与同款处置的 settings 迁移都是这么踩出来的。请用
--     `ls db/migration | sort -V | tail` 或直接查 flyway_schema_history 的最大 version。
--   ⚠️ 改号后记得清 `target/classes/db/migration/` 里的**旧号残留**（maven 资源拷贝不会删旧文件，
--     残留的 V100__llm_usage_cost_breakdown.sql 会与新号文件/既有 V100 撞号，测试期 Flyway 直接拒启）。
--
-- 背景：V98 只落一个总额 cost_yuan，读侧无法回答「缓存到底省了多少钱」。本次把计价点的
--   分项（{@code ModelCostCalculator.CostBreakdown}）落到明细表，使明细表成为用量的
--   **唯一真源**（读侧聚合下推到 SQL，不再建 rollup 表；sessions 上的 rollup 列仍作视图保留）。
--
-- 列语义（元；DECIMAL(14,8)，可 null = 该行无分项）：
--   cost_input          = 输入侧实付（anthropic = 全部 input 全价；非 anthropic = 扣掉命中后的 miss 全价）。
--   cost_output         = 输出侧实付（两分支同式）。
--   cost_cache_read     = cache 命中（读）实付（按命中折价）。
--   cost_cache_write    = cache 写入实付（仅 anthropic；非 anthropic 记 0.0，deepseek 无 cache_write 档）。
--   cost_no_cache_input = **反事实诊断项**：该轮若一次 cache 都没有（命中/写入都按全价）input 侧要花多少。
--                         ⛔ 不计入 cost_yuan；「cache 省了多少钱」= cost_no_cache_input
--                            − (cost_input + cost_cache_read + cost_cache_write)，由读侧算，本表不摊分不伪造。
--   cost_detail_source  = 分项来源：'PRICED' = V101 起本次调用在计价点算出分项；
--                         'LEGACY' = V101 之前的历史行（以及计价器缺失的兜底）——分项列全 null，
--                         聚合时 LEGACY 单列统计，⛔ 不摊分、不按比例倒推、不伪造分项。
--
-- 不变式：cost_yuan == cost_input + cost_output + cost_cache_read + cost_cache_write（PRICED 行）。
--
-- 存量行：本表当前零行（真库停在 V78，V98/V99 尚未应用），无需回填；DEFAULT 'LEGACY' 只是
--   让「手工/历史插入不填该列」的行可被明确识别为无分项，而不是被当成 PRICED 的 0 分项。
--
-- 索引：区间/时间线读取（started_at 单列，配 V98 的 (session_id, started_at) 前缀索引）。
--   注：started_at 的写入格式本次统一为「本地时区 + 偏移」的 ISO-8601 文本
--   （应用层 SessionUsageLedger 显式写入；DM1 裁定：业务时间列 = ISO-8601 带偏移文本），
--   同偏移下字典序 == 时间序（'+' < '.' < 数字），故区间过滤可直接用字符串比较并吃到本索引。
--
-- ⚠️ 本文件注释**不得**出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （V73/V78/V92..V99 同款警告：application.yml 未关 flyway.placeholder-replacement，
--     SQL 注释也在替换范围内 → Flyway 会把它当未配置占位符并解析失败）。
-- ===================================================================

ALTER TABLE llm_usage_calls ADD COLUMN cost_input          DECIMAL(14,8) NULL;
ALTER TABLE llm_usage_calls ADD COLUMN cost_output         DECIMAL(14,8) NULL;
ALTER TABLE llm_usage_calls ADD COLUMN cost_cache_read     DECIMAL(14,8) NULL;
ALTER TABLE llm_usage_calls ADD COLUMN cost_cache_write    DECIMAL(14,8) NULL;
ALTER TABLE llm_usage_calls ADD COLUMN cost_no_cache_input DECIMAL(14,8) NULL;
ALTER TABLE llm_usage_calls ADD COLUMN cost_detail_source  TEXT NOT NULL DEFAULT 'LEGACY';

CREATE INDEX IF NOT EXISTS idx_llm_usage_calls_started_at ON llm_usage_calls(started_at);

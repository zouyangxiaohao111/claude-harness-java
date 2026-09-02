-- ===================================================================
-- V49: models 表新增价格列（8 列 · DeepSeek 双档计费 · 元/百万 tokens）
--
-- 背景：token usage/cost 上报链路（CC 对齐）需按模型计费。CC 真源
--   ModelCosts（Open-ClaudeCode/src/utils/modelCost.ts:27-33）5 字段
--   inputTokens / outputTokens / promptCacheReadTokens / promptCacheWriteTokens
--   / webSearchRequests；Java 端把 4 个 token 价格列拆「空闲/高峰」双档
--   （用户拍板价格表，空闲=高峰×50%），webSearchRequests 无列
--   （DeepSeek 无 web search 计费，硬编码 0）。
--
-- 单位：元/百万 tokens（值用人民币元，字段名对齐 CC、不换算 USD）。
--   映射：inputTokens→input_price、outputTokens→output_price、
--   promptCacheReadTokens→cache_read_price、promptCacheWriteTokens→cache_write_price。
-- 可空：null = 该模型未配置价格 → 运行时回落 ModelCostCalculator 内置 DeepSeek 默认档。
-- Java 端 camelCase 字段（inputPricePeak/...），MyBatis-Flex 自动 snake↔camel。
-- ===================================================================
ALTER TABLE models ADD COLUMN input_price_peak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN input_price_offpeak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN output_price_peak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN output_price_offpeak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN cache_read_price_peak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN cache_read_price_offpeak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN cache_write_price_peak DECIMAL(10,4) NULL;
ALTER TABLE models ADD COLUMN cache_write_price_offpeak DECIMAL(10,4) NULL;

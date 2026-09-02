-- ===================================================================
-- V37: settings 表新增 websearch_engine / api_key / proxy / websearch_use_small_model 列
--
-- 背景：用户 2026-08-23 拍板「WebSearch 配置统一入 DB settings（4 项）」——
--   WebSearchTool 引擎/key/proxy/小模型开关全部走 DB settings（前端可配置），
--   不再用 @Value 配置文件（application.yml websearch 块废弃引擎/api-key 配置）。
--
-- 列（MyBatis-Flex snake↔camel，同 V34 auto_memory_* 既有列约定）：
--   websearch_engine          ↔ websearchEngine（String，缺省 "anysearch"，Java 端兜底）
--   api_key                   ↔ apiKey（String，空 → 内置默认 as_sk_a95d63d2e77de587a95b88dd9e0de48b 兜底）
--   proxy                     ↔ proxy（String host:port，空 → 直连）
--   websearch_use_small_model ↔ websearchUseSmallModel（Boolean 0/1，对齐 CC tengu_plum_vx3 flag，
--                               缺省 false = 主循环模型）
--   [R1 返工] 字段名 websearchUseSmallModel（小写 s）：MyBatis-Flex camelCase→snake 精确映射
--     websearchUseSmallModel → websearch_use_small_model；勿改回 webSearchUseSmallModel
--     （会映射 web_search_use_small_model，列不存在 → select/update 列错位）。
--
-- 字段语义：api_key / proxy 为 WebSearch 引擎通用配置（websearch_engine 判断走哪个引擎，
--   当前 anysearch 用 api_key 作 Bearer、proxy 作 HttpClient 代理；duckduckgo 走 HTML 抓取
--   不用 api_key，proxy 透传登记为受控残留——WebFetchSecurity HttpClient 无 ProxySelector）；
--   websearch_use_small_model 对齐 CC WebSearchTool.ts:262-265「tengu_plum_vx3」feature flag
--   （useHaiku ? getSmallFastModel() : mainLoopModel，WebSearchTool.ts:280）。
--
-- 类型说明：SQLite 类型亲和（VARCHAR → TEXT 亲和 / BOOLEAN → NUMERIC 亲和），
--   与既有 V1/V34 的 TEXT/INTEGER 存储语义一致；Java 端 Boolean 列由 MyBatis-Flex
--   按 0/1 映射（同 auto_memory_enabled 先例）。
-- ===================================================================
ALTER TABLE settings ADD COLUMN websearch_engine VARCHAR(32);
ALTER TABLE settings ADD COLUMN api_key VARCHAR(255);
ALTER TABLE settings ADD COLUMN proxy VARCHAR(255);
ALTER TABLE settings ADD COLUMN websearch_use_small_model BOOLEAN;

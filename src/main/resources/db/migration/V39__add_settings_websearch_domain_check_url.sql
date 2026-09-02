-- ===================================================================
-- V39: settings 表新增 websearch_domain_check_url 列
-- [websearch-domaincheck] 域预检端点可配置（用户 2026-08-23 拍板 方案 1+2）：
--   空/未配置 → 跳过域预检（skipDomainCheck=true，不依赖 api.anthropic.com）；
--   配置 → 预检该端点（checkDomainBlocklist 保持 can_fetch JSON 语义不变）。
--   与 V38 同风格：websearch_domain_check_url ↔ websearchDomainCheckUrl（String；SQLite VARCHAR → TEXT 亲和）。
--   当前最新迁移 V38，新增 V39 无版本冲突。
-- ===================================================================
ALTER TABLE settings ADD COLUMN websearch_domain_check_url VARCHAR(255);

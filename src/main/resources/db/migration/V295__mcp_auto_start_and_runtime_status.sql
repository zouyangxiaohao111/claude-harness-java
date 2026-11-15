-- ===================================================================
-- V295: MCP 启动自启 + 运行时状态（不落库）+ 全局总闸入 DB
--
-- 背景（2026-11-14 定稿「对齐 CC + 扩展」）：
--   CC 没有独立的「启动自启」字段 —— 语义是「enabled 即启动即连」；
--   本仓在此基础上加一层 auto_start（enabled=总开关，auto_start=启动即连），
--   默认 1 ⇒ 升级后行为与 CC 等价；用户可把某条设为「可用但不自启」。
--
--   运行时状态（connected/pending/failed/needs-auth/disabled/stopped）
--   一律由 McpToolPool 内存态现算，⛔ 不再落库。DB 里旧 status 列会残留
--   「上个进程的 running」→ 面板显示假状态（2026-11-14 实测 rea 即此形态）。
--   ⇒ 删除 status 列；消费侧改判 McpToolPool.isServerConnected(name)。
--
--   全局总闸从 yml（nexusai.mcp.prefetch-on-startup=false）迁到 settings 表，
--   DB 优先、yml 兜底；默认 1（启动即预取）。
--
-- 依赖检查（V1:92/98）：status 上有 idx_mcp_status 索引，
--   SQLite DROP COLUMN 不允许列被索引引用 ⇒ 先 DROP INDEX。
--   SQLite >= 3.35 支持 DROP COLUMN（本仓 V19/V30/V77/V285 同栈先例）。
-- ===================================================================

ALTER TABLE mcp_servers ADD COLUMN auto_start INTEGER NOT NULL DEFAULT 1;
UPDATE mcp_servers SET auto_start = 0 WHERE enabled = 0 OR enabled IS NULL;

DROP INDEX IF EXISTS idx_mcp_status;
ALTER TABLE mcp_servers DROP COLUMN status;

ALTER TABLE settings ADD COLUMN mcp_prefetch_on_startup INTEGER NOT NULL DEFAULT 1;

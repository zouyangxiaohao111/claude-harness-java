-- ===================================================================
-- V11: MCP channel allowlist（Q-37 ledger 白名单改 DB 表）
-- 对齐 CC services/mcp/channelAllowlist.ts getChannelAllowlist()
--   GrowthBook 'tengu_harbor_ledger' 返回 [{marketplace, plugin}]（Zod safeParse 失败 → []）。
-- 插件级粒度：插件批准 = 其全部 channel server 批准（channelAllowlist.ts L1-16 注释）。
-- 时间统一 TEXT（ISO 8601 / datetime('now')），与 V1 mcp_servers 风格一致。
-- ===================================================================
CREATE TABLE mcp_channel_allowlist (
  id          TEXT PRIMARY KEY,
  marketplace TEXT NOT NULL,
  plugin      TEXT NOT NULL,
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(marketplace, plugin)
);
CREATE INDEX idx_mcp_channel_allowlist_mp ON mcp_channel_allowlist(marketplace, plugin);

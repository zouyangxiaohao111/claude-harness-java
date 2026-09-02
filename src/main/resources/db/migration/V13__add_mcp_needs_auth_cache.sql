-- ===================================================================
-- V13: MCP needs-auth 缓存跨实例共享（R2-3）
-- 对齐 CC services/mcp/client.ts mcp-needs-auth-cache.json（config home 文件缓存）
--   McpAuthCacheData = Record<string, {timestamp: number}>，键 = server name
--   （setMcpAuthCacheEntry(name) client.ts:359 / isMcpAuthCached(name) client.ts:280-287）
-- CC 文件在单机多进程间天然共享（config home 目录）；Java 多实例部署共享 DB 为唯一事实源
--   → 落 mcp_needs_auth_cache 表，内存 ConcurrentHashMap 保留为本地快路径（写穿 + miss 读库）。
-- server_name = MCP server 配置名（与 McpNeedsAuthCache 内存键一致）
-- cached_at  = epoch millis（CC entry.timestamp = Date.now()，client.ts:297）
-- TTL 判定在应用层（15min，MCP_AUTH_CACHE_TTL_MS client.ts:257），表只存原始时间戳。
-- 时间统一 TEXT（ISO 8601 / datetime('now')），与 V12 mcp_oauth_tokens 风格一致。
-- ===================================================================
CREATE TABLE mcp_needs_auth_cache (
  server_name TEXT PRIMARY KEY,
  cached_at   INTEGER NOT NULL,              -- epoch millis（CC entry.timestamp = Date.now()）
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

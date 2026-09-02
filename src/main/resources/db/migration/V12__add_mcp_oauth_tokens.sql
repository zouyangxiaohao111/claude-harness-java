-- ===================================================================
-- V12: MCP OAuth token 持久化（Q-01=A，CC keychain → Java DB）
-- 对齐 CC services/mcp/auth.ts SecureStorageData.mcpOAuth[serverKey] 存储形态
--   （saveClientInformation/saveTokens/saveDiscoveryState/invalidateCredentials 全走同一 entry）
-- server_key = getServerKey(serverName, type, url, headers) = sha256(稳定键序 JSON) 16hex 前 16 + serverName| 前缀
--   → 防同名/同配置复用凭据（auth.ts:325-338）
-- 时间统一 TEXT（ISO 8601 / datetime('now')），与 V1 mcp_servers 风格一致。
-- token 敏感字段明文存储：对齐项目现状 McpServerRecord；加密归安全模块后续批（R4 登记）。
-- ===================================================================
CREATE TABLE mcp_oauth_tokens (
  server_key      TEXT PRIMARY KEY,
  server_name     TEXT NOT NULL,
  server_url      TEXT NOT NULL,
  access_token    TEXT,
  refresh_token   TEXT,
  expires_at      INTEGER DEFAULT 0,        -- epoch millis（CC expiresAt = Date.now() + expires_in*1000）
  scope           TEXT,
  client_id       TEXT,
  client_secret   TEXT,
  step_up_scope   TEXT,                     -- CC stepUpScope（redirectToAuthorization 持久化，step-up 复用）
  discovery_state TEXT,                     -- CC discoveryState {authorizationServerUrl, resourceMetadataUrl} JSON
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 预配置 client_id + client_secret（CC mcpOAuthClientConfig[serverKey]，clientInformation 二级回退）
CREATE TABLE mcp_oauth_client_config (
  server_key    TEXT PRIMARY KEY,
  client_secret TEXT,
  created_at    TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

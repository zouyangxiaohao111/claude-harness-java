-- ===================================================================
-- V15: 账号级 OAuth token 持久化（S2）
-- 对齐 CC utils/auth.ts SecureStorageData.claudeAiOauth 单 key（auth.ts:1217-1228）
--   saveOAuthTokensIfNeeded 写 { accessToken/refreshToken/expiresAt/scopes/subscriptionType/rateLimitTier }
--   getClaudeAIOAuthTokens 读同 entry（auth.ts:1289-1291），无 accessToken 返回 null
-- 由 CC 单 key（claudeAiOauth，claude.ai 单 provider 单账号）泛化为 provider|identity 复合键，
--   支持 GitHub / GitLab 等多 provider 多账号并存（CC getOauthConfig() 硬编码 Anthropic 端点，
--   constants/oauth.ts —— 证伪『CC first-party OAuth provider 无关』）。
--
-- 语义登记（避免未来审计误判）：
--   ① expires_at INTEGER 可空，NULL=不过期（对齐 CC isOAuthTokenExpired(null)→false，client.ts:345），
--      区别于 V12 mcp_oauth_tokens 的 DEFAULT 0 惯例（mcp 域 token 必有 expires_in，账号域 GitHub 可能不过期）。
--   ② refresh_token 可空（GitHub OAuth App 无 refresh_token，G-8）——偏离 CC
--      saveOAuthTokensIfNeeded:1204『无 refreshToken 不保存』规则；该规则是 claude.ai
--      inference-only env token 专用过滤，GitHub 无 refresh_token 属正常账号 token，须存。
--   ③ token 敏感字段（access_token/refresh_token）明文存储：加密归安全模块后续批（R4 登记，
--      同 V12 自注口径）；上线前安全评估（中风险）。
--
-- 时间统一 TEXT（ISO 8601 / datetime('now')），与 V12 mcp_oauth_tokens 风格一致。
-- ===================================================================
CREATE TABLE oauth_account_tokens (
  provider      TEXT NOT NULL,
  identity      TEXT NOT NULL,
  access_token  TEXT,
  refresh_token TEXT,                       -- 可空：GitHub OAuth App 无 refresh_token（G-8）
  expires_at    INTEGER,                    -- epoch millis；NULL=不过期（CC isOAuthTokenExpired(null)→false）
  scope         TEXT,                       -- space-joined（CC scopes:string[] 持久化形式，同 mcp_oauth_tokens.scope）
  created_at    TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at    TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (provider, identity)
);

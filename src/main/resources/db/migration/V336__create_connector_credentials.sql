-- ===================================================================
-- V336: 连接器市场 · 连接凭据表（market connector credentials）
--
-- 背景（2026-11-23 · Octop 连接器市场接入 P1）：
--   技能市场「连接器」tab 过去只是「插件壳」（点 + 走插件安装），没有授权状态。
--   本轮把连接器改成「OAuth 授权 → 落凭据 → upsert mcp_servers（user scope）」，
--   需要一个按连接器 key 存连接的实体表。
--
-- 约定：
--   * 连接器 key = mcp server 名（内置 catalog 的 key），一行一连接器；未连接时无行。
--   * 列风格照 V296（provider_codex_oauth）：判别列 + 加密列 + 在途 state。
--     - auth_type 是判别列（oauth2 | apiKey | personalToken | customFields | imapAppPassword）；
--     - access_token_encrypted / refresh_token_encrypted / client_secret_encrypted /
--       extra_encrypted 存 CryptoUtil 密文（base64(iv || ciphertext_with_tag)），
--       ⛔ 绝不落明文、绝不回传前端（对齐 V296:13-14 的密文约定）；
--     - extra_encrypted 存手动凭据的整包 JSON（api key / 自定义字段等非 OAuth 字段）。
--   * client_source 记录 clientId 来源（dcr | catalog | manual）：DCR 注册的 client 在
--     redirect_uri 变化（回调端口回落）时必须重新注册，手工/内置 client 则不能重注册。
--   * state / state_expires_at 是授权流程在途的一次性随机串（CSRF），用完即清。
--   * status 取值多一个 reauth_required（token 过期且刷新失败，需用户重新授权）。
--   * expires_at 为 epoch 毫秒（与 V296 oauth_expires_at 口径一致；NULL = 不过期/未知）。
--   * token_endpoint / scope / redirect_uri 是明文元数据，供刷新与 missingScopes 判定。
--
-- 影响：只新增一张表，不改既有表；可回滚（DROP TABLE）。
-- 注意：迁移编号只增不减，已发布迁移的编号与文件名永不改动。
-- ===================================================================

CREATE TABLE connector_credentials (
  connector_key            TEXT PRIMARY KEY,           -- 连接器 key == mcp server 名
  auth_type                TEXT NOT NULL,              -- oauth2|apiKey|personalToken|customFields|imapAppPassword
  status                   TEXT NOT NULL DEFAULT 'unconnected', -- unconnected|pending|connected|error|disabled
  account                  TEXT,                       -- 授权账号展示名（拿不到时为 NULL，不编造）
  scope                    TEXT,                       -- 已授予 scope（空格分隔，RFC 6749 §5.1）
  token_endpoint           TEXT,                       -- 刷新用 token endpoint（明文）
  redirect_uri             TEXT,                       -- 本轮授权使用的 loopback 回调地址
  access_token_encrypted   TEXT,                       -- CryptoUtil 密文
  refresh_token_encrypted  TEXT,                       -- CryptoUtil 密文
  client_id                TEXT,                       -- OAuth client id（DCR 或手工填）
  client_source            TEXT,                       -- dcr | catalog | manual（决定 redirect 变化时能否重注册）
  client_secret_encrypted  TEXT,                       -- CryptoUtil 密文
  extra_encrypted          TEXT,                       -- 手动凭据整包 JSON（CryptoUtil 密文）
  expires_at               INTEGER,                    -- epoch millis；NULL = 不过期/未知
  state                    TEXT,                       -- 授权中：一次性 state（CSRF）
  state_expires_at         INTEGER,                    -- 授权中：state 过期时间（epoch millis）
  last_error               TEXT,                       -- 最近一次错误（供界面对账）
  created_at               TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at               TEXT NOT NULL DEFAULT (datetime('now'))
);

-- -------------------------------------------------------------------
-- 连接器创建的 mcp_servers 归属标记（JSON）：
--   {"source":"connector","connectorKey":"<key>"}
-- 断开连接只删「带该标记且 connectorKey 匹配」的行，绝不误删用户自建同名 server。
-- 既有行保持 NULL（无标记 = 不是连接器建的，永不被连接器删除）。
-- -------------------------------------------------------------------
ALTER TABLE mcp_servers ADD COLUMN config_json TEXT;

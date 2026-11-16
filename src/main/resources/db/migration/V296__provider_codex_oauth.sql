-- ===================================================================
-- V296: Provider 凭据层 —— OpenAI 订阅（Codex）OAuth 凭据持久化
--
-- 背景（C2a，2026-11-15）：
--   provider 目前只支持「静态 API key」一种凭据形态（api_key_encrypted）。
--   接入 OpenAI 订阅账号（Codex OAuth）后，同一 provider 既可能是 API key，
--   也可能是 OAuth 会话（access/refresh/expires_at/account_id）。
--   ⇒ 加 auth_mode 判别列（api_key | codex_oauth），OAuth 四列与登录在途的
--     state（CSRF）各占一列。
--
-- 约定：
--   * auth_mode 是判别列，读写都必须先看它；缺省 'api_key' ⇒ 升级后行为不变。
--   * oauth_access_encrypted / oauth_refresh_encrypted 存 CryptoUtil 的密文
--     （base64(iv || ciphertext_with_tag)），⛔ 绝不落明文、绝不回传前端。
--   * oauth_expires_at 为 epoch 毫秒（与既有 INTEGER 时间列口径一致）。
--   * oauth_state 为登录流程在途的一次性随机串，用完即清。
-- ===================================================================

ALTER TABLE providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key';
ALTER TABLE providers ADD COLUMN oauth_access_encrypted TEXT;
ALTER TABLE providers ADD COLUMN oauth_refresh_encrypted TEXT;
ALTER TABLE providers ADD COLUMN oauth_expires_at INTEGER;
ALTER TABLE providers ADD COLUMN oauth_account_id TEXT;
ALTER TABLE providers ADD COLUMN oauth_state TEXT;

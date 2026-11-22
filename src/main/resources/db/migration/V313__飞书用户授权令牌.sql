-- V313: 飞书用户授权令牌（「以用户本人名义发消息」的前提）
--
-- 背景：
--   飞书 im/v1/messages 支持两种身份 —— tenant_access_token（应用/机器人）与
--   user_access_token（用户本人）。后者只能通过 OAuth device flow 拿到：
--     POST accounts.feishu.cn/oauth/v1/device_authorization → user_code
--     用户在浏览器同意
--     POST accounts.feishu.cn/oauth/v1/token (grant_type=device_code) → access_token + refresh_token
--   授权是按「应用 × 用户」的，不是按实例 —— 但本仓一个实例对应一个应用凭证，
--   所以令牌落在实例上是准确的（同一实例只可能对应同一个应用）。
--
-- 影响：
--   ⚠️ 三列都可空 —— 绝大多数实例永远不会做用户授权，存量行为不变
--   （canSendAsUser() 读不到 token 时返回 false ⇒ 与今天完全一致）。
--   ⚠️ user_token / user_refresh_token 存的是**密文**（与 credential 同一套 CryptoUtil），
--   绝不落明文；user_open_id 不是敏感信息（飞书可见字段），明文方便排障。
--
-- 注意：迁移编号只增不减，已发布迁移的编号与文件名永不改动。

ALTER TABLE im_channel_instance ADD COLUMN user_token TEXT;
ALTER TABLE im_channel_instance ADD COLUMN user_refresh_token TEXT;
ALTER TABLE im_channel_instance ADD COLUMN user_token_expires_at TEXT;
ALTER TABLE im_channel_instance ADD COLUMN user_open_id TEXT;

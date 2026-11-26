-- ===================================================================
-- V338: Google 订阅（Gemini Code Assist / Cloud Code）—— project 持久化
--
-- 背景（2026-11-25）：
--   Google 订阅登录成功后，发请求除 access token 外还必须携带 Cloud Code
--   project（loadCodeAssist / onboardUser 得到）。该值与 chatgpt-account-id
--   语义无关，故不复用 oauth_account_id，单开一列。
--
-- 约定：
--   * null = 未取得（登录未完成 / 账号无资格 / 需要绑定 GCP project）；
--   * auth_mode='google_oauth' 时，只有 token + project 齐备才算 ready；
--   * 非机密元数据：可出现在 status 接口；⛔ 但绝不回传 token / state。
-- ===================================================================

ALTER TABLE providers ADD COLUMN oauth_project TEXT;

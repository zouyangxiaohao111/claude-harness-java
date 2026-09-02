-- ===================================================================
-- V2: 添加 api_key_encrypted 列（AES/GCM 密文）以便解密后用于真实 LLM 调用
-- api_key_hash (SHA-256) 保留用于校验 / 防止明文泄露
-- ===================================================================

ALTER TABLE providers ADD COLUMN api_key_encrypted TEXT;

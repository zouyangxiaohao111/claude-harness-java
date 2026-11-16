-- [V299] 提供商归属品牌：一个品牌可以有多个连接（不同 Key / 不同账号）。
-- brand_key 对应 BuiltinProviderCatalog.BRANDS[].key；NULL = 自定义连接。
ALTER TABLE providers ADD COLUMN brand_key TEXT;

CREATE INDEX IF NOT EXISTS idx_providers_brand_key ON providers(brand_key);

-- 市场管理员模式（默认关）：普通用户看不到「市场管理」（版本历史/回滚/全量清单），
-- 但上架能力（市场弹窗「＋ 添加」）对所有人开放。登录权限上线后改按角色判定。
ALTER TABLE settings ADD COLUMN market_admin_enabled INTEGER;

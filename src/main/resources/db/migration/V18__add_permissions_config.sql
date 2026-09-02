-- ===================================================================
-- V16: permissions_config 单行配置表 · 数据库一键禁用 bypassPermissions 开关
-- 对齐 CC Statsig org 门 'tengu_disable_bypass_permissions_mode'
--   （permissionSetup.ts:701 / :934 / :1374，checkStatsigFeatureGate_CACHED_MAY_BE_STALE）
--
-- 语义登记（避免未来审计误判）：
--   ① 本项目无 Statsig infra，CC 生产 Config.defaults() 恒 ()->false（org 门永关）。
--      用户拍板（方案 A）：数据库存开关，启动读一次 + 登录重读（refresh）+ REST 管理端点，
--      替代 CC Statsig 门（Java 端以 PermissionConfigProvider.isBypassPermissionsDisabled()
--      作为 InitialPermissionModeResolver.Config.statsigDisableBypassPermissionsMode）。
--   ② 单行配置 id=1 + CHECK 约束（同 V1 settings 单例表风格，id 恒 1）。
--   ③ disable_bypass_permissions INTEGER NOT NULL DEFAULT 0：
--      0=不禁用（CC gate false，bypassPermissions 可用，默认）；1=禁用（CC gate true，禁用门关闭）。
--   ④ 时间 TEXT（ISO 8601 / datetime('now')），与 V1/V12/V15 风格一致。
-- ===================================================================
CREATE TABLE permissions_config (
  id                          INTEGER PRIMARY KEY CHECK (id = 1),
  disable_bypass_permissions  INTEGER NOT NULL DEFAULT 0,
  created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO permissions_config (id) VALUES (1);

-- IM 配置（单行）。
--
-- 为什么是单行而不是 key-value：
--   这 6 个开关是**同一语义组**（IM 的安全与权限），永远一起读、一起写；
--   单行让类型与默认值都写在 DDL 里，前端一次拿到全部，不用拼 key。
--   后续若要加互不相关的配置（如默认项目路径），再起一张表或加列 —— 编号只增不减。
--
-- 为什么布尔用 INTEGER：
--   SQLite 无原生 BOOLEAN，本仓约定用 INTEGER 存 0/1，Java 侧映射成 Integer 再转 boolean。
--
-- 默认值与原型一致：readAll/autoBind/existingOnly/takeoverConfirm/audit 开，
-- aiDirect 关（AI 直接发消息给真人默认需要确认）。
CREATE TABLE IF NOT EXISTS im_config (
    id               TEXT PRIMARY KEY,
    read_all         INTEGER NOT NULL DEFAULT 1,
    auto_bind        INTEGER NOT NULL DEFAULT 1,
    ai_direct        INTEGER NOT NULL DEFAULT 0,
    existing_only    INTEGER NOT NULL DEFAULT 1,
    takeover_confirm INTEGER NOT NULL DEFAULT 1,
    audit            INTEGER NOT NULL DEFAULT 1,
    creator          TEXT,
    create_time      TEXT NOT NULL DEFAULT (datetime('now')),
    updater          TEXT,
    update_time      TEXT NOT NULL DEFAULT (datetime('now')),
    deleted          INTEGER NOT NULL DEFAULT 0
);

-- 预置唯一一行：Service 读取时不必区分"没有配置"与"全是默认值"。
INSERT OR IGNORE INTO im_config (id, creator) VALUES ('imcfg-default', 'system');

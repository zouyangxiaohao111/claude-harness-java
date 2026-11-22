-- V318 · 「消息扫描」系统任务的任务级暂停开关
--
-- ══════════════════════════════════════════════════════════════════════════
-- 一、为什么不复用 im_scan_channel.enabled
-- ══════════════════════════════════════════════════════════════════════════
-- im_scan_channel 是**渠道级**配置（一行 = 要不要扫这个实例），暂停是**任务级**
-- 开关（整个「消息扫描」停不停）。两者的语义不同：
--   · 暂停后**渠道勾选必须原样保留** —— 用户恢复时不该重新挑一遍渠道；
--   · 如果靠「把所有渠道置 enabled=0」来表达暂停，恢复时就无从知道原来勾了哪些。
-- ⇒ 单起一张单行表，只存「暂停」这一个事实。
--
-- ══════════════════════════════════════════════════════════════════════════
-- 二、为什么不加在 im_config 上
-- ══════════════════════════════════════════════════════════════════════════
-- im_config 是「IM 安全与权限」开关组，且前端 updateConfig 是**全量 PUT**：
-- 往那张表加列而不改前端类型，保存任意一个安全开关都会把新列静默写回默认值。
-- 扫描任务的暂停与那组安全开关也不属于同一语义，不混表。
--
-- 默认值 = 0（未暂停）：行为与 V315/V317 完全一致，升级后不会让扫描悄悄停掉。
CREATE TABLE IF NOT EXISTS im_scan_task (
    id          TEXT PRIMARY KEY,
    -- 0 = 运行中，1 = 已暂停；SQLite 无布尔类型，本仓统一 INTEGER
    paused      INTEGER NOT NULL DEFAULT 0,
    creator     TEXT,
    create_time TEXT NOT NULL DEFAULT (datetime('now')),
    updater     TEXT,
    update_time TEXT NOT NULL DEFAULT (datetime('now')),
    deleted     INTEGER NOT NULL DEFAULT 0
);

-- 预置唯一一行：Service 读不到行时也按「未暂停」处理，但预置让
-- update(entity) 有稳定的行可写（对齐 im_config 的做法）。
INSERT OR IGNORE INTO im_scan_task (id, creator) VALUES ('scan-default', 'system');

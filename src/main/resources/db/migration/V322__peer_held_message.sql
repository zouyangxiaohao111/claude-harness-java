-- ===================================================================
-- V322: peer_held_message（跨会话待收件持久化）
--
-- 背景（SendMessage 跨会话 · 待收件持久化 + 实时事件）：
--   原 hold 只写进程内 ConcurrentHashMap ⇒ 重启即全丢；前端「未读」= 列表条数，
--   每次重启后 pending 重新算未读 ⇒ 重复提醒。本表把 hold 落 SQLite：
--     status = pending 且 seen_at IS NULL ⇒ 未读；
--     seen_at 有值 ⇒ 已读（重启后不再重复提醒，仍可批准）。
--   终态（accepted/rejected/expired）保留审计，不再出现在列表里。
--
-- 语义：
--   message_id 唯一（= PeerEnvelope.messageId，幂等键；重复 hold 用 INSERT OR IGNORE）。
--   hop_chain 存 JSON 数组（重建 PeerEnvelope 必需；逐段 24 hex）。
--   source_endpoint_epoch / target_endpoint_epoch 是发送时的代次快照；
--     重启后 live registry 清空 ⇒ 读侧按 sessionId 重新 resolve，不把旧代次当判死依据。
--   status 不落 CHECK（仓库惯例：应用层枚举封闭，扩展走 append 迁移，见 V98 注释）。
--   body 是用户数据（与既有 messages 表同一本机信任边界）；不存任何 endpoint token。
--
-- 索引：列表按 (target_session_id, status) 过滤并按 created_at 排序；
--   单独 created_at 索引供清理/审计扫描。
--
-- 版本号说明：当前最高 V303；V304 未被占用（V301 归 DSH 副本自有迁移）。
--   ⛔ 不修改历史迁移。
-- ===================================================================
CREATE TABLE IF NOT EXISTS peer_held_message (
    message_id            TEXT PRIMARY KEY,
    target_session_id     TEXT NOT NULL,
    source_session_id     TEXT,
    source_title          TEXT,
    source_kind           TEXT,
    from_name             TEXT,
    from_mode             TEXT,
    from_address          TEXT,
    hop_chain             TEXT,
    summary               TEXT,
    body                  TEXT NOT NULL,
    reason                TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'pending',
    source_endpoint_epoch INTEGER NOT NULL DEFAULT 0,
    target_endpoint_epoch INTEGER NOT NULL DEFAULT -1,
    notify_when_idle      INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT NOT NULL,
    seen_at               TEXT
);

CREATE INDEX IF NOT EXISTS idx_peer_held_target_status
    ON peer_held_message(target_session_id, status);

CREATE INDEX IF NOT EXISTS idx_peer_held_created
    ON peer_held_message(created_at);

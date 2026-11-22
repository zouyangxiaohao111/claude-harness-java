-- ===================================================================
-- V327: peer_held_message 过期结算（对齐 CC 2.1.328 实测语义）
--
-- 背景（2026-11-20 用户裁定）：
--   待确认（hold）消息**没有**专用 TTL。用户看到的「过期」是确认卡的通用对话框超时：
--   默认 300000 ms（5 分钟），可由环境变量 NEXUSAI_USER_DIALOG_TIMEOUT_MS 覆盖。
--   结算只有三种：
--     ① 用户批准 / 拒绝；
--     ② 接收方会话关闭（close/delete）⇒ 全部结转 expired；
--     ③ 同一接收方 pending 超过 100 条 ⇒ 淘汰最旧为 expired。
--   过期后必须通知发送方（写入发送方会话记录，不是一次性提示）。
--
-- 语义：
--   last_shown_at      该行最后一次被待收件列表展示（前端拉取）的时刻，epoch millis。
--   pending_expires_at 到期时刻 = last_shown_at + 对话框超时，epoch millis；
--                      NULL = 从未被展示 ⇒ 不计时（「未展示不累计」）。
--   status='expired'   终态（与 accepted/rejected 同级）：列表不再返回；
--                      发送方会话收到一条落库的失败通知。
--
--   ⛔ 用 epoch millis 而不是 ISO 字符串：到期判定是范围比较（pending_expires_at <= now），
--      字符串形态在「带/不带小数秒」混存时字典序会误判（例如 ...00Z 会排在 ...00.123Z 之后）。
--
-- 版本号说明：迁移目录当前最高 V326（V324 被本树外的迁移占用），V327 为下一个空号。
--   ⛔ 不修改历史迁移。
-- ===================================================================
ALTER TABLE peer_held_message ADD COLUMN last_shown_at INTEGER;
ALTER TABLE peer_held_message ADD COLUMN pending_expires_at INTEGER;

CREATE INDEX IF NOT EXISTS idx_peer_held_expiry
    ON peer_held_message(target_session_id, status, pending_expires_at);

-- V312: 新建 im_send_audit（IM 对外发言审计表）
--
-- 背景（为什么需要这张表）：
--   IM 里的「对外发言」有两种身份：**机器人名义**（通知）与**用户本人名义**（AI 代替用户说话）。
--   后者的社会后果完全不同 —— 对方会以为「这句话是用户本人说的」。
--   机器人通知发错了无所谓，但「以用户名义发错一句话」会变成用户的社会信誉问题。
--   因此每一次以用户名义的对外发言都必须**留痕可追溯**：
--   事后能回答「这句话是谁让 AI 发的、发给谁、发了什么、什么时间、实际是什么身份」。
--
-- 为什么单独建表而不是复用 im_config.audit 开关：
--   im_config.audit 只是「要不要记」的开关，本表是「记下来」的地方，两者职责不同。
--
-- 字段说明：
--   target_chat_id  目标聊天的 im_chat.id（可空：聊天不存在时投递失败也要留痕）
--   peer_id         平台侧会话标识（群 id / 对端 id），用于事后按平台查
--   platform        wechat | feishu | wecom | dingtalk
--   identity        **实际**使用的身份：BOT | USER
--   requested_identity 请求的身份：BOT | USER
--                   ⚠️ 分两列记，是因为「请求 USER 但被降级成 BOT」是必须能查出来的事件
--                   —— 那正是「用户以为是自己说的话、实际是机器人说的」的高危场景。
--   text_preview    正文前 200 字（⛔ 只留摘要，不落全文，避免审计表变成内容仓库）
--   attachment_count 附件数量
--   status          SENT | SKIPPED | FAILED
--   error_code      失败码（成功时为空）
--   reason          失败/降级原因（成功时可能为空）
--
-- 索引：按时间倒序查最近动作；按目标聊天查历史。
CREATE TABLE IF NOT EXISTS im_send_audit (
    id                 TEXT PRIMARY KEY,
    target_chat_id     TEXT,
    peer_id            TEXT,
    platform           TEXT,
    identity           TEXT,
    requested_identity TEXT,
    text_preview       TEXT,
    attachment_count   INTEGER NOT NULL DEFAULT 0,
    status             TEXT,
    error_code         TEXT,
    reason             TEXT,
    created_at         TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_im_send_audit_created_at ON im_send_audit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_im_send_audit_target ON im_send_audit (target_chat_id);

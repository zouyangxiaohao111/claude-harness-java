-- ===================================================================
-- V302.1: IM 渠道实例表
--
-- 背景：IM 频道页「频道」tab 要管理**实例**（同一平台可加多个：两个微信号、
--   两个飞书租户）。实例是「一条到平台的长连接」，**不归属任何会话** ——
--   任何会话都能用它发消息（发送即接线，接线关系在 mcp_channel_binding）。
--
-- 与既有表的关系：
--   · mcp_channel_binding（V255）= 渠道 id ↔ 会话（MCP 通道的归属）
--   · im_channel_instance（本表）= 平台实例本身（凭证/连接状态/游标）
--   两者不是替代关系：本表回答「连上了哪些账号」，绑定表回答「消息给哪个会话」。
--
-- ⚠️ last_cursor 必须落库（用户裁定 + 真机实测教训）：
--   微信 iLink 的 getupdates 是**单消费者、读走即销毁**（没有 ack 重投）。
--   游标只在内存 ⇒ 进程重启后要么重复消费要么丢消息。所以本列是必需的，
--   不是可选优化。
--
-- ⚠️ credential 存加密后的 JSON，界面上**不回显明文**（前端只拿 masked 摘要）。
-- ===================================================================
CREATE TABLE IF NOT EXISTS im_channel_instance (
  id              TEXT PRIMARY KEY,
  platform        TEXT NOT NULL,                        -- wechat | feishu | wecom | dingtalk
  display_name    TEXT NOT NULL,                        -- 用户可改，列表里展示
  credential      TEXT,                                 -- 加密 JSON（botToken / botId+secret / appId+secret）
  status          TEXT NOT NULL DEFAULT 'disconnected',  -- connected | disconnected | error
  status_detail   TEXT,                                 -- 断线原因 / 最近错误（给人看的一句话）
  last_active_at  TEXT,
  last_cursor     TEXT,                                 -- 微信 get_updates_buf：单消费者游标
  config_json     TEXT,                                 -- 平台特有开关（autoReply / groupAtOnly / ...）
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_im_instance_platform ON im_channel_instance(platform);
CREATE INDEX IF NOT EXISTS idx_im_instance_status   ON im_channel_instance(status);

-- V305: IM 聊天目录（im_chat）
--
-- 背景：界面上「任务」tab 要展示的是**IM 聊天**（张三 / 产品需求群），
--   而既有 mcp_channel_binding（V255）只到**渠道**粒度（channelId = wechat/feishu），
--   拿不到"这个渠道下具体是哪个聊天、最后说了什么"。两者不是同一层：
--     · mcp_channel_binding = MCP 通道归属（协议层：消息投给哪个会话）
--     · im_chat（本表）        = 产品层聊天目录（展示层：谁在跟我说话、聊到哪了）
--
-- 影响：只新增表，不动既有表；可回滚（DROP TABLE）。
--
-- ⚠️ 单一写者约束（避免两个地方都能改接线）：
--   产品层的「聊天 → 任务」接线只写本表的 bound_session_id。
--   mcp_channel_binding.session_id 仍由 MCP 门序维护，两者**不互相回写**；
--   将来若要合并，必须先定谁是权威源，⛔ 不要两处都写。
--
-- ⚠️ 数据来源：由平台适配器在收到消息时 upsert（当前适配器未接入 ⇒ 本表可为空）。
--   表先建好是为了让「任务」tab 的结构与设计一致，不靠猜字段。
--
-- ⚠️ 迁移编号只增不减，已发布迁移的编号与文件名永不改动。
CREATE TABLE IF NOT EXISTS im_chat (
  id                   TEXT PRIMARY KEY,
  instance_id          TEXT NOT NULL,          -- 指向 im_channel_instance.id
  platform             TEXT NOT NULL,          -- wechat | feishu | wecom | dingtalk

  peer_id              TEXT NOT NULL,          -- 平台侧聊天标识（wxid / oc_xxx / chatid / cid）
  peer_name            TEXT,                   -- 展示名（备注 > 群名 > peer_id）
  chat_kind            TEXT NOT NULL DEFAULT 'p2p',  -- p2p | group
  member_count         INTEGER,                -- 群人数（p2p 为空）

  last_message_preview TEXT,                   -- 最后一条消息摘要（给列表用，非全文）
  last_message_at      TEXT,

  -- 产品层接线（唯一权威）
  bound_session_id     TEXT,
  bind_mode            TEXT,                   -- auto（ChannelSend 首次发送自动接）| manual
  bound_at             TEXT,

  created_at           TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at           TEXT NOT NULL DEFAULT (datetime('now')),

  -- 同一实例下同一个 peer 只能有一行（适配器据此 upsert）
  UNIQUE(instance_id, peer_id)
);

CREATE INDEX IF NOT EXISTS idx_im_chat_instance ON im_chat(instance_id);
-- 一个聊天只能接一个任务，但一个任务可接多个聊天 ⇒ 该列只普通索引（不加 UNIQUE）
CREATE INDEX IF NOT EXISTS idx_im_chat_bound_session ON im_chat(bound_session_id);
CREATE INDEX IF NOT EXISTS idx_im_chat_last_msg ON im_chat(last_message_at);

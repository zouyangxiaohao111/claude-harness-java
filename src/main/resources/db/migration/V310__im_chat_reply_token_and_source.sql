-- V310: im_chat 补「回复令牌」与「数据来源」两列
--
-- 背景（NXS-UC-CHN-020/021 主动投递）：
--   ① 微信 iLink 主动发消息**必须带 context_token**（来自用户最近一次来信），
--      空 token 会发出空白消息（PIT-WX-008）⇒ 需要一列把它存住。
--   ② im_chat 的数据有多个来源：入站消息、本地微信扫描（wechat_conversation，V301）、
--      平台列表拉取（企微 /cli/chat/groups/list、飞书 /open-apis/im/v1/chats）。
--      没有来源标记时，后写的会覆盖先写的（例如扫描把入站拿到的 reply_token 冲掉）。
--
-- 影响：只加列（ALTER TABLE ADD COLUMN），不改既有列、不动既有数据；
--   新增列全部可空/有默认值 ⇒ 既有行不需要回填，回滚只需忽略新列。
--
-- ⚠️ reply_token 是敏感值（可用它向对端发消息）：
--   ① 不得出现在任何 DTO / 日志 / 异常消息里；
--   ② 只在 ImSendService 内部读取，用完即弃；
--   ③ 它不是「凭证」，而是「会话上下文」——过期后由下一次入站消息刷新。
--
-- ⚠️ 迁移编号只增不减，已发布迁移的编号与文件名永不改动。
ALTER TABLE im_chat ADD COLUMN reply_token TEXT;
ALTER TABLE im_chat ADD COLUMN reply_token_expires_at TEXT;

-- 数据来源：inbound（收到消息）| scanned（本地微信扫描）| listed（平台列表拉取）
-- 默认 inbound 与 V305 建表注释「由平台适配器在收到消息时 upsert」一致。
ALTER TABLE im_chat ADD COLUMN source TEXT NOT NULL DEFAULT 'inbound';

-- 最后一条**出站**消息时间（区分「对方说了话」与「我说了话」，列表排序用）
ALTER TABLE im_chat ADD COLUMN last_outbound_at TEXT;

CREATE INDEX IF NOT EXISTS idx_im_chat_source ON im_chat(source);

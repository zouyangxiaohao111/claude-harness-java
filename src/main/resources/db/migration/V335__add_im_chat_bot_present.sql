-- V335: add_im_chat_bot_present
--
-- 背景：飞书「本人所在的群」同步会把「机器人不在里面」的群也写进 im_chat
--       （see ImChatSyncService.syncFeishuUserChats）。这类行看起来可绑定，
--       但群里 @ 机器人不会推事件、拉历史也会被平台拒绝 —— 绑了等于静默失效。
--       依据 larksuite/cli 源码（shortcuts/im/im_chat_list.go）：
--       bot 身份的 GET /im/v1/chats 返回的就是「机器人作为成员的群」，
--       而 bot 身份列不到 p2p（bot_strip_p2p）⇒ 单聊不能据此判死。
--
-- 影响：im_chat 增两列，纯增量、可回滚（旧代码忽略这两列即可）。
--   语义：
--     bot_present = 1      → 机器人确认在该会话（群列表里有，或 p2p / 微信恒真）
--     bot_present = 0      → 机器人不在该群（列表里没有；仅由「平台列表成功返回」推导）
--     bot_present IS NULL  → 尚未判定过 ⇒ 读侧一律按「可达」处理，避免上线首日把旧行全隐藏
--   bot_present_at = 最近一次判定的时刻（ISO-8601），排查「为什么这个群不见了」用。
--
-- 注意：迁移编号只增不减，已发布迁移的编号与文件名永不改动。
ALTER TABLE im_chat ADD COLUMN bot_present INTEGER;
ALTER TABLE im_chat ADD COLUMN bot_present_at TEXT;

-- V314 · 飞书授权用户的**昵称**
--
-- 为什么需要这一列：
--   `im.message.receive_v1` 事件只带 `sender.sender_id.open_id`，**不带昵称、不带会话名**。
--   飞书**单聊的 chat_id（oc_…）本身也没有名字**（真机实测 `GET /im/v1/chats/{chat_id}`
--   返回 `chat_mode=p2p`、`name=""`、只有 `owner_id`）。
--   而应用身份的通讯录接口（`GET /contact/v3/users/{open_id}`）在「权限范围为空」时
--   **只返回 open_id/union_id，不返回 name**（真机实测）。
--
--   ⇒ 唯一稳定拿得到名字的地方是**用户授权时**：`GET /open-apis/authen/v1/user_info`
--   （user_access_token）返回的是「授权人自己」的资料，不受应用通讯录范围限制。
--   所以把 name 和令牌一起存下来，入站 p2p 消息用它当会话名。
--
-- 适用范围（必须写清，避免误用）：
--   只有「p2p 且 sender.open_id == user_open_id」时才代表「你本人在跟机器人说话」；
--   别人私聊机器人时对端 open_id 不同 ⇒ **不能拿这一列当他们的名字**。
--
-- 可空：老数据/未授权实例没有它；UI 侧回落显示 peer_id 尾号。

ALTER TABLE im_channel_instance ADD COLUMN user_display_name TEXT;

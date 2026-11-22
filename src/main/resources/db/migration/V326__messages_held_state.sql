-- ===================================================================
-- V326: messages.held_state（待确认来信进会话记录）
--
-- 背景（用户实测 CC 后裁定）：
--   CC 的待确认（hold）来信会进入会话记录（transcript / messages 表），下次打开仍照常
--   渲染成那条待确认卡片；同时它未获批准前<b>不会</b>作为上下文交给模型。
--   本仓此前只在 peer_held_message 存 hold 行，重启后只能从「待收件」接口渲染 ——
--   会话记录里没有这条消息，语义与 CC 不一致。
--
-- 语义（应用层枚举，不落 CHECK —— 仓库惯例：扩展走 append 迁移）：
--   held_state = 'pending'    hold 判定通过、尚未批准：GET /messages 可见（渲染待确认卡片），
--                             ⛔ 不进入模型上下文（历史恢复 / 模型视图读侧过滤）。
--   held_state = 'delivered'  用户批准且已按既有唯一落库出口写入会话：按普通来信渲染 + 进上下文。
--                             ⚠️ 与 status='accepted' 不同：delivered 是「已投递进历史」，
--                             本列只描述消息行，不替代 peer_held_message 的审批状态机。
--   held_state = 'declined'   用户拒绝：按「已拒绝」卡片渲染，⛔ 不进入模型上下文。
--   held_state IS NULL        普通消息（绝大多数行）：行为与迁移前逐字一致。
--
-- 与 peer_held_message 的关系（单一真源划分）：
--   · messages 行 = 会话记录本体（内容 / 位置 seq / 渲染）；
--   · peer_held_message = 审批附属信息（hop 链 / 信封 / 已读 / 准入方式 / 审批时间）；
--   · 两者用同一主键 messageId 对齐（= PeerEnvelope.messageId）；
--   · 批准后只更新 messages 行的 held_state（同 id 原地更新，⛔ 不再插入第二行）。
--
-- 索引：读侧过滤用 (session_id, held_state)；既有查询都是 session 维度。
--
-- 版本号说明：当前最高 V324；V326 为下一个空号。⛔ 不修改历史迁移。
-- ===================================================================
ALTER TABLE messages ADD COLUMN held_state TEXT;

CREATE INDEX IF NOT EXISTS idx_messages_session_held_state
    ON messages(session_id, held_state);

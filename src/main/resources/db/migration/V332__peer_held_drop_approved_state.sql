-- ===================================================================
-- V332: peer_held_message 取消 approved 中间态（严格 CC 2.1.328 语义）
--
-- 背景（2026-11-21 用户裁定）：
--   CC 实测没有 approved 中间态 —— 批准 = 从 hold 缓冲一次性取出 + 同步投递；
--   成功回执 delivered，失败<b>立即结算</b>（策略变 refuse→refused；限速/队满→dropped；
--   目标会话关闭→receiver-closed），并回执发送方。不能撤销、不能二次批准。
--
--   本仓旧实现把「用户已批准但尚未写入会话历史」持久化为 status='approved'（V323），
--   投递失败时行留在 approved（列表可见、可再次批准）。这与 CC 语义冲突：
--   「已批准」的行会长期滞留，且允许二次批准。现收敛为：
--     pending --批准(take)--> [立即投递] --成功--> accepted
--                                      --失败--> expired（终态 + 发送方失败通知）
--
-- 本次迁移（一次性数据整理，不可逆但仍幂等）：
--   历史 approved 行 → pending（让用户重新处理），同时清掉批准/展示相关的中间态标记：
--     approved_at       清空（不再代表已批准）
--     admission/admission_reason  清空（pending 行从未准入；重新批准时会再写 approved/user-approved）
--     last_shown_at / pending_expires_at  清空（重新展示时按「首次展示」重新计时，
--                                          否则旧到期时刻可能落在过去 ⇒ 一进列表就过期）
--   seen_at 保留（用户当时确实看过，避免重复提醒）。
--
-- 版本号说明：迁移目录当前最高 V331，V332 为下一个空号。⛔ 不修改历史迁移。
-- ===================================================================
UPDATE peer_held_message
   SET status = 'pending',
       approved_at = NULL,
       admission = NULL,
       admission_reason = NULL,
       last_shown_at = NULL,
       pending_expires_at = NULL
 WHERE status = 'approved';

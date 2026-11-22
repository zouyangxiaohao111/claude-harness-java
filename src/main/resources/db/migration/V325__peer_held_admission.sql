-- ===================================================================
-- V325: peer_held_message 记录「入站准入方式」
--
-- 背景（前端卡片要显示「自动通过 / 手动批准」）：
--   原表只存 status（pending/approved/accepted/rejected），看不出这条来信到底
--   是服务端自动准入（模式类一致 / selfSent / 显式 accept）还是命中 hold 后由用户
--   手动批准。两者对用户的意义不同（前者无需用户处理，后者由用户显式接受）。
--
-- 语义：
--   admission        服务端生成值，闭集：'auto' | 'approved'。NULL = 未准入（pending 行）/旧行。
--                    ⛔ 不由模型正文 / 工具输入 / HTTP 请求体写入；只有路由层写入。
--   admission_reason 可选理由（小写字母/数字/连字符）：mode-match / self-sent /
--                    user-approved / explicit-accept。NULL = 未知。
--   写入点：
--     · 发送 ACCEPT 分支（PeerMessageRouter.send）⇒ 投递信封带 auto（本表无行）；
--     · 用户批准（PeerHeldMessageMapper.approveRow）⇒ 与 status/approved_at 同一条 UPDATE
--       写 'approved' / 'user-approved'（重启后从行重建信封时准入方式不丢，⛔ 不回落成 auto）。
--
-- 回填：本次迁移前已处于 approved / accepted 的行，其准入方式必然是「用户手动批准」
--   （V322/V323 期间只有审批路径能离开 pending）⇒ 补齐为 'approved' / 'user-approved'。
--   accepted 是 approved 的后继态，同样属于「曾经手动批准」，一并回填。
--   pending 行保持 NULL（尚未准入，前端显示「未准入」）。
--
-- 版本号说明：迁移目录当前最高 V323，但本机共享开发库 ~/.nexusai/nexusai.db 的
--   flyway_schema_history 里 V324 已被另一迁移（V324__nexus_i01_wire_run_commit.sql，
--   本树内无此文件）占用 ⇒ 按「不抢已被占用的号」取 V325（scripts/check-migrations.sh 复核通过）。
--   ⛔ 不修改历史迁移。
-- ===================================================================
ALTER TABLE peer_held_message ADD COLUMN admission TEXT;
ALTER TABLE peer_held_message ADD COLUMN admission_reason TEXT;

UPDATE peer_held_message
   SET admission = 'approved', admission_reason = 'user-approved'
 WHERE status IN ('approved', 'accepted');

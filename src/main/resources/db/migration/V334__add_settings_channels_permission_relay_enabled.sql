-- ===================================================================
-- V334: 权限中继的两道门落库（默认开）+ channels_enabled 默认改为开
--
-- 用户 2026-11-22 裁定：把 CC 的两道门做成 settings 里的两个开关，放「环境配置 → 权限与协作」，
-- **默认打开**；两个都开 ≠ 会推送 —— 推送还必须「本会话已接入渠道」。
--
-- 两道门（与 CC 的对应关系，见 CC exe 2.1.328）：
--   ① channels_enabled                      ← CC isChannelsEnabled()（tengu_harbor，channelAllowlist.ts:51-53）
--      能力开关：允许 channel 入站通知 handler 注册。V254 已有本列（旧默认 fail-closed），
--      本次把「未配置」的存量行回填成 1（默认开），并把读侧默认改为开。
--   ② channels_permission_relay_enabled      ← CC isChannelPermissionRelayEnabled()
--      （tengu_harbor_permissions，channelPermissions.ts:36-38）
--      权限中继开关：权限确认是否参与 channel 竞速。
--
-- ⚠️ 权威源：这两列是唯一权威（用户：「后期远程可以直接改数据库」）；
--   yml 的 nexusai.mcp.channels-enabled / channels-permission-relay-enabled 不再参与这两道门的判定。
--   旧的假门闸 nexusai.feature.channel-permission（ChannelPermissionFeature）随之删除。
--
-- ⚠️ 真正的「推送」条件（缺一不可）：
--   ① 本列 = 1；② relay 列 = 1；③ 该请求所属会话已绑定渠道（im_chat.bound_session_id 或
--   mcp_channel_binding）；④ 工具不要求用户交互（Ask 不走中继）。与「无人值守」无关。
--
-- ⚠️ 文件落盘即冻结（用户随时可能重启让 Flyway 应用）；内容不得再改。
-- ===================================================================
ALTER TABLE settings ADD COLUMN channels_permission_relay_enabled INTEGER;

-- 默认开：存量行显式写 1（与「未配置 → 开」同义，UI 能直接显示当前值）
UPDATE settings SET channels_permission_relay_enabled = 1
 WHERE channels_permission_relay_enabled IS NULL;

-- ① 能力门默认由关改为开（V254 遗留行的 NULL 回填；已有显式 0 的用户选择保持不动）
UPDATE settings SET channels_enabled = 1
 WHERE channels_enabled IS NULL;

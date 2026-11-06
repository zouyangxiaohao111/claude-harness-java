-- ===================================================================
-- V255: MCP channel 绑定表（外部渠道 ↔ 会话）
--
-- 背景：CC 单进程单会话 ⇒ channel 白名单是**进程级全局**（claude.exe v2.1.212 `Rt.allowedChannels`，
--   setAllowedChannels = `function m4e(e){Rt.allowedChannels=e}`），消息靠 ambient 的
--   `agentId: ci()` 进「当前正在跑的 agent」队列 ⇒ **CC 压根没有「消息该给哪个会话」这个问题**。
--
--   本仓是 Web 多会话（一个后端进程，多个前端会话并发）：
--     · MCP 连接是 `Map<String, McpTransport>` 按 serverName **全局单例**（与 CC 同构）
--     · 但消息必须**显式**指定 `QueueItem.sessionId`（`NotificationQueue.drainForQuery` 的
--       [3a] 规则：具体会话的 turn 只捞 `sessionId` 匹配的命令）
--   ⇒ 连接是全局的、消费是分会话的，中间缺一张「渠道 ↔ 会话」的绑定关系表。
--
-- 互斥语义（用户裁定 2026-11-03）：
--   · **互斥键 = 渠道 id（channel_id），不是 server_name**
--     （两个 server 都声明 id=wechat 时，仍互斥 —— 这正是不做 serverName 兜底的意义）
--   · 同一渠道 id 全局只能绑定**一个**会话 ⇒ UNIQUE(channel_id)
--   · 同一会话可同时绑定**多个不同**渠道（微信 + 飞书 + 企微并存）
--
-- ⛔ 不做 provider 门序（用户裁定 2026-11-03「第三方模型肯定放行不能拦截」）：
--   与 CC 的 `channels are not available on third-party providers` **有意分歧**，非漏做。
--
-- ⚠️ 本文件注释**不得**出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （application.yml 未关 flyway.placeholder-replacement，SQL 注释也在替换范围内）。
-- ===================================================================
CREATE TABLE mcp_channel_binding (
  id          TEXT PRIMARY KEY,
  channel_id  TEXT NOT NULL,           -- 渠道 id（wechat / feishu / wecom / ... / custom:<name>）
  server_name TEXT NOT NULL,           -- 承载该渠道的 MCP server 名
  session_id  TEXT NOT NULL,           -- 归属会话（消息投给谁）
  bound_at    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(channel_id)                   -- ← 互斥铁律：同渠道全局唯一
);
CREATE INDEX idx_mcp_channel_binding_session ON mcp_channel_binding(session_id);
CREATE INDEX idx_mcp_channel_binding_server  ON mcp_channel_binding(server_name);

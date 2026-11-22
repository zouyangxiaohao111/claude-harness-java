-- ===================================================================
-- V304: IM 审批表（审批单 + 审批项）
--
-- 背景：扫描命中的消息不会直接执行，而是生成一张**审批单**推给用户；
--   用户逐项勾选/忽略后才唤醒 agent。所以审批与消息是 1:N，
--   决策必须存到 **item 粒度**（用户裁定：可以「确认 2 项、忽略 1 项」）。
--
-- 主表状态机（设计稿定义）：
--   pending            等待确认
--   partially_approved 已确认一部分，剩余项仍待办
--   approved           全部处理完（确认的已执行）
--   ignored            用户主动全部忽略
--   expired            超时未确认（不自动执行）
--
-- ⚠️ code（短码）是微信/企微的兜底路径：
--   企微模板卡片链接分钟级过期、微信用户可能没开卡片 —— 两边都靠短码匹配
--   「审批码 A7K3」，所以在库内**全局唯一**。
--
-- ⚠️ created_session_id：确认后按 exec_mode=new 新建的任务 id（Q2=A）。
--   存下来是为了「审批单 → 它变成了哪个任务」可回溯；exec_mode=existing 时为空。
--
-- ⚠️ pushed_json 记录每个渠道的推送结果：
--   [{"platform":"wechat","ok":false,"error":"无会话上下文"}]
--   微信必须先有 context_token 才能主动发，推送失败是常态，必须能显式展示。
-- ===================================================================
CREATE TABLE IF NOT EXISTS im_approval (
  id                 TEXT PRIMARY KEY,
  rule_id            TEXT,                                  -- 规则删除后置空，保留历史
  rule_name          TEXT NOT NULL,                         -- 冗余存名字（规则删了也能看懂）
  status             TEXT NOT NULL DEFAULT 'pending',
  code               TEXT NOT NULL,                          -- 审批短码（微信/企微兜底）
  exec_mode          TEXT NOT NULL DEFAULT 'new',
  exec_session_id    TEXT,
  created_session_id TEXT,                                  -- 确认后新建的任务 id
  due_at             TEXT,
  pushed_json        TEXT,                                  -- 各渠道推送结果
  created_at         TEXT NOT NULL DEFAULT (datetime('now')),
  resolved_at        TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_im_approval_code ON im_approval(code);
CREATE INDEX IF NOT EXISTS idx_im_approval_status ON im_approval(status);
CREATE INDEX IF NOT EXISTS idx_im_approval_created ON im_approval(created_at);

CREATE TABLE IF NOT EXISTS im_approval_item (
  id           TEXT PRIMARY KEY,
  approval_id  TEXT NOT NULL,
  seq          INTEGER NOT NULL,                -- 展示顺序（1-based，与短码「确认 1」对应）
  text         TEXT NOT NULL,                   -- 命中的消息原文（截断后）
  reason       TEXT,                            -- 为什么命中（给用户判断用）
  group_name   TEXT,                            -- 来源群/会话展示名
  sender       TEXT,
  msg_time     TEXT,
  msg_id       TEXT,                            -- 本机库里的消息 id（可回溯原文）
  decision     TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | ignored
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_im_approval_item_approval ON im_approval_item(approval_id);
CREATE INDEX IF NOT EXISTS idx_im_approval_item_decision ON im_approval_item(decision);

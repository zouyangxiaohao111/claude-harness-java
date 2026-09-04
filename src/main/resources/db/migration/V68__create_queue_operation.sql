-- ===================================================================
-- V68: queue_operation 队列审计表（对齐 CC queue-operation，OD-D11）
--
-- 背景（messageQueueManager.ts logOperation :28-38 + sessionStorage.ts:1464
-- recordQueueOperation → appendEntry）：CC 每次入队/出队/移除/拉回落编辑写一条
-- queue-operation（仅诊断，排查"命令怎么丢"）。Java 端以本表承载，NotificationQueue
-- mutator 触发 → QueueAuditService 落库。
--
-- 列语义：
--   operation  = 'enqueue' | 'dequeue' | 'remove' | 'popAll'（CC 语义；
--                popAll = Esc/↑ 拉回编辑，带 content；dequeue/remove 不带）。
--   session_id / uuid / mode / priority / workload = Java 增强身份字段（入出队配对诊断）。
--   priority   = 小写 'now' | 'next' | 'later'（QueueItem.Priority name() 转小写）。
--   content    = 仅 enqueue / popAll 携带原文（CC logOperation content 语义）。
--   created_at = DB 默认 datetime('now')，Java 端不设值（统一 DB 文本格式保排序）。
--
-- 清理策略登记（不阻塞）：全局单表无限增长，后续按天归档/删除（与 CC 随会话
-- JSONL 轮转不同，登记差异）。索引 idx_queue_operation_session 供按 session 排查。
-- ===================================================================
CREATE TABLE IF NOT EXISTS queue_operation (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    operation  TEXT NOT NULL,
    session_id TEXT,
    uuid       TEXT,
    mode       TEXT,
    priority   TEXT,
    workload   TEXT,
    content    TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_queue_operation_session ON queue_operation(session_id, created_at);

-- [RAG 时间线落库] 快速问答的工具阶段（query_understand / knowledge_search …）随 assistant 消息存一份，
-- 刷新 / 重启后还能重画当时的时间线（对齐源站 WeKnora types.Message.agent_steps）。
-- ⚠️ ReAct 不走这里：它的工具调用是独立的 role='tool' 消息行。
ALTER TABLE messages ADD COLUMN agent_steps TEXT;

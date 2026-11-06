-- 主对话「引入知识库」开关（Q1 已拍板，2026-11-06）
--
-- 语义：打开后，4 个知识库专属工具（search_knowledge / list_documents /
-- read_document / query_knowledge_graph）进入主线程工具池。
-- 它们平时被 @ExclusiveTool 挡在默认池之外（缺陷：用户不知道有这个能力）。
--
-- ⚠️ 默认 0（关闭）= 与改造前行为完全一致，存量会话零影响。
-- ⚠️ 只控制「工具可见性」，不改变 loop() 的任何执行逻辑。
ALTER TABLE sessions ADD COLUMN kb_tools_enabled INTEGER NOT NULL DEFAULT 0;

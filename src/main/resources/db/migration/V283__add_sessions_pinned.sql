-- 会话置顶标记（左栏「任务」列表）
-- 三态：NULL = 未设置（读侧按未置顶处理）；0 = 显式未置顶；1 = 置顶。
ALTER TABLE sessions ADD COLUMN pinned INTEGER;

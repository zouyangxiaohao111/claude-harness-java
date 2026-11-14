-- V291: 模型级「后台并发上限」（对齐源站 model.max_concurrency）
--
-- <p>语义与源站一致：只限制**后台任务**（文档入库向量化 / 图片向量化）对单个模型的
-- 并发调用数，按模型 ID 共享；交互式问答的查询编码不受影响。
-- NULL / 0 = 沿用全局默认（后端常量 32）；负数 = 关闭限制。
ALTER TABLE models ADD COLUMN max_concurrency INTEGER;

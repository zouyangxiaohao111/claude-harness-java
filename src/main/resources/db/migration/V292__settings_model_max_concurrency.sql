-- V292: 后台向量化并发上限的**全局默认**（对齐源站系统设置 model.max_concurrency）
--
-- <p>模型级上限在 models.max_concurrency（V291）；本列是它的缺省值：
-- 模型未配置（null/0）时用这里的值，仍未配置再回落后端常量 32。
-- 只影响后台任务（文档入库 / 图片向量化），交互式问答的查询编码不受影响。
ALTER TABLE settings ADD COLUMN model_max_concurrency INTEGER;

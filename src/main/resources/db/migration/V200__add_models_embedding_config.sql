-- 模型级向量嵌入配置（embedding）· 2026-10-26
--
-- 背景：models.type 早就有 embedding/rerank 可选，但后端没有任何向量/重排调用路径。
--       本次只补「向量嵌入」：前端可配置维度与批大小，后端按 OpenAI 兼容协议调用
--       POST {baseUrl}/embeddings（百炼 compatible-mode 同样适用）。
--
-- 语义：
--   embedding_dimensions  向量维度（仅 type=embedding 生效）。NULL = 请求不携带 dimensions，
--                         由服务端使用模型默认维度（百炼 text-embedding-v4 默认 1024）。
--   embedding_batch_size  单次请求最多几条文本（仅 type=embedding 生效）。NULL/<=0 = 10。
--                         百炼上限为 10；其它 OpenAI 兼容端点可调大。
--
-- ⚠️ 编号说明（为什么是 V200 而不是 V105）：
--   master 当前最大迁移号是 V104；场景分支（feat/scene-fusion）的 agri 批次占用了
--   V105..V125（尚未提交）。若此处用 V105 会与场景分支合并时的迁移号正面冲突
--   （历史 V78/V100 撞号教训：两个同名版本会让 Flyway 直接拒启）。
--   ⇒ 主线的通用迁移统一跳到 V200 段，给场景分支的 V1xx 留出空间。
ALTER TABLE models ADD COLUMN embedding_dimensions INTEGER;
ALTER TABLE models ADD COLUMN embedding_batch_size INTEGER;

-- 重排默认配置（仅 type=rerank 生效）
--   rerank_top_n   请求未传 topN 时的默认值；NULL = 不发送（服务端默认）
--   rerank_instruct 请求未传 instruct 时的默认指令；NULL/空 = 不发送
ALTER TABLE models ADD COLUMN rerank_top_n INTEGER;
ALTER TABLE models ADD COLUMN rerank_instruct TEXT;

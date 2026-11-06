-- ===================================================================
-- V277: kb_config 扩「问答链参数」（批次 QA-1 · 问答链完整对齐）
--
-- 依据：WeKnora `config/config.yaml:9-22` 的 conversation 段
--   max_rounds / keyword_threshold / embedding_top_k / vector_threshold
--   rerank_threshold / rerank_top_k / enable_rewrite / enable_query_expansion
--   + `types/chat_manage.go` 的 FAQ / WebFetch / DataAnalysis 开关
--   + `session_knowledge_qa.go:221-233` 的动态装配条件
--
-- ⭐ 落点选择：全部进 `kb_config`（单行，id='kb'）
--   WHY 不进 settings：settings 是跨功能共用单行，多会话并发 PUT 会整体覆盖
--   （W8 已实际丢过字段，见 V273 头注释）。知识库域参数必须跟知识库域一起走。
--
-- ⭐ 全部列可空，NULL = 「用代码默认值」
--   WHY：存量库升级后行为必须与升级前完全一致 —— 不加任何隐含的默认值变更。
--   默认值只在 `KbConfigService` 的读取方法里单点给出（读侧兜底），
--   ⛔ 不在 DDL 里写 DEFAULT（那样「未配置」和「配成默认值」就分不开了）。
-- ===================================================================

-- ── 检索 ──────────────────────────────────────────────────────────
-- 每路检索返回条数（源侧 embedding_top_k=30）
ALTER TABLE kb_config ADD COLUMN embedding_top_k INTEGER;
-- 向量相似度下限，0 = 不过滤（源侧 vector_threshold=0.2）
ALTER TABLE kb_config ADD COLUMN vector_threshold REAL;
-- 关键词分数下限，0 = 不过滤（源侧 keyword_threshold=0.3）
ALTER TABLE kb_config ADD COLUMN keyword_threshold REAL;
-- 重排分数下限，0 = 不过滤（源侧 rerank_threshold=0.3）
ALTER TABLE kb_config ADD COLUMN rerank_threshold REAL;
-- 重排后保留条数（源侧 rerank_top_k=30）
ALTER TABLE kb_config ADD COLUMN rerank_top_k INTEGER;
-- 最终进上下文的段数上限（NULL ⇒ 12）
-- ⚠️ 本仓新增，源侧没有这一项（源侧 rerank_top_k=30 直接全进上下文）。
--    理由：单段上限 2000 码点 × 30 段 = 6 万字符，会把上下文预算打满并挤掉对话历史。
--    要与源侧完全一致 ⇒ 把它配成与 rerank_top_k 相同的值。
ALTER TABLE kb_config ADD COLUMN context_max_chunks INTEGER;

-- ── 对话 / 改写 ────────────────────────────────────────────────────
-- 多轮窗口（源侧 max_rounds=5；0 = 显式关闭多轮，⛔ 有意义，不可当「未配置」）
ALTER TABLE kb_config ADD COLUMN max_rounds INTEGER;
-- 查询改写开关（源侧 enable_rewrite=1）
ALTER TABLE kb_config ADD COLUMN enable_rewrite INTEGER;
-- 低召回时的查询扩展开关（源侧 enable_query_expansion=1）
ALTER TABLE kb_config ADD COLUMN enable_query_expansion INTEGER;

-- ── FAQ 优先策略（源侧 chatManage.FAQPriorityEnabled / FAQScoreBoost）────
ALTER TABLE kb_config ADD COLUMN faq_priority_enabled INTEGER;
ALTER TABLE kb_config ADD COLUMN faq_score_boost REAL;
-- 高于此值（用 **boost 前** 分数比较）的 FAQ 判为「精确命中」
ALTER TABLE kb_config ADD COLUMN faq_direct_answer_threshold REAL;

-- ── 联网检索 ───────────────────────────────────────────────────────
-- 单次联网检索结果条数上限（源侧 EffectiveWebSearchConfig.MaxResults）
ALTER TABLE kb_config ADD COLUMN web_search_max_results INTEGER;
-- 是否抓取网页正文替换 snippet（源侧 WebFetchEnabled）
ALTER TABLE kb_config ADD COLUMN web_fetch_enabled INTEGER;
-- 抓取正文的前 N 条（源侧 WebFetchTopN，默认 3）
ALTER TABLE kb_config ADD COLUMN web_fetch_top_n INTEGER;
-- 是否启用 DuckDB 数据分析阶段（源侧 DataAnalysisEnabled，默认关）
ALTER TABLE kb_config ADD COLUMN data_analysis_enabled INTEGER;

-- ── 提示词模板 ─────────────────────────────────────────────────────
-- 上下文模板（占位符 {query} / {contexts} / {language}）；NULL = 走本仓内置格式
ALTER TABLE kb_config ADD COLUMN context_template TEXT;
-- 非检索意图的系统提示词覆盖（JSON：{"greeting": "...", "chitchat": "..."}）
ALTER TABLE kb_config ADD COLUMN intent_prompts TEXT;

-- ── 主对话「引入知识库」开关（批次 QA-12）────────────────────────────
-- 1 = 会话主线程允许使用 KB 专属工具（search_knowledge / list_documents /
--     read_document / query_knowledge_graph）；NULL/0 = 不引入（默认）。
-- ⚠️ 这是**会话级**默认，实际生效还看会话绑定 agent 的 tools 名单（两者取交）。
ALTER TABLE kb_config ADD COLUMN kb_tools_enabled INTEGER;

-- ===================================================================
-- V268: 检索驱动可插拔（WeKnora 复刻 · 批次 W1）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   internal/types/retriever.go:8-26   → 9 个驱动常量（另有 2 个空壳常量）
--   internal/types/tenant.go:17       → retrieverEngineMapping（RETRIEVE_DRIVER → 能力）
--   internal/types/interfaces/retriever.go:96-118 → RetrieveEngineService 13 个方法
--
-- 目标侧做法（用户 2026-11-04 裁定）：
--   「向量库可插拔对我们来说不就是引入 start 吗？Java 本身支持的吧 定义好对应的接口就行吧」
--   ⇒ 用 Java interface + Spring 组件集合注入做注册表，⛔ 不照抄 Go 的 map 常量表。
--   「我们默认用 sqlite」
--
-- 本迁移只加配置列，不动数据。
-- ===================================================================

-- ───────────────────────────────────────────────────────────────
-- kb_knowledge_base：每库可覆盖检索驱动
--
-- 空/NULL = 用全局默认（settings.kb_retriever_engine），全局默认空 = sqlite。
--
-- ⚠️ 为什么允许每库覆盖而不是只有一个全局值：
--   一个部署里可能既有「必须走 pgvector 的大库」也有「只在小库用 sqlite」的场景。
--   但**跨驱动检索不融合** —— 一次查询只打一个驱动（源侧同样按库解析 store 分组）。
-- ───────────────────────────────────────────────────────────────
ALTER TABLE kb_knowledge_base ADD COLUMN retriever_engine TEXT;

-- ───────────────────────────────────────────────────────────────
-- settings（单例行 id=1）：全局默认驱动
--
-- ⚠️ 留 NULL 而不是 'sqlite'：
--   两种写法行为一样（解析时都会回落 sqlite），但 NULL 能区分
--   「用户显式选了 sqlite」与「用户没配」——
--   将来若要提示「你还没配驱动」，只有 NULL 能表达这件事。
-- ───────────────────────────────────────────────────────────────
ALTER TABLE settings ADD COLUMN kb_retriever_engine TEXT;

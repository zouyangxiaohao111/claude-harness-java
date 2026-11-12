-- ===================================================================
-- V289: 图谱边唯一键对齐 Neo4j「含关系类型」+ 清理逻辑删除残留
--
-- 背景（代码为准）：
--   1) 源侧 WeKnora 的边唯一语义 = Neo4j
--      {@code apoc.merge.relationship(source, row.type, {}, props, target)}
--      —— MERGE 键是 (source, **type**, target)，同一对实体允许挂多条不同类型的边。
--      本仓本地表原唯一索引只有 (knowledge_id, source_name, target_name)（不含 type），
--      ⇒ 与 Neo4j 侧语义不一致：将来放开「同端点多类型边」时本地会抛
--      SQLITE_CONSTRAINT_UNIQUE（11-12 千岛湖已实际撞过一次，见 V 之前的关系去重修复）。
--      本迁移把 type 并入唯一键，两侧语义统一。
--
--   2) 实体/关系表的重建清理原走 MyBatis-Flex 逻辑删（deleted=1），而源侧
--      {@code neo4j/repository.go:140-162 DelGraph} 是**物理删**（DELETE r / DELETE n）。
--      ⇒ 本仓每次重建都在表里累积不可见行（千岛湖 1679 行里只有 673 行有效）。
--      代码侧已改为物理删除（LocalSqliteGraphStore#deleteByKnowledge，JdbcTemplate
--      DELETE FROM），本迁移顺带把历史残留一次性清掉。
--
-- 依赖检查（本机 nexusai.db 实测）：kb_graph_relation.relation_type 为
--   TEXT NOT NULL DEFAULT ''，无 null ⇒ 可安全进唯一键；两表均无外键引用。
-- ===================================================================

DROP INDEX IF EXISTS uq_kb_graph_relation_doc_edge;

CREATE UNIQUE INDEX uq_kb_graph_relation_doc_edge
    ON kb_graph_relation (knowledge_id, source_name, target_name, relation_type)
    WHERE deleted = 0;

DELETE FROM kb_graph_relation WHERE deleted = 1;
DELETE FROM kb_graph_entity WHERE deleted = 1;

package com.nexusai.kb.app.graph.store;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.repo.mapper.KbGraphEntityMapper;
import com.nexusai.kb.repo.mapper.KbGraphRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地 SQLite 图谱存储（默认实现）。
 *
 * <p>⚠️ 本类是<b>原样搬运</b>：逻辑与抽取/检索服务里原来的 mapper 调用逐字一致，
 * ⛔ 行为零变化。抽接口的目的只是让远程 Neo4j 能插进来（见 {@link KbGraphStore}）。
 *
 * <p>启用条件：{@code nexusai.kb.graph.store} 未设置或等于 {@code local}。
 * 配置成 {@code neo4j} 时由 {@link Neo4jGraphStore} 接管。
 */
@Component
@ConditionalOnProperty(name = "nexusai.kb.graph.store", havingValue = "local", matchIfMissing = true)
public class LocalSqliteGraphStore implements KbGraphStore {

    private static final Logger log = LoggerFactory.getLogger(LocalSqliteGraphStore.class);

    private final KbGraphEntityMapper entityMapper;
    private final KbGraphRelationMapper relationMapper;
    /**
     * 重建清理走物理删 · 对齐源侧 {@code neo4j/repository.go:140-162 DelGraph}
     * （{@code DELETE r} / {@code DELETE n}）。
     *
     * <p>⚠️ 不能用 mapper 的 {@code deleteByQuery}：实体带逻辑删注解，那是
     * {@code UPDATE deleted=1}，会让表随每次重建无限累积（千岛湖实测 1679 行里
     * 只有 673 行有效）。图谱是派生数据，无需保留历史。
     */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    public LocalSqliteGraphStore(KbGraphEntityMapper entityMapper,
                                 KbGraphRelationMapper relationMapper,
                                 org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.jdbc = jdbc;
    }

    @Override
    public void replaceGraph(String knowledgeBaseId, String knowledgeId,
                             List<KbGraphEntityRecord> entities,
                             List<KbGraphRelationRecord> relations) {
        // 先清后写（源侧 DelGraph + AddGraph 的同构做法）。
        // ⚠️ 不清会留下上一次解析的过期实体 —— 重解析后图谱只会变大、不会变准。
        deleteByKnowledge(knowledgeId);
        int e = 0;
        if (entities != null) {
            for (KbGraphEntityRecord r : entities) {
                if (r == null) {
                    continue;
                }
                entityMapper.insert(r);
                e++;
            }
        }
        int rel = 0;
        if (relations != null) {
            // ⭐⭐ 双保险：按 (source, target) 再去重一次 —— 对齐 Neo4j 侧的
            //   {@code apoc.merge.relationship} 幂等语义（源站 repository.go:101）。
            //   DB 上有部分唯一索引 uq_kb_graph_relation_doc_edge，重复即抛
            //   SQLITE_CONSTRAINT_UNIQUE 并中断整批（11-12 千岛湖实测 500）。
            //   上游 parseRelations 已合并同端点关系，这里防「绕过解析层直接构造记录」
            //   的调用方把批次写挂。
            java.util.Map<String, KbGraphRelationRecord> unique = new java.util.LinkedHashMap<>();
            for (KbGraphRelationRecord r : relations) {
                if (r == null) {
                    continue;
                }
                // 键含 relation_type —— 与 V289 唯一索引 (knowledge_id, source, target, type)
                // 及 Neo4j apoc.merge.relationship 的 MERGE 键保持一致
                String key = r.getSourceName() + "\u0000" + r.getTargetName()
                    + "\u0000" + (r.getRelationType() == null ? "" : r.getRelationType());
                unique.putIfAbsent(key, r);
            }
            for (KbGraphRelationRecord r : unique.values()) {
                relationMapper.insert(r);
                rel++;
            }
        }
        log.info("[图谱存储·本地] 写入完成 kb={} knowledge={} 实体={} 关系={}",
            knowledgeBaseId, knowledgeId, e, rel);
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return;
        }
        // 物理删（对齐源侧 DelGraph：先关系后节点，DELETE 而非 UPDATE deleted=1）
        int rels = jdbc.update("DELETE FROM kb_graph_relation WHERE knowledge_id = ?", knowledgeId);
        int ents = jdbc.update("DELETE FROM kb_graph_entity WHERE knowledge_id = ?", knowledgeId);
        if (log.isDebugEnabled()) {
            log.debug("[图谱存储·本地] 已清除旧图 knowledge={} 关系={} 实体={}", knowledgeId, rels, ents);
        }
    }

    @Override
    public List<KbGraphEntityRecord> listEntities(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }
        List<KbGraphEntityRecord> got = entityMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", knowledgeBaseId));
        return got == null ? List.of() : got;
    }

    @Override
    public List<KbGraphRelationRecord> listRelations(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }
        List<KbGraphRelationRecord> got = relationMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", knowledgeBaseId));
        return got == null ? List.of() : got;
    }
}

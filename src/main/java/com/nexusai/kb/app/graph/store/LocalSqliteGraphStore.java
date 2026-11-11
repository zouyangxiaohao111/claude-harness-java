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

    @Autowired
    public LocalSqliteGraphStore(KbGraphEntityMapper entityMapper,
                                 KbGraphRelationMapper relationMapper) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
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
            for (KbGraphRelationRecord r : relations) {
                if (r == null) {
                    continue;
                }
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
        entityMapper.deleteByQuery(QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
        relationMapper.deleteByQuery(QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
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

package com.nexusai.kb.app.retrieval.engine;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.repo.entity.KbEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 检索引擎 · 本仓默认驱动（用户 2026-11-04 裁定「我们默认用 sqlite」）。
 *
 * <h2>它是「适配器」，不是新实现</h2>
 * 具体的双路召回 + RRF 融合逻辑已在 {@link KbHybridSearcher} 里（B3b/B5 已测过 118 例）。
 * 本类只做两件事：
 * <ol>
 *   <li>把引擎接口的入参翻译成 {@code KbHybridSearcher.search(...)} 的调用；</li>
 *   <li>索引写入委托给 {@link KbIndexWriter}（它是「整文档重建」，不是单条 upsert）。</li>
 * </ol>
 * ⛔ 不在这里复制粘贴检索算法 —— 两份实现一定会分叉。
 *
 * <h2>⚠️ 与源侧 sqlite 驱动的差异（已在 SCHEDULE R1 记录）</h2>
 * 源侧用 {@code sqlite-vec} C 扩展（{@code vec0} 虚拟表 + 按维度分表 + MATCH/k 查询）。
 * 本仓用 BLOB + 应用层余弦。语义等价（都是余弦相似度），差别在：
 * <ul>
 *   <li>性能：数据量大时应用层算余弦是 O(N·d)，没有 ANN 索引；</li>
 *   <li>精度：精确算全量，没有近似召回损失。</li>
 * </ul>
 * ⇒ 这是<b>有意的取舍</b>，不是遗漏。要换成 sqlite-vec 只需改本类的 {@code retrieve}
 * 实现，接口与调用方零改动。
 */
@Component
public class KbSqliteRetrievalEngine implements KbRetrievalEngine {

    private static final Logger log = LoggerFactory.getLogger(KbSqliteRetrievalEngine.class);

    public static final String TYPE = "sqlite";

    private final KbHybridSearcher searcher;
    private final KbIndexWriter indexWriter;
    private final KbEmbeddingMapper embeddingMapper;
    private final DataSource dataSource;

    /**
     * 知识库域配置（{@code kb_config}）。
     *
     * <p><b>⭐ 为什么用字段注入而不是构造注入</b>：本类构造器被两个直构测试逐位使用
     * （{@code KnowledgeIngestTest} / {@code KnowledgeServiceTest}），加参数会破坏它们；
     * 字段注入 + {@code required=false} 让「不走 Spring 的直构」仍可工作（视为未配置 ⇒ 阈值 0 = 不过滤）。
     * 与 {@code KnowledgeService#indexWriter} 的既有模式一致。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.kb.config.KbConfigService configService;

    public KbSqliteRetrievalEngine(
        KbHybridSearcher searcher,
        KbIndexWriter indexWriter,
        KbEmbeddingMapper embeddingMapper,
        DataSource dataSource
    ) {
        this.searcher = searcher;
        this.indexWriter = indexWriter;
        this.embeddingMapper = embeddingMapper;
        this.dataSource = dataSource;
    }

    @Override
    public String engineType() {
        return TYPE;
    }

    @Override
    public boolean supports(Feature feature) {
        return switch (feature) {
            case KEYWORD, VECTOR, BATCH_ENABLED_STATUS, BATCH_TAG -> true;
            // 跨库复制索引：本仓没有「免重算向量复制」的需求（复制库走重新入库）
            case COPY_INDICES -> false;
            // 存储估算：本仓用分块内容字节数近似（O-16），不做向量存储估算
            case ESTIMATE_STORAGE -> false;
        };
    }

    /**
     * 写入索引。
     *
     * <p>⚠️ {@link KbIndexWriter#reindexKnowledge} 是<b>整文档重建</b>
     * （先 clearIndex 再全量写）。逐条 {@code index()} 语义上不成立：
     * 同一个 knowledgeId 写两次会互相清掉。
     * <p>⇒ 这里按 {@code knowledgeId} 分组后，每个分组各调一次重建。
     * ⛔ 不使用传入的 {@code content} —— 重建会从 {@code kb_chunk} 表读原文，
     * 保证索引与库内数据一致（传进来的可能是调用方拼的，可能与库不同步）。
     *
     * @param kbId      知识库 ID
     * @param documents 待索引文档（按其 knowledgeId 分组）
     * @param modelId   向量模型 ID（null = 只建 FTS）
     */
    @Override
    public void index(String kbId, List<IndexDocument> documents, String modelId) {
        if (documents == null || documents.isEmpty() || kbId == null || kbId.isBlank()) {
            return;
        }
        java.util.Set<String> knowledgeIds = new java.util.LinkedHashSet<>();
        for (IndexDocument d : documents) {
            if (d != null && d.knowledgeId() != null && !d.knowledgeId().isBlank()) {
                knowledgeIds.add(d.knowledgeId());
            }
        }
        for (String knowledgeId : knowledgeIds) {
            indexWriter.reindexKnowledge(knowledgeId, kbId, modelId);
        }
        if (log.isInfoEnabled()) {
            log.info("[sqlite 引擎] 索引写入完成 kb={} 文档数={} 模型={}",
                kbId, knowledgeIds.size(), modelId == null ? "仅关键词" : modelId);
        }
    }

    @Override
    public void deleteByKnowledgeIds(String kbId, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        // ⚠️ 逻辑删除而不是物理删：kb_embedding / kb_chunk_fts 都要清。
        // FTS 是虚拟表、没有 deleted 列 ⇒ 物理删；向量表走逻辑删（与其它表一致）。
        for (String knowledgeId : knowledgeIds) {
            if (knowledgeId == null || knowledgeId.isBlank()) {
                continue;
            }
            try (var conn = dataSource.getConnection()) {
                try (var ps = conn.prepareStatement(
                    "DELETE FROM kb_chunk_fts WHERE knowledge_id = ?")) {
                    ps.setString(1, knowledgeId);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                log.warn("[sqlite 引擎] 清 FTS 失败 knowledge={} 原因={}", knowledgeId, e.getMessage());
            }
            try {
                embeddingMapper.deleteByQuery(QueryWrapper.create()
                    .where("knowledge_id = ?", knowledgeId));
            } catch (RuntimeException e) {
                log.warn("[sqlite 引擎] 清向量失败 knowledge={} 原因={}", knowledgeId, e.getMessage());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[sqlite 引擎] 索引已删除 kb={} 文档数={}", kbId, knowledgeIds.size());
        }
    }

    @Override
    public List<Hit> retrieve(RetrieveParams params) {
        if (params == null || params.kbId() == null || params.bothDisabled()) {
            return List.of();
        }
        String modelId = params.disableVector() ? null : params.modelId();
        // ⭐ 阈值来自 kb_config（vector_threshold / keyword_threshold）。
        //   历史缺陷：这里调 4 参重载 ⇒ 阈值恒为 0.0（不过滤）⇒ API 路径召回噪声大，
        //   而问答链（KbQaPipeline）显式传了阈值 ⇒ 两条路径口径不一致。
        double vectorThreshold = 0.0;
        double keywordThreshold = 0.0;
        if (configService != null) {
            try {
                com.nexusai.kb.config.KbConfigService.QaParams qa = configService.readQaParams();
                if (qa != null) {
                    vectorThreshold = qa.vectorThreshold();
                    keywordThreshold = qa.keywordThreshold();
                }
            } catch (RuntimeException e) {
                // ⭐ 降级而不是失败：配置读不出来不该让检索整体 500（readQaParams 内部已全默认）
                log.warn("[sqlite 引擎] 读检索阈值失败，本次不过滤 kb={} 原因={}",
                    params.kbId(), e.getMessage());
            }
        }
        List<KbHybridSearcher.Hit> hits = searcher.search(
            params.kbId(), params.query(), params.limit(), modelId,
            vectorThreshold, keywordThreshold);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>(hits.size());
        for (KbHybridSearcher.Hit h : hits) {
            // 单路禁用时按 rank 过滤（与 KnowledgeService.hybridSearch 的既有语义一致）
            if (params.disableVector() && h.keywordRank() <= 0) {
                continue;
            }
            if (params.disableKeyword() && h.vectorRank() <= 0) {
                continue;
            }
            out.add(new Hit(h.chunkId(), h.knowledgeId(), h.content(),
                h.contextHeader(), h.score(), h.vectorRank(), h.keywordRank()));
        }
        return out;
    }
}

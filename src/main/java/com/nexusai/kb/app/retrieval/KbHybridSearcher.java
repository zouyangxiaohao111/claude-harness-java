package com.nexusai.kb.app.retrieval;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 混合检索 · 复刻 WeKnora {@code knowledgebase_search.go} 的
 * 「向量召回 ∪ 关键词召回 → RRF 融合」主路径。
 *
 * <h2>两路召回为什么都要</h2>
 * <ul>
 *   <li><b>向量</b>：语义近似（「怎么处理污水」命中「农村生活污水处理技术」）</li>
 *   <li><b>关键词</b>：精确词（人名、型号、编号、代码）—— 向量对低频精确词天然弱</li>
 * </ul>
 * 只留一路会明显掉召回。源侧也是两路，这是设计而非优化。
 *
 * <h2>⭐ 中文的关键词检索必须用 trigram</h2>
 * 实测（sqlite-jdbc 3.46.0.0）：FTS5 默认 {@code unicode61} 分词器把
 * 「农村污水处理设施」整段当一个 token ⇒ {@code MATCH '污水'} **零命中**。
 * 建表已指定 {@code tokenize='trigram'}。
 * <p>代价：trigram 固定 3-gram ⇒ <b>查询串少于 3 个字符时无法匹配</b>，
 * 此时回落到 {@code LIKE '%q%'}（精度略降但可用，比直接返回空好）。
 *
 * <h2>失败降级策略</h2>
 * <ul>
 *   <li>没有配置嵌入模型 ⇒ 只走关键词（不报错：用户可能只想用关键词检索）</li>
 *   <li>嵌入调用失败 ⇒ 记 WARN，退回关键词路（⛔ 不让一次模型抖动把整个检索打死）</li>
 * </ul>
 */
@Component
public class KbHybridSearcher {

    private static final Logger log = LoggerFactory.getLogger(KbHybridSearcher.class);

    /** 每路召回的候选数（融合前）。源侧是 MatchCount*5，此处取一个固定上限更可预测。 */
    private static final int CANDIDATES_PER_ROUTE = 50;

    /** trigram 分词器的最小可匹配长度。 */
    private static final int TRIGRAM_MIN_CHARS = 3;

    /**
     * 查询最多拆成多少个 3-gram（OR 项上限）。
     *
     * <p>长问句（如一整段粘贴进来的文字）不加限会生成几百个 OR，
     * SQL 解析和 bm25 排序开销都不可控。60 个 gram ≈ 62 个汉字的查询，
     * 已经远超正常问句长度。
     */
    private static final int MAX_QUERY_GRAMS = 60;

    private final KbChunkMapper chunkMapper;
    private final KbEmbeddingMapper embeddingMapper;
    private final KbEmbeddingPort embeddingPort;
    private final DataSource dataSource;

    public KbHybridSearcher(
        KbChunkMapper chunkMapper,
        KbEmbeddingMapper embeddingMapper,
        KbEmbeddingPort embeddingPort,
        DataSource dataSource
    ) {
        this.chunkMapper = chunkMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingPort = embeddingPort;
        this.dataSource = dataSource;
    }

    /**
     * 检索命中项。
     *
     * @param chunkId         块 ID
     * @param knowledgeId     所属文档（wiki 块这里是 wiki 页 ID，见 {@link #chunkType()}）
     * @param content         块正文（不含表头，保位置不变式）
     * @param contextHeader   表头
     * @param score           融合分数 [0,1]
     * @param vectorRank      向量路名次（0 = 未命中）
     * @param keywordRank     关键词路名次（0 = 未命中）
     * @param chunkType       分块类型（{@code text} / {@code image} / {@code wiki_page} …）
     */
    public record Hit(
        String chunkId, String knowledgeId, String content, String contextHeader,
        double score, int vectorRank, int keywordRank, String chunkType
    ) {
        /** 普通文本块的类型值（{@code kb_chunk.chunk_type} 的默认值）。 */
        public static final String TYPE_TEXT = "text";

        /** Wiki 页块（源侧 {@code ChunkTypeWikiPage}，WIKI_BOOST 的识别依据）。 */
        public static final String TYPE_WIKI_PAGE = "wiki_page";

        /**
         * 兼容构造：不带块类型 ⇒ 视为普通文本块。
         *
         * <p>⚠️ 保留它是因为检索之外还有 11 处构造（实体路、测试、工具）只关心
         * 「哪一块、多少分」，不该被迫填一个与自己无关的字段。
         */
        public Hit(String chunkId, String knowledgeId, String content, String contextHeader,
                   double score, int vectorRank, int keywordRank) {
            this(chunkId, knowledgeId, content, contextHeader, score, vectorRank,
                keywordRank, TYPE_TEXT);
        }

        /** 是否 wiki 页块（WIKI_BOOST 判据）。 */
        public boolean isWikiPage() {
            return TYPE_WIKI_PAGE.equalsIgnoreCase(chunkType);
        }
    }

    /**
     * 混合检索。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本
     * @param limit           返回条数（&lt;= 0 用 10）
     * @param modelId         嵌入模型 ID（null/空 = 跳过向量路）
     * @return 按分数降序的命中
     */
    public List<Hit> search(String knowledgeBaseId, String query, int limit, String modelId) {
        return search(knowledgeBaseId, query, limit, modelId, 0.0, 0.0);
    }

    /**
     * 混合检索（带**融合前**的原始分阈值）。
     *
     * <h2>⭐⭐ 阈值必须作用在融合之前</h2>
     * RRF 只用<b>名次</b>融合两路，融合后的分数已经与原始相似度无关
     * （源侧 {@code vector_score} 也是 {@code json:"-"} 不外泄）。
     * ⇒ 若在融合后再按阈值筛，筛的是「名次分的合成值」，与「这条到底像不像」脱钩。
     * 源侧（{@code knowledgebase_search.go:547/577}）同样把阈值传进各路召回的
     * {@code SearchParams}，在每路内部先筛再融合 —— 这里照此。
     *
     * <p>⚠️ 阈值 0 = 不过滤（与源侧 {@code VectorThreshold: 0 保留全部} 一致）。
     *
     * @param knowledgeBaseId   知识库 id
     * @param query             查询文本
     * @param limit             返回条数（&lt;= 0 用 10）
     * @param modelId           嵌入模型 id（null/空 = 跳过向量路）
     * @param vectorThreshold   向量余弦下限（&lt;= 0 = 不过滤）
     * @param keywordThreshold  关键词分下限（&lt;= 0 = 不过滤）
     * @return 按融合分降序的命中
     */
    public List<Hit> search(String knowledgeBaseId, String query, int limit, String modelId,
                            double vectorThreshold, double keywordThreshold) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int top = limit <= 0 ? 10 : limit;

        List<String> vectorIds = List.of();
        if (modelId != null && !modelId.isBlank()) {
            vectorIds = vectorRecall(knowledgeBaseId, q, modelId, vectorThreshold);
        } else {
            log.debug("[检索] 未指定嵌入模型，跳过向量路 kb={}", knowledgeBaseId);
        }
        List<String> keywordIds = keywordRecall(knowledgeBaseId, q, keywordThreshold);

        if (vectorIds.isEmpty() && keywordIds.isEmpty()) {
            return List.of();
        }

        List<KbRrfFusion.Fused> fused = KbRrfFusion.fuse(
            vectorIds, keywordIds,
            KbRrfFusion.DEFAULT_K,
            KbRrfFusion.DEFAULT_VECTOR_WEIGHT,
            KbRrfFusion.DEFAULT_KEYWORD_WEIGHT);

        List<KbRrfFusion.Fused> cut = fused.size() > top ? fused.subList(0, top) : fused;
        return materialize(cut);
    }

    // ── 向量召回 ────────────────────────────────────────────────────

    private List<String> vectorRecall(String kbId, String query, String modelId) {
        return vectorRecall(kbId, query, modelId, 0.0);
    }

    /**
     * 向量召回（按余弦相似度阈值先筛）。
     *
     * @param threshold 余弦下限（&lt;= 0 = 不过滤）
     */
    private List<String> vectorRecall(String kbId, String query, String modelId, double threshold) {
        float[] queryVec;
        try {
            List<float[]> got = embeddingPort.embed(modelId, List.of(query));
            if (got == null || got.isEmpty()) {
                log.warn("[检索] 查询向量为空，跳过向量路 kb={}", kbId);
                return List.of();
            }
            queryVec = got.get(0);
        } catch (RuntimeException e) {
            // ⭐ 降级而不是失败：一次模型抖动不该让整个检索不可用
            log.warn("[检索] 查询嵌入失败，退回纯关键词检索 kb={} 原因={}", kbId, e.getMessage());
            return List.of();
        }

        // ⚠️ 必须按 model_id 过滤：不同模型的向量空间不可比，混算余弦是垃圾结果
        List<KbEmbeddingRecord> rows = embeddingMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("model_id = ?", modelId)
                .and("source_type = ?", "chunk"));

        List<Scored> scored = new ArrayList<>(rows.size());
        for (KbEmbeddingRecord row : rows) {
            float[] vec;
            try {
                vec = KbVectorCodec.decode(row.getVector());
            } catch (IllegalArgumentException e) {
                log.warn("[检索] 向量数据损坏，跳过 embedding={} 原因={}", row.getId(), e.getMessage());
                continue;
            }
            if (vec.length != queryVec.length) {
                // 维度不一致 = 模型换了但旧向量没清，跳过（不参与排序）
                continue;
            }
            double sim = KbVectorCodec.cosine(queryVec, vec);
            scored.add(new Scored(row.getSourceId(), sim));
        }

        scored.sort(Comparator
            .comparingDouble(Scored::score).reversed()
            .thenComparing(Scored::chunkId));  // 同分按 id，保证可复现

        List<String> out = new ArrayList<>(Math.min(scored.size(), CANDIDATES_PER_ROUTE));
        int dropped = 0;
        for (int i = 0; i < scored.size() && out.size() < CANDIDATES_PER_ROUTE; i++) {
            Scored s = scored.get(i);
            if (threshold > 0 && s.score() < threshold) {
                // 已按分数降序 ⇒ 后面只会更低，直接停
                dropped = scored.size() - i;
                break;
            }
            out.add(s.chunkId());
        }
        if (dropped > 0 && log.isDebugEnabled()) {
            log.debug("[检索] 向量路阈值过滤 kb={} 阈值={} 丢弃={}", kbId, threshold, dropped);
        }
        return out;
    }

    // ── 关键词召回 ──────────────────────────────────────────────────

    /**
     * FTS5 trigram 检索；查询串 &lt; 3 字符时回落 LIKE。
     *
     * <h2>⭐⭐ 为什么不能把整句当短语</h2>
     * 真机实测（B5 E2E）：问句「农村生活污水处理设施的覆盖率要求是多少？」对一篇
     * 确实包含「覆盖率应当达到百分之九十以上」的文档召回 **0 条**。
     * <p>原因：trigram 分词器下，{@code MATCH '"整句"'} 等价于<b>子串包含</b> ——
     * 只有原文逐字出现整句才会命中。而自然语言问句几乎不可能在文档里原样出现。
     * <p>⇒ 拆成 <b>3-gram 的 OR</b>：只要有一个 3 字片段出现就算候选，
     * 再由 SQLite 的 bm25 {@code rank} 按命中片段数量排序。
     * 实测同一问句拆 3-gram 后命中 1 条。
     *
     * <p>⚠️ FTS5 的 {@code MATCH} 语法里双引号是「短语」操作符，用户输入里的引号
     * 必须转义（连写两个），否则会抛 SQL 错误。
     */
    private List<String> keywordRecall(String kbId, String query) {
        return keywordRecall(kbId, query, 0.0);
    }

    /**
     * 关键词召回（FTS5 trigram，按 bm25 归一化分阈值先筛）。
     *
     * <p><b>分数怎么来</b>：SQLite FTS5 的 {@code rank} 是 bm25 的<b>负值</b>
     * （越负越相关，0 表示完全匹配不到词项）。源侧 keyword score 与它同源。
     * 这里用 {@code 1/(1 + max(0, -rank))} 归一到 (0,1]：
     * {@code rank=-1 → 0.5}、{@code -3 → 0.25}、{@code -9 → 0.1}。
     *
     * <p>⚠️ 小语料上 bm25 的绝对值很小（常见 {@code -1e-6} 量级）⇒ 归一化后都接近 1，
     * 阈值实际上几乎不过滤。这是**安全方向**：宁可多给几条让重排去判，
     * 也不要因为一个不适配小语料的硬阈值把正确结果砍掉。
     * 真机上要让阈值真正生效，需要用大语料校准 —— 这一点已在 RUN_LOG 登记。
     *
     * @param threshold 关键词分下限（&lt;= 0 = 不过滤）
     */
    private List<String> keywordRecall(String kbId, String query, double threshold) {
        int cpLen = query.codePointCount(0, query.length());
        if (cpLen < TRIGRAM_MIN_CHARS) {
            return keywordRecallLike(kbId, query);
        }
        String matchExpr = buildTrigramMatch(query);
        if (matchExpr == null) {
            return keywordRecallLike(kbId, query);
        }
        // rank 也要取出来：阈值要按原始分筛（源侧 keyword_threshold 语义）
        String sql = "SELECT chunk_id, rank FROM kb_chunk_fts "
            + "WHERE kb_chunk_fts MATCH ? AND knowledge_base_id = ? "
            + "ORDER BY rank LIMIT ?";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchExpr);
            ps.setString(2, kbId);
            ps.setInt(3, CANDIDATES_PER_ROUTE);
            try (var rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                int dropped = 0;
                while (rs.next() && out.size() < CANDIDATES_PER_ROUTE) {
                    double rank = rs.getDouble(2);
                    if (threshold > 0 && keywordScoreOf(rank) < threshold) {
                        dropped++;
                        continue;
                    }
                    out.add(rs.getString(1));
                }
                if (dropped > 0 && log.isDebugEnabled()) {
                    log.debug("[检索] 关键词路阈值过滤 kb={} 阈值={} 丢弃={}", kbId, threshold, dropped);
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("[检索] FTS 检索失败，退回 LIKE kb={} 原因={}", kbId, e.getMessage());
            return keywordRecallLike(kbId, query);
        }
    }

    /**
     * FTS5 bm25 rank（负值）→ (0,1] 的关键词分。
     *
     * @param rank SQLite FTS5 {@code rank} 列（负数；0 = 无词项命中）
     * @return 归一化分数；rank >= 0 → 0（视为完全没命中）
     */
    static double keywordScoreOf(double rank) {
        if (rank >= 0) {
            return 0.0;
        }
        return 1.0 / (1.0 + Math.abs(rank));
    }

    /**
     * 把查询拆成 3-gram 的 OR 表达式（每个 gram 加双引号并按短语处理）。
     *
     * @param query 查询串（码点数 ≥ 3）
     * @return MATCH 表达式；无法构造时 {@code null}
     */
    static String buildTrigramMatch(String query) {
        if (query == null) {
            return null;
        }
        int[] cps = query.codePoints().toArray();
        if (cps.length < TRIGRAM_MIN_CHARS) {
            return null;
        }
        // ⚠️ 上限：超长查询会生成几百个 OR 项，SQL 与解析开销都不可控
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + TRIGRAM_MIN_CHARS <= cps.length && grams.size() < MAX_QUERY_GRAMS; i++) {
            grams.add(new String(cps, i, TRIGRAM_MIN_CHARS));
        }
        if (grams.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(grams.size() * 16);
        for (String g : grams) {
            if (sb.length() > 0) {
                sb.append(" OR ");
            }
            // 双引号转义：FTS5 里 "" 表示一个字面双引号
            sb.append('"').append(g.replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    private List<String> keywordRecallLike(String kbId, String query) {
        // LIKE 回落的通配符转义（ESCAPE 子句）—— 用户输入里的 % 与 _ 是字面量
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("(content LIKE ? ESCAPE '\\' OR context_header LIKE ? ESCAPE '\\')",
                    pattern, pattern)
                .orderBy("chunk_index", true)
                .limit(CANDIDATES_PER_ROUTE));
        List<String> out = new ArrayList<>(rows.size());
        for (KbChunkRecord r : rows) {
            out.add(r.getId());
        }
        return out;
    }

    // ── 回填正文 ────────────────────────────────────────────────────

    private List<Hit> materialize(List<KbRrfFusion.Fused> fused) {
        if (fused.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (KbRrfFusion.Fused f : fused) {
            ids.add(f.id());
        }
        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create().where(
                "id IN (" + String.join(",", ids.stream().map(x -> "?").toList()) + ")",
                ids.toArray()));
        java.util.Map<String, KbChunkRecord> byId = new java.util.HashMap<>();
        for (KbChunkRecord r : rows) {
            byId.put(r.getId(), r);
        }

        List<Hit> out = new ArrayList<>(fused.size());
        for (KbRrfFusion.Fused f : fused) {
            KbChunkRecord r = byId.get(f.id());
            if (r == null) {
                continue;  // 索引里在、块表已删（重解析竞态）⇒ 跳过
            }
            out.add(new Hit(r.getId(), r.getKnowledgeId(), r.getContent(),
                r.getContextHeader(), f.score(), f.vectorRank(), f.keywordRank(),
                r.getChunkType() == null || r.getChunkType().isBlank()
                    ? Hit.TYPE_TEXT : r.getChunkType()));
        }
        return out;
    }

    private record Scored(String chunkId, double score) {
    }
}

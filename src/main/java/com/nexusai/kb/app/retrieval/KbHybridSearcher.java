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
        return search(knowledgeBaseId, query, limit, modelId,
            vectorThreshold, keywordThreshold, MODE_HYBRID);
    }

    /** 检索模式 · 混合（两路都走）· 对齐源站 {@code search_knowledge} 的 {@code mode=hybrid}（默认）。 */
    public static final String MODE_HYBRID = "hybrid";
    /** 检索模式 · 只走向量路 · 对齐源站 {@code mode=semantic}。 */
    public static final String MODE_SEMANTIC = "semantic";
    /** 检索模式 · 只走关键词路 · 对齐源站 {@code mode=keyword}。 */
    public static final String MODE_KEYWORD = "keyword";

    /**
     * 按<b>检索模式</b>选择走哪几路召回 · 对齐源站 {@code search_knowledge} 的 {@code mode} 参数
     * （{@code internal/agent/tools/*search_knowledge*.go}）。
     *
     * <h2>⭐⭐ 三档语义（源站描述逐字对齐）</h2>
     * <ul>
     *   <li>{@code hybrid}（默认）：两路都走再 RRF 融合 —— 覆盖大多数问题</li>
     *   <li>{@code semantic}：<b>只走向量路</b> —— 适合改述的、概念性的问题</li>
     *   <li>{@code keyword}：<b>只走关键词路</b> —— 适合精确词、错误信息、标识符</li>
     * </ul>
     *
     * <p>⚠️ {@code semantic} 但没配嵌入模型时，<b>不静默退化成 hybrid</b>：返回空结果并在
     * 日志里 warn，因为「用户明确要求语义检索」和「模型缺失」是两件事，
     * 悄悄换成关键词检索会让他以为语义检索有效。
     *
     * <p>⚠️ 未知 mode 值按 {@code hybrid} 处理（源站 {@code mode} 是枚举，非法值不会到这一步；
     * Java 侧做兜底是为了让模型写错时仍能检索，而非报错中断）。
     *
     * @param knowledgeBaseId   知识库 id
     * @param query             查询文本
     * @param limit             返回条数（&lt;= 0 用 10）
     * @param modelId           嵌入模型 id（null/空 = 跳过向量路）
     * @param vectorThreshold   向量余弦下限（&lt;= 0 = 不过滤）
     * @param keywordThreshold  关键词分下限（&lt;= 0 = 不过滤）
     * @param mode              hybrid / semantic / keyword
     * @return 按融合分降序的命中
     */
    public List<Hit> search(String knowledgeBaseId, String query, int limit, String modelId,
                            double vectorThreshold, double keywordThreshold, String mode) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        int top = limit <= 0 ? 10 : limit;
        String m = normalizeMode(mode);

        boolean wantVector = !MODE_KEYWORD.equals(m);
        boolean wantKeyword = !MODE_SEMANTIC.equals(m);

        List<String> vectorIds = List.of();
        if (wantVector) {
            if (modelId != null && !modelId.isBlank()) {
                vectorIds = vectorRecall(knowledgeBaseId, q, modelId, vectorThreshold);
            } else if (MODE_SEMANTIC.equals(m)) {
                // ⛔ 不静默退化成关键词检索：显式要求 semantic 却没有模型，如实返回空
                log.warn("[检索] mode=semantic 但未配置嵌入模型，语义检索不可用 kb={}", knowledgeBaseId);
                return List.of();
            } else {
                log.debug("[检索] 未指定嵌入模型，跳过向量路 kb={}", knowledgeBaseId);
            }
        }
        List<String> keywordIds = wantKeyword
            ? keywordRecall(knowledgeBaseId, q, keywordThreshold)
            : List.of();

        if (vectorIds.isEmpty() && keywordIds.isEmpty()) {
            return List.of();
        }

        List<KbRrfFusion.Fused> fused = KbRrfFusion.fuse(
            vectorIds, keywordIds,
            KbRrfFusion.DEFAULT_K,
            KbRrfFusion.DEFAULT_VECTOR_WEIGHT,
            KbRrfFusion.DEFAULT_KEYWORD_WEIGHT);

        // ⚠️ 顺序：先回填（会丢掉「块已删 / 块禁用 / 文档已删」的命中）再截断。
        //    反过来做的话，被丢掉的命中会白占名额 ⇒ 实际返回条数少于 limit。
        List<Hit> hits = materialize(fused);
        return hits.size() > top ? new ArrayList<>(hits.subList(0, top)) : hits;
    }

    /**
     * 规范化检索模式：null / 空 / 未知值 → {@code hybrid}（对齐源站默认值）。
     *
     * @param mode 原始值
     * @return hybrid / semantic / keyword 三者之一
     */
    public static String normalizeMode(String mode) {
        if (mode == null) {
            return MODE_HYBRID;
        }
        String m = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (MODE_SEMANTIC.equals(m) || MODE_KEYWORD.equals(m)) {
            return m;
        }
        return MODE_HYBRID;
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
        // ⭐ 块级可见性闸（缺陷：is_enabled=0 的分块仍被召回）
        //   kb_embedding 只是索引产物，块被禁用后向量行可能还在（禁用不走重建索引）
        //   ⇒ 用子查询把「未删 + 启用」的块限定出来，一次查询完成（不是逐条查、也不是 N+1）。
        List<KbEmbeddingRecord> rows = embeddingMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("model_id = ?", modelId)
                .and("source_type = ?", "chunk")
                .and("source_id IN (SELECT id FROM kb_chunk WHERE knowledge_base_id = ?"
                    + " AND deleted = 0 AND is_enabled = 1)", kbId));

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
        // ⭐ JOIN kb_chunk 做块级可见性闸（缺陷：is_enabled=0 的块仍被召回）。
        //   FTS 表没有 is_enabled 列，且它只是索引产物 ⇒ 以 kb_chunk 为准。
        //   ⚠️ FTS5 的 MATCH 必须作用在**表名**上（带别名会报 no such column）——
        //   真机实测：`FROM kb_chunk_fts f ... WHERE f MATCH ?` 直接 SQL 错误。
        String sql = "SELECT kb_chunk_fts.chunk_id, kb_chunk_fts.rank FROM kb_chunk_fts "
            + "JOIN kb_chunk c ON c.id = kb_chunk_fts.chunk_id "
            + "WHERE kb_chunk_fts MATCH ? AND kb_chunk_fts.knowledge_base_id = ? "
            + "AND c.deleted = 0 AND c.is_enabled = 1 "
            + "ORDER BY kb_chunk_fts.rank LIMIT ?";
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
                // ⭐ 禁用块不召回（与 FTS 路同口径）
                .and("is_enabled = 1")
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
            QueryWrapper.create()
                // ⭐ 禁用块不返回正文（与召回侧同一闸；索引可能残留旧行）
                .where("is_enabled = 1")
                .and("id IN (" + String.join(",", ids.stream().map(x -> "?").toList()) + ")",
                    ids.toArray()));

        // ⭐⭐ 文档状态闸（缺陷：逻辑删 / 禁用的文档仍被召回并返回完整正文）
        //   索引产物（kb_embedding / kb_chunk_fts）不会随 kb_knowledge.deleted 自动失效，
        //   所以检索侧必须自己确认「块所属文档仍然可见」。
        //   ⚠️ 一次批量 IN 查询（内部按批切），⛔ 不是逐条查（N+1）。
        Set<String> knowledgeIds = new LinkedHashSet<>();
        for (KbChunkRecord r : rows) {
            if (r.getKnowledgeId() != null) {
                knowledgeIds.add(r.getKnowledgeId());
            }
        }
        // 只收录「表里确实存在」的文档 → 可见性；查不到的（wiki 页块）不在 map 里，不参与过滤。
        java.util.Map<String, Boolean> docVisible = knowledgeVisibility(knowledgeIds);

        java.util.Map<String, KbChunkRecord> byId = new java.util.HashMap<>();
        for (KbChunkRecord r : rows) {
            Boolean visible = docVisible.get(r.getKnowledgeId());
            if (Boolean.FALSE.equals(visible)) {
                continue;  // 逻辑删 / disabled 的文档 ⇒ 不返回正文
            }
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

    // ── 可见性闸（批量，⛔ 不逐条查）─────────────────────────────────

    /** 文档状态批量查询的单批 id 数（SQLite 绑定变量数有上限，分批更稳）。 */
    private static final int IN_BATCH = 200;

    /**
     * 文档级可见性：{@code knowledgeId → 是否可见}。
     *
     * <p><b>⭐ 为什么检索侧也要问一遍</b>：删文档虽然有清理索引的路径，但它是「先清索引、
     * 再删主行」的多步操作，中途崩溃或别的入口漏清都会留下孤儿索引 ⇒ 已删文档仍能被召回，
     * 而且返回**完整正文**。这是数据正确性问题，不能只靠上游自觉。
     *
     * <p><b>口径</b>（V257 DDL）：{@code kb_knowledge.deleted = 0} 且
     * {@code enable_status = 'enabled'}（列注释：enabled | disabled）。
     * ⚠️ <b>不</b>在这里过滤 {@code parse_status}：{@code KbAdvancedService#rebuildIndex}
     * 会对 {@code finalizing} 的文档建索引，若这里要求 {@code completed}，
     * 那些文档会「索引建好了但永远搜不到」。
     *
     * <p>⚠️ 查不到的 id <b>不进</b> map —— wiki 页块把 {@code knowledge_id} 填成 wiki 页 id，
     * 没有对应知识条目（见 {@code KbWikiChunkSync}），不能因此被当成「已删」丢掉。
     *
     * @param knowledgeIds 候选文档 id
     * @return id → 是否可见；读失败时返回空 map（= 不额外过滤）
     */
    private java.util.Map<String, Boolean> knowledgeVisibility(Set<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Boolean> out = new java.util.HashMap<>(knowledgeIds.size() * 2);
        List<String> all = new ArrayList<>(knowledgeIds);
        try {
            for (int start = 0; start < all.size(); start += IN_BATCH) {
                List<String> batch = all.subList(start, Math.min(start + IN_BATCH, all.size()));
                String sql = "SELECT id, deleted, enable_status FROM kb_knowledge "
                    + "WHERE id IN (" + String.join(",", batch.stream().map(x -> "?").toList()) + ")";
                try (var conn = dataSource.getConnection();
                     var ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < batch.size(); i++) {
                        ps.setString(i + 1, batch.get(i));
                    }
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString(1),
                                rs.getInt(2) == 0 && "enabled".equals(rs.getString(3)));
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[检索] 文档可见性查询失败，跳过该闸 原因={}", e.getMessage());
            return java.util.Map.of();
        }
    }
}

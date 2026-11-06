package com.nexusai.kb.app.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合 · 逐字对齐 WeKnora
 * {@code internal/application/service/knowledgebase_search_fusion.go:170-220}。
 *
 * <p><b>为什么用 RRF 而不是「分数加权求和」</b>：向量分（余弦，[-1,1]）与
 * BM25 分（无上界，常 &gt;10）**量纲不同、不可比**。直接相加会让 BM25 完全压住向量分。
 * RRF 只用<b>排名</b>，天然免疫量纲差异 —— 这是源侧选它的原因（源侧注释也明说
 * {@code rescaleUnboundedScores} 就是为绕开同类问题而加的补丁）。
 *
 * <p><b>公式</b>：
 * <pre>
 *   rrf(chunk) = Σ_lists  weight_list / (k + rank_in_list)
 *   rank 从 1 开始（源侧 bestRanks 是 1-based）
 *   最终分数归一化：rrf / maxRRF，其中 maxRRF = (wV + wK) / (k + 1)
 *   ⇒ 双路都排第 1 的块得分为 1.0，保证输出落在 [0,1]
 * </pre>
 *
 * <p><b>参数默认值（源侧 {@code retrieval_config.go:92-115}）</b>：
 * <ul>
 *   <li>{@code k = 60}（{@code RRFK <= 0} 时回落）</li>
 *   <li>向量权重 {@code 0.7} / 关键词权重 {@code 0.3}
 *       （两者都为 0 时回落；单独一个为 0 时各自回落 0.7 / 0.3）</li>
 * </ul>
 *
 * <p><b>⭐ 同一列表内重复出现的块只取最好名次</b>（源侧 {@code bestRanks}）：
 * 多知识库 fan-out 时同一个块可能被多个子查询召回，重复计分会让它虚高。
 */
public final class KbRrfFusion {

    /** RRF 平滑常数默认值（源侧 {@code GetEffectiveRRFK}）。 */
    public static final int DEFAULT_K = 60;

    /** 向量路默认权重（源侧 {@code GetEffectiveRRFWeights}）。 */
    public static final double DEFAULT_VECTOR_WEIGHT = 0.7;

    /** 关键词路默认权重。 */
    public static final double DEFAULT_KEYWORD_WEIGHT = 0.3;

    private KbRrfFusion() {
    }

    /**
     * 参与融合的候选（只在内部使用，避免调用方依赖检索结果的具体结构）。
     *
     * @param id   块 ID
     * @param rank 在该列表中的名次（1 起）
     */
    public record Ranked(String id, int rank) {
    }

    /**
     * 融合结果。
     *
     * @param id    块 ID
     * @param score 归一化后的 RRF 分数，落在 [0, 1]
     * @param vectorRank 向量路名次（0 = 未命中）
     * @param keywordRank 关键词路名次（0 = 未命中）
     */
    public record Fused(String id, double score, int vectorRank, int keywordRank) {
    }

    /**
     * 双路融合。
     *
     * @param vectorIds  向量路结果（已按相似度降序，index 0 = 第 1 名）
     * @param keywordIds 关键词路结果（已按分数降序）
     * @param k          平滑常数（&lt;= 0 用默认 60）
     * @param vectorWeight 向量权重（&lt;= 0 用默认 0.7）
     * @param keywordWeight 关键词权重（&lt;= 0 用默认 0.3）
     * @return 按分数降序的融合结果；两路都空时返回空列表
     */
    public static List<Fused> fuse(
        List<String> vectorIds, List<String> keywordIds,
        int k, double vectorWeight, double keywordWeight
    ) {
        int rrfK = k <= 0 ? DEFAULT_K : k;
        double wV = vectorWeight <= 0 ? DEFAULT_VECTOR_WEIGHT : vectorWeight;
        double wK = keywordWeight <= 0 ? DEFAULT_KEYWORD_WEIGHT : keywordWeight;

        Map<String, Integer> vectorRanks = bestRanks(vectorIds);
        Map<String, Integer> keywordRanks = bestRanks(keywordIds);

        if (vectorRanks.isEmpty() && keywordRanks.isEmpty()) {
            return List.of();
        }

        // 保持插入顺序做确定性输出（同一分数时按首次出现顺序，结果可复现）
        Map<String, Fused> acc = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : vectorRanks.entrySet()) {
            acc.put(e.getKey(), new Fused(e.getKey(), 0.0, e.getValue(), 0));
        }
        for (Map.Entry<String, Integer> e : keywordRanks.entrySet()) {
            Fused prev = acc.get(e.getKey());
            acc.put(e.getKey(), new Fused(
                e.getKey(), 0.0,
                prev == null ? 0 : prev.vectorRank(),
                e.getValue()));
        }

        double maxRrf = (wV + wK) / (double) (rrfK + 1);
        List<Fused> out = new ArrayList<>(acc.size());
        for (Fused f : acc.values()) {
            double raw = 0.0;
            if (f.vectorRank() > 0) {
                raw += wV / (double) (rrfK + f.vectorRank());
            }
            if (f.keywordRank() > 0) {
                raw += wK / (double) (rrfK + f.keywordRank());
            }
            double normalized = maxRrf > 0 ? raw / maxRrf : 0.0;
            out.add(new Fused(f.id(), normalized, f.vectorRank(), f.keywordRank()));
        }

        // 分数降序；同分按 id 升序 —— 保证同输入同输出（可回归测试）
        out.sort((a, b) -> {
            int c = Double.compare(b.score(), a.score());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        return out;
    }

    /**
     * 每块在列表中的最好名次（1 起）。重复出现只取最好（源侧 {@code bestRanks}）。
     *
     * @param ids 已排序的块 ID 列表
     * @return id → 名次
     */
    static Map<String, Integer> bestRanks(List<String> ids) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        if (ids == null) {
            return ranks;
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null || id.isEmpty()) {
                continue;
            }
            int rank = i + 1;
            Integer prev = ranks.get(id);
            if (prev == null || rank < prev) {
                ranks.put(id, rank);
            }
        }
        return ranks;
    }
}

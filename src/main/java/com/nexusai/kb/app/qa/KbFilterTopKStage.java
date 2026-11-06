package com.nexusai.kb.app.qa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 阶段 8 · FILTER_TOP_K（对齐源侧 {@code chat_pipeline/filter_top_k.go}）。
 *
 * <h2>为什么 merge 之后还要再截一次</h2>
 * {@code KbMergeStage} 截的是 {@code context_max_chunks}，且排序只按分数。
 * 这一阶段补两件 merge 不做的事：
 * <ol>
 *   <li><b>完全确定的排序</b>：同分时顺序由 knowledgeId / chunkType / chunkIndex / chunkId
 *       决定，⛔ 不依赖上游 Map 的遍历顺序（否则同一查询两次答得不一样）；</li>
 *   <li><b>按 {@code rerank_top_k} 再截一次</b>：与 {@code context_max_chunks} 是两个不同的旋钮，
 *       源侧在 merge 后也按 rerankTopK 收口。</li>
 * </ol>
 *
 * <h2>⭐ 必须豁免 score == 0 的命中</h2>
 * 阶段 4c 实体检索与阶段 7 历史引用注入产出的命中<b>分数恒为 0</b>
 * （它们不是文本相似度排出来的）。若按分数排序后直接截断，这些**结构性证据**
 * 会被全部丢掉 —— 前面两个阶段的活就白干了。
 * ⇒ 规则：分数 &gt; 0 的按序取 topK，分数 == 0 的全部追加在后面
 * （与源侧 {@code TopKKeepingKept}「kept 追加在后」同形）。
 *
 * <h2>⚠️ 与源侧的两处偏离（已登记 RUN_LOG）</h2>
 * <ol>
 *   <li><b>不做父块槽位聚合</b>：源侧按 {@code ParentChunkID} 聚合。本仓
 *       {@code KbMergeStage} 的 {@code groupAndMergeSequential} 已把同文档同类型的
 *       相邻块合并成一段，且 {@link KbMergeStage.MergedChunk} 不带父块 id ⇒
 *       在这里没有可靠的聚合键。⛔ 不硬造一个键去做聚合。</li>
 *   <li><b>不做图片豁免</b>：源侧靠重排阶段写的 {@code Metadata[KeptBy]} 标记，
 *       本仓重排没有这个概念。⛔ 不为一个从未产出的标记写豁免逻辑（缺陷 27 同类）。</li>
 * </ol>
 */
@Component
public class KbFilterTopKStage {

    private static final Logger log = LoggerFactory.getLogger(KbFilterTopKStage.class);

    /**
     * 确定性排序（源侧 {@code sortSearchResultsDeterministically}）。
     *
     * <p>比较顺序：分降 → knowledgeId 升 → chunkType 升 → chunkIndex 升 → chunkId 升。
     * 同输入必须同输出。
     */
    static void sortDeterministically(List<KbMergeStage.MergedChunk> chunks) {
        chunks.sort(Comparator
            .comparingDouble(KbMergeStage.MergedChunk::score).reversed()
            .thenComparing(c -> nz(c.knowledgeId()))
            .thenComparing(c -> nz(c.chunkType()))
            .thenComparingInt(KbMergeStage.MergedChunk::chunkIndex)
            .thenComparing(c -> nz(c.chunkId())));
    }

    /**
     * 截断（源侧 {@code TopKKeepingKept} 的等价物）。
     *
     * @param sorted 已确定性排序的片段
     * @param topK   上限（&lt;= 0 ⇒ 不截）
     * @return 截断后（分数 0 的片段全部保留，追加在后）
     */
    public static List<KbMergeStage.MergedChunk> cutKeepingZeroScores(
        List<KbMergeStage.MergedChunk> sorted, int topK
    ) {
        if (sorted == null || sorted.isEmpty() || topK <= 0) {
            return sorted == null ? List.of() : sorted;
        }
        List<KbMergeStage.MergedChunk> ranked = new ArrayList<>();
        List<KbMergeStage.MergedChunk> structural = new ArrayList<>();
        for (KbMergeStage.MergedChunk c : sorted) {
            if (c == null) {
                continue;
            }
            if (c.score() == 0.0) {
                structural.add(c);
            } else {
                ranked.add(c);
            }
        }
        List<KbMergeStage.MergedChunk> out =
            new ArrayList<>(Math.min(ranked.size(), topK) + structural.size());
        out.addAll(ranked.subList(0, Math.min(ranked.size(), topK)));
        out.addAll(structural);
        return out;
    }

    /**
     * 完整一次过滤：排序 → 截断。
     *
     * @param chunks 合并后的片段（可空）
     * @param topK   上限（&lt;= 0 ⇒ 只排序不截断）
     * @return 过滤后的片段（空输入 ⇒ 空列表）
     */
    public List<KbMergeStage.MergedChunk> filter(List<KbMergeStage.MergedChunk> chunks, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        int before = chunks.size();
        List<KbMergeStage.MergedChunk> work = new ArrayList<>(chunks);
        sortDeterministically(work);
        List<KbMergeStage.MergedChunk> out = cutKeepingZeroScores(work, topK);
        long zeroKept = out.stream().filter(c -> c.score() == 0.0).count();
        log.info("[问答链] ⑧ 截断 输入={} 输出={} 上限={} 分数0保留={}",
            before, out.size(), topK, zeroKept);
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

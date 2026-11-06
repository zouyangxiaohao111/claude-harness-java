package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 阶段 5c · MEMORY_AFFINITY（对齐源侧 {@code chat_pipeline/memory_affinity.go}）。
 *
 * <h2>它在补什么</h2>
 * 只把记忆塞进提示词、检索却对所有人返回同样的段落，是「记忆」功能的浅层一半。
 * 知识库产品里最耐久的个人信号是<b>这个人实际反复在用哪些资料</b>，
 * 而重排正是放这个信号的地方。
 *
 * <h2>⭐ 三个数字为什么是这三个</h2>
 * <ul>
 *   <li><b>1.15 上限</b>：明显低于 wiki boost（1.3）。因为这个信号更弱 ——
 *       「文档出现在过去的回答里」只说明检索器一再用它，不等于用户觉得它有用。
 *       加成是用来<u>打破相近段落之间的平局</u>的，绝不能把无关文档拖到第一。</li>
 *   <li><b>log1p(hits)/log1p(8)</b>：第 10 次复用远小于第 2 次的增量。
 *       熟悉度应该是缓慢累积的「轻推」，不是把人锁死在第一次打开的那份文档上的反馈环。</li>
 *   <li><b>hits &lt; 2 跳过</b>：只出现过一次不算「熟悉」（源侧 {@code MemoryDocAffinityMinHits = 2}）。</li>
 * </ul>
 *
 * <h2>⚠️ 必须在重排<b>之后</b></h2>
 * 源侧挂在 {@code CHUNK_RERANK} 事件上、且先 {@code next()} 再增强。
 * 顺序颠倒的话重排会用自己的分数覆盖掉 boost，等于这段代码白写。
 */
@Component
public class KbMemoryAffinityStage {

    private static final Logger log = LoggerFactory.getLogger(KbMemoryAffinityStage.class);

    /** 加成上限（源侧 {@code affinityMaxBoost = 1.15}）。 */
    static final double MAX_BOOST = 1.15;

    /** 曲线饱和点：命中这么多次就吃满加成（源侧 {@code affinityFullHits = 8.0}）。 */
    static final double FULL_HITS = 8.0;

    /** 最小命中数，低于它不加成（源侧 {@code MemoryDocAffinityMinHits = 2}）。 */
    static final int MIN_HITS = 2;

    /** 最多查多少个文档的亲和（源侧 {@code affinityMaxLookup = 200}）。 */
    static final int MAX_LOOKUP = 200;

    /**
     * 对已重排的命中应用个人文档加成。
     *
     * @param hits      重排后的命中（null/空 ⇒ 原样返回）
     * @param affinity  文档 id → 历史命中次数（null/空 ⇒ 原样返回）
     * @param knowledgeIdOf 从命中取文档 id 的函数（便于测试与复用）
     * @return 加成并重排后的命中（**新列表**，输入不被修改）
     */
    public List<KbHybridSearcher.Hit> apply(
        List<KbHybridSearcher.Hit> hits,
        Map<String, Integer> affinity,
        Function<KbHybridSearcher.Hit, String> knowledgeIdOf
    ) {
        if (hits == null || hits.isEmpty() || affinity == null || affinity.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>(hits.size());
        int boosted = 0;
        for (KbHybridSearcher.Hit h : hits) {
            if (h == null) {
                continue;
            }
            String kid = knowledgeIdOf == null ? h.knowledgeId() : knowledgeIdOf.apply(h);
            Integer raw = kid == null ? null : affinity.get(kid);
            if (raw == null || raw < MIN_HITS) {
                out.add(h);
                continue;
            }
            double factor = factor(raw);
            out.add(new KbHybridSearcher.Hit(
                h.chunkId(), h.knowledgeId(), h.content(), h.contextHeader(),
                h.score() * factor, h.vectorRank(), h.keywordRank(), h.chunkType()));
            boosted++;
        }
        if (boosted == 0) {
            log.info("[问答链] ⑤c 记忆亲和 提升=0/{} 未改分（无熟悉文档）", out.size());
            return out;
        }
        // ⚠️ 稳定排序：同分保持重排阶段的相对顺序，否则同一条查询两次答得不一样
        out.sort(Comparator.comparingDouble(KbHybridSearcher.Hit::score).reversed());
        log.info("[问答链] ⑤c 记忆亲和 提升={}/{} 上限={}", boosted, out.size(), MAX_BOOST);
        return out;
    }

    /**
     * 加成系数（源侧 {@code affinityFactor}）。
     *
     * @param hits 历史命中次数
     * @return 1.0（不加成）..{@link #MAX_BOOST}
     */
    static double factor(int hits) {
        if (hits < MIN_HITS) {
            return 1.0;
        }
        double ratio = Math.log1p(hits) / Math.log1p(FULL_HITS);
        if (ratio > 1.0) {
            ratio = 1.0;
        }
        return 1.0 + (MAX_BOOST - 1.0) * ratio;
    }

    /**
     * 从命中里取「要查亲和的文档 id」：去重 + 上限 {@link #MAX_LOOKUP}。
     *
     * <p>⭐ 单独抽出来是因为有两个容易漏的点：<b>必须去重</b>（一个文档几十个块会挤满上限，
     * 后面的文档永远查不到）和<b>必须保序</b>（结果可复现）。
     *
     * @param hits 命中列表
     * @return 文档 id（保序去重，最多 200 个）
     */
    public static List<String> knowledgeIdsToLookup(List<KbHybridSearcher.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (KbHybridSearcher.Hit h : hits) {
            if (h == null) {
                continue;
            }
            String kid = h.knowledgeId();
            if (kid == null || kid.isBlank()) {
                continue;
            }
            ids.add(kid);
            if (ids.size() >= MAX_LOOKUP) {
                break;
            }
        }
        return List.copyOf(ids);
    }
}

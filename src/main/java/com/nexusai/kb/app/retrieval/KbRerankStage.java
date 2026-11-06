package com.nexusai.kb.app.retrieval;

import com.nexusai.application.rerank.RerankService;
import com.nexusai.model.provider.dto.RerankItem;
import com.nexusai.model.provider.dto.RerankResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索结果重排 · 复刻 WeKnora 的 rerank 阶段。
 *
 * <h2>为什么融合之后还要重排</h2>
 * RRF 只用<b>排名</b>融合两路，它擅长「把两路都认可的块排前面」，
 * 但**不理解查询与内容的细粒度相关性**。重排模型（cross-encoder）直接读
 * 「查询 + 文档」对，给出的相关性判断明显更准 —— 这是 RAG 里公认的第二阶段精排。
 *
 * <h2>⭐ 降级的语义（很重要）</h2>
 * <ul>
 *   <li><b>没配重排模型</b> ⇒ 原样返回 RRF 结果（不是错误：RRF 本身可用）</li>
 *   <li><b>重排调用失败</b> ⇒ <b>原样返回 RRF 结果</b> + WARN。
 *       一次模型抖动不该让整个检索空手而归。</li>
 *   <li><b>重排返回的条数少于候选</b> ⇒ 只用返回的（重排模型自己做了截断）</li>
 * </ul>
 *
 * <h2>⚠️ 送入重排的文本</h2>
 * 用 {@code context_header + 正文}（与建索引时的 embeddingContent 同构），
 * 而不是纯正文 —— 否则「某章节下的段落」会丢掉章节语义，重排判断会偏。
 */
@Component
public class KbRerankStage {

    private static final Logger log = LoggerFactory.getLogger(KbRerankStage.class);

    private final RerankService rerankService;

    public KbRerankStage(RerankService rerankService) {
        this.rerankService = rerankService;
    }

    /**
     * 对命中做重排。
     *
     * @param query  查询文本
     * @param hits   RRF 融合后的候选（按融合分降序）
     * @param modelId 重排模型 ID（null/空 ⇒ 不重排，原样返回）
     * @param topN   保留前 N 条（null ⇒ 用模型级默认）
     * @return 重排后的命中；任何失败都回落原列表
     */
    public List<KbHybridSearcher.Hit> rerank(
        String query, List<KbHybridSearcher.Hit> hits, String modelId, Integer topN
    ) {
        return rerank(query, hits, modelId, topN, 0.0);
    }

    /**
     * 重排（带分数阈值）。
     *
     * <p><b>源侧语义</b>（{@code rerank.go:opts.Threshold = chatManage.RerankThreshold}）：
     * 重排模型给的相关性分低于 {@code rerank_threshold} 的候选被<b>丢弃</b>，
     * 而不是留到最后被 topK 截断 —— 两者不同：阈值会真的减条目，
     * 让「一条都不相关」的轮次走无上下文分支，而不是硬塞几条低分资料给模型。
     *
     * <p>⚠️ 阈值 0 = 不过滤（与源侧一致）。
     * ⚠️ 全部被阈值刷掉时返回<b>空列表</b>，⛔ 不回落原候选 —— 回落会让阈值形同虚设。
     *
     * @param threshold 相关性分下限（&lt;= 0 = 不过滤）
     */
    public List<KbHybridSearcher.Hit> rerank(
        String query, List<KbHybridSearcher.Hit> hits, String modelId, Integer topN, double threshold
    ) {
        if (hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        if (modelId == null || modelId.isBlank()) {
            log.debug("[重排] 未配置重排模型，使用 RRF 原始顺序（候选 {} 条）", hits.size());
            return hits;
        }
        if (rerankService == null) {
            // 未装配重排服务（如精简部署）⇒ 同样原样返回，⛔ 不 NPE
            log.warn("[重排] 重排服务未装配，使用 RRF 原始顺序（候选 {} 条）", hits.size());
            return hits;
        }

        List<String> documents = new ArrayList<>(hits.size());
        for (KbHybridSearcher.Hit h : hits) {
            documents.add(rerankTextOf(h));
        }

        RerankResponse resp;
        try {
            resp = rerankService.rerank(modelId, query, documents, topN, null);
        } catch (RuntimeException e) {
            // ⭐ 降级：重排只是精排，失败不该让用户拿不到结果
            log.warn("[重排] 调用失败，回落到 RRF 顺序 候选={} 原因={}", hits.size(), e.getMessage());
            return hits;
        }
        if (resp == null || resp.results() == null || resp.results().isEmpty()) {
            log.warn("[重排] 返回空结果，回落到 RRF 顺序 候选={}", hits.size());
            return hits;
        }

        // 按重排模型给的 index 取回原命中；⚠️ 越界/重复一律丢弃（不信任外部索引）
        List<KbHybridSearcher.Hit> out = new ArrayList<>(resp.results().size());
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int belowThreshold = 0;
        for (RerankItem item : resp.results()) {
            int idx = item.index();
            if (idx < 0 || idx >= hits.size() || !seen.add(idx)) {
                log.warn("[重排] 返回了非法或重复索引 {}（候选 {} 条），已忽略", idx, hits.size());
                continue;
            }
            if (threshold > 0 && item.relevanceScore() < threshold) {
                // 阈值筛在 topN 之前：低分候选不该占位（源侧 RerankOptions.Threshold 同语义）
                belowThreshold++;
                continue;
            }
            out.add(hits.get(idx));
        }
        if (threshold > 0) {
            log.info("[重排] 完成 候选={} 返回={} 阈值={} 低于阈值丢弃={} 模型={}",
                hits.size(), out.size(), threshold, belowThreshold, modelId);
            // ⚠️ 全部被刷掉 ⇒ 返回空：让上层走「无资料」分支，⛔ 不回落原候选
            return out;
        }
        log.info("[重排] 完成 候选={} 返回={} 模型={}", hits.size(), out.size(), modelId);
        return out.isEmpty() ? hits : out;
    }

    /** 重排输入文本 = 表头 + 正文（与建索引口径一致）。 */
    private static String rerankTextOf(KbHybridSearcher.Hit h) {
        String body = h.content() == null ? "" : h.content().strip();
        String header = h.contextHeader();
        return header == null || header.isEmpty() ? body : header + "\n\n" + body;
    }
}

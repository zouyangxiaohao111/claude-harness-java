package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 7 补 · 历史引用注入（对齐源侧 {@code merge.go:injectHistoryResults}）。
 *
 * <h2>它解决什么</h2>
 * 多轮对话里用户会说「那它呢」「这个怎么算」——本轮检索可能完全没命中，
 * 但**上一轮回答引用过的那几段**恰恰就是上下文。源侧把上一轮的引用当新命中注进来。
 *
 * <h2>⭐⭐ 只注入引用块，不注入正文</h2>
 * 源侧显式如此。理由：上一轮的**回答正文**是模型生成的，可能已经出错；
 * 而引用块是当时的原文证据。把回答正文当资料注入，等于让模型拿自己上一轮的
 * 幻觉当证据，越错越深。
 *
 * <h2>⚠️ 为什么不是「追加到 hits 末尾」</h2>
 * 引用块的分数不能编。它们**不是本轮检索出来的**，给任何分数都会污染排序。
 * ⇒ 固定 score = 0（与实体路同口径），靠重排把它们提上来；
 * 重排不认得它们时它们排在末尾，也符合「优先用本轮证据」的直觉。
 */
@Component
public class KbHistoryCitationStage {

    private static final Logger log = LoggerFactory.getLogger(KbHistoryCitationStage.class);

    /** 最多注入多少条（源侧同样有上限：注入过多会把本轮真实命中挤掉）。 */
    static final int MAX_INJECTED = 10;

    /**
     * 一条历史引用（从 {@code kb_citation} 读出来）。
     *
     * @param chunkId         块 ID
     * @param knowledgeId     所属文档
     * @param content         当时落库的片段（可能已被截断）
     * @param knowledgeTitle  文档标题
     */
    public record HistoryCitation(
        String chunkId, String knowledgeId, String content, String knowledgeTitle
    ) {
    }

    /**
     * 把历史引用并入本轮命中。
     *
     * <p><b>去重规则</b>：本轮已经命中的块不重复注入（否则同一块在上下文里出现两次，
     * 白占 token）。这与源侧 {@code filterSeenChunk} 的语义一致。
     *
     * @param hits      本轮命中（可空）
     * @param history   历史引用（可空）
     * @return 合并后的命中（新列表；history 为空时返回原列表）
     */
    public List<KbHybridSearcher.Hit> inject(
        List<KbHybridSearcher.Hit> hits, List<HistoryCitation> history
    ) {
        if (history == null || history.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        Map<String, KbHybridSearcher.Hit> merged = new LinkedHashMap<>();
        if (hits != null) {
            for (KbHybridSearcher.Hit h : hits) {
                if (h != null && h.chunkId() != null) {
                    merged.put(h.chunkId(), h);
                }
            }
        }
        int before = merged.size();
        int injected = 0;
        int skippedBlank = 0;
        for (HistoryCitation c : history) {
            if (injected >= MAX_INJECTED) {
                break;
            }
            if (c == null || c.chunkId() == null || c.chunkId().isBlank()) {
                skippedBlank++;
                continue;
            }
            if (merged.containsKey(c.chunkId())) {
                continue;   // 本轮已命中 ⇒ 用本轮的（分数/正文都更新）
            }
            String content = c.content() == null ? "" : c.content();
            if (content.isBlank()) {
                // 只有 id 没有正文的引用注进来等于给模型一个空段
                skippedBlank++;
                continue;
            }
            merged.put(c.chunkId(), new KbHybridSearcher.Hit(
                c.chunkId(), c.knowledgeId(), content,
                c.knowledgeTitle() == null ? "" : c.knowledgeTitle(),
                0.0, 0, 0, KbHybridSearcher.Hit.TYPE_TEXT));
            injected++;
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>(merged.values());
        log.info("[问答链] ⑦ 历史引用注入 注入={} 跳过(重复/空)={}/{} 合并后={}(原 {})",
            injected, skippedBlank, history.size(), out.size(), before);
        return out;
    }
}

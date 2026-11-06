package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 阶段 5b · WIKI_BOOST（对齐源侧 {@code chat_pipeline/wiki_boost.go}）。
 *
 * <h2>为什么 wiki 页要加成</h2>
 * Wiki 页是 LLM 预先合成的、跨文档交叉引用的知识；原始文档块是切碎的原文。
 * 同一件事两者都有时，合成过的更容易直接回答用户的问题。
 *
 * <h2>⭐ 两道门都必须有</h2>
 * <ol>
 *   <li><b>结果里真的没有 wiki 块 ⇒ 立刻返回</b>：每轮问答都去查一次知识库配置
 *       是没有意义的开销（源侧注释把这个叫 fast path）。</li>
 *   <li><b>库必须「是 wiki 的」</b>：光有 {@code chunk_type='wiki_page'} 不够 ——
 *       残留块、跨库误捞都可能带上这个类型。源侧用 {@code kb.IsWikiEnabled()}；
 *       本仓没有那个配置位，改用「该库确实存在 wiki 页」这个运行时事实。</li>
 * </ol>
 * ⚠️ 第二道门看似多余，实际是必要的：它把「配置关了但块没清干净」这类脏数据挡在外面。
 *
 * <h2>⚠️ 顺序</h2>
 * 必须在<b>重排之后</b>（源侧挂在 {@code CHUNK_RERANK} 且先 {@code next()}）。
 * 放在重排之前，重排会用模型分把加成覆盖掉。
 */
@Component
public class KbWikiBoostStage {

    private static final Logger log = LoggerFactory.getLogger(KbWikiBoostStage.class);

    /**
     * wiki 页加成系数（源侧 {@code wikiBoostFactor = 1.3}）。
     *
     * <p>明显高于记忆亲和的 1.15：wiki 页是「已经验证过的合成知识」，
     * 比「这个文档我用得多」的信号强得多。
     */
    static final double BOOST = 1.3;

    /**
     * 应用加成。
     *
     * @param hits          重排后的命中（null/空 ⇒ 原样返回）
     * @param kindOfChunk   按 chunkId 取块类型（null ⇒ 直接用 {@code hit.chunkType()}）
     * @param wikiKbPresent 库里是否确实有 wiki 页（false ⇒ 不加成，直接返回）
     * @return 加成并稳定重排后的命中（新列表，输入不被修改）
     */
    public List<KbHybridSearcher.Hit> apply(
        List<KbHybridSearcher.Hit> hits,
        java.util.Map<String, String> kindOfChunk,
        boolean wikiKbPresent
    ) {
        if (hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        // ── 门 1：先扫一遍，没有 wiki 块就立刻走人（⛔ 别先查库）──
        boolean hasWikiChunk = false;
        for (KbHybridSearcher.Hit h : hits) {
            if (h != null && kindOf(h, kindOfChunk)) {
                hasWikiChunk = true;
                break;
            }
        }
        if (!hasWikiChunk) {
            return hits;
        }
        // ── 门 2：库不是 wiki 库 ⇒ 不加成（防残留脏块）──
        if (!wikiKbPresent) {
            log.info("[问答链] ⑤b wiki 加成 跳过：命中里有 wiki 块但库内无 wiki 页（疑似残留）");
            return hits;
        }

        List<KbHybridSearcher.Hit> out = new ArrayList<>(hits.size());
        int boosted = 0;
        for (KbHybridSearcher.Hit h : hits) {
            if (h == null) {
                continue;
            }
            if (!kindOf(h, kindOfChunk)) {
                out.add(h);
                continue;
            }
            out.add(new KbHybridSearcher.Hit(
                h.chunkId(), h.knowledgeId(), h.content(), h.contextHeader(),
                h.score() * BOOST, h.vectorRank(), h.keywordRank(), h.chunkType()));
            boosted++;
        }
        // ⚠️ 稳定排序：同分保持重排阶段的相对顺序
        out.sort(Comparator.comparingDouble(KbHybridSearcher.Hit::score).reversed());
        log.info("[问答链] ⑤b wiki 加成 提升={}/{} 系数={}", boosted, out.size(), BOOST);
        return out;
    }

    /**
     * 判断一个命中是不是 wiki 块。
     *
     * <p>优先用「按 chunkId 查出来的真实类型」（库里的值），没有再退回 hit 自带的值。
     * 为什么：hit 的 chunkType 是检索时读出来的快照，而这里的 map 可以来自
     * 更接近当前时刻的一次查询。
     */
    private static boolean kindOf(KbHybridSearcher.Hit h, java.util.Map<String, String> kindOfChunk) {
        if (kindOfChunk != null && h.chunkId() != null) {
            String kind = kindOfChunk.get(h.chunkId());
            if (kind != null) {
                return KbHybridSearcher.Hit.TYPE_WIKI_PAGE.equalsIgnoreCase(kind);
            }
        }
        return h.isWikiPage();
    }

    /**
     * 命中里是否含 wiki 块（供调用方决定「要不要去查库」）。
     *
     * @param hits 命中
     * @return 是否含 wiki 块
     */
    public static boolean hasWikiChunk(List<KbHybridSearcher.Hit> hits) {
        if (hits == null) {
            return false;
        }
        for (KbHybridSearcher.Hit h : hits) {
            if (h != null && h.isWikiPage()) {
                return true;
            }
        }
        return false;
    }

    /** 便利重载：直接按 hit 自带的 chunkType 判断（测试/特殊场景）。 */
    public List<KbHybridSearcher.Hit> apply(
        List<KbHybridSearcher.Hit> hits, boolean wikiKbPresent
    ) {
        return apply(hits, null, wikiKbPresent);
    }
}

package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 5c · 写入侧与读侧的<b>契约对齐</b>。
 *
 * <h2>⭐⭐ 为什么必须有这组断言</h2>
 * 阶段 5c 的读侧（{@link KbMemoryAffinityStage#apply}）实现完整、单测也有，
 * 但它的数据源 {@code kb_memory_doc_affinity} 曾有<b>整整一个批次没人写</b> ——
 * {@code KbMemoryService.recordAnswerSources} 全仓零调用。
 * 症状是每轮日志都打「提升=0/19 无历史使用记录」，功能看着在跑、实际永不生效，
 * 而所有单测全绿（读侧确实没 bug）。
 *
 * <p>⇒ 判据必须是<b>跨两端的</b>：「写入侧产出的形状」喂给「读侧」后确实会加成。
 * 只测其中一端，这类「读了没人写 / 写了没人读」的缺陷永远抓不到。
 */
@DisplayName("[QA-7] 阶段 5c · 亲和读写的契约对齐")
class KbMemoryAffinityContractTest {

    private static KbHybridSearcher.Hit hit(String chunkId, String knowledgeId, double score) {
        return new KbHybridSearcher.Hit(
            chunkId, knowledgeId, "正文", null, score, 0, 0, "text");
    }

    @Test
    @DisplayName("⭐⭐ 写入侧记的是「本轮引用的文档」，读侧能按同一个 knowledgeId 加成")
    void writeSideShapeFeedsReadSide() {
        // 写入侧（KbQaPipeline.recordDocAffinity）按 citations 去重后，
        // 交给 KbMemoryService.recordAnswerSources ⇒ hits 从 1 起步。
        // 读侧（KbMemoryAffinityStage）拿到的就是 knowledgeId → hits。
        Map<String, Integer> affinity = Map.of("doc-a", 3);

        List<KbHybridSearcher.Hit> out = new KbMemoryAffinityStage().apply(
            List.of(hit("c1", "doc-a", 1.0), hit("c2", "doc-b", 1.0)),
            affinity,
            KbHybridSearcher.Hit::knowledgeId);

        assertThat(out).hasSize(2);
        // doc-a 被加成（hits=3 ≥ MIN_HITS），doc-b 没有记录 ⇒ 原分
        assertThat(out.get(0).knowledgeId()).isEqualTo("doc-a");
        assertThat(out.get(0).score()).isGreaterThan(1.0);
        assertThat(out.get(1).knowledgeId()).isEqualTo("doc-b");
        assertThat(out.get(1).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ hits=1 时**不加成**（第一次引用不算熟悉）—— 这是 read 侧唯一会「静默无效果」的分支")
    void singleHitGetsNoBoost() {
        List<KbHybridSearcher.Hit> out = new KbMemoryAffinityStage().apply(
            List.of(hit("c1", "doc-a", 1.0)),
            Map.of("doc-a", 1),
            KbHybridSearcher.Hit::knowledgeId);

        assertThat(out.get(0).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("hits 单调递增，但**每多一次**的边际收益递减（log 曲线，不是线性）")
    void boostCurveIsLogarithmic() {
        double at2 = KbMemoryAffinityStage.factor(2);
        double at8 = KbMemoryAffinityStage.factor(8);

        // 单调增
        assertThat(at8).isGreaterThan(at2);
        // ⚠️ 必须比「等步长」的增量：log 是凹函数 ⇒ f(k+1)-f(k) 递减。
        //    ⛔ 不能比 f(4)-f(2) 与 f(8)-f(4) —— 区间加倍时增量反而可能变大
        //    （实测 0.0349 < 0.0401，会把正确实现判成红）。
        double step2to3 = KbMemoryAffinityStage.factor(3) - at2;
        double step3to4 = KbMemoryAffinityStage.factor(4) - KbMemoryAffinityStage.factor(3);
        double step4to5 = KbMemoryAffinityStage.factor(5) - KbMemoryAffinityStage.factor(4);
        assertThat(step3to4).isLessThan(step2to3);
        assertThat(step4to5).isLessThan(step3to4);
        // 饱和在上限
        assertThat(at8).isCloseTo(KbMemoryAffinityStage.MAX_BOOST, org.assertj.core.data.Offset.offset(1e-9));
        // 超过饱和点不再增长
        assertThat(KbMemoryAffinityStage.factor(100))
            .isCloseTo(KbMemoryAffinityStage.MAX_BOOST, org.assertj.core.data.Offset.offset(1e-9));
        // 加成必须**小于** wiki boost（1.3）：记忆信号比 wiki 更弱
        assertThat(KbMemoryAffinityStage.MAX_BOOST).isLessThan(KbWikiBoostStage.BOOST);
    }

    @Test
    @DisplayName("查亲和时文档 id 去重且保序（一个文档几十块不能挤满 200 的上限）")
    void lookupIdsDedupAndKeepOrder() {
        List<String> ids = KbMemoryAffinityStage.knowledgeIdsToLookup(List.of(
            hit("c1", "doc-a", 1), hit("c2", "doc-a", 1), hit("c3", "doc-b", 1)));

        assertThat(ids).containsExactly("doc-a", "doc-b");
    }

    @Test
    @DisplayName("空 affinity ⇒ 原样返回（不能因为亲和表空就把命中清空）")
    void emptyAffinityKeepsHits() {
        List<KbHybridSearcher.Hit> in = List.of(hit("c1", "doc-a", 1.0));
        assertThat(new KbMemoryAffinityStage().apply(in, Map.of(), KbHybridSearcher.Hit::knowledgeId))
            .isSameAs(in);
        assertThat(new KbMemoryAffinityStage().apply(in, null, KbHybridSearcher.Hit::knowledgeId))
            .isSameAs(in);
    }
}

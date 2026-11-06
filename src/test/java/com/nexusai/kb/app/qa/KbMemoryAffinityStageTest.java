package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 阶段 5c · {@link KbMemoryAffinityStage} 测试。
 *
 * <p>守的是三个源侧常量背后的语义，而不是「代码能跑」：
 * <b>hits&lt;2 不加成</b>、<b>对数曲线在 8 处饱和</b>、<b>加成后必须稳定重排</b>。
 */
class KbMemoryAffinityStageTest {

    private final KbMemoryAffinityStage stage = new KbMemoryAffinityStage();

    private static KbHybridSearcher.Hit hit(String chunkId, String knowledgeId, double score) {
        return new KbHybridSearcher.Hit(chunkId, knowledgeId, "正文" + chunkId, "表头", score, 0, 0);
    }

    @Test
    @DisplayName("hits < 2 ⇒ 不加成（只出现一次不算熟悉）")
    void belowMinHitsNoBoost() {
        assertThat(KbMemoryAffinityStage.factor(0)).isEqualTo(1.0);
        assertThat(KbMemoryAffinityStage.factor(1)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("hit 次数按对数曲线增长，在 FULL_HITS=8 处饱和到 MAX_BOOST")
    void factorGrowsLogarithmicallyAndSaturates() {
        double at2 = KbMemoryAffinityStage.factor(2);
        double at8 = KbMemoryAffinityStage.factor(8);
        double at100 = KbMemoryAffinityStage.factor(100);

        assertThat(at2).isGreaterThan(1.0).isLessThan(at8);
        // log1p(8)/log1p(8) = 1 ⇒ 恰好是上限
        assertThat(at8).isCloseTo(KbMemoryAffinityStage.MAX_BOOST, within(1e-9));
        // 超过饱和点也不超过上限（截断必须生效）
        assertThat(at100).isCloseTo(KbMemoryAffinityStage.MAX_BOOST, within(1e-9));
    }

    @Test
    @DisplayName("⭐ 曲线是「缓慢累积」：第 10 次复用的增益远小于第 2 次")
    void curveIsSlow() {
        double gain2 = KbMemoryAffinityStage.factor(2) - 1.0;
        double gain10 = KbMemoryAffinityStage.factor(10) - 1.0;
        // 从 2→10（5 倍次数）增益增量不该超过 0.15 的一半
        assertThat(gain10 - gain2).isLessThan(0.15 / 2);
    }

    @Test
    @DisplayName("熟悉文档被加成分，并按分降序稳定重排")
    void boostsAndResorts() {
        List<KbHybridSearcher.Hit> hits = List.of(
            hit("c1", "k-fresh", 0.60),
            hit("c2", "k-familiar", 0.55));

        List<KbHybridSearcher.Hit> got = stage.apply(hits, Map.of("k-familiar", 8),
            KbHybridSearcher.Hit::knowledgeId);

        // 0.55 × 1.15 = 0.6325 > 0.60 ⇒ 熟悉的文档反超
        assertThat(got.get(0).chunkId()).isEqualTo("c2");
        assertThat(got.get(0).score()).isCloseTo(0.55 * 1.15, within(1e-9));
        assertThat(got.get(1).chunkId()).isEqualTo("c1");
        assertThat(got.get(1).score()).isEqualTo(0.60);
    }

    @Test
    @DisplayName("⛔ 不改输入列表（返回新列表）")
    void doesNotMutateInput() {
        KbHybridSearcher.Hit original = hit("c1", "k1", 0.5);
        List<KbHybridSearcher.Hit> input = List.of(original);

        stage.apply(input, Map.of("k1", 8), KbHybridSearcher.Hit::knowledgeId);

        assertThat(input.get(0).score()).isEqualTo(0.5);
        assertThat(input.get(0)).isSameAs(original);
    }

    @Test
    @DisplayName("没有亲和数据 ⇒ 原样返回（不改分不排序）")
    void noAffinityReturnsSame() {
        List<KbHybridSearcher.Hit> hits = List.of(hit("c1", "k1", 0.6), hit("c2", "k2", 0.5));
        assertThat(stage.apply(hits, Map.of(), KbHybridSearcher.Hit::knowledgeId))
            .isSameAs(hits);
        assertThat(stage.apply(hits, null, KbHybridSearcher.Hit::knowledgeId))
            .isSameAs(hits);
        assertThat(stage.apply(null, Map.of("k1", 8), KbHybridSearcher.Hit::knowledgeId))
            .isEmpty();
    }

    @Test
    @DisplayName("⭐ hits=1 在 apply 里也不加成（边界在调用路径上同样成立）")
    void applySkipsAtBoundary() {
        List<KbHybridSearcher.Hit> got = stage.apply(
            List.of(hit("c1", "k1", 0.5)),
            Map.of("k1", 1),
            KbHybridSearcher.Hit::knowledgeId);

        assertThat(got.get(0).score()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("⭐ hits=2 恰好开始加成（边界另一侧）")
    void applyBoostsAtMinHits() {
        List<KbHybridSearcher.Hit> got = stage.apply(
            List.of(hit("c1", "k1", 0.5)),
            Map.of("k1", 2),
            KbHybridSearcher.Hit::knowledgeId);

        assertThat(got.get(0).score()).isGreaterThan(0.5);
        assertThat(got.get(0).score()).isLessThanOrEqualTo(0.5 * KbMemoryAffinityStage.MAX_BOOST);
    }

    @Test
    @DisplayName("文档不在 affinity map 里 ⇒ 该块不加成")
    void unknownDocUntouched() {
        List<KbHybridSearcher.Hit> got = stage.apply(
            List.of(hit("c1", "k-unknown", 0.5)),
            Map.of("k-other", 100),
            KbHybridSearcher.Hit::knowledgeId);
        assertThat(got.get(0).score()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("⭐ 稳定排序：同分保持重排阶段的相对顺序（可复现）")
    void stableSort() {
        // 两块同分、同文档；只有一块所在的文档... 两块都同文档，都加成后仍同分
        List<KbHybridSearcher.Hit> got = stage.apply(
            List.of(hit("c-first", "k1", 0.5), hit("c-second", "k1", 0.5)),
            Map.of("k1", 8),
            KbHybridSearcher.Hit::knowledgeId);
        assertThat(got.get(0).chunkId()).isEqualTo("c-first");
        assertThat(got.get(1).chunkId()).isEqualTo("c-second");
    }

    // ── knowledgeIdsToLookup ─────────────────────────────────────

    @Test
    @DisplayName("查亲和的文档 id：去重 + 保序")
    void lookupDedupesKeepingOrder() {
        List<KbHybridSearcher.Hit> hits = List.of(
            hit("c1", "k-b", 1), hit("c2", "k-a", 1), hit("c3", "k-b", 1));
        assertThat(KbMemoryAffinityStage.knowledgeIdsToLookup(hits))
            .containsExactly("k-b", "k-a");
    }

    @Test
    @DisplayName("查亲和的文档 id 上限 200（防一个文档几十块挤满）")
    void lookupCapsAt200() {
        List<KbHybridSearcher.Hit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            hits.add(hit("c" + i, "k" + i, 1));
        }
        assertThat(KbMemoryAffinityStage.knowledgeIdsToLookup(hits)).hasSize(200);
    }

    @Test
    @DisplayName("空 knowledgeId 被跳过")
    void lookupSkipsBlank() {
        List<KbHybridSearcher.Hit> hits = List.of(
            hit("c1", null, 1), hit("c2", "  ", 1), hit("c3", "k1", 1));
        assertThat(KbMemoryAffinityStage.knowledgeIdsToLookup(hits)).containsExactly("k1");
    }
}

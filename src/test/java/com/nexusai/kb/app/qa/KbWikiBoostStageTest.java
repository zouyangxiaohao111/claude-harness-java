package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 阶段 5b · {@link KbWikiBoostStage} 测试。
 *
 * <p>守两条门：<b>没有 wiki 块 ⇒ 不加成</b>、<b>库内没有 wiki 页 ⇒ 不加成</b>
 * （后者挡「块没清干净」的残留），以及加成系数与稳定排序。
 */
class KbWikiBoostStageTest {

    private final KbWikiBoostStage stage = new KbWikiBoostStage();

    private static KbHybridSearcher.Hit hit(String chunkId, double score, String type) {
        return new KbHybridSearcher.Hit(chunkId, "k1", "正文", "表头", score, 0, 0, type);
    }

    @Test
    @DisplayName("⭐ 门1：结果里没有 wiki 块 ⇒ 原样返回（不改分不排序）")
    void noWikiChunkReturnsSame() {
        List<KbHybridSearcher.Hit> hits = List.of(
            hit("c1", 0.5, "text"), hit("c2", 0.4, "text"));

        assertThat(stage.apply(hits, true)).isSameAs(hits);
        assertThat(stage.apply(hits, false)).isSameAs(hits);
    }

    @Test
    @DisplayName("⭐ 门2：有 wiki 块但库内无 wiki 页 ⇒ 不加成（防残留脏块）")
    void wikiChunkButNoWikiKbNoBoost() {
        List<KbHybridSearcher.Hit> hits = List.of(
            hit("w1", 0.5, KbHybridSearcher.Hit.TYPE_WIKI_PAGE),
            hit("c2", 0.4, "text"));

        List<KbHybridSearcher.Hit> got = stage.apply(hits, false);

        assertThat(got.get(0).score()).isEqualTo(0.5);
        assertThat(got.get(1).score()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("两道门都过 ⇒ wiki 块 ×1.3 并按分降序重排")
    void boostsWikiChunks() {
        List<KbHybridSearcher.Hit> hits = List.of(
            hit("c1", 0.60, "text"),
            hit("w1", 0.50, KbHybridSearcher.Hit.TYPE_WIKI_PAGE));

        List<KbHybridSearcher.Hit> got = stage.apply(hits, true);

        // 0.50 × 1.3 = 0.65 > 0.60 ⇒ wiki 反超
        assertThat(got.get(0).chunkId()).isEqualTo("w1");
        assertThat(got.get(0).score()).isCloseTo(0.65, within(1e-9));
        assertThat(got.get(1).chunkId()).isEqualTo("c1");
        assertThat(got.get(1).score()).isEqualTo(0.60);
    }

    @Test
    @DisplayName("只有 wiki 块被加成，普通块分数不变")
    void onlyWikiBoosted() {
        List<KbHybridSearcher.Hit> got = stage.apply(List.of(
            hit("w1", 0.4, KbHybridSearcher.Hit.TYPE_WIKI_PAGE),
            hit("c1", 0.4, "text")), true);

        double wikiScore = got.stream().filter(h -> h.chunkId().equals("w1"))
            .findFirst().orElseThrow().score();
        double textScore = got.stream().filter(h -> h.chunkId().equals("c1"))
            .findFirst().orElseThrow().score();
        assertThat(wikiScore).isCloseTo(0.4 * 1.3, within(1e-9));
        assertThat(textScore).isEqualTo(0.4);
    }

    @Test
    @DisplayName("⛔ 不改输入列表（返回新列表）")
    void doesNotMutateInput() {
        KbHybridSearcher.Hit original = hit("w1", 0.5, KbHybridSearcher.Hit.TYPE_WIKI_PAGE);
        List<KbHybridSearcher.Hit> input = List.of(original);

        stage.apply(input, true);

        assertThat(input.get(0).score()).isEqualTo(0.5);
        assertThat(input.get(0)).isSameAs(original);
    }

    @Test
    @DisplayName("⭐ 稳定排序：同分保持原相对顺序")
    void stableSort() {
        List<KbHybridSearcher.Hit> got = stage.apply(List.of(
            hit("w1", 0.5, KbHybridSearcher.Hit.TYPE_WIKI_PAGE),
            hit("w2", 0.5, KbHybridSearcher.Hit.TYPE_WIKI_PAGE)), true);

        assertThat(got.get(0).chunkId()).isEqualTo("w1");
        assertThat(got.get(1).chunkId()).isEqualTo("w2");
    }

    @Test
    @DisplayName("chunkType 大小写不敏感（库里可能有 WIKI_PAGE）")
    void caseInsensitiveType() {
        List<KbHybridSearcher.Hit> hits = List.of(hit("w1", 0.5, "WIKI_PAGE"));
        assertThat(stage.apply(hits, true).get(0).score())
            .isCloseTo(0.5 * 1.3, within(1e-9));
    }

    @Test
    @DisplayName("kindOfChunk map（库里的真实类型）优先于 hit 自带值")
    void mapOverridesHitType() {
        // hit 自称 text，但库里该 chunkId 实际是 wiki_page
        List<KbHybridSearcher.Hit> hits = List.of(hit("w1", 0.5, "text"));
        List<KbHybridSearcher.Hit> got = stage.apply(
            hits, Map.of("w1", KbHybridSearcher.Hit.TYPE_WIKI_PAGE), true);

        assertThat(got.get(0).score()).isCloseTo(0.5 * 1.3, within(1e-9));
    }

    @Test
    @DisplayName("hasWikiChunk：快速判断（调用方据此决定是否查库）")
    void hasWikiChunkHelper() {
        assertThat(KbWikiBoostStage.hasWikiChunk(null)).isFalse();
        assertThat(KbWikiBoostStage.hasWikiChunk(List.of())).isFalse();
        assertThat(KbWikiBoostStage.hasWikiChunk(List.of(hit("c1", 1, "text")))).isFalse();
        assertThat(KbWikiBoostStage.hasWikiChunk(List.of(
            hit("c1", 1, "text"), hit("w1", 1, "wiki_page")))).isTrue();
    }

    @Test
    @DisplayName("空输入 ⇒ 空列表，不抛")
    void emptyInputs() {
        assertThat(stage.apply(null, true)).isEmpty();
        assertThat(stage.apply(List.of(), true)).isEmpty();
    }

    @Test
    @DisplayName("兼容构造器把 chunkType 默认成 text（旧调用点零改动）")
    void legacyConstructorDefaultsToText() {
        KbHybridSearcher.Hit legacy =
            new KbHybridSearcher.Hit("c1", "k1", "正文", "表头", 0.5, 0, 0);
        assertThat(legacy.chunkType()).isEqualTo(KbHybridSearcher.Hit.TYPE_TEXT);
        assertThat(legacy.isWikiPage()).isFalse();
    }
}

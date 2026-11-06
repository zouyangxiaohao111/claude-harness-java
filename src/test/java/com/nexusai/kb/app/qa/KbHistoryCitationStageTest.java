package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 7 补 · {@link KbHistoryCitationStage} 测试。
 *
 * <p>守四件事：<b>不重复注入</b>、<b>空正文不注入</b>、<b>分数必须是 0</b>、<b>上限</b>。
 */
class KbHistoryCitationStageTest {

    private final KbHistoryCitationStage stage = new KbHistoryCitationStage();

    private static KbHybridSearcher.Hit hit(String chunkId, double score) {
        return new KbHybridSearcher.Hit(chunkId, "k1", "本轮正文", "表头", score, 0, 0);
    }

    private static KbHistoryCitationStage.HistoryCitation ref(String chunkId, String content) {
        return new KbHistoryCitationStage.HistoryCitation(chunkId, "k1", content, "标题");
    }

    @Test
    @DisplayName("⭐ 本轮已命中的块不重复注入（用本轮的分数与正文）")
    void doesNotDuplicateExistingHits() {
        List<KbHybridSearcher.Hit> got = stage.inject(
            List.of(hit("c1", 0.8)),
            List.of(ref("c1", "历史片段")));

        assertThat(got).hasSize(1);
        assertThat(got.get(0).score()).isEqualTo(0.8);
        assertThat(got.get(0).content()).isEqualTo("本轮正文");
    }

    @Test
    @DisplayName("⭐ 注入的引用分数必须是 0（它们不是本轮检索出来的）")
    void injectedScoreIsZero() {
        List<KbHybridSearcher.Hit> got = stage.inject(
            List.of(hit("c1", 0.8)),
            List.of(ref("c2", "历史片段")));

        KbHybridSearcher.Hit injected = got.stream()
            .filter(h -> h.chunkId().equals("c2")).findFirst().orElseThrow();
        assertThat(injected.score()).isZero();
        assertThat(injected.vectorRank()).isZero();
        assertThat(injected.keywordRank()).isZero();
        assertThat(injected.content()).isEqualTo("历史片段");
    }

    @Test
    @DisplayName("空正文的引用不注入（只有 id 等于给模型一个空段）")
    void skipsBlankContent() {
        List<KbHybridSearcher.Hit> got = stage.inject(
            List.of(hit("c1", 0.8)),
            List.of(ref("c2", ""), ref("c3", "   "), ref(null, "有正文但无 id")));
        assertThat(got).hasSize(1);
    }

    @Test
    @DisplayName("无历史引用 ⇒ 原样返回（同一列表实例）")
    void noHistoryReturnsSame() {
        List<KbHybridSearcher.Hit> hits = List.of(hit("c1", 0.8));
        assertThat(stage.inject(hits, List.of())).isSameAs(hits);
        assertThat(stage.inject(hits, null)).isSameAs(hits);
        assertThat(stage.inject(null, List.of())).isEmpty();
    }

    @Test
    @DisplayName("注入上限 MAX_INJECTED")
    void capsAtMax() {
        List<KbHistoryCitationStage.HistoryCitation> refs = new java.util.ArrayList<>();
        for (int i = 0; i < KbHistoryCitationStage.MAX_INJECTED + 20; i++) {
            refs.add(ref("cx" + i, "片段" + i));
        }
        List<KbHybridSearcher.Hit> got = stage.inject(List.of(), refs);
        assertThat(got).hasSize(KbHistoryCitationStage.MAX_INJECTED);
    }

    @Test
    @DisplayName("⭐ 保序：本轮命中在前，历史引用按原序追加")
    void keepsOrder() {
        List<KbHybridSearcher.Hit> got = stage.inject(
            List.of(hit("c1", 0.9), hit("c2", 0.7)),
            List.of(ref("h1", "片段1"), ref("h2", "片段2")));

        assertThat(got).extracting(KbHybridSearcher.Hit::chunkId)
            .containsExactly("c1", "c2", "h1", "h2");
    }

    @Test
    @DisplayName("历史引用之间的重复也去掉（保留先出现的）")
    void dedupesWithinHistory() {
        List<KbHybridSearcher.Hit> got = stage.inject(
            List.of(),
            List.of(ref("h1", "片段1"), ref("h1", "片段1-重复")));
        assertThat(got).hasSize(1);
        assertThat(got.get(0).content()).isEqualTo("片段1");
    }
}

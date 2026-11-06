package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 8 · {@link KbFilterTopKStage} 测试。
 *
 * <p>守两件事：<b>排序完全确定</b>（同分时靠 id 决出，不依赖输入顺序）与
 * <b>score == 0 的命中不被截掉</b>（实体路 / 历史引用的结构证据）。
 */
class KbFilterTopKStageTest {

    private final KbFilterTopKStage stage = new KbFilterTopKStage();

    private static KbMergeStage.MergedChunk chunk(
        String id, String kid, String type, int index, double score) {
        return new KbMergeStage.MergedChunk(
            id, kid, "kb1", "正文", "表头", type, index, score, List.of());
    }

    @Test
    @DisplayName("按分数降序截断")
    void cutsByScore() {
        List<KbMergeStage.MergedChunk> got = stage.filter(List.of(
            chunk("c1", "k1", "text", 0, 0.5),
            chunk("c2", "k1", "text", 1, 0.9),
            chunk("c3", "k1", "text", 2, 0.7)), 2);

        assertThat(got).extracting(KbMergeStage.MergedChunk::chunkId)
            .containsExactly("c2", "c3");
    }

    @Test
    @DisplayName("⭐ score == 0 的片段全部保留（实体路 / 历史引用的结构证据）")
    void keepsZeroScoreChunks() {
        List<KbMergeStage.MergedChunk> got = stage.filter(List.of(
            chunk("c1", "k1", "text", 0, 0.9),
            chunk("g1", "k2", "text", 0, 0.0),
            chunk("g2", "k3", "text", 0, 0.0)), 1);

        // 前 1 条按分取 c1，两个 score=0 的追加在后
        assertThat(got).extracting(KbMergeStage.MergedChunk::chunkId)
            .containsExactly("c1", "g1", "g2");
    }

    @Test
    @DisplayName("⭐ 全是 score=0 时：不被截断（topK 只作用于计分条目）")
    void allZeroScoresSurvive() {
        List<KbMergeStage.MergedChunk> input = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            input.add(chunk("g" + i, "k" + i, "text", 0, 0.0));
        }
        assertThat(stage.filter(input, 3)).hasSize(10);
    }

    @Test
    @DisplayName("⭐ 排序完全确定：同分时按 knowledgeId → chunkType → chunkIndex → chunkId")
    void deterministicOrder() {
        // 全部同分，靠后续键决出
        List<KbMergeStage.MergedChunk> got = stage.filter(List.of(
            chunk("c3", "k2", "text", 0, 0.5),
            chunk("c1", "k1", "text", 1, 0.5),
            chunk("c2", "k1", "text", 0, 0.5)), 10);

        assertThat(got).extracting(KbMergeStage.MergedChunk::chunkId)
            .containsExactly("c2", "c1", "c3");   // k1 的两个（index 0 先）→ k2
    }

    @Test
    @DisplayName("⛔ 输入顺序不影响输出（打乱后结果相同）")
    void orderIndependentOfInput() {
        List<KbMergeStage.MergedChunk> a = List.of(
            chunk("x", "k2", "text", 0, 0.5),
            chunk("y", "k1", "text", 0, 0.5));
        List<KbMergeStage.MergedChunk> b = List.of(
            chunk("y", "k1", "text", 0, 0.5),
            chunk("x", "k2", "text", 0, 0.5));

        assertThat(stage.filter(a, 10)).extracting(KbMergeStage.MergedChunk::chunkId)
            .containsExactlyElementsOf(
                stage.filter(b, 10).stream().map(KbMergeStage.MergedChunk::chunkId).toList());
    }

    @Test
    @DisplayName("topK <= 0 ⇒ 只排序不截断")
    void nonPositiveTopKDoesNotCut() {
        List<KbMergeStage.MergedChunk> got = stage.filter(List.of(
            chunk("c1", "k1", "text", 0, 0.5),
            chunk("c2", "k1", "text", 1, 0.9)), 0);
        assertThat(got).hasSize(2);
        assertThat(got.get(0).chunkId()).isEqualTo("c2");
    }

    @Test
    @DisplayName("⛔ 不改输入列表（返回新列表）")
    void doesNotMutateInput() {
        List<KbMergeStage.MergedChunk> input = new ArrayList<>(List.of(
            chunk("c1", "k1", "text", 0, 0.5),
            chunk("c2", "k1", "text", 1, 0.9)));
        stage.filter(input, 10);
        // 输入顺序未被就地排序
        assertThat(input.get(0).chunkId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("空输入 ⇒ 空列表，不抛")
    void emptyInputs() {
        assertThat(stage.filter(null, 5)).isEmpty();
        assertThat(stage.filter(List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("cutKeepingZeroScores 可单独调用（纯函数）")
    void cutIsPureFunction() {
        List<KbMergeStage.MergedChunk> sorted = List.of(
            chunk("a", "k", "text", 0, 0.9),
            chunk("b", "k", "text", 1, 0.8),
            chunk("z", "k", "text", 2, 0.0));
        assertThat(KbFilterTopKStage.cutKeepingZeroScores(sorted, 1))
            .extracting(KbMergeStage.MergedChunk::chunkId)
            .containsExactly("a", "z");
    }
}

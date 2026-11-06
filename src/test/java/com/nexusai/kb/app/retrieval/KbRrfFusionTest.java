package com.nexusai.kb.app.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RRF 融合 · 对齐 WeKnora {@code knowledgebase_search_fusion.go:170-220}。
 *
 * <p><b>WHY 这些断言</b>：RRF 是「向量分与 BM25 分不可比」的解法。
 * 写错会有两种静默事故：①量纲混算（BM25 压死向量）②同块重复计分（fan-out 时虚高）。
 * 两者都不会报错，只会让排序悄悄变差。
 */
@DisplayName("[B3] RRF 融合")
class KbRrfFusionTest {

    @Nested
    @DisplayName("基本公式")
    class Basics {

        @Test
        @DisplayName("双路都排第 1 的块得分为 1.0（归一化上界）")
        void bothFirstGetsOne() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("a", "b"), List.of("a", "c"), 60, 0.7, 0.3);
            assertThat(out.get(0).id()).isEqualTo("a");
            assertThat(out.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(out.get(0).vectorRank()).isEqualTo(1);
            assertThat(out.get(0).keywordRank()).isEqualTo(1);
        }

        @Test
        @DisplayName("只命中向量路的块：分数 = wV/(k+rank) / maxRRF")
        void vectorOnlyScore() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("v1"), List.of("k1"), 60, 0.7, 0.3);
            KbRrfFusion.Fused v = out.stream().filter(f -> f.id().equals("v1")).findFirst().orElseThrow();
            double maxRrf = (0.7 + 0.3) / 61.0;
            assertThat(v.score()).isCloseTo((0.7 / 61.0) / maxRrf,
                org.assertj.core.data.Offset.offset(1e-9));
            assertThat(v.keywordRank()).isZero();
        }

        @Test
        @DisplayName("双路都命中 > 只命中一路（同一名次下）")
        void bothRoutesBeatSingleRoute() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("both", "vOnly"),
                List.of("both", "kOnly"),
                60, 0.7, 0.3);
            assertThat(out.get(0).id()).isEqualTo("both");
        }

        @Test
        @DisplayName("两路都空 → 空列表")
        void bothEmpty() {
            assertThat(KbRrfFusion.fuse(List.of(), List.of(), 60, 0.7, 0.3)).isEmpty();
        }
    }

    @Nested
    @DisplayName("参数回落（源侧 retrieval_config.go:92-115）")
    class Defaults {

        @Test
        @DisplayName("k <= 0 → 回落 60")
        void kFallsBack() {
            List<KbRrfFusion.Fused> withZero = KbRrfFusion.fuse(List.of("a"), List.of(), 0, 0.7, 0.3);
            List<KbRrfFusion.Fused> with60 = KbRrfFusion.fuse(List.of("a"), List.of(), 60, 0.7, 0.3);
            assertThat(withZero.get(0).score()).isEqualTo(with60.get(0).score());
        }

        @Test
        @DisplayName("权重都为 0 → 回落 0.7 / 0.3")
        void weightsFallBack() {
            List<KbRrfFusion.Fused> zero = KbRrfFusion.fuse(List.of("a"), List.of("b"), 60, 0, 0);
            List<KbRrfFusion.Fused> def = KbRrfFusion.fuse(List.of("a"), List.of("b"), 60, 0.7, 0.3);
            assertThat(zero).hasSameSizeAs(def);
            assertThat(zero.get(0).id()).isEqualTo(def.get(0).id());
        }

        @Test
        @DisplayName("向量权重更高 ⇒ 向量路命中优先于关键词路（同排名）")
        void vectorWeightDominates() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("v"), List.of("k"), 60, 0.7, 0.3);
            assertThat(out.get(0).id()).as("0.7 > 0.3 ⇒ 向量路第 1 名在关键词路第 1 名之前")
                .isEqualTo("v");
        }
    }

    @Nested
    @DisplayName("⭐ 重复只取最好名次（源侧 bestRanks）")
    class DedupRanks {

        @Test
        @DisplayName("同一列表内重复出现只计最好名次")
        void bestRankOnly() {
            // "a" 出现两次（第 1、第 3）⇒ 只按第 1 名算
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("a", "b", "a"), List.of(), 60, 0.7, 0.3);
            KbRrfFusion.Fused a = out.stream().filter(f -> f.id().equals("a")).findFirst().orElseThrow();
            assertThat(a.vectorRank()).as("重复出现取最好的（最小）名次").isEqualTo(1);
        }

        @Test
        @DisplayName("结果中同一 id 只出现一次")
        void noDuplicateOutput() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("a", "a", "a"), List.of("a"), 60, 0.7, 0.3);
            assertThat(out).hasSize(1);
        }

        @Test
        @DisplayName("空 id / null id 被忽略（不污染排名）")
        void blankIdsSkipped() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                java.util.Arrays.asList("", null, "a"), List.of(), 60, 0.7, 0.3);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).id()).isEqualTo("a");
        }
    }

    @Nested
    @DisplayName("确定性（可回归）")
    class Determinism {

        @Test
        @DisplayName("同分按 id 升序 —— 同输入同输出")
        void tiesSortedById() {
            List<KbRrfFusion.Fused> a = KbRrfFusion.fuse(
                List.of("z", "y", "x"), List.of("x", "y", "z"), 60, 0.7, 0.3);
            List<KbRrfFusion.Fused> b = KbRrfFusion.fuse(
                List.of("z", "y", "x"), List.of("x", "y", "z"), 60, 0.7, 0.3);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("分数落在 [0,1]")
        void scoresNormalized() {
            List<KbRrfFusion.Fused> out = KbRrfFusion.fuse(
                List.of("a", "b", "c"), List.of("c", "b", "a"), 60, 0.7, 0.3);
            for (KbRrfFusion.Fused f : out) {
                assertThat(f.score()).isBetween(0.0, 1.0);
            }
        }
    }
}

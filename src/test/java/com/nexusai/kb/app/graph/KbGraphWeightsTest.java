package com.nexusai.kb.app.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbGraphWeights} 测试 · 批次 W7。
 *
 * <p><b>⭐ 为什么这些算术要逐条测</b>：权重算错时，图上只是「线的粗细不对」，
 * 没有任何报错。而其中四个归一（PMI 截零 / log2 / 各自归一 / 1+9x 缩放）
 * 任意一个漏掉，都只体现为「看起来差不多」。
 */
class KbGraphWeightsTest {

    private static KbGraphWeights.EntityInput e(String name, int chunks) {
        return new KbGraphWeights.EntityInput(name, chunksOf(name, chunks));
    }

    private static List<String> chunksOf(String prefix, int n) {
        List<String> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(prefix + "-" + i);
        }
        return out;
    }

    private static KbGraphWeights.RelationInput r(String s, String t, int strength, int chunks) {
        return new KbGraphWeights.RelationInput(s, t, strength, chunksOf(s + ">" + t, chunks));
    }

    @Nested
    @DisplayName("输出区间")
    class Range {

        @Test
        @DisplayName("⭐⭐ 权重恒在 [1,10]（下限 1 不能是 0，否则边会彻底消失）")
        void weightsBounded() {
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 5), e("乙", 3), e("丙", 1)),
                List.of(r("甲", "乙", 10, 3), r("乙", "丙", 5, 1), r("丙", "甲", 7, 1)));
            for (KbGraphWeights.Result res : out) {
                assertThat(res.weight()).isBetween(1.0, 10.0);
            }
        }

        @Test
        @DisplayName("⭐⭐ 权重 = 1 + 9×(0.6·PMI归一 + 0.4·强度归一)：逐值核对源侧公式")
        void exactFormula() {
            // 手算（已用 Python 复现）：
            //   实体出现总数 = 10+1+1 = 12；关系出现总数 = 1+1 = 2
            //   P(甲)=10/12, P(乙)=1/12, P(甲乙)=1/2 ⇒ PMI = log2((1/2)/((10/12)(1/12))) = 2.848
            //   两条关系 PMI 相同 ⇒ maxPMI = 2.848 ⇒ 归一后 1.0
            //   强度都是 5 ⇒ 归一后 1.0
            //   combined = 1.0*0.6 + 1.0*0.4 = 1.0 ⇒ weight = 1 + 9*1.0 = 10.0
            // 若漏掉「1 +」变成 9*1.0 = 9.0 ⇒ 本断言回红
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 10), e("乙", 1), e("丙", 1)),
                List.of(r("甲", "乙", 5, 1), r("甲", "丙", 5, 1)));
            assertThat(out.get(0).weight())
                .as("必须含 +1 的基线（否则最弱边会趋近 0，渲染时直接消失）")
                .isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("强度越高权重越大（PMI 相同时）")
        void strongerIsHeavier() {
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 2), e("乙", 2), e("丙", 2)),
                List.of(r("甲", "乙", 10, 1), r("甲", "丙", 5, 1)));
            assertThat(out.get(0).weight()).isGreaterThan(out.get(1).weight());
        }

        @Test
        @DisplayName("空输入 ⇒ 空结果")
        void empty() {
            assertThat(KbGraphWeights.compute(List.of(), List.of())).isEmpty();
            assertThat(KbGraphWeights.compute(null, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("PMI 归一")
    class Pmi {

        @Test
        @DisplayName("⭐⭐ 负 PMI 被截为 0（互斥信号不该变成负权重）")
        void negativePmiClamped() {
            // 甲很常见(10)、乙很罕见(1)、关系只出现 1 次 ⇒ PMI 为负
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 10), e("乙", 1)),
                List.of(r("甲", "乙", 5, 1), r("甲", "丙", 5, 1)));
            for (KbGraphWeights.Result res : out) {
                assertThat(res.weight()).isGreaterThanOrEqualTo(1.0);
            }
        }

        @Test
        @DisplayName("⭐⭐ PMI 方向：共现远高于偶然预期 ⇒ PMI 高 ⇒ 权重更大")
        void highCooccurrenceRatiosHeavier() {
            // 甲、乙 各出现 10 块且**块块共现** ⇒ 概率比远高于独立假设 ⇒ PMI 高
            // 甲、丙 各出现 10 块但**只共现 1 块** ⇒ 概率比低于独立假设 ⇒ PMI 负数 ⇒ 截零
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 10), e("乙", 10), e("丙", 10)),
                List.of(r("甲", "乙", 8, 10), r("甲", "丙", 8, 1)));
            assertThat(out.get(0).weight())
                .as("高共现对（甲-乙）应比低共现对（甲-丙）权重更大")
                .isGreaterThan(out.get(1).weight());
        }

        @Test
        @DisplayName("数据不足（无块/无关系）⇒ 只按强度算，不抛")
        void insufficientDataFallback() {
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(new KbGraphWeights.EntityInput("甲", List.of()),
                        new KbGraphWeights.EntityInput("乙", List.of())),
                List.of(new KbGraphWeights.RelationInput("甲", "乙", 8, List.of())));
            assertThat(out).hasSize(1);
            assertThat(out.get(0).weight()).isBetween(1.0, 10.0);
        }
    }

    @Nested
    @DisplayName("度数")
    class Degrees {

        @Test
        @DisplayName("度数 = 两端入度+出度之和")
        void combinedDegree() {
            // 甲出现 2 次（出2），乙出现 1 次（入1），丙出现 1 次（入1）
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 3), e("乙", 1), e("丙", 1)),
                List.of(r("甲", "乙", 8, 1), r("甲", "丙", 8, 1)));
            // 甲度数 = 出2 + 入0 = 2；乙 = 入1 = 1 ⇒ 第一条 combined = 3
            assertThat(out.get(0).combinedDegree()).isEqualTo(3);
        }

        @Test
        @DisplayName("⭐ 同一对实体的多个关系都计入度数")
        void repeatedEdgesCount() {
            List<KbGraphWeights.Result> out = KbGraphWeights.compute(
                List.of(e("甲", 2), e("乙", 2)),
                List.of(r("甲", "乙", 8, 1), r("乙", "甲", 8, 1)));
            // 甲：出1(甲乙) + 入1(乙甲) = 2；乙：出1 + 入1 = 2 ⇒ combined = 4
            assertThat(out.get(0).combinedDegree()).isEqualTo(4);
            assertThat(out.get(1).combinedDegree()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("工具函数")
    class Helpers {

        @Test
        @DisplayName("log2 正确（以 2 为底，不是自然对数）")
        void log2IsBase2() {
            assertThat(KbGraphWeights.log2(2.0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(KbGraphWeights.log2(8.0)).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(KbGraphWeights.log2(1.0)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("权重系数与源侧一致（0.6 / 0.4 / 9.0）")
        void constants() {
            assertThat(KbGraphWeights.PMI_WEIGHT).isEqualTo(0.6);
            assertThat(KbGraphWeights.STRENGTH_WEIGHT).isEqualTo(0.4);
            assertThat(KbGraphWeights.WEIGHT_SCALE).isEqualTo(9.0);
        }
    }
}

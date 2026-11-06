package com.nexusai.kb.app.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量编解码 · 字节序与边界。
 *
 * <p><b>WHY</b>：字节序写反<b>不会报错</b>，只会静默给出错误数值。
 * 同进程内自洽（写读都错）时所有测试都能通过 —— 所以必须用**已知字节**做断言，
 * 而不是「编码再解码相等」。
 */
@DisplayName("[B3] 向量编解码")
class KbVectorCodecTest {

    @Nested
    @DisplayName("字节序（必须小端）")
    class Endianness {

        @Test
        @DisplayName("⭐ 1.0f 在小端下是 00 00 80 3F（大端会是 3F 80 00 00）")
        void littleEndianLayout() {
            byte[] blob = KbVectorCodec.encode(new float[] {1.0f});
            assertThat(blob).containsExactly(0x00, 0x00, (byte) 0x80, 0x3F);
        }

        @Test
        @DisplayName("编码再解码能还原（自洽，但这不能证明字节序正确）")
        void roundTrip() {
            float[] src = {0.5f, -1.25f, 3.75f, 0.0f};
            assertThat(KbVectorCodec.decode(KbVectorCodec.encode(src))).containsExactly(src);
        }
    }

    @Nested
    @DisplayName("边界")
    class Edges {

        @Test
        @DisplayName("null / 空 → 空字节 / 空数组")
        void nullAndEmpty() {
            assertThat(KbVectorCodec.encode(null)).isEmpty();
            assertThat(KbVectorCodec.encode(new float[0])).isEmpty();
            assertThat(KbVectorCodec.decode(null)).isEmpty();
            assertThat(KbVectorCodec.decode(new byte[0])).isEmpty();
        }

        @Test
        @DisplayName("⭐ 字节数不是 4 的倍数 ⇒ 抛异常（数据损坏不能静默吞）")
        void corruptedBlobThrows() {
            assertThatThrownBy(() -> KbVectorCodec.decode(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 4 的倍数");
        }

        @Test
        @DisplayName("维度 = 字节数 / 4")
        void dimensions() {
            assertThat(KbVectorCodec.dimensionsOf(KbVectorCodec.encode(new float[1536]))).isEqualTo(1536);
            assertThat(KbVectorCodec.dimensionsOf(null)).isZero();
        }
    }

    @Nested
    @DisplayName("余弦相似度")
    class Cosine {

        @Test
        @DisplayName("相同向量 ⇒ 1.0")
        void identical() {
            float[] v = {0.3f, 0.4f, 0.5f};
            assertThat(KbVectorCodec.cosine(v, v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("正交 ⇒ 0.0")
        void orthogonal() {
            assertThat(KbVectorCodec.cosine(new float[] {1, 0}, new float[] {0, 1}))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("反向 ⇒ -1.0")
        void opposite() {
            assertThat(KbVectorCodec.cosine(new float[] {1, 0}, new float[] {-1, 0}))
                .isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("⭐ 维度不一致 ⇒ 0（不抛异常：混入不同模型的向量是可预期的坏数据）")
        void dimensionMismatchReturnsZero() {
            assertThat(KbVectorCodec.cosine(new float[] {1, 2}, new float[] {1, 2, 3})).isZero();
        }

        @Test
        @DisplayName("⭐ 零向量 ⇒ 0（除零保护）")
        void zeroVectorReturnsZero() {
            assertThat(KbVectorCodec.cosine(new float[] {0, 0}, new float[] {1, 2})).isZero();
        }

        @Test
        @DisplayName("null / 空 ⇒ 0")
        void nullReturnsZero() {
            assertThat(KbVectorCodec.cosine(null, new float[] {1})).isZero();
            assertThat(KbVectorCodec.cosine(new float[0], new float[0])).isZero();
        }
    }
}

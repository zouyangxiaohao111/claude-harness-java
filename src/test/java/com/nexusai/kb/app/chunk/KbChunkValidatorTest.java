package com.nexusai.kb.app.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块校验器 · 五条判据逐条钉死 · 批次 B2（对齐源侧 {@code validator.go:19-75}）。
 *
 * <p><b>WHY</b>：这是「三级回退」的唯一开关。判据写错会有两种静默事故：
 * 过严 ⇒ 好结果被否，白白回退到更差的策略；过松 ⇒ 坏结果被放行，
 * 用户拿到一堆碎片或超长块而系统自认成功。
 * 所以每条判据都要有一条**刚好越过/刚好不越过边界**的用例。
 */
@DisplayName("[B2] 分块校验器（五条判据）")
class KbChunkValidatorTest {

    /** 构造一个指定内容长度的块。 */
    private static KbChunk chunk(String content, int seq) {
        return new KbChunk(content, "", seq, 0, content.codePointCount(0, content.length()));
    }

    /** 构造 n 个长度为 len 的块。 */
    private static List<KbChunk> chunks(int n, int len) {
        List<KbChunk> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(chunk("甲".repeat(len), i));
        }
        return out;
    }

    @Nested
    @DisplayName("判据 1：一个块都没有")
    class NoChunks {

        @Test
        @DisplayName("空列表 → no chunks produced")
        void emptyList() {
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(List.of(), 10_000, 512);
            assertThat(r.ok()).isFalse();
            assertThat(r.reason()).isEqualTo("no chunks produced");
        }

        @Test
        @DisplayName("null → 同样按空处理（不抛 NPE）")
        void nullList() {
            assertThat(KbChunkValidator.validate(null, 10_000, 512).reason())
                .isEqualTo("no chunks produced");
        }
    }

    @Nested
    @DisplayName("判据 2：大文档只产出一块")
    class SingleChunkForLargeDocument {

        @Test
        @DisplayName("1 块 + 总字符 > 2*chunkSize → single chunk for large document")
        void largeDocumentOneChunk() {
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(chunks(1, 1025), 1025, 512);
            assertThat(r.ok()).isFalse();
            assertThat(r.reason()).isEqualTo("single chunk for large document");
        }

        @Test
        @DisplayName("1 块 + 总字符刚好等于 2*chunkSize → 放行（判据是严格大于）")
        void exactlyTwiceSizePasses() {
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(chunks(1, 1024), 1024, 512);
            assertThat(r.ok())
                .as("totalChars == 2*chunkSize 不触发（源侧是 > 不是 >=）")
                .isTrue();
        }

        @Test
        @DisplayName("小文档 1 块 → 放行（短文档本来就一块）")
        void smallDocumentOneChunkPasses() {
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(chunks(1, 120), 120, 512);
            assertThat(r.ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("判据 3：碎片过多（末块豁免）")
    class TinyChunks {

        @Test
        @DisplayName("非末块 <50 字符的数量 > size/4 且 >2 → too many tiny chunks")
        void tooManyTinyChunks() {
            // 20 块，前 19 块都只有 10 字符，末块很长
            List<KbChunk> list = new ArrayList<>();
            for (int i = 0; i < 19; i++) {
                list.add(chunk("甲".repeat(10), i));
            }
            list.add(chunk("乙".repeat(500), 19));
            // tinyCount = 19 > 20/4=5 且 > 2 ⇒ 触发
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(list, 19 * 10 + 500, 512);
            assertThat(r.ok()).isFalse();
            assertThat(r.reason()).isEqualTo("too many tiny chunks");
        }

        @Test
        @DisplayName("⭐ 末块很小但其余都正常 → 放行（尾部残余是正常现象）")
        void tinyLastChunkIsExempt() {
            List<KbChunk> list = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                list.add(chunk("甲".repeat(400), i));
            }
            list.add(chunk("尾", 4)); // 末块只有 1 字符
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(list, 1601, 512);
            assertThat(r.ok())
                .as("末块不参与碎片统计，否则每篇文档都因尾部残余被否")
                .isTrue();
        }

        @Test
        @DisplayName("碎片数 = 2 → 放行（判据要求 >2）")
        void twoTinyChunksPass() {
            List<KbChunk> list = new ArrayList<>();
            list.add(chunk("甲".repeat(10), 0));
            list.add(chunk("乙".repeat(10), 1));
            for (int i = 2; i < 8; i++) {
                list.add(chunk("丙".repeat(400), i));
            }
            assertThat(KbChunkValidator.validate(list, 2420, 512).ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("判据 4：所有块都远低于目标")
    class AllFarBelowTarget {

        @Test
        @DisplayName("最大块 < chunkSize/4 且总字符 > chunkSize → all chunks far below target size")
        void allChunksTooSmall() {
            // chunkSize=400 ⇒ 阈值 100；所有块都是 99 字符
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(chunks(10, 99), 990, 400);
            assertThat(r.ok()).isFalse();
            assertThat(r.reason()).isEqualTo("all chunks far below target size");
        }

        @Test
        @DisplayName("最大块正好等于 chunkSize/4 → 放行（判据是严格小于）")
        void exactlyQuarterPasses() {
            // chunkSize=400 ⇒ 阈值 100；最大块正好 100
            assertThat(KbChunkValidator.validate(chunks(10, 100), 1000, 400).ok()).isTrue();
        }

        @Test
        @DisplayName("总字符 <= chunkSize → 放行（短文档不适用此判据）")
        void shortDocumentPasses() {
            assertThat(KbChunkValidator.validate(chunks(2, 50), 100, 400).ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("判据 5：超过两倍预算")
    class ExceedsTwiceTarget {

        @Test
        @DisplayName("最大块 > 2*chunkSize → chunk exceeds 2x target size")
        void chunkTooLarge() {
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(chunks(3, 1025), 3075, 512);
            assertThat(r.ok()).isFalse();
            assertThat(r.reason()).isEqualTo("chunk exceeds 2x target size");
        }

        @Test
        @DisplayName("最大块正好等于 2*chunkSize → 放行（判据是严格大于）")
        void exactlyTwicePasses() {
            assertThat(KbChunkValidator.validate(chunks(3, 1024), 3072, 512).ok()).isTrue();
        }

        @Test
        @DisplayName("chunkSize=0 时不触发（源侧有 chunkSize > 0 守卫，防除零语义）")
        void zeroChunkSizeDoesNotTrigger() {
            assertThat(KbChunkValidator.validate(chunks(2, 100), 200, 0).ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("长度口径：必须按 rune 数而不是 char 数")
    class RuneCounting {

        @Test
        @DisplayName("emoji 按 rune 计数（1 个 emoji = 1 rune，但 String.length()=2）")
        void emojiCountedAsSingleRune() {
            // 600 个 emoji：rune 数 600 < 2*512=1024 ⇒ 不触发判据 5
            // 若误用 String.length() 会得 1200 > 1024 ⇒ 误报
            String emoji = "😀".repeat(600);
            KbChunkValidator.ValidationResult r =
                KbChunkValidator.validate(List.of(chunk(emoji, 0), chunk(emoji, 1)), 1200, 512);
            assertThat(r.ok())
                .as("⭐ 必须用 codePointCount；用 length() 会把 emoji 算成 2 个字符")
                .isTrue();
        }

        @Test
        @DisplayName("runeLength 对中文/emoji/null 均正确")
        void runeLengthBasics() {
            assertThat(KbChunkValidator.runeLength(null)).isZero();
            assertThat(KbChunkValidator.runeLength("")).isZero();
            assertThat(KbChunkValidator.runeLength("中文")).isEqualTo(2);
            assertThat(KbChunkValidator.runeLength("😀")).isEqualTo(1);
            assertThat(KbChunkValidator.runeLength("a😀中")).isEqualTo(3);
        }
    }
}

package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本合并工具 · 移植自 WeKnora {@code searchutil/chunkmerge.go}。
 *
 * <p><b>WHY 这些断言</b>：这里的每个函数都决定「要不要删掉一段内容」。
 * 删错了<b>不会报错</b>，只会让答案少一段事实 —— 用户看到的是「知识库里没有」，
 * 而实际上有。所以每条边界都要钉死。
 */
@DisplayName("[B5] 文本合并工具")
class KbMergeTextTest {

    @Nested
    @DisplayName("⭐ 长度用码点（rune），不是 String.length()")
    class RuneLength {

        @Test
        @DisplayName("纯中文：两者相等（这个用例测不出错，但保证基线）")
        void chineseSame() {
            assertThat(KbMergeText.runeLen("农村污水")).isEqualTo(4);
            assertThat("农村污水".length()).isEqualTo(4);
        }

        @Test
        @DisplayName("⭐⭐ 含 emoji：码点数 ≠ length()（代理对）")
        void emojiDiffers() {
            String s = "污水💧处理";
            // "💧" 是一个码点、两个 UTF-16 code unit
            assertThat(KbMergeText.runeLen(s)).isEqualTo(5);
            assertThat(s.length()).isEqualTo(6);
            assertThat(KbMergeText.runeLen(s)).as("必须按码点算，否则截断会切坏 emoji")
                .isNotEqualTo(s.length());
        }

        @Test
        @DisplayName("null / 空串 ⇒ 0")
        void nullSafe() {
            assertThat(KbMergeText.runeLen(null)).isZero();
            assertThat(KbMergeText.runeLen("")).isZero();
        }
    }

    @Nested
    @DisplayName("appendWithOverlap 的边界")
    class AppendWithOverlap {

        @Test
        @DisplayName("⭐⭐ positionOverlap <= 0 ⇒ 原样拼接，一个字符都不删")
        void nonPositiveOverlapNeverTrims() {
            // 这两段有真实的重叠后缀/前缀，但坐标说它们不相邻
            String acc = "农村生活污水处理设施的建设标准如下所述";
            String next = "建设标准如下所述，需要满足三项要求";
            String out = KbMergeText.appendWithOverlap(acc, next, 0);
            assertThat(out)
                .as("⭐⭐ 位置不相邻时必须直接拼接 —— 否则文本匹配会误把 next 开头当"
                    + "『补写表头』删掉，造成不可逆内容丢失")
                .isEqualTo(acc + next);
            assertThat(KbMergeText.appendWithOverlap(acc, next, -5)).isEqualTo(acc + next);
        }

        @Test
        @DisplayName("重叠 ≥12 码点且有共同后缀 ⇒ 去掉重叠部分")
        void trimsRealOverlap() {
            // 重叠段必须 ≥ MIN_OVERLAP_RUNES(12)：短重叠（如 6 个字）不参与匹配，
            // 因为太短的片段在正文里到处都有，会误删真实内容
            String shared = "接下来是第二段内容的详细说明";
            String acc = "第一段内容到此结束，" + shared;
            String next = shared + "，以及第三段";
            String out = KbMergeText.appendWithOverlap(acc, next, 8);
            assertThat(out)
                .as("重叠被正确裁剪，不应出现「%s」重复两次", shared)
                .isEqualTo("第一段内容到此结束，" + shared + "，以及第三段");
        }

        @Test
        @DisplayName("无重叠 ⇒ 直接拼")
        void noOverlapAppends() {
            assertThat(KbMergeText.appendWithOverlap("甲乙丙", "丁戊己", 3))
                .isEqualTo("甲乙丙丁戊己");
        }
    }

    @Nested
    @DisplayName("appendWithExactOverlap 的校验")
    class AppendWithExactOverlap {

        @Test
        @DisplayName("⭐⭐ 坐标重叠量对不上 ⇒ 返回 null（调用方回退到文本匹配）")
        void mismatchReturnsNull() {
            assertThat(KbMergeText.appendWithExactOverlap("农村污水", "城市污水", 3))
                .as("⭐⭐ 必须返回 null 让调用方回退 —— 静默返回半成品会丢字")
                .isNull();
        }

        @Test
        @DisplayName("重叠量超出任一段长度 ⇒ null")
        void overlapTooLargeReturnsNull() {
            assertThat(KbMergeText.appendWithExactOverlap("abcd", "cdef", 10)).isNull();
            assertThat(KbMergeText.appendWithExactOverlap("abcd", "cdef", -1)).isNull();
        }

        @Test
        @DisplayName("重叠量 0 ⇒ 直接拼")
        void zeroOverlapAppends() {
            assertThat(KbMergeText.appendWithExactOverlap("abcd", "efgh", 0)).isEqualTo("abcdefgh");
        }

        @Test
        @DisplayName("重叠量正确 ⇒ 精确裁剪")
        void exactTrim() {
            // 末 2 个码点 "cd" == 首 2 个码点 "cd"
            assertThat(KbMergeText.appendWithExactOverlap("abcd", "cdef", 2)).isEqualTo("abcdef");
        }
    }

    @Nested
    @DisplayName("包含判定")
    class Contains {

        @Test
        @DisplayName("短于 12 个码点的子串不算包含（防常见词误删）")
        void shortSubstringNotContained() {
            assertThat(KbMergeText.containsChunkContent("农村污水处理设施", "污水"))
                .as("⭐ 2 个字的子串可能是任意内容的一部分，不能当作『已被包含』")
                .isFalse();
        }

        @Test
        @DisplayName("≥ 12 个码点的子串算包含")
        void longSubstringContained() {
            String container = "农村生活污水处理设施的建设与运行管理办法";
            // ⚠️ 必须 ≥12 码点（含中文标点也算，但这里全用汉字更直观）
            String contained = "污水处理设施的建设与运行";
            assertThat(KbMergeText.runeLen(contained)).isGreaterThanOrEqualTo(12);
            assertThat(KbMergeText.containsChunkContent(container, contained)).isTrue();
        }

        @Test
        @DisplayName("完全相同 ⇒ 包含")
        void identicalContained() {
            assertThat(KbMergeText.containsChunkContent("abc", "abc")).isTrue();
        }
    }

    @Nested
    @DisplayName("joinChunkContent")
    class JoinChunkContent {

        @Test
        @DisplayName("后段完全包含前段 ⇒ 只留后段")
        void containedCollapses() {
            String a = "农村生活污水处理设施的建设标准";
            String b = "前文。农村生活污水处理设施的建设标准。后文";
            assertThat(KbMergeText.joinChunkContent(a, b, "\n\n")).isEqualTo(b);
        }

        @Test
        @DisplayName("空串短路")
        void emptyShortCircuit() {
            assertThat(KbMergeText.joinChunkContent("", "abc", "\n\n")).isEqualTo("abc");
            assertThat(KbMergeText.joinChunkContent("abc", "", "\n\n")).isEqualTo("abc");
            assertThat(KbMergeText.joinChunkContent(null, null, "\n\n")).isEmpty();
        }
    }

    @Nested
    @DisplayName("去重用的文本工具")
    class DedupHelpers {

        @Test
        @DisplayName("签名：大小写/多空白/全角空格归一后相同")
        void signatureNormalizes() {
            String a = KbMergeText.contentSignature("农村  污水处理");
            String b = KbMergeText.contentSignature("  农村 污水处理  ");
            String c = KbMergeText.contentSignature("农村\u3000污水处理");
            assertThat(a).isEqualTo(b);
            assertThat(a).as("⭐ 全角空格必须归一 —— 中文正文里很常见，不处理会让同一内容产生两个签名")
                .isEqualTo(c);
        }

        @Test
        @DisplayName("空内容 ⇒ 签名 null（不参与签名去重）")
        void emptySignatureNull() {
            assertThat(KbMergeText.contentSignature("")).isNull();
            assertThat(KbMergeText.contentSignature("   ")).isNull();
            assertThat(KbMergeText.contentSignature(null)).isNull();
        }

        @Test
        @DisplayName("⭐⭐ shorterOnlyFraction：短文本有独有 token ⇒ 不能删")
        void shorterOnlyFractionBlocksDeletion() {
            // 两者共享大量样板词，但短的还有 "88.9" 这个只属于它的事实
            var longTokens = KbMergeText.tokenizeSimple(
                "农村生活污水处理设施运行管理办法规定各项指标应当达标排放并定期报送数据");
            var shortTokens = KbMergeText.tokenizeSimple(
                "农村生活污水处理设施运行管理办法规定各项指标应当达标排放88.9");
            double overlap = KbMergeText.tokenOverlapRatio(shortTokens, longTokens);
            double onlyFraction = KbMergeText.shorterOnlyFraction(shortTokens, longTokens);

            assertThat(overlap).as("重叠率很高（共享样板文字）").isGreaterThan(0.85);
            assertThat(onlyFraction)
                .as("⭐⭐ 短文本有独占 token（88.9）⇒ onlyFraction > 0.02 ⇒ 必须两条都留，"
                    + "否则会静默丢掉一个真实数字")
                .isGreaterThan(KbMergeStage.PARTIAL_OVERLAP_SHORTER_ONLY);
        }

        @Test
        @DisplayName("短文本 token 全在长文本里 ⇒ onlyFraction = 0")
        void fullContainmentZeroFraction() {
            var longTokens = KbMergeText.tokenizeSimple("污水处理设施运行维护管理办法实施细则");
            var shortTokens = KbMergeText.tokenizeSimple("污水处理设施");
            assertThat(KbMergeText.shorterOnlyFraction(shortTokens, longTokens)).isZero();
        }

        @Test
        @DisplayName("空集合的重叠率是 0（不是 1）")
        void emptyOverlapZero() {
            assertThat(KbMergeText.tokenOverlapRatio(null, null)).isZero();
            assertThat(KbMergeText.tokenOverlapRatio(java.util.Set.of(), java.util.Set.of()))
                .isZero();
            // ⚠️ 空短集的 onlyFraction 是 1（空集不能证明被覆盖）
            assertThat(KbMergeText.shorterOnlyFraction(java.util.Set.of(), java.util.Set.of()))
                .isEqualTo(1.0);
        }
    }
}

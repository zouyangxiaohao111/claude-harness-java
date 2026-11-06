package com.nexusai.kb.app.faq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KbFaqMetadata} 纯函数测试 · 批次 W5。
 *
 * <p>重点守三条「不这么做就静默出错」的规则：
 * ① 反例永不进索引正文；② 答案是否进正文取决于 index_mode；
 * ③ 归一化只用于判重，不改原文。
 */
class KbFaqMetadataTest {

    private static KbFaqMetadata.Entry entry(String std, List<String> similar,
                                             List<String> negative, List<String> answers) {
        return KbFaqMetadata.fromPayload(std, similar, negative, answers, null);
    }

    @Nested
    @DisplayName("渲染索引正文")
    class RenderContent {

        @Test
        @DisplayName("question_only 只出问法，不出答案")
        void questionOnlyOmitsAnswers() {
            KbFaqMetadata.Entry e = entry("如何申请补贴？",
                List.of("补贴怎么申请"), List.of(),
                List.of("向环保部门申请"));
            String c = KbFaqMetadata.renderContent(e, KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(c).isEqualTo("Q: 如何申请补贴？\nSimilar Questions:\n- 补贴怎么申请\n");
            assertThat(c).doesNotContain("Answers:").doesNotContain("向环保部门申请");
        }

        @Test
        @DisplayName("question_answer 带上答案")
        void questionAnswerIncludesAnswers() {
            KbFaqMetadata.Entry e = entry("如何申请补贴？", List.of(),
                List.of(), List.of("向环保部门申请"));
            String c = KbFaqMetadata.renderContent(e, KbFaqMetadata.INDEX_MODE_QUESTION_ANSWER);
            assertThat(c).contains("Answers:").contains("- 向环保部门申请");
        }

        @Test
        @DisplayName("⭐⭐ 反例问题永不进正文（两种模式都不进）")
        void negativesNeverIndexed() {
            KbFaqMetadata.Entry e = entry("农村污水补贴", List.of(),
                List.of("城市污水补贴", "工业废水补贴"),
                List.of("答案甲"));
            for (String mode : new String[]{KbFaqMetadata.INDEX_MODE_QUESTION_ONLY,
                KbFaqMetadata.INDEX_MODE_QUESTION_ANSWER}) {
                String c = KbFaqMetadata.renderContent(e, mode);
                assertThat(c).as("模式 %s 不得包含反例", mode)
                    .doesNotContain("城市污水补贴")
                    .doesNotContain("工业废水补贴")
                    // 反例也不应出现在 Similar Questions 段
                    .doesNotContain("Similar Questions");
            }
        }

        @Test
        @DisplayName("无相似问时不输出空的 Similar Questions 段")
        void noEmptySection() {
            KbFaqMetadata.Entry e = entry("只有一个问", List.of(), List.of(), List.of("答"));
            String c = KbFaqMetadata.renderContent(e, KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(c).isEqualTo("Q: 只有一个问\n");
        }

        @Test
        @DisplayName("多个相似问按输入顺序逐行输出")
        void similarQuestionsOrdered() {
            KbFaqMetadata.Entry e = entry("甲", List.of("乙", "丙", "丁"), List.of(), List.of("答"));
            String c = KbFaqMetadata.renderContent(e, KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(c.indexOf("- 乙")).isLessThan(c.indexOf("- 丙"));
            assertThat(c.indexOf("- 丙")).isLessThan(c.indexOf("- 丁"));
        }
    }

    @Nested
    @DisplayName("输入校验")
    class Validation {

        @Test
        @DisplayName("标准问为空 ⇒ 拒绝")
        void rejectsEmptyStandardQuestion() {
            assertThatThrownBy(() -> entry("   ", List.of(), List.of(), List.of("答")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标准问");
        }

        @Test
        @DisplayName("没有答案 ⇒ 拒绝")
        void rejectsNoAnswers() {
            assertThatThrownBy(() -> entry("问题", List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("答案");
            // 全是空白的答案等同于没有
            assertThatThrownBy(() -> entry("问题", List.of(), List.of(), Arrays.asList("  ", "")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("answer_strategy 只接受 all / random")
        void rejectsBadStrategy() {
            assertThatThrownBy(() -> KbFaqMetadata.fromPayload("q", List.of(), List.of(),
                List.of("a"), "first"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer_strategy");
            // 大小写与空白容错
            assertThat(KbFaqMetadata.fromPayload("q", List.of(), List.of(), List.of("a"), " RANDOM ")
                .answerStrategy()).isEqualTo("random");
        }

        @Test
        @DisplayName("不传策略默认 all")
        void defaultsToAll() {
            assertThat(entry("q", List.of(), List.of(), List.of("a")).answerStrategy())
                .isEqualTo("all");
        }
    }

    @Nested
    @DisplayName("列表清洗")
    class SanitizeList {

        @Test
        @DisplayName("trim + 去空 + 去重（保持顺序）")
        void trimsDedupsKeepsOrder() {
            List<String> out = KbFaqMetadata.sanitizeList(
                Arrays.asList(" 甲 ", "", null, "乙", "甲", "  "));
            assertThat(out).containsExactly("甲", "乙");
        }

        @Test
        @DisplayName("null / 空列表返回空列表（不返回 null）")
        void neverReturnsNull() {
            assertThat(KbFaqMetadata.sanitizeList(null)).isEmpty();
            assertThat(KbFaqMetadata.sanitizeList(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("归一化判重")
    class Normalize {

        @Test
        @DisplayName("全角/半角、大小写、标点、空白都不影响")
        void equalAfterNormalize() {
            String a = KbFaqMetadata.normalize("如何申请？");
            String b = KbFaqMetadata.normalize("如何申请");
            String c = KbFaqMetadata.normalize("  如何  申请  ");
            String d = KbFaqMetadata.normalize("如何申请！");
            assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(d);
        }

        @Test
        @DisplayName("全角字母数字转半角后与半角相同")
        void fullWidthAlnum() {
            assertThat(KbFaqMetadata.normalize("ＡＢＣ１２３"))
                .isEqualTo(KbFaqMetadata.normalize("abc123"));
        }

        @Test
        @DisplayName("不同的问题归一化后仍不同（不能过度归一）")
        void differentQuestionsStayDifferent() {
            assertThat(KbFaqMetadata.normalize("如何申请补贴"))
                .isNotEqualTo(KbFaqMetadata.normalize("如何申请补助"));
        }

        @Test
        @DisplayName("null / 空白返回空串")
        void handlesNull() {
            assertThat(KbFaqMetadata.normalize(null)).isEmpty();
            assertThat(KbFaqMetadata.normalize("，。！")).isEmpty();
        }
    }

    @Nested
    @DisplayName("JSON 往返")
    class JsonRoundTrip {

        @Test
        @DisplayName("⭐⭐ 写进去的每个字段都能读回来（读写对称）")
        void allFieldsRoundTrip() {
            KbFaqMetadata.Entry original = new KbFaqMetadata.Entry(
                "标准问", List.of("相似1", "相似2"), List.of("反例1"),
                List.of("答案1", "答案2"), "random", 1, "faq");
            KbFaqMetadata.Entry back = KbFaqMetadata.fromJson(KbFaqMetadata.toJson(original));
            assertThat(back).isNotNull();
            assertThat(back.standardQuestion()).isEqualTo("标准问");
            assertThat(back.similarQuestions()).containsExactly("相似1", "相似2");
            assertThat(back.negativeQuestions()).containsExactly("反例1");
            assertThat(back.answers()).containsExactly("答案1", "答案2");
            assertThat(back.answerStrategy()).isEqualTo("random");
            assertThat(back.version()).isEqualTo(1);
            assertThat(back.source()).isEqualTo("faq");
        }

        @Test
        @DisplayName("空列表写成 []，读回来是空列表（不是 null）")
        void emptyListsRoundTrip() {
            KbFaqMetadata.Entry e = new KbFaqMetadata.Entry("问", List.of(), List.of(),
                List.of("答"), "all", 1, "faq");
            String json = KbFaqMetadata.toJson(e);
            assertThat(json).contains("\"similar_questions\":[]");
            KbFaqMetadata.Entry back = KbFaqMetadata.fromJson(json);
            assertThat(back).isNotNull();
            assertThat(back.similarQuestions()).isEmpty();
        }

        @Test
        @DisplayName("坏 JSON ⇒ 返回 null（不抛，让列表接口跳过这条）")
        void badJsonReturnsNull() {
            assertThat(KbFaqMetadata.fromJson("{不是json")).isNull();
            assertThat(KbFaqMetadata.fromJson("[]")).isNull();
            assertThat(KbFaqMetadata.fromJson("")).isNull();
            assertThat(KbFaqMetadata.fromJson(null)).isNull();
        }

        @Test
        @DisplayName("缺 answer_strategy 时默认 all")
        void missingStrategyDefaultsToAll() {
            KbFaqMetadata.Entry e = KbFaqMetadata.fromJson(
                "{\"standard_question\":\"问\",\"answers\":[\"答\"]}");
            assertThat(e).isNotNull();
            assertThat(e.answerStrategy()).isEqualTo("all");
        }
    }

    @Nested
    @DisplayName("index_mode 解析")
    class IndexMode {

        @Test
        @DisplayName("⭐⭐ 空配置/无 index_mode 默认 question_only（与源侧 CreateFAQEntry 一致）")
        void defaultsToQuestionOnly() {
            assertThat(KbFaqMetadata.resolveIndexMode(null))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(KbFaqMetadata.resolveIndexMode(""))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(KbFaqMetadata.resolveIndexMode("{}"))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(KbFaqMetadata.resolveIndexMode("{\"other\":1}"))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
        }

        @Test
        @DisplayName("显式 question_answer 才带上答案")
        void explicitQuestionAnswer() {
            assertThat(KbFaqMetadata.resolveIndexMode("{\"index_mode\":\"question_answer\"}"))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ANSWER);
        }

        @Test
        @DisplayName("非法值回落 question_only")
        void invalidFallsBack() {
            assertThat(KbFaqMetadata.resolveIndexMode("{\"index_mode\":\"whatever\"}"))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
            assertThat(KbFaqMetadata.resolveIndexMode("坏 json"))
                .isEqualTo(KbFaqMetadata.INDEX_MODE_QUESTION_ONLY);
        }
    }
}

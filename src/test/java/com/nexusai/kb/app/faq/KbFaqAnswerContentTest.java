package com.nexusai.kb.app.faq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FAQ「回答用正文」· 对齐源侧 {@code merge_faq.go:buildFAQAnswerContent}。
 *
 * <p><b>WHY 这些断言（规则九）</b>：FAQ 块在库里的 content 是<b>索引内容</b>
 * （带相似问法，为的是被不同问法命中）；而给模型的必须是<b>回答内容</b>。
 * 两者混用不会报错，只会让回答里莫名其妙多出一串「相似问法」——
 * 用户看到的是「这个答案把别人的问题也抄进来了」。
 * ⇒ 断言必须同时验「有问有答」和「不含相似问法」。
 */
@DisplayName("[QA 对齐] FAQ 回答用正文")
class KbFaqAnswerContentTest {

    private static KbFaqMetadata.Entry entry(List<String> similar, List<String> answers) {
        return new KbFaqMetadata.Entry(
            "报销流程是什么？", similar, List.of(), answers, KbFaqMetadata.STRATEGY_ALL, 1, "manual");
    }

    @Test
    @DisplayName("只输出 Q + Answer，⛔ 不含相似问法（相似问法只属于索引侧）")
    void excludesSimilarQuestions() {
        String s = KbFaqMetadata.answerContent(
            entry(List.of("怎么报销", "报销怎么走"), List.of("提交发票后由财务审批")));
        assertThat(s).contains("Q: 报销流程是什么？");
        assertThat(s).contains("提交发票后由财务审批");
        assertThat(s).doesNotContain("怎么报销");
        assertThat(s).doesNotContain("Similar Questions");
    }

    @Test
    @DisplayName("空答案列表 → 仍有 Q 行（不返回全空，避免这条命中变成空白段）")
    void questionOnlyStillRenders() {
        String s = KbFaqMetadata.answerContent(entry(List.of(), List.of()));
        assertThat(s).isEqualTo("Q: 报销流程是什么？");
    }

    @Test
    @DisplayName("答案里的空白项被剔除（不留空的 `- ` 行）")
    void dropsBlankAnswers() {
        String s = KbFaqMetadata.answerContent(entry(List.of(), List.of("  ", "有内容", "")));
        assertThat(s).contains("- 有内容");
        assertThat(s).doesNotContain("- \n");
    }

    @Test
    @DisplayName("null 条目 → 空串（不抛）")
    void nullEntryYieldsEmpty() {
        assertThat(KbFaqMetadata.answerContent(null)).isEmpty();
    }

    @Test
    @DisplayName("与索引内容不同：renderContent 带相似问法，answerContent 不带")
    void differsFromIndexContent() {
        KbFaqMetadata.Entry e = entry(List.of("怎么报销"), List.of("答案"));
        String index = KbFaqMetadata.renderContent(e, KbFaqMetadata.INDEX_MODE_QUESTION_ANSWER);
        String answer = KbFaqMetadata.answerContent(e);
        assertThat(index).contains("怎么报销");
        assertThat(answer).doesNotContain("怎么报销");
    }
}

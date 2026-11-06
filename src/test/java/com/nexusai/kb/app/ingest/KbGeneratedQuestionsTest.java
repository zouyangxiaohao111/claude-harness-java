package com.nexusai.kb.app.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbGeneratedQuestions} 测试 · 批次 W6。
 *
 * <p>真正要守的是「问句必须真的进索引文本」：
 * 只写进 metadata 而不拼进 FTS / 向量输入 ⇒ 功能看起来做完了、召回率零提升。
 */
class KbGeneratedQuestionsTest {

    @Test
    @DisplayName("读出问句数组")
    void readsQuestions() {
        String json = "{\"generated_questions\":["
            + "{\"id\":\"q1\",\"question\":\"如何申请补贴？\",\"content_revision\":2},"
            + "{\"id\":\"q2\",\"question\":\"补贴标准是多少？\",\"content_revision\":2}]}";
        assertThat(KbGeneratedQuestions.read(json))
            .containsExactly("如何申请补贴？", "补贴标准是多少？");
    }

    @Test
    @DisplayName("字符串数组形态也兼容")
    void readsPlainStrings() {
        assertThat(KbGeneratedQuestions.read("{\"generated_questions\":[\"甲\",\"乙\"]}"))
            .containsExactly("甲", "乙");
    }

    @Test
    @DisplayName("⭐ 索引片段每行一个问句，且以换行开头（可直接字符串相加）")
    void indexTextFormat() {
        String json = "{\"generated_questions\":[\"问题一\",\"问题二\"]}";
        assertThat(KbGeneratedQuestions.indexText(json)).isEqualTo("\n问题一\n问题二");
    }

    @Test
    @DisplayName("没有问句 ⇒ 空串（不是 null，避免调用方 NPE）")
    void emptyWhenAbsent() {
        assertThat(KbGeneratedQuestions.indexText(null)).isEmpty();
        assertThat(KbGeneratedQuestions.indexText("")).isEmpty();
        assertThat(KbGeneratedQuestions.indexText("{}")).isEmpty();
        assertThat(KbGeneratedQuestions.indexText("{\"other\":1}")).isEmpty();
        assertThat(KbGeneratedQuestions.indexText("{\"generated_questions\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("坏 JSON ⇒ 空列表（一条脏 metadata 不能让整个索引失败）")
    void badJsonFailsSoft() {
        assertThat(KbGeneratedQuestions.read("{坏")).isEmpty();
        assertThat(KbGeneratedQuestions.indexText("{坏")).isEmpty();
        assertThat(KbGeneratedQuestions.read("[1,2,3]")).isEmpty();
    }

    @Test
    @DisplayName("空问句被丢弃")
    void skipsBlank() {
        String json = "{\"generated_questions\":[{\"question\":\"有\"},{\"question\":\"  \"},{},{\"question\":null}]}";
        assertThat(KbGeneratedQuestions.read(json)).containsExactly("有");
    }

    @Test
    @DisplayName("问句前后空白被 trim")
    void trims() {
        assertThat(KbGeneratedQuestions.read("{\"generated_questions\":[\"  问句  \"]}"))
            .containsExactly("问句");
    }

    @Test
    @DisplayName("结果不可变（调用方不能改到内部状态）")
    void immutableList() {
        List<String> qs = KbGeneratedQuestions.read("{\"generated_questions\":[\"甲\"]}");
        assertThat(qs).isUnmodifiable();
    }
}

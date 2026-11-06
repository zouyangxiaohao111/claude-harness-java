package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbQuestionGenerationService} 纯函数测试 · 批次 W6。
 */
class KbQuestionGenerationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    @DisplayName("解析模型输出")
    class ParseQuestions {

        @Test
        @DisplayName("每行一条")
        void onePerLine() {
            assertThat(KbQuestionGenerationService.parseQuestions("如何申请补贴？\n补贴标准是多少？", 3))
                .containsExactly("如何申请补贴？", "补贴标准是多少？");
        }

        @Test
        @DisplayName("⭐ 去掉行首编号与项目符号")
        void stripsNumbering() {
            String raw = "1. 如何申请补贴？\n2) 补贴标准是多少？\n- 谁负责审核？\n* 多久到账？";
            assertThat(KbQuestionGenerationService.parseQuestions(raw, 10))
                .containsExactly("如何申请补贴？", "补贴标准是多少？", "谁负责审核？", "多久到账？");
        }

        @Test
        @DisplayName("⭐⭐ 剔除 <think> 推理块（否则每行推理都被当成问句索引进去）")
        void stripsThinkBlock() {
            String raw = "<think>\n用户想要的是补贴相关内容\n这里应该生成三条\n</think>\n如何申请补贴？\n补贴标准是多少？";
            assertThat(KbQuestionGenerationService.parseQuestions(raw, 10))
                .containsExactly("如何申请补贴？", "补贴标准是多少？");
        }

        @Test
        @DisplayName("多行 think 块（含空行）也能整块剔除")
        void multilineThink() {
            String raw = "<think>\n第一行\n\n第二行\n</think>真正的问句是什么？";
            assertThat(KbQuestionGenerationService.parseQuestions(raw, 5))
                .containsExactly("真正的问句是什么？");
        }

        @Test
        @DisplayName("⭐ 单字行被丢弃（按 UTF-8 字节判：1 个汉字 = 3 字节 ≤ 5）")
        void tooShortDropped() {
            assertThat(KbQuestionGenerationService.parseQuestions("行\n如何申请补贴？", 5))
                .containsExactly("如何申请补贴？");
        }

        @Test
        @DisplayName("⭐⭐ 长度按字节判（与 Go 的 len() 同口径）：中文 3 字 = 9 字节，保留")
        void byteLengthSemantics() {
            // 若误用 codePointCount > 5，这 3 个字的问句会被误删
            assertThat(KbQuestionGenerationService.parseQuestions("怎么办？", 5))
                .containsExactly("怎么办？");
        }

        @Test
        @DisplayName("截到 limit 条")
        void respectsLimit() {
            String raw = "问句一是什么？\n问句二是什么？\n问句三是什么？\n问句四是什么？";
            assertThat(KbQuestionGenerationService.parseQuestions(raw, 2)).hasSize(2);
        }

        @Test
        @DisplayName("空行被忽略")
        void blankLinesIgnored() {
            assertThat(KbQuestionGenerationService.parseQuestions("\n\n如何申请补贴？\n\n", 5))
                .containsExactly("如何申请补贴？");
        }

        @Test
        @DisplayName("空 / null ⇒ 空列表")
        void emptyInputs() {
            assertThat(KbQuestionGenerationService.parseQuestions(null, 3)).isEmpty();
            assertThat(KbQuestionGenerationService.parseQuestions("", 3)).isEmpty();
            assertThat(KbQuestionGenerationService.parseQuestions("如何申请补贴？", 0)).isEmpty();
        }

        @Test
        @DisplayName("前后空白被 trim")
        void trims() {
            assertThat(KbQuestionGenerationService.parseQuestions("   如何申请补贴？   ", 3))
                .containsExactly("如何申请补贴？");
        }
    }

    @Nested
    @DisplayName("合并 metadata")
    class MergeQuestions {

        @Test
        @DisplayName("⭐⭐ 保留原有其它 key（不能整体覆盖）")
        void keepsOtherKeys() throws Exception {
            String old = "{\"faq\":{\"a\":1},\"image\":\"x.png\"}";
            String merged = KbQuestionGenerationService.mergeQuestions(
                old, List.of("问句是什么？"), 2);
            JsonNode n = JSON.readTree(merged);
            assertThat(n.path("faq").path("a").asInt()).isEqualTo(1);
            assertThat(n.path("image").asText()).isEqualTo("x.png");
            assertThat(n.path(KbGeneratedQuestions.KEY)).hasSize(1);
        }

        @Test
        @DisplayName("问句带 content_revision")
        void carriesRevision() throws Exception {
            String merged = KbQuestionGenerationService.mergeQuestions(
                null, List.of("问句是什么？"), 7);
            JsonNode n = JSON.readTree(merged);
            assertThat(n.path(KbGeneratedQuestions.KEY).get(0).path("content_revision").asInt())
                .isEqualTo(7);
            assertThat(n.path("generated_questions_revision").asInt()).isEqualTo(7);
        }

        @Test
        @DisplayName("⭐ 合并后的 metadata 能被 KbGeneratedQuestions 读回（往返对称）")
        void roundTrip() {
            String merged = KbQuestionGenerationService.mergeQuestions(
                "{\"other\":1}", List.of("问句一是什么？", "问句二是什么？"), 1);
            assertThat(KbGeneratedQuestions.read(merged))
                .containsExactly("问句一是什么？", "问句二是什么？");
        }

        @Test
        @DisplayName("坏原 JSON ⇒ 当成空对象（不抛）")
        void badOldJson() throws Exception {
            String merged = KbQuestionGenerationService.mergeQuestions(
                "{坏", List.of("问句是什么？"), 0);
            assertThat(JSON.readTree(merged).path(KbGeneratedQuestions.KEY)).hasSize(1);
        }

        @Test
        @DisplayName("重复生成会覆盖旧问句（不是累加）")
        void overwritesPrevious() throws Exception {
            String first = KbQuestionGenerationService.mergeQuestions(
                null, List.of("旧问句是什么？"), 1);
            String second = KbQuestionGenerationService.mergeQuestions(
                first, List.of("新问句是什么？"), 2);
            assertThat(KbGeneratedQuestions.read(second)).containsExactly("新问句是什么？");
        }

        @Test
        @DisplayName("每条问句都有非空 id")
        void hasIds() throws Exception {
            String merged = KbQuestionGenerationService.mergeQuestions(
                null, List.of("问句一是什么？", "问句二是什么？"), 1);
            for (JsonNode n : JSON.readTree(merged).path(KbGeneratedQuestions.KEY)) {
                assertThat(n.path("id").asText()).isNotBlank();
            }
        }
    }
}

package com.nexusai.kb.app.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.app.ingest.KbGeneratedQuestions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生成问句「新增 / 修改」的纯函数测试（{@link KbChunkService#upsertQuestionMetadata}）。
 *
 * <h2>★★★★ 本类守护的最关键一条：修改必须是「替换」，不是「追加」</h2>
 * 把 {@code question_id} 非空的分支写成「总是 append」，用户点「保存」后列表会
 * <b>多出一条、旧的那条还在</b> —— UI 上看起来「改了」，实际是复制了一份。
 * 这类缺陷不会报错、不会 500，只会在检索里多出一条互斥的问句。
 * ⇒ 「修改」用例必须能区分这两种实现（{@link Modify#replacesInsteadOfAppending}）。
 *
 * <p>另外两条：空问句要 400；{@code question_id} 找不到要 400（⛔ 不静默新增）。
 */
@DisplayName("问句 upsert · 追加 vs 替换")
class KbChunkQuestionUpsertTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode questionsOf(String metadataJson) throws Exception {
        JsonNode root = JSON.readTree(metadataJson);
        return root.get(KbGeneratedQuestions.KEY);
    }

    private static String seed(String... questions) {
        StringBuilder sb = new StringBuilder("{\"generated_questions\":[");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"q").append(i).append("\",\"question\":\"")
                .append(questions[i]).append("\",\"content_revision\":1}");
        }
        return sb.append("],\"faq\":\"keep-me\"}").toString();
    }

    @Nested
    @DisplayName("新增（question_id 为空）")
    class Add {

        @Test
        @DisplayName("空 metadata ⇒ 建出一条")
        void createsFirst() throws Exception {
            String out = KbChunkService.upsertQuestionMetadata(null, "", "补贴怎么申请？", 0);
            JsonNode arr = questionsOf(out);
            assertThat(arr).hasSize(1);
            assertThat(arr.get(0).path("question").asText()).isEqualTo("补贴怎么申请？");
        }

        @Test
        @DisplayName("已有 2 条 ⇒ 变 3 条，顺序保持（新的在末尾）")
        void appendsToEnd() throws Exception {
            String out = KbChunkService.upsertQuestionMetadata(
                seed("第一条问句", "第二条问句"), null, "第三条问句", 2);
            JsonNode arr = questionsOf(out);
            assertThat(arr).hasSize(3);
            assertThat(arr.get(2).path("question").asText()).isEqualTo("第三条问句");
            assertThat(arr.get(2).path("content_revision").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("⭐ metadata 里其它键（faq）必须保留 —— ⛔ 不能整体覆盖")
        void keepsOtherKeys() throws Exception {
            String out = KbChunkService.upsertQuestionMetadata(seed("原问句"), "", "新问句", 1);
            assertThat(JSON.readTree(out).path("faq").asText()).isEqualTo("keep-me");
        }
    }

    @Nested
    @DisplayName("修改（question_id = 旧问句文本）")
    class Modify {

        @Test
        @DisplayName("⭐⭐ 替换而非追加：条数不变、旧文本消失、新文本就位")
        void replacesInsteadOfAppending() throws Exception {
            String out = KbChunkService.upsertQuestionMetadata(
                seed("旧的问法", "第二条不变"), "旧的问法", "新的问法", 5);
            JsonNode arr = questionsOf(out);
            // 变异验证：把「替换」改成「总是追加」⇒ 这里会是 3 条 ⇒ 红
            assertThat(arr).hasSize(2);
            assertThat(arr.get(0).path("question").asText()).isEqualTo("新的问法");
            assertThat(arr.get(0).path("content_revision").asInt()).isEqualTo(5);
            assertThat(arr.get(1).path("question").asText()).isEqualTo("第二条不变");
            assertThat(out).doesNotContain("旧的问法");
        }

        @Test
        @DisplayName("替换保留原条目的 id（前端回传同一 id 仍能对上）")
        void keepsId() throws Exception {
            String out = KbChunkService.upsertQuestionMetadata(
                seed("旧的问法"), "旧的问法", "新的问法", 3);
            assertThat(questionsOf(out).get(0).path("id").asText()).isEqualTo("q0");
        }

        @Test
        @DisplayName("⛔ 找不到该问句 ⇒ 400，不静默新增")
        void throwsWhenMissing() {
            assertThatThrownBy(() -> KbChunkService.upsertQuestionMetadata(
                seed("甲问句", "乙问句"), "丙问句", "新问句", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("没有这条问句");
        }
    }

    @Nested
    @DisplayName("校验")
    class Validation {

        @Test
        @DisplayName("空问句 ⇒ 400")
        void rejectsBlank() {
            assertThatThrownBy(() -> KbChunkService.upsertQuestionMetadata(null, "", "   ", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("问句不能为空");
            assertThatThrownBy(() -> KbChunkService.upsertQuestionMetadata(null, null, null, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("问句不能为空");
        }

        @Test
        @DisplayName("问句两端空白被 trim")
        void trims() throws Exception {
            String out = KbChunkService.upsertQuestionMetadata(null, "", "  带空格  ", 0);
            assertThat(questionsOf(out).get(0).path("question").asText()).isEqualTo("带空格");
        }
    }
}

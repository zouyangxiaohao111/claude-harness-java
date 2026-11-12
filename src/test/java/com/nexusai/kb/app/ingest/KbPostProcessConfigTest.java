package com.nexusai.kb.app.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbPostProcessConfig} 解析测试 · 批次 W6。
 *
 * <p>重点守两类「静默出错」：
 * ① 配置缺省方向搞反（摘要不开 / Wiki 被关掉 / 打标默认跳过）；
 * ② 模型继承链断掉（问句/打标没继承摘要模型 ⇒ 明明配了模型却什么都不跑）。
 */
class KbPostProcessConfigTest {

    /** 造一个知识库行（只设本测试关心的字段）。 */
    private static com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord kb(
        String summaryModel, String qJson, String tagJson, String wikiJson) {
        com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord r =
            new com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord();
        r.setSummaryModelId(summaryModel);
        r.setEmbeddingModelId("emb-1");
        // 索引策略默认「向量 + 关键词都开」——问句能否生成看的是这个，
        // ⛔ 不是 embedding_model_id（见 QuestionGeneration#runnable 的注释）
        r.setIndexingStrategy("{\"vector_enabled\":true,\"keyword_enabled\":true}");
        r.setQuestionGenerationConfig(qJson);
        r.setAutoTagConfig(tagJson);
        r.setWikiConfig(wikiJson);
        r.setType("document");
        return r;
    }

    @Nested
    @DisplayName("摘要")
    class Summary {

        @Test
        @DisplayName("配了摘要模型 ⇒ 开启")
        void enabledWithModel() {
            assertThat(KbPostProcessConfig.resolve(kb("m1", null, null, null)).summaryEnabled()).isTrue();
        }

        @Test
        @DisplayName("⭐⭐ 没配摘要模型 ⇒ 仍然派发（对齐源码：模型缺失在执行期处置，不在派发期）")
        void dispatchedWithoutModel() {
            KbPostProcessConfig c = KbPostProcessConfig.resolve(kb("", null, null, null));
            // 源码 knowledge_post_process.go:216 `eff.SummaryEnabled && len(textChunks) > 0`，
            // SummaryEnabled 恒 true（ResolveProcessConfig:43）⇒ 不看模型。
            // 模型缺失由 knowledge_process.go:1521-1527 在**任务执行时**标 failed + no_summary_model。
            //
            // ⚠️ 反面（本仓曾经的写法）：派发期就判「无模型 ⇒ 不派发」⇒ 状态永远停在 none，
            //    界面永远「尚未生成」，且没有任何错误、查不到原因 —— **静默失效**。
            assertThat(c.summaryEnabled())
                .as("派发条件只表达「要不要做这个环节」，不是「能不能跑」")
                .isTrue();
            assertThat(c.summaryModelId()).isEmpty();
        }

        @Test
        @DisplayName("null 知识库 ⇒ 全关（不是抛异常）")
        void nullKb() {
            assertThat(KbPostProcessConfig.resolve(null)).isEqualTo(KbPostProcessConfig.NONE);
        }
    }

    @Nested
    @DisplayName("问题生成")
    class Question {

        @Test
        @DisplayName("开启 + 有嵌入模型 ⇒ 可执行")
        void runnable() {
            KbPostProcessConfig c = KbPostProcessConfig.resolve(
                kb("m1", "{\"enabled\":true,\"question_count\":5}", null, null));
            assertThat(c.question().enabled()).isTrue();
            assertThat(c.question().questionCount()).isEqualTo(5);
            assertThat(c.question().runnable()).isTrue();
        }

        @Test
        @DisplayName("⭐⭐ 没配嵌入模型但索引策略开着 ⇒ 仍可执行（对齐源站 NeedsEmbeddingModel）")
        void noEmbeddingModelStillRunnable() {
            com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord r =
                kb("m1", "{\"enabled\":true}", null, null);
            r.setEmbeddingModelId(null);
            // 源站判据 = IndexingStrategy.NeedsEmbedding() = VectorEnabled || KeywordEnabled
            //   （knowledge_post_process.go:217 / indexing_strategy.go:34-36）
            // ⛔ 不是「embedding_model_id 非空」—— 2026-11-12 实测这个偏离会让
            //    「索引策略全开、只是没配嵌入模型」的知识库点辅助召回时静默跳过。
            assertThat(KbPostProcessConfig.resolve(r).question().runnable())
                .as("索引策略要求嵌入 ≠ 必须有 embedding_model_id")
                .isTrue();
        }

        @Test
        @DisplayName("⭐⭐ 索引策略全关 ⇒ 不可执行（问句无处可写）")
        void noIndexingNoRunnable() {
            com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord r =
                kb("m1", "{\"enabled\":true}", null, null);
            r.setIndexingStrategy("{\"vector_enabled\":false,\"keyword_enabled\":false}");
            assertThat(KbPostProcessConfig.resolve(r).question().runnable()).isFalse();
        }

        @Test
        @DisplayName("没写模型 ⇒ 继承摘要模型")
        void inheritsSummaryModel() {
            KbPostProcessConfig c = KbPostProcessConfig.resolve(
                kb("summary-m", "{\"enabled\":true}", null, null));
            assertThat(c.question().modelId()).isEqualTo("summary-m");
        }

        @Test
        @DisplayName("question_count 越界被夹到 1..10")
        void clampsCount() {
            assertThat(KbPostProcessConfig.resolve(
                kb("m", "{\"enabled\":true,\"question_count\":99}", null, null))
                .question().questionCount()).isEqualTo(10);
            assertThat(KbPostProcessConfig.resolve(
                kb("m", "{\"enabled\":true,\"question_count\":0}", null, null))
                .question().questionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("缺 question_count ⇒ 默认 3")
        void defaultCount() {
            assertThat(KbPostProcessConfig.resolve(
                kb("m", "{\"enabled\":true}", null, null)).question().questionCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("配置不是合法 JSON ⇒ 按未配置处理（不让整个入库失败）")
        void badJsonFailsSoft() {
            KbPostProcessConfig c = KbPostProcessConfig.resolve(kb("m", "{坏", null, null));
            assertThat(c.question().enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("自动打标")
    class AutoTagging {

        @Test
        @DisplayName("开启 + 继承模型 ⇒ 可执行")
        void runnable() {
            KbPostProcessConfig c = KbPostProcessConfig.resolve(
                kb("m1", null, "{\"enabled\":true}", null));
            assertThat(c.autoTag().runnable()).isTrue();
            assertThat(c.autoTag().modelId()).isEqualTo("m1");
        }

        @Test
        @DisplayName("⭐⭐ skip_if_tagged 缺省为 true（源侧 *bool 语义：null ⇒ true）")
        void skipIfTaggedDefaultsTrue() {
            assertThat(KbPostProcessConfig.resolve(
                kb("m", null, "{\"enabled\":true}", null)).autoTag().skipIfTagged()).isTrue();
            assertThat(KbPostProcessConfig.resolve(
                kb("m", null, "{\"enabled\":true,\"skip_if_tagged\":false}", null))
                .autoTag().skipIfTagged()).isFalse();
        }

        @Test
        @DisplayName("没配 auto_tag_config ⇒ 关闭")
        void disabledByDefault() {
            assertThat(KbPostProcessConfig.resolve(kb("m", null, null, null)).autoTag().enabled())
                .isFalse();
        }

        @Test
        @DisplayName("max_tags 越界被夹到 1..10")
        void clampsMaxTags() {
            assertThat(KbPostProcessConfig.resolve(
                kb("m", null, "{\"enabled\":true,\"max_tags\":50}", null)).autoTag().maxTags())
                .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Wiki")
    class Wiki {

        @Test
        @DisplayName("⭐⭐ auto_generate 缺省为 true（老库没写这个字段，不能被静默关掉）")
        void autoGenerateDefaultsTrue() {
            assertThat(KbPostProcessConfig.resolve(kb("m", null, null, null)).wikiEnabled()).isTrue();
            assertThat(KbPostProcessConfig.resolve(kb("m", null, null, "{}")).wikiEnabled()).isTrue();
        }

        @Test
        @DisplayName("⭐⭐ 开关开着但没模型 ⇒ 不可执行（否则派一个必然 skip 的任务，状态闪一下再回来）")
        void needsModel() {
            assertThat(KbPostProcessConfig.resolve(kb("", null, null, null)).wikiEnabled()).isFalse();
            assertThat(KbPostProcessConfig.resolve(kb("", null, null, "{}")).wikiEnabled()).isFalse();
        }

        @Test
        @DisplayName("显式 false ⇒ 关闭")
        void explicitFalse() {
            assertThat(KbPostProcessConfig.resolve(
                kb("m", null, null, "{\"auto_generate\":false}")).wikiEnabled()).isFalse();
        }
    }
}

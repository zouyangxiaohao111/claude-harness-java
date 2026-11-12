package com.nexusai.kb.app.ingest;

import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbPostProcessStage#plan} 测试 · 批次 W6。
 *
 * <p><b>⭐ 这一组测试守的是「槽位账」</b>：派发清单里的 {@code countsSlot}
 * 必须与真实占槽数一致 —— 算多了文档永久卡在「处理中」，算少了提前显示「已完成」。
 */
class KbPostProcessStagePlanTest {

    private final KbPostProcessStage stage = new KbPostProcessStage(null, null, null);

    private static KbKnowledgeRecord knowledge() {
        KbKnowledgeRecord k = new KbKnowledgeRecord();
        k.setId("k1");
        k.setKnowledgeBaseId("kb1");
        k.setParseAttempt(1);
        k.setParseStatus("processing");
        return k;
    }

    private static KbChunkRecord chunk(String id, String content) {
        KbChunkRecord c = new KbChunkRecord();
        c.setId(id);
        c.setContent(content);
        c.setChunkType("text");
        c.setIsEnabled(1);
        return c;
    }

    private static List<KbChunkRecord> chunks(int n) {
        List<KbChunkRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(chunk("c" + i, "这是第 " + i + " 段正文，内容足够长用于通过可抽取文本判定。"));
        }
        return out;
    }

    /** 图片理解关（大多数用例不关心它）。 */
    private static final KbPostProcessConfig.ImageUnderstanding IMG_OFF =
        new KbPostProcessConfig.ImageUnderstanding(false, "");

    private static KbPostProcessConfig cfg(boolean summary, boolean question, boolean wiki, boolean tag) {
        return new KbPostProcessConfig(
            summary, summary ? "m" : "",
            // 第 5 参是 indexingReady（索引策略是否要求嵌入），⛔ 不是 embedding_model_id
            new KbPostProcessConfig.QuestionGeneration(question, "m", 3, "", true),
            new KbPostProcessConfig.AutoTag(tag, "m", 5, true), wiki, wiki ? "m" : "", false, IMG_OFF);
    }

    @Nested
    @DisplayName("全关 / 全开")
    class Extremes {

        @Test
        @DisplayName("全关 ⇒ 空清单（文档直接 completed）")
        void allOff() {
            assertThat(stage.plan(knowledge(), cfg(false, false, false, false), chunks(3))).isEmpty();
        }

        @Test
        @DisplayName("⭐ 全开 3 块 ⇒ 摘要1 + 问题1批 + Wiki1 + 打标1 = 4 条，占槽 3")
        void allOn() {
            List<KbPostProcessStage.Planned> plan =
                stage.plan(knowledge(), cfg(true, true, true, true), chunks(3));
            assertThat(plan).hasSize(4);
            assertThat(plan.stream().filter(KbPostProcessStage.Planned::countsSlot).count()).isEqualTo(3);
        }

        @Test
        @DisplayName("⭐⭐ 自动打标不占槽位（失败不能把文档卡在 finalizing）")
        void autoTagDoesNotCount() {
            List<KbPostProcessStage.Planned> plan =
                stage.plan(knowledge(), cfg(false, false, false, true), chunks(1));
            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).taskType()).isEqualTo(KbIngestTaskRecord.TYPE_AUTO_TAG);
            assertThat(plan.get(0).countsSlot()).isFalse();
        }

        @Test
        @DisplayName("没有分块 ⇒ 空清单（不派发任何任务）")
        void noChunks() {
            assertThat(stage.plan(knowledge(), cfg(true, true, true, true), List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("问题生成分批")
    class QuestionBatches {

        @Test
        @DisplayName("20 块 ⇒ 恰好 1 批")
        void exactlyOneBatch() {
            assertThat(stage.plan(knowledge(), cfg(false, true, false, false), chunks(20)))
                .hasSize(1);
        }

        @Test
        @DisplayName("21 块 ⇒ 2 批（ceil(21/20)）")
        void twoBatches() {
            assertThat(stage.plan(knowledge(), cfg(false, true, false, false), chunks(21)))
                .hasSize(2);
        }

        @Test
        @DisplayName("⭐⭐ 只有图片的块不进问题生成（模型对 URL 编造问句）")
        void imageOnlyChunksSkipped() {
            List<KbChunkRecord> list = new ArrayList<>(chunks(2));
            for (int i = 0; i < 25; i++) {
                list.add(chunk("img" + i, "![image](http://x/p" + i + ".png)"));
            }
            assertThat(stage.plan(knowledge(), cfg(false, true, false, false), list)).hasSize(1);
        }

        @Test
        @DisplayName("每批 payload 带 batch_index / chunk_ids / question_count")
        void payloadShape() {
            String payload = stage.plan(knowledge(), cfg(false, true, false, false), chunks(1))
                .get(0).payload();
            assertThat(payload).contains("\"batch_index\":0")
                .contains("\"chunk_ids\":[\"c0\"]")
                .contains("\"question_count\":3");
        }
    }

    @Nested
    @DisplayName("图谱派发")
    class GraphPlan {

        private static KbPostProcessConfig withGraph(boolean graph) {
            return new KbPostProcessConfig(
                false, "m",
                new KbPostProcessConfig.QuestionGeneration(false, "m", 3, "", true),
                new KbPostProcessConfig.AutoTag(false, "m", 5, true),
                false, "", graph, IMG_OFF);
        }

        @Test
        @DisplayName("图谱开 ⇒ 1 条占 1 槽，payload 带模型")
        void graphSlot() {
            List<KbPostProcessStage.Planned> plan =
                stage.plan(knowledge(), withGraph(true), chunks(2));
            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).taskType()).isEqualTo(KbIngestTaskRecord.TYPE_GRAPH);
            assertThat(plan.get(0).countsSlot()).isTrue();
            assertThat(plan.get(0).payload()).contains("\"model\":\"m\"");
        }

        @Test
        @DisplayName("⭐ 每篇文档只派 1 个图谱任务（⛔ 不按块拆）")
        void oneTaskPerDocument() {
            assertThat(stage.plan(knowledge(), withGraph(true), chunks(50))).hasSize(1);
        }

        @Test
        @DisplayName("图谱关 ⇒ 不派发")
        void graphOff() {
            assertThat(stage.plan(knowledge(), withGraph(false), chunks(2))).isEmpty();
        }
    }

    @Nested
    @DisplayName("条件组合")
    class Combinations {

        @Test
        @DisplayName("只开摘要 ⇒ 1 条占 1 槽")
        void summaryOnly() {
            List<KbPostProcessStage.Planned> plan =
                stage.plan(knowledge(), cfg(true, false, false, false), chunks(1));
            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).taskType()).isEqualTo(KbIngestTaskRecord.TYPE_SUMMARY);
            assertThat(plan.get(0).countsSlot()).isTrue();
        }

        @Test
        @DisplayName("问题生成开着但索引策略全关 ⇒ 不派发")
        void questionWithoutEmbedding() {
            KbPostProcessConfig c = new KbPostProcessConfig(
                false, "",
                // indexingReady=false 等价于「vector_enabled 与 keyword_enabled 都关」
                new KbPostProcessConfig.QuestionGeneration(true, "m", 3, "", false),
                new KbPostProcessConfig.AutoTag(false, "", 5, true), false, "", false, IMG_OFF);
            assertThat(stage.plan(knowledge(), c, chunks(5))).isEmpty();
        }

        @Test
        @DisplayName("Wiki 开着 ⇒ 1 条占 1 槽，payload 带模型名")
        void wikiSlot() {
            List<KbPostProcessStage.Planned> plan =
                stage.plan(knowledge(), cfg(true, false, true, false), chunks(1));
            assertThat(plan).hasSize(2);
            KbPostProcessStage.Planned wiki = plan.stream()
                .filter(p -> KbIngestTaskRecord.TYPE_WIKI.equals(p.taskType()))
                .findFirst().orElseThrow();
            assertThat(wiki.countsSlot()).isTrue();
            assertThat(wiki.payload()).contains("\"model\":\"m\"");
        }
    }
}

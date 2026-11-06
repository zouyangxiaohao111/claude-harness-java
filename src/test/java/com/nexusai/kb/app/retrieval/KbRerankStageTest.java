package com.nexusai.kb.app.retrieval;

import com.nexusai.application.rerank.RerankService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排阶段 · 降级语义。
 *
 * <p><b>WHY</b>：重排是「第二阶段精排」，不是必需品。它的失效方式全是**降级**而非报错：
 * 没模型、服务没装配、调用抛异常、返回空 —— 都必须安静地回到 RRF 顺序。
 * 若写成抛异常，一次重排服务抖动就会让整个检索失败（用户搜不到任何东西）。
 *
 * <p>⚠️ 本类<b>不验证排序质量</b>（那需要真重排模型）；只验「失败时不破坏结果」。
 */
@DisplayName("[B4] 重排降级语义")
class KbRerankStageTest {

    private static KbHybridSearcher.Hit hit(String id, String content) {
        return new KbHybridSearcher.Hit(id, "k1", content, "", 0.5, 1, 1);
    }

    private static final List<KbHybridSearcher.Hit> THREE = List.of(
        hit("a", "甲内容"), hit("b", "乙内容"), hit("c", "丙内容"));

    @Nested
    @DisplayName("不重排的情况")
    class Passthrough {

        @Test
        @DisplayName("未指定重排模型 ⇒ 原样返回（顺序不变）")
        void noModelPassesThrough() {
            KbRerankStage stage = new KbRerankStage(null);
            assertThat(stage.rerank("查询", THREE, null, null))
                .extracting(KbHybridSearcher.Hit::chunkId)
                .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("重排模型 ID 是空白串 ⇒ 也视为未配置")
        void blankModelPassesThrough() {
            KbRerankStage stage = new KbRerankStage(null);
            assertThat(stage.rerank("查询", THREE, "   ", null)).hasSize(3);
        }

        @Test
        @DisplayName("空候选 ⇒ 空列表")
        void emptyHits() {
            KbRerankStage stage = new KbRerankStage(null);
            assertThat(stage.rerank("查询", List.of(), "model-x", null)).isEmpty();
            assertThat(stage.rerank("查询", null, "model-x", null)).isEmpty();
        }

        @Test
        @DisplayName("⭐ 重排服务未装配（null）⇒ 原样返回，不 NPE")
        void nullServicePassesThrough() {
            KbRerankStage stage = new KbRerankStage(null);
            assertThat(stage.rerank("查询", THREE, "model-x", null))
                .as("精简部署可能没有重排服务 —— 不能因此崩掉检索")
                .hasSize(3);
        }
    }

    @Nested
    @DisplayName("⭐ 失败一律回落 RRF 顺序")
    class Degrades {

        @Test
        @DisplayName("重排调用抛异常 ⇒ 回落原顺序（一次抖动不该让检索空手而归）")
        void exceptionDegrades() {
            // RerankService 的 resolver 传 null ⇒ 任何 rerank() 调用都会 NPE，
            // 正好用来模拟「重排服务不可用」。
            RerankService brokenService = new RerankService(null, List.of());
            KbRerankStage stage = new KbRerankStage(brokenService);

            List<KbHybridSearcher.Hit> out = stage.rerank("查询", THREE, "model-x", 2);

            assertThat(out)
                .as("⭐ 重排失败必须回落原列表，而不是抛异常或返回空")
                .extracting(KbHybridSearcher.Hit::chunkId)
                .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("回落时不受 topN 影响（原列表原样返回）")
        void degradeIgnoresTopN() {
            RerankService brokenService = new RerankService(null, List.of());
            KbRerankStage stage = new KbRerankStage(brokenService);
            assertThat(stage.rerank("查询", THREE, "model-x", 1)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("[QA 对齐] 重排分数阈值（源侧 rerank_threshold）")
    class Threshold {

        /** 造一个「按给定相关性分返回全部候选」的重排服务。 */
        private static KbRerankStage stageReturning(double... scores) {
            RerankService svc = org.mockito.Mockito.mock(RerankService.class);
            List<com.nexusai.model.provider.dto.RerankItem> items =
                new java.util.ArrayList<>(scores.length);
            for (int i = 0; i < scores.length; i++) {
                items.add(new com.nexusai.model.provider.dto.RerankItem(i, scores[i], null));
            }
            org.mockito.Mockito.when(svc.rerank(
                    org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.any(),
                    org.mockito.Mockito.any(),
                    org.mockito.Mockito.any(),
                    org.mockito.Mockito.any()))
                .thenReturn(new com.nexusai.model.provider.dto.RerankResponse(
                    "model-x", items.size(), items, 0L));
            return new KbRerankStage(svc);
        }

        @Test
        @DisplayName("低于阈值的候选被丢弃（不是留到最后被 topN 截断）")
        void dropsBelowThreshold() {
            KbRerankStage stage = stageReturning(0.9, 0.5, 0.1);
            List<KbHybridSearcher.Hit> out = stage.rerank("查询", THREE, "model-x", null, 0.3);
            assertThat(out).extracting(KbHybridSearcher.Hit::chunkId)
                .as("0.1 < 0.3 ⇒ 只剩前两条")
                .containsExactly("a", "b");
        }

        @Test
        @DisplayName("⭐ 全部低于阈值 ⇒ 返回空（⛔ 不回落原候选，否则阈值形同虚设）")
        void allBelowThresholdYieldsEmpty() {
            KbRerankStage stage = stageReturning(0.1, 0.05, 0.2);
            List<KbHybridSearcher.Hit> out = stage.rerank("查询", THREE, "model-x", null, 0.5);
            assertThat(out)
                .as("回落原候选会让 rerank_threshold 完全失去意义")
                .isEmpty();
        }

        @Test
        @DisplayName("阈值 0 = 不过滤（源侧语义），条数不变")
        void zeroThresholdKeepsAll() {
            KbRerankStage stage = stageReturning(0.9, 0.5, 0.1);
            assertThat(stage.rerank("查询", THREE, "model-x", null, 0.0)).hasSize(3);
        }

        @Test
        @DisplayName("等于阈值 ⇒ 保留（判据是 < 而不是 <=）")
        void boundaryIsInclusive() {
            KbRerankStage stage = stageReturning(0.3, 0.29);
            List<KbHybridSearcher.Hit> out = stage.rerank("查询", THREE.subList(0, 2),
                "model-x", null, 0.3);
            assertThat(out).extracting(KbHybridSearcher.Hit::chunkId).containsExactly("a");
        }
    }
}

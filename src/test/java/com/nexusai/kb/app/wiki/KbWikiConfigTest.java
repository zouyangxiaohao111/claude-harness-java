package com.nexusai.kb.app.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki 配置 · W2-1。
 *
 * <p><b>WHY</b>：这个类只有十几个字段，但 {@code auto_generate} 的默认值写错
 * 会让<b>所有老知识库静默停止生成 Wiki</b>（不报错、只是不再产出页面）。
 */
@DisplayName("[W2] Wiki 配置")
class KbWikiConfigTest {

    @Nested
    @DisplayName("⭐⭐ auto_generate 三态")
    class AutoGenerate {

        @Test
        @DisplayName("⭐⭐ 字段缺失 ⇒ true（老配置没这个键也要照常生成）")
        void missingMeansTrue() {
            KbWikiConfig cfg = KbWikiConfig.parse("{\"max_pages_per_ingest\": 10}");
            assertThat(cfg.getAutoGenerate()).isNull();
            assertThat(cfg.isAutoGenerateEnabled())
                .as("⭐⭐ 若这里判 false：升级后所有老库永久不再生成 Wiki，且不报错 —— "
                    + "用户只会觉得「新功能没生效」")
                .isTrue();
        }

        @Test
        @DisplayName("⭐⭐ 整个 JSON 为 null / 空串 ⇒ true")
        void nullJsonMeansTrue() {
            assertThat(KbWikiConfig.parse(null).isAutoGenerateEnabled()).isTrue();
            assertThat(KbWikiConfig.parse("").isAutoGenerateEnabled()).isTrue();
            assertThat(KbWikiConfig.parse("   ").isAutoGenerateEnabled()).isTrue();
        }

        @Test
        @DisplayName("显式 false ⇒ false（用户明确关掉）")
        void explicitFalseStaysFalse() {
            assertThat(KbWikiConfig.parse("{\"auto_generate\": false}").isAutoGenerateEnabled())
                .as("显式关闭必须生效，否则变成「关不掉」")
                .isFalse();
        }

        @Test
        @DisplayName("显式 true ⇒ true")
        void explicitTrue() {
            assertThat(KbWikiConfig.parse("{\"auto_generate\": true}").isAutoGenerateEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("数值字段 0 = 用默认值")
    class NumericDefaults {

        @Test
        @DisplayName("batch_size 未设 / 0 / 负数 ⇒ 5（对齐源侧 wikiMaxDocsPerBatch）")
        void batchSizeDefault() {
            assertThat(new KbWikiConfig().effectiveBatchSize()).isEqualTo(5);
            assertThat(KbWikiConfig.parse("{\"ingest_batch_size\": 0}").effectiveBatchSize()).isEqualTo(5);
            assertThat(KbWikiConfig.parse("{\"ingest_batch_size\": -3}").effectiveBatchSize()).isEqualTo(5);
            assertThat(KbWikiConfig.parse("{\"ingest_batch_size\": 12}").effectiveBatchSize()).isEqualTo(12);
        }

        @Test
        @DisplayName("⭐ max_pages_per_ingest 未设 / 0 ⇒ 不限（不是 0 页）")
        void maxPagesDefault() {
            assertThat(new KbWikiConfig().effectiveMaxPagesPerIngest())
                .as("⭐ 0 表示「不限」。若当成 0 页，Wiki 会一条都不生成")
                .isEqualTo(Integer.MAX_VALUE);
            assertThat(KbWikiConfig.parse("{\"max_pages_per_ingest\": 0}")
                .effectiveMaxPagesPerIngest()).isEqualTo(Integer.MAX_VALUE);
            assertThat(KbWikiConfig.parse("{\"max_pages_per_ingest\": 7}")
                .effectiveMaxPagesPerIngest()).isEqualTo(7);
        }

        @Test
        @DisplayName("并发参数未设 ⇒ 用调用方 fallback")
        void parallelFallback() {
            KbWikiConfig cfg = new KbWikiConfig();
            assertThat(cfg.effectiveMapParallel(3)).isEqualTo(3);
            assertThat(cfg.effectiveReduceParallel(3)).isEqualTo(3);
            assertThat(cfg.effectiveMaxInflight(1)).isEqualTo(1);
            assertThat(KbWikiConfig.parse("{\"ingest_map_parallel\": 8}").effectiveMapParallel(3))
                .isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("粒度与容错")
    class GranularityAndParsing {

        @Test
        @DisplayName("非法粒度 ⇒ standard（⛔ 不抛异常）")
        void invalidGranularity() {
            assertThat(new KbWikiConfig().effectiveGranularity()).isEqualTo("standard");
            assertThat(KbWikiConfig.parse("{\"extraction_granularity\": \"wholesale\"}")
                .effectiveGranularity()).isEqualTo("standard");
            assertThat(KbWikiConfig.parse("{\"extraction_granularity\": \"FOCUSED\"}")
                .effectiveGranularity())
                .as("大小写必须归一")
                .isEqualTo("focused");
            assertThat(KbWikiConfig.parse("{\"extraction_granularity\": \"exhaustive\"}")
                .effectiveGranularity()).isEqualTo("exhaustive");
        }

        @Test
        @DisplayName("⭐ 坏 JSON ⇒ 默认配置（不是抛异常）")
        void brokenJsonFailsSoft() {
            assertThat(KbWikiConfig.parse("{not json").isAutoGenerateEnabled())
                .as("⭐ 一份写坏的配置不该让整个知识库的 Wiki 功能永久 500 —— "
                    + "用户连「怎么修」的入口都进不去")
                .isTrue();
            assertThat(KbWikiConfig.parse("[1,2,3]").isAutoGenerateEnabled()).isTrue();
        }

        @Test
        @DisplayName("未知字段忽略（向后兼容）")
        void unknownFieldsIgnored() {
            KbWikiConfig cfg = KbWikiConfig.parse(
                "{\"auto_generate\": false, \"future_field\": \"x\"}");
            assertThat(cfg.isAutoGenerateEnabled()).isFalse();
        }
    }
}

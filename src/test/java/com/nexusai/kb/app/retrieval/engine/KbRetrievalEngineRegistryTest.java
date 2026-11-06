package com.nexusai.kb.app.retrieval.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索驱动注册表 · W1。
 *
 * <p><b>WHY 这些断言</b>：注册表本身只有几十行，但它决定「用户以为数据在哪」。
 * 最致命的一类缺陷是<b>静默回落</b>：
 * <ul>
 *   <li>用户把知识库配成 {@code postgres} ⇒ 实际写进了本地 sqlite；</li>
 *   <li>日志全绿、检索也能用（因为回落到了 sqlite 里的旧数据）；</li>
 *   <li>直到换机器 / 重装才发现向量全丢。</li>
 * </ul>
 * ⇒ 「未注册必须抛」这条是本类唯一真正的价值，必须钉死。
 */
@DisplayName("[W1] 检索驱动注册表")
class KbRetrievalEngineRegistryTest {

    /** 最小假驱动：可控 available。 */
    private static KbRetrievalEngine fake(String type, boolean available) {
        return new KbRetrievalEngine() {
            @Override public String engineType() { return type; }
            @Override public boolean available() { return available; }
            @Override public boolean supports(Feature feature) { return true; }
            @Override public void index(String kbId, List<IndexDocument> docs, String modelId) { }
            @Override public void deleteByKnowledgeIds(String kbId, List<String> ids) { }
            @Override public List<Hit> retrieve(RetrieveParams params) { return List.of(); }
        };
    }

    @Nested
    @DisplayName("⭐⭐ 未注册 / 不可用必须 fail-loud")
    class FailLoud {

        @Test
        @DisplayName("⭐⭐ 未注册的驱动 ⇒ 抛异常且列出可用驱动（⛔ 不静默回落 sqlite）")
        void unknownEngineThrows() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(
                List.of(fake("sqlite", true)));

            assertThatThrownBy(() -> registry.require("postgres"))
                .as("⭐⭐ 静默回落会让用户以为数据在 pgvector，实际全在本地 sqlite —— "
                    + "日志全绿，换机器才发现全丢")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("sqlite");   // 必须列出可用驱动
        }

        @Test
        @DisplayName("⭐⭐ 已注册但本机不可用 ⇒ 抛异常（不是「悄悄降级」）")
        void unavailableEngineThrows() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(
                List.of(fake("sqlite", true), fake("milvus", false)));

            assertThatThrownBy(() -> registry.require("milvus"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可用");
        }

        @Test
        @DisplayName("可用驱动正常返回")
        void availableEngineReturned() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(
                List.of(fake("sqlite", true)));
            assertThat(registry.require("sqlite").engineType()).isEqualTo("sqlite");
        }

        @Test
        @DisplayName("null / 空 驱动类型 ⇒ 用默认 sqlite")
        void blankFallsBackToDefault() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(
                List.of(fake("sqlite", true)));
            assertThat(registry.require(null).engineType())
                .as("用户裁定「我们默认用 sqlite」").isEqualTo(KbRetrievalEngineRegistry.DEFAULT_ENGINE);
            assertThat(registry.require("  ").engineType()).isEqualTo("sqlite");
        }
    }

    @Nested
    @DisplayName("清单")
    class Lists {

        @Test
        @DisplayName("registered 含未接入的；available 只含真能用的")
        void registeredVsAvailable() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(
                List.of(fake("sqlite", true), fake("qdrant", false)));

            assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("sqlite", "qdrant");
            assertThat(registry.availableTypes())
                .as("⭐ 界面要能区分「已声明」与「真能用」—— 只显示前者会让用户以为可选")
                .containsExactly("sqlite");
        }

        @Test
        @DisplayName("空实现集合不崩（容器里一个驱动都没有的极端情况）")
        void emptyRegistry() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(List.of());
            assertThat(registry.registeredTypes()).isEmpty();
            assertThat(registry.availableTypes()).isEmpty();
            assertThatThrownBy(() -> registry.require("sqlite"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("null 入参不 NPE")
        void nullSafe() {
            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(null);
            assertThat(registry.registeredTypes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("真实驱动清单")
    class RealEngines {

        @Test
        @DisplayName("⭐ 9 个源侧驱动都已声明（即使未接入也要能被列出）")
        void allNineDeclared() {
            // 用反射拿静态内部类实例，验证「声明齐全」而不只是「sqlite 能跑」
            List<KbRetrievalEngine> all = List.of(
                new KbUnavailableEngines.Postgres(),
                new KbUnavailableEngines.Elasticsearch(),
                new KbUnavailableEngines.OpenSearch(),
                new KbUnavailableEngines.Qdrant(),
                new KbUnavailableEngines.Milvus(),
                new KbUnavailableEngines.Weaviate(),
                new KbUnavailableEngines.Doris(),
                new KbUnavailableEngines.TencentVectorDb());

            KbRetrievalEngineRegistry registry = new KbRetrievalEngineRegistry(all);

            assertThat(registry.registeredTypes()).containsExactlyInAnyOrder(
                "postgres", "elasticsearch", "opensearch", "qdrant",
                "milvus", "weaviate", "doris", "tencent_vectordb");
            assertThat(registry.availableTypes())
                .as("⭐⭐ 本机无任何外部向量库环境 ⇒ 必须全部报不可用，"
                    + "⛔ 不能假装可用（那会让界面显示「已支持」）")
                .isEmpty();
        }

        @Test
        @DisplayName("未接入驱动调用任何方法都抛（防御性 fail-loud）")
        void unavailableThrows() {
            KbRetrievalEngine p = new KbUnavailableEngines.Postgres();
            assertThat(p.available()).isFalse();
            assertThatThrownBy(() -> p.retrieve(
                new KbRetrievalEngine.RetrieveParams("kb", "q", null, null, 10, false, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未接入");
            assertThatThrownBy(() -> p.index("kb", List.of(), null))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> p.deleteByKnowledgeIds("kb", List.of("k1")))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("检索参数")
    class Params {

        @Test
        @DisplayName("两路都关 ⇒ bothDisabled（调用方应提前 400）")
        void bothDisabledDetected() {
            assertThat(new KbRetrievalEngine.RetrieveParams(
                "kb", "q", null, null, 10, true, true).bothDisabled()).isTrue();
            assertThat(new KbRetrievalEngine.RetrieveParams(
                "kb", "q", null, null, 10, true, false).bothDisabled()).isFalse();
        }
    }
}

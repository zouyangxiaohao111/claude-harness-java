package com.nexusai.kb.app.qa;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.UsageCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询改写 · 解析与降级（不连真模型）。
 *
 * <p><b>WHY</b>：改写链路的失效方式全是「静默降级」，而每种降级的<b>表现</b>必须明确：
 * <ul>
 *   <li>模型没配 ⇒ 用原问题（不是空查询 —— 空查询会让检索返回空，
 *       用户看到「没找到相关内容」，但真实原因是链路挂了）</li>
 *   <li>模型返回 markdown 围栏 / 前后有废话 ⇒ 要能抠出 JSON（真实模型经常这样）</li>
 *   <li>模型返回未知意图 ⇒ fail-open 到 kb_search</li>
 *   <li>rewrite_query 为空 ⇒ 用原问题</li>
 * </ul>
 * <p>⚠️ 本类用假 {@link LlmSideCallClient} 驱动 `<b>解析</b>` 与 `<b>降级</b>` 两条路径；
 * <b>真模型输出形态</b>必须靠真机跑（单测验的只是「我理解的协议」）。
 */
@DisplayName("[B5] 查询改写（解析与降级）")
class KbQueryUnderstandingTest {

    /** 假 side-call：按预设返回，可模拟抛异常。 */
    private static final class FakeSideCall extends LlmSideCallClient {
        String response;
        RuntimeException toThrow;

        @Override
        public String chat(com.nexusai.infra.llm.ProviderConfig config, String modelName,
                           String systemPrompt, String userMessage,
                           com.nexusai.infra.llm.LlmProvider.ChatRequestOptions options,
                           SideCallUsage usage) {
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private FakeSideCall sideCall;
    private KbQueryUnderstanding understanding;

    @BeforeEach
    void setUp() {
        sideCall = new FakeSideCall();
        understanding = new KbQueryUnderstanding(sideCall);
    }

    private static LlmSideCallClient.SideCallUsage usage() {
        return LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, "query_rewrite");
    }

    /** 用非 null config/model 触发真实调用路径（config 只需非 null，假客户端不看内容）。 */
    private KbQueryUnderstanding.Result run(String query) {
        return understanding.understand(null, null, query, List.of(), false, false, "中文", usage());
    }

    @Nested
    @DisplayName("⭐ 降级（永不抛、永不返回空查询）")
    class Degrades {

        @Test
        @DisplayName("模型未配置 ⇒ 原问题 + KB_SEARCH + degraded=true")
        void noModelDegrades() {
            KbQueryUnderstanding.Result r = run("农村污水处理标准是什么");

            assertThat(r.degraded()).isTrue();
            assertThat(r.rewriteQuery())
                .as("⭐ 必须是原问题 —— 空查询会让检索返回空，看起来像「没找到」")
                .isEqualTo("农村污水处理标准是什么");
            assertThat(r.intent()).isEqualTo(KbQueryIntent.KB_SEARCH);
        }

        @Test
        @DisplayName("⭐ 模型调用抛异常 ⇒ 同样回落（一次抖动不该让整轮失败）")
        void modelThrowsDegrades() {
            KbQueryUnderstanding u = new KbQueryUnderstanding(sideCall);
            sideCall.toThrow = new RuntimeException("模拟上游 502");

            KbQueryUnderstanding.Result r = u.understand(
                fakeConfig(), "fake-model", "原问题", List.of(), false, false, "中文", usage());

            assertThat(r.degraded()).isTrue();
            assertThat(r.rewriteQuery()).isEqualTo("原问题");
            assertThat(r.intent()).isEqualTo(KbQueryIntent.KB_SEARCH);
        }

        @Test
        @DisplayName("⭐ 模型输出不是 JSON ⇒ 回落原问题")
        void nonJsonDegrades() {
            sideCall.response = "抱歉，我无法处理这个请求。";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "原问题", List.of(), false, false, "中文", usage());

            assertThat(r.degraded()).isTrue();
            assertThat(r.rewriteQuery()).isEqualTo("原问题");
        }
    }

    @Nested
    @DisplayName("解析容错（真实模型常见形态）")
    class Parsing {

        @Test
        @DisplayName("标准 JSON ⇒ 正常解析")
        void plainJson() {
            sideCall.response = "{\"rewrite_query\":\"农村生活污水处理设施的清掏周期\","
                + "\"intent\":\"kb_search\",\"image_description\":\"\"}";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "多久清掏一次", List.of(), false, false, "中文", usage());

            assertThat(r.degraded()).isFalse();
            assertThat(r.rewriteQuery()).isEqualTo("农村生活污水处理设施的清掏周期");
            assertThat(r.intent()).isEqualTo(KbQueryIntent.KB_SEARCH);
            assertThat(r.needsRetrieval(false)).isTrue();
        }

        @Test
        @DisplayName("⭐ markdown 围栏包裹 ⇒ 仍能抠出 JSON")
        void markdownFenced() {
            sideCall.response = "```json\n{\"rewrite_query\":\"改写后\",\"intent\":\"greeting\","
                + "\"image_description\":\"\"}\n```";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "你好", List.of(), false, false, "中文", usage());

            assertThat(r.degraded()).isFalse();
            assertThat(r.rewriteQuery()).isEqualTo("改写后");
            assertThat(r.intent()).isEqualTo(KbQueryIntent.GREETING);
            assertThat(r.needsRetrieval(false))
                .as("问候不该触发检索")
                .isFalse();
        }

        @Test
        @DisplayName("JSON 前后有解释文字 ⇒ 仍能抠出")
        void jsonWithProse() {
            sideCall.response = "好的，结果如下：{\"rewrite_query\":\"Q2\",\"intent\":\"summarize\"} 以上。";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "总结一下", List.of(), false, false, "中文", usage());

            assertThat(r.degraded()).isFalse();
            assertThat(r.rewriteQuery()).isEqualTo("Q2");
            assertThat(r.intent()).isEqualTo(KbQueryIntent.SUMMARIZE);
        }

        @Test
        @DisplayName("⭐ rewrite_query 为空 ⇒ 用原问题（不返回空查询）")
        void emptyRewriteFallsBack() {
            sideCall.response = "{\"rewrite_query\":\"\",\"intent\":\"kb_search\"}";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "原问题文本", List.of(), false, false, "中文", usage());

            assertThat(r.rewriteQuery())
                .as("⭐ 空改写必须回落原问题，否则检索会空手而归")
                .isEqualTo("原问题文本");
        }

        @Test
        @DisplayName("⭐ 未知意图 ⇒ fail-open 到 KB_SEARCH")
        void unknownIntentFailsOpen() {
            sideCall.response = "{\"rewrite_query\":\"Q\",\"intent\":\"some_new_label\"}";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "问题", List.of(), false, false, "中文", usage());

            assertThat(r.intent()).isEqualTo(KbQueryIntent.KB_SEARCH);
            assertThat(r.needsRetrieval(false)).isTrue();
        }
    }

    @Nested
    @DisplayName("检索查询列表")
    class RetrievalQueries {

        @Test
        @DisplayName("正常返回单条查询")
        void singleQuery() {
            sideCall.response = "{\"rewrite_query\":\"污水处理标准\",\"intent\":\"kb_search\"}";
            KbQueryUnderstanding.Result r = understanding.understand(
                fakeConfig(), "fake-model", "标准是什么", List.of(), false, false, "中文", usage());

            assertThat(r.retrievalQueries()).containsExactly("污水处理标准");
        }

        @Test
        @DisplayName("空查询 ⇒ 空列表（不是含空串的列表）")
        void blankYieldsEmptyList() {
            KbQueryUnderstanding.Result r = understanding.understand(
                null, null, "", List.of(), false, false, "中文", usage());
            assertThat(r.retrievalQueries()).isEmpty();
        }
    }

    /** 构造一个「非 null」的 config 以进入真实写码路径（假客户端不读取其内容）。 */
    private static com.nexusai.infra.llm.ProviderConfig fakeConfig() {
        return new com.nexusai.infra.llm.ProviderConfig(
            "http://localhost", "sk-test", java.util.Map.of(), java.util.Map.of());
    }
}

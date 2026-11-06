package com.nexusai.kb.app.wiki;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki Map 阶段 · W2-4。
 *
 * <p><b>WHY</b>：这一层最容易出的不是崩溃，而是<b>静默产出垃圾</b> ——
 * 扫描件没有文本却硬生成一页编造的实体；模型返回的脏 JSON 被当作「没有候选」；
 * 去重把两个不同事物合并成一个。三者都不报错。
 */
@DisplayName("[W2] Wiki Map 阶段")
class KbWikiMapStageTest {

    /** 假 side call：按 system 提示词内容路由到预设输出，并记录调用次数。 */
    private static class FakeSideCall extends LlmSideCallClient {
        final List<String> calls = new ArrayList<>();
        String candidateRaw = "{\"entities\":[],\"concepts\":[]}";
        String summaryRaw = "SUMMARY: 测试摘要。\n\n## 要点\n- 一\n";
        String citationRaw = "{\"citations\":{},\"new_slugs\":[]}";
        String dedupeRaw = "{\"merges\":[]}";
        boolean failAll = false;

        FakeSideCall() {
            // LlmSideCallClient 没有显式构造器 ⇒ 隐式无参；所有依赖都是字段注入
        }

        @Override
        public String chat(ProviderConfig config, String modelName, String systemPrompt,
                           String userMessage, LlmProvider.ChatRequestOptions options,
                           LlmSideCallClient.SideCallUsage usage) {
            if (failAll) {
                throw new IllegalStateException("模拟模型失败");
            }
            String label = usage == null ? "" : String.valueOf(usage.label());
            calls.add(label);
            // ⚠️ 按系统提示词里的**独有短语**路由（不是「候选」这种泛词 ——
            //    候选抽取提示词里其实没写「候选」二字）
            if (systemPrompt != null && systemPrompt.contains("实质性讨论")) {
                return citationRaw;
            }
            if (systemPrompt != null && systemPrompt.contains("同一个事物")) {
                return dedupeRaw;
            }
            if (systemPrompt != null && systemPrompt.contains("实体抽取器")) {
                return candidateRaw;
            }
            if (systemPrompt != null && systemPrompt.contains("摘要")) {
                return summaryRaw;
            }
            return null;
        }
    }

    private FakeSideCall client;
    private KbWikiMapStage stage;

    @BeforeEach
    void setUp() {
        client = new FakeSideCall();
        stage = new KbWikiMapStage(client);
        // 注入一个总是成功的 resolver
        var field = KbWikiMapStage.class.getDeclaredFields();
        try {
            java.lang.reflect.Field f = KbWikiMapStage.class.getDeclaredField("modelConfigResolver");
            f.setAccessible(true);
            f.set(stage, new com.nexusai.infra.llm.ModelConfigResolver() {
                @Override
                public ResolvedModel resolve(String modelName) {
                    return new ResolvedModel(
                        new ProviderConfig("http://x", "k", Map.of(), Map.of()), "test");
                }
            });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<KbWikiMapStage.CitationChunk> chunks(String... contents) {
        List<KbWikiMapStage.CitationChunk> out = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            out.add(new KbWikiMapStage.CitationChunk(
                String.format("c%03d", i + 1), contents[i]));
        }
        return out;
    }

    @Nested
    @DisplayName("⭐⭐ 空内容守卫（防扫描件幻觉）")
    class EmptyContentGuard {

        @Test
        @DisplayName("⭐⭐ 无文本 ⇒ 跳过，且⛔ 一次模型都不调")
        void skipsWithoutCallingModel() {
            KbWikiMapStage.MapResult r = stage.mapOneDocument(
                "k1", "", List.of(), List.of(), List.of(), "m", "standard", null);

            assertThat(r.skipped()).isTrue();
            assertThat(r.skipReason()).contains("无可用文本");
            assertThat(client.calls)
                .as("⭐⭐ 若这里仍调模型：扫描件只给一张图标记，模型会凭空编造实体 —— "
                    + "用户看到一页完全虚构的内容，而且没有任何报错")
                .isEmpty();
        }

        @Test
        @DisplayName("只有空白 / 只有图片标记 ⇒ 也算无文本")
        void whitespaceOnlySkipped() {
            assertThat(stage.mapOneDocument("k1", "   \n\t  ", List.of(), null, null,
                "m", "standard", null).skipped()).isTrue();
            assertThat(stage.mapOneDocument("k1", "![img](http://x/1.png)", List.of(), null, null,
                "m", "standard", null).skipped())
                .as("只有图片标记没有文字 ⇒ 跳过")
                .isTrue();
        }

        @Test
        @DisplayName("⭐ 恰好 19 个非空白码点 ⇒ 跳过；20 个 ⇒ 处理")
        void thresholdBoundary() {
            assertThat(KbWikiMapStage.hasSufficientText("a".repeat(19))).isFalse();
            assertThat(KbWikiMapStage.hasSufficientText("a".repeat(20))).isTrue();
            assertThat(KbWikiMapStage.hasSufficientText(" a b c d e f g h i j k l m n o p q r s t "))
                .as("空白不计入")
                .isTrue();
            assertThat(KbWikiMapStage.hasSufficientText(null)).isFalse();
        }

        @Test
        @DisplayName("未配置模型 ⇒ 跳过（不是崩溃）")
        void noModelSkips() {
            KbWikiMapStage.MapResult r = stage.mapOneDocument(
                "k1", "足够长的正文内容用于测试这个守卫逻辑是否正常工作",
                List.of(), null, null, null, "standard", null);
            assertThat(r.skipped()).isTrue();
            assertThat(r.skipReason()).contains("未配置");
        }
    }

    @Nested
    @DisplayName("候选解析")
    class CandidateParsing {

        @Test
        @DisplayName("标准 JSON 正常解析")
        void parsesStandard() {
            client.candidateRaw = """
                {"entities":[{"name":"Acme Corp","slug":"entity/acme-corp","aliases":["Acme"],
                  "description":"一家公司","details":"细节"}],
                 "concepts":[{"name":"RAG","slug":"concept/rag","aliases":[],
                  "description":"检索增强","details":""}]}
                """;
            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试解析逻辑是否正确工作",
                List.of(), null, null, "m", "standard", null);
            assertThat(r.candidates()).hasSize(2);
            assertThat(r.candidates().get(0).slug()).isEqualTo("entity/acme-corp");
            assertThat(r.candidates().get(0).aliases()).containsExactly("Acme");
            assertThat(r.candidates().get(1).slug()).isEqualTo("concept/rag");
        }

        @Test
        @DisplayName("⭐ markdown 围栏 / 前后废话都能解析")
        void tolerantParsing() {
            client.candidateRaw = """
                好的，这是结果：
                ```json
                {"entities":[{"name":"A","slug":"entity/a"}],"concepts":[]}
                ```
                希望有帮助。
                """;
            assertThat(KbWikiMapStage.parseCandidates(client.candidateRaw)).hasSize(1);
        }

        @Test
        @DisplayName("⭐⭐ 非法类型 / 无法识别的条目直接丢弃（⛔ 不猜）")
        void dropsInvalidEntries() {
            String raw = "{\"entities\":[{\"name\":\"X\",\"slug\":\"weird/x\"},{\"name\":\"\"}],"
                + "\"concepts\":[]}";
            assertThat(KbWikiMapStage.parseCandidates(raw))
                .as("weird/x 类型非法；空 name 且无 slug ⇒ 都丢")
                .isEmpty();
        }

        @Test
        @DisplayName("⭐ 缺 slug 但有 name ⇒ 用 name 生成")
        void generatesSlugFromName() {
            String raw = "{\"entities\":[{\"name\":\"Acme Corp\"}],\"concepts\":[]}";
            var list = KbWikiMapStage.parseCandidates(raw);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).slug()).isEqualTo("entity/acme-corp");
        }

        @Test
        @DisplayName("⭐ 模型把 concept 放进 entities ⇒ 以 slug 前缀为准")
        void typeFollowsSlugPrefix() {
            String raw = "{\"entities\":[{\"name\":\"RAG\",\"slug\":\"concept/rag\"}],"
                + "\"concepts\":[]}";
            assertThat(KbWikiMapStage.parseCandidates(raw).get(0).type())
                .as("⭐ 若按数组位置定类型，这个条目会被当成实体 —— "
                    + "而实体/概念的去重规则不同（不许跨类型合并）")
                .isEqualTo("concept");
        }

        @Test
        @DisplayName("重复 slug 只保留第一次")
        void dedupesSlugWithinDocument() {
            String raw = "{\"entities\":[{\"name\":\"A\",\"slug\":\"entity/a\"},"
                + "{\"name\":\"A again\",\"slug\":\"entity/a\"}],\"concepts\":[]}";
            assertThat(KbWikiMapStage.parseCandidates(raw)).hasSize(1);
        }

        @Test
        @DisplayName("坏 JSON ⇒ 空列表（⛔ 不抛）")
        void brokenJsonReturnsEmpty() {
            assertThat(KbWikiMapStage.parseCandidates("{not json")).isEmpty();
            assertThat(KbWikiMapStage.parseCandidates(null)).isEmpty();
            assertThat(KbWikiMapStage.parseCandidates("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("引用分类")
    class CitationClassification {

        @Test
        @DisplayName("⭐ 按字符预算切批，单个超长 chunk 独占一批（⛔ 不跳过）")
        void batchesRespectBudget() {
            // 两个 8000 + 一个 20000：
            //   8000+8000=16000 > 12000 ⇒ 各自成批（1、2 分开）
            //   20000 单条超预算 ⇒ 独占第 3 批
            var list = chunks("a".repeat(8000), "b".repeat(8000), "c".repeat(20000));
            var batches = KbWikiMapStage.splitBatches(list, 12000);

            assertThat(batches).hasSize(3);
            assertThat(batches.get(2)).hasSize(1)
                .as("⭐⭐ 超预算的单个 chunk 必须独占一批。若跳过，那个 chunk 里的引用就丢了 —— "
                    + "页面看起来正常，只是没法溯源");
            assertThat(batches.get(2).get(0).content()).hasSize(20000);
        }

        @Test
        @DisplayName("小 chunk 正常合并成一批")
        void batchesMergeSmall() {
            var batches = KbWikiMapStage.splitBatches(chunks("a".repeat(100), "b".repeat(100)), 12000);
            assertThat(batches).hasSize(1);
            assertThat(batches.get(0)).hasSize(2);
        }

        @Test
        @DisplayName("⭐ 引用结果合并到候选上")
        void mergesCitations() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"A\",\"slug\":\"entity/a\"},"
                + "{\"name\":\"B\",\"slug\":\"entity/b\"}],\"concepts\":[]}";
            client.citationRaw = "{\"citations\":{\"entity/a\":[\"c001\",\"c002\"]},\"new_slugs\":[]}";

            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试引用分类逻辑是否正确",
                chunks("甲内容", "乙内容"), null, null, "m", "standard", null);

            var a = r.candidates().stream().filter(c -> c.slug().equals("entity/a")).findFirst().orElseThrow();
            var b = r.candidates().stream().filter(c -> c.slug().equals("entity/b")).findFirst().orElseThrow();
            assertThat(a.chunkIds()).containsExactly("c001", "c002");
            assertThat(b.chunkIds()).as("没被实质性讨论的条目不该有引用").isEmpty();
        }

        @Test
        @DisplayName("⭐ 模型新发现的条目也进结果")
        void includesNewSlugsFromModel() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"A\",\"slug\":\"entity/a\"}],\"concepts\":[]}";
            client.citationRaw = """
                {"citations":{"entity/a":["c001"]},
                 "new_slugs":[{"type":"concept","name":"New Thing","slug":"concept/new-thing",
                               "source_chunks":["c002"]}]}
                """;
            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试新条目发现逻辑是否工作",
                chunks("甲内容", "乙内容"), null, null, "m", "standard", null);

            assertThat(r.candidates()).hasSize(2);
            var added = r.candidates().stream()
                .filter(c -> c.slug().equals("concept/new-thing")).findFirst().orElseThrow();
            assertThat(added.chunkIds()).containsExactly("c002");
        }

        @Test
        @DisplayName("⭐ 某批失败不阻塞其他批（只丢那批的引用）")
        void batchFailureIsolated() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"A\",\"slug\":\"entity/a\"}],\"concepts\":[]}";
            // 让所有调用都返回 null（模拟失败）—— 候选应保留但无引用
            client.citationRaw = null;
            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试批次失败隔离逻辑是否工作",
                chunks("甲内容"), null, null, "m", "standard", null);
            assertThat(r.candidates()).hasSize(1);
            assertThat(r.candidates().get(0).chunkIds()).isEmpty();
            assertThat(r.skipped()).as("引用失败不该让整个文档被跳过").isFalse();
        }
    }

    @Nested
    @DisplayName("⭐⭐ 去重判同")
    class Deduplication {

        @Test
        @DisplayName("⭐⭐ 合并到已有页面 ⇒ slug 换掉、chunk 引用并集")
        void mergesIntoExisting() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"Acme Corp\",\"slug\":\"entity/acme-corp\"}],"
                + "\"concepts\":[]}";
            client.citationRaw = "{\"citations\":{\"entity/acme-corp\":[\"c001\"]},\"new_slugs\":[]}";
            client.dedupeRaw = "{\"merges\":[{\"slug\":\"entity/acme-corp\","
                + "\"into\":\"entity/acme-corporation\",\"reason\":\"缩写\"}]}";

            var existing = List.of(new KbWikiMapStage.ExistingPage(
                "entity/acme-corporation", "Acme Corporation", List.of("Acme")));

            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试去重合并逻辑是否正常工作",
                chunks("甲内容"), existing, null, "m", "standard", null);

            assertThat(r.candidates()).hasSize(1);
            assertThat(r.candidates().get(0).slug())
                .as("⭐⭐ 合并后 slug 必须换成已有页面的 —— 否则会建出两个指向同一事物的页面")
                .isEqualTo("entity/acme-corporation");
            assertThat(r.candidates().get(0).chunkIds())
                .as("chunk 引用必须保留（否则证据丢在合并里）")
                .containsExactly("c001");
        }

        @Test
        @DisplayName("⭐⭐ 合并目标不在该条目自己的候选集内 ⇒ 拒绝")
        void rejectsMergeOutsideCandidates() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"Acme Corp\",\"slug\":\"entity/acme-corp\"}],"
                + "\"concepts\":[]}";
            // 模型试图并到一个「不在该条目 candidates 里」的页面
            client.dedupeRaw = "{\"merges\":[{\"slug\":\"entity/acme-corp\","
                + "\"into\":\"entity/some-other-page\"}]}";
            var existing = List.of(
                new KbWikiMapStage.ExistingPage("entity/acme-corporation", "Acme Corporation", List.of()),
                new KbWikiMapStage.ExistingPage("entity/some-other-page", "Something Else", List.of()));

            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试越界合并拒绝逻辑是否工作",
                List.of(), existing, null, "m", "standard", null);

            assertThat(r.candidates().get(0).slug())
                .as("⭐⭐ 源侧硬约束：只能合并到该条目自己 candidates 里的页面。"
                    + "越界合并会把无关的页面合并掉")
                .isEqualTo("entity/acme-corp");
        }

        @Test
        @DisplayName("⭐ 无反例候选时不做判同（不浪费调用）")
        void noRelatedPagesNoCall() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"Zebra\",\"slug\":\"entity/zebra\"}],"
                + "\"concepts\":[]}";
            var existing = List.of(new KbWikiMapStage.ExistingPage(
                "entity/apple", "Apple", List.of()));

            int before = client.calls.size();
            stage.mapOneDocument("k1", "足够长的正文内容用于测试不相关页面不做判同的逻辑",
                List.of(), existing, null, "m", "standard", null);
            assertThat(client.calls.stream().filter(s -> s.startsWith("wiki_dedup")).count())
                .as("⭐ 完全不同名的页面不该进判同（否则每篇都要烧一次 token）")
                .isZero();
        }

        @Test
        @DisplayName("⭐⭐ 跨类型不许合并（实体 vs 概念）")
        void noCrossTypeMerge() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"RAG\",\"slug\":\"entity/rag\"}],"
                + "\"concepts\":[]}";
            client.dedupeRaw = "{\"merges\":[{\"slug\":\"entity/rag\",\"into\":\"concept/rag\"}]}";
            var existing = List.of(new KbWikiMapStage.ExistingPage(
                "entity/rag", "RAG", List.of()), 
                new KbWikiMapStage.ExistingPage("concept/rag", "RAG", List.of()));

            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试跨类型合并拒绝逻辑是否工作",
                List.of(), existing, null, "m", "standard", null);

            // 同名时实体自己也会进 relatedPages（同类型匹配）⇒ 但 concept/rag 不该在候选里
            assertThat(r.candidates()).allMatch(c -> c.type().equals("entity"));
        }

        @Test
        @DisplayName("空 merges ⇒ 原样返回")
        void emptyMergesKeepsAll() {
            client.candidateRaw = "{\"entities\":[{\"name\":\"Acme\",\"slug\":\"entity/acme\"}],\"concepts\":[]}";
            client.dedupeRaw = "{\"merges\":[]}";
            var existing = List.of(new KbWikiMapStage.ExistingPage(
                "entity/acme-corp", "Acme Corp", List.of()));

            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试空合并结果处理的逻辑是否工作",
                List.of(), existing, null, "m", "standard", null);
            assertThat(r.candidates()).hasSize(1);
            assertThat(r.candidates().get(0).slug()).isEqualTo("entity/acme");
        }
    }

    @Nested
    @DisplayName("⭐⭐ 文件名永不喂给模型")
    class NoFilenameLeak {

        @Test
        @DisplayName("⭐⭐ 接口签名里没有 title / filename 参数")
        void signatureHasNoFilename() {
            var methods = KbWikiMapStage.class.getDeclaredMethods();
            var mapOne = java.util.Arrays.stream(methods)
                .filter(m -> m.getName().equals("mapOneDocument"))
                .findFirst().orElseThrow();

            assertThat(java.util.Arrays.stream(mapOne.getParameters())
                .map(java.lang.reflect.Parameter::getName)
                .toList())
                .as("⭐⭐ 源侧注释明确：文件名会诱发幻觉摘要（扫描件叫 MX5280.pdf）。"
                    + "让参数根本不存在 = 编译期就挡住「不小心传进去」")
                .doesNotContain("title", "filename", "fileName", "docTitle");
        }

        @Test
        @DisplayName("⭐ 摘要页 slug 用 knowledgeId")
        void summarySlugUsesId() {
            client.candidateRaw = "{\"entities\":[],\"concepts\":[]}";
            var r = stage.mapOneDocument("kb-doc-123", "足够长的正文内容用于测试摘要页 slug 生成逻辑对",
                List.of(), null, null, "m", "standard", null);
            assertThat(r.summarySlug()).isEqualTo("summary/kb-doc-123");
        }

        @Test
        @DisplayName("⭐ 发给模型的 prompt 里不含文件名字样")
        void promptHasNoFilename() {
            client.candidateRaw = "{\"entities\":[],\"concepts\":[]}";
            stage.mapOneDocument("k1", "足够长的正文内容用于测试提示词里不含文件名这一约束是否成立",
                List.of(), null, null, "m", "standard", null);
            // 这个测试弱一些：主要验证候选 prompt 里没有 title 占位符
            assertThat(KbWikiPrompts.CANDIDATE_SLUG_USER).doesNotContain("TITLE");
            assertThat(KbWikiPrompts.SUMMARY_USER).doesNotContain("TITLE");
        }
    }

    @Nested
    @DisplayName("降级与容错")
    class Degradation {

        @Test
        @DisplayName("⭐ 摘要失败 ⇒ 用占位摘要，文档不整体失败")
        void summaryFailureDegrades() {
            client.summaryRaw = null;
            client.candidateRaw = "{\"entities\":[{\"name\":\"A\",\"slug\":\"entity/a\"}],\"concepts\":[]}";
            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试摘要失败降级逻辑是否正常工作",
                List.of(), null, null, "m", "standard", null);
            assertThat(r.skipped()).isFalse();
            assertThat(r.summaryContent()).contains("SUMMARY:");
            assertThat(r.candidates()).hasSize(1);
        }

        @Test
        @DisplayName("⭐ 所有模型调用都抛异常 ⇒ 不崩，返回空候选")
        void allCallsFailGracefully() {
            client.failAll = true;
            client.candidateRaw = "{\"entities\":[{\"name\":\"A\",\"slug\":\"entity/a\"}],\"concepts\":[]}";
            // 候选取不到 ⇒ 后续并行也拿不到候选；整体不应抛
            var r = stage.mapOneDocument("k1", "足够长的正文内容用于测试全部调用失败的容错能力是否工作",
                List.of(), null, null, "m", "standard", null);
            assertThat(r.skipped()).isFalse();
            assertThat(r.candidates()).isEmpty();
        }

        @Test
        @DisplayName("⭐ 超长正文按码点截断到 32768")
        void truncatesLongContent() {
            String longText = "中".repeat(40000);
            assertThat(KbWikiMapStage.truncate(longText, 32768).codePointCount(0,
                KbWikiMapStage.truncate(longText, 32768).length())).isEqualTo(32768);
        }

        @Test
        @DisplayName("⭐ 截断不切断代理对（emoji）")
        void truncateKeepsSurrogatePairs() {
            String emoji = "😀".repeat(100);
            String out = KbWikiMapStage.truncate(emoji, 50);
            assertThat(out.codePointCount(0, out.length())).isEqualTo(50);
            assertThat(out).doesNotContain("\uFFFD");
        }
    }
}

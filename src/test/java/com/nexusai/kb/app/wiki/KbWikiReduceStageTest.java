package com.nexusai.kb.app.wiki;

import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki Reduce 阶段 · W2-5。
 *
 * <p><b>WHY</b>：这一层有两类<b>不可恢复</b>的静默损坏：
 * <ol>
 *   <li><b>残页落库</b>：长枚举被截断，一次存下来少三分之一，系统以为已完成；</li>
 *   <li><b>错并</b>：新信息讲的是另一个事物却被并进页面，事实就此污染，且不会再被纠正。</li>
 * </ol>
 * 两类都不报错、都不可自动修复 ⇒ 必须靠「拒绝」而不是「尽量」。本测试守的就是这个。
 */
@DisplayName("[W2] Wiki Reduce 阶段")
class KbWikiReduceStageTest {

    /** 假 side call：可按调用顺序返回 (content, finishReason)。 */
    private static class FakeSideCall extends LlmSideCallClient {
        final Deque<Object[]> responses = new ArrayDeque<>();
        final List<String> labels = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        Object[] fallback = new Object[]{"SUMMARY: 默认。\n\n正文。", "stop"};

        @Override
        public LlmProvider.LlmInvocationResult message(
            ProviderConfig config, String modelName, String systemPrompt, String userMessage,
            LlmProvider.ChatRequestOptions options, LlmSideCallClient.SideCallUsage usage) {
            labels.add(usage == null ? "" : String.valueOf(usage.label()));
            prompts.add((systemPrompt == null ? "" : systemPrompt) + "\n---\n"
                + (userMessage == null ? "" : userMessage));
            Object[] r = responses.isEmpty() ? fallback : responses.poll();
            return new LlmProvider.LlmInvocationResult(
                (String) r[0], (String) r[1], List.of(),
                com.nexusai.application.agent.tool.AgentUsage.EMPTY, "id", null, null, modelName, 1L);
        }
    }

    private FakeSideCall client;
    private KbWikiReduceStage stage;

    @BeforeEach
    void setUp() {
        client = new FakeSideCall();
        stage = new KbWikiReduceStage(client);
        try {
            java.lang.reflect.Field f = KbWikiReduceStage.class.getDeclaredField("modelConfigResolver");
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

    private static KbWikiReduceStage.SlugUpdate add(String knowledgeId, String name, String... chunks) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String c : chunks) {
            texts.put(c, "chunk " + c + " 的正文内容");
        }
        return new KbWikiReduceStage.SlugUpdate(
            knowledgeId, knowledgeId, "文档摘要：" + knowledgeId,
            name, "描述 " + name, "细节", List.of("别名A"),
            List.of(chunks), texts);
    }

    private static KbWikiReduceStage.ExistingWikiPage page(String title, String content) {
        return new KbWikiReduceStage.ExistingWikiPage(
            title, content, "旧摘要", List.of("旧别名"), "entity",
            List.of("doc-old"), List.of("c900"));
    }

    @Nested
    @DisplayName("⭐⭐ 截断必须续写；续写耗尽必须拒绝（⛔ 不存残页）")
    class Truncation {

        @Test
        @DisplayName("⭐⭐ finish_reason=length ⇒ 自动续写并逐字追加")
        void continuesOnLength() {
            client.responses.add(new Object[]{"SUMMARY: 前半。\n\n第一段", "length"});
            client.responses.add(new Object[]{"第二段继续。", "stop"});

            var r = stage.reduceSlug("entity/a", null,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);

            assertThat(r.action()).isEqualTo("created");
            assertThat(r.content())
                .as("⭐⭐ 若直接存第一段：长枚举会少三分之一，而且系统以为已完成 —— 不可恢复")
                .contains("第一段")
                .contains("第二段继续");
            assertThat(client.labels).contains("wiki_page_continue");
        }

        @Test
        @DisplayName("⭐⭐ 3 轮续写后仍截断 ⇒ 拒绝（内容保持旧页，不落残页）")
        void rejectsAfterMaxContinuations() {
            client.responses.add(new Object[]{"SUMMARY: 开头。\n\n第一段", "length"});
            client.responses.add(new Object[]{"续A", "max_tokens"});
            client.responses.add(new Object[]{"续B", "max_output_tokens"});
            client.responses.add(new Object[]{"续C", "length"});

            var existing = page("A", "旧页面完整正文");
            var r = stage.reduceSlug("entity/a", existing,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);

            assertThat(r.action())
                .as("⭐⭐ 宁可保留旧页也不存半个 —— 半页不可恢复，旧页只是「这次没更新」")
                .isEqualTo("rejected");
            assertThat(r.rejectReason()).contains("截断");
            assertThat(r.content())
                .as("拒绝时内容必须是旧页原文")
                .isEqualTo("旧页面完整正文");
        }

        @Test
        @DisplayName("⭐ 续写返回 (complete) ⇒ 立即停止")
        void stopsOnComplete() {
            client.responses.add(new Object[]{"SUMMARY: 写完。\n\n正文", "length"});
            client.responses.add(new Object[]{"(complete)", "stop"});

            var r = stage.reduceSlug("entity/a", null,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);

            assertThat(r.action()).isEqualTo("created");
            assertThat(r.content()).doesNotContain("(complete)");
            assertThat(client.labels.stream().filter("wiki_page_continue"::equals).count())
                .as("(complete) 后不该再请求续写")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("⭐ 续写返回空 ⇒ 停止（⛔ 不当成写完继续）")
        void stopsOnEmptyContinuation() {
            client.responses.add(new Object[]{"SUMMARY: 开头。\n\n正文", "length"});
            client.responses.add(new Object[]{"", "stop"});
            client.responses.add(new Object[]{"不该被调用", "stop"});

            var existing = page("A", "旧正文");
            var r = stage.reduceSlug("entity/a", existing,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);

            assertThat(r.content()).doesNotContain("不该被调用");
        }

        @Test
        @DisplayName("⭐ finish_reason 三种词汇都认（length / max_tokens / max_output_tokens）")
        void lengthVocabulary() {
            assertThat(KbWikiReduceStage.isLengthFinish("length")).isTrue();
            assertThat(KbWikiReduceStage.isLengthFinish("max_tokens")).isTrue();
            assertThat(KbWikiReduceStage.isLengthFinish("max_output_tokens")).isTrue();
            assertThat(KbWikiReduceStage.isLengthFinish(" MAX_TOKENS ")).isTrue();
            assertThat(KbWikiReduceStage.isLengthFinish("stop")).isFalse();
            assertThat(KbWikiReduceStage.isLengthFinish("end_turn")).isFalse();
            assertThat(KbWikiReduceStage.isLengthFinish(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("⭐⭐ chunk id 永不进正文（两侧都要剔）")
    class ChunkIdStripping {

        @Test
        @DisplayName("⭐⭐ 模型写了 [c003] ⇒ 写前剔除")
        void stripsModelOutput() {
            client.responses.add(new Object[]{
                "SUMMARY: 摘要。\n\n这个事实[c003]很重要，另一个[c12]也是。", "stop"});

            var r = stage.reduceSlug("entity/a", null,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);

            assertThat(r.content())
                .as("⭐⭐ 只靠提示词禁止不可靠 —— 模型仍会写出 chunk id，必须结构上剔掉。"
                    + "chunk id 进正文 = 用户看到噪音")
                .doesNotContain("[c003]")
                .doesNotContain("[c12]")
                .contains("这个事实")
                .contains("很重要");
        }

        @Test
        @DisplayName("⭐⭐ 旧页面里的历史遗留也要在喂给模型前剔掉")
        void stripsExistingBeforePrompt() {
            client.responses.add(new Object[]{"SUMMARY: 摘要。\n\n新正文", "stop"});
            var existing = page("A", "旧内容[c099]带噪音");

            stage.reduceSlug("entity/a", existing,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);

            assertThat(client.prompts.get(0))
                .as("⭐⭐ 不剔 ⇒ 模型照抄，下一轮又写回去（源侧明令要求「编辑时删掉历史遗留的」）")
                .doesNotContain("[c099]")
                .contains("旧内容");
        }

        @Test
        @DisplayName("⭐ 正常编号引用 [1] / [2] 必须保留（不是 chunk id）")
        void keepsNumericCitations() {
            assertThat(KbWikiReduceStage.stripInlineChunkCitations("见[1]和[2]"))
                .as("⭐ [1] 是引用编号，属于正文内容；只删 [c+数字]")
                .isEqualTo("见[1]和[2]");
            assertThat(KbWikiReduceStage.stripInlineChunkCitations("[c1]x[c99]"))
                .isEqualTo("x");
        }
    }

    @Nested
    @DisplayName("合并语义")
    class MergeSemantics {

        @Test
        @DisplayName("新页面：来源与 chunk 引用都写入")
        void createsNew() {
            client.responses.add(new Object[]{"SUMMARY: 新页。\n\n正文", "stop"});
            var r = stage.reduceSlug("entity/a", null,
                List.of(add("doc-1", "A", "c1", "c2")), List.of(), Map.of(), "m", null);

            assertThat(r.action()).isEqualTo("created");
            assertThat(r.sourceRefs()).containsExactly("doc-1");
            assertThat(r.chunkRefs()).containsExactly("c1", "c2");
            assertThat(r.title()).isEqualTo("A");
            assertThat(r.pageType()).isEqualTo("entity");
        }

        @Test
        @DisplayName("⭐ 已有页面：来源/证据取并集（⛔ 不覆盖）")
        void updatesUnion() {
            client.responses.add(new Object[]{"SUMMARY: 更新。\n\n新正文", "stop"});
            var existing = page("A", "旧正文");

            var r = stage.reduceSlug("entity/a", existing,
                List.of(add("doc-2", "A", "c2")), List.of(), Map.of(), "m", null);

            assertThat(r.action()).isEqualTo("updated");
            assertThat(r.sourceRefs())
                .as("⭐ 覆盖会让旧文档的来源凭空消失 ⇒ 引用溯源断掉")
                .containsExactlyInAnyOrder("doc-old", "doc-2");
            assertThat(r.chunkRefs()).containsExactlyInAnyOrder("c900", "c2");
            assertThat(r.title()).as("已有标题优先").isEqualTo("A");
        }

        @Test
        @DisplayName("⭐ 撤稿：被删文档的来源移出，其余保留")
        void retractionRemovesSource() {
            client.fallback = new Object[]{"SUMMARY: 撤稿后。\n\n剩余正文", "stop"};
            var existing = new KbWikiReduceStage.ExistingWikiPage(
                "A", "旧正文", "旧摘要", List.of(), "entity",
                List.of("doc-1", "doc-2"), List.of("c1", "c2"));

            var r = stage.reduceSlug("entity/a", existing,
                List.of(), List.of("doc-1"), Map.of(), "m", null);

            assertThat(r.sourceRefs()).containsExactly("doc-2");
            assertThat(r.action()).isEqualTo("updated");
        }

        @Test
        @DisplayName("⭐ 别名合并去重保序")
        void mergesAliases() {
            assertThat(KbWikiReduceStage.mergeAliases(
                List.of("A", "B"), List.of(add("d", "X"))))
                .containsExactly("A", "B", "别名A");
        }

        @Test
        @DisplayName("无新增无撤稿 ⇒ unchanged（不调模型）")
        void unchangedNoCall() {
            var r = stage.reduceSlug("entity/a", page("A", "旧正文"),
                List.of(), List.of(), Map.of(), "m", null);
            assertThat(r.action()).isEqualTo("unchanged");
            assertThat(client.labels).isEmpty();
        }

        @Test
        @DisplayName("⭐ 模型返回空 ⇒ 拒绝（⛔ 不写空页面）")
        void emptyOutputRejected() {
            client.responses.add(new Object[]{"", "stop"});
            var r = stage.reduceSlug("entity/a", page("A", "旧正文"),
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);
            assertThat(r.action()).isEqualTo("rejected");
            assertThat(r.content()).isEqualTo("旧正文");
        }

        @Test
        @DisplayName("⭐ 只有 SUMMARY 行没有正文 ⇒ 拒绝")
        void summaryOnlyRejected() {
            client.responses.add(new Object[]{"SUMMARY: 只有摘要没有正文。", "stop"});
            var r = stage.reduceSlug("entity/a", page("A", "旧正文"),
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);
            assertThat(r.action()).isEqualTo("rejected");
        }
    }

    @Nested
    @DisplayName("SUMMARY 行解析")
    class SummaryParsing {

        @Test
        @DisplayName("行首 SUMMARY 拆出来，正文不含它")
        void splitsSummary() {
            var s = KbWikiReduceStage.splitSummaryLine("SUMMARY: 一句话摘要。\n\n## 标题\n正文");
            assertThat(s.summary()).isEqualTo("一句话摘要。");
            assertThat(s.body()).doesNotContain("SUMMARY:");
            assertThat(s.body()).contains("## 标题");
        }

        @Test
        @DisplayName("⭐ 正文中间出现的 SUMMARY: 不算摘要行（必须行首）")
        void onlyLineStart() {
            var s = KbWikiReduceStage.splitSummaryLine("## 标题\n这里提到 SUMMARY: 不是摘要");
            assertThat(s.summary())
                .as("⭐ 用 find() 而非全文搜索：否则正文里的这个词会被误当摘要")
                .isNull();
            assertThat(s.body()).contains("## 标题");
        }

        @Test
        @DisplayName("没有 SUMMARY 行 ⇒ 全文当正文")
        void noSummaryFallsBack() {
            var s = KbWikiReduceStage.splitSummaryLine("## 标题\n正文");
            assertThat(s.summary()).isNull();
            assertThat(s.body()).isEqualTo("## 标题\n正文");
        }
    }

    @Nested
    @DisplayName("⭐⭐ 目录规划（一批一次，≤2 级）")
    class Taxonomy {

        @Test
        @DisplayName("⭐⭐ 超过 2 级 ⇒ 截断（不是丢弃）")
        void truncatesDeepPaths() {
            String raw = "{\"assignments\":[{\"slug\":\"entity/a\",\"path\":[\"地点\",\"地址\",\"详细\"]}]}";
            var m = KbWikiReduceStage.parseAssignments(raw, java.util.Set.of("entity/a"));
            assertThat(m.get("entity/a"))
                .as("⭐ 截断保留归属；丢弃会让页面回到根目录")
                .containsExactly("地点", "地址");
        }

        @Test
        @DisplayName("⭐⭐ 用 entity/concept 当目录名 ⇒ 丢弃该条")
        void rejectsTypeNamesAsFolders() {
            String raw = "{\"assignments\":[{\"slug\":\"entity/a\",\"path\":[\"entity\"]}]}";
            assertThat(KbWikiReduceStage.parseAssignments(raw, java.util.Set.of("entity/a")))
                .as("⭐⭐ 源侧明令禁止 —— 那会让目录树与页面类型混淆")
                .isEmpty();
        }

        @Test
        @DisplayName("⭐ 目录名含斜杠 ⇒ 丢弃")
        void rejectsSlashInSegment() {
            String raw = "{\"assignments\":[{\"slug\":\"entity/a\",\"path\":[\"a/b\"]}]}";
            assertThat(KbWikiReduceStage.parseAssignments(raw, java.util.Set.of("entity/a"))).isEmpty();
        }

        @Test
        @DisplayName("⭐ 非本批 slug ⇒ 丢弃（模型偶尔返回上一批的）")
        void rejectsUnknownSlug() {
            String raw = "{\"assignments\":[{\"slug\":\"entity/other\",\"path\":[\"地点\"]}]}";
            assertThat(KbWikiReduceStage.parseAssignments(raw, java.util.Set.of("entity/a"))).isEmpty();
        }

        @Test
        @DisplayName("空路径 = 根目录（合法，应保留）")
        void emptyPathAllowed() {
            String raw = "{\"assignments\":[{\"slug\":\"entity/a\",\"path\":[]}]}";
            var m = KbWikiReduceStage.parseAssignments(raw, java.util.Set.of("entity/a"));
            assertThat(m).containsKey("entity/a");
            assertThat(m.get("entity/a")).isEmpty();
        }
    }

    @Nested
    @DisplayName("sourceRef 只含 knowledgeId")
    class SourceRef {

        @Test
        @DisplayName("⭐ 三种形态都能取出 knowledgeId")
        void extractsId() {
            assertThat(KbWikiReduceStage.knowledgeIdOfRef("doc-1")).isEqualTo("doc-1");
            assertThat(KbWikiReduceStage.knowledgeIdOfRef("doc-1#文件名.pdf")).isEqualTo("doc-1");
            assertThat(KbWikiReduceStage.knowledgeIdOfRef(" doc-1 ")).isEqualTo("doc-1");
            assertThat(KbWikiReduceStage.knowledgeIdOfRef(null)).isEmpty();
        }

        @Test
        @DisplayName("⭐⭐ 发给模型的 new_information 里不含文件名")
        void promptHasNoFilename() {
            client.responses.add(new Object[]{"SUMMARY: x。\n\n正文", "stop"});
            stage.reduceSlug("entity/a", null,
                List.of(add("doc-1", "A", "c1")), List.of(), Map.of(), "m", null);
            // 我们的 SlugUpdate 只有 knowledgeId / sourceRef，签名里根本没有 title
            var record = KbWikiReduceStage.SlugUpdate.class.getRecordComponents();
            assertThat(java.util.Arrays.stream(record).map(c -> c.getName()).toList())
                .as("⭐⭐ 让 title 参数根本不存在 = 编译期挡住「不小心传进去」")
                .doesNotContain("title", "filename", "fileName", "docTitle");
        }
    }
}

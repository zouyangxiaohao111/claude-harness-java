package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.qa.KbContextBuilder.CitationRef;
import com.nexusai.kb.app.qa.KbMergeStage.MergedChunk;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文拼装 · 编号与引用的对齐。
 *
 * <p><b>WHY 这些断言</b>：这层唯一的职责就是「编号」。
 * 编号错了不会抛异常，只会让用户点开引用看到**别的段落** ——
 * 这是知识库产品里最伤信任的一类缺陷（看起来有出处，实际上是错的）。
 */
@DisplayName("[B5] 上下文拼装")
class KbContextBuilderTest {

    private final Map<String, KbKnowledgeRecord> docs = new HashMap<>();
    private KbContextBuilder builder;

    @BeforeEach
    void setUp() {
        KbKnowledgeMapper mapper = Mockito.mock(KbKnowledgeMapper.class);
        docs.clear();
        Mockito.when(mapper.selectListByQuery(Mockito.any()))
            .thenAnswer(inv -> new ArrayList<>(docs.values()));
        builder = new KbContextBuilder(mapper);
    }

    private void doc(String id, String title) {
        KbKnowledgeRecord r = new KbKnowledgeRecord();
        r.setId(id);
        r.setTitle(title);
        docs.put(id, r);
    }

    private static MergedChunk chunk(String id, String knowledgeId, String content, int index) {
        return new MergedChunk(id, knowledgeId, "kb-1", content, null, "text", index, 0.9,
            List.of(id));
    }

    @Test
    @DisplayName("⭐⭐ 编号从 1 连续，且与 chunkIndex 无关（编号是引用锚点，不是段落序号）")
    void numberingAligned() {
        doc("k1", "污水治理导则");
        doc("k2", "运维管理办法");

        // ⚠️ 刻意让 chunkIndex（5/17/42）与引用编号（1/2/3）**完全不同**。
        //    若实现误把 chunkIndex 当 refIndex，这条立刻变红 —— 用 1/2/3 当 chunkIndex
        //    会掩盖这个缺陷（实测：第一版就是这样漏过去的）。
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "第一段内容", 5),
            chunk("c2", "k2", "第二段内容", 17),
            chunk("c3", "k1", "第三段内容", 42)));

        assertThat(r.candidateCount()).isEqualTo(3);
        assertThat(r.citedChunkIds()).containsExactly("c1", "c2", "c3");
        assertThat(r.citations()).hasSize(3);
        for (int i = 0; i < r.citations().size(); i++) {
            CitationRef ref = r.citations().get(i);
            assertThat(ref.refIndex())
                .as("第 %d 条的引用编号必须是 %d（不是 chunkIndex）", i, i + 1)
                .isEqualTo(i + 1);
            assertThat(ref.chunkId()).isEqualTo(r.citedChunkIds().get(i));
            assertThat(ref.chunkIndex())
                .as("chunkIndex 应原样保留（用于前端跳转），与 refIndex 是两回事")
                .isEqualTo(i == 0 ? 5 : (i == 1 ? 17 : 42));
        }
        assertThat(r.text()).contains("[1]").contains("[2]").contains("[3]");
    }

    @Test
    @DisplayName("文本里出现 [1] [2] 编号，且带文档标题")
    void textHasNumberAndTitle() {
        doc("k1", "污水治理导则");
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "内容甲", 3)));

        assertThat(r.text())
            .contains("[1]")
            .contains("《污水治理导则》")
            .contains("内容甲");
    }

    @Test
    @DisplayName("⭐ 文档标题查不到 ⇒ 写「未命名文档」，不留空括号、不抛异常")
    void missingTitlePlaceholder() {
        // 故意不往 docs 里放 k1
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "内容", 1)));

        assertThat(r.text())
            .as("⭐ 空括号会让用户以为文档没名字；抛异常会让整个问答失败。两者都错")
            .contains("《" + KbContextBuilder.UNTITLED + "》")
            .doesNotContain("《》");
        assertThat(r.citedChunkIds()).containsExactly("c1");
    }

    @Test
    @DisplayName("空输入 ⇒ 空结果（文本空串、无引用）")
    void emptyInput() {
        assertThat(builder.build(null).text()).isEmpty();
        assertThat(builder.build(null).candidateCount()).isZero();
        assertThat(builder.build(List.of()).citations()).isEmpty();
    }

    @Test
    @DisplayName("⭐⭐ 超长正文截断到上限并显式标注「后略」")
    void longContentTruncated() {
        String huge = "农".repeat(KbContextBuilder.SEGMENT_MAX_CHARS + 500);
        KbContextBuilder.Rendered r = builder.build(List.of(chunk("c1", "k1", huge, 1)));

        assertThat(r.text()).contains("…（后略）");
        assertThat(KbMergeText.runeLen(r.text()))
            .as("截断后总长应在上限附近（含标题行与标注）")
            .isLessThan(KbContextBuilder.SEGMENT_MAX_CHARS + 200);
        assertThat(r.citations().get(0).content())
            .as("引用里保留完整正文（快照用于证据展示），只有给模型的文本被截断")
            .hasSize(huge.length());
    }

    @Test
    @DisplayName("⭐ emoji 内容截断不切坏代理对")
    void emojiNotSplit() {
        String s = "污水💧".repeat(KbContextBuilder.SEGMENT_MAX_CHARS);
        KbContextBuilder.Rendered r = builder.build(List.of(chunk("c1", "k1", s, 1)));
        // 截断后必须仍是合法字符串（不出现孤立代理）
        assertThat(r.text().chars().anyMatch(c -> Character.isSurrogate((char) c)
            && r.text().indexOf(Character.toString((char) c)) == r.text().length() - 1))
            .as("⭐ 截断点不能落在代理对中间")
            .isFalse();
    }

    @Nested
    @DisplayName("提示词")
    class Prompts {

        @Test
        @DisplayName("有资料 / 无资料 用两套不同的系统提示词")
        void differentSystemPrompts() {
            String with = KbQaPrompts.systemPrompt(true);
            String without = KbQaPrompts.systemPrompt(false);
            assertThat(with).isNotEqualTo(without);
            assertThat(with).as("有资料时要求标注编号").contains("[1]");
            assertThat(without)
                .as("⭐⭐ 无资料时必须明确要求『不要凭常识作答』—— 否则用户会把通用知识"
                    + "当成知识库里写的")
                .contains("没有找到")
                .contains("不要凭常识");
        }

        @Test
        @DisplayName("资料在前、问题在后")
        void contextBeforeQuestion() {
            String msg = KbQaPrompts.userMessage("## 检索到的资料\n[1] 内容", "污水怎么处理");
            assertThat(msg.indexOf("[1] 内容"))
                .as("⭐ 部分模型对末尾内容注意力更强，问题应放在最后")
                .isLessThan(msg.indexOf("污水怎么处理"));
        }

        @Test
        @DisplayName("无上下文时只返回问题（不拼空资料块）")
        void noContextJustQuestion() {
            assertThat(KbQaPrompts.userMessage("", "问题")).isEqualTo("问题");
            assertThat(KbQaPrompts.userMessage(null, "问题")).isEqualTo("问题");
        }
    }
}

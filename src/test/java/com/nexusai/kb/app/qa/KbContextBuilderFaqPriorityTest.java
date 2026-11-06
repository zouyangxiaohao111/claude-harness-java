package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 10 · {@link KbContextBuilder} 的 FAQ 优先分离测试。
 *
 * <p>守四件事：<b>FAQ 段前置</b>、<b>阈值边界</b>（{@code >=} 不是 {@code >}）、
 * <b>关闭时等同旧行为</b>、<b>编号与 citations 对齐</b>。
 */
class KbContextBuilderFaqPriorityTest {

    /** knowledgeMapper 为 null ⇒ 标题统一「未命名文档」（本类不需要查库）。 */
    private final KbContextBuilder builder = new KbContextBuilder(null);

    private static KbMergeStage.MergedChunk chunk(
        String id, String kid, String type, double score, String content) {
        return new KbMergeStage.MergedChunk(
            id, kid, "kb1", content, "表头", type, 0, score, List.of());
    }

    @Test
    @DisplayName("⭐ FAQ 段前置（即使分数更低）")
    void faqChunksComeFirst() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "text", 0.9, "普通文档正文"),
            chunk("f1", "k2", "faq", 0.5, "Q: 怎么报销？\nA: 走 OA")),
            List.of(), new KbContextBuilder.Options(true, 0.0));

        int faqAt = r.text().indexOf("怎么报销");
        int docAt = r.text().indexOf("普通文档正文");
        assertThat(faqAt).isGreaterThanOrEqualTo(0);
        assertThat(docAt).isGreaterThan(faqAt);
    }

    @Test
    @DisplayName("FAQ 段带「标准问答，优先采用」标记")
    void faqChunksAreMarked() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("f1", "k1", "faq", 0.5, "Q: 甲\nA: 乙")),
            List.of(), new KbContextBuilder.Options(true, 0.0));

        assertThat(r.text()).contains("标准问答，优先采用");
        assertThat(r.text()).contains("1 段是知识库的标准问答");
    }

    @Test
    @DisplayName("⭐ 阈值边界：score == 阈值 就要标（>= 不是 >）")
    void exactMatchBoundary() {
        KbContextBuilder.Rendered at = builder.build(
            List.of(chunk("f1", "k1", "faq", 0.9, "Q: 甲\nA: 乙")),
            List.of(), new KbContextBuilder.Options(true, 0.9));
        assertThat(at.text()).contains("精确匹配");

        KbContextBuilder.Rendered below = builder.build(
            List.of(chunk("f1", "k1", "faq", 0.89, "Q: 甲\nA: 乙")),
            List.of(), new KbContextBuilder.Options(true, 0.9));
        assertThat(below.text()).doesNotContain("精确匹配");
    }

    @Test
    @DisplayName("阈值 <= 0 ⇒ 永不标精确匹配")
    void nonPositiveThresholdNeverMarks() {
        KbContextBuilder.Rendered r = builder.build(
            List.of(chunk("f1", "k1", "faq", 1.0, "Q: 甲\nA: 乙")),
            List.of(), new KbContextBuilder.Options(true, 0.0));
        assertThat(r.text()).doesNotContain("精确匹配");
        assertThat(r.text()).contains("标准问答，优先采用");
    }

    @Test
    @DisplayName("FAQ 优先关闭 ⇒ 保持原顺序、无标记（等同旧行为）")
    void disabledKeepsOldBehavior() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "text", 0.9, "普通文档正文"),
            chunk("f1", "k2", "faq", 0.5, "Q: 甲\nA: 乙")),
            List.of(), new KbContextBuilder.Options(false, 0.9));

        assertThat(r.text().indexOf("普通文档正文"))
            .isLessThan(r.text().indexOf("Q: 甲"));
        assertThat(r.text()).doesNotContain("标准问答");
        assertThat(r.text()).doesNotContain("精确匹配");
    }

    @Test
    @DisplayName("旧签名 build(chunks, externals) 行为不变")
    void legacySignatureUnchanged() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("f1", "k1", "faq", 0.9, "Q: 甲\nA: 乙")), List.of());
        assertThat(r.text()).doesNotContain("标准问答");
        assertThat(r.text()).doesNotContain("精确匹配");
    }

    @Test
    @DisplayName("⭐ 编号连续，且 citations 的顺序与编号一致（FAQ 前置后仍对齐）")
    void citationsAlignWithNumbers() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "text", 0.9, "普通正文"),
            chunk("f1", "k2", "faq", 0.5, "Q: 甲\nA: 乙")),
            List.of(), new KbContextBuilder.Options(true, 0.0));

        // FAQ 被前置到 [1]，普通文档变成 [2]
        assertThat(r.citations()).hasSize(2);
        assertThat(r.citations().get(0).chunkId()).isEqualTo("f1");
        assertThat(r.citations().get(0).refIndex()).isEqualTo(1);
        assertThat(r.citations().get(1).chunkId()).isEqualTo("c1");
        assertThat(r.citations().get(1).refIndex()).isEqualTo(2);
        // citedChunkIds 下标 i ↔ 编号 i+1
        assertThat(r.citedChunkIds()).containsExactly("f1", "c1");
    }

    @Test
    @DisplayName("⭐ 同文档标题只完整渲染一次，后续标「（续）」")
    void sameDocumentTitleOnce() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("c1", "k1", "text", 0.9, "第一段正文"),
            chunk("c2", "k1", "text", 0.8, "第二段正文")),
            List.of(), new KbContextBuilder.Options(false, 0.0));

        assertThat(r.text()).contains("（续）");
        // 「未命名文档」出现次数 = 标记 + 一次续标记
        int count = r.text().split("未命名文档", -1).length - 1;
        assertThat(count).isEqualTo(2);   // 第一处完整标题 + 第二处（续）
    }

    @Test
    @DisplayName("全 FAQ 场景：一句摘要 + 全段前置")
    void allFaq() {
        KbContextBuilder.Rendered r = builder.build(List.of(
            chunk("f1", "k1", "faq", 0.9, "Q: 甲\nA: 乙"),
            chunk("f2", "k2", "faq", 0.8, "Q: 丙\nA: 丁")),
            List.of(), new KbContextBuilder.Options(true, 0.9));

        assertThat(r.text()).contains("2 段是知识库的标准问答");
        assertThat(r.citations()).hasSize(2);
    }

    @Test
    @DisplayName("外部段仍编号在最后，且不进 citations")
    void externalsStillLast() {
        KbContextBuilder.Rendered r = builder.build(
            List.of(chunk("f1", "k1", "faq", 0.5, "Q: 甲\nA: 乙")),
            List.of(new KbContextBuilder.ExternalSegment("网页标题", "https://x", "网页正文")),
            new KbContextBuilder.Options(true, 0.0));

        assertThat(r.text().indexOf("网页正文")).isGreaterThan(r.text().indexOf("Q: 甲"));
        assertThat(r.citations()).hasSize(1);   // 外部段不落引用
    }
}

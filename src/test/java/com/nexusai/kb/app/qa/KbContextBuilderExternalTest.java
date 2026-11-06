package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.qa.KbContextBuilder.ExternalSegment;
import com.nexusai.kb.app.qa.KbMergeStage.MergedChunk;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联网结果进上下文 · 编号连续 + 引用不落库。
 *
 * <p><b>WHY 这些断言（规则九 · 测试验证意图）</b>：网页没有 chunk 行。
 * 如果把网页当成普通段一起写进 {@code citations}，落库时会得到一个
 * {@code chunk_id = URL} 的引用行 —— 前端点开就是 404/空白，而且不会报错。
 * ⇒ 断言必须成对：
 * <ol>
 *   <li><b>编号连续</b>：库内段 [1..n]、外部段 [n+1..]，模型看到的是一整套；</li>
 *   <li><b>citations 只含库内段</b>：外部段不产生引用行。</li>
 * </ol>
 * 只测第一条会漏掉「引用串味」，只测第二条会漏掉「编号断层」。
 */
@DisplayName("[QA 对齐] 联网段进上下文")
class KbContextBuilderExternalTest {

    private final Map<String, KbKnowledgeRecord> docs = new HashMap<>();
    private KbContextBuilder builder;

    @BeforeEach
    void setUp() {
        KbKnowledgeMapper mapper = Mockito.mock(KbKnowledgeMapper.class);
        docs.clear();
        Mockito.when(mapper.selectListByQuery(Mockito.any()))
            .thenAnswer(inv -> new ArrayList<>(docs.values()));
        builder = new KbContextBuilder(mapper);
        KbKnowledgeRecord d = new KbKnowledgeRecord();
        d.setId("doc-1");
        d.setTitle("农污规范");
        docs.put("doc-1", d);
    }

    private static MergedChunk chunk(String id, String content) {
        return new MergedChunk(id, "doc-1", "kb-1", content, "", "text", 0, 0.8, List.of());
    }

    @Test
    @DisplayName("库内 2 段 + 网页 1 段 → 编号 1/2/3 连续，引用只有 2 条")
    void numbersAreContinuousButCitationsOnlyCoverChunks() {
        KbContextBuilder.Rendered r = builder.build(
            List.of(chunk("c1", "第一段正文"), chunk("c2", "第二段正文")),
            List.of(new ExternalSegment("新闻标题", "https://example.com/a", "网页正文")));

        assertThat(r.text()).contains("[1]").contains("[2]").contains("[3]");
        // 顺序：库内在前、外部在后
        assertThat(r.text().indexOf("[1]")).isLessThan(r.text().indexOf("[3]"));
        assertThat(r.text()).contains("网页正文");
        // ⭐ 关键：网页不进 citations
        assertThat(r.citations()).hasSize(2);
        assertThat(r.citations()).extracting(KbContextBuilder.CitationRef::chunkId)
            .containsExactly("c1", "c2");
        // candidateCount 是「模型看到几段」，含外部段
        assertThat(r.candidateCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("知识库 0 命中但联网有结果 → 不能返回空上下文（否则模型会说「知识库里没找到」）")
    void externalOnlyIsNotTreatedAsEmpty() {
        KbContextBuilder.Rendered r = builder.build(
            List.of(), List.of(new ExternalSegment("标题", "https://e.com", "联网正文")));
        assertThat(r.text()).isNotBlank();
        assertThat(r.text()).contains("联网正文");
        assertThat(r.candidateCount()).isEqualTo(1);
        // 没有任何 chunk 可引用
        assertThat(r.citations()).isEmpty();
    }

    @Test
    @DisplayName("两边都空 → 仍是空上下文（这是合法的「无资料」）")
    void bothEmptyYieldsEmpty() {
        KbContextBuilder.Rendered r = builder.build(List.of(), List.of());
        assertThat(r.text()).isEmpty();
        assertThat(r.candidateCount()).isZero();
    }

    @Test
    @DisplayName("网页标题为空 → 用 URL 代替（⛔ 不留空书名号）")
    void missingTitleFallsBackToUrl() {
        KbContextBuilder.Rendered r = builder.build(
            List.of(), List.of(new ExternalSegment(null, "https://e.com/x", "正文")));
        assertThat(r.text()).contains("https://e.com/x");
        assertThat(r.text()).doesNotContain("《》");
    }

    @Test
    @DisplayName("超长网页正文被截断并标注「后略」（与库内段同一上限）")
    void longExternalTextIsTruncated() {
        String longText = "字".repeat(KbContextBuilder.SEGMENT_MAX_CHARS + 50);
        KbContextBuilder.Rendered r = builder.build(
            List.of(), List.of(new ExternalSegment("长文", "https://e.com", longText)));
        assertThat(r.text()).contains("后略");
        assertThat(r.text().codePointCount(0, r.text().length()))
            .isLessThan(longText.codePointCount(0, longText.length()) + 200);
    }
}

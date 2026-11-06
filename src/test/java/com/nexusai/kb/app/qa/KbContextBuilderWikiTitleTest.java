package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.qa.KbMergeStage.MergedChunk;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.nexusai.kb.repo.mapper.KbWikiPageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文拼装 · wiki 页标题回退。
 *
 * <p><b>WHY 这组断言</b>：wiki 块的 {@code knowledgeId} 存的是<b>页 id</b>，
 * 它不在 {@code kb_knowledge} 里。只查文档表 ⇒ 用户看到的是
 * 「[2] 《未命名文档》」—— 引用了 wiki 内容却认不出来源。
 *
 * <p>⚠️ 这条缺陷在真机才暴露（单测原先只覆盖文档标题），因为两边都不抛异常，
 * 只是标题悄悄变成占位符。
 */
@DisplayName("[W12] 上下文拼装 · wiki 标题回退")
class KbContextBuilderWikiTitleTest {

    private static MergedChunk chunk(String chunkId, String knowledgeId, String content) {
        return new MergedChunk(chunkId, knowledgeId, "kb-1", content, null, "wiki_page", 0, 1.0,
            List.of(chunkId));
    }

    @Test
    @DisplayName("⭐⭐ 文档表查不到时回退查 kb_wiki_page，标题不再是「未命名文档」")
    void fallsBackToWikiPageTitle() {
        KbKnowledgeMapper knowledgeMapper = Mockito.mock(KbKnowledgeMapper.class);
        KbWikiPageMapper wikiMapper = Mockito.mock(KbWikiPageMapper.class);
        Mockito.when(knowledgeMapper.selectListByQuery(Mockito.any())).thenReturn(List.of());

        KbWikiPageRecord page = new KbWikiPageRecord();
        page.setId("wiki-1");
        page.setTitle("无人机巡检合规要求");
        Mockito.when(wikiMapper.selectListByQuery(Mockito.any())).thenReturn(List.of(page));

        KbContextBuilder builder = new KbContextBuilder(knowledgeMapper);
        ReflectionTestUtils.setField(builder, "wikiPageMapper", wikiMapper);

        KbContextBuilder.Rendered r = builder.build(
            List.of(chunk("c1", "wiki-1", "巡检应当遵守空域管理规定")));

        assertThat(r.text()).contains("无人机巡检合规要求");
        // ⛔ 反向判据：不能既有真标题又留着占位符
        assertThat(r.text()).doesNotContain(KbContextBuilder.UNTITLED);
        assertThat(r.citations()).hasSize(1);
        assertThat(r.citations().get(0).chunkId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("文档表命中时不查 wiki（避免无谓查询，也不覆盖真实标题）")
    void doesNotQueryWikiWhenKnowledgeTitleResolved() {
        KbKnowledgeMapper knowledgeMapper = Mockito.mock(KbKnowledgeMapper.class);
        KbWikiPageMapper wikiMapper = Mockito.mock(KbWikiPageMapper.class);
        KbKnowledgeRecord doc = new KbKnowledgeRecord();
        doc.setId("k1");
        doc.setTitle("污水治理导则");
        Mockito.when(knowledgeMapper.selectListByQuery(Mockito.any())).thenReturn(List.of(doc));

        KbContextBuilder builder = new KbContextBuilder(knowledgeMapper);
        ReflectionTestUtils.setField(builder, "wikiPageMapper", wikiMapper);

        KbContextBuilder.Rendered r = builder.build(List.of(chunk("c1", "k1", "正文")));

        assertThat(r.text()).contains("污水治理导则");
        Mockito.verify(wikiMapper, Mockito.never()).selectListByQuery(Mockito.any());
    }

    @Test
    @DisplayName("wiki mapper 未装配（plain 单测 / 部分装配）⇒ 降级为占位符，不抛")
    void degradesWhenWikiMapperAbsent() {
        KbKnowledgeMapper knowledgeMapper = Mockito.mock(KbKnowledgeMapper.class);
        Mockito.when(knowledgeMapper.selectListByQuery(Mockito.any())).thenReturn(List.of());

        KbContextBuilder builder = new KbContextBuilder(knowledgeMapper);

        KbContextBuilder.Rendered r = builder.build(List.of(chunk("c1", "wiki-x", "正文")));

        assertThat(r.text()).contains(KbContextBuilder.UNTITLED);
    }
}

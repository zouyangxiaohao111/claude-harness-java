package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbCitationRecord;
import com.nexusai.kb.repo.mapper.KbCitationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用落库 · 序号与排序。
 *
 * <p><b>WHY 这些断言</b>：引用是知识库问答的<b>证据链</b>。
 * 序号错位、条数缺失、失败炸掉主流程 —— 三种都会让「可追溯」这件事变成谎话。
 */
@DisplayName("[B5] 引用落库")
class KbCitationWriterTest {

    private KbCitationMapper mapper;
    private KbCitationWriter writer;
    private final List<KbCitationRecord> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        inserted.clear();
        mapper = Mockito.mock(KbCitationMapper.class);
        Mockito.when(mapper.insert(Mockito.any(KbCitationRecord.class))).thenAnswer(inv -> {
            inserted.add(inv.getArgument(0));
            return 1;
        });
        writer = new KbCitationWriter(mapper);
    }

    private static KbCitationWriter.Citation citation(int idx, String chunkId, String content) {
        return new KbCitationWriter.Citation(
            idx, chunkId, "k1", "kb-1", "污水导则", content, idx);
    }

    @Test
    @DisplayName("批量写入：条数与序号都落到库里")
    void savesAllWithIndex() {
        int saved = writer.saveAll("s1", "m1", List.of(
            citation(1, "c1", "内容一"),
            citation(2, "c2", "内容二"),
            citation(3, "c3", "内容三")));

        assertThat(saved).isEqualTo(3);
        assertThat(inserted).hasSize(3);
        assertThat(inserted).extracting(KbCitationRecord::getRefIndex)
            .as("⭐⭐ 序号必须原样落库 —— 它就是模型答复里 [n] 的落点")
            .containsExactly(1, 2, 3);
        assertThat(inserted).extracting(KbCitationRecord::getChunkId)
            .containsExactly("c1", "c2", "c3");
        assertThat(inserted).allSatisfy(r -> {
            assertThat(r.getSessionId()).isEqualTo("s1");
            assertThat(r.getAssistantMessageId()).isEqualTo("m1");
            assertThat(r.getId()).as("主键必须生成").isNotBlank();
        });
    }

    @Test
    @DisplayName("缺 chunkId 的引用跳过（写进去也点不开，只会污染证据链）")
    void skipsCitationWithoutChunkId() {
        int saved = writer.saveAll("s1", "m1", List.of(
            citation(1, "c1", "有 id"),
            citation(2, null, "无 id")));

        assertThat(saved).isEqualTo(1);
        assertThat(inserted).hasSize(1);
    }

    @Test
    @DisplayName("空列表 / 空 session / 空 messageId ⇒ 不写，返回 0")
    void emptyInputsNoWrite() {
        assertThat(writer.saveAll("s1", "m1", List.of())).isZero();
        assertThat(writer.saveAll("s1", "m1", null)).isZero();
        assertThat(writer.saveAll(null, "m1", List.of(citation(1, "c1", "x")))).isZero();
        assertThat(writer.saveAll("s1", "", List.of(citation(1, "c1", "x")))).isZero();
        assertThat(inserted).isEmpty();
    }

    @Test
    @DisplayName("⭐ 写库异常 ⇒ 不抛、返回已写入条数（引用失败不能打断主流程）")
    void insertFailureDoesNotThrow() {
        KbCitationMapper failing = Mockito.mock(KbCitationMapper.class);
        Mockito.when(failing.insert(Mockito.any(KbCitationRecord.class)))
            .thenThrow(new RuntimeException("模拟磁盘满"));
        KbCitationWriter w = new KbCitationWriter(failing);

        int saved = w.saveAll("s1", "m1", List.of(
            citation(1, "c1", "a"), citation(2, "c2", "b")));

        assertThat(saved)
            .as("⭐ 引用是附加证据，不是答案本身 —— 写失败不该让用户拿不到已生成的回答")
            .isZero();
    }

    @Test
    @DisplayName("⭐ 正文快照按码点截断（不切坏 emoji），并加省略号")
    void snippetTruncatedByCodePoint() {
        String longContent = "污💧".repeat(500);
        writer.saveAll("s1", "m1", List.of(citation(1, "c1", longContent)));

        ArgumentCaptor<KbCitationRecord> cap = ArgumentCaptor.forClass(KbCitationRecord.class);
        Mockito.verify(mapper).insert(cap.capture());
        String snippet = cap.getValue().getSnippet();
        assertThat(snippet).endsWith("…");
        // 截断后不能以孤立代理（半个 emoji）结尾
        char last = snippet.charAt(snippet.length() - 2);
        assertThat(Character.isHighSurrogate(last))
            .as("⭐ 按码点切 → 不会留下半个 emoji")
            .isFalse();
    }

    @Test
    @DisplayName("取引用按编号升序")
    void listsOrderedByIndex() {
        KbCitationMapper m = Mockito.mock(KbCitationMapper.class);
        Mockito.when(m.selectListByQuery(Mockito.any())).thenReturn(List.of());
        KbCitationWriter w = new KbCitationWriter(m);

        assertThat(w.listByMessage("m1")).isEmpty();
        assertThat(w.listByMessage(null)).isEmpty();
        assertThat(w.listByMessage("  ")).isEmpty();
    }

    @Test
    @DisplayName("查询异常 ⇒ 返回空列表，不抛")
    void listFailureReturnsEmpty() {
        KbCitationMapper m = Mockito.mock(KbCitationMapper.class);
        Mockito.when(m.selectListByQuery(Mockito.any()))
            .thenThrow(new RuntimeException("表不存在"));
        assertThat(new KbCitationWriter(m).listByMessage("m1")).isEmpty();
    }

    // ── 扇出 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 扇出：单路失败不影响其它路")
    void fanOutPartialFailure() {
        KbHybridSearcher searcher = Mockito.mock(KbHybridSearcher.class);
        Mockito.when(searcher.search("kb-bad", "q", 10, null))
            .thenThrow(new RuntimeException("模拟 kb 不可用"));
        Mockito.when(searcher.search("kb-ok", "q", 10, null))
            .thenReturn(List.of(new KbHybridSearcher.Hit("c1", "k1", "内容", "头", 0.9, 1, 1)));

        KbSearchFanOut fanOut = new KbSearchFanOut(searcher);
        List<KbHybridSearcher.Hit> out = fanOut.searchAll(List.of(
            new KbSearchFanOut.QueryPlan("kb-bad", "q", 10, null),
            new KbSearchFanOut.QueryPlan("kb-ok", "q", 10, null)), 10);
        fanOut.shutdown();

        assertThat(out)
            .as("⭐ 一个库挂了不该让整轮问答空手而归")
            .hasSize(1);
        assertThat(out.get(0).chunkId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("⭐ 扇出：同一 chunk 被多路命中 ⇒ 保留最高分")
    void fanOutKeepsBestScore() {
        KbHybridSearcher searcher = Mockito.mock(KbHybridSearcher.class);
        Mockito.when(searcher.search("kb-1", "q1", 10, null))
            .thenReturn(List.of(new KbHybridSearcher.Hit("c1", "k1", "内容", "头", 0.5, 1, 0)));
        Mockito.when(searcher.search("kb-1", "q2", 10, null))
            .thenReturn(List.of(new KbHybridSearcher.Hit("c1", "k1", "内容", "头", 0.9, 1, 1)));

        KbSearchFanOut fanOut = new KbSearchFanOut(searcher);
        List<KbHybridSearcher.Hit> out = fanOut.searchAll(List.of(
            new KbSearchFanOut.QueryPlan("kb-1", "q1", 10, null),
            new KbSearchFanOut.QueryPlan("kb-1", "q2", 10, null)), 10);
        fanOut.shutdown();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).score())
            .as("去重保留最高分 —— 同一块被两条查询命中，取更强的证据")
            .isEqualTo(0.9);
    }

    @Test
    @DisplayName("扇出：空计划 / null ⇒ 空结果，不 NPE")
    void fanOutEmpty() {
        KbHybridSearcher searcher = Mockito.mock(KbHybridSearcher.class);
        KbSearchFanOut fanOut = new KbSearchFanOut(searcher);
        assertThat(fanOut.searchAll(null, 10)).isEmpty();
        assertThat(fanOut.searchAll(List.of(), 10)).isEmpty();
        assertThat(fanOut.searchAll(List.of(
            new KbSearchFanOut.QueryPlan("", "q", 10, null)), 10)).isEmpty();
        fanOut.shutdown();
    }
}

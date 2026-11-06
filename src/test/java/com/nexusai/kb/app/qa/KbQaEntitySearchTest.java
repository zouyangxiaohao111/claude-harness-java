package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 4c · {@link KbQaEntitySearch} 的纯规则测试。
 *
 * <p>守三件事：<b>跨实体去重但记录全部来源</b>、<b>保序</b>、<b>单实体上限</b>，
 * 外加「转成检索命中时分数恒为 0」—— 这一个值下游阶段 7 的截断逻辑依赖它。
 */
class KbQaEntitySearchTest {

    private static Map<String, List<String>> byEntity(Object... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (List<String>) pairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("同一块被两个实体带出 ⇒ 一条记录，两个来源都留着")
    void sameChunkFromTwoEntities() {
        Map<String, List<String>> got = KbQaEntitySearch.mergeByChunk(byEntity(
            "甲", List.of("c1", "c2"),
            "乙", List.of("c1")));

        assertThat(got).containsOnlyKeys("c1", "c2");
        assertThat(got.get("c1")).containsExactly("甲", "乙");
        assertThat(got.get("c2")).containsExactly("甲");
    }

    @Test
    @DisplayName("⭐ 保序：顺序 = 实体顺序 + 组内块顺序（LinkedHashMap 语义）")
    void keepsOrder() {
        Map<String, List<String>> got = KbQaEntitySearch.mergeByChunk(byEntity(
            "乙", List.of("c9", "c8"),
            "甲", List.of("c1")));

        assertThat(got.keySet()).containsExactly("c9", "c8", "c1");
    }

    @Test
    @DisplayName("同一实体重复列出同一块 ⇒ 来源里不重复")
    void noDuplicateVia() {
        Map<String, List<String>> got = KbQaEntitySearch.mergeByChunk(byEntity(
            "甲", List.of("c1", "c1", "c1")));

        assertThat(got.get("c1")).containsExactly("甲");
    }

    @Test
    @DisplayName("单实体最多带出 MAX_CHUNKS_PER_ENTITY 块（防高度数实体灌满上下文）")
    void capsPerEntity() {
        int cap = KbQaEntitySearch.MAX_CHUNKS_PER_ENTITY;
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < cap + 10; i++) {
            many.add("c" + i);
        }
        Map<String, List<String>> got = KbQaEntitySearch.mergeByChunk(byEntity("甲", many));

        assertThat(got).hasSize(cap);
        assertThat(got).containsKeys("c0", "c" + (cap - 1));
        assertThat(got).doesNotContainKey("c" + cap);
    }

    @Test
    @DisplayName("空 / null 输入 ⇒ 空 map（不抛）")
    void emptyInputs() {
        assertThat(KbQaEntitySearch.mergeByChunk(null)).isEmpty();
        assertThat(KbQaEntitySearch.mergeByChunk(Map.of())).isEmpty();
        assertThat(KbQaEntitySearch.mergeByChunk(byEntity("甲", List.of()))).isEmpty();
        assertThat(KbQaEntitySearch.mergeByChunk(byEntity(null, List.of("c1")))).isEmpty();
        assertThat(KbQaEntitySearch.mergeByChunk(byEntity("甲", null))).isEmpty();
    }

    @Test
    @DisplayName("⭐⭐ LIKE 通配符必须转义（否则「处理率 50%」会匹配一切实体）")
    void escapesLikeWildcards() {
        // 实体名里带 % / _ 是真实存在的（「处理率 50%」「chunk_1」）
        assertThat(KbQaEntitySearch.escapeLike("处理率 50%")).isEqualTo("处理率 50\\%");
        assertThat(KbQaEntitySearch.escapeLike("chunk_1")).isEqualTo("chunk\\_1");
        // 反斜杠本身要先转，否则后面的转义会被吃掉
        assertThat(KbQaEntitySearch.escapeLike("a\\b")).isEqualTo("a\\\\b");
        // 普通中文名不受影响（绝大多数情况）
        assertThat(KbQaEntitySearch.escapeLike("农村生活污水处理设施")).isEqualTo("农村生活污水处理设施");
    }

    @Test
    @DisplayName("块 id 里的 null / 空白被跳过")
    void skipsBlankChunkIds() {
        Map<String, List<String>> got = KbQaEntitySearch.mergeByChunk(
            byEntity("甲", java.util.Arrays.asList("c1", null, "  ", "c2")));

        assertThat(got.keySet()).containsExactly("c1", "c2");
    }

    @Test
    @DisplayName("⭐ 转成检索命中时 score 恒为 0（阶段 7 靠这个值识别 graph 命中）")
    void toHitsScoreIsZero() {
        List<KbQaEntitySearch.EntityHit> hits = List.of(
            new KbQaEntitySearch.EntityHit("c1", "k1", "正文", "表头", 0.0, List.of("甲")),
            new KbQaEntitySearch.EntityHit("c2", "k2", "正文2", "表头2", 0.0, List.of("乙")));

        List<KbHybridSearcher.Hit> got = KbQaEntitySearch.toHits(hits);

        assertThat(got).hasSize(2);
        assertThat(got).allSatisfy(h -> assertThat(h.score()).isZero());
        assertThat(got.get(0).chunkId()).isEqualTo("c1");
        assertThat(got.get(0).knowledgeId()).isEqualTo("k1");
        // 实体路不是「文本相似」，两路名次都必须是 0（未命中），⛔ 不能伪造名次
        assertThat(got.get(0).vectorRank()).isZero();
        assertThat(got.get(0).keywordRank()).isZero();
    }

    @Test
    @DisplayName("toHits 空输入 ⇒ 空列表")
    void toHitsEmpty() {
        assertThat(KbQaEntitySearch.toHits(null)).isEmpty();
        assertThat(KbQaEntitySearch.toHits(List.of())).isEmpty();
    }
}

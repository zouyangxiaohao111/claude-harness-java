package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.app.graph.store.KbGraphStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段 4c · 与源站 {@code chat_pipeline/search_entity.go} 的**边界语义**对齐。
 *
 * <h2>为什么要单独一组</h2>
 * 本仓此前只对齐了「主干」（子串匹配、精确名优先、一跳、分数 0），
 * 但源站有 5 条<b>边界限制</b>是防真实事故的，缺了不会报错、只会让召回诡异：
 *
 * <table>
 *   <tr><th>源站常量/子句</th><th>作用</th><th>缺失后果</th></tr>
 *   <tr><td>{@code graphSearchMaxSeedNodes = 200}</td><td>种子实体上限</td>
 *       <td>抽出「公司」这种宽泛词 ⇒ 捞出全库实体，每实体再带块 ⇒
 *           撑爆 rerank 请求上限（源站注释记录过 1309 节点/1917 关系）</td></tr>
 *   <tr><td>{@code graphSearchMaxRows = 2000}</td><td>展开关系行上限</td>
 *       <td>hub 实体（实测度 36）邻域过大时无界</td></tr>
 *   <tr><td>{@code EXISTS { (n)--() }}</td><td>只让「有关系」的实体当种子</td>
 *       <td>孤立实体占掉种子名额却贡献 0 邻居</td></tr>
 *   <tr><td>{@code graph.Node[].Chunks}（含邻居 {@code m}）</td><td>邻居节点**自己的**块也要取</td>
 *       <td>只取关系行 chunk_ids ⇒ 召回系统性偏少（关系行通常只记 1 个块）</td></tr>
 *   <tr><td>{@code maxEntityChunks = 30}</td><td>总块数上限</td>
 *       <td>上下文被图谱边挤满</td></tr>
 * </table>
 *
 * <p>⚠️ 用 Mockito 假 mapper（不连库、不连网）⇒ 能直接断言「查到几条、顺序如何」。
 * 假实现按 SQL 文本粗解析：含 {@code LIKE} ⇒ 子串过滤；含 {@code IN} ⇒ 名字精确过滤。
 */
class KbQaEntitySearchBoundaryTest {

    // ── 假 mapper 工厂 ─────────────────────────────────────────────

    /** 从 SQL 里抠出单引号字面量（LIKE 的值会带 %，IN 的值是纯名字）。 */
    private static List<String> quotedValues(String sql) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = sql.indexOf('\'', i)) >= 0) {
            int b = sql.indexOf('\'', i + 1);
            if (b < 0) {
                break;
            }
            out.add(sql.substring(i + 1, b));
            i = b + 1;
        }
        return out;
    }

    /** 假 store：按库返回预置实体/关系（替代原来的两个假 mapper）。 */
    private static KbGraphStore stubStore(List<KbGraphEntityRecord> es,
                                          List<KbGraphRelationRecord> rs) {
        return new KbGraphStore() {
            @Override
            public void replaceGraph(String kb, String k, List<KbGraphEntityRecord> e,
                                     List<KbGraphRelationRecord> r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteByKnowledge(String knowledgeId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<KbGraphEntityRecord> listEntities(String kbId) {
                return new ArrayList<>(es);
            }

            @Override
            public List<KbGraphRelationRecord> listRelations(String kbId) {
                return new ArrayList<>(rs);
            }
        };
    }

    private static KbChunkMapper chunkMapper(Map<String, KbChunkRecord> byId) {
        KbChunkMapper m = mock(KbChunkMapper.class);
        when(m.selectListByQuery(any())).thenAnswer(inv -> {
            QueryWrapper q = inv.getArgument(0);
            List<String> ids = quotedValues(String.valueOf(q.toSQL()));
            List<KbChunkRecord> out = new ArrayList<>();
            for (String id : ids) {
                KbChunkRecord c = byId.get(id);
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        });
        return m;
    }

    // ── 造数据 ──────────────────────────────────────────────────────

    private static String json(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ids.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static KbGraphEntityRecord entity(String name, String kg, String... chunks) {
        KbGraphEntityRecord r = new KbGraphEntityRecord();
        r.setId("e-" + name);
        r.setKnowledgeBaseId("kb1");
        r.setKnowledgeId(kg);
        r.setName(name);
        r.setChunkIds(json(List.of(chunks)));
        return r;
    }

    private static KbGraphRelationRecord relation(String src, String dst, String... chunks) {
        KbGraphRelationRecord r = new KbGraphRelationRecord();
        r.setKnowledgeBaseId("kb1");
        r.setSourceName(src);
        r.setTargetName(dst);
        r.setChunkIds(json(List.of(chunks)));
        return r;
    }

    private static KbChunkRecord chunk(String id, String content) {
        KbChunkRecord c = new KbChunkRecord();
        c.setId(id);
        c.setKnowledgeBaseId("kb1");
        c.setKnowledgeId("k1");
        c.setContent(content);
        c.setIsEnabled(1);
        return c;
    }

    private static KbQaEntitySearch search(
        List<KbGraphEntityRecord> es, List<KbGraphRelationRecord> rs, Map<String, KbChunkRecord> cs
    ) {
        return new KbQaEntitySearch(stubStore(es, rs), chunkMapper(cs));
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 邻居节点自己的 chunks 也要取（源侧 decode 把 n 和 m 都放进 graph.Node）")
    void neighbourOwnChunksAreCollected() {
        List<KbGraphEntityRecord> es = List.of(
            entity("种子", "k1", "c-seed"),
            entity("邻居", "k1", "c-neighbour"));
        // ⚠️ 关键：关系行上**没有** c-neighbour —— 只有邻居节点自己才有
        List<KbGraphRelationRecord> rs = List.of(relation("种子", "邻居", "c-relation"));
        Map<String, KbChunkRecord> cs = new LinkedHashMap<>();
        cs.put("c-seed", chunk("c-seed", "seed 正文"));
        cs.put("c-neighbour", chunk("c-neighbour", "邻居实体在别的文档里的正文"));
        cs.put("c-relation", chunk("c-relation", "关系抽出处的正文"));

        List<KbQaEntitySearch.EntityHit> hits =
            search(es, rs, cs).search(Map.of("kb1", List.of("种子")), 0);

        List<String> ids = hits.stream().map(KbQaEntitySearch.EntityHit::chunkId).toList();
        assertThat(ids)
            .as("只取关系行 chunks ⇒ 缺 c-neighbour，召回系统性偏少")
            .contains("c-neighbour");
        assertThat(ids).contains("c-seed");
    }

    @Test
    @DisplayName("⭐⭐ 只让「有关系」的实体当种子（源侧 EXISTS { (n)--() }）")
    void isolatedEntityIsNotASeed() {
        List<KbGraphEntityRecord> es = List.of(
            entity("孤立", "k1", "c-isolated"),   // 没有任何关系
            entity("甲", "k1", "c-a"),
            entity("乙", "k1", "c-b"));
        List<KbGraphRelationRecord> rs = List.of(relation("甲", "乙", "c-rel"));
        Map<String, KbChunkRecord> cs = new LinkedHashMap<>();
        for (String id : List.of("c-isolated", "c-a", "c-b", "c-rel")) {
            cs.put(id, chunk(id, id));
        }

        List<KbQaEntitySearch.EntityHit> hits =
            search(es, rs, cs).search(Map.of("kb1", List.of("孤立")), 0);

        assertThat(hits)
            .as("孤立实体没有边 ⇒ 源侧 EXISTS 子句会排除它，不该带出块")
            .isEmpty();
    }

    @Test
    @DisplayName("⭐⭐ 种子实体上限 200（源侧 graphSearchMaxSeedNodes）")
    void seedCapIs200() {
        assertThat(KbQaEntitySearch.MAX_SEED_NODES).isEqualTo(200);
        /* ⚠️ 这条判据设计失败过两次（都是假绿），记录清楚免得后人重犯：
           ① 「最终块数 ≤ 30」⇒ 总块上限抢先生效，删掉种子上限仍全绿；
           ② 「命中数 = 200」⇒ 同样被 30 块封顶，还是假绿。
           ⇒ 必须让「截断与否」**直接改变输出内容**，且远低于 30 块：
           · 250 个实体，名字定长 `项目000..项目249` ⇒ 字典序 = 数值序；
           · 只给 **项目000**（必定进前 200）和 **项目200**（恰好第 201 名）各 1 块；
           · 边用**成对不相交**（项目002k ↔ 项目002k+1），刻意不用星型 ——
             星型会让 项目000 的邻域覆盖 项目200，邻居路径又把它的块带回来，
             判据就失效了。
           于是：截断生效 ⇒ 项目200 既不是种子、也不是任何种子的邻居 ⇒ 它的块不出现；
                截断失效 ⇒ 项目200 变成种子 ⇒ 它的块出现。 */
        List<KbGraphEntityRecord> es = new ArrayList<>();
        List<KbGraphRelationRecord> rs = new ArrayList<>();
        Map<String, KbChunkRecord> cs = new LinkedHashMap<>();
        for (int i = 0; i < 250; i++) {
            String name = String.format("项目%03d", i);
            // 只有 000 和 200 带块，其余为空
            es.add(i == 0 || i == 200 ? entity(name, "k1", "c-" + i) : entity(name, "k1"));
            cs.put("c-" + i, chunk("c-" + i, "正文" + i));
        }
        for (int i = 0; i + 1 < 250; i += 2) {
            rs.add(relation(String.format("项目%03d", i), String.format("项目%03d", i + 1)));
        }

        List<KbQaEntitySearch.EntityHit> hits =
            search(es, rs, cs).search(Map.of("kb1", List.of("项目")), 1000);

        List<String> ids = hits.stream().map(KbQaEntitySearch.EntityHit::chunkId).toList();
        assertThat(ids)
            .as("前 200 名的种子必须仍能被带出（否则是「一个都没有」的另一种假绿）")
            .contains("c-0");
        assertThat(ids)
            .as("种子上限 200 未生效 ⇒ 第 201 名（项目200）会变成种子并带出它的块"
                + "（本条在变异验证中已确认能精确回红）")
            .doesNotContain("c-200");
    }

    @Test
    @DisplayName("⭐⭐ 总块数上限 30（源侧 maxEntityChunks）")
    void totalChunkCapIs30() {
        assertThat(KbQaEntitySearch.MAX_TOTAL_CHUNKS).isEqualTo(30);
        List<KbGraphEntityRecord> es = new ArrayList<>();
        List<KbGraphRelationRecord> rs = new ArrayList<>();
        Map<String, KbChunkRecord> cs = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            es.add(entity("节点" + i, "k1", "c-" + i));
            cs.put("c-" + i, chunk("c-" + i, "正文" + i));
            if (i > 0) {
                rs.add(relation("节点0", "节点" + i, "c-" + i));
            }
        }

        List<KbQaEntitySearch.EntityHit> hits =
            search(es, rs, cs).search(Map.of("kb1", List.of("节点0")), 0);

        assertThat(hits).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("⭐ 种子排序：精确名 → 名字短 → 名字 → kg → id（源侧 ORDER BY 五级）")
    void seedOrderIsDeterministic() {
        List<KbGraphEntityRecord> es = List.of(
            entity("农村生活污水处理设施", "k1", "c-long"),
            entity("农村", "k1", "c-exact"),
            entity("农村污水", "k1", "c-mid"));
        List<KbGraphRelationRecord> rs = List.of(
            relation("农村", "农村污水", "c-rel1"),
            relation("农村", "农村生活污水处理设施", "c-rel2"));
        Map<String, KbChunkRecord> cs = new LinkedHashMap<>();
        for (String id : List.of("c-long", "c-exact", "c-mid", "c-rel1", "c-rel2")) {
            cs.put(id, chunk(id, id));
        }

        List<KbQaEntitySearch.EntityHit> hits =
            search(es, rs, cs).search(Map.of("kb1", List.of("农村")), 0);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId())
            .as("精确名必须排第一（源侧 CASE WHEN n.name IN $nodes THEN 0 ELSE 1）")
            .isEqualTo("c-exact");
        assertThat(hits.get(0).viaEntities()).containsExactly("农村");
    }

    @Test
    @DisplayName("⭐ 转检索命中时 score 恒为 0（阶段 7 靠这个值识别图谱命中）")
    void scoreStaysZero() {
        List<KbQaEntitySearch.EntityHit> raw = List.of(
            new KbQaEntitySearch.EntityHit("c1", "k1", "正文", null, 0.0, List.of("甲")));
        List<KbHybridSearcher.Hit> hits = KbQaEntitySearch.toHits(raw);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isZero();
    }
}

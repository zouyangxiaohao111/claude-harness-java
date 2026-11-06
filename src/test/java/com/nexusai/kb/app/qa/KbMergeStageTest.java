package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
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
 * 合并阶段 · 顺序块合并 / 短上下文扩展 / 近似重复剔除。
 *
 * <p><b>WHY 这些断言</b>：合并阶段每一条规则都是在「该拼」与「不该拼」之间划线。
 * 划错了的后果分两种，都很难发现：
 * <ul>
 *   <li><b>拼过头</b>：把不同文档的段落缝在一起 ⇒ 答案里出现「张冠李戴」的事实；</li>
 *   <li><b>删过头</b>：把带独立事实的近重复块丢掉 ⇒ 答案少一条，用户以为库里没有。</li>
 * </ul>
 */
@DisplayName("[B5] 合并阶段")
class KbMergeStageTest {

    /** 内存版块表：selectOneById / selectListByQuery 都从 map 里取。 */
    private final Map<String, KbChunkRecord> rows = new HashMap<>();
    private KbChunkMapper mapper;
    private KbMergeStage stage;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(KbChunkMapper.class);
        rows.clear();
        Mockito.when(mapper.selectOneById(Mockito.any()))
            .thenAnswer(inv -> rows.get(String.valueOf(inv.getArgument(0))));
        // ⚠️ 直接返回全部行是安全的：merge() 只按 hits 里的 chunkId 去 records 里取，
        //    多返回不会让未命中的块混进结果。这样可以避开 QueryWrapper 参数形状的耦合。
        Mockito.when(mapper.selectListByQuery(Mockito.any()))
            .thenAnswer(inv -> new ArrayList<>(rows.values()));
        stage = new KbMergeStage(mapper);
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private KbChunkRecord chunk(String id, String knowledgeId, String content, int index) {
        KbChunkRecord r = new KbChunkRecord();
        r.setId(id);
        r.setKnowledgeId(knowledgeId);
        r.setKnowledgeBaseId("kb-1");
        r.setContent(content);
        r.setChunkIndex(index);
        r.setChunkType("text");
        r.setStartAt(0);
        r.setEndAt(0);                 // 坐标不可用 ⇒ 走文本匹配（安全路径）
        r.setContentRevision(0);
        rows.put(id, r);
        return r;
    }

    private static KbHybridSearcher.Hit hit(String chunkId, String knowledgeId, double score) {
        return new KbHybridSearcher.Hit(chunkId, knowledgeId, "", "", score, 1, 1);
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("顺序块合并")
    class SequentialMerge {

        @Test
        @DisplayName("连续的 index 合并成一段")
        void sequentialMerged() {
            chunk("c1", "k1", "农村生活污水处理设施的建设应当符合规划要求", 1);
            chunk("c2", "k1", "并按照技术标准配置相应的处理工艺与配套设施", 2);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.9), hit("c2", "k1", 0.8)), 10);

            assertThat(out).as("相邻块应合并成 1 段").hasSize(1);
            assertThat(out.get(0).content())
                .contains("应当符合规划要求")
                .contains("配置相应的处理工艺");
            assertThat(out.get(0).subChunkIds()).as("被合并的块要留痕，供引用溯源")
                .contains("c2");
            assertThat(out.get(0).score()).as("组内保留最高分").isEqualTo(0.9);
        }

        @Test
        @DisplayName("⭐ index 不连续且无文本包含 ⇒ 不合并")
        void nonSequentialNotMerged() {
            // ⚠️ 两块内容必须"彼此都不包含"，否则会走 JOIN_TEXT（那是对的行为，不是本用例要测的）
            chunk("c1", "k1", "农村生活污水处理设施的建设要求", 1);
            chunk("c9", "k1", "城镇污水处理厂的运行管理办法", 9);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.9), hit("c9", "k1", 0.8)), 10);

            assertThat(out).as("中间隔了 7 块且内容无关 ⇒ 必须是 2 段，否则会把不相干的内容缝在一起")
                .hasSize(2);
        }

        @Test
        @DisplayName("⭐⭐ 不同文档的块即使 index 连续也不合并")
        void differentKnowledgeNotMerged() {
            chunk("c1", "k1", "农村生活污水处理设施的建设要求", 1);
            chunk("c2", "k2", "农村生活污水处理设施的建设标准", 2);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.9), hit("c2", "k2", 0.8)), 10);

            assertThat(out).as("⭐ 跨文档拼接会产生『张冠李戴』的事实").hasSize(2);
        }

        @Test
        @DisplayName("后块完全被前块包含 ⇒ 只留一段并记下 id")
        void subsumedCollapses() {
            String longText = "农村生活污水处理设施的建设应当符合规划要求，并配置相应处理工艺";
            chunk("c1", "k1", longText, 1);
            chunk("c2", "k1", "农村生活污水处理设施的建设应当符合规划要求", 2);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.9), hit("c2", "k1", 0.8)), 10);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).content()).isEqualTo(longText);
            assertThat(out.get(0).subChunkIds()).contains("c2");
        }
    }

    @Nested
    @DisplayName("去重")
    class Dedup {

        @Test
        @DisplayName("内容完全相同的两块 ⇒ 只留一条")
        void identicalContentDeduped() {
            String text = "农村生活污水处理设施的运行维护应当定期开展水质监测";
            chunk("c1", "k1", text, 1);
            chunk("c2", "k2", text, 1);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.9), hit("c2", "k2", 0.8)), 10);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).chunkId()).as("保留分数高的那条").isEqualTo("c1");
        }

        @Test
        @DisplayName("⭐⭐ 近似重复：分数低的被删（源侧判据）")
        void partialOverlapDropLowerScore() {
            // ⚠️ 子串判定要求短文本 ≥12 码点（太短的不算包含）
            String shortText = "农村生活污水处理设施的建设标准以及运行维护";
            String longText = shortText + "管理办法的全部要求都在本节中说明";
            chunk("c1", "k1", longText, 1);
            chunk("c2", "k2", shortText, 1);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.5), hit("c2", "k2", 0.9)), 10);

            assertThat(out).as("一对近似重复只留一条").hasSize(1);
            assertThat(out.get(0).chunkId())
                .as("⭐⭐ 源侧判据是「删分数低的」而不是「删短的」—— 这里短的 0.9 分、长的 0.5 分，"
                    + "所以留下的是短的那条。⚠️ 已知取舍：长的信息量更大但分数低时会丢内容；"
                    + "这是逐字对齐源侧的结果，不擅自改成「永远留长的」")
                .isEqualTo("c2");
        }

        @Test
        @DisplayName("⭐⭐ 近似重复：分数相同时删短的、留长的")
        void partialOverlapTieKeepsLonger() {
            String shortText = "农村生活污水处理设施的建设标准以及运行维护";
            String longText = shortText + "管理办法的全部要求都在本节中说明";
            chunk("c1", "k1", longText, 1);
            chunk("c2", "k2", shortText, 1);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.8), hit("c2", "k2", 0.8)), 10);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).chunkId())
                .as("同分时留长的 —— 长的包含短的全部信息")
                .isEqualTo("c1");
        }

        @Test
        @DisplayName("⭐⭐ 共享样板文字但各有独立事实 ⇒ 两条都留")
        void sharedBoilerplateButDistinctFactsKept() {
            String a = "农村生活污水处理设施运行管理办法规定各项指标应当达标排放并定期报送数据";
            String b = "农村生活污水处理设施运行管理办法规定各项指标应当达标排放设施数量为88座";
            chunk("c1", "k1", a, 1);
            chunk("c2", "k2", b, 1);

            List<KbMergeStage.MergedChunk> out = stage.merge(
                List.of(hit("c1", "k1", 0.9), hit("c2", "k2", 0.8)), 10);

            assertThat(out)
                .as("⭐⭐ 只有重叠率判据时这里会误删一条 —— shorterOnlyFraction 就是为了挡这个")
                .hasSize(2);
        }
    }

    @Nested
    @DisplayName("短上下文扩展")
    class Expand {

        @Test
        @DisplayName("短块只往后扩展：内容变长且命中块本身仍在")
        void expandsNext() {
            chunk("c1", "k1", "该指标为 88.9%", 1);
            String nextText = "（1）覆盖率的统计口径：以行政村为单位，按已接入污水治理设施的农户数"
                + "占全村农户总数的比例计算，数据来源为季度报送台账，经区县住建部门复核后汇总上报。";
            chunk("c2", "k1", nextText, 2);
            rows.get("c1").setNextChunkId("c2");
            rows.get("c1").setPreChunkId(null);

            List<KbMergeStage.MergedChunk> out = stage.merge(List.of(hit("c1", "k1", 0.9)), 10);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).content())
                .as("⭐ 命中块本身必须在结果里 —— 不能为了凑长度把它挤掉")
                .contains("该指标为 88.9%");
            assertThat(out.get(0).content().length())
                .as("扩展后应该比原来长").isGreaterThan("该指标为 88.9%".length());
            assertThat(out.get(0).subChunkIds()).contains("c2");
        }

        @Test
        @DisplayName("⭐⭐ 邻居属于另一个文档 ⇒ 不扩展")
        void crossDocumentNotExpanded() {
            chunk("c1", "k1", "该指标为 88.9%", 1);
            chunk("c2", "k2", "另一个文档的内容，绝不该拼进来".repeat(10), 2);
            rows.get("c1").setNextChunkId("c2");

            List<KbMergeStage.MergedChunk> out = stage.merge(List.of(hit("c1", "k1", 0.9)), 10);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).content())
                .as("⭐⭐ 跨文档扩展会把两个来源缝成一句话 —— 这是最危险的缺陷")
                .doesNotContain("另一个文档的内容");
        }

        @Test
        @DisplayName("块已足够长 ⇒ 不扩展")
        void longEnoughNoExpand() {
            String longText = "农村生活污水处理设施的运行维护要求。".repeat(30);
            assertThat(KbMergeText.runeLen(longText)).isGreaterThan(KbMergeStage.EXPAND_MIN_LEN);
            chunk("c1", "k1", longText, 1);
            chunk("c2", "k1", "不该被拼进来的邻居内容".repeat(20), 2);
            rows.get("c1").setNextChunkId("c2");

            List<KbMergeStage.MergedChunk> out = stage.merge(List.of(hit("c1", "k1", 0.9)), 10);

            assertThat(out.get(0).content()).isEqualTo(longText);
        }
    }

    @Nested
    @DisplayName("⭐⭐⭐ mergeOrderedContent 的预算分配")
    class OrderedContentBudget {

        @Test
        @DisplayName("prev 极长、base 很短 ⇒ base 完整保留")
        void veryLongPrevNeverPushesBaseOut() {
            String prev = "前".repeat(2000);
            String base = "命中块的内容";
            String next = "后".repeat(2000);

            String out = KbMergeStage.mergeOrderedContent(prev, base, next, 850);

            assertThat(out)
                .as("⭐⭐⭐ base 永不被裁 —— 先拼再截断会把命中块挤出上下文，"
                    + "用户拿到的全是与问题无关的前文")
                .contains(base);
            assertThat(KbMergeText.runeLen(out))
                .as("总长度应控制在预算附近（允许少量分隔符开销）")
                .isLessThanOrEqualTo(860);
        }

        @Test
        @DisplayName("prev 与 next 都短 ⇒ 两者都进")
        void bothShortBothIncluded() {
            String out = KbMergeStage.mergeOrderedContent("前文一点点", "中间命中", "后文一点点", 850);
            assertThat(out).contains("前文一点点").contains("中间命中").contains("后文一点点");
        }

        @Test
        @DisplayName("base 自己就超预算 ⇒ 不裁 base，也不加邻居")
        void baseOverBudgetKeptWhole() {
            String base = "命中".repeat(600);
            String out = KbMergeStage.mergeOrderedContent("前文", base, "后文", 100);
            assertThat(out).isEqualTo(base);
        }
    }

    @Nested
    @DisplayName("边界")
    class Edges {

        @Test
        @DisplayName("null / 空输入 ⇒ 空结果，不 NPE")
        void nullSafe() {
            assertThat(stage.merge(null, 10)).isEmpty();
            assertThat(stage.merge(List.of(), 10)).isEmpty();
        }

        @Test
        @DisplayName("命中块在块表里已不存在 ⇒ 跳过（重解析竞态）")
        void missingChunkSkipped() {
            // 不往 mapper 里放 c1
            KbHybridSearcher.Hit h = new KbHybridSearcher.Hit(
                "c1", "k1", "内容", "", 0.9, 1, 1);
            assertThat(stage.merge(List.of(h), 10))
                .as("索引里有、块表已删 ⇒ 跳过，不拿脏数据凑数")
                .isEmpty();
        }

        @Test
        @DisplayName("topK 截断在扩展之前生效")
        void topKTruncates() {
            for (int i = 1; i <= 5; i++) {
                chunk("c" + i, "k" + i, "第 " + i + " 段独立内容".repeat(20), i);
            }
            List<KbHybridSearcher.Hit> hits = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                hits.add(hit("c" + i, "k" + i, 1.0 - i * 0.1));
            }
            List<KbMergeStage.MergedChunk> out = stage.merge(hits, 2);
            assertThat(out).hasSize(2);
            assertThat(out.get(0).chunkId()).isEqualTo("c1");
        }
    }
}

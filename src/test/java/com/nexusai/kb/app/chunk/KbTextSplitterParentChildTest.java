package com.nexusai.kb.app.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 父子分块契约测试 · 对齐 WeKnora {@code chunker.SplitParentChild}（{@code strategy.go:135-184}）。
 *
 * <p><b>WHY 这三条（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>父块保留条件</b>（源侧 {@code strategy.go:172}）：父块只在「切成 &gt;1 个子块」或
 *       「唯一子块内容 ≠ 父块」时保留。这条写错不会有异常 —— 短文档会多出一份与子块完全相同的
 *       父块，检索命中子块后又把同一段内容拼一遍（上下文翻倍、分数虚高）。</li>
 *   <li><b>子块坐标必须是全文坐标</b>（源侧 {@code splitter.go:900-905 mapChildrenToSource}）：
 *       写成父块内相对坐标后，落库的 {@code start_at/end_at} 全部指向文档开头，
 *       文档重建与高亮静默错位。</li>
 *   <li><b>子块 seq 单调</b>（源侧 {@code :167/177-180}）：跨父块必须继续递增，
 *       否则 {@code chunk_index} 重复，按序取块会乱。</li>
 * </ol>
 */
@DisplayName("[B0] 父子分块（父块保留条件 + 全文坐标 + 子块序号）")
class KbTextSplitterParentChildTest {

    private static KbSplitterConfig cfg(int size, int overlap) {
        return new KbSplitterConfig(size, overlap, KbSplitterConfig.DEFAULT_SEPARATORS, "", 0,
            List.of());
    }

    /** 造长文本：固定长度段落，ASCII（保证 char 偏移 == code point 偏移，断言可逐字比对）。 */
    private static String longAsciiText(int approxChars) {
        String para = "Parent child chunking needs deterministic text so every offset is checkable. ";
        StringBuilder sb = new StringBuilder(approxChars + para.length());
        while (sb.length() < approxChars) {
            sb.append(para).append("\n\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("长文本（父 4096 / 子 384）⇒ 至少保留 1 个父块，且父子 start/end 都是全文坐标")
    void longTextRetainsParentsAndUsesDocumentOffsets() {
        String text = longAsciiText(20_000);
        KbSplitterConfig parentCfg = cfg(4096, 0);
        KbSplitterConfig childCfg = cfg(384, 384 / 5);

        KbTextSplitter.ParentChildResult result =
            KbTextSplitter.splitParentChild(text, parentCfg, childCfg);

        assertThat(result.children()).as("长文本必须有子块").isNotEmpty();
        assertThat(result.parents())
            .as("被切成多个子块的父块必须保留（源侧 strategy.go:172）")
            .isNotEmpty();

        // ⭐ 父块：content == 原文 [start, end)
        for (KbChunk p : result.parents()) {
            assertThat(text.substring(p.start(), p.end()))
                .as("父块 seq=%d 的 [start,end) 必须是原文切片（全文坐标）", p.seq())
                .isEqualTo(p.content());
            assertThat(p.start()).isGreaterThanOrEqualTo(0);
            assertThat(p.end()).isLessThanOrEqualTo(text.length());
        }

        // ⭐ 子块：content == 原文 [start, end)，且父指针在父块列表范围内
        int prevSeq = -1;
        for (KbChunk c : result.children()) {
            assertThat(text.substring(c.start(), c.end()))
                .as("子块 seq=%d 的 [start,end) 必须是原文切片（不是父块内相对坐标）", c.seq())
                .isEqualTo(c.content());
            assertThat(c.seq()).as("子块 seq 必须全文档单调递增").isGreaterThan(prevSeq);
            prevSeq = c.seq();
            if (c.parentIndex() >= 0) {
                assertThat(c.parentIndex()).isLessThan(result.parents().size());
            } else {
                assertThat(c.parentIndex()).isEqualTo(KbChunk.NO_PARENT);
            }
        }

        // 至少有一个子块真的挂到了父块上（否则父子结构等于白做）
        assertThat(result.children()).anySatisfy(c -> assertThat(c.parentIndex()).isGreaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("短文本（< 子块大小）⇒ 唯一子块内容 == 父块 ⇒ 父块不保留")
    void shortTextDropsParent() {
        String text = "Short document that fits in one child chunk.";
        assertThat(text.length()).as("测试前提：必须比子块尺寸小").isLessThan(384);

        KbTextSplitter.ParentChildResult result =
            KbTextSplitter.splitParentChild(text, cfg(4096, 0), cfg(384, 76));

        assertThat(result.parents())
            .as("唯一子块与父块内容相同时不需要父块（源侧 strategy.go:172 的否定分支）")
            .isEmpty();
        assertThat(result.children()).hasSize(1);
        assertThat(result.children().get(0).content()).isEqualTo(text);
        assertThat(result.children().get(0).start()).isZero();
        assertThat(result.children().get(0).end()).isEqualTo(text.length());
        assertThat(result.children().get(0).parentIndex()).isEqualTo(KbChunk.NO_PARENT);
    }

    @Test
    @DisplayName("空文本 ⇒ 空结果，不抛")
    void emptyTextYieldsEmptyResult() {
        KbTextSplitter.ParentChildResult result =
            KbTextSplitter.splitParentChild("", cfg(4096, 0), cfg(384, 76));
        assertThat(result.parents()).isEmpty();
        assertThat(result.children()).isEmpty();
        assertThat(result.hasParents()).isFalse();
    }

    @Test
    @DisplayName("mapChildrenToSource：父块内相对偏移换算成全文偏移（含 max 下界修正）")
    void mapChildrenToSourceTranslatesOffsets() {
        // 父块 content 长度为 10，占原文 [100, 110)
        KbChunk parent = new KbChunk("0123456789", "", 0, 100, 110);
        KbChunk sub = new KbChunk("3456", "", 0, 3, 7);

        List<KbChunk> mapped = KbTextSplitter.mapChildrenToSource(parent, List.of(sub));

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).start()).isEqualTo(103);
        assertThat(mapped.get(0).end()).isEqualTo(107);
        assertThat(mapped.get(0).content()).isEqualTo("3456");
    }

    @Test
    @DisplayName("mapChildrenToSource：空区间子块被丢弃；起点不早于父块起点")
    void mapChildrenToSourceDropsEmptyAndClampsStart() {
        KbChunk parent = new KbChunk("0123456789", "", 0, 100, 110);
        // 子块区间为空（start == end）⇒ 丢弃
        KbChunk empty = new KbChunk("", "", 0, 5, 5);
        List<KbChunk> onlyEmpty = KbTextSplitter.mapChildrenToSource(parent, List.of(empty));
        assertThat(onlyEmpty).isEmpty();

        // 表头前置会让 content 比 span 长 ⇒ offset 变小，子块起点可能落到父块起点之前
        // parent.content 16 个字符、span 只有 10 ⇒ offset = 110 - 16 = 94
        KbChunk headerParent = new KbChunk("HEADER0123456789", "", 0, 100, 110);
        List<KbChunk> mapped =
            KbTextSplitter.mapChildrenToSource(headerParent,
                List.of(new KbChunk("0123456789", "", 0, 0, 10)));
        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).start()).as("start 被夹到父块起点（94+0 < 100）").isEqualTo(100);
        assertThat(mapped.get(0).end()).isEqualTo(104);

        // 夹紧后 end <= start ⇒ 丢弃（宁可少一块，也不要一个指向父块之前的错块）
        List<KbChunk> dropped =
            KbTextSplitter.mapChildrenToSource(headerParent,
                List.of(new KbChunk("0123", "", 0, 0, 4)));
        assertThat(dropped).as("94+4=98 <= 100 ⇒ 区间为空，丢弃").isEmpty();
    }

    @Test
    @DisplayName("mergeBreadcrumbs：父子首尾重复行只保留一份")
    void mergeBreadcrumbsDropsDuplicateLine() {
        assertThat(KbTextSplitter.mergeBreadcrumbs("", "A")).isEqualTo("A");
        assertThat(KbTextSplitter.mergeBreadcrumbs("A", "")).isEqualTo("A");
        assertThat(KbTextSplitter.mergeBreadcrumbs("A\nB", "B\nC")).isEqualTo("A\nB\nC");
        assertThat(KbTextSplitter.mergeBreadcrumbs("A", "B")).isEqualTo("A\nB");
    }
}

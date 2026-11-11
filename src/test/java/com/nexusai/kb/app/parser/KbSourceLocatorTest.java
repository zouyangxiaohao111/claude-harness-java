package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源位置溯源意图测试（源站 {@code internal/sourceloc/*}）。
 *
 * <p><b>WHY 这些断言（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>{@code source_locators} 这一列如果只"写进去"但值是错的，前端点引用会跳到错误页码</b> ——
 *       这比"没有定位"更糟：用户会以为引用核实过了。故区间查询、partial 标记必须逐字验证。</li>
 *   <li><b>折叠规则决定"同一页的 3 段引用显示成 1 条还是 3 条"</b>；连续行区间不折叠会让
 *       一个表格块产出几十条 locator，前端列表被刷屏。</li>
 *   <li><b>{@code start/end} 若是 char 偏移，中文文档整体错位</b> —— 这是"看起来有值、
 *       实际全错"的最隐蔽形态，必须用含 emoji 的输入验证码点语义。</li>
 *   <li><b>PDF 空 quote 必须保留</b>：扫描页的区域内可能没有文字，若被过滤掉，
 *       整页定位直接消失（而这个分支只在 PDF 走，普通用例发现不了）。</li>
 * </ol>
 */
@DisplayName("[溯源] 源位置索引（SourceLocator / Index / Align）")
class KbSourceLocatorTest {

    // ════════════════════════════════════════════════════════════════════
    // 1. 基本区间查询
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("区间查询")
    class Locators {

        /**
         * 三段文本各 10 个码点，块区间 [0,10) [10,20) [20,30)。
         * 查 [5,15) 必须命中前两块，且 quote 是各自被覆盖的那半截 ——
         * 若 quote 取整块，用户在查看器里搜不到（被引用的原文只有半句）。
         *
         * <p>⚠️ 这里用 {@code docx} 类型而不是 {@code text}：{@code text} 类型相邻区间会按源站
         * {@code foldInto} 折叠成一条（见 {@link Fold} 的用例），拿它测「命中 2 条」测不出来。
         */
        @Test
        @DisplayName("跨两块查询 ⇒ 返回 2 条 locator，quote 各取重叠片段")
        void returnsLocatorsForOverlappingBlocks() {
            String markdown = "0123456789" + "abcdefghij" + "ABCDEFGHIJ";
            List<KbSourceBlock> blocks = List.of(
                new KbSourceBlock(0, 10, KbSourceLocator.docx(1)),
                new KbSourceBlock(10, 20, KbSourceLocator.docx(2)),
                new KbSourceBlock(20, 30, KbSourceLocator.docx(3)));

            KbSourceIndex index = KbSourceIndex.of(markdown, blocks);
            List<KbSourceLocator> locs = index.locators(5, 15);

            assertThat(locs).hasSize(2);
            assertThat(locs.get(0).quote())
                .as("第一块的 quote 必须只是 [5,10) 这半截").isEqualTo("56789");
            assertThat(locs.get(0).block()).isEqualTo(1);
            assertThat(locs.get(1).quote())
                .as("第二块的 quote 必须只是 [10,15) 这半截").isEqualTo("abcde");
            assertThat(locs.get(1).block()).isEqualTo(2);
            assertThat(locs).allSatisfy(l -> assertThat(l.partial()).as("全覆盖不该标 partial").isFalse());
        }

        /** 区间完全落在块外 ⇒ 空列表（不是「猜一个最近的块」）。 */
        @Test
        @DisplayName("区间不被任何块覆盖 ⇒ 返回空列表")
        void returnsEmptyWhenUncovered() {
            String markdown = "abc";
            KbSourceIndex index = KbSourceIndex.of(markdown,
                List.of(new KbSourceBlock(0, 3, KbSourceLocator.text(0, 3))));

            assertThat(index.locators(3, 6)).isEmpty();
            assertThat(index.locators(5, 4)).as("end<=start 直接空").isEmpty();
        }

        /**
         * ⭐ 这条用例是「maxEnd 前缀最大值」的判据，必须按下面的布局构造：
         * <pre>
         *   blocks（按 start 升序）：B0=[0,10)  B1=[2,4)   B2=[4,6)   B3=[6,8)
         *   maxEnd：               10       10        10        10
         * </pre>
         * 查询起点 = 8。B0 的 start=0 &lt; 8，但它覆盖到 10 ⇒ 必须命中 B0。
         * 若 maxEnd 退化成「直接用该块自己的 end」（B1.end=4 ≤ 8），二分会在 B3 处收尾，
         * <b>B0 被整块漏掉</b>；反之 B0 的 start 太小，所以不能用「只比 start」的朴素二分。
         * 已用变异实验确认：把前缀最大值改成直接赋值后本用例变红。
         */
        @Test
        @DisplayName("长块包住后面几个短块 ⇒ 从尾部查询仍必须命中长块（maxEnd 前缀最大值判据）")
        void longBlockFoundWhenQueryingAfterShortBlocks() {
            String markdown = "x".repeat(20);
            List<KbSourceBlock> blocks = List.of(
                new KbSourceBlock(0, 10, KbSourceLocator.docx(1)),    // 长块
                new KbSourceBlock(2, 4, KbSourceLocator.docx(2)),
                new KbSourceBlock(4, 6, KbSourceLocator.docx(3)),
                new KbSourceBlock(6, 8, KbSourceLocator.docx(4)));

            KbSourceIndex index = KbSourceIndex.of(markdown, blocks);
            List<KbSourceLocator> locs = index.locators(8, 10);

            assertThat(locs).as("只有长块 B0 覆盖 [8,10)").hasSize(1);
            assertThat(locs.get(0).block()).isEqualTo(1);
            assertThat(locs.get(0).quote()).isEqualTo("xx");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. partial 标记
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("partial 标记")
    class Partial {

        /**
         * 只覆盖 [0,10) 与 [20,30)，中间 [10,20) 有真实文字但无源映射 ⇒ 查 [0,30) 必须 partial。
         * <b>这是"引用只对了一半"的唯一信号</b>，丢了前端会声称整段都可定位。
         */
        @Test
        @DisplayName("中间有未覆盖文字 ⇒ 所有 locator 标 partial=true")
        void marksPartialWhenGapHasText() {
            String markdown = "aaaaaaaaaa" + "bbbbbbbbbb" + "cccccccccc";
            List<KbSourceBlock> blocks = List.of(
                new KbSourceBlock(0, 10, KbSourceLocator.text(0, 10)),
                new KbSourceBlock(20, 30, KbSourceLocator.text(20, 30)));

            KbSourceIndex index = KbSourceIndex.of(markdown, blocks);
            List<KbSourceLocator> locs = index.locators(0, 30);

            assertThat(locs).hasSize(2);
            assertThat(locs).allSatisfy(l ->
                assertThat(l.partial()).as("中间 10 个字无源映射 ⇒ 本块部分可定位").isTrue());
        }

        /** 中间只有空白 ⇒ 不算 partial（源站 assign.go:58 用 normalizeMarkdown 判空）。 */
        @Test
        @DisplayName("间隙只有空白 ⇒ 不标 partial")
        void doesNotMarkPartialForWhitespaceGap() {
            String markdown = "aaaa" + "      " + "cccc";
            List<KbSourceBlock> blocks = List.of(
                new KbSourceBlock(0, 4, KbSourceLocator.text(0, 4)),
                new KbSourceBlock(10, 14, KbSourceLocator.text(10, 14)));

            KbSourceIndex index = KbSourceIndex.of(markdown, blocks);
            List<KbSourceLocator> locs = index.locators(0, 14);

            assertThat(locs).hasSize(2);
            assertThat(locs).allSatisfy(l -> assertThat(l.partial()).isFalse());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 折叠
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("折叠（foldInto）")
    class Fold {

        /** 连续 sheet 行区间 ⇒ 折成一条并扩区间；否则一个表格块会刷出几十条 locator。 */
        @Test
        @DisplayName("连续行区间 ⇒ 折叠为 rowStart=1,rowEnd=20")
        void foldsAdjacentSheetRows() {
            // 每行占 6 个码点「| a |\n」，20 行 = 120 码点
            String markdown = "| a |\n".repeat(20);
            List<KbSourceBlock> blocks = new ArrayList<>();
            for (int row = 1; row <= 20; row++) {
                int start = (row - 1) * 6;
                blocks.add(new KbSourceBlock(start, start + 5,
                    KbSourceLocator.sheet("Sheet1", row, row)));
            }
            KbSourceIndex index = KbSourceIndex.of(markdown, blocks);
            List<KbSourceLocator> locs = index.locators(0, 120);

            assertThat(locs).as("20 行应折叠成 1 条").hasSize(1);
            assertThat(locs.get(0).rowStart()).isEqualTo(1);
            assertThat(locs.get(0).rowEnd()).isEqualTo(20);
        }

        /** 行区间断开（缺 3 行以上）⇒ 不折叠，必须两条。 */
        @Test
        @DisplayName("行区间断开 ⇒ 不折叠")
        void doesNotFoldDisjointSheetRows() {
            List<KbSourceLocator> list = new ArrayList<>();
            KbSourceIndex.append(list, KbSourceLocator.sheet("S", 1, 2));
            KbSourceIndex.append(list, KbSourceLocator.sheet("S", 10, 11));

            assertThat(list).hasSize(2);
        }

        /** 不同工作表即使行号相同也不能折叠（否则定位会跳到另一个表）。 */
        @Test
        @DisplayName("不同 sheet 名 ⇒ 不折叠")
        void doesNotFoldAcrossSheets() {
            List<KbSourceLocator> list = new ArrayList<>();
            KbSourceIndex.append(list, KbSourceLocator.sheet("Sheet1", 1, 1));
            KbSourceIndex.append(list, KbSourceLocator.sheet("Sheet2", 2, 2));

            assertThat(list).hasSize(2);
        }

        /** 有 bbox 的 PDF 不折叠（页面内不同区域是不同位置）。 */
        @Test
        @DisplayName("PDF 带 bbox ⇒ 不折叠；不带 bbox 同页 ⇒ 折叠")
        void foldsOnlyPdfWithoutBbox() {
            List<KbSourceLocator> withBox = new ArrayList<>();
            KbSourceIndex.append(withBox, KbSourceLocator.pdf(3, new double[]{0, 0, 1, 0.5}));
            KbSourceIndex.append(withBox, KbSourceLocator.pdf(3, new double[]{0, 0.5, 1, 1}));
            assertThat(withBox).as("不同区域不折叠").hasSize(2);

            List<KbSourceLocator> noBox = new ArrayList<>();
            KbSourceIndex.append(noBox, KbSourceLocator.pdf(3, null));
            KbSourceIndex.append(noBox, KbSourceLocator.pdf(3, null));
            assertThat(noBox).as("同页无区域 ⇒ 折叠成 1 条").hasSize(1);
        }

        /** 同一张幻灯片折叠；不同页不折叠。 */
        @Test
        @DisplayName("slide：同页折叠，异页不折叠")
        void foldsSameSlideOnly() {
            List<KbSourceLocator> list = new ArrayList<>();
            KbSourceIndex.append(list, KbSourceLocator.slide(2, null));
            KbSourceIndex.append(list, KbSourceLocator.slide(2, null));
            KbSourceIndex.append(list, KbSourceLocator.slide(3, null));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).slide()).isEqualTo(2);
            assertThat(list.get(1).slide()).isEqualTo(3);
        }

        /** docx 不折叠：每个 body 级段落是独立位置。 */
        @Test
        @DisplayName("docx ⇒ 永不折叠")
        void neverFoldsDocx() {
            List<KbSourceLocator> list = new ArrayList<>();
            KbSourceIndex.append(list, KbSourceLocator.docx(5));
            KbSourceIndex.append(list, KbSourceLocator.docx(6));
            assertThat(list).hasSize(2);
        }

        /** 折叠后的 quote 用空格连接（源站 joinQuote）。 */
        @Test
        @DisplayName("折叠后 quote 空格连接")
        void joinsQuotesOnFold() {
            List<KbSourceLocator> list = new ArrayList<>();
            KbSourceIndex.append(list, KbSourceLocator.slide(1, null).withQuote("第一段"));
            KbSourceIndex.append(list, KbSourceLocator.slide(1, null).withQuote("第二段"));

            assertThat(list).hasSize(1);
            assertThat(list.get(0).quote()).isEqualTo("第一段 第二段");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. PDF 空 quote 保留
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PDF 特例")
    class PdfEmptyQuote {

        /**
         * 源站 assign.go:63：{@code if quote == "" && type != pdf { continue }}。
         * 扫描页区域内可能一个字都没有 —— 若把空 quote 的 PDF 也过滤掉，整页定位直接消失，
         * 用户点引用什么也看不到（比"定位到页但无高亮"更糟）。
         *
         * <p>构造方式：整篇只有一条图片引用 ⇒ 清洗后 quote 为空（源站 {@code fullQuote} 把
         * {@code ![](..)} 换成空格），这是真实会发生的形态（扫描页只有一张图）。
         */
        @Test
        @DisplayName("空 quote 的 pdf locator 保留；空 quote 的非 pdf 被跳过")
        void keepsPdfEvenWhenQuoteEmpty() {
            String markdown = "![图](http://x/a.png)";
            List<KbSourceBlock> blocks = List.of(
                new KbSourceBlock(0, 21, KbSourceLocator.pdf(1, null)),
                new KbSourceBlock(0, 21, KbSourceLocator.docx(1)));

            KbSourceIndex index = KbSourceIndex.of(markdown, blocks);
            List<KbSourceLocator> locs = index.locators(0, 21);

            assertThat(locs).hasSize(1);
            assertThat(locs.get(0).type()).as("PDF 空 quote 必须留下来").isEqualTo(KbSourceLocator.TYPE_PDF);
            assertThat(locs.get(0).page()).isEqualTo(1);
            assertThat(locs.get(0).quote())
                .as("没有文字就没有引用片段（Go omitempty ⇒ Java 侧存 null，JSON 里都省略）")
                .isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 码点偏移
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("码点偏移（rune 语义）")
    class RuneOffsets {

        /**
         * 「中文」2 个 char = 2 个码点；「😀」2 个 char = <b>1</b> 个码点。
         * 若实现用 {@code String.length()}，块区间会与分块器的码点区间错位 3 格，
         * 引用整体偏移 —— 而输入里全是中文，肉眼看不出来。
         */
        @Test
        @DisplayName("含中文与 emoji 的文本 ⇒ start/end 是码点偏移")
        void usesCodePointOffsets() {
            String markdown = "中文😀结尾";      // 码点：中 文 😀 结 尾 = 5；char：6
            List<KbSourceBlock> blocks = KbSourceIndex.textBlocks(markdown);

            assertThat(blocks).hasSize(1);
            assertThat(blocks.get(0).start()).isEqualTo(0);
            assertThat(blocks.get(0).end())
                .as("必须是码点数 5，而不是 String.length()=6")
                .isEqualTo(5);
            assertThat(markdown.length()).as("反证：char 长度确实是 6").isEqualTo(6);
            assertThat(blocks.get(0).locator().end()).isEqualTo(5);
        }

        /**
         * 多段落时第二段的起点必须是码点偏移（前面有 emoji 才会暴露 char/码点差异）。
         *
         * <p>⚠️ 区间<b>含段尾换行</b>（源站 text.go:29-31 {@code emit(lineStart)} 里 lineStart 已
         * 越过上一个换行，故第一段是 [0,5) 而不是 [0,4)）。这是源站行为，不是笔误。
         */
        @Test
        @DisplayName("多段落 + emoji ⇒ 各段码点区间正确")
        void multiParagraphOffsets() {
            String markdown = "😀第一段\n\n第二段";
            List<KbSourceBlock> blocks = KbSourceIndex.textBlocks(markdown);

            assertThat(blocks).hasSize(2);
            assertThat(blocks.get(0).start()).isEqualTo(0);
            assertThat(blocks.get(0).end()).as("😀 是 1 个码点 ⇒ 第一段 [0,5)（含段尾换行）").isEqualTo(5);
            assertThat(blocks.get(1).start()).isEqualTo(6);
            assertThat(blocks.get(1).end()).isEqualTo(9);
        }

        /** 码点截断：300 个 emoji 不能被截成 300 个 char（会切成半个代理对）。 */
        @Test
        @DisplayName("cleanQuote 按码点截断，不切碎 emoji")
        void capByCodePoints() {
            String many = "😀".repeat(400);
            String cut = KbSourceIndex.cleanQuote(many);

            assertThat(cut.codePointCount(0, cut.length())).isEqualTo(KbSourceLocator.QUOTE_MAX);
            assertThat(cut).as("截断后必须仍是完整 emoji 序列（不能有落单代理字符）")
                .isEqualTo("😀".repeat(KbSourceLocator.QUOTE_MAX));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. cleanQuote
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cleanQuote 清洗")
    class CleanQuote {

        /** 图片整段删；链接只留锚文本（URL 不是证据）。 */
        @Test
        @DisplayName("删图片、留链接锚文本")
        void stripsImagesKeepsAnchor() {
            String raw = "见图 ![示意图](http://a/x.png) 与 [锚文本](http://b/y) 结束";

            String cleaned = KbSourceIndex.cleanQuote(raw);

            assertThat(cleaned).doesNotContain("x.png").doesNotContain("http://");
            assertThat(cleaned).contains("锚文本").contains("见图").contains("结束");
        }

        /** HTML 标签与注释删除；实体反解成字。 */
        @Test
        @DisplayName("删 HTML 标签、反解实体")
        void stripsTagsAndUnescapes() {
            String raw = "<p>甲&nbsp;乙</p><!-- 注释 -->丙 &lt;丁&gt;";

            String cleaned = KbSourceIndex.cleanQuote(raw);

            assertThat(cleaned).doesNotContain("<p>").doesNotContain("<!--").doesNotContain("&nbsp;");
            assertThat(cleaned).contains("甲").contains("丙").contains("<丁>");
        }

        /** 超过 300 个码点必须截断（防止整块正文塞进 JSON 列）。 */
        @Test
        @DisplayName("超 300 码点截断")
        void capsAt300() {
            String raw = "字".repeat(500);
            String cleaned = KbSourceIndex.cleanQuote(raw);

            assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(300);
        }

        /** 连续空白压成一个空格。 */
        @Test
        @DisplayName("空白压缩")
        void collapsesWhitespace() {
            assertThat(KbSourceIndex.cleanQuote("  甲   \n\n  乙  ")).isEqualTo("甲 乙");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. 结构对齐 / 重映射 / JSON
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("结构与序列化")
    class StructureAndJson {

        /** Markdown 里有重复文本时不能瞎对齐（源站 align.go:41-47：出现次数必须一致）。 */
        @Test
        @DisplayName("出现次数与单元数不等 ⇒ 放弃该单元")
        void skipsAmbiguousUnits() {
            String markdown = "## 标题\n\n同文\n\n同文";
            List<KbSourceIndex.Unit> units = List.of(
                new KbSourceIndex.Unit("同文", KbSourceLocator.docx(1)),
                new KbSourceIndex.Unit("同文", KbSourceLocator.docx(2)),
                new KbSourceIndex.Unit("同文", KbSourceLocator.docx(3)));

            List<KbSourceBlock> blocks = KbSourceIndex.align(markdown, units);

            assertThat(blocks).as("3 个单元但文档里只有 2 处 ⇒ 一个都对齐不了（不猜）").isEmpty();
        }

        /** CRLF → LF 后所有偏移左移，必须重新对齐而不是原样套用。 */
        @Test
        @DisplayName("remap：CRLF 文本 → LF 文本，偏移随之前移")
        void remapsAfterLineEndingNormalization() {
            String oldText = "第一段\r\n\r\n第二段";
            String newText = "第一段\n\n第二段";
            List<KbSourceBlock> old = KbSourceIndex.textBlocks(oldText);
            assertThat(old).hasSize(2);
            assertThat(old.get(1).start()).as("CRLF 下第二段从码点 7 开始").isEqualTo(7);

            List<KbSourceBlock> remapped = KbSourceIndex.remap(old, oldText, newText);

            assertThat(remapped).hasSize(2);
            assertThat(remapped.get(1).start()).as("LF 下第二段从码点 5 开始").isEqualTo(5);
        }

        /** JSON 字段名必须是源站的 snake_case，且 null 省略、空列表存 null。 */
        @Test
        @DisplayName("JSON：snake_case + 空值省略 + 空列表 → null")
        void serializesLikeUpstream() {
            String json = KbSourceLocator.toJson(List.of(
                KbSourceLocator.sheet("Sheet1", 1, 20).withQuote("引用")));

            assertThat(json).contains("\"type\":\"sheet\"")
                .contains("\"sheet\":\"Sheet1\"")
                .contains("\"row_start\":1")
                .contains("\"row_end\":20")
                .contains("\"quote\":\"引用\"");
            assertThat(json).as("未设置字段必须省略（源站 omitempty）")
                .doesNotContain("rowStart").doesNotContain("\"page\":0").doesNotContain("\"partial\":false");

            assertThat(KbSourceLocator.toJson(List.of())).as("空列表存 NULL").isNull();
            assertThat(KbSourceLocator.toJson(null)).isNull();
        }

        /** textBlocks：空行分段，纯空白行不算段（区间含段尾换行，源站 text.go:22-32）。 */
        @Test
        @DisplayName("textBlocks：空行分段")
        void parsesParagraphs() {
            String markdown = "第一段\n第二行\n\n第二段\n\n\n第三段";
            List<KbSourceBlock> blocks = KbSourceIndex.textBlocks(markdown);

            assertThat(blocks).hasSize(3);
            assertThat(blocks.get(0).start()).isEqualTo(0);
            assertThat(blocks.get(0).end()).as("第一段含尾随换行 ⇒ [0,8)").isEqualTo(8);
            assertThat(blocks.get(2).start()).as("第三段起点（前导空行不算进段）").isEqualTo(15);
            assertThat(blocks.get(2).end()).isEqualTo(18);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 8. 真实文件端到端（这才是「能溯源」的判据）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 解析器 → 源块 → 分块 → locator 全链路，用 POI 造的真实二进制文件跑。
     *
     * <p><b>WHY 必须有这一组</b>：纯算法用例全绿只证明「给定块能查对」，
     * 但 {@code source_locators} 是 NULL 的根因就是<b>没人产出块</b>。
     * 若 POI 抽取在真实文件上抛异常（返回空列表），算法再对，库里仍然全是 NULL ——
     * 而且编译期毫无信号。故这一组断言必须落到「块数 > 0 且类型正确」。
     */
    @Nested
    @DisplayName("真实 Office 文件端到端")
    class RealOfficeFiles {

        @Test
        @DisplayName("docx ⇒ 每个正文段落/表格行一个 docx block，且 type=docx")
        void docxProducesDocxBlocks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            java.nio.file.Path file = KbTestDocuments.writeDocx(dir);
            KbParsedDocument parsed = new KbOfficeParser()
                .parse(file, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(parsed.sourceBlocks()).as("docx 必须产出源块（否则 source_locators 永远 NULL）")
                .isNotEmpty();
            assertThat(parsed.sourceBlocks())
                .allSatisfy(b -> assertThat(b.locator().type()).isEqualTo(KbSourceLocator.TYPE_DOCX));
            assertThat(parsed.sourceBlocks())
                .as("block 序号 1-based 且递增")
                .extracting(b -> b.locator().block())
                .allSatisfy(n -> assertThat(n).isPositive());
            // 正文里真有的句子必须能被定位到（说明对齐成功，不是只抽了结构）。
            // ⚠️ quote 是「查询时填」的（源站 assign.go:66-68），块本身不带 quote。
            KbSourceIndex index = KbSourceIndex.of(parsed.text(), parsed.sourceBlocks());
            List<KbSourceLocator> locs = index.locators(0, parsed.text().codePointCount(0, parsed.text().length()));
            assertThat(locs).isNotEmpty();
            assertThat(locs)
                .as("整篇查询必须能带出正文引用（证明对齐到了真实句子）")
                .anySatisfy(l -> assertThat(l.quote()).contains("总体情况"));
            assertThat(parsed.metadata()).containsEntry(KbParsedDocument.META_SOURCE_BLOCKS,
                String.valueOf(parsed.sourceBlocks().size()));
        }

        @Test
        @DisplayName("xlsx ⇒ sheet block 带工作表名与 1-based 行号")
        void xlsxProducesSheetBlocks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            java.nio.file.Path file = KbTestDocuments.writeXlsx(dir);
            KbParsedDocument parsed = new KbOfficeParser()
                .parse(file, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(parsed.sourceBlocks()).isNotEmpty();
            assertThat(parsed.sourceBlocks())
                .allSatisfy(b -> {
                    assertThat(b.locator().type()).isEqualTo(KbSourceLocator.TYPE_SHEET);
                    assertThat(b.locator().sheet()).isNotBlank();
                    assertThat(b.locator().rowStart()).as("行号必须 1-based").isPositive();
                });
            assertThat(parsed.sourceBlocks())
                .as("两个工作表都应出现")
                .extracting(b -> b.locator().sheet())
                .contains("项目清单", "备注");
        }

        @Test
        @DisplayName("pptx ⇒ slide block 带 1-based 页号")
        void pptxProducesSlideBlocks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            java.nio.file.Path file = KbTestDocuments.writePptx(dir);
            KbParsedDocument parsed = new KbOfficeParser()
                .parse(file, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(parsed.sourceBlocks()).isNotEmpty();
            assertThat(parsed.sourceBlocks())
                .allSatisfy(b -> assertThat(b.locator().type()).isEqualTo(KbSourceLocator.TYPE_SLIDE));
            assertThat(parsed.sourceBlocks())
                .extracting(b -> b.locator().slide())
                .as("两页幻灯片都应被定位到")
                .contains(1, 2);
        }

        @Test
        @DisplayName("md 文本 ⇒ text block，且用分块区间查得到 locator（闭环）")
        void textFileClosesTheLoop(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            java.nio.file.Path file = dir.resolve("说明.md");
            java.nio.file.Files.writeString(file,
                "# 标题\n\n第一段正文内容。\n\n第二段正文内容。\n");
            KbParsedDocument parsed = new KbTextParser()
                .parse(file, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(parsed.sourceBlocks()).as("纯文本必须产出段落块").hasSizeGreaterThanOrEqualTo(3);
            KbSourceIndex index = KbSourceIndex.of(parsed.text(), parsed.sourceBlocks());

            // 用「第二段」的码点区间当成分块区间查（模拟 chunker 的输入）
            int start = parsed.text().indexOf("第二段");
            int cpStart = parsed.text().codePointCount(0, start);
            List<KbSourceLocator> locs = index.locators(cpStart, cpStart + 8);

            assertThat(locs).as("分块区间必须命中源块（否则库里写 NULL）").isNotEmpty();
            assertThat(locs.get(0).type()).isEqualTo(KbSourceLocator.TYPE_TEXT);
            assertThat(locs.get(0).quote()).contains("第二段正文");
        }

        @Test
        @DisplayName("csv 被改写为 Markdown 表格 ⇒ 不产出块（避免给错位置）")
        void csvDoesNotProduceMisleadingBlocks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
                throws Exception {
            java.nio.file.Path file = KbTestDocuments.writeCsv(dir);
            KbParsedDocument parsed = new KbTextParser()
                .parse(file, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(parsed.metadata()).containsEntry(KbTextParser.META_STRUCTURED, "true");
            assertThat(parsed.sourceBlocks())
                .as("正文已被改写成 Markdown 表格 ⇒ 原文偏移不再对应，⛔ 不能产出块")
                .isEmpty();
        }
    }
}

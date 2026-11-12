package com.nexusai.kb.app.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 逐段产出 {@link KbSourceBlock}（页号 + 页面内区域）的判据。
 *
 * <p><b>WHY</b>（审计 P0）：本仓 PDF 原先产出 0 个 source block ⇒ 引用只能指「第 N 块」，
 * 指不到「第 3 页的某块区域」。源站 docreader {@code parser/source_locator.py} 逐段产
 * {@code {type:"pdf", page, bbox}}，本类补齐这条链。
 *
 * <p><b>判据都落在「能被下游消费」上</b>，不是源码文本断言：
 * <ol>
 *   <li>块区间<b>必须真的框住正文</b>：用 {@code text.codePoints()} 切出来再比对段落文本 ——
 *       char/码点口径写错时（中文、emoji），切片会错位或抛越界。</li>
 *   <li>块必须能被 {@link KbSourceIndex} 查到 locator：这才是引用的入口。</li>
 *   <li>空白页（C17 已跳过）不得产块；空块区间会被 {@code KbSourceIndex.of} 丢弃，报了也没用。</li>
 *   <li>正文<b>逐字不变</b>：同一 PDF 两次解析文本必须相等，且关键文本原样在正文里。</li>
 * </ol>
 */
@DisplayName("[P0-C20] PDF 逐段产 source block（page + bbox）")
class KbPdfSourceBlockTest {

    private static final PDRectangle A4 = new PDRectangle(595f, 842f);

    /**
     * 正文行 · 长度必须<b>留在 A4 页宽内</b>：字形出 CropBox 的会被隐藏文字过滤剔除（既有行为），
     * 用太长的行会把「正文被截断」误读成本次改动破坏了正文。
     */
    private static final String LINE1 = "This page has a real text layer with enough text.";
    private static final String LINE2 = "Second body line helps the layout ordering check.";
    private static final String PAGE_BODY = "Page 1\n" + LINE1 + "\n" + LINE2;

    // ── ① 三页文字 PDF → 三个页号都有块 ────────────────────────────────────

    @Test
    @DisplayName("3 页文字 PDF → 块非空、页号 1/2/3 都有、区间落在码点范围内且递增")
    void threeTextPagesProduceBlocks(@TempDir Path dir) throws Exception {
        Path pdf = writeTextPdf(dir, 3);

        KbParsedDocument result = parse(pdf, dir);

        assertThat(result.sourceBlocks())
            .as("审计 P0：PDF 不能再是 0 个 source block")
            .isNotEmpty();

        int totalCp = codePointLength(result.text());
        assertThat(totalCp).as("正文必须非空（否则下面的区间断言没有意义）").isGreaterThan(0);

        Set<Integer> pages = new LinkedHashSet<>();
        int prevEnd = 0;
        for (KbSourceBlock b : result.sourceBlocks()) {
            assertThat(b.isValid()).as("空区间/负区间会被 KbSourceIndex.of 直接丢弃").isTrue();
            assertThat(b.locator().type()).isEqualTo(KbSourceLocator.TYPE_PDF);
            assertThat(b.locator().page()).as("页号必须 1-based 且落在解析页里").isBetween(1, 3);
            assertThat(b.start()).as("start 必须落在正文码点范围内").isGreaterThanOrEqualTo(0);
            assertThat(b.end()).as("end 必须落在正文码点范围内").isLessThanOrEqualTo(totalCp);
            assertThat(b.end()).as("end 必须严格大于 start").isGreaterThan(b.start());
            assertThat(b.start()).as("块按 start 升序且不重叠").isGreaterThanOrEqualTo(prevEnd);
            prevEnd = b.end();
            pages.add(b.locator().page());
        }
        assertThat(pages).as("三条文字页都必须有块").containsExactlyInAnyOrder(1, 2, 3);

        // 区间必须真的框住该段正文：按码点切片后比对（⛔ 用 char 下标切中文会错位）
        assertThat(normalize(pageSlice(result, 1)))
            .as("第 1 页的块必须框住这一页的正文，而不是页标题或别的页")
            .isEqualTo(normalize(PAGE_BODY));
        assertThat(normalize(pageSlice(result, 3)))
            .isEqualTo(normalize("Page 3\n" + LINE1 + "\n" + LINE2));

        // bbox：必须是真实区域（比例值 0–1），⛔ 不能是伪造的 0,0,0,0
        for (KbSourceBlock b : result.sourceBlocks()) {
            double[] box = b.locator().bbox();
            assertThat(box).as("文字页有字形 ⇒ bbox 不能缺失").isNotNull();
            assertThat(box).hasSize(4);
            for (double v : box) {
                assertThat(v).as("bbox 是比例值，必须夹在 0–1").isBetween(0.0, 1.0);
            }
            assertThat(box[2]).as("右边界必须大于左边界").isGreaterThan(box[0]);
            assertThat(box[3]).as("下边界必须大于上边界").isGreaterThan(box[1]);
            assertThat(box[2]).as("绝不能伪造 0,0,0,0").isGreaterThan(0.0);
            assertThat(box[3]).as("绝不能伪造 0,0,0,0").isGreaterThan(0.0);
        }

        // 覆盖率：页标题（## 第 N 页）与分隔空行不属于任何段 ⇒ 不可能 100%
        int covered = coveredCodePoints(result);
        double ratio = (double) covered / totalCp;
        assertThat(ratio).as("正文段落必须被块覆盖（实测 %.1f%%）", ratio * 100).isGreaterThan(0.5);
    }

    // ── ② 块能被 KbSourceIndex 按区间查到 locator ───────────────────────────

    @Test
    @DisplayName("KbSourceIndex.of(text, blocks) → 每个块区间都能查到对应页的 pdf locator")
    void indexQueriesBlocksByRange(@TempDir Path dir) throws Exception {
        Path pdf = writeTextPdf(dir, 3);
        KbParsedDocument result = parse(pdf, dir);

        KbSourceIndex index = KbSourceIndex.of(result.text(), result.sourceBlocks());
        assertThat(index.isEmpty()).as("有块就必须建出非空索引").isFalse();

        for (KbSourceBlock b : result.sourceBlocks()) {
            List<KbSourceLocator> locators = index.locators(b.start(), b.end());
            assertThat(locators)
                .as("块 [%s, %s) 必须能查到定位", b.start(), b.end())
                .isNotEmpty();
            assertThat(locators)
                .as("查到的定位必须包含该块自己的页号")
                .anySatisfy(loc -> {
                    assertThat(loc.type()).isEqualTo(KbSourceLocator.TYPE_PDF);
                    assertThat(loc.page()).isEqualTo(b.locator().page());
                });
            // 引用片段来自正文，不能是空串（pdf 空 quote 只在「区域里没有文字」时才允许）
            assertThat(locators.get(0).quote()).as("引用片段必须来自正文").isNotBlank();
        }

        // 位置查询（图片定位用它）同样能命中
        KbSourceBlock first = result.sourceBlocks().get(0);
        assertThat(index.locatorsAt(first.start())).isNotEmpty();
    }

    // ── ③ 空页不产块 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("全空白 PDF → 0 个块（空白页不产标题也不产块）")
    void blankPagesProduceNoBlocks(@TempDir Path dir) throws Exception {
        Path pdf = writeBlankPdf(dir, 2);

        KbParsedDocument result = parse(pdf, dir);

        assertThat(result.text()).as("空白页不产任何正文").isEmpty();
        assertThat(result.sourceBlocks()).as("没有正文就没有可定位的段落").isEmpty();
        assertThat(result.metadata()).containsEntry(KbParsedDocument.META_SOURCE_BLOCKS, "0");
    }

    @Test
    @DisplayName("3 页：第 2 页空白 → 只有第 1/3 页有块（块数不等于页数）")
    void blankMiddlePageHasNoBlock(@TempDir Path dir) throws Exception {
        Path pdf = writeMixedPdf(dir);

        KbParsedDocument result = parse(pdf, dir);

        Set<Integer> pages = new LinkedHashSet<>();
        for (KbSourceBlock b : result.sourceBlocks()) {
            pages.add(b.locator().page());
            assertThat(b.isValid()).isTrue();
        }
        assertThat(pages).as("空白页（C17 已跳过）不得产块").containsExactlyInAnyOrder(1, 3);
        assertThat(result.text()).doesNotContain("## 第 2 页");
    }

    // ── ④ 正文逐字不变 ────────────────────────────────────────────────────

    @Test
    @DisplayName("同一 PDF 两次解析正文完全相同，且关键文本/页序原样保留")
    void textIsUnchangedAcrossRuns(@TempDir Path dir) throws Exception {
        Path pdf = writeTextPdf(dir, 3);

        KbParsedDocument first = parse(pdf, dir);
        KbParsedDocument second = parse(pdf, dir);

        assertThat(second.text()).as("本批只加 sourceBlocks，正文必须逐字不变").isEqualTo(first.text());
        assertThat(first.text())
            .contains("## 第 1 页")
            .contains("## 第 2 页")
            .contains("## 第 3 页")
            .contains("Page 1")
            .contains("Page 3")
            .contains(LINE1)
            .contains(LINE2);
        assertThat(first.text().indexOf("## 第 1 页"))
            .as("页序不得变化")
            .isLessThan(first.text().indexOf("## 第 3 页"));
    }

    // ── ⑤ 码点口径（不是 char） ──────────────────────────────────────────

    @Test
    @DisplayName("块区间按码点换算：emoji（代理对）后面不留空隙；strip/truncate 也按码点对齐")
    void offsetsAreCodePointsNotChars() {
        String raw = "a\uD83D\uDE00b";     // "a😀b"：4 个 char / 3 个码点
        List<KbPdfParser.PendingBlock> pending = List.of(
            new KbPdfParser.PendingBlock(1, 0, 3, null));   // char [0,3) = "a😀"

        List<KbSourceBlock> blocks = KbPdfParser.toSourceBlocks(pending, raw, 0, raw);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).start()).isEqualTo(0);
        assertThat(blocks.get(0).end())
            .as("char 口径会算成 3；码点口径必须是 2")
            .isEqualTo(2);
        int[] cps = raw.codePoints().toArray();
        assertThat(new String(cps, blocks.get(0).start(), blocks.get(0).length())).isEqualTo("a\uD83D\uDE00");

        // 前导空白被 strip 掉 ⇒ 偏移整体左移同样的码点数
        String padded = "\n\nab";
        List<KbSourceBlock> shifted = KbPdfParser.toSourceBlocks(
            List.of(new KbPdfParser.PendingBlock(2, 2, 4, null)), padded, 2, "ab");
        assertThat(shifted).hasSize(1);
        assertThat(shifted.get(0).start()).isZero();
        assertThat(shifted.get(0).end()).isEqualTo(2);

        // 截断后越界的块被裁剪（end 不超过正文码点数），完全越界的块被丢弃
        List<KbSourceBlock> clipped = KbPdfParser.toSourceBlocks(
            List.of(new KbPdfParser.PendingBlock(3, 2, 5, null),
                new KbPdfParser.PendingBlock(4, 5, 6, null)), "abcdef", 0, "abcd");
        assertThat(clipped).hasSize(1);
        assertThat(clipped.get(0).start()).isEqualTo(2);
        assertThat(clipped.get(0).end()).as("clamp 到正文码点数").isEqualTo(4);
    }

    // ── 夹具与小工具 ──────────────────────────────────────────────────────

    private static KbParsedDocument parse(Path pdf, Path dir) {
        return new KbPdfParser(null).parse(pdf,
            KbParseContext.defaults().withoutVision().withWorkDir(dir.resolve("imgs")));
    }

    /** 每页三行文字（"Page N" + 两行正文），行距足够大 ⇒ 三行不会并成一行。 */
    private static Path writeTextPdf(Path dir, int pages) throws IOException {
        Path file = dir.resolve("电子版-" + pages + "页.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + i);
                    cs.newLineAtOffset(0, -20);
                    cs.showText(LINE1);
                    cs.newLineAtOffset(0, -20);
                    cs.showText(LINE2);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** {@code pages} 个真空白页（不建内容流）。 */
    private static Path writeBlankPdf(Path dir, int pages) throws IOException {
        Path file = dir.resolve("全空白-" + pages + "页.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(A4));
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 3 页：第 1 页文字、第 2 页真空白、第 3 页文字。 */
    private static Path writeMixedPdf(Path dir) throws IOException {
        Path file = dir.resolve("中间空白页.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int pageNo : new int[]{1, 2, 3}) {
                PDPage page = new PDPage(A4);
                doc.addPage(page);
                if (pageNo == 2) {
                    continue;   // 真空白页
                }
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + pageNo);
                    cs.newLineAtOffset(0, -20);
                    cs.showText(LINE1);
                    cs.newLineAtOffset(0, -20);
                    cs.showText(LINE2);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 某页所有块的切片文本（按码点切，用换行连接）。 */
    private static String pageSlice(KbParsedDocument doc, int page) {
        int[] cps = doc.text().codePoints().toArray();
        List<String> parts = new ArrayList<>();
        for (KbSourceBlock b : doc.sourceBlocks()) {
            if (b.locator().page() != null && b.locator().page() == page) {
                parts.add(new String(cps, b.start(), b.end() - b.start()));
            }
        }
        return String.join("\n", parts);
    }

    /** 被块覆盖的码点数（测试侧独立实现，避免与被测代码共用同一套换算）。 */
    private static int coveredCodePoints(KbParsedDocument doc) {
        int total = codePointLength(doc.text());
        boolean[] hit = new boolean[total];
        for (KbSourceBlock b : doc.sourceBlocks()) {
            for (int i = Math.max(0, b.start()); i < Math.min(total, b.end()); i++) {
                hit[i] = true;
            }
        }
        int n = 0;
        for (boolean h : hit) {
            if (h) {
                n++;
            }
        }
        return n;
    }

    private static int codePointLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 压空白再比对：排版/换行差异不算「区间框错了」。 */
    private static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }
}

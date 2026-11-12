package com.nexusai.kb.app.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 空白页的标题产出判据。
 *
 * <p><b>WHY</b>：{@code KbPdfParser} 过去对每一页无条件追加 {@code ## 第 N 页}，于是「一份全是空白页的
 * PDF」解析出来的正文长度是 8（只有标题），上层「空产出判 failed」的护栏判不出来 ⇒ 空 PDF 仍显示
 * completed。源站 docreader（{@code docreader/parser/pdf_parser.py}）的做法是：空文本块直接丢弃，
 * 不产 heading。
 *
 * <p><b>判据</b>：只有「有可提取文字（去空白后非空）∨ 被判为扫描页（visionPages 含它）∨ 有图片」
 * 的页才产 {@code ## 第 N 页}；真正的空白页（无文字、非扫描、无图）跳过，且不产任何正文。
 * 页号仍取真实页码（跳页不影响后续页的编号）。
 */
@DisplayName("[B2b] PDF 空白页不产页标题（对齐源站丢弃空文本块）")
class KbPdfEmptyPageTest {

    private static final PDRectangle A4 = new PDRectangle(595f, 842f);

    @Test
    @DisplayName("纯空白 PDF（无文字无图）→ 解析文本为空、textLength=0")
    void blankPdfProducesEmptyText(@TempDir Path dir) throws Exception {
        Path pdf = writeBlankPdf(dir);

        KbParsedDocument result = new KbPdfParser(null).parse(pdf,
            KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(result.text())
            .as("空白页不得留下任何 `## 第 N 页`（否则上层「空产出」护栏被标题骗过）")
            .isEmpty();
        assertThat(result.metadata())
            .containsEntry(KbParsedDocument.META_PAGE_COUNT, "1")
            .containsEntry(KbParsedDocument.META_TEXT_LENGTH, "0");
    }

    @Test
    @DisplayName("两页：第 1 页有文字、第 2 页空白 → 只有 `## 第 1 页`")
    void blankSecondPageGetsNoHeading(@TempDir Path dir) throws Exception {
        Path pdf = writeTextThenBlankPdf(dir);

        KbParsedDocument result = new KbPdfParser(null).parse(pdf,
            KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(result.text())
            .as("有文字的页行为不变")
            .contains("## 第 1 页")
            .contains("Page 1 has a real text layer.");
        assertThat(result.text())
            .as("空白页不产标题")
            .doesNotContain("## 第 2 页");
        assertThat(result.metadata())
            .as("跳标题不能改变页数口径")
            .containsEntry(KbParsedDocument.META_PAGE_COUNT, "2");
    }

    @Test
    @DisplayName("扫描页（整页图）→ 仍有 `## 第 N 页`（图片页不算空白页）")
    void scannedPageKeepsHeading(@TempDir Path dir) throws Exception {
        Path pdf = writeScannedPdf(dir);

        KbParsedDocument result = new KbPdfParser(null).parse(pdf,
            KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(result.text())
            .as("被判定为扫描页 ⇒ 标题必须保留（否则视觉补强的正文没有页头）")
            .isEqualTo("## 第 1 页");
        assertThat(result.metadata())
            .as("扫描页即使无视觉能力也必须留痕")
            .containsEntry("visionSkipped", "true");
    }

    @Test
    @DisplayName("混合 3 页：文字 / 空白 / 扫描 → 标题为第 1、3 页（跳页不重排页号）")
    void skippedPageDoesNotRenumberFollowingPages(@TempDir Path dir) throws Exception {
        Path pdf = writeMixedPdf(dir);

        KbParsedDocument result = new KbPdfParser(null).parse(pdf,
            KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(result.text())
            .contains("## 第 1 页")
            .contains("## 第 3 页")
            .doesNotContain("## 第 2 页");
        assertThat(result.text().indexOf("## 第 1 页"))
            .as("页序不得变化")
            .isLessThan(result.text().indexOf("## 第 3 页"));
        assertThat(result.metadata())
            .containsEntry(KbParsedDocument.META_PAGE_COUNT, "3");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 单页空白 PDF：不建内容流 ⇒ 无文字无图。 */
    private static Path writeBlankPdf(Path dir) throws IOException {
        Path file = dir.resolve("全空白.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(A4));
            doc.save(file.toFile());
        }
        return file;
    }

    /** 两页：第 1 页有文字，第 2 页真空白（不建内容流）。 */
    private static Path writeTextThenBlankPdf(Path dir) throws IOException {
        Path file = dir.resolve("第2页空白.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage p1 = new PDPage(A4);
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50f, 700f);
                cs.showText("Page 1 has a real text layer.");
                cs.endText();
            }
            doc.addPage(new PDPage(A4));
            doc.save(file.toFile());
        }
        return file;
    }

    /** 单页扫描件：整页一张图、无文本层 ⇒ 图片面积比 ≈ 1.0，必判为扫描页。 */
    private static Path writeScannedPdf(Path dir) throws IOException {
        Path file = dir.resolve("扫描页.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(A4);
            doc.addPage(page);
            drawFullPageImage(doc, page);
            doc.save(file.toFile());
        }
        return file;
    }

    /** 3 页：第 1 页有文字、第 2 页空白、第 3 页整页图（扫描页）。 */
    private static Path writeMixedPdf(Path dir) throws IOException {
        Path file = dir.resolve("混合.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage p1 = new PDPage(A4);
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50f, 700f);
                cs.showText("Page 1 has a real text layer.");
                cs.endText();
            }

            doc.addPage(new PDPage(A4));

            PDPage p3 = new PDPage(A4);
            doc.addPage(p3);
            drawFullPageImage(doc, p3);

            doc.save(file.toFile());
        }
        return file;
    }

    private static void drawFullPageImage(PDDocument doc, PDPage page) throws IOException {
        BufferedImage pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        PDImageXObject img = LosslessFactory.createFromImage(doc, pixels);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, 0f, 0f, 595f, 842f);
        }
    }
}

package com.nexusai.kb.app.parser;

import com.nexusai.application.agent.tool.impl.PdfSupport;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 解析器本轮三项增量（纯单测，不启 Spring）：
 * <ol>
 *   <li><b>超大页面缩放</b> —— 源站 docreader {@code pdf_render_max_edge}（config.py:136-142）= 2000：
 *       100 DPI 渲染后长边超 2000 的页图必须等比缩到 2000 再落盘。这条判据只能靠「读回 JPEG 的真实
 *       像素尺寸」验证：代码路径看起来有 if，但缩放比算错/写盘仍写原图时，只有实际尺寸能抓住。</li>
 *   <li><b>扫描页专用提示词</b> —— 页图是整页文档，需要「逐块转写为 Markdown」，不是「描述这张图」。
 *       判据是捕获 {@link KbVisionCaller#describeImage} 收到的 prompt 实参（不是源码文本断言）。</li>
 *   <li><b>页数上限可配</b> —— yml {@code nexusai.knowledge.pdf.max-pages}（缺省 200）；
 *       用 maxPages=3 解析 4 页 PDF，第 4 页必须完全不处理，且 metadata 留 truncated 痕迹。</li>
 * </ol>
 */
@DisplayName("[B2b] PDF 增量：超大页缩放 + 扫描页转写提示词 + 页数上限可配")
class KbPdfExtraTest {

    @Test
    @DisplayName("4000x6000pt 超大页面：默认与显式上限都按长边等比缩放（读回 JPEG 实际尺寸）")
    void oversizePageIsScaledToMaxEdge(@TempDir Path dir) throws Exception {
        Path pdf = writeOversizePdf(dir, 4000f, 6000f);

        // ① 旧 4 参签名（既有调用方走这条）→ 必须委托默认上限 2000
        Path defaultOut = dir.resolve("out-default");
        PdfSupport.PdfExtractResult defaultResult =
            PdfSupport.extractPDFPages(pdf, defaultOut, 1, 1);
        assertThat(defaultResult.success())
            .as("渲染应成功，失败原因=%s", defaultResult.error())
            .isTrue();

        BufferedImage defaultImg = ImageIO.read(defaultOut.resolve("page-01.jpg").toFile());
        assertThat(defaultImg).as("页图必须落盘且可解码").isNotNull();
        int defaultLongEdge = Math.max(defaultImg.getWidth(), defaultImg.getHeight());
        assertThat(defaultLongEdge)
            .as("渲染前长边=%s px（4000x6000pt @100DPI），必须缩到默认上限 %s 以内",
                8333, PdfSupport.PDF_RENDER_MAX_EDGE)
            .isLessThanOrEqualTo(PdfSupport.PDF_RENDER_MAX_EDGE);
        assertThat(defaultLongEdge)
            .as("等比缩放后长边应正好等于 2000")
            .isEqualTo(PdfSupport.PDF_RENDER_MAX_EDGE);
        assertThat(Math.min(defaultImg.getWidth(), defaultImg.getHeight()))
            .as("短边必须保持原比例（4000/6000 × 2000 ≈ 1333）")
            .isBetween(1330, 1336);
        defaultImg.flush();

        // ② 新增 5 参重载：显式上限 1000 必须生效（证明上限是入参，不是硬编码 2000）
        Path explicitOut = dir.resolve("out-explicit");
        PdfSupport.PdfExtractResult explicitResult =
            PdfSupport.extractPDFPages(pdf, explicitOut, 1, 1, 1000);
        assertThat(explicitResult.success()).isTrue();

        BufferedImage explicitImg = ImageIO.read(explicitOut.resolve("page-01.jpg").toFile());
        assertThat(explicitImg).isNotNull();
        assertThat(Math.max(explicitImg.getWidth(), explicitImg.getHeight()))
            .as("显式 maxEdge=1000 必须生效")
            .isEqualTo(1000);
        explicitImg.flush();
    }

    @Test
    @DisplayName("扫描页收到的 prompt 非空、就是转写为 Markdown 的专用提示词（不是 null 通用描述）")
    void scannedPageGetsTranscriptionPrompt(@TempDir Path dir) throws Exception {
        Path pdf = writeScannedPdf(dir);
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        AtomicReference<Path> seenImage = new AtomicReference<>();
        KbVisionCaller capture = new KbVisionCaller() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
                seenImage.set(imageFile);
                seenPrompt.set(prompt);
                return "# 转写结果";
            }
        };

        new KbPdfParser(capture).parse(pdf,
            KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(seenImage.get()).as("扫描页必须真的调了视觉（判据有效的前提）").isNotNull();
        assertThat(seenPrompt.get()).as("扫描页 prompt 不能是 null").isNotBlank();
        assertThat(seenPrompt.get())
            .as("必须是本解析器的专用常量，而不是各实现自己的默认提示")
            .isEqualTo(KbPdfParser.SCAN_PAGE_PROMPT);
        assertThat(seenPrompt.get())
            .as("语义必须落在「转写 / Markdown」上（防止被改回「描述图片」类提示）")
            .contains("转写")
            .contains("Markdown");
    }

    @Test
    @DisplayName("maxPages=3 → 只处理前 3 页，第 4 页不处理且 metadata 记 truncated")
    void maxPagesIsConfigurable(@TempDir Path dir) throws Exception {
        assertThat(KbPdfParser.MAX_PAGES).as("静态默认常量必须保留 200").isEqualTo(200);

        Path pdf = KbTestDocuments.writeTextPdf(dir, 4);
        KbParsedDocument result = new KbPdfParser(null, 2, 3)
            .parse(pdf, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

        assertThat(result.text())
            .as("前 3 页正常解析")
            .contains("## 第 1 页")
            .contains("## 第 3 页");
        assertThat(result.text())
            .as("第 4 页必须完全不处理（上限被读成常量 200 时这条会红）")
            .doesNotContain("## 第 4 页")
            .doesNotContain("Page 4");
        assertThat(result.metadata())
            .containsEntry(KbParsedDocument.META_PAGE_COUNT, "4")
            .containsEntry(KbParsedDocument.META_TRUNCATED, "true")
            .containsEntry("parsedPages", "3");
    }

    /** 造一个 4000x6000pt 的超大页面 PDF（100 DPI 下长边 8333px，必须触发缩放）。 */
    private static Path writeOversizePdf(Path dir, float widthPt, float heightPt) throws IOException {
        Path file = dir.resolve("超大页面-" + (int) widthPt + "x" + (int) heightPt + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(widthPt, heightPt));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50f, heightPt - 80f);
                cs.showText("oversize page");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 造一个整页一张图、无文本层的「扫描件」PDF（图片面积比 ≈ 1.0 ⇒ 必走视觉通道）。 */
    private static Path writeScannedPdf(Path dir) throws IOException {
        Path file = dir.resolve("扫描页.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595f, 842f));
            doc.addPage(page);
            BufferedImage pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            PDImageXObject img = LosslessFactory.createFromImage(doc, pixels);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0f, 0f, 595f, 842f);
            }
            doc.save(file.toFile());
        }
        return file;
    }
}

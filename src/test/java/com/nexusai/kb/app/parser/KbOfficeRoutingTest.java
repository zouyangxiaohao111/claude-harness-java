package com.nexusai.kb.app.parser;

import com.nexusai.kb.app.chunk.ParserEngineRule;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Office 默认路由 + 让出后的能力覆盖（2026-11-12 用户裁定）。
 *
 * <p>裁定：Office 类格式默认改走 Tika（{@link KbOfficeParser}），anydoc 只在
 * {@code parser_engine_rules} 显式点名时使用。理由：需要 source block（可溯源）与内嵌图片，
 * anydoc 只出 markdown、两者都不提供。
 *
 * <p>WHY 这些断言（判据要钉在会坏的地方）：
 * <ol>
 *   <li>默认选中谁必须钉在 {@code KbParserRegistry.select()} 上；只改 {@code supportedExtensions}
 *       也能让清单变好看但路由不变。</li>
 *   <li>反向：若 {@link KbOfficeParser#engineFamilyNames()} 仍声称 {@code anydoc}，族名一对多消歧
 *       会把「规则点名 anydoc」抢到 Office ⇒ 显式点名失效。两侧各一条断言。</li>
 *   <li>图片断言落到「文件真落盘 + 内容真是 PNG」；source block 非空（这是改路由的唯一目的）。</li>
 * </ol>
 *
 * <p>⛔ 不用 {@code @SpringBootTest}（会迁移真库）；直接 new 解析器 + 手搭注册表。
 */
@DisplayName("[A] Office 默认路由（Tika）+ sourceBlocks / 内嵌图片覆盖")
class KbOfficeRoutingTest {

    /** 1x1 PNG（与 KbAnydocAssetsTest 同一张）。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static KbParserRegistry registry() {
        // 与生产装配同一批解析器（顺序由 @Order 决定，构造时自动排序）
        return new KbParserRegistry(List.of(
            new KbPdfParser(null),
            new KbAnydocParser(),
            new KbImageParser(null),
            new KbXlsxParser(),      // @Order(16)：OOXML 表格的默认引擎
            new KbOfficeParser(),
            new KbHtmlParser(),
            new KbTextParser(),
            new KbXmindParser()));
    }

    private static ParserEngineRule rule(String engine, String... fileTypes) {
        return new ParserEngineRule(List.of(fileTypes), engine);
    }

    // ── ① 默认路由 ────────────────────────────────────────────────

    @Test
    @DisplayName("默认：docx / xlsx / pptx 全部选中 KbOfficeParser（不再被 anydoc 抢）")
    void officeFormatsDefaultToTika() {
        KbParserRegistry reg = registry();

        assertThat(reg.select("x.docx", null).name()).isEqualTo("KbOfficeParser");
        // 2026-11-12：OOXML 表格改由 KbXlsxParser 逐行渲染（要带 Excel 行号），不再走 Tika
        assertThat(reg.select("a.xlsx", null).name()).isEqualTo("KbXlsxParser");
        assertThat(reg.select("s.pptx", null).name()).isEqualTo("KbOfficeParser");

        // 前提：anydoc 的默认支持面已为空（否则上面三条只是碰巧顺序靠后）
        assertThat(new KbAnydocParser().supports("x.docx", null)).isFalse();
        assertThat(new KbAnydocParser().supportedExtensions()).isEmpty();
    }

    @Test
    @DisplayName("默认：让出的整套后缀都落到 KbOfficeParser（宏/模板/RTF/ODF 也要有接管者）")
    void handedOverExtensionsAllRouteToOffice() {
        KbParserRegistry reg = registry();

        for (String ext : List.of(
            "doc", "docx", "docm", "dot", "dotx",
            "xls",
            "ppt", "pptx", "pptm", "pot", "potx",
            "rtf", "odt", "ods", "odp")) {
            KbDocumentParser picked = reg.select("sample." + ext, null);
            assertThat(picked)
                .as("扩展名 .%s 在让出 anydoc 后必须有接管者（否则上传报「没有匹配的解析器」）", ext)
                .isNotNull();
            assertThat(picked.name()).as("扩展名 .%s 的默认解析器", ext).isEqualTo("KbOfficeParser");
        }
        // OOXML 表格同一批后缀，但接管者已换成 KbXlsxParser（逐行渲染 + 行号）
        for (String ext : List.of("xlsx", "xlsm", "xltx", "xlt")) {
            KbDocumentParser picked = reg.select("sample." + ext, null);
            assertThat(picked).as("扩展名 .%s 必须有接管者", ext).isNotNull();
            assertThat(picked.name()).as("扩展名 .%s 的默认解析器", ext).isEqualTo("KbXlsxParser");
        }
    }

    // ── ② 显式点名 anydoc ─────────────────────────────────────────

    @Test
    @DisplayName("规则点名 anydoc ⇒ 仍选 KbAnydocParser（即使它默认 supports 恒 false）")
    void explicitAnydocRuleStillSelectsAnydoc() {
        Assumptions.assumeTrue(AnydocNative.isAvailable(),
            "anydoc 原生库不可用：显式点名会按既有语义回落默认，本机无法验证");

        KbParserRegistry reg = registry();
        // 前提：默认已不让 anydoc 接管
        assertThat(new KbAnydocParser().supports("x.docx", null)).isFalse();

        assertThat(KbParserEngineRouter.select(reg, "x.docx",
            List.of(rule("anydoc", "docx"))).name()).isEqualTo("KbAnydocParser");
        assertThat(KbParserEngineRouter.select(reg, "a.xlsx",
            List.of(rule("KbAnydocParser", "xlsx"))).name()).isEqualTo("KbAnydocParser");
        // 未点名仍是 Tika
        assertThat(KbParserEngineRouter.select(reg, "x.docx", List.of()).name())
            .isEqualTo("KbOfficeParser");
    }

    @Test
    @DisplayName("一站式 parse（生产同一入口）：docx 走规则路由也落到 KbOfficeParser，图片与 sourceBlocks 都在")
    void oneStopParseRoutesDocxToOffice(@TempDir Path dir) throws Exception {
        Path docx = writeDocxWithImage(dir);
        KbParsedDocument doc = KbParserEngineRouter.parse(
            registry(), docx, ctx(dir, "one-stop"), List.of());

        assertThat(doc.metadata().get(KbParsedDocument.META_PARSER)).isEqualTo("KbOfficeParser");
        assertThat(doc.images()).hasSize(1);
        assertThat(doc.sourceBlocks()).isNotEmpty();
    }

    // ── ③ 图片 + sourceBlocks ────────────────────────────────────

    @Test
    @DisplayName("docx 含 1 张内嵌 PNG ⇒ images 恰 1 条、真落盘且是 PNG；正文与 sourceBlocks 都在")
    void docxEmbeddedPngYieldsImageAndSourceBlocks(@TempDir Path dir) throws Exception {
        Path docx = writeDocxWithImage(dir);
        KbParsedDocument doc = new KbOfficeParser()
            .parse(docx, KbParseContext.defaults().withWorkDir(dir.resolve("work")));

        assertThat(doc.metadata()).containsEntry(KbParsedDocument.META_PARSER, "KbOfficeParser");
        assertThat(doc.images()).as("内嵌 PNG 没被抽出来：%s", doc.images()).hasSize(1);

        KbImageRef ref = doc.images().get(0);
        assertThat(ref.sourceKind()).isEqualTo(KbImageRef.KIND_OFFICE_EMBEDDED);
        assertThat(ref.pageIndex()).isZero();
        Path written = Path.of(ref.imagePath());
        assertThat(written).isRegularFile();
        assertThat(written.getFileName().toString()).startsWith("office-image-").endsWith(".png");
        byte[] bytes = Files.readAllBytes(written);
        assertThat(bytes.length).isGreaterThanOrEqualTo(8);
        assertThat(bytes[0] & 0xFF).isEqualTo(0x89);
        assertThat((char) bytes[1]).isEqualTo('P');

        assertThat(doc.text()).contains("图片前段落").contains("图片后段落");
        assertThat(doc.sourceBlocks()).as("docx 必须产出 source block（本次改路由的目的）").isNotEmpty();
        assertThat(doc.metadata().get(KbParsedDocument.META_SOURCE_BLOCKS))
            .isEqualTo(String.valueOf(doc.sourceBlocks().size()));
        assertThat(Integer.parseInt(doc.metadata().get(KbParsedDocument.META_SOURCE_BLOCKS)))
            .isPositive();
    }

    @Test
    @DisplayName("sourceBlocks 覆盖：docx / xlsx / pptx 三族都非空（rtf/odf/epub 本就不支持）")
    void sourceBlocksCoverageForDocxXlsxPptx(@TempDir Path dir) throws Exception {
        KbOfficeParser parser = new KbOfficeParser();

        Path docx = writeDocxWithImage(dir);
        Path xlsx = writeXlsxWithImage(dir);
        Path pptx = writePptxWithImage(dir);

        int docxBlocks = parser.parse(docx, ctx(dir, "a")).sourceBlocks().size();
        int xlsxBlocks = parser.parse(xlsx, ctx(dir, "b")).sourceBlocks().size();
        int pptxBlocks = parser.parse(pptx, ctx(dir, "c")).sourceBlocks().size();
        System.out.println("[KbOfficeRoutingTest] sourceBlocks docx=" + docxBlocks
            + " xlsx=" + xlsxBlocks + " pptx=" + pptxBlocks);

        assertThat(docxBlocks).as("docx sourceBlocks").isPositive();
        assertThat(xlsxBlocks).as("xlsx sourceBlocks").isPositive();
        assertThat(pptxBlocks).as("pptx sourceBlocks").isPositive();

        // 不支持的类型：如 RTF / ODF / EPUB —— KbOfficeSourceBlocks.supports 明确不含
        assertThat(KbOfficeSourceBlocks.supports("rtf")).isFalse();
        assertThat(KbOfficeSourceBlocks.supports("odt")).isFalse();
        assertThat(KbOfficeSourceBlocks.supports("epub")).isFalse();
    }

    @Test
    @DisplayName("图片覆盖：docx / xlsx / pptx 各含 1 张内嵌 PNG ⇒ 每份都能抽出 1 张")
    void embeddedPngExtractionCoverage(@TempDir Path dir) throws Exception {
        KbOfficeParser parser = new KbOfficeParser();

        int docx = parser.parse(writeDocxWithImage(dir), ctx(dir, "i1")).images().size();
        KbParsedDocument xlsxDoc = parser.parse(writeXlsxWithImage(dir), ctx(dir, "i2"));
        KbParsedDocument pptxDoc = parser.parse(writePptxWithImage(dir), ctx(dir, "i3"));
        int xlsx = xlsxDoc.images().size();
        int pptx = pptxDoc.images().size();
        System.out.println("[KbOfficeRoutingTest] images docx=" + docx
            + " xlsx=" + xlsx + " pptx=" + pptx);

        assertThat(docx).as("docx 内嵌图片").isEqualTo(1);
        assertThat(xlsx).as("xlsx 内嵌图片").isEqualTo(1);
        // pptx 实测 2 张：内容图 + 文档缩略图（docProps/thumbnail.jpeg，POI 保存时自带）。
        // Tika 把两者都当内嵌图片交出来，故按「至少 1 张」断言，实测值见交付表。
        assertThat(pptx).as("pptx 内嵌图片（含缩略图）").isGreaterThanOrEqualTo(1);
        for (KbImageRef ref : pptxDoc.images()) {
            assertThat(Path.of(ref.imagePath())).as("pptx 图片必须真落盘").isRegularFile();
        }
    }

    private static KbParseContext ctx(Path dir, String name) throws Exception {
        Path work = Files.createDirectories(dir.resolve("work-" + name));
        return KbParseContext.defaults().withWorkDir(work);
    }

    // ── 夹具：真实 OOXML（POI 生成，含 1 张 1x1 PNG） ──────────────

    private static Path writeDocxWithImage(Path dir) throws Exception {
        Path file = dir.resolve("图文报告.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("图片前段落");
            XWPFRun run = doc.createParagraph().createRun();
            try (ByteArrayInputStream in = new ByteArrayInputStream(PNG)) {
                run.addPicture(in, Document.PICTURE_TYPE_PNG, "image1.png", 100, 100);
            }
            doc.createParagraph().createRun().setText("图片后段落");
            try (OutputStream out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }
        return file;
    }

    private static Path writeXlsxWithImage(Path dir) throws Exception {
        Path file = dir.resolve("数据表.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("数据表");
            XSSFRow header = sheet.createRow(0);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("金额");
            XSSFRow data = sheet.createRow(1);
            data.createCell(0).setCellValue("A 标段");
            data.createCell(1).setCellValue(6000);

            int pictureIndex = wb.addPicture(PNG, Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 2, 1, 4, 3), pictureIndex);

            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }

    private static Path writePptxWithImage(Path dir) throws Exception {
        Path file = dir.resolve("汇报.pptx");
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new Rectangle(50, 50, 400, 100));
            box.setText("第一页标题");

            XSLFPictureData picture = show.addPicture(PNG, PictureData.PictureType.PNG);
            slide.createPicture(picture);

            try (OutputStream out = Files.newOutputStream(file)) {
                show.write(out);
            }
        }
        return file;
    }
}


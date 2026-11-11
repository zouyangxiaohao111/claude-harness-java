package com.nexusai.kb.app.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试夹具生成器 · 用 POI / PDFBox 在临时目录里造真实二进制文档。
 *
 * <p><b>WHY 造而不放 fixture 文件</b>：二进制夹具进 Git 会带来「用什么工具生成」的黑盒问题
 * （以后谁也不知道那个 .docx 里到底写了什么）。生成器把「文档内容」写在 Java 里，可读、可改、
 * 且测试自带清理。
 *
 * <p><b>为什么必须是真实格式</b>：派单书验收要求「用真实文件跑」。用字符串伪造的假 .xlsx 只能
 * 证明代码不抛异常，不能证明 Tika + POI 真能读出内容。
 */
final class KbTestDocuments {

    private KbTestDocuments() {
    }

    /**
     * 造一个 .docx：标题 + 正文段落 + 2x2 表格。
     *
     * <p>表格是重点 —— 派单书要求 Excel/Office 结构转 Markdown 表格；Word 表格同样要走这条路径。
     */
    static Path writeDocx(Path dir) throws IOException {
        Path file = dir.resolve("测试报告.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("季度工作报告");
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("一、总体情况");
            doc.createParagraph().createRun().setText("本季度累计完成工程量 1.2 亿元，较上季度增长 8%。");

            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("标段");
            table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("A 标段");
            table.getRow(1).getCell(1).setText("6000 万元");

            try (OutputStream out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }
        return file;
    }

    /** 造一个 .xlsx：两个 sheet（第二个 sheet 用来验证「每个 sheet 一个二级标题」）。 */
    static Path writeXlsx(Path dir) throws IOException {
        Path file = dir.resolve("项目清单.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet1 = workbook.createSheet("项目清单");
            String[][] rows = {
                {"项目", "金额", "负责人"},
                {"A 标段", "6000", "张三"},
                {"B 标段", "6000", "李四"},
            };
            for (int r = 0; r < rows.length; r++) {
                XSSFRow row = sheet1.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            XSSFSheet sheet2 = workbook.createSheet("备注");
            sheet2.createRow(0).createCell(0).setCellValue("以上数据由财务部提供");

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }

    /** 造一个 .pptx：两页，每页一个文本框。 */
    static Path writePptx(Path dir) throws IOException {
        Path file = dir.resolve("汇报.pptx");
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            XSLFSlide slide1 = slideShow.createSlide();
            XSLFTextBox box1 = slide1.createTextBox();
            box1.setAnchor(new Rectangle(50, 50, 500, 100));
            box1.setText("第一页标题");

            XSLFSlide slide2 = slideShow.createSlide();
            XSLFTextBox box2 = slide2.createTextBox();
            box2.setAnchor(new Rectangle(50, 50, 500, 100));
            box2.setText("第二页内容");

            try (OutputStream out = Files.newOutputStream(file)) {
                slideShow.write(out);
            }
        }
        return file;
    }

    /** 造一个带文本层的 PDF（每页文字足够长，不该触发视觉补强）。 */
    static Path writeTextPdf(Path dir, int pages) throws IOException {
        Path file = dir.resolve("电子版说明.pdf");
        String line = "This page has a real text layer with enough characters to pass the "
            + "scan-detection threshold used by the knowledge base parser.";
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + (i + 1));
                    cs.newLineAtOffset(0, -20);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * 造一个<b>扫描件</b>的 PDF：整页一张图 + 无文本层。
     *
     * <p>这是验收门 G-PDF视觉 的输入：图片盖满整页（面积比 ≈ 1.0）⇒ 必须走视觉通道。
     * ⚠️ 不能只画一条线：对齐源站判据后，「无文字且无图」是<b>真空白页</b>，按
     * {@code _classify_page} 归为 text、不渲图（否则会为空白页白付视觉费用）。
     */
    static Path writeScannedPdf(Path dir) throws IOException {
        Path file = dir.resolve("扫描件.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_A4);
            doc.addPage(page);
            java.awt.image.BufferedImage pixels =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, pixels);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0f, 0f, 595f, 842f);
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * 造一个<b>整页大图</b>的 PDF（A4 595×842pt，图片铺满整页）· 面积比判据的正样本。
     *
     * <p>与 {@link #writeScannedPdf} 的区别：那份没有任何图片对象，只能靠「文本为空」触发；
     * 这份的触发证据是<b>图片覆盖面积比 ≈ 1.0</b>，正是对齐源站
     * {@code _page_image_area_ratio} 时要验的东西。
     */
    static Path writeFullPageImagePdf(Path dir) throws IOException {
        return writeScaledImagePdf(dir, 595f, 842f);
    }

    /**
     * 造一个「图片以 {@code width×height} pt 绘制在 A4 页左下角」的 PDF · 面积比的可算式夹具。
     *
     * <p>期望面积比 = {@code width*height / (595*842)}，测试据此断言 CTM 口径是否真的生效
     * （用像素数近似会因 DPI 不同而算错）。
     */
    static Path writeScaledImagePdf(Path dir, float width, float height) throws IOException {
        Path file = dir.resolve("图片页-" + (int) width + "x" + (int) height + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_A4);
            doc.addPage(page);
            java.awt.image.BufferedImage pixels =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, pixels);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0f, 0f, width, height);
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 面积比夹具统一用 A4（842 × 595），保证分母可复算。 */
    private static final org.apache.pdfbox.pdmodel.common.PDRectangle PAGE_A4 =
        new org.apache.pdfbox.pdmodel.common.PDRectangle(595f, 842f);

    /** 造一个 .md（纯文本类，验证「不做任何改写」）。 */
    static Path writeMarkdown(Path dir) throws IOException {
        Path file = dir.resolve("说明.md");
        Files.writeString(file, "# 标题\n\n这是 Markdown 正文，包含中文。\n\n- 条目一\n- 条目二\n",
            StandardCharsets.UTF_8);
        return file;
    }

    /** 造一个 GBK 编码的 .txt（验证编码自动探测）。 */
    static Path writeGbkText(Path dir) throws IOException {
        Path file = dir.resolve("gbk说明.txt");
        Files.write(file, "中文编码测试：知识点检索".getBytes(java.nio.charset.Charset.forName("GBK")));
        return file;
    }

    /**
     * 造一个 .csv：表头 + 两行，其中<b>一个金额带千分位逗号并用引号包裹</b>。
     *
     * <p>那个引号字段是本夹具的重点：{@code String.split(",")} 会把它切成两列，
     * 只有真正按 RFC4180 解析才能还原成 {@code 1,200 万元}。真实工程数据里金额列几乎都长这样。
     */
    static Path writeCsv(Path dir) throws IOException {
        Path file = dir.resolve("标段金额.csv");
        Files.writeString(file,
            "标段,金额,负责人\nA 标段,6000 万元,张三\nB 标段,\"1,200 万元\",李四\n",
            StandardCharsets.UTF_8);
        return file;
    }

    /** 造一个 .csv 的 <b>GBK</b> 版本（验证「按 Tika 探测到的 charset 读字节」，不是硬编 UTF-8）。 */
    static Path writeGbkCsv(Path dir) throws IOException {
        Path file = dir.resolve("gbk标段.csv");
        Files.write(file,
            "标段,金额\nA 标段,6000 万元\n".getBytes(java.nio.charset.Charset.forName("GBK")));
        return file;
    }

    /** 造一个 .json（嵌套对象 + 数组，验证围栏代码块且层级保留）。 */
    static Path writeJson(Path dir) throws IOException {
        Path file = dir.resolve("配置.json");
        Files.writeString(file,
            "{\"project\":\"测试\",\"items\":[{\"name\":\"a\",\"value\":1}],\"nested\":{\"k\":\"v\"}}",
            StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 造一个最小可用的真实 .epub（zip 容器：mimetype + container.xml + OPF + 一章 XHTML）。
     *
     * <p>{@code mimetype} 必须是 zip 的第一项且 <b>不压缩</b> —— EPUB 规范要求，也是
     * {@code EpubParser} 定位容器的依据。
     */
    static Path writeEpub(Path dir) throws IOException {
        Path file = dir.resolve("电子书.epub");
        try (java.util.zip.ZipOutputStream zip =
                 new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            java.util.zip.ZipEntry mimetype = new java.util.zip.ZipEntry("mimetype");
            mimetype.setMethod(java.util.zip.ZipEntry.STORED);
            byte[] mimeBytes = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            mimetype.setSize(mimeBytes.length);
            mimetype.setCompressedSize(mimeBytes.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(mimeBytes);
            mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype);
            zip.write(mimeBytes);
            zip.closeEntry();

            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/container.xml"));
            zip.write(("<?xml version=\"1.0\"?><container version=\"1.0\" "
                + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" "
                + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/content.opf"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:identifier id=\"uid\">urn:uuid:12345678-1234-1234-1234-123456789012</dc:identifier>"
                + "<dc:title>电子书标题</dc:title><dc:language>zh</dc:language></metadata>"
                + "<manifest><item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                + "<spine><itemref idref=\"c1\"/></spine></package>")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new java.util.zip.ZipEntry("OEBPS/ch1.xhtml"));
            zip.write(("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
                + "<body><h1>第一章 电子书正文</h1><p>这是 EPUB 解析验证用的正文段落。</p></body></html>")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    /** 造一个最小可用的 .mhtml（multipart/related，正文 HTML 段）。 */
    static Path writeMhtml(Path dir) throws IOException {
        Path file = dir.resolve("网页归档.mhtml");
        String body = "From: <Saved by Blink>\r\n"
            + "Subject: 网页归档标题\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/related; type=\"text/html\"; boundary=\"----=_NextPart_000_0000\"\r\n"
            + "\r\n"
            + "------=_NextPart_000_0000\r\n"
            + "Content-Type: text/html; charset=\"utf-8\"\r\n"
            + "Content-Transfer-Encoding: 8bit\r\n"
            + "Content-Location: http://example.com/page\r\n"
            + "\r\n"
            + "<html><head><title>网页归档</title></head><body>"
            + "<h1>网页归档正文标题</h1><p>这是 MHTML 解析验证用的正文段落。</p></body></html>\r\n"
            + "\r\n"
            + "------=_NextPart_000_0000--\r\n";
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}

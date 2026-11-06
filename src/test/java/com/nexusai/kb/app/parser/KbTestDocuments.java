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
     * 造一个<b>无文本层</b>的 PDF（只有一条线）· 扫描件模拟。
     *
     * <p>这是验收门 G-PDF视觉 的输入：文本层为空 ⇒ 必须走视觉通道。
     */
    static Path writeScannedPdf(Path dir) throws IOException {
        Path file = dir.resolve("扫描件.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.moveTo(50, 700);
                cs.lineTo(300, 700);
                cs.stroke();
            }
            doc.save(file.toFile());
        }
        return file;
    }

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
}

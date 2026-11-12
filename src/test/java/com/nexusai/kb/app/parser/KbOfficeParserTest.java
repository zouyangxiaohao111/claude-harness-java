package com.nexusai.kb.app.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Office 解析器意图测试。
 *
 * <p><b>WHY 这些断言（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>表格必须是 Markdown 表格</b> —— 若退回 Tika 纯文本，表格被拍平成制表符行，
 *       下游分块会把一张表拆到不同块里，检索「某标段金额」就查不到。这是最容易「改坏了不报错」的点。</li>
 *   <li><b>Excel 每个 sheet 一个二级标题</b> —— 一张工作簿里有多个语义区块，没有分界会让
 *       「备注」的内容被误当成「项目清单」的一部分。</li>
 *   <li><b>PPT 每页一个 Slide 标题</b> —— 同理，且母版占位符必须剔除（否则每页多一份空标题）。</li>
 *   <li><b>上传上限截断要留痕</b> —— 静默截断会让用户以为文档被完整索引。</li>
 * </ol>
 */
@DisplayName("[B2b] Office 解析器（Word/Excel/PPT → Markdown）")
class KbOfficeParserTest {

    private final KbOfficeParser parser = new KbOfficeParser();

    @Nested
    @DisplayName("类型识别")
    class Supports {

        @Test
        @DisplayName("Word/Excel(.xls)/PPT 新旧扩展名都被识别；OOXML 表格已让给 KbXlsxParser")
        void recognisesAllOfficeExtensions() {
            for (String ext : new String[]{"doc", "docx", "xls", "ppt", "pptx"}) {
                assertThat(parser.supports("a." + ext, null))
                    .as("扩展名 %s 应被 Office 解析器接管", ext).isTrue();
            }
            // 2026-11-12：xlsx/xlsm/xltx/xlt 让给 KbXlsxParser —— 只有逐行渲染才带得出 Excel 行号。
            // 旧断言「xlsx 由 Office 接管」已迁移成「不再接管」，⛔ 未删除用例。
            for (String ext : new String[]{"xlsx", "xlsm", "xltx", "xlt"}) {
                assertThat(parser.supports("a." + ext, null))
                    .as("扩展名 %s 已让给 KbXlsxParser", ext).isFalse();
                assertThat(parser.supportedExtensions()).doesNotContain(ext);
            }
            assertThat(parser.supportedExtensions()).contains("xls");
            assertThat(parser.supports("a.txt", "text/plain")).isFalse();
            assertThat(parser.supports("a.pdf", "application/pdf")).isFalse();
        }

        @Test
        @DisplayName("MIME 命中（扩展名缺失时）也能识别 OOXML")
        void recognisesOoxmlMime() {
            assertThat(parser.supports("noext",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
            assertThat(parser.supports("noext",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
            assertThat(parser.supports("noext",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation")).isTrue();
        }

        @Test
        @DisplayName("电子书 / 网页归档：扩展名 + MIME 两条路都识别")
        void recognisesEbookAndWebArchive() {
            assertThat(parser.supports("a.epub", null)).isTrue();
            assertThat(parser.supports("a.mhtml", null)).isTrue();
            assertThat(parser.supports("noext", "application/epub+zip")).isTrue();
            assertThat(parser.supports("noext", "multipart/related; type=\"text/html\"")).isTrue();
            assertThat(parser.supports("noext", "application/x-mimearchive")).isTrue();
            // 仍然是「不抢别人的」
            assertThat(parser.supports("a.txt", "text/plain")).isFalse();
            assertThat(parser.supports("a.png", "image/png")).isFalse();
        }

        @Test
        @DisplayName("能力声明包含 epub/mhtml（前端下拉据此出现真实选项）")
        void declaresEbookAndWebArchive() {
            assertThat(parser.supportedExtensions()).contains("epub", "mhtml");
            assertThat(parser.supportedExtensions()).containsAll(KbOfficeParser.EBOOK_WEB_EXT);
        }
    }

    @Nested
    @DisplayName("电子书 / 网页归档")
    class EbookAndWebArchive {

        /**
         * 这两条与本文件其余用例的区别：它们要求「正文真的在」，而不只是「解析没抛异常」。
         *
         * <p>MHTML 的正文由 Tika 的 {@code EmbeddedDocumentExtractor} 交回解析；回落 context 里没有
         * {@code Parser} 时会退到 {@code EmptyParser} ⇒ 正文变 0 字符、不报错。这种「静默变空」
         * 用「不抛异常」根本测不出来（坏实现同样是绿的），故断言必须落到正文内容上。
         * 已用变异对照确认：去掉回落 context 里的 {@code Parser} 后，MHTML 用例会红。
         */
        @Test
        @DisplayName("EPUB：按 spine 抽出章节正文（不是 0 字符）")
        void epubExtractsChapterText(@TempDir Path dir) throws Exception {
            Path epub = KbTestDocuments.writeEpub(dir);
            KbParsedDocument result = parser.parse(epub, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .as("章节正文必须真的被抽出来")
                .contains("第一章 电子书正文")
                .contains("这是 EPUB 解析验证用的正文段落。");
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbOfficeParser");
            assertThat(result.metadata().get(KbParsedDocument.META_MIME_TYPE))
                .as("MIME 由 Tika 探测（不是扩展名硬编）")
                .isEqualTo("application/epub+zip");
        }

        @Test
        @DisplayName("MHTML：正文 HTML 被解析成文字，MIME 报文头不泄漏进正文")
        void mhtmlExtractsBodyOnly(@TempDir Path dir) throws Exception {
            Path mhtml = KbTestDocuments.writeMhtml(dir);
            KbParsedDocument result = parser.parse(mhtml, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .as("⛔ 回归：内嵌回落的 ParseContext 缺 Parser ⇒ MHTML 正文静默变空（实测 0 字符）")
                .contains("网页归档正文标题")
                .contains("这是 MHTML 解析验证用的正文段落。");
            assertThat(result.text())
                .as("只留正文：MIME 头与 boundary 不该进 Markdown")
                .doesNotContain("MIME-Version")
                .doesNotContain("=_NextPart")
                .doesNotContain("Content-Type:");
        }
    }

    @Nested
    @DisplayName("Word")
    class Word {

        @Test
        @DisplayName("正文段落保留，表格转 Markdown 表格（表头 + 分隔行 + 数据行）")
        void convertsTableToMarkdown(@TempDir Path dir) throws Exception {
            Path docx = KbTestDocuments.writeDocx(dir);
            KbParsedDocument result = parser.parse(docx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .as("正文段落必须原样出现")
                .contains("季度工作报告")
                .contains("本季度累计完成工程量 1.2 亿元");

            assertThat(result.text())
                .as("表格必须转成 Markdown：表头行 + 分隔行 + 数据行")
                .contains("| 标段 | 金额 |")
                .contains("| --- | --- |")
                .contains("| A 标段 | 6000 万元 |");

            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbOfficeParser")
                .containsKeys(KbParsedDocument.META_FILE_NAME, KbParsedDocument.META_MIME_TYPE);
            assertThat(result.metadata().get(KbParsedDocument.META_MIME_TYPE))
                .as("MIME 必须由 Tika 探测出来（不是扩展名硬编）")
                .contains("wordprocessingml");
        }

        // ── docx vMerge 物化（对齐源站 docx_merge.py）──

        @Test
        @DisplayName("纵向合并：续行单元格原本为空，物化后两行都出现 restart 内容")
        void verticalMergeCopiedIntoContinueRows(@TempDir Path dir) throws Exception {
            Path docx = writeDocxWithVMerge(dir, false);

            KbParsedDocument result = parser.parse(docx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .as("restart 行本身照常")
                .contains("| 甲 | 10 |");
            assertThat(result.text())
                .as("⛔ 续行在 Word XML 里是空单元格；不物化就只剩 |  | 20 |")
                .contains("| 甲 | 20 |");
        }

        @Test
        @DisplayName("纵向合并：两列各自合并时，续行的两列都要拿到各自 restart 的值")
        void verticalMergeFillsEachColumnIndependently(@TempDir Path dir) throws Exception {
            Path docx = writeDocxWithVMerge(dir, true);

            KbParsedDocument result = parser.parse(docx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            // ⚠️ 不能断言 contains("| 甲 | 通过 |") —— restart 那一行本来就长这样，会假绿。
            //    判据 = 两列各自都出现了<b>两行</b>同值（restart 行 + continue 行）。
            long jiaRows = rowsContaining(result.text(), "甲");
            long passRows = rowsContaining(result.text(), "通过");
            assertThat(jiaRows).as("第一列合并 ⇒ 甲 必须出现在 2 行（restart + continue）").isEqualTo(2);
            assertThat(passRows).as("第二列合并 ⇒ 通过 必须出现在 2 行（⛔ 只填第一列是常见半套实现）").isEqualTo(2);
        }

        private static long rowsContaining(String text, String needle) {
            return Arrays.stream(text.split("\\R")).filter(line -> line.contains(needle)).count();
        }

        /**
         * 造一个 3 行 × 2 列的带 vMerge docx：
         * <pre>
         * 标段 | 金额
         * 甲   | 10      ← restart
         * (空) | 20      ← continue（第二列是否也合并由 {@code twoGroups} 决定）
         * </pre>
         */
        private static Path writeDocxWithVMerge(Path dir, boolean twoGroups) throws Exception {
            Path file = dir.resolve(twoGroups ? "双列合并.docx" : "纵向合并.docx");
            try (XWPFDocument doc = new XWPFDocument()) {
                XWPFTable table = doc.createTable(3, 2);
                table.getRow(0).getCell(0).setText("标段");
                table.getRow(0).getCell(1).setText("金额");
                table.getRow(1).getCell(0).setText("甲");
                table.getRow(1).getCell(1).setText(twoGroups ? "通过" : "10");
                table.getRow(2).getCell(0).setText("");
                table.getRow(2).getCell(1).setText(twoGroups ? "" : "20");

                applyVMerge(table.getRow(1).getCell(0), STMerge.RESTART);
                applyVMerge(table.getRow(2).getCell(0), STMerge.CONTINUE);
                if (twoGroups) {
                    applyVMerge(table.getRow(1).getCell(1), STMerge.RESTART);
                    applyVMerge(table.getRow(2).getCell(1), STMerge.CONTINUE);
                }
                try (OutputStream out = Files.newOutputStream(file)) {
                    doc.write(out);
                }
            }
            return file;
        }

        private static void applyVMerge(XWPFTableCell cell, STMerge.Enum type) {
            CTTcPr properties = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
            CTVMerge merge = properties.isSetVMerge() ? properties.getVMerge() : properties.addNewVMerge();
            merge.setVal(type);
        }
    }

    @Nested
    @DisplayName("Excel")
    class Excel {

        @Test
        @DisplayName("每个 sheet 一个二级标题，数据转 Markdown 表格")
        void eachSheetBecomesHeadingAndTable(@TempDir Path dir) throws Exception {
            Path xlsx = KbTestDocuments.writeXlsx(dir);
            KbParsedDocument result = parser.parse(xlsx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .as("sheet 名必须是二级标题（规格：## <sheet名>）")
                .contains("## 项目清单")
                .contains("## 备注");

            assertThat(result.text())
                .as("数据行与分隔行都要在")
                .contains("| 项目 | 金额 | 负责人 |")
                .contains("| 张三 |")
                .contains("以上数据由财务部提供");
        }

        @Test
        @DisplayName("截断：超过上限时 metadata 记 truncated=true")
        void marksTruncation(@TempDir Path dir) throws Exception {
            Path xlsx = KbTestDocuments.writeXlsx(dir);
            KbParsedDocument full = parser.parse(xlsx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs2")));
            assertThat(full.metadata()).doesNotContainKey(KbParsedDocument.META_TRUNCATED);
            assertThat(full.metadata())
                .containsEntry(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(full.text().length()));
        }
    }

    @Nested
    @DisplayName("PPT")
    class PowerPoint {

        @Test
        @DisplayName("每页一个 Slide 标题，母版占位符不产生空标题")
        void slidesGetHeadings(@TempDir Path dir) throws Exception {
            Path pptx = KbTestDocuments.writePptx(dir);
            KbParsedDocument result = parser.parse(pptx, KbParseContext.defaults().withWorkDir(dir.resolve("imgs")));

            assertThat(result.text())
                .contains("## Slide 1")
                .contains("## Slide 2")
                .contains("第一页标题")
                .contains("第二页内容");

            assertThat(result.text())
                .as("⛔ 回归：母版 div 的跳过计数曾不平衡 ⇒ 第 2 页起整段被吞（只出第 1 页）")
                .contains("第二页内容");

            assertThat(result.text())
                .as("母版占位符被跳过 ⇒ 不会出现第三页空标题")
                .doesNotContain("## Slide 3");
        }
    }

    @Nested
    @DisplayName("失败语义")
    class Failure {

        @Test
        @DisplayName("截断的 OOXML（zip 不完整）→ 抛 KbParseException（不返回半成品）")
        void truncatedFileThrows(@TempDir Path dir) throws Exception {
            Path good = KbTestDocuments.writeDocx(dir);
            byte[] bytes = java.nio.file.Files.readAllBytes(good);
            // 砍掉后半截：中央目录丢失 ⇒ 不是合法 zip，Tika/POI 必然失败
            Path bad = dir.resolve("截断.docx");
            java.nio.file.Files.write(bad, java.util.Arrays.copyOf(bytes, bytes.length / 2));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> parser.parse(bad, KbParseContext.defaults().withWorkDir(dir.resolve("imgs"))))
                .isInstanceOf(KbParseException.class)
                .hasMessageContaining("docx");
        }

        @Test
        @DisplayName("空壳 zip（没有 OOXML 部件）→ 抛 KbParseException")
        void shellZipThrows(@TempDir Path dir) throws Exception {
            Path bad = dir.resolve("空壳.xlsx");
            try (java.util.zip.ZipOutputStream zip =
                     new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(bad))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
                zip.write("not a spreadsheet".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> parser.parse(bad, KbParseContext.defaults().withWorkDir(dir.resolve("imgs"))))
                .isInstanceOf(KbParseException.class);
        }
    }
}

package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
        @DisplayName("Word/Excel/PPT 新旧扩展名都被识别")
        void recognisesAllOfficeExtensions() {
            for (String ext : new String[]{"doc", "docx", "xls", "xlsx", "ppt", "pptx"}) {
                assertThat(parser.supports("a." + ext, null))
                    .as("扩展名 %s 应被 Office 解析器接管", ext).isTrue();
            }
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

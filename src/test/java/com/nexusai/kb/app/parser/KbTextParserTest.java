package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯文本解析器 + 注册表意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>「不做任何改写」</b>：Markdown 的 {@code #}、{@code -} 是内容本身；若被 HTML 转义或结构化
 *       转换，用户文档会面目全非（且不报错）。</li>
 *   <li><b>编码自动探测</b>：GBK 文档按 UTF-8 硬读会得到乱码，而乱码文档入库后检索永远查不到 ——
 *       这是「静默的数据损坏」，必须钉死。</li>
 *   <li><b>兜底解析器不能抢走 PDF/Office</b>：路由顺序错了会把 PDF 当文本抽出乱码。</li>
 * </ol>
 */
@DisplayName("[B2b] 文本解析器 + 解析器注册表")
class KbTextParserTest {

    private final KbTextParser parser = new KbTextParser();

    @Nested
    @DisplayName("文本解析")
    class Parsing {

        @Test
        @DisplayName("Markdown 原样保留（# 标题、- 列表都不被改写）")
        void markdownIsUnchanged(@TempDir Path dir) throws Exception {
            Path md = KbTestDocuments.writeMarkdown(dir);
            KbParsedDocument result = parser.parse(md, KbParseContext.defaults());

            assertThat(result.text())
                .contains("# 标题")
                .contains("- 条目一")
                .contains("- 条目二");
            assertThat(result.metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbTextParser");
        }

        @Test
        @DisplayName("GBK 编码的 txt 自动探测（不出现乱码）")
        void gbkTextIsDetected(@TempDir Path dir) throws Exception {
            Path txt = KbTestDocuments.writeGbkText(dir);
            KbParsedDocument result = parser.parse(txt, KbParseContext.defaults());

            assertThat(result.text())
                .as("编码探测失效会变成乱码，此处必须是正确中文")
                .contains("中文编码测试")
                .contains("知识点检索");
            assertThat(result.text()).doesNotContain("\uFFFD");
        }

        @Test
        @DisplayName("首尾空行被裁掉，正文内部不动")
        void trimsOuterBlankLinesOnly() {
            assertThat(KbTextParser.trimOuterBlankLines("\n\n abc \n\n")).isEqualTo("abc");
            assertThat(KbTextParser.trimOuterBlankLines("a\n\nb")).isEqualTo("a\n\nb");
            assertThat(KbTextParser.trimOuterBlankLines("")).isEmpty();
            assertThat(KbTextParser.trimOuterBlankLines(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("类型识别与兜底边界")
    class Supports {

        @Test
        @DisplayName("文本类扩展名全部识别")
        void recognisesTextExtensions() {
            for (String ext : new String[]{"txt", "md", "markdown", "html", "htm", "json", "csv", "xml", "log"}) {
                assertThat(parser.supports("a." + ext, null)).as("扩展名 %s", ext).isTrue();
            }
        }

        @Test
        @DisplayName("⛔ 不抢 PDF/Office（否则会被当文本抽出乱码）")
        void doesNotClaimBinaryFormats() {
            for (String ext : new String[]{"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"}) {
                assertThat(parser.supports("a." + ext, "text/plain"))
                    .as("扩展名 %s 不能让文本解析器接管", ext).isFalse();
            }
        }

        @Test
        @DisplayName("已知 text/* MIME 的陌生扩展名兜底接管")
        void fallsBackForTextMime() {
            assertThat(parser.supports("noext", "text/plain")).isTrue();
            assertThat(parser.supports("noext", "application/octet-stream")).isFalse();
        }
    }

    @Nested
    @DisplayName("注册表路由")
    class Registry {

        private KbParserRegistry registry() {
            return new KbParserRegistry(List.of(
                new KbPdfParser(null), new KbOfficeParser(), new KbTextParser()));
        }

        @Test
        @DisplayName("@Order 生效：PDF → Office → 文本（文本是兜底，必须最后）")
        void ordersParsers() {
            assertThat(registry().parsers())
                .extracting(KbDocumentParser::name)
                .containsExactly("KbPdfParser", "KbOfficeParser", "KbTextParser");
        }

        @Test
        @DisplayName("按扩展名路由到正确的解析器")
        void routesByExtension() {
            KbParserRegistry reg = registry();
            assertThat(reg.select("a.pdf", null)).isInstanceOf(KbPdfParser.class);
            assertThat(reg.select("a.docx", null)).isInstanceOf(KbOfficeParser.class);
            assertThat(reg.select("a.xlsx", null)).isInstanceOf(KbOfficeParser.class);
            assertThat(reg.select("a.md", null)).isInstanceOf(KbTextParser.class);
        }

        @Test
        @DisplayName("一站式 parse：探测 MIME → 路由 → 解析")
        void parseEndToEnd(@TempDir Path dir) throws Exception {
            Path md = KbTestDocuments.writeMarkdown(dir);
            KbParsedDocument result = registry().parse(md, KbParseContext.defaults());
            assertThat(result.text()).contains("# 标题");
        }

        @Test
        @DisplayName("文件不存在 → KbParseException（不静默返回空）")
        void missingFileThrows(@TempDir Path dir) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> registry().parse(dir.resolve("不存在.md"), KbParseContext.defaults()))
                .isInstanceOf(KbParseException.class)
                .hasMessageContaining("不存在");
        }
    }

    @Nested
    @DisplayName("扩展名解析")
    class ExtensionParsing {

        @Test
        @DisplayName("取最后一段扩展名并转小写（文件名本身可带点）")
        void lastSegmentIsExtension() {
            assertThat(KbParsers.extensionOf("2026.11 报告v1.DOCX")).isEqualTo("docx");
            assertThat(KbParsers.extensionOf("C:\\tmp\\a.PDF")).isEqualTo("pdf");
            assertThat(KbParsers.extensionOf("/tmp/a.md")).isEqualTo("md");
            assertThat(KbParsers.extensionOf("noext")).isNull();
            assertThat(KbParsers.extensionOf("trailing.")).isNull();
            assertThat(KbParsers.extensionOf(null)).isNull();
        }

        @Test
        @DisplayName("MIME 归一化：去参数 + 小写")
        void normalizesMime() {
            assertThat(KbParsers.normalizeMime("Application/PDF; charset=UTF-8")).isEqualTo("application/pdf");
            assertThat(KbParsers.normalizeMime(null)).isEmpty();
        }
    }
}

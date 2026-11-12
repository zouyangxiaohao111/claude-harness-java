package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * anydoc 解析器 · 路由与降级契约测试（不依赖原生库）。
 *
 * <p><b>WHY 这些断言（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>PDF 绝不能被 anydoc 抢走</b> —— anydoc 对 PDF 没有 document model（{@code ERR_PDF_NO_MODEL=7}），
 *       只能出扁平 markdown，图表/表格/版面全丢。判据必须钉在 {@code supports()} 上，因为路由
 *       （{@code KbParserRegistry.select}）只看它。这条是派单书点名的硬约束。</li>
 *   <li><b>图片 / 简单格式同样不能被抢</b> —— 图片要走视觉模型、{@code csv/md/txt/json} 要走
 *       {@link KbTextParser}（它的结构化转换与源站逐字对齐）。任一侧漂移，用户上传的会被静默降级。</li>
 *   <li><b>原生库不可用时 {@code supports()} 必须 false</b>（而不是「返回空文本」）—— 否则文档会
 *       「解析成功但内容为空」入库，用户看到的是一份空文档，比解析失败更糟。这条同时守护
 *       「非 Windows / DLL 缺失 ⇒ 回落内置 Office 解析」的路由结论。</li>
 *   <li><b>声明的扩展名必须与 {@code supports()} 同源</b> —— {@code describeEngines()} 把
 *       {@code supportedExtensions()} 直接吐给前端下拉；声明了却路由不到就是假选项。</li>
 * </ol>
 *
 * <p><b>不做的事</b>：不测试真实转换（那需要原生库，见 {@code AnydocNative} 的类注释与
 * {@code front/scripts/build-anydoc.md}）。本机真机验证的完整输出记在交付报告里。
 */
@DisplayName("[A] anydoc 解析器 · 路由与降级")
class KbAnydocParserTest {

    private final KbAnydocParser parser = new KbAnydocParser();

    /** 与 {@code AnydocNative.isAvailable()} 同源的当前可用性（单测环境可能 true 也可能 false）。 */
    private boolean nativeAvailable() {
        return AnydocNative.isAvailable();
    }

    @Test
    @DisplayName("PDF 永不被抢占（anydoc 无 PDF document model，ERR_PDF_NO_MODEL=7）")
    void neverTakesPdf() {
        assertThat(parser.supports("报告.pdf", "application/pdf"))
            .as("PDF 必须留给 KbPdfParser")
            .isFalse();
        // 无扩展名、只靠 MIME 也不能认（本解析器压根不看 MIME）
        assertThat(parser.supports(null, "application/pdf")).isFalse();
        assertThat(parser.supportedExtensions()).doesNotContain("pdf");
    }

    @Test
    @DisplayName("图片 / 简单格式不被抢占（留给视觉模型与 KbTextParser）")
    void neverTakesImagesOrSimpleFormats() {
        for (String name : List.of(
            "a.jpg", "a.jpeg", "a.png", "a.gif", "a.bmp", "a.tiff", "a.tif", "a.webp",
            "a.csv", "a.tsv", "a.md", "a.markdown", "a.txt", "a.json", "a.log",
            "a.html", "a.htm", "a.xhtml", "a.xml", "a.mhtml")) {
            assertThat(parser.supports(name, null)).as("%s 不该被 anydoc 接管", name).isFalse();
        }
    }

    @Test
    @DisplayName("原生库可用时也不抢默认路由（2026-11-12 用户裁定：Office 默认走 Tika）")
    void givesUpDefaultRoutingEvenWhenNativeAvailable() {
        List<String> office = List.of(
            "a.doc", "a.docx", "a.docm", "a.dot", "a.dotx",
            "a.xls", "a.xlsx", "a.xlsm", "a.xlt", "a.xltx",
            "a.ppt", "a.pptx", "a.pptm", "a.pot", "a.potx",
            "a.rtf", "a.odt", "a.ods", "a.odp", "a.epub");
        for (String name : office) {
            assertThat(parser.supports(name, null))
                .as("%s 不该被 anydoc 默认接管（只允许 parser_engine_rules 点名）", name)
                .isFalse();
        }
    }

    @Test
    @DisplayName("声明的扩展名为空（默认路由让给 Office；能力仍由 parse() 保留，供规则点名）")
    void declaresNoExtensionsByDefault() {
        assertThat(parser.supportedExtensions()).isEmpty();
        assertThat(parser.displayName()).isNotBlank();
    }

    @Test
    @DisplayName("原生库不可用 ⇒ supports() 恒 false（让注册表路由到内置 Office 解析）")
    void unavailableMeansNoSupport() {
        assertThat(parser.supports("a.docx", null)).isFalse();
        assertThat(parser.supports("a.pptx", null)).isFalse();
        assertThat(parser.supports("a.epub", null)).isFalse();
        if (!nativeAvailable()) {
            assertThat(parser.isAvailable()).isFalse();
            assertThat(parser.unavailableReason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("parse() 失败 / 不可用时 fail-soft：不抛异常，metadata 带原因")
    void parseIsFailSoft() throws Exception {
        Path dir = Files.createTempDirectory("anydoc-failsoft");
        Path broken = dir.resolve("broken.docx");
        Files.writeString(broken, "not a zip at all");

        KbParsedDocument doc = parser.parse(broken, KbParseContext.defaults());

        assertThat(doc.text()).isNullOrEmpty();
        assertThat(doc.metadata().get(KbParsedDocument.META_PARSER)).isEqualTo("KbAnydocParser");
        assertThat(doc.metadata().get(KbAnydocParser.META_FALLBACK_REASON)).isNotBlank();
        assertThat(doc.metadata().get(KbParsedDocument.META_TEXT_LENGTH)).isEqualTo("0");
    }

    @Test
    @DisplayName("注册表：可用时 docx→KbAnydocParser、不可用时 docx→KbOfficeParser；pdf/png/csv 不受影响")
    void registryRouting() {
        KbParserRegistry registry = new KbParserRegistry(List.of(
            new KbPdfParser(null), parser, new KbImageParser(null),
            new KbOfficeParser(), new KbTextParser()));

        // 2026-11-12 用户裁定：anydoc 让出默认路由 ⇒ docx/epub 都落 KbOfficeParser
        String expectedDocx = "KbOfficeParser";
        assertThat(registry.select("a.docx", null).name()).isEqualTo(expectedDocx);
        assertThat(registry.select("a.epub", null).name()).isEqualTo(expectedDocx);
        // 三条不受 anydoc 影响的既有路由
        assertThat(registry.select("a.pdf", "application/pdf").name()).isEqualTo("KbPdfParser");
        assertThat(registry.select("a.png", "image/png").name()).isEqualTo("KbImageParser");
        assertThat(registry.select("a.csv", "text/csv").name()).isEqualTo("KbTextParser");
    }

    @Test
    @DisplayName("describeEngines：anydoc 出现在清单里，Available 与 isAvailable() 一致")
    void describeEnginesReflectsAvailability() {
        KbParserRegistry registry = new KbParserRegistry(List.of(
            new KbPdfParser(null), parser, new KbImageParser(null),
            new KbOfficeParser(), new KbTextParser()));

        ParserEngineInfo anydoc = registry.describeEngines().stream()
            .filter(e -> e.name().equals("KbAnydocParser")).findFirst().orElseThrow();

        assertThat(anydoc.displayName()).isEqualTo("anydoc");
        assertThat(anydoc.available()).isEqualTo(nativeAvailable());
        if (nativeAvailable()) {
            assertThat(anydoc.unavailableReason()).isEmpty();
        } else {
            assertThat(anydoc.unavailableReason()).isNotBlank();
            // 原因串要能一屏读完（JNA 的原始异常带整条 classpath，实测 22KB）
            assertThat(anydoc.unavailableReason().length()).isLessThanOrEqualTo(400);
        }
        // 其余引擎不受影响
        assertThat(registry.describeEngines())
            .filteredOn(e -> !e.name().equals("KbAnydocParser"))
            .allSatisfy(e -> {
                assertThat(e.available()).isTrue();
                assertThat(e.unavailableReason()).isEmpty();
            });
    }

    @Test
    @DisplayName("错误码名称表与 anydoc.h 的 ERR_* 逐条一致")
    void errorNamesMatchHeader() {
        assertThat(AnydocNative.errorName(0)).isEqualTo("OK");
        assertThat(AnydocNative.errorName(1)).isEqualTo("UNSUPPORTED");
        assertThat(AnydocNative.errorName(2)).isEqualTo("MALFORMED");
        assertThat(AnydocNative.errorName(3)).isEqualTo("ENCRYPTED");
        assertThat(AnydocNative.errorName(4)).isEqualTo("RESOURCE_LIMIT");
        assertThat(AnydocNative.errorName(5)).isEqualTo("MISSING_PART");
        assertThat(AnydocNative.errorName(6)).isEqualTo("IO");
        assertThat(AnydocNative.errorName(7)).isEqualTo("PDF_NO_MODEL");
        assertThat(AnydocNative.errorName(8)).isEqualTo("INVALID_ARG");
        assertThat(AnydocNative.errorName(9)).isEqualTo("UNKNOWN_FORMAT");
        assertThat(AnydocNative.errorName(10)).isEqualTo("NEEDS_OCR");
    }
}

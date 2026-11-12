package com.nexusai.kb.app.parser;

import com.nexusai.kb.app.chunk.ParserEngineRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析引擎族名别名 · {@code parser_engine_rules} 里写源站族名（{@code builtin} / {@code anydoc} /
 * {@code simple} / {@code paddleocr_vl} …）也要命中本仓解析器。
 *
 * <p><b>WHY（缺陷本身）</b>：{@code name()} 返回的是类名（{@code KbPdfParser}），源站规则里写的是引擎族名。
 * 只比类名时这类规则<b>静默</b>匹配不到 ⇒ 回落默认路由：配置看着保存成功、行为完全没变；
 * PaddleOCR-VL 则根本无法被规则点名（它只有排在最后的默认顺序）。
 *
 * <p><b>别名只追加、不改主名</b>：{@code name()} / {@code displayName()} 的取值被引擎清单与已存
 * {@code metadata.parser} 依赖，改主名会把现有配置与历史数据一起打断。
 */
@DisplayName("[B0] 解析引擎族名别名（源站族名命中 / 主名仍命中 / 未知名字仍回落）")
class KbParserEngineFamilyTest {

    /** 构造一个可用（endpoint 非空）的 PaddleOCR-VL 解析器 · 路由只问可用性，不会真的发请求。 */
    private final KbDocumentParser paddle = new KbPaddleOcrVlParser("http://127.0.0.1:9", "");

    private KbParserRegistry registry() {
        return new KbParserRegistry(List.of(
            new KbPdfParser(null),
            new KbAnydocParser(),
            new KbImageParser(null),
            new KbOfficeParser(),
            new KbHtmlParser(),
            new KbTextParser(),
            new KbXmindParser(),
            paddle));
    }

    private static ParserEngineRule rule(String engine, String... fileTypes) {
        return new ParserEngineRule(List.of(fileTypes), engine);
    }

    @Test
    @DisplayName("规则写 paddleocr_vl ⇒ 命中 KbPaddleOcrVlParser（此前完全无法被点名）")
    void paddleOcrVlByNameIsSelectable() {
        KbParserRegistry reg = registry();

        // 前提：默认顺序下 pdf 由内置 PDF 解析器接管（PaddleOCR-VL 排在 @Order(40)，默认拿不到）
        assertThat(reg.select("扫描件.pdf", null).name()).isEqualTo("KbPdfParser");

        KbDocumentParser picked = KbParserEngineRouter.select(reg, "扫描件.pdf",
            List.of(rule("paddleocr_vl", "pdf")));

        assertThat(picked).isSameAs(paddle);
        assertThat(picked.name()).isEqualTo("KbPaddleOcrVlParser");
    }

    @Test
    @DisplayName("规则写 simple ⇒ 按文件类型消歧命中 KbTextParser（simple 同时是图片解析器的别名）")
    void simpleFamilyHitsTextParserForTxt() {
        KbParserRegistry reg = registry();

        KbDocumentParser picked = KbParserEngineRouter.select(reg, "a.txt",
            List.of(rule("simple", "txt")));

        assertThat(picked).isNotNull();
        assertThat(picked.name())
            .as("simple 被文本与图片两个解析器共用 ⇒ 必须按 supports() 认文件，不能盲取 @Order 第一个")
            .isEqualTo("KbTextParser");
    }

    @Test
    @DisplayName("规则写 Java 类名 ⇒ 仍然命中（存量配置行为不变）")
    void javaClassNameStillHits() {
        KbParserRegistry reg = registry();

        assertThat(KbParserEngineRouter.select(reg, "报告.pdf",
            List.of(rule("KbAnydocParser", "pdf"))).name()).isEqualTo("KbAnydocParser");
        assertThat(KbParserEngineRouter.select(reg, "a.txt",
            List.of(rule("KbTextParser", "txt"))).name()).isEqualTo("KbTextParser");
        assertThat(KbParserEngineRouter.select(reg, "a.xmind",
            List.of(rule("KbXmindParser", "xmind"))).name()).isEqualTo("KbXmindParser");
    }

    @Test
    @DisplayName("未知名字 ⇒ 仍回落默认路由（不抛、不吞掉默认顺序）")
    void unknownEngineFallsBackToDefault() {
        KbParserRegistry reg = registry();

        KbDocumentParser picked = KbParserEngineRouter.select(reg, "a.html",
            List.of(rule("markitdown", "html")));

        assertThat(picked).isNotNull();
        assertThat(picked.name()).isEqualTo(reg.select("a.html", null).name());
    }

    @Test
    @DisplayName("builtin 一对多 ⇒ 按文件类型落到 PDF / HTML / XMind 各自的解析器")
    void builtinFamilyDisambiguatesByFileType() {
        KbParserRegistry reg = registry();

        assertThat(KbParserEngineRouter.select(reg, "报告.pdf",
            List.of(rule("builtin", "pdf"))).name()).isEqualTo("KbPdfParser");
        assertThat(KbParserEngineRouter.select(reg, "页面.html",
            List.of(rule("builtin", "html"))).name()).isEqualTo("KbHtmlParser");
        assertThat(KbParserEngineRouter.select(reg, "导图.xmind",
            List.of(rule("builtin", "xmind"))).name()).isEqualTo("KbXmindParser");
    }

    @Test
    @DisplayName("大小写不敏感、连字符等价下划线（paddleocr-vl ≡ PaddleOCR_VL）")
    void engineNameIsNormalized() {
        KbParserRegistry reg = registry();

        assertThat(KbParserEngineRouter.select(reg, "扫描件.pdf",
            List.of(rule("PaddleOCR-VL", "pdf")))).isSameAs(paddle);
        assertThat(KbParserEngineRouter.select(reg, "页面.html",
            List.of(rule("BUILTIN", "html"))).name()).isEqualTo("KbHtmlParser");
        assertThat(KbParserEngineRouter.select(reg, "a.txt",
            List.of(rule(" Simple ", "txt"))).name()).isEqualTo("KbTextParser");
    }

    @Test
    @DisplayName("office / anydoc 别名：office 唯一命中 Office；anydoc 命中 anydoc 解析器（可用时）")
    void officeAndAnydocFamilies() {
        KbParserRegistry reg = registry();

        assertThat(KbParserEngineRouter.select(reg, "a.docx",
            List.of(rule("office", "docx"))).name()).isEqualTo("KbOfficeParser");

        KbDocumentParser anydocPicked = KbParserEngineRouter.select(reg, "a.docx",
            List.of(rule("anydoc", "docx")));
        if (AnydocNative.isAvailable()) {
            assertThat(anydocPicked.name()).isEqualTo("KbAnydocParser");
        } else {
            // 与既有回落语义一致：点名的引擎不可用 ⇒ 走默认路由
            assertThat(anydocPicked.name()).isEqualTo(reg.select("a.docx", null).name());
        }
    }

    @Test
    @DisplayName("主名与展示名不被别名改动（引擎清单 / metadata.parser 依赖）")
    void primaryNamesUnchanged() {
        assertThat(new KbPdfParser(null).name()).isEqualTo("KbPdfParser");
        assertThat(new KbPdfParser(null).displayName()).isEqualTo("内置 PDF 解析");
        assertThat(paddle.name()).isEqualTo("KbPaddleOcrVlParser");
        assertThat(paddle.displayName()).isEqualTo("PaddleOCR-VL（外部服务）");

        assertThat(new KbPdfParser(null).engineFamilyNames()).containsExactlyInAnyOrder("builtin", "pdf");
        // 2026-11-12 改判：Office 默认解析器让出后不再声称 anydoc 族名
        // （否则「规则点名 anydoc」会被族名一对多消歧按 supports() 抢到 Office）
        assertThat(new KbOfficeParser().engineFamilyNames()).containsExactly("office");
        assertThat(new KbTextParser().engineFamilyNames()).containsExactlyInAnyOrder("simple", "text");
        assertThat(new KbAnydocParser().engineFamilyNames()).containsExactly("anydoc");
        assertThat(new KbImageParser(null).engineFamilyNames()).containsExactlyInAnyOrder("simple", "image");
        assertThat(paddle.engineFamilyNames())
            .containsExactlyInAnyOrder("paddleocr_vl", "paddleocr_vl_cloud");
        assertThat(new KbHtmlParser().engineFamilyNames()).containsExactlyInAnyOrder("builtin", "html");
        assertThat(new KbXmindParser().engineFamilyNames()).containsExactlyInAnyOrder("builtin", "xmind");

        // 未 override 的第三方实现仍有默认别名（= 主名），路由不会因为空集而漏人
        KbDocumentParser bare = new KbDocumentParser() {
            @Override
            public boolean supports(String fileName, String mimeType) {
                return false;
            }

            @Override
            public KbParsedDocument parse(java.nio.file.Path file, KbParseContext ctx) {
                throw new KbParseException("not used");
            }
        };
        assertThat(bare.engineFamilyNames()).containsExactly(bare.name());
    }
}

package com.nexusai.kb.app.parser;

import com.nexusai.kb.app.chunk.ParserEngineRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解析引擎按库路由 · 对齐 WeKnora {@code ChunkingConfig.ResolveParserEngine}
 * （{@code knowledgebase.go:343-367}）+ 消费点 {@code knowledge_process.go:4389}。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：路由错有三种后果，全都不报错：
 * <ol>
 *   <li><b>规则没生效</b> ⇒ 用户在下拉里给 PDF 选了 anydoc，实际仍走内置 PDF 解析 ——
 *       配置看着是存进去了，行为没变。</li>
 *   <li><b>规则过宽</b> ⇒ 把不该接管的类型（图片 / 简单格式）也抢走，解析出空文本入库。</li>
 *   <li><b>规则名写错就整库失败</b>（源站是这种严格语义）⇒ 一个笔误让整库文档全部解析失败。
 *       本仓<b>有意偏离</b>：记 warn 并回落默认，故这条必须被测试钉住。</li>
 * </ol>
 */
@DisplayName("[B0] 解析引擎按库路由（规则命中 / 未知引擎回落 / 空规则走默认顺序）")
class KbParserEngineRouterTest {

    private final KbAnydocParser anydoc = new KbAnydocParser();

    private KbParserRegistry registry() {
        return new KbParserRegistry(List.of(
            new KbPdfParser(null), anydoc, new KbImageParser(null),
            new KbOfficeParser(), new KbTextParser()));
    }

    private static ParserEngineRule rule(String engine, String... fileTypes) {
        return new ParserEngineRule(List.of(fileTypes), engine);
    }

    @Test
    @DisplayName("规则命中 pdf→KbAnydocParser ⇒ 返回 KbAnydocParser（即使它不是 pdf 的默认解析器）")
    void ruleOverridesDefaultForPdf() {
        KbParserRegistry reg = registry();
        // 前提：默认顺序下 pdf 由 KbPdfParser 接管
        assertThat(reg.select("报告.pdf", "application/pdf").name()).isEqualTo("KbPdfParser");

        KbDocumentParser picked = KbParserEngineRouter.select(reg, "报告.pdf",
            List.of(rule("KbAnydocParser", "pdf")));

        assertThat(picked).isNotNull();
        assertThat(picked.name()).isEqualTo("KbAnydocParser");
    }

    @Test
    @DisplayName("规则里的 engine 名不存在 ⇒ 回落默认（不抛）")
    void unknownEngineFallsBackToDefault() {
        KbParserRegistry reg = registry();

        KbDocumentParser picked = KbParserEngineRouter.select(reg, "a.docx",
            List.of(rule("NoSuchParser", "docx")));

        assertThat(picked).isNotNull();
        assertThat(picked.name())
            .as("未知引擎 ⇒ 走注册表默认顺序（docx 默认是 anydoc 可用 / Office 兜底）")
            .isEqualTo(reg.select("a.docx", null).name());
    }

    @Test
    @DisplayName("engine 名大小写不敏感（kbanydocparser 命中 KbAnydocParser）")
    void engineNameIsCaseInsensitive() {
        KbParserRegistry reg = registry();
        KbDocumentParser picked = KbParserEngineRouter.select(reg, "a.docx",
            List.of(rule("kbanydocparser", "docx")));
        assertThat(picked.name()).isEqualTo("KbAnydocParser");
    }

    @Test
    @DisplayName("规则为空 ⇒ 走默认 @Order 顺序（docx → KbOfficeParser，anydoc 已让出默认）")
    void emptyRulesUseRegistryDefault() {
        KbParserRegistry reg = registry();
        // 2026-11-12 用户裁定：本仓 Office 默认走 Tika，anydoc 只在规则点名时使用
        String expected = "KbOfficeParser";

        assertThat(KbParserEngineRouter.select(reg, "a.docx", List.of()).name())
            .isEqualTo(expected);
        assertThat(KbParserEngineRouter.select(reg, "a.docx", null).name())
            .isEqualTo(expected);

        // 不受规则影响的既有路由
        assertThat(KbParserEngineRouter.select(reg, "a.pdf", null).name()).isEqualTo("KbPdfParser");
        assertThat(KbParserEngineRouter.select(reg, "a.csv", null).name()).isEqualTo("KbTextParser");
    }

    @Test
    @DisplayName("首条命中规则胜出（声明顺序，源侧 knowledgebase.go:359-365）")
    void firstMatchingRuleWins() {
        KbParserRegistry reg = registry();

        KbDocumentParser picked = KbParserEngineRouter.select(reg, "a.docx", List.of(
            rule("KbTextParser", "docx"),
            rule("KbOfficeParser", "docx")));

        assertThat(picked.name()).isEqualTo("KbTextParser");
    }

    @Test
    @DisplayName("规则点名的引擎当前不可用 ⇒ 回落默认（anydoc 无原生库时）")
    void unavailableEngineFallsBack() {
        if (AnydocNative.isAvailable()) {
            // 本机有原生库，构造不出「不可用」状态（静态状态改造会污染同 JVM 用例）⇒ 跳过该侧
            assertThat(KbParserEngineRouter.select(registry(), "a.docx",
                List.of(rule("KbAnydocParser", "docx"))).name()).isEqualTo("KbAnydocParser");
            return;
        }
        KbParserRegistry reg = registry();
        KbDocumentParser picked = KbParserEngineRouter.select(reg, "a.docx",
            List.of(rule("KbAnydocParser", "docx")));
        assertThat(picked.name()).isEqualTo("KbOfficeParser");
    }

    @Test
    @DisplayName("一站式 parse：按规则落到指定解析器并真的产出文本")
    void parseRoutesThroughRules(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("note.txt");
        Files.writeString(file, "hello parent child routing");

        // 规则把 txt 指到 KbTextParser（本来就是默认，但这条走的是规则分支）
        KbParsedDocument doc = KbParserEngineRouter.parse(registry(), file,
            KbParseContext.defaults(), List.of(rule("KbTextParser", "txt")));

        assertThat(doc.text()).contains("hello parent child routing");
        assertThat(doc.metadata().get(KbParsedDocument.META_PARSER)).isEqualTo("KbTextParser");
    }

    @Test
    @DisplayName("一站式 parse：文件不存在 / 注册表为 null ⇒ 抛 KbParseException（与注册表同文案口径）")
    void parseValidatesInputs(@TempDir Path dir) {
        assertThatThrownBy(() -> KbParserEngineRouter.parse(registry(), dir.resolve("nope.txt"),
            KbParseContext.defaults(), List.of()))
            .isInstanceOf(KbParseException.class)
            .hasMessageContaining("不存在");

        assertThatThrownBy(() -> KbParserEngineRouter.parse(null, dir.resolve("x.txt"),
            KbParseContext.defaults(), List.of()))
            .isInstanceOf(KbParseException.class)
            .hasMessageContaining("注册表");
    }

    @Test
    @DisplayName("库文件名按平台推导：Windows / Mac / Linux 三态")
    void libraryFileNamePerPlatform() {
        assertThat(AnydocNative.libraryFileNameFor("Windows 11")).isEqualTo("anydoc_go.dll");
        assertThat(AnydocNative.libraryFileNameFor("Mac OS X")).isEqualTo("libanydoc_go.dylib");
        assertThat(AnydocNative.libraryFileNameFor("Darwin")).isEqualTo("libanydoc_go.dylib");
        assertThat(AnydocNative.libraryFileNameFor("Linux")).isEqualTo("libanydoc_go.so");
        // 未知平台给 .so（Linux 语义），⛔ 不再因「不是 Windows」直接判不可用
        assertThat(AnydocNative.libraryFileNameFor("FreeBSD")).isEqualTo("libanydoc_go.so");
    }
}

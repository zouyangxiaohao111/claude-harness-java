package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析引擎能力声明测试 · 守护 {@link KbParserRegistry#describeEngines()} 产出的对外契约。
 *
 * <p><b>WHY 这些断言（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>声明的扩展名必须真的路由到同一个解析器</b> —— 前端据此渲染下拉，用户选了却走到别的引擎
 *       就是「假选项」。这条断言把 {@code supportedExtensions()} 与 {@code supports()} 钉在一起：
 *       任一侧漂移（比如文本解析器把让位列表也暴露出去）都会红。</li>
 *   <li><b>顺序必须是 {@code @Order} 升序</b> —— Spring 对 {@code List} 注入不保证顺序；注册表存在的
 *       唯一理由就是消除这个不确定性。顺序错了，兜底解析器会排到前面抢走 Office 文件。</li>
 *   <li><b>JSON 键名必须大写开头</b> —— 前端按 {@code Name}/{@code FileTypes}/{@code Available} 读，
 *       改成 camelCase 前端会静默取到 {@code undefined}（不报错、下拉全空）。</li>
 *   <li><b>{@code FileTypes} 必须可复现</b> —— {@code Set.of(...)} 迭代顺序每次启动随机；
 *       不排序则同一份能力两次请求的数组顺序不同，契约无法比对。</li>
 * </ol>
 */
class ParserEngineInfoTest {

    private final KbParserRegistry registry = new KbParserRegistry(List.of(
        new KbTextParser(), new KbPdfParser(null), new KbOfficeParser(), new KbImageParser(null)));

    @Test
    @DisplayName("四个内置引擎按 @Order 升序：PDF → 图片 → Office → 文本兜底")
    void enginesAreOrderedByAnnotation() {
        assertThat(registry.describeEngines())
            .extracting(ParserEngineInfo::name)
            .containsExactly("KbPdfParser", "KbImageParser", "KbOfficeParser", "KbTextParser");
    }

    @Test
    @DisplayName("每个引擎声明的扩展名都真的由它来解析（否则前端是假选项）")
    void declaredExtensionsMatchActualRouting() {
        for (ParserEngineInfo engine : registry.describeEngines()) {
            assertThat(engine.fileTypes())
                .as("引擎 %s 必须声明扩展名", engine.name())
                .isNotEmpty();
            for (String ext : engine.fileTypes()) {
                assertThat(registry.select("sample." + ext, null))
                    .as("扩展名 .%s 由 %s 声明，必须路由到它自己", ext, engine.name())
                    .isSameAs(registry.parsers().stream()
                        .filter(p -> p.name().equals(engine.name()))
                        .findFirst().orElseThrow());
            }
        }
    }

    @Test
    @DisplayName("文本解析器不得把「让位列表」（pdf/doc/xls/ppt 等）当作自身能力")
    void textParserDoesNotAdvertiseOtherParsersExtensions() {
        ParserEngineInfo text = registry.describeEngines().stream()
            .filter(e -> e.name().equals("KbTextParser")).findFirst().orElseThrow();

        assertThat(text.fileTypes())
            .doesNotContain("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf")
            .contains("txt", "md", "csv", "json");
    }

    @Test
    @DisplayName("FileTypes 排序且可复现（Set.of 的迭代顺序随机）")
    void fileTypesAreSortedAndStable() {
        // ⚠️ 用 LinkedHashSet 喂<b>倒序</b>输入：迭代顺序确定，删掉 record 里的排序就会稳定变红。
        // （直接对 Set.of 断言「是否有序」会有随机通过率，判据不可靠。）
        java.util.LinkedHashSet<String> reversed = new java.util.LinkedHashSet<>(
            List.of("z", "y", "x"));
        assertThat(new ParserEngineInfo("x", "y", reversed, true, "").fileTypes())
            .containsExactly("x", "y", "z");

        // 注册表层同样按迭代顺序核 —— Set.equals 不比较顺序，用它判序会假绿。
        ParserEngineInfo office = registry.describeEngines().stream()
            .filter(e -> e.name().equals("KbOfficeParser")).findFirst().orElseThrow();
        assertThat(office.fileTypes())
            .containsExactlyElementsOf(new TreeSet<>(office.fileTypes()));
    }

    @Test
    @DisplayName("序列化键名逐字对齐源站：Name/Description/FileTypes/Available/UnavailableReason")
    void jsonKeysMatchUpstreamContract() throws Exception {
        JsonNode first = new ObjectMapper()
            .valueToTree(registry.describeEngines().get(0));

        assertThat(first.fieldNames()).toIterable()
            .containsExactlyInAnyOrder(
                "Name", "Description", "FileTypes", "Available", "UnavailableReason");
        assertThat(first.get("Name").asText()).isEqualTo("KbPdfParser");
        assertThat(first.get("Description").asText()).isEqualTo("内置 PDF 解析");
        assertThat(first.get("Available").asBoolean()).isTrue();
        assertThat(first.get("UnavailableReason").asText()).isEmpty();
        assertThat(first.get("FileTypes"))
            .extracting(JsonNode::asText)
            .containsExactly("pdf");
    }

    @Test
    @DisplayName("四个内置引擎恒可用；字段不为 null")
    void builtinEnginesAreAlwaysAvailable() {
        assertThat(registry.describeEngines())
            .allSatisfy(e -> {
                assertThat(e.available()).isTrue();
                assertThat(e.unavailableReason()).isEmpty();
                assertThat(e.displayName()).isNotBlank();
                assertThat(e.fileTypes()).isNotNull();
            });
    }
}

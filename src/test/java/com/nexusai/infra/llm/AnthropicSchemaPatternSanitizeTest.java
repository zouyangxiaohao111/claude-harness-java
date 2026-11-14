package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnthropicSdkProvider#sanitizeAnthropicSchemaPatterns} 单测 · 纯函数，不启动 Spring。
 *
 * <p>防的线上故障（2026-11-14）：接入 rea MCP 后 ant 通道 400 ——
 * {@code Invalid schema for function 'mcp__rea__extract_artifact': "...^[U+D800-U+DFFF]$" is not a regex}。
 * 该 pattern 由 MCP server 自带（本仓不生成），Anthropic/RE2 不接受代理项转义写法。
 */
class AnthropicSchemaPatternSanitizeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 线上那条 pattern 的字面文本（反斜杠 + uD800 形式的转义文本，不是真实代理项字符）。 */
    private static final String SURROGATE_PATTERN_TEXT = "^[\\uD800-\\uDFFF]$";

    private static ObjectNode schemaWith(String pattern) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode artifact = props.putObject("artifact");
        artifact.put("type", "string");
        artifact.put("pattern", pattern);
        artifact.put("description", "要抽取的产物");
        props.putObject("limit").put("type", "integer").put("minimum", 1);
        ArrayNode required = schema.putArray("required");
        required.add("artifact");
        return schema;
    }

    @Test
    @DisplayName("嵌套在 properties 里的代理项 pattern 被剔除，同层其它字段与 required 原样保留")
    void stripsNestedSurrogatePattern() {
        ObjectNode schema = schemaWith(SURROGATE_PATTERN_TEXT);

        JsonNode out = AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(schema, "mcp__rea__extract_artifact");

        JsonNode artifact = out.path("properties").path("artifact");
        assertFalse(artifact.has("pattern"), "含代理项转义的 pattern 必须被剔除");
        assertEquals("要抽取的产物", artifact.path("description").asText(), "同层其它字段不能丢");
        assertEquals("integer", out.path("properties").path("limit").path("type").asText());
        assertEquals("artifact", out.path("required").path(0).asText());
    }

    @Test
    @DisplayName("入参不被改写（返回副本）")
    void inputNotMutated() {
        ObjectNode schema = schemaWith(SURROGATE_PATTERN_TEXT);
        AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(schema, "t");
        assertTrue(schema.path("properties").path("artifact").has("pattern"),
            "⛔ 只生成副本，不能就地改原始 schema");
    }

    @Test
    @DisplayName("无需改写的 pattern 原样保留（不含 Unicode 转义 / 或只是普通转义）")
    void keepsNormalPatterns() {
        for (String p : new String[]{"^\\d+$", "^[a-z0-9_-]{1,64}$", "^https?://", "^\\w+(\\.\\w+)*$"}) {
            JsonNode out = AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(schemaWith(p), "t");
            assertEquals(p, out.path("properties").path("artifact").path("pattern").asText(),
                "不该误伤普通 pattern: " + p);
        }
    }

    @Test
    @DisplayName("非代理区 Unicode 转义改写为 RE2 的 \\x{...}（语义不变，不删约束）")
    void rewritesNonSurrogateUnicodeEscapes() {
        assertEquals("^[\\x{4e00}-\\x{9fa5}]+$", AnthropicSdkProvider.toRe2Pattern("^[\\u4e00-\\u9fa5]+$"));
        assertEquals("\\x{41}", AnthropicSdkProvider.toRe2Pattern("\\u0041"));
        assertEquals("a\\x{20}b", AnthropicSdkProvider.toRe2Pattern("a\\u0020b"));
    }

    @Test
    @DisplayName("代理对合并成单码点；孤立代理项返回 null（RE2 无法表达）")
    void surrogatePairsAndLoneSurrogates() {
        assertEquals("\\x{1f600}", AnthropicSdkProvider.toRe2Pattern("\\uD83D\\uDE00"), "astral 字符应合并");
        assertNull(AnthropicSdkProvider.toRe2Pattern("\\uD83D"), "孤立高代理项");
        assertNull(AnthropicSdkProvider.toRe2Pattern("\\uDE00"), "孤立低代理项");
        assertNull(AnthropicSdkProvider.toRe2Pattern("^[\\uD800-\\uDFFF]$"), "线上那条");
    }

    @Test
    @DisplayName("转义的反斜杠 \\\\u 不是 Unicode 转义，原样保留")
    void escapedBackslashIsNotUnicodeEscape() {
        assertEquals("\\\\u0041", AnthropicSdkProvider.toRe2Pattern("\\\\u0041"),
            "文本 `\\\\u0041`（两个反斜杠）表示字面反斜杠 + u0041，不得改写");
    }

    @Test
    @DisplayName("RE2 判定（re2j 真编译）：能过就原样，Unicode 转义能改写过，其余 RE2 不支持的整类删除")
    void re2Resolution() {
        assertEquals("^\\d+$", AnthropicSdkProvider.resolvePatternForRe2("^\\d+$"), "普通 pattern 原样");
        assertEquals("^[\\x{4e00}-\\x{9fa5}]+$",
            AnthropicSdkProvider.resolvePatternForRe2("^[\\u4e00-\\u9fa5]+$"), "Unicode 转义改写后能过");
        assertNull(AnthropicSdkProvider.resolvePatternForRe2("^[\\uD800-\\uDFFF]$"), "孤立代理项无解");
        assertNull(AnthropicSdkProvider.resolvePatternForRe2("(?<=a)b"),
            "lookbehind 是 RE2 不支持的整类语法（改写救不了，直接删）");
        assertNull(AnthropicSdkProvider.resolvePatternForRe2("(a)\\1"), "反向引用同理");
    }

    @Test
    @DisplayName("数组/items 里的 pattern 也会被处理")
    void walksArraysAndItems() {
        ObjectNode schema = JSON.createObjectNode();
        ObjectNode props = schema.putObject("properties");
        ObjectNode arr = props.putObject("list");
        arr.put("type", "array");
        ObjectNode item = arr.putObject("items");
        item.put("type", "string");
        item.put("pattern", SURROGATE_PATTERN_TEXT);

        JsonNode out = AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(schema, "t");
        assertFalse(out.path("properties").path("list").path("items").has("pattern"));
    }

    @Test
    @DisplayName("字面孤立代理项同样剔除；非对象 schema 原样返回")
    void literalLoneSurrogateAndNonObject() {
        ObjectNode schema = schemaWith("^" + (char) 0xD800 + "$");
        JsonNode out = AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(schema, "t");
        assertFalse(out.path("properties").path("artifact").has("pattern"));

        ArrayNode arr = JSON.createArrayNode();
        assertSame(arr, AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(arr, "t"));
        assertNull(AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(null, "t"));
    }
}

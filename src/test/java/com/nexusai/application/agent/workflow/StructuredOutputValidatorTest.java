package com.nexusai.application.agent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StructuredOutputValidator D-2 全关键字升级测试 · 对齐 CC {@code structuredOutput.ts} Ajv 编译语义
 * （{@code new Ajv({allErrors:true, strict:false})} :9 + errors 映射 :39-42）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>oneOf 恰一语义</b>（E 域）— Ajv 的 oneOf 是"恰一个"而非"任一"：值同时命中两个子 schema
 *       必须报错。若实现成"任一通过"，{@code oneOf:[{type:number},{type:integer}]} 对整数永远放行，
 *       等于把 oneOf 退化成 anyOf，契约丢失。</li>
 *   <li><b>enum 数字数值相等</b>（F 域）— JS 无 int/long/double 类型分号，{@code 1} 与 {@code 1.0} 同值；
 *       Java 端 JSON 反序列化给 Double、schema 手写 Integer，若直接 equals 会把合法枚举值误杀。</li>
 *   <li><b>pattern 部分匹配</b>（G 域）— JS {@code RegExp.test} 是子串命中即过，Java 全匹配会误拒
 *       {@code "xabcy"} 这种合法值（Ajv pattern 非全量锚定）。</li>
 *   <li><b>format 已知格式 + 未知放行</b>（H 域）— email/uri/date 等已知格式必须校验（P1 D-2 曾静默跳过）；
 *       未知格式名忽略（对齐 Ajv strict:false 不抛 "unknown format"）。</li>
 *   <li><b>additionalProperties 未知键拒绝</b>（I 域）— {@code additionalProperties:false} 下多余键必须报错，
 *       且 {@code patternProperties} 命中的键不算未知（properties ∪ patternProperties 为已知键）。</li>
 *   <li><b>instancePath 前缀对齐</b>（J 域）— 根级错误不加前缀（CC {@code e.instancePath ? ... : message}），
 *       嵌套带 {@code /path} 前缀；根级若加了前导空格会污染 dead.detail 聚合文案。</li>
 * </ol>
 */
class StructuredOutputValidatorTest {

    // ─────────────────────────── A 域：assertValidJsonSchema（D-5）───────────────────────────

    @Test
    @DisplayName("A.1 null schema 放行（无 schema 不校验）")
    void assertValidNullSchemaAllowed() {
        StructuredOutputValidator.assertValidJsonSchema(null);
    }

    @Test
    @DisplayName("A.2 Map schema 放行")
    void assertValidMapSchemaAllowed() {
        StructuredOutputValidator.assertValidJsonSchema(Map.of("type", "object"));
    }

    @Test
    @DisplayName("A.3 非 Map schema（String）拒绝 → IllegalArgumentException（配置错误直接抛，不重试）")
    void assertValidNonMapStringRejected() {
        // WHY: schema 非法是 workflow 配置错误（CC structuredOutput.ts:21-23 前置编译直接抛，
        // hooks.ts:71-73 不重试），而非瞬时 agent 失败。
        assertThatThrownBy(() -> StructuredOutputValidator.assertValidJsonSchema("{\"type\":\"object\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON schema");
    }

    @Test
    @DisplayName("A.4 非 Map schema（List）拒绝")
    void assertValidNonMapListRejected() {
        assertThatThrownBy(() -> StructuredOutputValidator.assertValidJsonSchema(List.of("type")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────── E 域：oneOf / anyOf ───────────────────────────

    @Test
    @DisplayName("E.1 oneOf：恰一个子 schema 命中才通过（integer 或 string）")
    void oneOfExactlyOnePasses() {
        Map<String, Object> schema = Map.of(
                "oneOf", List.of(Map.of("type", "integer"), Map.of("type", "string")));

        assertThat(StructuredOutputValidator.validateAgainstSchema("5", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema("\"ok\"", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("true", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("must match exactly one schema in oneOf"));
    }

    @Test
    @DisplayName("E.2 oneOf 恰一语义：同时命中两个子 schema 必须失败（非 anyOf）")
    void oneOfMultipleMatchesFails() {
        Map<String, Object> schema = Map.of(
                "oneOf", List.of(Map.of("type", "number"), Map.of("type", "integer")));

        // 整数 5 同时是 number 和 integer → 命中 2 个 → 必须 invalid
        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("5", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("must match exactly one schema in oneOf"));
    }

    @Test
    @DisplayName("E.3 anyOf：至少一个子 schema 命中即通过")
    void anyOfAtLeastOnePasses() {
        Map<String, Object> schema = Map.of(
                "anyOf", List.of(Map.of("type", "integer"), Map.of("type", "string")));

        assertThat(StructuredOutputValidator.validateAgainstSchema("5", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema("\"ok\"", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("true", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("must match a schema in anyOf"));
    }

    // ─────────────────────────── F 域：enum ───────────────────────────

    @Test
    @DisplayName("F.1 enum：命中放行，未命中报错")
    void enumHitAndMiss() {
        Map<String, Object> schema = Map.of("enum", List.of(1, 2, 3));

        assertThat(StructuredOutputValidator.validateAgainstSchema("2", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("4", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors())
                .anyMatch(e -> e.contains("must be equal to one of the allowed values"));
    }

    @Test
    @DisplayName("F.2 enum 数字数值相等：Double 1.0 命中 Integer 1（JS 同值语义）")
    void enumNumericEqualityAcrossTypes() {
        Map<String, Object> schema = Map.of("enum", List.of(1, 2, 3));

        // JSON 反序列化 1.0 → Double；schema 手写 1 → Integer；JS 中两者同值
        assertThat(StructuredOutputValidator.validateAgainstSchema("1.0", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema("1.5", schema).valid()).isFalse();
    }

    @Test
    @DisplayName("F.3 enum 嵌套对象深比较")
    void enumNestedObjectDeepEquals() {
        Map<String, Object> schema = Map.of(
                "enum", List.of(Map.of("role", "admin", "level", 1)));

        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "{\"role\":\"admin\",\"level\":1}", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "{\"role\":\"admin\",\"level\":2}", schema).valid()).isFalse();
    }

    // ─────────────────────────── G 域：pattern ───────────────────────────

    @Test
    @DisplayName("G.1 pattern：正则命中放行，未命中报错")
    void patternHitAndMiss() {
        Map<String, Object> schema = Map.of("type", "string", "pattern", "^[a-z]+$");

        assertThat(StructuredOutputValidator.validateAgainstSchema("\"abc\"", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("\"ABC\"", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("must match pattern \"^[a-z]+$\""));
    }

    @Test
    @DisplayName("G.2 pattern 部分匹配：pattern \"abc\" 命中 \"xabcy\"（JS RegExp.test 语义）")
    void patternPartialMatch() {
        Map<String, Object> schema = Map.of("type", "string", "pattern", "abc");

        assertThat(StructuredOutputValidator.validateAgainstSchema("\"xabcy\"", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema("\"xyz\"", schema).valid()).isFalse();
    }

    // ─────────────────────────── H 域：format ───────────────────────────

    @Test
    @DisplayName("H.1 format email：合法命中，非法报错")
    void formatEmail() {
        Map<String, Object> schema = Map.of("type", "string", "format", "email");

        assertThat(StructuredOutputValidator.validateAgainstSchema("\"a@b.com\"", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("\"not-an-email\"", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("must match format \"email\""));
    }

    @Test
    @DisplayName("H.2 format uri：无 scheme 的相对串非法（绝对 URI 才合法）")
    void formatUri() {
        Map<String, Object> schema = Map.of("type", "string", "format", "uri");

        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "\"https://example.com/a/b\"", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema("\"/relative/path\"", schema).valid()).isFalse();
    }

    @Test
    @DisplayName("H.3 format date：2026-02-30 非法（LocalDate 校验真实历法）")
    void formatDate() {
        Map<String, Object> schema = Map.of("type", "string", "format", "date");

        assertThat(StructuredOutputValidator.validateAgainstSchema("\"2026-02-28\"", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema("\"2026-02-30\"", schema).valid()).isFalse();
        assertThat(StructuredOutputValidator.validateAgainstSchema("\"2026/02/28\"", schema).valid()).isFalse();
    }

    @Test
    @DisplayName("H.4 format uuid")
    void formatUuid() {
        Map<String, Object> schema = Map.of("type", "string", "format", "uuid");

        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "\"550e8400-e29b-41d4-a716-446655440000\"", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "\"550e8400-e29b-41d4\"", schema).valid()).isFalse();
    }

    @Test
    @DisplayName("H.5 format 未知格式忽略（对齐 Ajv strict:false 不抛 unknown format）")
    void formatUnknownIgnored() {
        Map<String, Object> schema = Map.of("type", "string", "format", "imaginary-format");

        assertThat(StructuredOutputValidator.validateAgainstSchema("\"whatever\"", schema).valid()).isTrue();
    }

    // ─────────────────────────── I 域：additionalProperties ───────────────────────────

    @Test
    @DisplayName("I.1 additionalProperties:false：未知键拒绝，已知键放行")
    void additionalPropertiesFalseRejectsUnknown() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of("type", "integer")),
                "additionalProperties", false);

        assertThat(StructuredOutputValidator.validateAgainstSchema("{\"a\":1}", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("{\"a\":1,\"b\":2}", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("must NOT have additional properties"));
    }

    @Test
    @DisplayName("I.2 additionalProperties:false + patternProperties：pattern 命中键不算未知")
    void patternPropertiesKeysNotAdditional() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of("type", "integer")),
                "patternProperties", Map.of("^x-", Map.of("type", "string")),
                "additionalProperties", false);

        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "{\"a\":1,\"x-extra\":\"ok\"}", schema).valid()).isTrue();
        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "{\"a\":1,\"y-extra\":\"ok\"}", schema).valid()).isFalse();
    }

    @Test
    @DisplayName("I.3 additionalProperties 为 schema：未知键按该 schema 校验")
    void additionalPropertiesSchemaValidatesUnknownKeys() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of("type", "integer")),
                "additionalProperties", Map.of("type", "string"));

        assertThat(StructuredOutputValidator.validateAgainstSchema(
                "{\"a\":1,\"extra\":\"ok\"}", schema).valid()).isTrue();

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("{\"a\":1,\"extra\":5}", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.startsWith("/extra") && e.contains("must be string"));
    }

    @Test
    @DisplayName("I.4 嵌套 additionalProperties 错误路径前缀：错误在对象路径 /obj")
    void nestedAdditionalPropertiesErrorPath() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("obj", Map.of(
                        "type", "object",
                        "properties", Map.of("a", Map.of("type", "integer")),
                        "additionalProperties", false)));

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("{\"obj\":{\"a\":1,\"b\":2}}", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.startsWith("/obj") && e.contains("must NOT have additional properties"));
    }

    // ─────────────────────────── J 域：instancePath 前缀对齐 ───────────────────────────

    @Test
    @DisplayName("J.1 根级错误不加前导空格（CC e.instancePath ? ... : message）")
    void rootErrorNoLeadingSpace() {
        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("stale-string", Map.of("type", "object"));

        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).containsExactly("must be object");
    }

    @Test
    @DisplayName("J.2 嵌套枚举/pattern 错误带 /path 前缀")
    void nestedKeywordErrorsCarryPathPrefix() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("type", "string", "enum", List.of("a", "b")),
                        "code", Map.of("type", "string", "pattern", "^\\d+$")));

        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("{\"kind\":\"x\",\"code\":\"abc\"}", schema);

        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.startsWith("/kind") && e.contains("allowed values"));
        assertThat(v.errors()).anyMatch(e -> e.startsWith("/code") && e.contains("must match pattern"));
    }
}

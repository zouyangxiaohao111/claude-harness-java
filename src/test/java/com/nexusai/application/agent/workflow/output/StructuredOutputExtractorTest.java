package com.nexusai.application.agent.workflow.output;

import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.StructuredOutputValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StructuredOutputExtractor 测试 · 对齐 CC {@code structuredOutput.ts}（提取）+ {@code claudeCodeBackend.ts}
 * （dead 分类）+ {@code hooks.ts}（二次校验）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>fenced 优先</b>（A 域）— agent 自发加 ```json 围栏是高频形态；剥围栏整块解析，
 *       不得被裸文本中的无关花括号干扰。若围栏解析失序，长叙述后 agent 返回的正确 JSON 会漏掉。</li>
 *   <li><b>括号平衡扫描</b>（B 域）— 字符串字面量内的 {@code {}}/转义符必须跳过，否则
 *       {@code {"url":"http://x/{y}"}} 这类合法输出会被误切、误报 dead。嵌套对象取整段平衡对，
 *       不拼接多个不相关片段。</li>
 *   <li><b>dead 分类</b>（C 域）— 找不到纯对象 → no-structured-output（带 200 字预览）；
 *       找到但不匹配 schema → invalid-structured-output。两种 reason 是日志聚合/审计关键，
 *       不可混淆（types.ts:60-65）。</li>
 *   <li><b>校验器 String 输出解析</b>（D 域）— Java 端 ok.output 为 String（JSON 文本），
 *       校验必须解析后判形状，否则 schema 模式所有 ok 结果都会被误杀为 dead。</li>
 * </ol>
 */
class StructuredOutputExtractorTest {

    // ─────────────────────────── A 域：fenced 优先 ───────────────────────────

    @Test
    @DisplayName("A.1 fenced ```json 围栏优先：剥围栏整块解析，忽略前导叙述")
    void fencedJsonBlockTakesPriority() {
        String text = "完成，以下是结果：\n```json\n{\"name\": \"w2b\", \"ok\": true}\n```\n没有更多。";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("name", "w2b").containsEntry("ok", true);
    }

    @Test
    @DisplayName("A.2 fenced 无语言标签的 ``` 围栏同样支持")
    void fencedPlainBlock() {
        String text = "```\n{\"a\": 1}\n```";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("a", 1);
    }

    @Test
    @DisplayName("A.3 围栏解析失败则回退到裸文本括号平衡扫描（不中断）")
    void fencedParseFailureFallsThroughToBraceScan() {
        String text = "```json\n{not valid json}\n```\n然后 {" + "\"b\": 2" + "}";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("b", 2);
    }

    // ─────────────────────────── B 域：括号平衡扫描 ───────────────────────────

    @Test
    @DisplayName("B.1 裸文本中取首个括号平衡对象，容忍前后叙述")
    void bareBalancedObject() {
        String text = "结果在这里：{\"a\": 1} 完毕";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("a", 1);
    }

    @Test
    @DisplayName("B.2 嵌套对象取整段平衡对")
    void nestedObjectBalanced() {
        String text = "{\"outer\": {\"inner\": {\"deep\": 1}}, \"sibling\": 2}";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("sibling", 2);
        assertThat((Map<String, Object>) map.get("outer")).containsKeys("inner");
    }

    @Test
    @DisplayName("B.3 字符串字面量内的 {} 必须跳过（URL 含花括号不被误切）")
    void bracesInsideStringLiteralSkipped() {
        String text = "{\"url\": \"http://example.com/path/{id}\", \"n\": 1}";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("url")).isEqualTo("http://example.com/path/{id}");
        assertThat(map).containsEntry("n", 1);
    }

    @Test
    @DisplayName("B.4 转义字符正确处理（a\\\"b 含引号/花括号）")
    void escapeCharactersHandled() {
        String text = "{\"s\": \"a\\\"b{c}\", \"k\": 2}";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("k", 2);
    }

    @Test
    @DisplayName("B.5 多个不相关片段：取首个可解析纯对象，不拼接")
    void doesNotConcatenateFragments() {
        String text = "{\"a\": 1} {\"b\": 2}";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("a", 1);
        assertThat((Map<String, Object>) result).doesNotContainKey("b");
    }

    @Test
    @DisplayName("B.6 不平衡花括号 → null（不误切到错误区间）")
    void unbalancedBracesNull() {
        String text = "{\"a\": 1";
        Object result = StructuredOutputExtractor.extractStructuredOutput(List.of(StructuredContentBlock.text(text)));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("B.7 顶层数组/标量 → null（schema 模式契约是纯对象，跑偏即 off-track）")
    void arrayOrScalarTopLevelIsNull() {
        assertThat(StructuredOutputExtractor.extractStructuredOutput(
                List.of(StructuredContentBlock.text("[1, 2, 3]")))).isNull();
        assertThat(StructuredOutputExtractor.extractStructuredOutput(
                List.of(StructuredContentBlock.text("just some text")))).isNull();
    }

    @Test
    @DisplayName("B.8 非 text 块与空 text 被跳过")
    void nonTextBlocksSkipped() {
        List<StructuredContentBlock> content = List.of(
                new StructuredContentBlock("tool_use", null),
                new StructuredContentBlock("text", null),
                StructuredContentBlock.text("{\"ok\": true}"));
        Object result = StructuredOutputExtractor.extractStructuredOutput(content);

        assertThat(result).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result).containsEntry("ok", true);
    }

    // ─────────────────────────── C 域：dead 分类 ───────────────────────────

    @Test
    @DisplayName("C.1 找不到 JSON → dead{no-structured-output}，detail 为 200 字预览")
    void noJsonDeadNoStructuredOutput() {
        AgentRunResult result = StructuredOutputExtractor.classifySchemaMode(
                Map.of("type", "object"), List.of(StructuredContentBlock.text("agent 忘了输出 JSON")));

        assertThat(result).isInstanceOf(AgentRunResultDead.class);
        AgentRunResultDead dead = (AgentRunResultDead) result;
        assertThat(dead.reason()).isEqualTo(AgentRunResult.DeadReason.NO_STRUCTURED_OUTPUT);
        assertThat(dead.detail()).isEqualTo("agent 忘了输出 JSON");
    }

    @Test
    @DisplayName("C.2 200 字预览截断（claudeCodeBackend.ts:379 slice(0,200)）")
    void previewTruncatedTo200Chars() {
        String longText = "x".repeat(500);
        AgentRunResult result = StructuredOutputExtractor.classifySchemaMode(
                Map.of("type", "object"), List.of(StructuredContentBlock.text(longText)));

        assertThat(result).isInstanceOf(AgentRunResultDead.class);
        assertThat(((AgentRunResultDead) result).detail()).hasSize(200);
    }

    @Test
    @DisplayName("C.3 提取到对象但不匹配 schema → dead{invalid-structured-output}")
    void invalidStructuredOutputDead() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of("id", Map.of("type", "integer")));
        String json = "{\"name\": \"missing-id\"}";

        AgentRunResult result = StructuredOutputExtractor.classifySchemaMode(schema, List.of(StructuredContentBlock.text(json)));

        assertThat(result).isInstanceOf(AgentRunResultDead.class);
        AgentRunResultDead dead = (AgentRunResultDead) result;
        assertThat(dead.reason()).isEqualTo(AgentRunResult.DeadReason.INVALID_STRUCTURED_OUTPUT);
        assertThat(dead.detail()).contains("required property 'id'");
    }

    @Test
    @DisplayName("C.4 提取到对象且匹配 schema → ok，output 为紧凑 JSON 字符串")
    void validSchemaModeOk() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("name", Map.of("type", "string")));
        String json = "{\"name\": \"w2b\"}";

        AgentRunResult result = StructuredOutputExtractor.classifySchemaMode(schema, List.of(StructuredContentBlock.text(json)));

        assertThat(result).isInstanceOf(AgentRunResultOk.class);
        AgentRunResultOk ok = (AgentRunResultOk) result;
        // Jackson 序列化为紧凑 JSON（去空白）· ok.output 语义等价于 CC 的 structured object（types.ts:44-51）
        assertThat(ok.output()).isEqualTo("{\"name\":\"w2b\"}");
    }

    // ─────────────────────────── D 域：校验器 String 输出解析 ───────────────────────────

    @Test
    @DisplayName("D.1 String 输出为 JSON 对象文本 → type=object 校验通过（schema 模式 ok 不再被误杀）")
    void stringJsonObjectValidatesAgainstObjectSchema() {
        StructuredOutputValidator.ValidationResult v = StructuredOutputValidator.validateAgainstSchema(
                "{\"a\": 1}", Map.of("type", "object"));

        assertThat(v.valid()).isTrue();
    }

    @Test
    @DisplayName("D.2 非 JSON 裸文本 String → type=object 必 invalid（对齐 A.2 旧语义）")
    void nonJsonStringInvalidAgainstObjectSchema() {
        StructuredOutputValidator.ValidationResult v = StructuredOutputValidator.validateAgainstSchema(
                "stale-string", Map.of("type", "object"));

        assertThat(v.valid()).isFalse();
    }

    @Test
    @DisplayName("D.3 递归 required/properties 校验 + 错误路径前缀（Ajv 式）")
    void recursiveRequiredAndPropertyType() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of(
                        "id", Map.of("type", "integer"),
                        "meta", Map.of("type", "object", "properties",
                                Map.of("depth", Map.of("type", "integer")))));

        StructuredOutputValidator.ValidationResult missingRequired =
                StructuredOutputValidator.validateAgainstSchema("{\"name\": 1}", schema);
        assertThat(missingRequired.valid()).isFalse();
        assertThat(missingRequired.errors()).anyMatch(e -> e.contains("required property 'id'"));

        StructuredOutputValidator.ValidationResult wrongType =
                StructuredOutputValidator.validateAgainstSchema("{\"id\": \"not-int\", \"meta\": {}}", schema);
        assertThat(wrongType.valid()).isFalse();
        assertThat(wrongType.errors()).anyMatch(e -> e.startsWith("/id") && e.contains("must be integer"));

        StructuredOutputValidator.ValidationResult nestedWrongType =
                StructuredOutputValidator.validateAgainstSchema("{\"id\": 1, \"meta\": {\"depth\": \"x\"}}", schema);
        assertThat(nestedWrongType.valid()).isFalse();
        assertThat(nestedWrongType.errors()).anyMatch(e -> e.startsWith("/meta/depth") && e.contains("must be integer"));

        StructuredOutputValidator.ValidationResult valid =
                StructuredOutputValidator.validateAgainstSchema("{\"id\": 1, \"meta\": {\"depth\": 2}}", schema);
        assertThat(valid.valid()).isTrue();
    }

    @Test
    @DisplayName("D.4 数组 items 递归校验")
    void arrayItemsRecursive() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "items", Map.of("type", "integer"));

        assertThat(StructuredOutputValidator.validateAgainstSchema("[1, 2, 3]", schema).valid()).isTrue();
        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema("[1, \"x\", 3]", schema);
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.startsWith("/1") && e.contains("must be integer"));
    }

    @Test
    @DisplayName("D.5 validateStructuredResult：ok+不匹配 schema → dead{invalid-structured-output}")
    void validateStructuredResultClassifiesInvalid() {
        AgentRunResult ok = new AgentRunResultOk("{\"a\": 1}", 10, "m", 1, 100);
        AgentRunResult result = StructuredOutputValidator.validateStructuredResult(
                ok, Map.of("type", "object", "required", List.of("missing")));

        assertThat(result).isInstanceOf(AgentRunResultDead.class);
        assertThat(((AgentRunResultDead) result).reason())
                .isEqualTo(AgentRunResult.DeadReason.INVALID_STRUCTURED_OUTPUT);
    }
}

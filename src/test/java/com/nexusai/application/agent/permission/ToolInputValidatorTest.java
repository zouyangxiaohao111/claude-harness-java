package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolErrorFormatter;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-PR-02 / MT-06 · ToolInputValidator 边界对齐 Zod safeParse（enum / 嵌套 / 数组元素）。
 *
 * <p><b>WHY (意图验证)</b>: CC 在 toolExecution.ts:615 用 <b>真实 Zod 运行时</b>
 * {@code tool.inputSchema.safeParse(input)} 做输入校验，Zod v4 语义含 enum 值域
 * （invalid_value）、嵌套对象递归、数组元素类型（实测 zod@4.4.3）。旧 Java 端
 * safeParseSchema 只做<b>顶层</b> required/类型/未知键扁平校验，对含 enum/嵌套/数组
 * schema 的工具（TodoWrite/ExitPlanMode/NotebookEdit/TaskGet 等 17 个）产生校验漏报：
 * 非法 enum 值 / 嵌套类型错 / 数组元素类型错在 CC 会被 safeParse 拒绝（hook if 门禁
 * 过滤 + 工具执行前置拒绝），Java 放行。本测试验证补全后的递归校验闭环。
 *
 * <p><b>关键不变式</b>:
 * <ul>
 *   <li>enum 值不在 schema enum 数组 → {@code invalid_value}（实测 zod@4.4.3 code，
 *       非 v3 的 invalid_enum_value），消息逐字对齐 zod：单值
 *       {@code "Invalid input: expected \"x\""}、多值
 *       {@code "Invalid option: expected one of \"a\"|\"b\""}。</li>
 *   <li>嵌套 object 递归 required/类型/enum，path 完整（如 {@code config.mode}）。</li>
 *   <li>数组元素递归 items schema，path 含下标（如 {@code items[1]} / {@code todos[0].status}）。</li>
 *   <li><b>嵌套/数组元素对象未知键恒 strip</b>（CC z.object 运行时 strip；zod v4
 *       toJSONSchema 对 z.object 也广告 additionalProperties=false，若按广告拒绝会过度
 *       拒绝 —— TodoWriteToolOutputSchemaTest.itemLevelUnknownKeyStrippedNotRejected 护栏）。</li>
 *   <li>顶层未知键行为不变（UNSPECIFIED + additionalProperties=false → 拒绝；STRIP/
 *       PASSTHROUGH → 放行）。</li>
 * </ul>
 */
class ToolInputValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolInputValidator validator = new ToolInputValidator();

    private Tool toolWithSchema(String name, JsonNode schema) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test tool " + name; }
            @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "ok"); }
            @Override public JsonNode inputSchema() { return schema; }
        };
    }

    // ── enum 值域（实测 zod@4.4.3：invalid_value + 逐字消息）──

    @Test
    @DisplayName("MT-06: enum 值不在集合 → invalid_value，多值消息逐字 zod")
    void enumValueMismatch() {
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("mode", JSON.createObjectNode().put("type", "string")
            .set("enum", JSON.createArrayNode().add("auto").add("manual")));
        Tool tool = toolWithSchema("Config", JSON.createObjectNode().set("properties", props));
        JsonNode input = JSON.createObjectNode().put("mode", "other");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_value");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("mode"));
        // 逐字对齐 zod@4.4.3 实测: {code:'invalid_value', message:'Invalid option:
        //   expected one of "auto"|"manual"'}
        assertThat(result.issues().get(0).message())
            .isEqualTo("Invalid option: expected one of \"auto\"|\"manual\"");
    }

    @Test
    @DisplayName("MT-06: 单值 enum 不匹配 → invalid_value 消息 \"Invalid input: expected \\\"x\\\"\"")
    void enumSingleOptionMismatch() {
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("tool", JSON.createObjectNode().put("type", "string")
            .set("enum", JSON.createArrayNode().add("Bash")));
        Tool tool = toolWithSchema("Gate", JSON.createObjectNode().set("properties", props));
        JsonNode input = JSON.createObjectNode().put("tool", "Other");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_value");
        assertThat(result.issues().get(0).message())
            .isEqualTo("Invalid input: expected \"Bash\"");
    }

    @Test
    @DisplayName("MT-06: enum 命中集合 → ok=true（不放行非法值的同时也不误伤合法值）")
    void enumValueMatchPasses() {
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("status", JSON.createObjectNode().put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("in_progress").add("completed")));
        Tool tool = toolWithSchema("Todo", JSON.createObjectNode().set("properties", props));
        JsonNode input = JSON.createObjectNode().put("status", "in_progress");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    // ── 嵌套 object 递归 ──

    @Test
    @DisplayName("MT-06: 嵌套 object 字段类型错 → invalid_type，path 完整 config.mode")
    void nestedObjectTypeMismatch() {
        Tool tool = toolWithSchema("Nested", nestedConfigSchema());
        // 提供合法 path（required），隔离出唯一的嵌套类型错（mode=123）
        JsonNode input = JSON.createObjectNode()
            .set("config", JSON.createObjectNode().put("mode", 123).put("path", "/tmp/x"));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("config", "mode"));
        assertThat(result.issues().get(0).message())
            .isEqualTo("The parameter `config.mode` type is expected as `string` but provided as `number`");
    }

    @Test
    @DisplayName("MT-06: 嵌套 object 缺 required → missing_required，path 完整 config.path")
    void nestedObjectMissingRequired() {
        Tool tool = toolWithSchema("Nested", nestedConfigSchema());
        JsonNode input = JSON.createObjectNode()
            .set("config", JSON.createObjectNode());

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("missing_required");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("config", "path"));
        assertThat(result.issues().get(0).message())
            .isEqualTo("The required parameter `config.path` is missing");
    }

    @Test
    @DisplayName("MT-06: 嵌套 object 合法输入 → ok=true（递归不误伤合法嵌套）")
    void nestedObjectValidPasses() {
        Tool tool = toolWithSchema("Nested", nestedConfigSchema());
        JsonNode input = JSON.createObjectNode()
            .set("config", JSON.createObjectNode().put("mode", "auto").put("path", "/tmp/x"));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("MT-06: 嵌套 object 声明 object 但输入为标量 → invalid_type（不进入递归）")
    void nestedObjectWrongType() {
        Tool tool = toolWithSchema("Nested", nestedConfigSchema());
        JsonNode input = JSON.createObjectNode().put("config", "not-an-object");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("config"));
    }

    // ── 数组元素递归 ──

    @Test
    @DisplayName("MT-06: 数组元素类型错 → invalid_type，path 含下标 items[1]")
    void arrayElementTypeMismatch() {
        Tool tool = toolWithSchema("Array", arraySchema());
        JsonNode input = JSON.createObjectNode()
            .set("items", JSON.createArrayNode().add("a").add(42));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("items", 1));
        assertThat(result.issues().get(0).message())
            .isEqualTo("The parameter `items[1]` type is expected as `string` but provided as `number`");
    }

    @Test
    @DisplayName("MT-06: 数组元素对象嵌套 enum 错 → invalid_value，path todos[0].status")
    void arrayElementNestedEnumMismatch() {
        Tool tool = toolWithSchema("TodoList", todoListSchema());
        JsonNode input = JSON.createObjectNode()
            .set("todos", JSON.createArrayNode().add(
                JSON.createObjectNode().put("content", "Run tests").put("status", "bogus")));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_value");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("todos", 0, "status"));
    }

    @Test
    @DisplayName("MT-06: 数组元素对象缺 required → missing_required，path todos[0].content")
    void arrayElementNestedMissingRequired() {
        Tool tool = toolWithSchema("TodoList", todoListSchema());
        JsonNode input = JSON.createObjectNode()
            .set("todos", JSON.createArrayNode().add(
                JSON.createObjectNode().put("status", "pending")));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("missing_required");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("todos", 0, "content"));
        assertThat(result.issues().get(0).message())
            .isEqualTo("The required parameter `todos[0].content` is missing");
    }

    @Test
    @DisplayName("MT-06: 数组声明 array 但输入为标量 → invalid_type（不进入递归）")
    void arrayWrongType() {
        Tool tool = toolWithSchema("Array", arraySchema());
        JsonNode input = JSON.createObjectNode().put("items", "not-an-array");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("items"));
    }

    @Test
    @DisplayName("MT-06: 数组元素合法 → ok=true")
    void arrayElementValidPasses() {
        Tool tool = toolWithSchema("TodoList", todoListSchema());
        JsonNode input = JSON.createObjectNode()
            .set("todos", JSON.createArrayNode().add(
                JSON.createObjectNode().put("content", "Run tests").put("status", "pending")));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    // ── 嵌套未知键恒 strip（防过度递归回归护栏）──

    @Test
    @DisplayName("MT-06: 嵌套/数组元素对象未知键恒 strip（CC z.object 运行时；即使嵌套 additionalProperties=false）")
    void nestedUnknownKeyStripped() {
        // 嵌套 config 显式 additionalProperties=false（zod v4 toJSONSchema 对 z.object 也输出 false），
        // 但 CC 运行时 z.object strip → Java 递归必须放行嵌套未知键（回归护栏）
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode config = JSON.createObjectNode();
        config.put("type", "object");
        config.set("properties", JSON.createObjectNode().set(
            "mode", JSON.createObjectNode().put("type", "string")));
        config.put("additionalProperties", false);
        schema.set("properties", JSON.createObjectNode().set("config", config));
        Tool tool = toolWithSchema("Nested", schema);
        JsonNode input = JSON.createObjectNode()
            .set("config", JSON.createObjectNode().put("mode", "auto").put("extra", 1));

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).as("嵌套 object 未知键必须 strip（CC z.object），不得按嵌套广告拒绝")
            .isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("MT-06: 顶层未知键仍按工具策略拒绝（行为不变）")
    void topLevelUnknownKeyStillRejected() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("properties", JSON.createObjectNode().set(
            "mode", JSON.createObjectNode().put("type", "string")));
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("Strict", schema);
        JsonNode input = JSON.createObjectNode().put("mode", "auto").put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("extra"));
    }

    @Test
    @DisplayName("MT-06: formatZodValidationError 对 invalid_value issue 输出 path: code - message 句")
    void enumIssueFlowsToFormatter() {
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("status", JSON.createObjectNode().put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("completed")));
        Tool tool = toolWithSchema("Todo", JSON.createObjectNode().set("properties", props));
        JsonNode input = JSON.createObjectNode().put("status", "bogus");

        ToolErrorFormatter.SafeParseResult parsed = validator.safeParseSchema(tool, input);
        assertThat(parsed.ok()).isFalse();

        // invalid_value 不在 CC formatZodValidationError 三句式（missing/unexpected/typeMismatch）
        // 分类内 → 走 default 未分类 toOneLine（"path: code - message"），仍可见可排障
        String formatted = ToolErrorFormatter.formatZodValidationError(tool.name(), parsed.issues());
        assertThat(formatted)
            .isEqualTo("Todo failed due to the following issue:\n"
                + "status: invalid_value - Invalid option: expected one of \"pending\"|\"completed\"");
    }

    // ── schema 构造 helpers ──

    /** {config:{mode:string, path:string}, required:[path]} */
    private static JsonNode nestedConfigSchema() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode config = JSON.createObjectNode();
        config.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode configProps = JSON.createObjectNode();
        configProps.set("mode", JSON.createObjectNode().put("type", "string"));
        configProps.set("path", JSON.createObjectNode().put("type", "string"));
        config.set("properties", configProps);
        config.set("required", JSON.createArrayNode().add("path"));
        schema.set("properties", JSON.createObjectNode().set("config", config));
        return schema;
    }

    /** {items:[string]} */
    private static JsonNode arraySchema() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode items = JSON.createObjectNode();
        items.put("type", "array");
        items.set("items", JSON.createObjectNode().put("type", "string"));
        schema.set("properties", JSON.createObjectNode().set("items", items));
        return schema;
    }

    /** {todos:[{content:string, status:enum[pending,in_progress,completed]}]} */
    private static JsonNode todoListSchema() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode todos = JSON.createObjectNode();
        todos.put("type", "array");
        com.fasterxml.jackson.databind.node.ObjectNode item = JSON.createObjectNode();
        item.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode itemProps = JSON.createObjectNode();
        itemProps.set("content", JSON.createObjectNode().put("type", "string"));
        itemProps.set("status", JSON.createObjectNode().put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("in_progress").add("completed")));
        item.set("properties", itemProps);
        item.set("required", JSON.createArrayNode().add("content"));
        todos.set("items", item);
        schema.set("properties", JSON.createObjectNode().set("todos", todos));
        return schema;
    }
}

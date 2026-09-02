package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.ToolInputValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 C6 · safeParse + Zod 风格 issue 验证 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolOrchestration.ts:97-107} safeParse +
 * {@code toolExecution.ts:670} ZodIssue.
 *
 * <p><b>WHY (意图验证)</b>: schema 验证是输入阶段首道防线. 旧 {@code validateSchema}
 * 折叠为单 issue, LLM 一次只能看到一个错; 新 {@code safeParseSchema} 暴露完整 issue 列表
 * 让 LLM 一次性看到所有错误, 减少多轮往返. 同时保留旧 API 向后兼容.
 *
 * <p><b>关键不变式</b>:
 * <ul>
 *   <li>{@code safeParseSchema(tool, input)} 在 ok 时携带 input value; ok=false 时 issues 非空.</li>
 *   <li>{@code validateSchema(tool, input)} 内部委托 {@code safeParseSchema}, 返回值与旧行为兼容.</li>
 *   <li>缺 required 字段时, 每个缺失字段生成一个 issue (path=[fieldName], code="missing_required").</li>
 *   <li>类型不匹配时, 每个字段生成一个 issue (path=[fieldName], code="invalid_type").</li>
 *   <li>schema {@code additionalProperties=false} 时, 每个未知键生成一个 issue
 *       (path=[键名], code="unrecognized_keys", 消息逐字 CC toolErrors.ts:114).</li>
 *   <li>[IT-5] 工具显式声明 {@link Tool.UnknownKeysPolicy#STRIP} /
 *       {@link Tool.UnknownKeysPolicy#PASSTHROUGH} 时, 即使 schema
 *       {@code additionalProperties=false} 也跳过未知键检查（对齐 CC z.object strip /
 *       .passthrough() 运行时语义, OD-TDV1-3 五处分歧关闭）.</li>
 * </ul>
 */
class R32B15_SafeParseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private ToolInputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolInputValidator();
    }

    private Tool toolWithSchema(String name, JsonNode schema) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test tool " + name; }
            @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "ok"); }
            @Override public JsonNode inputSchema() { return schema; }
        };
    }

    @Test
    @DisplayName("C6: safeParseSchema 输入合法 → ok=true 携带 value")
    void safeParseSchemaValid() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode();
        required.add("path");
        schema.set("required", required);
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode pathField = JSON.createObjectNode();
        pathField.put("type", "string");
        props.set("path", pathField);
        schema.set("properties", props);
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode().put("path", "/etc/hosts");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo(input);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("C6: safeParseSchema 缺 required → 每个缺失字段一个 issue")
    void safeParseSchemaMissingRequired() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode();
        required.add("path");
        required.add("command");
        schema.set("required", required);
        Tool tool = toolWithSchema("Bash", schema);
        JsonNode input = JSON.createObjectNode();

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(2);
        assertThat(result.issues().get(0).code()).isEqualTo("missing_required");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("path"));
        assertThat(result.issues().get(1).path()).isEqualTo(List.of("command"));
    }

    @Test
    @DisplayName("C6: safeParseSchema 类型不匹配 → invalid_type issue")
    void safeParseSchemaTypeMismatch() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode pathField = JSON.createObjectNode();
        pathField.put("type", "string");
        props.set("path", pathField);
        schema.set("properties", props);
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode().put("path", 123);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("path"));
        assertThat(result.issues().get(0).message()).contains("expected as `string`");
    }

    @Test
    @DisplayName("C6: safeParseSchema input=null → invalid_type issue（zod received null 等价）")
    void safeParseSchemaNullInput() {
        Tool tool = toolWithSchema("Read", JSON.createObjectNode());
        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        // 逐字对齐 zod v4 实测 (path=[]): {code:'invalid_type', message:'Invalid input:
        //   expected object, received null', expected:'object', received:'null'}
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).message())
            .isEqualTo("Invalid input: expected object, received null");
    }

    @Test
    @DisplayName("C6: safeParseSchema input 不是 object → invalid_type issue（zod received 等价）")
    void safeParseSchemaNotObject() {
        Tool tool = toolWithSchema("Read", JSON.createObjectNode());
        JsonNode input = JSON.createArrayNode().add("not object");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).message())
            .isEqualTo("Invalid input: expected object, received array");
    }

    @Test
    @DisplayName("C6: validateSchema 委托 safeParseSchema → 旧 API 行为兼容")
    void validateSchemaDelegates() {
        JsonNode schema = JSON.createObjectNode()
            .set("required", JSON.createArrayNode().add("path"));
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode();

        // 旧 API 仍返回 ValidationResult 单 issue
        Tool.ValidationResult result = validator.validateSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("missing_required");
        assertThat(result.message()).contains("The required parameter `path` is missing");
    }

    @Test
    @DisplayName("C6: validateSchema pass → 单 issue 折叠为空 (向后兼容)")
    void validateSchemaPass() {
        JsonNode schema = JSON.createObjectNode();
        Tool tool = toolWithSchema("Read", schema);

        Tool.ValidationResult result = validator.validateSchema(tool, JSON.createObjectNode());

        assertThat(result.ok()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("C6: 同时缺 required + 类型错 → safeParseSchema 暴露多 issue")
    void safeParseSchemaMultipleIssues() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode();
        required.add("name");
        schema.set("required", required);
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode countField = JSON.createObjectNode();
        countField.put("type", "string");
        props.set("count", countField);
        schema.set("properties", props);
        Tool tool = toolWithSchema("Write", schema);
        JsonNode input = JSON.createObjectNode().put("count", 42);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        // 2 issues: name 缺失 + count 类型错
        assertThat(result.issues()).hasSize(2);
        assertThat(result.issues()).extracting(ToolErrorFormatter.ZodIssue::code)
            .containsExactlyInAnyOrder("missing_required", "invalid_type");
    }

    @Test
    @DisplayName("C6: safeParseSchema tool=null → SCHEMA_INVALID issue")
    void safeParseSchemaNullTool() {
        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(null, JSON.createObjectNode());

        assertThat(result.ok()).isFalse();
        assertThat(result.issues().get(0).code()).isEqualTo("SCHEMA_INVALID");
        assertThat(result.issues().get(0).message()).contains("Tool is null");
    }

    @Test
    @DisplayName("C6: safeParseSchema 空 schema → 全部 pass (无约束)")
    void safeParseSchemaNoSchema() {
        Tool tool = toolWithSchema("Read", null);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, JSON.createObjectNode());

        assertThat(result.ok()).isTrue();
    }
    @Test
    @DisplayName("C6: additionalProperties=false 时未知键 → unrecognized_keys issue（CC z.strictObject 等价）")
    void safeParseSchemaUnknownKeyRejected() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode pathField = JSON.createObjectNode();
        pathField.put("type", "string");
        props.set("path", pathField);
        schema.set("properties", props);
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode()
            .put("path", "/etc/hosts")
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("extra"));
        // 逐字对齐 CC toolErrors.ts:114
        assertThat(result.issues().get(0).message())
            .isEqualTo("An unexpected parameter `extra` was provided");
    }

    @Test
    @DisplayName("C6: additionalProperties 缺省/true → 未知键放行（CC z.object strip 等价）")
    void safeParseSchemaLenientDefault() {
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("path", JSON.createObjectNode().put("type", "string"));
        // 缺省: 不设 additionalProperties
        com.fasterxml.jackson.databind.node.ObjectNode schemaDefault = JSON.createObjectNode();
        schemaDefault.set("properties", props);
        // 显式 true: 同样宽松
        com.fasterxml.jackson.databind.node.ObjectNode schemaTrue = JSON.createObjectNode();
        schemaTrue.set("properties", props.deepCopy());
        schemaTrue.put("additionalProperties", true);
        JsonNode input = JSON.createObjectNode()
            .put("path", "/etc/hosts")
            .put("extra", 1);

        for (Tool tool : new Tool[]{
                toolWithSchema("Read", schemaDefault),
                toolWithSchema("Read", schemaTrue)}) {
            ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);
            assertThat(result.ok())
                .as("additionalProperties 缺省/true 时未知键必须放行（CC z.object strip）")
                .isTrue();
            assertThat(result.issues()).isEmpty();
        }
    }

    @Test
    @DisplayName("C6: 多个未知键 → 每个键一个 unrecognized_keys issue（CC flatMap(err.keys) 一对一）")
    void safeParseSchemaMultipleUnknownKeys() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("properties", JSON.createObjectNode().set(
            "path", JSON.createObjectNode().put("type", "string")));
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode()
            .put("path", "/etc/hosts")
            .put("extra1", 1)
            .put("extra2", "x");

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(2);
        assertThat(result.issues()).extracting(ToolErrorFormatter.ZodIssue::code)
            .containsExactly("unrecognized_keys", "unrecognized_keys");
        assertThat(result.issues()).extracting(ToolErrorFormatter.ZodIssue::path)
            .containsExactly(List.of("extra1"), List.of("extra2"));
    }

    @Test
    @DisplayName("C6: 值为 null 的未知键同样拒绝（按键存在性判断）")
    void safeParseSchemaUnknownKeyWithNullValue() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("properties", JSON.createObjectNode().set(
            "path", JSON.createObjectNode().put("type", "string")));
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode()
            .put("path", "/etc/hosts")
            .set("extra", JSON.nullNode());

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("extra"));
    }

    @Test
    @DisplayName("C6: 缺 required + 未知键并存 → 两类 issue 同时暴露")
    void safeParseSchemaRequiredPlusUnknown() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("required", JSON.createArrayNode().add("path"));
        schema.set("properties", JSON.createObjectNode().set(
            "path", JSON.createObjectNode().put("type", "string")));
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode().put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(2);
        assertThat(result.issues()).extracting(ToolErrorFormatter.ZodIssue::code)
            .containsExactlyInAnyOrder("missing_required", "unrecognized_keys");
    }

    @Test
    @DisplayName("IT-4: required 字段值为 null → invalid_type received=null（zod: null→typeMismatch 句）")
    void safeParseSchemaNullValueOnRequiredField() {
        // zod v4 实测: {todos: null} 对 z.object({todos: z.array()}) →
        //   {code:'invalid_type', message:'Invalid input: expected array, received null'}
        // 即 required 检查只判缺失, null 值走 type 检查 → typeMismatch 句而非 missing 句
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("required", JSON.createArrayNode().add("path"));
        schema.set("properties", JSON.createObjectNode().set(
            "path", JSON.createObjectNode().put("type", "string")));
        Tool tool = toolWithSchema("Read", schema);
        JsonNode input = JSON.createObjectNode().set("path", JSON.nullNode());

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("invalid_type");
        assertThat(result.issues().get(0).expected()).isEqualTo("string");
        assertThat(result.issues().get(0).received()).isEqualTo("null");
        assertThat(result.issues().get(0).message())
            .isEqualTo("The parameter `path` type is expected as `string` but provided as `null`");
    }

    @Test
    @DisplayName("IT-4: 行为等价抽查 — safeParseSchema issues → formatZodValidationError 逐字 golden(4)")
    void safeParseThenFormatZodValidationGolden4() {
        // 计划 verifyStrategy 抽查: TodoWrite 输入缺 todos + 顶层未知键 extraKey,
        // 经真实 validator → formatter 输出与 /tmp/zodcheck/golden.js 实测 golden(4) 逐字一致
        // （missing 句在 unexpected 句前, 与 issue 生成顺序无关）
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("required", JSON.createArrayNode().add("todos"));
        schema.set("properties", JSON.createObjectNode().set(
            "todos", JSON.createObjectNode().put("type", "array")));
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("TodoWrite", schema);
        JsonNode input = JSON.createObjectNode().put("extraKey", 1);

        ToolErrorFormatter.SafeParseResult parsed = validator.safeParseSchema(tool, input);
        assertThat(parsed.ok()).isFalse();

        String formatted = ToolErrorFormatter.formatZodValidationError(tool.name(), parsed.issues());
        assertThat(formatted)
            .isEqualTo("TodoWrite failed due to the following issues:\n"
                + "The required parameter `todos` is missing\n"
                + "An unexpected parameter `extraKey` was provided");
    }


    // ════════════════════════════════════════════════════════════════════════
    // [IT-5] OD-TDV1-3 关闭 · 五处 CC z.object / strictObject().passthrough() 工具
    // 运行时未知键放行（广告 additionalProperties=false 保留, 策略显式声明）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IT-5: SendMessageTool 未知键 → ok=true（CC SendMessageTool.ts:67 z.object strip）")
    void safeParseSchemaSendMessageStrip() {
        com.nexusai.application.agent.tool.impl.SendMessageTool tool =
            new com.nexusai.application.agent.tool.impl.SendMessageTool(null);
        // 广告层仍为 additionalProperties=false（zod v4 toJSONSchema 对 z.object 输出 false）
        assertThat(tool.inputSchema().get("additionalProperties").asBoolean()).isFalse();
        assertThat(tool.unknownKeysPolicy()).isEqualTo(Tool.UnknownKeysPolicy.STRIP);

        JsonNode input = JSON.createObjectNode()
            .put("to", "researcher@my-team")
            .put("message", "hello")
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).as("CC z.object strip: 未知键不报 unrecognized_keys").isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("IT-5: ToolSearchTool 未知键 → ok=true（CC ToolSearchTool.ts:21 z.object strip）")
    void safeParseSchemaToolSearchStrip() {
        com.nexusai.application.agent.tool.impl.ToolSearchTool tool =
            new com.nexusai.application.agent.tool.impl.ToolSearchTool();
        assertThat(tool.inputSchema().get("additionalProperties").asBoolean()).isFalse();
        assertThat(tool.unknownKeysPolicy()).isEqualTo(Tool.UnknownKeysPolicy.STRIP);

        JsonNode input = JSON.createObjectNode()
            .put("query", "read")
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).as("CC z.object strip: 未知键不报 unrecognized_keys").isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("IT-5: SubagentTool 未知键 → ok=true（CC AgentTool.tsx:82 z.object + merge().extend() strip）")
    void safeParseSchemaSubagentStrip() {
        com.nexusai.application.agent.tool.impl.SubagentTool tool =
            new com.nexusai.application.agent.tool.impl.SubagentTool();
        assertThat(tool.inputSchema().get("additionalProperties").asBoolean()).isFalse();
        assertThat(tool.unknownKeysPolicy()).isEqualTo(Tool.UnknownKeysPolicy.STRIP);

        JsonNode input = JSON.createObjectNode()
            .put("description", "refactor module")
            .put("prompt", "Refactor the auth module")
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).as("CC z.object strip: 未知键不报 unrecognized_keys").isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("IT-5: ExitPlanModeTool 未知键 → ok=true（CC ExitPlanModeV2Tool.ts:79-88 strictObject().passthrough()）")
    void safeParseSchemaExitPlanModePassthrough() {
        com.nexusai.application.agent.tool.impl.ExitPlanModeTool tool =
            new com.nexusai.application.agent.tool.impl.ExitPlanModeTool();
        // 广告层 = true（布尔）· zod v4 toJSONSchema 对 strictObject().passthrough() 实测输出是
        // additionalProperties:{}（空对象），但 Spring AI ToolCallbackConverter 只接受布尔 →
        // RES-04 全量 @SpringBootTest 发现后改为 true（语义等价：允许任意附加键，SDK 注入
        // plan/planFilePath 键；运行时由 unknownKeysPolicy()=PASSTHROUGH 承担）。见
        // ExitPlanModeTool.java inputSchema() 注释。
        JsonNode ad = tool.inputSchema().get("additionalProperties");
        assertThat(ad).isNotNull();
        assertThat(ad.isBoolean()).as("additionalProperties 为布尔 true（Spring AI ToolCallbackConverter 约束，语义等价 zod {}）").isTrue();
        assertThat(ad.asBoolean()).isTrue();
        assertThat(tool.unknownKeysPolicy()).isEqualTo(Tool.UnknownKeysPolicy.PASSTHROUGH);

        JsonNode input = JSON.createObjectNode()
            .put("apply", false)
            .put("plan", "1. do x")
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).as("CC passthrough: 未知键接受").isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("IT-5: hook StructuredOutput 实例未知键 → ok=true（CC hookHelpers.ts:17 z.object strip）")
    void safeParseSchemaHookStructuredOutputStrip() {
        // ExecAgentHook.createHookStructuredOutputTool 等价物: {ok, reason} schema 的
        // SyntheticOutputTool（广告 additionalProperties=false = CC hookHelpers.ts:58）
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("ok", JSON.createObjectNode().put("type", "boolean"));
        props.set("reason", JSON.createObjectNode().put("type", "string"));
        schema.set("properties", props);
        schema.set("required", JSON.createArrayNode().add("ok"));
        schema.put("additionalProperties", false);
        com.nexusai.application.agent.tool.impl.SyntheticOutputTool tool =
            new com.nexusai.application.agent.tool.impl.SyntheticOutputTool(schema);
        assertThat(tool.unknownKeysPolicy()).isEqualTo(Tool.UnknownKeysPolicy.PASSTHROUGH);

        JsonNode input = JSON.createObjectNode()
            .put("ok", true)
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).as("hook 运行时 z.object strip: 未知键不报 unrecognized_keys").isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("IT-5: 匿名工具（默认 UNSPECIFIED）additionalProperties=false 仍拒绝未知键（护栏）")
    void safeParseSchemaUnspecifiedPolicyStillRejects() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = JSON.createObjectNode();
        schema.set("properties", JSON.createObjectNode().set(
            "path", JSON.createObjectNode().put("type", "string")));
        schema.put("additionalProperties", false);
        Tool tool = toolWithSchema("Read", schema);
        assertThat(tool.unknownKeysPolicy())
            .as("默认策略必须为 UNSPECIFIED（跟随广告层, CC z.strictObject 工具零回归）")
            .isEqualTo(Tool.UnknownKeysPolicy.UNSPECIFIED);
        JsonNode input = JSON.createObjectNode()
            .put("path", "/etc/hosts")
            .put("extra", 1);

        ToolErrorFormatter.SafeParseResult result = validator.safeParseSchema(tool, input);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).message())
            .isEqualTo("An unexpected parameter `extra` was provided");
    }
}
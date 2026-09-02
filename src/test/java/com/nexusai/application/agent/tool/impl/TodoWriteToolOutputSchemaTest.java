package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 outputschema-strict-v1 · TodoWrite v1 outputSchema 严格度契约测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC TodoWriteTool.ts 的 input/output schema 严格度——
 * inputSchema 用 z.strictObject（TodoWriteTool.ts:14，拒绝未知键），outputSchema 用 z.object
 * （TodoWriteTool.ts:20-26），但 CC 对外<b>广告</b>的 schema 经 zodToJsonSchema.ts:20
 * {@code toJSONSchema()} 序列化（zod v4 io='output' 默认），普通 z.object 输出
 * {@code additionalProperties=false}。Java 端用 JSON Schema 的 {@code additionalProperties} 表达：
 * <ul>
 *   <li>{@code inputSchema()} 必须 {@code additionalProperties=false}（对齐 CC :14 strictObject）——
 *       回归护栏，防误删本差异项的对齐成果；</li>
 *   <li>{@code outputSchema()} 必须 {@code additionalProperties=false}（对齐 CC 广告契约：
 *       TodoWriteTool.ts:20-26 z.object + zodToJsonSchema.ts:20 输出 additionalProperties=false）
 *       ——拒绝 true/缺失。消费方按此契约拒绝未知键，与 CC 广告 schema 一致。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring 上下文），直接 {@code new TodoWriteTool()}，参照
 * TaskGetListOutputSchemaContractTest 模式。
 */
class TodoWriteToolOutputSchemaTest {

    @Test
    @DisplayName("outputSchema: type=object + 三字段 oldTodos/newTodos/verificationNudgeNeeded")
    void outputSchema_typeAndProperties() {
        // WHY: CC TodoWriteTool.ts:21-25 输出契约 = {oldTodos, newTodos, verificationNudgeNeeded}。
        // 若 Java 缺任一字段，结构化输出消费方拿不到完整数据。
        JsonNode schema = new TodoWriteTool().outputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.has("properties")).isTrue();
        JsonNode props = schema.get("properties");
        assertThat(props.has("oldTodos")).as("oldTodos").isTrue();
        assertThat(props.has("newTodos")).as("newTodos").isTrue();
        assertThat(props.has("verificationNudgeNeeded")).as("verificationNudgeNeeded").isTrue();
    }

    @Test
    @DisplayName("outputSchema: verificationNudgeNeeded 为 boolean")
    void outputSchema_verificationNudgeNeededIsBoolean() {
        // WHY: CC TodoWriteTool.ts:24 verificationNudgeNeeded: z.boolean().optional()。
        // 类型必须为 boolean，否则消费方无法按 boolean 解析。
        JsonNode schema = new TodoWriteTool().outputSchema();
        JsonNode nudge = schema.get("properties").get("verificationNudgeNeeded");
        assertThat(nudge).isNotNull();
        assertThat(nudge.get("type").asText())
            .as("verificationNudgeNeeded 必须为 boolean（CC TodoWriteTool.ts:24 z.boolean()）")
            .isEqualTo("boolean");
    }

    @Test
    @DisplayName("outputSchema: additionalProperties=false（对齐 CC 广告 schema，zodToJsonSchema 输出）")
    void outputSchema_additionalPropertiesFalse() {
        // WHY: CC TodoWriteTool.ts:20-26 outputSchema 用 z.object；CC 广告 schema 经
        // zodToJsonSchema.ts:20 toJSONSchema() 序列化普通 z.object 输出 additionalProperties=false。
        // Java outputSchema 必须显式声明 additionalProperties=false，使对外广告契约与 CC 一致
        // （消费方按此契约拒绝未知键），不得退回旧"运行时 strip 容忍额外键"的非严格语义。
        JsonNode schema = new TodoWriteTool().outputSchema();
        JsonNode ap = schema.get("additionalProperties");
        assertThat(ap)
            .as("outputSchema 必须显式声明 additionalProperties（对齐 CC 广告 schema，zodToJsonSchema.ts:20 输出 false）")
            .isNotNull();
        assertThat(ap.asBoolean())
            .as("outputSchema 的 additionalProperties 必须为 false（对齐 CC 广告契约：TodoWriteTool.ts:20-26 z.object + zodToJsonSchema.ts:20），拒绝 true/缺失")
            .isFalse();
    }

    @Test
    @DisplayName("回归护栏: inputSchema additionalProperties 必须为 false（对齐 CC z.strictObject 严格）")
    void inputSchema_additionalPropertiesStrict() {
        // WHY: CC TodoWriteTool.ts:14 inputSchema 用 z.strictObject（拒绝未知键），是输入校验的
        // 严格契约。本差异项严禁触碰 inputSchema 严格度——此测试护栏确保 future 改造不会误删。
        JsonNode schema = new TodoWriteTool().inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean())
            .as("inputSchema 的 additionalProperties 必须为 false（对齐 CC TodoWriteTool.ts:14 z.strictObject）")
            .isFalse();
    }

    @Test
    @DisplayName("strict() = true 显式覆写（CC TodoWriteTool.ts:35）")
    void strict_explicitlyTrue() {
        // WHY: CC TodoWriteTool.ts:35 strict: true（与 z.strictObject inputSchema :14 一致，
        //      模型不可注入额外字段）；Java Tool.java strict() 默认 false（Tool.java:536-538）
        //      → 若缺 override，ToolRegistry.toOpenAiToolsArray（ToolRegistry.java:481
        //      flag && tool.strict()）不会标记 fn.strict=true，模型看到的工具契约与 CC 漂移。
        assertThat(new TodoWriteTool().strict()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // S07 · CC 广告 schema / 文案逐字对齐（A7/A8/A6/A21 + D-TDV1-5 删除等价性）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("inputSchema: item 级 minLength=1 + additionalProperties=false（对齐 CC types.ts:8-14）")
    void inputSchema_itemLevelConstraints() {
        // WHY: CC TodoItemSchema（utils/todo/types.ts:8-14）content/activeForm 为
        // z.string().min(1, ...)，zod v4 toJSONSchema 广告输出 minLength:1；z.object 广告输出
        // additionalProperties=false。广告层与运行时 parseTodos 拒绝语义一致化（A7）。
        JsonNode schema = new TodoWriteTool().inputSchema();
        JsonNode items = schema.get("properties").get("todos").get("items");
        assertThat(items).isNotNull();
        assertThat(items.get("type").asText()).isEqualTo("object");
        assertThat(items.has("additionalProperties"))
            .as("item 必须显式声明 additionalProperties（CC types.ts:8 z.object 广告输出 false）")
            .isTrue();
        assertThat(items.get("additionalProperties").asBoolean())
            .as("item 的 additionalProperties 必须为 false（CC types.ts:8 z.object 广告契约）")
            .isFalse();
        JsonNode itemProps = items.get("properties");
        assertThat(itemProps.get("content").get("minLength").asInt())
            .as("content minLength=1（CC types.ts:10 z.string().min(1, 'Content cannot be empty')）")
            .isEqualTo(1);
        assertThat(itemProps.get("activeForm").get("minLength").asInt())
            .as("activeForm minLength=1（CC types.ts:12 z.string().min(1, 'Active form cannot be empty')）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("outputSchema: required 声明 oldTodos/newTodos 必填、verificationNudgeNeeded 可选（CC :20-27）")
    void outputSchema_requiredDeclaresOldNewTodos() {
        // WHY: CC outputSchema z.object({oldTodos, newTodos, verificationNudgeNeeded: optional()})
        // 广告 JSON Schema 的 required = [oldTodos, newTodos]（A8，TodoWriteTool.ts:22-24）。
        JsonNode schema = new TodoWriteTool().outputSchema();
        JsonNode required = schema.get("required");
        assertThat(required)
            .as("outputSchema 必须声明 required（CC TodoWriteTool.ts:20-27 z.object 广告输出）")
            .isNotNull();
        List<String> requiredFields = new ArrayList<>();
        required.forEach(n -> requiredFields.add(n.asText()));
        assertThat(requiredFields)
            .as("required 必须含 oldTodos/newTodos（CC TodoWriteTool.ts:22-23）")
            .contains("oldTodos", "newTodos");
        assertThat(requiredFields)
            .as("verificationNudgeNeeded 可选，不得在 required（CC TodoWriteTool.ts:24 z.boolean().optional()）")
            .doesNotContain("verificationNudgeNeeded");
    }

    @Test
    @DisplayName("outputSchema: oldTodos/newTodos item 级约束对齐 CC types.ts:8-14（U-2）")
    void outputSchema_itemLevelConstraints() {
        // WHY: CC outputSchema（TodoWriteTool.ts:22-23）的 oldTodos/newTodos 复用 TodoListSchema()
        //   （utils/todo/types.ts:17 z.array(TodoItemSchema())），TodoItemSchema（:8-14）
        //   content/activeForm 为 z.string().min(1) → zod v4 toJSONSchema（zodToJsonSchema.ts:20）
        //   广告 minLength:1；z.object 广告 required=[content,status,activeForm] + additionalProperties=false。
        //   Java outputSchema 旧实现（createTodoListSchemaProperty）items 仅 type+enum——U-2 补齐为
        //   与 inputSchema item 级一致，否则消费方看到的输出契约比 CC 宽松（缺 minLength/required/拒未知键）。
        JsonNode schema = new TodoWriteTool().outputSchema();
        for (String field : new String[]{"oldTodos", "newTodos"}) {
            JsonNode items = schema.get("properties").get(field).get("items");
            assertThat(items)
                .as("outputSchema.%s.items 必须存在", field)
                .isNotNull();
            assertThat(items.get("type").asText()).isEqualTo("object");
            assertThat(items.has("additionalProperties"))
                .as("outputSchema.%s item 必须声明 additionalProperties（CC types.ts:8 z.object 广告）", field)
                .isTrue();
            assertThat(items.get("additionalProperties").asBoolean())
                .as("outputSchema.%s item additionalProperties 必须为 false（CC types.ts:8）", field)
                .isFalse();
            JsonNode itemProps = items.get("properties");
            assertThat(itemProps.get("content").get("minLength").asInt())
                .as("outputSchema.%s content minLength=1（CC types.ts:10 z.string().min(1)）", field)
                .isEqualTo(1);
            assertThat(itemProps.get("activeForm").get("minLength").asInt())
                .as("outputSchema.%s activeForm minLength=1（CC types.ts:12 z.string().min(1)）", field)
                .isEqualTo(1);
            List<String> required = new ArrayList<>();
            items.get("required").forEach(n -> required.add(n.asText()));
            assertThat(required)
                .as("outputSchema.%s item required=[content,status,activeForm]（CC types.ts:8-14 z.object）", field)
                .containsExactly("content", "status", "activeForm");
        }
    }

    @Test
    @DisplayName("PROMPT 尾部 \\n（对齐 CC prompt.ts:180-181）")
    void prompt_endsWithNewline() {
        // WHY: CC PROMPT 模板串以换行结尾（prompt.ts:180 内容行 + :181 模板闭合）；
        // Java text block 默认不带尾部换行（JLS 语义），必须显式补（A6/A25）。
        String prompt = new TodoWriteTool().prompt();
        assertThat(prompt.endsWith("\n"))
            .as("prompt() 输出必须以 \\n 结尾（CC prompt.ts:180-181），其余 178 行逐字不变")
            .isTrue();
    }

    @Test
    @DisplayName("nudge 文案 subagent_type 字节不变（对齐 CC TodoWriteTool.ts:107 + AgentTool/constants.ts:4）")
    void nudge_subagentTypeByteIdentical() {
        // WHY: A21 不变量——nudge 文案引 AgentToolConstants.VERIFICATION_AGENT_TYPE（='verification'，
        // + 主线程 + allDone + >=3 + 无 verif。
        // DC-1：单参 execute（ctx=null）已改抛 IllegalStateException（CC call 恒有 context），
        // 故改走二参 execute 并传主线程 ctx（agentId==sessionId → isMainThread true，CC :80）。
        System.setProperty("nexusai.feature.verification_agent", "true");
        System.setProperty("nexusai.feature.tengu_hive_evidence", "true");
        try {
            TodoWriteTool tool = new TodoWriteTool();
            // 直接构造 JSON 文本（避免依赖 package-private 辅助）
            JsonNode inputNode = jsonInput(new String[][]{
                {"Fix auth bug", "completed", "Fixing auth bug"},
                {"Add tests", "completed", "Adding tests"},
                {"Update docs", "completed", "Updating docs"},
            });
            // [session-id-short] 主线程：agentId=null（对齐 CC !context.agentId），sessionId=short
            ToolUseContext ctx = ToolUseContext.of(null, "sess-" + UUID.randomUUID().toString().substring(0, 8));
            // 二参 execute 返回 successWithStructuredOutput → data 为 Map{summary, structured_output}（A20/OD-TDV1-5）
            ToolResult<Map<String, Object>> result = tool.execute(new ToolUseBlock("t1", "TodoWrite", inputNode), ctx);
            String summary = (String) result.data().get("summary");
            assertThat(summary)
                .as("nudge 文案 subagent_type 字节不变（CC TodoWriteTool.ts:107，常量值='verification'）")
                .contains("subagent_type=\"verification\"");
        } finally {
            System.clearProperty("nexusai.feature.verification_agent");
            System.clearProperty("nexusai.feature.tengu_hive_evidence");
        }
    }

    @Test
    @DisplayName("D-TDV1-5 等价性: 顶层缺 todos 经 ToolInputValidator 拒绝（required 路径）")
    void topLevelMissingTodosRejectedByValidator() {
        // WHY: validateInput override 删除后，顶层 required 校验由 ToolInputValidator.safeParseSchema
        // 承担（ToolInputValidator.java:162-174，基于 inputSchema required=[\"todos\"]）。
        ToolInputValidator validator = new ToolInputValidator();
        var result = validator.safeParseSchema(new TodoWriteTool(), new ObjectNode(JsonNodeFactory.instance));
        assertThat(result.ok())
            .as("缺 todos 的输入必须被 ToolInputValidator 拒绝（等价于旧 validateInput MISSING_FIELD）")
            .isFalse();
    }

    @Test
    @DisplayName("D-TDV1-5 等价性: 顶层 todos 非数组经 ToolInputValidator 拒绝（type 路径）")
    void topLevelNonArrayTodosRejectedByValidator() {
        // WHY: validateInput override 删除后，顶层类型校验由 ToolInputValidator.safeParseSchema
        // 承担（ToolInputValidator.java:176-201，基于 inputSchema todos type=array）。
        ToolInputValidator validator = new ToolInputValidator();
        ObjectNode input = new ObjectNode(JsonNodeFactory.instance);
        input.put("todos", "not-an-array");
        var result = validator.safeParseSchema(new TodoWriteTool(), input);
        assertThat(result.ok())
            .as("todos 非数组的输入必须被 ToolInputValidator 拒绝（等价于旧 validateInput INVALID_TYPE）")
            .isFalse();
    }

    @Test
    @DisplayName("IT-3 OD-TDV1-3: 顶层未知键经 ToolInputValidator 拒绝（unrecognized_keys, CC z.strictObject 等价）")
    void topLevelUnknownKeyRejectedByValidator() {
        // WHY: inputSchema 顶层 additionalProperties=false（TodoWriteTool.java:483,
        // 对齐 CC TodoWriteTool.ts:14 z.strictObject）→ safeParseSchema 第 3 步
        // 拒绝不在 properties 的键（IT-3 新增; CC toolErrors.ts:114 逐字消息）。
        ToolInputValidator validator = new ToolInputValidator();
        ObjectNode input = (ObjectNode) jsonInput(
            new String[][]{{"Run tests", "pending", "Running tests"}});
        input.put("extra", 1);

        var result = validator.safeParseSchema(new TodoWriteTool(), input);

        assertThat(result.ok())
            .as("顶层未知键 extra 必须被拒绝（CC z.strictObject unrecognized_keys）")
            .isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).code()).isEqualTo("unrecognized_keys");
        assertThat(result.issues().get(0).path()).isEqualTo(List.of("extra"));
        // 逐字对齐 CC toolErrors.ts:114
        assertThat(result.issues().get(0).message())
            .isEqualTo("An unexpected parameter `extra` was provided");
    }

    @Test
    @DisplayName("IT-3 OD-TDV1-3: item 级未知键放行不递归（CC z.object strip + parseTodos:917 等价）")
    void itemLevelUnknownKeyStrippedNotRejected() {
        // WHY: item 广告 additionalProperties=false（TodoWriteTool.java:477, zod v4
        // toJSONSchema 输出）但 CC 运行时 z.object 对 item 未知键静默 strip、Java
        // parseTodos:917 同 —— 验证器不得递归进数组 item（防过度递归回归护栏）。
        ToolInputValidator validator = new ToolInputValidator();
        ObjectNode input = new ObjectNode(JsonNodeFactory.instance);
        ObjectNode item = input.putArray("todos").addObject();
        item.put("content", "Run tests");
        item.put("status", "pending");
        item.put("activeForm", "Running tests");
        item.put("subject", "x");

        var result = validator.safeParseSchema(new TodoWriteTool(), input);

        assertThat(result.ok())
            .as("item 级未知键（subject）必须放行 — CC z.object strip, 不得递归拒绝")
            .isTrue();
        assertThat(result.issues()).isEmpty();
    }

    /** 构造 CC inputSchema 形状：{todos: [{content, status, activeForm}, ...]}。 */
    private static JsonNode jsonInput(String[][] items) {
        ObjectNode input = new ObjectNode(JsonNodeFactory.instance);
        var arr = input.putArray("todos");
        for (String[] item : items) {
            ObjectNode n = arr.addObject();
            n.put("content", item[0]);
            n.put("status", item[1]);
            n.put("activeForm", item[2]);
        }
        return input;
    }
}

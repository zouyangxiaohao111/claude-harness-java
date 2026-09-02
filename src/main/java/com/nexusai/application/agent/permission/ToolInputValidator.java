package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolErrorFormatter;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.util.SemanticNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工具输入验证器 · 对齐 CC {@code toolExecution.ts:615-733} 的 Zod schema 验证 +
 * validateInput 语义验证两阶段。
 *
 * <h2>职责</h2>
 * <p>在权限管线（10 层 PermissionPipeline）之前执行两层输入验证：
 * <ol>
 *   <li><b>Schema 验证</b>（对齐 CC toolExecution.ts:615-680）：
 *       用工具的 {@link Tool#inputSchema()} JSON Schema 对 LLM 生成的 input 做
 *       类型 + required 字段检查（Zod schema 等价）。</li>
 *   <li><b>语义验证</b>（对齐 CC toolExecution.ts:683-733）：
 *       调 {@link Tool#validateInput(JsonNode, ToolUseContext)} 做参数值合法性检查
 *       （如 path 不越狱 workspace、命令不在黑名单）。</li>
 * </ol>
 *
 * <h2>为什么两阶段分开</h2>
 * <ul>
 *   <li>Schema 验证（Zod 等价）：结构/类型错误 → LLM 自纠（不需要上下文）</li>
 *   <li>语义验证（validateInput）：值域错误 → LLM 自纠（如 PATH_ESCAPE → 换合法路径）</li>
 *   <li>两阶段各自 fail 时返回不同的 errorCode，让 LLM 知道错在哪里</li>
 * </ul>
 *
 * <h2>[R32-b15 C6] safeParse + Zod 风格 issue</h2>
 * <p>对齐 CC {@code toolOrchestration.ts:97-107} 的 {@code tool.inputSchema.safeParse()}
 * 返回 {@code {success, data, error}}. 本类新增
 * {@link #safeParseSchema(Tool, JsonNode)} 返回
 * {@link com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult}
 * (typed value + Zod 风格 issue 列表).
 *
 * <p>向后兼容: 旧 {@link #validateSchema(Tool, JsonNode)} (返回 {@link Tool.ValidationResult})
 * 仍保留, 内部委托到 {@link #safeParseSchema}, 折叠为首个 issue 的 toOneLine
 * (原拼接格式逐字保留).
 *
 * <h2>CC 对齐节点</h2>
 * <p>对应 CC {@code toolExecution.ts} 中的第 1-2 阶段（10 阶段管线的前两个）：
 * <pre>
 *   checkPermissionsAndCallTool() {
 *     // 阶段 1: Zod schema 验证 (line 615-680)
 *     const schemaResult = validateToolInput(tool, input);
 *     if (!schemaResult.ok) return schemaResult;
 *
 *     // 阶段 2: validateInput 语义验证 (line 683-733)
 *     const semanticResult = tool.validateInput(input, context);
 *     if (!semanticResult.ok) return semanticResult;
 *
 *     // 阶段 3-10: hasPermissionsToUseToolInner 权限检查
 *     // ...
 *   }
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>本类无状态，所有方法纯函数，线程安全。
 *
 * @see Tool#inputSchema()
 * @see Tool#validateInput(JsonNode, ToolUseContext)
 * @see Tool.ValidationResult
 * @see com.nexusai.application.agent.tool.ToolErrorFormatter
 */
@Component
public class ToolInputValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolInputValidator.class);

    /**
     * Schema 验证：用工具的 inputSchema() JSON Schema 校验 input。
     *
     * <p>对齐 CC {@code toolExecution.ts:615-680} 的 Zod schema 验证。
     * 检查内容：
     * <ol>
     *   <li>input 不能为 null</li>
     *   <li>schema 中 {@code required} 数组列出的字段必须在 input 中存在</li>
     *   <li>schema 中 {@code properties} 定义的字段类型必须匹配
     *       （string / number / boolean / object / array）</li>
     *   <li>schema {@code additionalProperties=false} 且工具未声明宽松策略时,
     *       input 顶层不在 {@code properties} 的键 → unrecognized_keys issue
     *       （CC z.strictObject 等价, toolErrors.ts:114 逐字句子）.
     *       [IT-5] 运行时策略判定：工具显式声明 {@link Tool.UnknownKeysPolicy#STRIP}
     *       或 {@link Tool.UnknownKeysPolicy#PASSTHROUGH} 时跳过本检查
     *       （对齐 CC z.object strip / .passthrough() 运行时语义）；
     *       {@link Tool.UnknownKeysPolicy#STRICT} 强制拒绝（无视广告层）。</li>
     * </ol>
     *
     * <p>如果 tool 的 inputSchema() 返回 null 或空 object，视为 schema 无约束
     * → 直接 pass（对齐 CC：无 schema 的工具不需 Zod 校验）。
     *
     * @param tool  工具实例（用于取 inputSchema）
     * @param input LLM 生成的 JSON 输入参数
     * @return ValidationResult：pass() 表示通过；fail(errorCode, message) 注入 LLM 自纠
     */
    public Tool.ValidationResult validateSchema(Tool tool, JsonNode input) {
        // [R32-b15 C6] delegate to safeParseSchema, then collapse back to ValidationResult
        // (向后兼容: 旧 LlmAgentLoop.applyPermissionFilter 消费者零改动).
        com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult parsed =
            safeParseSchema(tool, input);
        if (parsed.ok()) {
            return Tool.ValidationResult.pass();
        }
        // 折叠为单 issue ValidationResult (保留首个 issue; toOneLine 与原拼接格式逐字一致:
        //   "path: code - message", 空 path → "code - message").
        var issues = parsed.issues();
        String code = issues.isEmpty() ? "SCHEMA_INVALID" : issues.get(0).code();
        String msg = issues.isEmpty() ? "schema validation failed" : issues.get(0).toOneLine();
        return Tool.ValidationResult.fail(code, msg);
    }

    /**
     * [R32-b15 C6] Zod 风格 safeParse · 对齐 CC
     * {@code Open-ClaudeCode/src/services/tools/toolOrchestration.ts:97-107}
     * {@code tool.inputSchema.safeParse(input)} + {@code toolExecution.ts:670} ZodIssue.
     *
     * <p>返回 {@link com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult}:
     * <ul>
     *   <li>{@code ok=true} → {@code value} 字段填入原 input (typed/transformed value
     *       现阶段等价于 raw input; 后续可接入 Zod 风格的 transform/coerce 链)</li>
     *   <li>{@code ok=false} → {@code issues} 列出每个失败字段 (path + code + message)</li>
     * </ul>
     *
     * <p>与 {@link #validateSchema(Tool, JsonNode)} 的区别:
     * <ul>
     *   <li>{@code validateSchema} 返回 {@link Tool.ValidationResult} (单 issue) — 旧消费者</li>
     *   <li>{@code safeParseSchema} 返回 Zod 风格多 issue — 后续 telemetry + LLM 注入</li>
     * </ul>
     *
     * <p>WHY 单实现双返回 (CLAUDE.md 规则 12 · Fail loud):
     * validateSchema 内部委托 safeParseSchema 保证两端逻辑零分歧; 测试同时覆盖
     * 两种返回类型, 一改即两处生效.
     */
    public com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult safeParseSchema(
            Tool tool, JsonNode input) {
        var issues = new java.util.ArrayList<com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue>();
        if (tool == null) {
            issues.add(new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
                List.of(), "SCHEMA_INVALID", "Tool is null", null, null, null));
            return com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult.fail(issues);
        }
        if (input == null || input.isNull()) {
            // 逐字对齐 zod v4 实测: {code:'invalid_type', message:'Invalid input: expected object,
            //   received null', expected:'object', received:'null'} (path=[])
            issues.add(new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
                List.of(), "invalid_type",
                "Invalid input: expected object, received null", "object", "null", null));
            return com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult.fail(issues);
        }
        if (!input.isObject()) {
            String received = input.getNodeType().name().toLowerCase();
            issues.add(new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
                List.of(), "invalid_type",
                "Invalid input: expected object, received " + received, "object", received, null));
            return com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult.fail(issues);
        }
        JsonNode schema = tool.inputSchema();
        if (schema == null || schema.isNull() || schema.isEmpty()) {
            return com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult.pass(input);
        }

        // ── [IMP-PR-02 / MT-06] 递归校验（required / 顶层类型 / enum / 嵌套 / 数组元素）──
        // 对齐 CC toolExecution.ts:615 tool.inputSchema.safeParse(input)（真实 Zod 运行时）。
        // 语义覆盖（实测 zod@4.4.3）：
        //   1. required 字段缺失 → missing_required（嵌套路径完整，如 config.mode）
        //   2. 顶层/嵌套字段类型 → invalid_type（expected/received 结构化字段）
        //   3. enum 值域 → invalid_value（zod v4.4 实测 code，非 v3 的 invalid_enum_value；
        //      消息逐字 "Invalid option: expected one of \"a\"|\"b\"" / 单值 "Invalid input: expected \"x\"")
        //   4. 嵌套对象 properties → 递归（required + 类型 + enum + 更内层递归）
        //   5. 数组元素 → 逐元素递归 items schema（类型 + enum + 嵌套对象校验）
        // 未知键策略：顶层沿用 tool.unknownKeysPolicy()（Java 平台等价 zod strip/passthrough，
        //   IT-5 广告层与运行时分离）；嵌套/数组元素对象始终 strip 未知键（CC z.object
        //   运行时 strip，TodoWriteTool.java:479 item 广告 additionalProperties=false 但
        //   parseTodos 静默 strip —— 防过度递归回归护栏，TodoWriteToolOutputSchemaTest
        //   itemLevelUnknownKeyStrippedNotRejected 守护）。
        Tool.UnknownKeysPolicy unknownKeysPolicy = tool.unknownKeysPolicy();
        boolean relaxUnknownKeys = unknownKeysPolicy == Tool.UnknownKeysPolicy.STRIP
            || unknownKeysPolicy == Tool.UnknownKeysPolicy.PASSTHROUGH;
        validateObjectSchema(schema, input, List.of(), issues, relaxUnknownKeys);

        if (issues.isEmpty()) {
            return com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult.pass(input);
        }
        return com.nexusai.application.agent.tool.ToolErrorFormatter.SafeParseResult.fail(issues);
    }

    /**
     * 递归校验 object 节点（对齐 CC Zod safeParse 的 object 分支）。
     *
     * <p>[IMP-PR-02 / MT-06] 从顶层扁平校验扩展为递归：object 的
     * required / properties（逐字段递归）/ 未知键 三层。
     *
     * <p>未知键语义：<b>仅顶层</b>可按 schema {@code additionalProperties=false} 拒绝
     * （由 {@code relaxUnknownKeys} 决定，值来自 tool.unknownKeysPolicy()）；
     * <b>嵌套 object 恒 strip</b>（validateNode 的 object 分支硬编码传 true）。
     * 原因：zod v4 toJSONSchema 对 z.object 与 z.strictObject 均广告
     * additionalProperties=false（TodoWriteTool.java:479 item 即此），但 CC 运行时
     * z.object 对未知键静默 strip —— 嵌套层若按广告拒绝会过度拒绝
     * （TodoWriteToolOutputSchemaTest.itemLevelUnknownKeyStrippedNotRejected 回归护栏）。
     *
     * @param schema           当前 object 的 JSON Schema 节点
     * @param value            当前 object 的实际输入（调用方保证 isObject()）
     * @param path             从输入根到当前 object 的路径段（如 ["config"]）
     * @param issues           结果 issue 列表（累加）
     * @param relaxUnknownKeys 是否跳过未知键检查（仅顶层由 tool.unknownKeysPolicy() 决定；
     *                         嵌套调用恒 true）
     */
    private void validateObjectSchema(JsonNode schema, JsonNode value, List<Object> path,
            java.util.List<com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue> issues,
            boolean relaxUnknownKeys) {
        // ── 1. required 字段存在性（缺失 → missing_required；null 值由类型检查判 invalid_type）──
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode requiredField : requiredNode) {
                String fieldName = requiredField.asText();
                // 只判字段缺失 (value.has)；null 值由 validateNode 类型检查判 invalid_type
                // received="null"（对齐 zod v4 实测：null → typeMismatch 句而非 missing 句）
                if (!value.has(fieldName)) {
                    List<Object> p = appendPath(path, fieldName);
                    issues.add(new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
                        p, "missing_required",
                        // 逐字对齐 CC toolErrors.ts:107 "The required parameter `p` is missing"
                        "The required parameter `" + ToolErrorFormatter.formatValidationPath(p)
                            + "` is missing",
                        null, null, null));
                }
            }
        }
        // ── 2. properties 逐字段递归校验 ──
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode fieldSchema = entry.getValue();
                if (!value.has(fieldName)) {
                    continue; // 缺失字段由 required 步负责（含非 required 可选字段不校验）
                }
                JsonNode fieldValue = value.get(fieldName);
                validateNode(fieldSchema, fieldValue, appendPath(path, fieldName), issues);
            }
        }
        // ── 3. 未知键检查（对齐 CC z.strictObject 的 unrecognized_keys 语义）──
        // 仅顶层可拒绝（relaxUnknownKeys=false）；嵌套/数组元素恒 strip（CC z.object
        // 运行时，见 class Javadoc [IMP-PR-02]）。
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (!relaxUnknownKeys && additionalProperties != null && additionalProperties.isBoolean()
                && !additionalProperties.asBoolean()) {
            java.util.Set<String> knownKeys = new java.util.HashSet<>();
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(knownKeys::add);
            }
            Iterator<String> inputKeys = value.fieldNames();
            while (inputKeys.hasNext()) {
                String key = inputKeys.next();
                if (!knownKeys.contains(key)) {
                    // keys 字段为裸键名（对齐 CC flatMap(err.keys) 一对一；formatZodValidationError
                    // 用裸键名生成 "An unexpected parameter `k` was provided" 句，与顶层一致）
                    issues.add(new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
                        appendPath(path, key), "unrecognized_keys",
                        "An unexpected parameter `" + key + "` was provided",
                        null, null, List.of(key)));
                }
            }
        }
    }

    /**
     * 递归校验 array 节点（对齐 CC Zod safeParse 的 array 分支）。
     *
     * <p>[IMP-PR-02 / MT-06] 新增：逐元素按 {@code items} schema 递归校验
     * （类型 / enum / 嵌套对象），对齐 zod {@code z.array(z.string())} 对元素类型
     * 的运行时校验。未知键在元素对象内恒 strip（调用方传 relaxUnknownKeys=false，
     * 但 validateObjectSchema 第 3 步要求 additionalProperties=false 才拒绝 —— 目前
     * 无工具在嵌套层声明 strict，TodoWrite item 广告 false 但运行时 strip）。
     *
     * @param schema 当前 array 的 JSON Schema 节点
     * @param value  当前 array 的实际输入（调用方保证 isArray()）
     * @param path   从输入根到当前 array 的路径段（如 ["todos"]）
     * @param issues 结果 issue 列表（累加）
     */
    private void validateArraySchema(JsonNode schema, JsonNode value, List<Object> path,
            java.util.List<com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue> issues) {
        JsonNode items = schema.get("items");
        if (items == null || items.isNull()) {
            return; // 无 items 约束 → 元素不校验（对齐 zod：未知数组构造宽松）
        }
        for (int i = 0; i < value.size(); i++) {
            JsonNode elem = value.get(i);
            if (items.isArray()) {
                // tuple 风格 items（zod tuple，JSON Schema "items":[s1,s2]）→ 按索引取 schema；
                // 越界元素无 schema 约束，跳过
                if (i < items.size()) {
                    validateNode(items.get(i), elem, appendPath(path, i), issues);
                }
            } else {
                validateNode(items, elem, appendPath(path, i), issues);
            }
        }
    }

    /**
     * 递归校验单个节点（对齐 CC Zod safeParse 的标量/复合分支）。
     *
     * <p>[IMP-PR-02 / MT-06] 校验顺序与 zod 一致：enum 值域 → 类型 → 复合结构递归。
     * <ul>
     *   <li>enum 存在且不匹配 → {@code invalid_value}（实测 zod@4.4.3，短路不再报类型错）</li>
     *   <li>{@code object} → 类型不符报 {@code invalid_type}，否则递归 validateObjectSchema
     *       （嵌套恒 strip 未知键，relaxUnknownKeys=true；防 TodoWrite item 过度拒绝回归）</li>
     *   <li>{@code array} → 类型不符报 {@code invalid_type}，否则递归 validateArraySchema</li>
     *   <li>标量 → {@link #checkType(JsonNode, String)} 判类型</li>
     *   <li>无显式 type 但有 properties/items → 按 object/array 推断（JSON Schema 隐式类型）</li>
     * </ul>
     *
     * @param schema 字段/元素的 JSON Schema 节点
     * @param value  实际输入值
     * @param path   从输入根到当前值的完整路径段
     * @param issues 结果 issue 列表（累加）
     */
    private void validateNode(JsonNode schema, JsonNode value, List<Object> path,
            java.util.List<com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue> issues) {
        if (schema == null || schema.isNull()) {
            return; // 无 schema 约束 → 宽松放行
        }
        // ── 1. enum 值域校验（zod z.enum → invalid_value；实测 zod@4.4.3）──
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && enumNode.size() > 0) {
            boolean matched = false;
            for (JsonNode opt : enumNode) {
                if (opt.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                issues.add(new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
                    path, "invalid_value", enumErrorMessage(enumNode), null, null, null));
                return; // zod enum 失败短路，不再报类型错（对齐实测）
            }
        }
        // ── 2. 确定期望类型（无显式 type 时按 properties/items 推断）──
        String type = null;
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            type = typeNode.asText();
        }
        if (type == null && schema.has("properties")) {
            type = "object";
        }
        if (type == null && schema.has("items")) {
            type = "array";
        }
        if (type == null) {
            return; // 无类型约束 → 宽松放行
        }
        // ── 3. 类型检查 + 复合结构递归 ──
        switch (type) {
            case "object" -> {
                if (!value.isObject()) {
                    issues.add(typeIssue(path, "object", value));
                } else {
                    // 嵌套 object 恒 strip 未知键（CC z.object 运行时 strip；顶层策略仅由
                    // safeParseSchema 的 relaxUnknownKeys 承担，见 validateObjectSchema）
                    validateObjectSchema(schema, value, path, issues, true);
                }
            }
            case "array" -> {
                if (!value.isArray()) {
                    issues.add(typeIssue(path, "array", value));
                } else {
                    validateArraySchema(schema, value, path, issues);
                }
            }
            default -> {
                if (!checkType(value, type) && !acceptSemanticCoercion(schema, type, value)) {
                    issues.add(typeIssue(path, type, value));
                }
            }
        }
    }

    /**
     * [IMP-PR-02 / MT-06] 类型不匹配 issue · 与顶层旧实现同一句形
     * （CC toolErrors.ts:122 typeMismatch 句），路径用完整嵌套路径
     * （formatValidationPath，如 "config.mode" / "items[0]"）。
     */
    private com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue typeIssue(
            List<Object> path, String expectedType, JsonNode value) {
        // received 取 Jackson 节点类型小写（string/number/boolean/object/array/null —
        // 与 zod received 对 JSON 类型一致）
        String received = value == null || value.isNull()
            ? "null"
            : value.getNodeType().name().toLowerCase();
        return new com.nexusai.application.agent.tool.ToolErrorFormatter.ZodIssue(
            path, "invalid_type",
            "The parameter `" + ToolErrorFormatter.formatValidationPath(path)
                + "` type is expected as `" + expectedType + "` but provided as `" + received + "`",
            expectedType, received, null);
    }

    /**
     * [G12/OPD-D3-14] semantic 字符串强转放行 · 对齐 CC {@code semanticNumber} /
     * {@code semanticBoolean}（utils/semanticNumber.ts / semanticBoolean.ts，z.preprocess
     * 在 inner schema 校验前把字符串转成 number/boolean）。
     *
     * <p>字段 schema 声明 {@code x-semantic-number=true} 时接受匹配 {@code /^-?\d+(\.\d+)?$/}
     * 的数字字符串（"250"、"3"）；声明 {@code x-semantic-boolean=true} 时接受 {@code "true"}/
     * {@code "false"}。GrepTool.ts:58-88 的 -B/-A/-C/context/head_limit/offset 与
     * -n/-i/multiline 即此语义。复用于 {@link SemanticNumber#parseNumber} 保持单点，
     * 非法字符串（如 "abc"）仍返回 false → 走 typeIssue 拒绝。
     *
     * @param schema       字段的 JSON Schema 节点（含 x-semantic-* 标记）
     * @param expectedType schema 声明的类型（"integer"/"number"/"boolean"）
     * @param value        实际输入值
     * @return true = 字符串值可按 CC semantic 强转语义放行
     */
    private boolean acceptSemanticCoercion(JsonNode schema, String expectedType, JsonNode value) {
        if (value == null || value.isNull() || !value.isTextual()) {
            return false;
        }
        String text = value.asText();
        if (("integer".equals(expectedType) || "number".equals(expectedType))
                && schema.path("x-semantic-number").asBoolean(false)) {
            return SemanticNumber.parseNumber(text) instanceof Number;
        }
        if ("boolean".equals(expectedType) && schema.path("x-semantic-boolean").asBoolean(false)) {
            return "true".equals(text) || "false".equals(text);
        }
        return false;
    }

    /**
     * [IMP-PR-02 / MT-06] enum 值域失败消息 · 逐字对齐 zod@4.4.3 实测：
     * <ul>
     *   <li>单值：{@code Invalid input: expected "x"}</li>
     *   <li>多值：{@code Invalid option: expected one of "a"|"b"|"c"}（字符串值带引号）</li>
     * </ul>
     */
    private static String enumErrorMessage(JsonNode enumNode) {
        java.util.List<String> opts = new java.util.ArrayList<>(enumNode.size());
        for (JsonNode o : enumNode) {
            opts.add(o.isTextual() ? "\"" + o.asText() + "\"" : o.asText());
        }
        if (opts.size() <= 1) {
            String v = opts.isEmpty() ? "" : opts.get(0);
            return "Invalid input: expected " + v;
        }
        StringBuilder sb = new StringBuilder("Invalid option: expected one of ");
        for (int i = 0; i < opts.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(opts.get(i));
        }
        return sb.toString();
    }

    /** [IMP-PR-02 / MT-06] 追加一个路径段（不可变拷贝）。 */
    private static List<Object> appendPath(List<Object> path, Object segment) {
        java.util.List<Object> p = new java.util.ArrayList<>(path.size() + 1);
        p.addAll(path);
        p.add(segment);
        return p;
    }


    /**
     * 语义验证：委托给工具自己的 {@link Tool#validateInput(JsonNode, ToolUseContext)}。
     *
     * <p>对齐 CC {@code toolExecution.ts:683-733} 的 validateInput 阶段。
     * 区别于 schema 验证（参数类型检查），语义验证检查参数值的合法性
     * （如 path 不越狱、命令不在黑名单等）。
     *
     * <p>默认工具返回 {@link Tool.ValidationResult#pass()}，具体工具按需 override。
     *
     * @param tool 工具实例
     * @param input LLM 生成的 JSON 输入参数（已通过 schema 验证）
     * @param ctx   工具调用上下文（sessionId / agentId / mode / working dirs）
     * @return ValidationResult：pass() 表示通过；fail() 注入 LLM 自纠
     */
    public Tool.ValidationResult validateSemantics(Tool tool, JsonNode input, ToolUseContext ctx) {
        if (tool == null) {
            return Tool.ValidationResult.fail("SEMANTIC_INVALID", "Tool is null");
        }
        if (ctx == null) {
            return Tool.ValidationResult.fail("SEMANTIC_INVALID", "ToolUseContext is null");
        }
        try {
            return tool.validateInput(input, ctx);
        } catch (Exception e) {
            // 工具 validateInput 抛异常 → 转 fail（fail loud 原则）
            log.error("ToolInputValidator.validateSemantics: tool={} threw: {}",
                tool.name(), e.toString(), e);
            return Tool.ValidationResult.fail(
                "SEMANTIC_INVALID",
                "Validation error for tool '" + tool.name() + "': " + e.getMessage()
            );
        }
    }

    /**
     * 检查 JsonNode 的值类型是否匹配 JSON Schema type 声明。
     *
     * <p>支持的 type：
     * <ul>
     *   <li>{@code "string"} — JSON string</li>
     *   <li>{@code "number"} / {@code "integer"} — JSON number</li>
     *   <li>{@code "boolean"} — JSON boolean</li>
     *   <li>{@code "object"} — JSON object</li>
     *   <li>{@code "array"} — JSON array</li>
     * </ul>
     *
     * @param value        JSON 值
     * @param expectedType JSON Schema 声明的 type 字符串
     * @return true 表示类型匹配
     */
    private boolean checkType(JsonNode value, String expectedType) {
        // [IT-4 OD-TDV1-6] null 值对任何 expected 返回 false（对齐 zod: null → invalid_type
        //   received 'null'; 原 default true 会放行 null → 缺失漏报）
        if (value == null || value.isNull()) {
            return false;
        }
        return switch (expectedType) {
            case "string"  -> value.isTextual();
            case "number"  -> value.isNumber();
            case "integer" -> value.isInt() || value.isLong() || value.isBigInteger();
            case "boolean" -> value.isBoolean();
            case "object"  -> value.isObject();
            case "array"   -> value.isArray();
            default        -> true; // 未知 type → 宽松放行
        };
    }
}

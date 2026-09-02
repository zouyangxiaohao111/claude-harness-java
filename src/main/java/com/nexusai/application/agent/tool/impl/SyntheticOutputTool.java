package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * R32-b14 StructuredOutput 工具 · 对齐 CC SyntheticOutputTool.ts。
 *
 * <p>该工具只在非交互会话使用，按动态 JSON Schema 校验输入，并把原始对象作为
 * 独立 structured_output 返回；文本 tool_result 仅用于确认调用成功。
 */
@Component
public class SyntheticOutputTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SyntheticOutputTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC 常量 SYNTHETIC_OUTPUT_TOOL_NAME 的真实值。 */
    public static final String NAME = com.nexusai.application.agent.tool.ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME;

    private final JsonNode jsonSchema;

    /** prompt 覆盖文本 · 非 null = 本实例优先返回（hook 专用实例 = CC hookHelpers.ts:60-62）。 */
    private final String promptOverride;
    /** Spring 默认实例接受任意 JSON object；SDK 可用带 schema 构造器创建专用实例。 */
    public SyntheticOutputTool() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        // [IT-5] 广告 additionalProperties 由 true 改为 {} 空对象 · 逐字对齐 zod v4
        // toJSONSchema 对 z.object({}).passthrough() 的实测输出（zod@4.4.3:
        // additionalProperties:{}；语义等价 true 与 {}，均允许任意附加键）。
        schema.set("additionalProperties", JsonNodeFactory.instance.objectNode());
        this.jsonSchema = schema;
        this.promptOverride = null;
    }

    public SyntheticOutputTool(JsonNode jsonSchema) {
        this(jsonSchema, null);
    }

    /**
     * 带 prompt 覆盖的构造器 · 对齐 CC {@code createStructuredOutputTool}（hookHelpers.ts:41-64）
     * 以 {@code {...SyntheticOutputTool, prompt: ...}} 覆盖基类 prompt 的语义。
     *
     * @param jsonSchema     动态 JSON Schema（{@link #inputJSONSchema()} 原样返回）
     * @param promptOverride 非 null → {@link #prompt()} 返回该文本（hook 专用实例用
     *                       hookHelpers.ts:60-62 逐字文本）；null → 基类 CC 文本（SyntheticOutputTool.ts:50-52）
     */
    public SyntheticOutputTool(JsonNode jsonSchema, String promptOverride) {
        String error = validateSchemaDefinition(jsonSchema);
        if (error != null) {
            throw new IllegalArgumentException("Invalid structured output schema: " + error);
        }
        this.jsonSchema = jsonSchema.deepCopy();
        this.promptOverride = promptOverride;
    }

    /**
     * [IT-5] 未知键运行时策略 = PASSTHROUGH · 对齐 CC SyntheticOutputTool.ts:11
     * {@code inputSchema = z.object({}).passthrough()} —— 接受任意未知键。
     *
     * <p>类级策略同时覆盖 hook 专用实例（ExecAgentHook.createHookStructuredOutputTool 以
     * {ok,reason} schema 构造）：hook 运行时 CC 为 hookResponseSchema（hookHelpers.ts:17
     * z.object）strip —— 对 validator 而言 strip 与 passthrough 均"不拒绝"，语义等价；
     * :125 自校验 validateSchema 与主链 StreamingToolExecutor 共用本策略。
     */
    @Override
    public Tool.UnknownKeysPolicy unknownKeysPolicy() {
        return Tool.UnknownKeysPolicy.PASSTHROUGH;
    }

    /**
     * 当前会话是否允许 SyntheticOutput 工具调用 · 对齐 CC
     * {@code isSyntheticOutputToolEnabled(opts: { isNonInteractiveSession }): boolean}
     * （SyntheticOutputTool.ts:22-26，函数体 {@code return opts.isNonInteractiveSession;}
     * 直透传入参数）。
     *
     * <p>[G30⑪ 重构合并] 原 Java 端曾以 {@code isAvailableInNonInteractiveSession} 重命名
     * （M4.3 Pattern #12 命名反直觉修正），CC 真源仅 {@code isSyntheticOutputToolEnabled}
     * 单名字面 —— 归并回 CC 命名，删除 deprecated 包装。
     *
     * @param isNonInteractiveSession 当前 session 是否非交互（CC 调用处 ctx.isNonInteractiveSession()）
     * @return true ⇔ 当前会话非交互（允许 SyntheticOutput tool 调用）；false ⇔ 交互会话（拒绝）
     */
    public static boolean isSyntheticOutputToolEnabled(boolean isNonInteractiveSession) {
        return isNonInteractiveSession;
    }

    /**
     * 工具提示词 · 对齐 CC {@code SyntheticOutputTool.ts:50-52 prompt()}（基类）与
     * {@code hookHelpers.ts:60-62}（hook 专用实例覆盖）。
     *
     * <p>序列化层 {@link com.nexusai.application.agent.tool.ToolRegistry#toOpenAiToolsArray}
     * 取 {@code prompt() ?? description()}（api.ts:171 同语义）——本 override 使
     * hook agent 的 StructuredOutput 工具 description 携带 CC 强制调用句
     * （CCJ-EXEC-16），不再依赖 ExecAgentHook systemPrompt 尾部附加句。
     */
    @Override
    public String prompt() {
        if (promptOverride != null) {
            return promptOverride;
        }
        // CC SyntheticOutputTool.ts:50-52 基类 prompt 逐字文本
        return "Use this tool to return your final response in the requested structured format. "
            + "You MUST call this tool exactly once at the end of your response to provide the structured output.";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Return structured output in the requested format";
    }

    @Override
    public JsonNode inputSchema() {
        return jsonSchema.deepCopy();
    }

    /**
     * 直接 JSON Schema 声明 · CC original: {@code inputJSONSchema}
     * （{@code SyntheticOutputTool.ts:141}，CC {@code buildSyntheticOutputTool} 把 jsonSchema
     * 设为 {@code inputJSONSchema}）。
     *
     * <p>序列化层（CC {@code api.ts:157-160}）优先使用 inputJSONSchema（跳过 zod 转换）。
     * Java 端 schema 已是原样 JSON Schema，直接声明避免 inputSchema() 二次复制。
     *
     * @return 本次构造的 JSON Schema
     */
    @Override
    public JsonNode inputJSONSchema() {
        return jsonSchema;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isOpenWorld(JsonNode input) {
        return false;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        if (ctx != null && !isSyntheticOutputToolEnabled(ctx.isNonInteractiveSession())) {
            log.warn("StructuredOutput 在交互会话被拒绝: toolUseId={}", call.id());
            return ToolResult.error(call.id(),
                "StructuredOutput is only available in non-interactive sessions");
        }

        Tool.ValidationResult validation = new ToolInputValidator().validateSchema(this, call.input());
        if (!validation.ok()) {
            log.warn("StructuredOutput schema 校验失败: toolUseId={} error={}",
                call.id(), validation.message());
            return ToolResult.error(call.id(),
                "Output does not match required schema: " + validation.message());
        }

        Map<String, Object> output = JSON.convertValue(
            call.input(), new TypeReference<Map<String, Object>>() {});
        if (log.isDebugEnabled()) {
            log.debug("StructuredOutput 已捕获: toolUseId={} 字段数={}", call.id(), output.size());
        }
        // [A1·退役 ExtendedToolResult] 结构化输出改走 ToolResult.successWithStructuredOutput 工厂
        // (CC Tool.ts:323 newMessages 通道 + structuredOutput 折入; 旧 ExtendedToolResult.withStructuredOutput 已退役).
        return ToolResult.successWithStructuredOutput(call.id(), "Structured output provided successfully", output);
    }

    private static String validateSchemaDefinition(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return "schema must be a JSON object";
        }
        JsonNode type = schema.get("type");
        if (type != null && !type.isTextual()) {
            return "type must be a string";
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            return "properties must be an object";
        }
        JsonNode required = schema.get("required");
        if (required != null && !required.isArray()) {
            return "required must be an array";
        }
        return null;
    }
}

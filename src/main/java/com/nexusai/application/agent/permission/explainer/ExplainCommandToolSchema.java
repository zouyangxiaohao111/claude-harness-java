package com.nexusai.application.agent.permission.explainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ExplainCommandToolSchema · 对齐 CC {@code EXPLAIN_COMMAND_TOOL} 顶层常量
 * (Open-ClaudeCode/src/utils/permissions/permissionExplainer.ts:46-74)。
 *
 * <p>CC 用强制 {@code tool_choice} + 该工具强制 LLM 返回结构化 tool_use：
 * <pre>{@code
 * const EXPLAIN_COMMAND_TOOL = {
 *   name: 'explain_command',
 *   description: 'Provide an explanation of a shell command',
 *   input_schema: {
 *     type: 'object',
 *     properties: {
 *       explanation: {type:'string', description:'What this command does (1-2 sentences)'},
 *       reasoning:   {type:'string', description:'Why YOU are running this command...'},
 *       risk:        {type:'string', description:'What could go wrong, under 15 words'},
 *       riskLevel:   {type:'string', enum:['LOW','MEDIUM','HIGH'], description:'LOW (safe dev workflows), ...'},
 *     },
 *     required: ['explanation','reasoning','risk','riskLevel'],  // 四字段全列
 *   },
 * }
 * }</pre>
 *
 * <p>Java 端 {@link com.nexusai.infra.llm.LlmProvider} 的 tools 通道是 OpenAI
 * function-calling 格式（{@code {type:'function', function:{name,description,parameters}}}），
 * 故本类把 CC 顶层 {@code name/description/input_schema} 投影到该 wrapper：
 * {@code parameters} = CC {@code input_schema}（type/properties/required 全字段透传）。
 *
 * @see PermissionExplainer
 */
public final class ExplainCommandToolSchema {

    /** 静态工具类 — 不允许实例化。 */
    private ExplainCommandToolSchema() {
        throw new AssertionError("utility class");
    }

    /** CC 工具名 · {@code tool_choice.name} 必须等于此值（permissionExplainer.ts:47）。 */
    public static final String TOOL_NAME = "explain_command";

    /** CC 工具描述（permissionExplainer.ts:48）。 */
    public static final String TOOL_DESCRIPTION = "Provide an explanation of a shell command";

    /** 复用单例 ObjectMapper（线程安全）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 构建 OpenAI function-calling 格式的 tools 数组（含单个 explain_command 工具）。
     *
     * <p>对齐 CC sideQuery 的 {@code tools: [EXPLAIN_COMMAND_TOOL]}（permissionExplainer.ts:182）。
     * required 四字段全列（explanation/reasoning/risk/riskLevel），区别于旧 Java 仅 2 字段。
     *
     * @return 含一个 explain_command 工具的 ArrayNode（OpenAI wrapper 格式）
     */
    public static ArrayNode buildToolsArray() {
        ObjectNode explanation = JSON.createObjectNode();
        explanation.put("type", "string");
        explanation.put("description", "What this command does (1-2 sentences)");

        ObjectNode reasoning = JSON.createObjectNode();
        reasoning.put("type", "string");
        reasoning.put("description",
            "Why YOU are running this command. Start with \"I\" - e.g. \"I need to check the file contents\"");

        ObjectNode risk = JSON.createObjectNode();
        risk.put("type", "string");
        risk.put("description", "What could go wrong, under 15 words");

        ObjectNode riskLevel = JSON.createObjectNode();
        riskLevel.put("type", "string");
        ArrayNode enumValues = JSON.createArrayNode();
        enumValues.add("LOW");
        enumValues.add("MEDIUM");
        enumValues.add("HIGH");
        riskLevel.set("enum", enumValues);
        riskLevel.put("description",
            "LOW (safe dev workflows), MEDIUM (recoverable changes), HIGH (dangerous/irreversible)");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("explanation", explanation);
        properties.set("reasoning", reasoning);
        properties.set("risk", risk);
        properties.set("riskLevel", riskLevel);

        ObjectNode parameters = JSON.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        ArrayNode required = JSON.createArrayNode();
        required.add("explanation");
        required.add("reasoning");
        required.add("risk");
        required.add("riskLevel");
        parameters.set("required", required);

        ObjectNode function = JSON.createObjectNode();
        function.put("name", TOOL_NAME);
        function.put("description", TOOL_DESCRIPTION);
        function.set("parameters", parameters);

        ObjectNode tool = JSON.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);

        ArrayNode tools = JSON.createArrayNode();
        tools.add(tool);
        return tools;
    }
}

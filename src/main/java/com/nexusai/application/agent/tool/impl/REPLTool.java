package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * REPLTool · 对齐 CC {@code Open-ClaudeCode/src/tools/REPLTool/REPLTool.ts}（G31② 删状态机对齐 CC）。
 *
 * <p><b>WHY（G31② / OPD-TR-H2-01）</b>: CC REPLTool 在无 ant-native runtime 时是<b>恒错误 stub</b>
 * （REPLTool.ts:77-89 call 恒返回 {@code {result: 'Error: REPL tool is not available in this build...',
 * tool_calls: 0}}）；REPL 执行引擎由 ant-native runtime 提供，CC 外部构建不含该引擎。
 * Java 端旧实现是<b>自创三态 start/eval/end 教学状态机</b>（sessions Map + action/session_id/language
 * schema），与 CC 契约错位——按拍板删除状态机，对齐 CC {@code {code}} stub。
 *
 * <p><b>保留项</b>（拍板 H2-01「删除对齐 CC stub，保留 name/门控/联动点」）：
 * <ul>
 *   <li>{@code name()='REPL'}（REPL_TOOL_NAME，REPLTool/constants.ts:7）</li>
 *   <li>{@link #isEnabled()} = {@link #isReplModeEnabled()} 门控（REPLTool/constants.ts:23-30
 *       isReplModeEnabled；Java 单配置门，默认 false）</li>
 *   <li>{@link #isTransparentWrapper()}=true（REPLTool.ts:52-54）——透明包装工具，渲染委托给内部工具</li>
 * </ul>
 *
 * <p><b>新增对齐</b>（CC 契约面）：
 * <ul>
 *   <li>inputSchema = {@code z.strictObject({code: string})}（REPLTool.ts:7-15）</li>
 *   <li>description / prompt 逐字（REPLTool.ts:31-44）</li>
 *   <li>isConcurrencySafe=false / isReadOnly=false（REPLTool.ts:46-51）</li>
 *   <li>renderToolUseMessage = code 80 字符预览（REPLTool.ts:60-64）</li>
 *   <li>mapToolResult → content.result（REPLTool.ts:66-75）</li>
 *   <li>execute = CC 恒错误 stub（REPLTool.ts:77-89）</li>
 * </ul>
 */
@Component
public class REPLTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(REPLTool.class);

    /** CC 工具名 · REPLTool/constants.ts:7 REPL_TOOL_NAME='REPL'。 */
    public static final String NAME = ToolNameConstants.REPL_TOOL_NAME;

    /** CC original: maxResultSizeChars=100_000（REPLTool.ts:24）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    /** CC 恒错误 stub 文案 · REPLTool.ts:84-85。 */
    private static final String NOT_AVAILABLE_RESULT =
            "Error: REPL tool is not available in this build. "
                    + "The REPL execution engine requires the ant-native runtime.";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Execute code in the REPL environment with access to all primitive tools";
    }

    /** 搜索提示 · 对齐 CC REPLTool.ts:23 searchHint。 */
    @Override
    public String searchHint() {
        return "repl execute batch code read write edit glob grep bash";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /**
     * 运行时守卫 · 对齐 CC REPLTool/constants.ts:23-30 isReplModeEnabled()（保留门控）。
     *
     * <p>Java 端单配置：env {@code NEXUSAI_REPL_MODE} 或 sysprop {@code nexusai.feature.repl-mode}
     * truthy → 启用；未设置 → false。ToolRegistry 分发前过滤，false 时 LLM 看不到此工具。
     */
    @Override
    public boolean isEnabled() {
        return isReplModeEnabled();
    }

    /**
     * REPL 模式开关 · 对齐 CC REPLTool/constants.ts:23 isReplModeEnabled()。
     *
     * <p>环境变量优先，系统属性次之；未设置 → 默认 false（Web 后端无 REPL 等价介质）。
     */
    static boolean isReplModeEnabled() {
        String env = System.getenv("NEXUSAI_REPL_MODE");
        if (env != null && !env.isBlank()) {
            return isEnvTruthy(env);
        }
        String prop = System.getProperty("nexusai.feature.repl-mode");
        if (prop != null && !prop.isBlank()) {
            return isEnvTruthy(prop);
        }
        return false;
    }

    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower)
                || "yes".equals(lower) || "on".equals(lower);
    }

    /** 不可并发 · 对齐 CC REPLTool.ts:46-48 isConcurrencySafe() → false。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** 非只读 · 对齐 CC REPLTool.ts:49-51 isReadOnly() → false。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    /** 透明包装工具 · 对齐 CC REPLTool.ts:52-54 isTransparentWrapper() → true。 */
    @Override
    public boolean isTransparentWrapper() {
        return true;
    }

    /** 工具提示词 · 对齐 CC REPLTool.ts:34-44 prompt()（逐字）。 */
    @Override
    public String prompt() {
        return """
                Execute code in the REPL — a sandboxed environment with direct access to primitive tools (Read, Write, Edit, Glob, Grep, Bash, NotebookEdit, Agent).

                When REPL mode is active, primitive tools are only accessible through this tool. Use REPL for:
                - Batch operations across many files
                - Complex multi-step file transformations
                - Operations that benefit from programmatic control flow
                - Combining search results with edits in a single turn

                The REPL runs in a VM context with tool APIs available as functions. Results from each tool call are collected and returned together.""";
    }

    /** 工具使用消息渲染 · 对齐 CC REPLTool.ts:60-64（code 80 字符预览）。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        String code = input != null && input.has("code") ? input.get("code").asText() : "";
        String preview = code.length() > 80 ? code.substring(0, 77) + "..." : code;
        return "REPL: " + preview;
    }

    /** 输入 schema · 对齐 CC REPLTool.ts:7-15 {@code z.strictObject({code})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode code = props.putObject("code");
        code.put("type", "string");
        code.put("description",
                "The code to execute in the REPL. Can call any primitive tool (Read, Write, Edit, "
                        + "Glob, Grep, Bash, NotebookEdit, Agent) via their APIs.");
        schema.putArray("required").add("code");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 结果块渲染 · 对齐 CC REPLTool.ts:66-75 mapToolResultToToolResultBlockParam：
     * content = REPLOutput.result。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        String content;
        if (result instanceof ToolResult<?> tr && tr.data() instanceof String s) {
            try {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                content = node.path("result").asText(s);
            } catch (Exception e) {
                content = s;
            }
        } else {
            content = ToolResult.renderToolResultPayloadText((ToolResult<?>) result);
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC REPLTool.ts:77-89 call — 无 ant-native runtime 恒错误 stub。
     *
     * <p>REPL 执行引擎由 ant-native runtime 提供；Java 无该引擎 → 恒返回
     * {@code {result: 'Error: ...', tool_calls: 0}}（对齐 CC 真源行为，非 fail-loud 桩）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("result", NOT_AVAILABLE_RESULT);
        out.put("tool_calls", 0);
        if (log.isInfoEnabled()) {
            log.info("[REPLTool] REPL 引擎不可用（无 ant-native runtime），返回 CC 恒错误 stub "
                    + "（REPLTool.ts:77-89）");
        }
        return ToolResult.success(call.id(), out.toString());
    }
}

package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P-CC-03] SchemaNotSentHint telemetry attrs 对齐 CC（补 isMcp、删 toolUseID）。
 *
 * <p>CC 真源 {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:625-628}：
 * <pre>{@code
 * logEvent('tengu_deferred_tool_schema_not_sent', {
 *   toolName: sanitizeToolNameForAnalytics(tool.name),
 *   isMcp: tool.isMcp ?? false,
 * })
 * }</pre>
 * 事件 attrs 恰为 {@code {toolName, isMcp}} —— 无 {@code toolUseID}。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: H-C2 已披露 Java
 * {@link StreamingToolExecutor#emitDeferredSchemaTelemetry} attrs 为
 * {@code {toolName, toolUseID}}（缺 isMcp、多 toolUseID）。telemetry attrs 是
 * 分析侧事件契约，多字段/少字段都会造成下游统计口径漂移（isMcp 用于 MCP
 * 工具占比分析，toolUseID 是 CC 未定义字段）。本测试走真实链路
 * （真实 ToolRegistry + 真实 ToolInputValidator schema 校验失败 + 真实
 * SchemaNotSentHint 4 道门 + 匿名 Telemetry 捕获 attrs）验证 attrs 精确对齐。
 *
 * <p><b>4 道门前置</b>（决定 hint 是否注入 → 事件是否发出，CC :578-597）：
 * <ol>
 *   <li>feature gate: {@code ToolSearchService.envOverride = Map.of()} 强制
 *       mode='tst' → 乐观开启（防本机环境变量干扰）</li>
 *   <li>ToolSearch 可用: ctx.availableTools() 含 name="ToolSearch"</li>
 *   <li>deferred tool: isMcp=true 恒 defer / 非 MCP 显式 shouldDefer=true</li>
 *   <li>discovered set: 真扫描 ctx.messages()（H2 对齐 CC toolSearch.ts:545-592）。
 *       test 消息为空 → discovered 空 → gate4 放行；若含 tool_reference 目标 → 拦截</li>
 * </ol>
 *
 * @see StreamingToolExecutor#emitDeferredSchemaTelemetry
 * @see SchemaNotSentHint
 */
class StreamingToolExecutorDeferredSchemaTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 可配置 mock tool: name / isMcp / shouldDefer 可控（对齐 SchemaNotSentHint 4 门语义）。 */
    static class ConfigTool implements Tool {
        private final String name;
        private final boolean mcp;
        private final boolean defer;

        ConfigTool(String name, boolean mcp, boolean defer) {
            this.name = name;
            this.mcp = mcp;
            this.defer = defer;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "mock tool " + name; }

        @Override public JsonNode inputSchema() {
            // required "path" 缺失 → 真实 ToolInputValidator schema 校验必失败
            ObjectNode schema = JSON.createObjectNode();
            schema.putArray("required").add("path");
            schema.putObject("properties").putObject("path").put("type", "string");
            return schema;
        }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        @Override public boolean isMcp() { return mcp; }

        @Override public boolean shouldDefer(JsonNode input) { return defer; }

        @Override public McpServerInfo mcpInfo() {
            return mcp ? new McpServerInfo(name + "_server", "stdio") : null;
        }
    }

    private ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    private ToolUseContext ctxWith(List<Tool> availableTools) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, availableTools);
    }

    /** 匿名 Telemetry 捕获 tengu_deferred_tool_schema_not_sent 事件的 attrs 快照。 */
    private Telemetry capturingSpy(AtomicReference<Map<String, Object>> deferredAttrs) {
        return new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_deferred_tool_schema_not_sent".equals(name)) {
                    deferredAttrs.set(new HashMap<>(attributes));
                }
            }
        };
    }

    /**
     * MCP deferred tool: schema 校验失败 → 4 门全过 → 事件发出,
     * attrs 恰为 {@code {toolName, isMcp=true}}, 无 {@code toolUseID}
     * （CC toolExecution.ts:625-628）。
     */
    @Test
    @DisplayName("MCP deferred tool: attrs={toolName, isMcp=true} 无 toolUseID (CC toolExecution.ts:625-628)")
    void deferredSchemaTelemetry_mcpTool_attrsAlignedWithCc() {
        ToolSearchService.envOverride = Map.of(); // gate1 恒开 (mode='tst')
        try {
            ConfigTool mcpTool = new ConfigTool("mcp__demo", true, false);
            ConfigTool searchTool = new ConfigTool("ToolSearch", false, false);
            ToolRegistry registry = registryWith(mcpTool, searchTool);
            ToolUseContext ctx = ctxWith(List.of(searchTool, mcpTool));

            AtomicReference<Map<String, Object>> deferredAttrs = new AtomicReference<>();
            StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
            exec.setTelemetry(capturingSpy(deferredAttrs));
            exec.setInputValidator(new ToolInputValidator()); // 真实 schema 校验

            exec.add(new ToolUseBlock("call_mcp_1", "mcp__demo", JSON.createObjectNode()));
            exec.getRemainingResults();

            assertThat(deferredAttrs.get())
                .as("MCP deferred schema 校验失败必须发出 tengu_deferred_tool_schema_not_sent")
                .isNotNull();
            assertThat(deferredAttrs.get().keySet())
                .as("attrs 恰为 {toolName, isMcp} — CC toolExecution.ts:625-628 无 toolUseID")
                .containsExactlyInAnyOrder("toolName", "isMcp");
            assertThat(deferredAttrs.get().get("toolName"))
                .as("toolName = mcp_tool (CC sanitizeToolNameForAnalytics 值语义, metadata.ts:70-77 — P-AL-05 D-1)")
                .isEqualTo("mcp_tool");
            assertThat(deferredAttrs.get().get("isMcp"))
                .as("isMcp = tool.isMcp() = true (CC tool.isMcp ?? false)")
                .isEqualTo(Boolean.TRUE);
        } finally {
            ToolSearchService.envOverride = null;
        }
    }

    /**
     * 非 MCP 但显式 shouldDefer=true 的工具（如 Plan）: 4 门全过 → 事件发出,
     * {@code isMcp=false} 对齐 CC {@code tool.isMcp ?? false}（CC Tool.ts:436
     * isMcp 为可选布尔, 缺省 false）。
     */
    @Test
    @DisplayName("非 MCP deferred tool: isMcp=false (CC tool.isMcp ?? false)")
    void deferredSchemaTelemetry_nonMcpDeferredTool_isMcpFalse() {
        ToolSearchService.envOverride = Map.of();
        try {
            ConfigTool deferTool = new ConfigTool("Plan", false, true); // isMcp=false + shouldDefer=true
            ConfigTool searchTool = new ConfigTool("ToolSearch", false, false);
            ToolRegistry registry = registryWith(deferTool, searchTool);
            ToolUseContext ctx = ctxWith(List.of(searchTool, deferTool));

            AtomicReference<Map<String, Object>> deferredAttrs = new AtomicReference<>();
            StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
            exec.setTelemetry(capturingSpy(deferredAttrs));
            exec.setInputValidator(new ToolInputValidator());

            exec.add(new ToolUseBlock("call_plan_1", "Plan", JSON.createObjectNode()));
            exec.getRemainingResults();

            assertThat(deferredAttrs.get())
                .as("非 MCP deferred schema 校验失败同样发出事件 (gate3 只看 deferred)")
                .isNotNull();
            assertThat(deferredAttrs.get().keySet())
                .as("attrs 仍恰为 {toolName, isMcp}")
                .containsExactlyInAnyOrder("toolName", "isMcp");
            assertThat(deferredAttrs.get().get("isMcp"))
                .as("非 MCP 工具 isMcp=false (CC tool.isMcp ?? false 缺省分支)")
                .isEqualTo(Boolean.FALSE);
        } finally {
            ToolSearchService.envOverride = null;
        }
    }

    /**
     * 非 deferred 工具: gate3 拦截 → 不发出事件（CC toolExecution.ts:589
     * isDeferredTool false → buildSchemaNotSentHint 返回 null → :624 if (schemaHint)
     * 不进入）。锁定"事件只在 hint 注入时发出"的触发条件。
     */
    @Test
    @DisplayName("非 deferred tool: 不发出事件 (gate3 拦截, CC :589/:624)")
    void deferredSchemaTelemetry_nonDeferredTool_noEvent() {
        ToolSearchService.envOverride = Map.of();
        try {
            ConfigTool readTool = new ConfigTool("Read", false, false); // 非 MCP 且不 defer
            ConfigTool searchTool = new ConfigTool("ToolSearch", false, false);
            ToolRegistry registry = registryWith(readTool, searchTool);
            ToolUseContext ctx = ctxWith(List.of(searchTool, readTool));

            AtomicReference<Map<String, Object>> deferredAttrs = new AtomicReference<>();
            StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
            exec.setTelemetry(capturingSpy(deferredAttrs));
            exec.setInputValidator(new ToolInputValidator());

            exec.add(new ToolUseBlock("call_read_1", "Read", JSON.createObjectNode()));
            exec.getRemainingResults();

            assertThat(deferredAttrs.get())
                .as("非 deferred 工具 schema 校验失败不发 tengu_deferred_tool_schema_not_sent")
                .isNull();
        } finally {
            ToolSearchService.envOverride = null;
        }
    }
}

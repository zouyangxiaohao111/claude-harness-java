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
import com.nexusai.application.agent.tool.ToolParent;
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
 * [P-AL-05 D-2] tengu_tool_use_error telemetry attrs 对齐 CC 载荷。
 *
 * <p>CC 真源 {@code Open-ClaudeCode/src/services/tools/toolExecution.ts}：
 * <ul>
 *   <li>schema 校验失败 :635-662 —— {@code {error: 'InputValidationError', errorDetails:
 *       errorContent.slice(0, 2000), messageID: messageId, toolName:
 *       sanitizeToolNameForAnalytics(tool.name), isMcp: tool.isMcp ?? false,
 *       queryChainId?, queryDepth?, ...}}</li>
 *   <li>validateInput 语义失败 :691-698 —— {@code {messageID: messageId, toolName:
 *       sanitizeToolNameForAnalytics(tool.name), error: isValidCall.message, errorCode:
 *       isValidCall.errorCode, isMcp: tool.isMcp ?? false, queryChainId?, queryDepth?, ...}}</li>
 * </ul>
 * 两载荷均<b>无 toolUseID</b>（旧 Java 实现多传，P-AL-05 D-2 已删）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: P-CC-03 D-2 已披露 Java
 * {@code emitToolUseErrorTelemetry} attrs = {toolName, toolUseID, error}（缺 isMcp /
 * messageID / errorDetails / errorCode，多 toolUseID）。telemetry attrs 是分析侧事件
 * 契约：isMcp 驱动 MCP 工具错误占比、errorDetails 承载 Zod 详情、errorCode 供按类型
 * 分支（PATH_ESCAPE 区别于 SCHEMA_INVALID）、messageID 归因到助手消息，toolUseID 是
 * CC 未定义字段——多字段/少字段都会造成下游统计口径漂移。本测试走真实链路
 * （真实 ToolRegistry + 真实 ToolInputValidator schema/语义校验失败 + 匿名 Telemetry
 * 捕获 attrs）验证两路径载荷精确对齐。
 *
 * @see StreamingToolExecutor#emitToolUseErrorTelemetry
 */
class StreamingToolExecutorErrorTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 可配置 mock tool: name / isMcp / schema 是否必过 / 语义校验失败 (errorCode+message) /
     * schema 超长 required 字段名（验证 errorDetails 2000 截断）。
     */
    static class ConfigTool implements Tool {
        private final String name;
        private final boolean mcp;
        private final boolean schemaPass;         // true = 空 schema (走语义校验路径)
        private final String semanticErrorCode;   // null = 语义校验通过
        private final String semanticErrorMessage;
        private final String longRequiredField;   // null = 常规 required="path"

        ConfigTool(String name, boolean mcp) {
            this(name, mcp, false, null, null, null);
        }

        ConfigTool(String name, boolean mcp, String semanticErrorCode,
                   String semanticErrorMessage, String longRequiredField) {
            this(name, mcp, false, semanticErrorCode, semanticErrorMessage, longRequiredField);
        }

        ConfigTool(String name, boolean mcp, boolean schemaPass, String semanticErrorCode,
                   String semanticErrorMessage, String longRequiredField) {
            this.name = name;
            this.mcp = mcp;
            this.schemaPass = schemaPass;
            this.semanticErrorCode = semanticErrorCode;
            this.semanticErrorMessage = semanticErrorMessage;
            this.longRequiredField = longRequiredField;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "mock tool " + name; }

        @Override public JsonNode inputSchema() {
            if (schemaPass) {
                // 空 schema: 无 required/类型约束 → schema 校验必过, 走到语义校验路径
                return JSON.createObjectNode();
            }
            // required 字段缺失 → 真实 ToolInputValidator schema 校验必失败
            ObjectNode schema = JSON.createObjectNode();
            String required = longRequiredField != null ? longRequiredField : "path";
            schema.putArray("required").add(required);
            schema.putObject("properties").putObject(required).put("type", "string");
            return schema;
        }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        @Override public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
            if (semanticErrorCode != null) {
                return ValidationResult.fail(semanticErrorCode, semanticErrorMessage);
            }
            return ValidationResult.pass();
        }

        @Override public boolean isMcp() { return mcp; }

        @Override public boolean shouldDefer(JsonNode input) { return false; }

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

    /** 匿名 Telemetry 捕获 tengu_tool_use_error 事件的 attrs 快照（recordEvent 拦截）。 */
    private Telemetry capturingSpy(AtomicReference<Map<String, Object>> errorAttrs) {
        return new Telemetry() {
            @Override
            public void recordEvent(String name, Map<String, Object> attributes) {
                if ("tengu_tool_use_error".equals(name)) {
                    errorAttrs.set(new HashMap<>(attributes));
                }
            }
        };
    }

    /**
     * schema 校验失败（MCP 工具 + 父消息）: 载荷对齐 CC toolExecution.ts:635-662 —
     * {@code {error: 'InputValidationError', errorDetails, messageID, toolName: 'mcp_tool',
     * isMcp: true}}，无 {@code toolUseID}。
     */
    @Test
    @DisplayName("schema fail MCP: attrs={error,errorDetails,messageID,toolName,isMcp=true} 无 toolUseID (CC :635-662)")
    void errorTelemetry_schemaFail_mcpTool_payloadAlignedWithCc() {
        ConfigTool mcpTool = new ConfigTool("mcp__demo", true);
        ToolRegistry registry = registryWith(mcpTool);
        ToolUseContext ctx = ctxWith(List.of(mcpTool));

        AtomicReference<Map<String, Object>> errorAttrs = new AtomicReference<>();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(capturingSpy(errorAttrs));
        exec.setInputValidator(new ToolInputValidator()); // 真实 schema 校验

        // parent 提供 assistantMessageId（CC messageId 镜像）→ messageID 必须注入
        exec.add(new ToolUseBlock("call_mcp_1", "mcp__demo", JSON.createObjectNode()),
            ToolParent.of("asst-1"), null);
        exec.getRemainingResults();

        assertThat(errorAttrs.get())
            .as("schema 校验失败必须发出 tengu_tool_use_error")
            .isNotNull();
        assertThat(errorAttrs.get().keySet())
            .as("载荷恰为 {error, errorDetails, messageID, toolName, isMcp} — CC :635-662 无 toolUseID")
            .containsExactlyInAnyOrder("error", "errorDetails", "messageID", "toolName", "isMcp");
        assertThat(errorAttrs.get().get("error"))
            .as("error = 字面量 InputValidationError (CC :637 常量, 非错误详情)")
            .isEqualTo("InputValidationError");
        assertThat((String) errorAttrs.get().get("errorDetails"))
            .as("errorDetails = CC :638-641 errorContent（IT-4 三句式, 非旧折叠含 code 格式）")
            .contains("mcp__demo failed due to the following issue:\n"
                + "The required parameter `path` is missing");
        assertThat(errorAttrs.get().get("messageID"))
            .as("messageID = 父助手消息 ID (CC :642-643 messageId)")
            .isEqualTo("asst-1");
        assertThat(errorAttrs.get().get("toolName"))
            .as("toolName = mcp_tool (CC sanitizeToolNameForAnalytics 值语义, metadata.ts:70-77)")
            .isEqualTo("mcp_tool");
        assertThat(errorAttrs.get().get("isMcp"))
            .as("isMcp = tool.isMcp() = true (CC :645 tool.isMcp ?? false)")
            .isEqualTo(Boolean.TRUE);
    }

    /**
     * schema 校验失败（非 MCP 工具 + 无 parent）: isMcp=false（CC ?? false 缺省分支），
     * parent==null 时 messageID 不注入（Java 防御路径，不捏造归因）。
     */
    @Test
    @DisplayName("schema fail 非 MCP: isMcp=false + 无 parent 时不注入 messageID")
    void errorTelemetry_schemaFail_nonMcpTool_noParent_isMcpFalse() {
        ConfigTool readTool = new ConfigTool("Read", false);
        ToolRegistry registry = registryWith(readTool);
        ToolUseContext ctx = ctxWith(List.of(readTool));

        AtomicReference<Map<String, Object>> errorAttrs = new AtomicReference<>();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(capturingSpy(errorAttrs));
        exec.setInputValidator(new ToolInputValidator());

        exec.add(new ToolUseBlock("call_read_1", "Read", JSON.createObjectNode()));
        exec.getRemainingResults();

        assertThat(errorAttrs.get())
            .as("schema 校验失败必须发出 tengu_tool_use_error")
            .isNotNull();
        assertThat(errorAttrs.get().keySet())
            .as("无 parent → messageID 不注入; 载荷 = {error, errorDetails, toolName, isMcp}")
            .containsExactlyInAnyOrder("error", "errorDetails", "toolName", "isMcp");
        assertThat(errorAttrs.get().get("isMcp"))
            .as("非 MCP 工具 isMcp=false (CC :645 ?? false 缺省分支)")
            .isEqualTo(Boolean.FALSE);
        assertThat(errorAttrs.get()).doesNotContainKey("toolUseID");
    }

    /**
     * [_raw 兜底拦截 2026-09-04] openai 兼容模型超大 tool 参数（Bash heredoc / Write 大 content）
     * 手写 JSON 转义偶发非法 → accumulator/provider 兜底包 {@code {_raw: 原文}}。执行层必须识别并
     * 给「模型可行动的引导」（拆小重试），而非喂 zod 校验报误导性「缺 path / 多 _raw」（模型误以为
     * 自己该传 _raw，20+ 轮死循环事故）。对齐 CC：非法 input 走 zod safeParse 失败给模型错误，
     * 引导信息须指向真实原因（超长 JSON 转义失败）。
     */
    @Test
    @DisplayName("_raw 兜底 input → 引导「拆小重试」，不执行工具、不发误导 zod telemetry")
    void rawFallbackInput_intercepted_guidanceNotMisleadingZod() {
        ConfigTool bashTool = new ConfigTool("Bash", false); // schema required=path：若不拦截会 zod 报 path missing
        ToolRegistry registry = registryWith(bashTool);
        ToolUseContext ctx = ctxWith(List.of(bashTool));

        AtomicReference<Map<String, Object>> errorAttrs = new AtomicReference<>();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(capturingSpy(errorAttrs));
        exec.setInputValidator(new ToolInputValidator()); // 真实 zod——证明拦截先于它，未走误导 schema 失败

        // {_raw: 原文} = readTree 失败兜底形态（事故复刻：超大 heredoc 命令 JSON 转义失败）
        ObjectNode rawInput = JSON.createObjectNode();
        rawInput.put("_raw", "{\"command\": \"cd \\\"D:/项目/运维/智能化运维一体平台\\\" && cat > demo.html "
            + "<<'DEMO_EOF_A'\\n<!DOCTYPE html>\\n<html lang=\\\"zh-CN\\\">…(转义出错导致非闭合)");
        exec.add(new ToolUseBlock("call_bash_raw_1", "Bash", rawInput));
        var results = exec.getRemainingResults();

        assertThat(results).as("拦截返回 1 个错误结果").hasSize(1);
        assertThat(results.get(0)).isInstanceOf(ToolResult.class);
        String payload = ToolResult.renderToolResultPayloadText((ToolResult<?>) results.get(0));
        assertThat(payload)
            .as("引导必须指向真实原因（非合法 JSON + 拆小重试）——模型据此拆分而非误以为要传 _raw")
            .contains("不是合法 JSON").contains("拆成多次较小");
        assertThat(payload)
            .as("不得出现误导性 zod 错误（缺 command/path、多 _raw）——那让模型以为格式错而 20+ 轮死循环")
            .doesNotContain("required parameter");
        assertThat(errorAttrs.get())
            .as("_raw 拦截先于 zod：不发 tengu_tool_use_error（未走 schema 失败 telemetry 路径）")
            .isNull();
    }

    /**
     * validateInput 语义校验失败: 载荷对齐 CC toolExecution.ts:691-698 —
     * {@code {messageID, toolName, error: isValidCall.message, errorCode: isValidCall.errorCode,
     * isMcp}}，无 toolUseID、无 errorDetails。
     */
    @Test
    @DisplayName("semantic fail: attrs={messageID,toolName,error,errorCode,isMcp} 无 toolUseID (CC :691-698)")
    void errorTelemetry_semanticFail_payloadAlignedWithCc() {
        ConfigTool editTool = new ConfigTool("Edit", false, true, "PATH_ESCAPE",
            "path escapes workspace", null);
        ToolRegistry registry = registryWith(editTool);
        ToolUseContext ctx = ctxWith(List.of(editTool));

        AtomicReference<Map<String, Object>> errorAttrs = new AtomicReference<>();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(capturingSpy(errorAttrs));
        exec.setInputValidator(new ToolInputValidator()); // 真实语义校验 (委托 tool.validateInput)

        exec.add(new ToolUseBlock("call_edit_1", "Edit", JSON.createObjectNode()),
            ToolParent.of("asst-2"), null);
        exec.getRemainingResults();

        assertThat(errorAttrs.get())
            .as("语义校验失败必须发出 tengu_tool_use_error")
            .isNotNull();
        assertThat(errorAttrs.get().keySet())
            .as("载荷恰为 {messageID, toolName, error, errorCode, isMcp} — CC :691-698")
            .containsExactlyInAnyOrder("messageID", "toolName", "error", "errorCode", "isMcp");
        assertThat(errorAttrs.get().get("error"))
            .as("error = isValidCall.message (CC :695-696)")
            .isEqualTo("path escapes workspace");
        assertThat(errorAttrs.get().get("errorCode"))
            .as("errorCode = isValidCall.errorCode (CC :697)")
            .isEqualTo("PATH_ESCAPE");
        assertThat(errorAttrs.get().get("messageID"))
            .as("messageID = 父助手消息 ID (CC :692-693)")
            .isEqualTo("asst-2");
        assertThat(errorAttrs.get().get("isMcp"))
            .as("非 MCP 工具 isMcp=false")
            .isEqualTo(Boolean.FALSE);
    }

    /**
     * errorDetails 2000 字符截断: 超长 required 字段名 → schema 失败消息 > 2000 字符,
     * 载荷 errorDetails 必须截断为 2000（CC :638-641 errorContent.slice(0, 2000)）。
     */
    @Test
    @DisplayName("schema fail: errorDetails 截断 2000 字符 (CC slice(0,2000))")
    void errorTelemetry_schemaFail_errorDetailsTruncatedAt2000() {
        String longField = "f".repeat(2500);
        ConfigTool tool = new ConfigTool("LongField", false, null, null, longField);
        ToolRegistry registry = registryWith(tool);
        ToolUseContext ctx = ctxWith(List.of(tool));

        AtomicReference<Map<String, Object>> errorAttrs = new AtomicReference<>();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(capturingSpy(errorAttrs));
        exec.setInputValidator(new ToolInputValidator());

        exec.add(new ToolUseBlock("call_long_1", "LongField", JSON.createObjectNode()));
        exec.getRemainingResults();

        assertThat(errorAttrs.get())
            .as("schema 校验失败必须发出 tengu_tool_use_error")
            .isNotNull();
        assertThat((String) errorAttrs.get().get("errorDetails"))
            .as("errorDetails 截断为 2000 字符 (CC :638-641 slice(0,2000))")
            .hasSize(2000);
    }
}

package com.nexusai.apis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.List;

/**
 * 入站 MCP server 测试探针工具 · 仅供 {@link InboundMcpServerTest} 使用。
 *
 * <p><b>WHY</b>：真实工具（Bash/Write/Read 等）在集成测试中可能产生文件系统 / 进程
 * 副作用，无法安全驱动 tools/call 的成功与错误路径。本探针以可控行为（成功回显 /
 * 权限 deny / 执行失败 / 运行期禁用）驱动 Spring AI MCP server 的 4 类结果契约。
 *
 * <ul>
 *   <li>{@code prompt()} 覆盖 —— 验证 tools/list description = prompt() ?? description()</li>
 *   <li>{@code denyPermission} —— checkPermissions 返回 {@link PermissionResult.Deny}，
 *       验证权限门 → isError</li>
 *   <li>{@code failExecution} —— execute 返回 {@link ToolResult#error}，验证执行失败 → isError</li>
 *   <li>{@code enabled} 可变 —— 启动期 true（进入 tools/list 快照），运行期可翻转为 false，
 *       验证 isEnabled 门 → isError（CC mcp.ts:138-140）</li>
 * </ul>
 */
class McpProbeTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final String description;
    private final String prompt;
    private final boolean denyPermission;
    private final boolean failExecution;
    private volatile boolean enabled = true;

    McpProbeTool(String name, String description, String prompt,
                 boolean denyPermission, boolean failExecution) {
        this.name = name;
        this.description = description;
        this.prompt = prompt;
        this.denyPermission = denyPermission;
        this.failExecution = failExecution;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String prompt() {
        return prompt;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.set("msg", JSON.createObjectNode().put("type", "string"));
        return schema;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (denyPermission) {
            return new PermissionResult.Deny(
                "probe permission deny", new PermissionDecisionReason.Other("probe"), null);
        }
        return new PermissionResult.Allow(
            input, new PermissionDecisionReason.Other("probe allow"), null, false, null, List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (failExecution) {
            // [R3 / tool_v3 IMP-C2] AgentToolResult.isError() 已删，错误检测由
            // LlmAgentLoop.isToolErrorData(data) 前缀门承载——错误文案必须以已登记前缀开头
            // （如 "Error:"）才能被识别并转 Spring AI isError:true。旧文案 "probe execution
            // failure" 不命中任何前缀 → 被当作正常结果（isError=false）。
            return ToolResult.error(call.id(), "Error: probe execution failure");
        }
        JsonNode msg = call.input() == null ? null : call.input().get("msg");
        return ToolResult.success(call.id(), "echo:" + (msg == null ? "" : msg.asText()));
    }
}

package com.nexusai.apis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 入站 MCP server 工具适配 · 对齐 CC {@code Open-ClaudeCode/src/entrypoints/mcp.ts:59-188}
 * （startMCPServer 的 ListToolsRequestSchema + CallToolRequestSchema）。
 *
 * <h2>WHY 存在</h2>
 * <p>Q-23 拍板（open-decisions.md L58）：用 Spring AI 现成 MCP server 把
 * {@link ToolRegistry} 的工具暴露给外部 MCP client 调用（不自研 JSON-RPC）。
 * Spring AI 自动配置收集 {@link ToolCallback} bean → 转成 MCP SDK {@code Tool} +
 * call handler → WebMvc 端点 {@code /mcp} 接管。本类即每工具一个的
 * {@link ToolCallback} 适配器，把 CC {@code mcp.ts} 的 tools/list + tools/call
 * 语义映射到 Java {@link Tool} 接口。
 *
 * <h2>tools/list 语义（CC mcp.ts:59-97）</h2>
 * <ul>
 *   <li>工具集 = {@code getTools(getEmptyToolPermissionContext())} —— Java 端
 *       {@link ToolRegistry#getTools} 等价（SPECIAL_TOOLS/isEnabled 过滤）。见
 *       {@link InboundMcpServerConfig} 的快照过滤。</li>
 *   <li>{@code description} = {@code await tool.prompt(...)} —— Java 端
 *       {@code Tool.prompt() 非 null 优先，否则 Tool.description()}（对齐
 *       ToolRegistry.toOpenAiToolsArray:424-428 已实证）。</li>
 *   <li>{@code inputSchema} = {@link Tool#inputSchema()}（JSON Schema JsonNode）。</li>
 *   <li>{@code outputSchema} —— CC :68-82 仅当根 {@code type=='object'} 才暴露；
 *       <b>Java 偏离登记</b>：Spring AI 1.1.2 ToolCallback → MCP Tool 的转换
 *       （McpToolUtils.toSharedSyncToolSpecification）不写 outputSchema 字段，
 *       tools/list 无法携带。当前全部 Tool.outputSchema() 默认 null，功能零损失，
 *       登记为 Spring AI 通道限制（后续若某工具实现 outputSchema 再评估）。
 * </ul>
 *
 * <h2>tools/call 语义（CC mcp.ts:99-188）</h2>
 * <ol>
 *   <li>{@code findToolByName} → 本适配器按 toolName 在运行期从
 *       {@link ToolRegistry#get(String)} 重新解析（含 alias）；未找到 → 抛错（落在
 *       Spring AI 异常包装 → {@code isError:true}）。</li>
 *   <li>{@code tool.isEnabled()} 检查 → 禁用 → 抛错。</li>
 *   <li>{@code tool.validateInput?.(args, ctx)} → {@link Tool#validateInput}，fail → 抛错。</li>
 *   <li>{@code tool.call(args, ctx, hasPermissionsToUseTool, ...)} → <b>权限门（全量管线）</b>：
 *       CC 以 hasPermissionsToUseTool 回调实现，Java 端由 {@link ToolPermissionGate#check}
 *       承载同一全量管线：1a whole-tool deny 规则 / 1b ask 规则 / 1c
 *       {@link Tool} 自身权限表态（CC permissions.ts:1208-1216）/ 1d-1g bypass-immune 层 /
 *       2a bypass / 2b allow 规则 / 3 passthrough→ask 兜底 + dontAsk 变换 + headless
 *       决策链。<b>v4 空上下文契约（OPD-WF8-02-GS-01 拍板）</b>：
 *       {@link InboundPermissionContextFactory} 返回 CC 空上下文
 *       （getEmptyToolPermissionContext，Tool.ts:140-148）——<b>不合并全量 settings 规则</b>、
 *       {@code shouldAvoidPermissionPrompts=false}（headless 位不置）。入站非交互会话
 *       （{@code isNonInteractiveSession=true}）无交互弹窗通道，Ask 结果经
 *       {@code WebSocketPermissionPrompter} 的 isNonInteractiveSession 分支拒绝（对齐 CC
 *       permissions.ts:929-952 非交互 ask→deny 降级；permissions.ts:503-517 dontAsk 变换
 *       不触发 —— DEFAULT mode）。</li>
 *   <li>{@code tool.call(...)} → {@link Tool#execute(ToolUseBlock, ToolUseContext)}；
 *       结果 text = {@code typeof finalResult === 'string' ? finalResult :
 *       jsonStringify(finalResult.data)}（CC :159-168）。</li>
 *   <li>catch → Spring AI 把异常包装成 {@code {isError:true, content:[{type:'text',
 *       text}]}}（已实证 McpToolUtils 转换器），HTTP 层不 500。</li>
 * </ol>
 *
 * <h2>ToolUseContext 构造（CC mcp.ts:112-134）</h2>
 * <p>{@code abortController} 新建、{@code mcpClients} 空、{@code isNonInteractiveSession}
 * =true、{@code availableTools}=getTools(空权限上下文)、{@code readFileState} 走
 * {@link ToolUseContext#createFileStateCache()}（100 条 / 25MB 双限 LRU，对齐 CC
 * createFileStateCacheWithSizeLimit(100)）。其余字段走 compact ctor 兜底。
 *
 * @see InboundMcpServerConfig
 */
public class InboundMcpToolProvider implements ToolCallback {

    /** 快照工具（bean 构造期捕获，用于 tools/list 定义快照 · CC 启动期快照语义）。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Tool tool;
    private static final Logger log = LoggerFactory.getLogger(InboundMcpToolProvider.class);
    /** 运行期工具注册表（@Lazy 懒代理，tools/call 时重新解析 · CC findToolByName）。 */
    private final ToolRegistry toolRegistry;
    /** 全量权限管线门 · CC hasPermissionsToUseTool（permissions.ts:473-956）Java 等价。 */
    private final ToolPermissionGate permissionGate;
    /** 入站权限上下文工厂 · CC getEmptyToolPermissionContext（mcp.ts:102）Java 映射。 */
    private final InboundPermissionContextFactory permissionContextFactory;

    private final String toolName;
    private final String description;
    private final String inputSchema;

    /**
     * @param tool        被适配的 Java {@link Tool}
     * @param toolRegistry 运行期注册表（可 null，null 时 call 用快照工具执行）
     * @param permissionGate 全量权限管线门（非 null，null 抛 IllegalArgumentException
     *                      fail-loud，对齐 PermissionContextBuilder:177 范例）
     * @param permissionContextFactory 入站权限上下文工厂（非 null，同上）
     */
    public InboundMcpToolProvider(Tool tool, ToolRegistry toolRegistry,
            ToolPermissionGate permissionGate,
            InboundPermissionContextFactory permissionContextFactory) {
        if (tool == null) {
            throw new IllegalArgumentException("InboundMcpToolProvider: tool is null");
        }
        if (permissionGate == null) {
            throw new IllegalArgumentException("InboundMcpToolProvider: permissionGate is null");
        }
        if (permissionContextFactory == null) {
            throw new IllegalArgumentException(
                "InboundMcpToolProvider: permissionContextFactory is null");
        }
        this.tool = tool;
        this.toolRegistry = toolRegistry;
        this.permissionGate = permissionGate;
        this.permissionContextFactory = permissionContextFactory;
        this.toolName = tool.name();
        // tools/list description = prompt() ?? description()（对齐 ToolRegistry.toOpenAiToolsArray）
        String desc = tool.prompt();
        if (desc == null || desc.isBlank()) {
            desc = tool.description();
        }
        this.description = desc == null ? "" : desc;
        // tools/list inputSchema = Tool.inputSchema()（JSON Schema）；null → 空 object schema
        JsonNode schema = tool.inputSchema();
        this.inputSchema = (schema == null) ? "{\"type\":\"object\"}" : schema.toString();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    /**
     * tools/call 执行入口 · Spring AI 调用（入参为请求 arguments 的 JSON 字符串）。
     *
     * <p>对齐 CC mcp.ts:99-188：解析输入 → 运行期解析工具 → isEnabled/validateInput/
     * 权限门 → execute → 返回 text。任何失败以异常抛出，由 Spring AI MCP 转换器
     * 包装为 {@code isError:true}（已实证），HTTP 层不 500。
     *
     * @param toolInput 工具入参 JSON（可能为 {@code "{}"} / 空串 / 非法 JSON）
     * @return 工具结果 text（CC finalResult.data 序列化）
     */
    @Override
    public String call(String toolInput) {
        JsonNode input;
        try {
            if (toolInput == null || toolInput.isBlank()) {
                input = JSON.createObjectNode();
            } else {
                input = JSON.readTree(toolInput);
            }
        } catch (Exception e) {
            // 非法 JSON 入参：CC 端 args 由 SDK 解析（不会到达此处），Java 端防御性处理
            log.warn("InboundMcpToolProvider: 工具 {} 入参非法 JSON，返回 isError: {}",
                toolName, e.getMessage());
            throw new IllegalArgumentException(
                "Tool " + toolName + " input is invalid JSON: " + e.getMessage(), e);
        }
        if (input == null) {
            input = JSON.createObjectNode();
        }
        return callInternal(input);
    }

    /**
     * 工具执行主体 · 严格对齐 CC mcp.ts:138-168 的调用序列。
     */
    private String callInternal(JsonNode input) {
        // 1) 运行期解析工具（CC findToolByName → ToolRegistry.get(name)，含 alias）
        Tool runtimeTool = this.tool;
        if (toolRegistry != null) {
            Optional<Tool> resolved = toolRegistry.get(toolName);
            if (resolved.isPresent()) {
                runtimeTool = resolved.get();
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("InboundMcpToolProvider: 工具 {} 已解析（快照={}, 运行期={}）",
                toolName, tool.getClass().getSimpleName(), runtimeTool.getClass().getSimpleName());
        }

        ToolUseContext ctx = buildToolUseContext();

        // 2) isEnabled 检查（CC :138-140）
        if (!runtimeTool.isEnabled()) {
            log.warn("InboundMcpToolProvider: 工具 {} 未启用，返回 isError（CC mcp.ts:138-140）", toolName);
            throw new IllegalStateException("Tool " + toolName + " is not enabled");
        }

        // 3) validateInput（CC :141-149）
        Tool.ValidationResult validation = runtimeTool.validateInput(input, ctx);
        if (!validation.ok()) {
            log.warn("InboundMcpToolProvider: 工具 {} 输入校验失败: {}（errorCode={}）",
                toolName, validation.message(), validation.errorCode());
            throw new IllegalStateException(
                "Tool " + toolName + " input is invalid: " + validation.message());
        }

        // 4) 权限门：全量权限管线（CC hasPermissionsToUseTool → tool.call 第 3 参 canUseTool，
        //    permissions.ts:473-956）。gate.check = 10 层规则链（1a whole-tool deny 规则 /
        //    1b ask 规则 / 1c 工具自身权限表态（CC :1208-1216）/ 1d-1g bypass-immune /
        //    2b allow 规则 / 3 passthrough→ask 兜底）+ dontAsk 变换 + headless 决策链。
        //    v4 空上下文契约（OPD-WF8-02-GS-01）：InboundPermissionContextFactory 不合并
        //    全量 settings 规则、shouldAvoidPermissionPrompts=false（headless 位不置）——
        //    非交互 ask→deny 由 WebSocketPermissionPrompter 的 isNonInteractiveSession 分支
        //    承载（对齐 CC mcp.ts:121 + interactive 语义，permissions.ts:929-952）。非交互
        //    会话的 Ask 结果降级为 Deny，不再放行执行（S06 验收 #1/#2）。
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), toolName, input);
        ToolPermissionContext permCtx = permissionContextFactory.build();
        if (log.isDebugEnabled()) {
            log.debug("InboundMcpToolProvider: 工具 {} 权限管线检查（mode={} denyRules={} "
                    + "shouldAvoidPrompts={}）",
                toolName, permCtx.mode(), permCtx.alwaysDenyRules().size(),
                permCtx.shouldAvoidPermissionPrompts());
        }
        ToolPermissionGate.DecisionResult decision =
            permissionGate.check(runtimeTool, call, input, ctx, permCtx);
        if (decision.decision() == ToolPermissionGate.Decision.DENY) {
            // 落入 Spring AI 异常包装 isError:true（对齐 CC catch 路径 mcp.ts:170-186）
            String message = (decision.result() instanceof PermissionResult.Deny d)
                ? d.message() : "permission denied";
            log.warn("InboundMcpToolProvider: 工具 {} 被权限管线拒绝（isError）: {}",
                toolName, message);
            throw new IllegalStateException(
                "Tool " + toolName + " denied by permission: " + message);
        }
        // CC updatedInput ?? input：管线 Allow（hook/规则改写）以 updatedInput 作为执行输入
        JsonNode execInput = input;
        if (decision.decision() == ToolPermissionGate.Decision.ALLOW
                && decision.result() instanceof PermissionResult.Allow allow
                && allow.updatedInput() != null) {
            execInput = allow.updatedInput();
            if (log.isDebugEnabled()) {
                log.debug("InboundMcpToolProvider: 工具 {} 权限管线返回改写输入（updatedInput）",
                    toolName);
            }
        }

        // 5) 执行（CC tool.call → Java Tool.execute）
        ToolUseBlock execCall = (execInput == input)
            ? call
            : new ToolUseBlock(call.id(), toolName, execInput);
        AgentToolResult<?> result = runtimeTool.execute(execCall, ctx);

        // [tool-v3 合并裁决] AgentToolResult 已删 isError()（IMP-C2 拍板），错误检测以
        // LlmAgentLoop.isToolErrorData(result.data()) 门替代（master result.isError() 编译不过）。
        if (com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(result.data())) {
            // Java 工具错误不抛、返回 ToolResult.error —— CC 侧工具以 throw 表达错误 →
            // 此处转异常让 Spring AI 包装 isError:true（对齐 CC catch 路径 :170-186）
            log.warn("InboundMcpToolProvider: 工具 {} 执行返回错误结果: {}",
                toolName, result.data());
            throw new IllegalStateException("Tool " + toolName + " execution failed: " + result.data());
        }
        if (log.isDebugEnabled()) {
            log.debug("InboundMcpToolProvider: 工具 {} 执行成功", toolName);
        }

        // 6) text = string ? string : jsonStringify(data)（CC :159-168）
        Object data = result.data();
        if (data instanceof String s) {
            return s;
        }
        if (data == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("InboundMcpToolProvider: 工具 {} 结果序列化失败: {}", toolName, e.getMessage());
            throw new IllegalStateException(
                "Tool " + toolName + " result serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * ToolUseContext 构造 · 对齐 CC mcp.ts:112-134（isNonInteractiveSession=true、
     * mcpClients 空、abortController 新建、availableTools=getTools(空权限上下文)、
     * readFileState=双限 LRU）。
     */
    private ToolUseContext buildToolUseContext() {
        List<Tool> availableTools = (toolRegistry != null)
            ? toolRegistry.getTools(null)
            : List.of();
        // CC getEmptyToolPermissionContext → factory.build()（DEFAULT mode + 空三桶 +
        // shouldAvoidPermissionPrompts=false，v4 空上下文契约 OPD-WF8-02-GS-01；
        // 非交互 ask→deny 由下方 isNonInteractiveSession=true 承载）
        ToolPermissionContext permissionContext = permissionContextFactory.build();
        return new ToolUseContext(
            UUID.randomUUID(),            // agentId（CC 无，兜底）
            "sess-" + UUID.randomUUID().toString().substring(0, 8),  // sessionId（CC 无，MCP 每调用独立；short 形态）
            PermissionMode.DEFAULT,
            Map.of(),                     // additionalWorkingDirectories
            availableTools,               // availableTools = getTools(空权限上下文)
            "",                           // taskListId
            new AbortController(),        // createAbortController()
            List.of(),                    // messages = []
            permissionContext,            // permissionContext（factory.build()）
            PermissionMode.DEFAULT,       // permissionMode
            Map.of(),                     // mcpClients = []（空）
            true,                         // isNonInteractiveSession = true
            "");                          // renderedSystemPrompt
    }
}

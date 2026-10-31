package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.InProcessMcpTransport;
import com.nexusai.application.agent.mcp.McpCallContext;
import com.nexusai.application.agent.mcp.McpServerToolProvider;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * computer-use 的 <b>真 MCP server</b> · 逐字对齐 CCB
 * {@code packages/@ant/computer-use-mcp/src/mcpServer.ts:255-314} 的
 * {@code createComputerUseMcpServer}。
 *
 * <h2>CCB 结构（本仓逐条对应）</h2>
 * <table border="1">
 *   <caption>CCB → 本仓</caption>
 *   <tr><th>CCB</th><th>本仓</th></tr>
 *   <tr><td>{@code createComputerUseMcpServer(adapter, coordinateMode, context?)}（:255-259）</td>
 *       <td>{@link #createComputerUseMcpServer}（同名）</td></tr>
 *   <tr><td>{@code buildComputerUseTools(adapter.executor.capabilities, coordinateMode)}（:267-270）</td>
 *       <td>{@link ComputerUseTools#buildComputerUseTools}（已逐字移植，平台裁剪在其内完成）</td></tr>
 *   <tr><td>{@code ListTools ⇒ adapter.isDisabled() ? {tools:[]} : {tools}}（:272-274）</td>
 *       <td>{@link #listToolsResult()}（同款分支）</td></tr>
 *   <tr><td>{@code CallTool ⇒ bindSessionContext(...).call(name, args)}（:276-288）</td>
 *       <td>{@link #callToolResult}（分派到既有 35 个工具实现）</td></tr>
 *   <tr><td>剥掉 {@code screenshot} / {@code telemetry} piggyback 字段（:281-286）</td>
 *       <td>{@link #toWireResult}（<b>只产出 content + isError</b>）</td></tr>
 *   <tr><td>无 context ⇒ stub handler（:292-312）</td>
 *       <td>{@link #NOT_WIRED_MESSAGE} + {@link #stubResult}（<b>明确报错，不静默</b>）</td></tr>
 * </table>
 *
 * <h2>WHY 结果只留 content + isError</h2>
 * CCB 的 {@code CuCallToolResult} = {@code CallToolResult & {screenshot?, telemetry?}}；
 * {@code mcpServer.ts:281-286} 在返回 SDK 前把这两个 piggyback 字段剥掉
 * （{@code toolCalls.ts:151-155} 注释亦明言「MCP clients never see this field」）。
 * <br>本仓同理：截图的<b>几何元数据</b>（displayWidth / origin / scaleFactor …）<b>不进 wire</b>，
 * 它由会话级 {@link ComputerUseGeometryStore} / {@link ComputerUseGroundingStore} 持有，
 * 供后续点击做坐标换算（护栏② 落点校验也读它）。
 *
 * <h2>会话上下文怎么来</h2>
 * CCB 在建 server 时闭包绑定 session context（单会话 CLI）。本仓 MCP server 是进程级单例
 * （{@link com.nexusai.application.agent.mcp.McpToolPool} 按 server 名缓存连接），
 * 多会话共享 ⇒ 改为<b>每次调用显式带</b>：{@code tools/call} params 的
 * {@link McpCallContext#PARAM_NAME} 同级键携带 sessionId / agentId / 模型名。
 * <br>⇒ 无会话上下文（信封缺失 / sessionId 为空 / 未绑定 provider）⇒ 走 stub 分支，
 * <b>返回明确错误</b>，不静默成功。
 */
public final class ComputerUseMcpServer implements McpServerToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseMcpServer.class);

    /** Jackson mapper。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * MCP server 名 · 对齐 CCB {@code src/utils/computerUse/common.ts:4}
     * {@code COMPUTER_USE_MCP_SERVER_NAME = 'computer-use'}。
     *
     * <p>模型可见的完整工具名 = {@code McpStringUtils.buildMcpToolName(SERVER_NAME, tool)}
     * = {@code mcp__computer-use__<tool>}。
     */
    public static final String SERVER_NAME = "computer-use";

    /** server 版本 · 对齐 CCB {@code mcpServer.ts:263} {@code version: '0.1.3'}。 */
    public static final String SERVER_VERSION = "0.1.3";

    /**
     * 未绑定会话时的错误文案 · <b>逐字</b> CCB {@code mcpServer.ts:305}。
     *
     * <p>CCB 原文：
     * {@code 'This computer-use server instance is not wired to a session. Per-session app permissions are not available on this code path.'}
     */
    public static final String NOT_WIRED_MESSAGE =
            "This computer-use server instance is not wired to a session. "
                    + "Per-session app permissions are not available on this code path.";

    /** tool_use_id 生成器（MCP {@code tools/call} 不带 tool_use_id ⇒ 本仓自造）。 */
    private final AtomicLong toolUseSeq = new AtomicLong(0);

    /** 能力记录（screenshotFiltering / platform / teachMode）· 决定工具面与描述文案。 */
    private final ComputerUseCapabilities capabilities;

    /** 坐标模式（冻结值）· 决定描述文案与换算口径。 */
    private final ComputerUseCoordinateMode coordinateMode;

    /** 已安装应用名（可空 ⇒ installedAppsHint 为空串，与 CCB CLI 同款：建 server 时不传第三参）。 */
    private final List<String> installedAppNames;

    /** 工具实现表 · key = <b>裸工具名</b>（如 {@code screenshot}），value = 本仓 {@link Tool} 实现。 */
    private final Map<String, Tool> toolImplementations;

    /** 开关关闭 ⇒ {@code ListTools} 返回空列表（对齐 CCB {@code adapter.isDisabled() ? {tools:[]} : {tools}}）。 */
    private final boolean disabled;

    /**
     * 会话上下文解析器 · sessionId（+ 模型标识）→ 会话上下文。
     *
     * <p><b>null ⇒ 未绑定会话</b> ⇒ {@code CallTool} 走 stub 分支（明确报错）。
     */
    private final Function<McpCallContext, ComputerUseSessionContext> sessionContextProvider;

    /**
     * MCP 工具池（装配期用）· 由接线方 setter 注入（避开 bean 循环：ToolRegistry → 本 server
     * → McpToolPool → ToolRegistry）。
     */
    private McpToolPool mcpToolPool;

    /** transport 工厂（登记进程内 server 用）· setter 注入（同上，避循环）。 */
    private McpTransportFactory transportFactory;

    /**
     * 注入 MCP 工具池。
     *
     * @param mcpToolPool 工具池
     */
    public void setMcpToolPool(McpToolPool mcpToolPool) {
        this.mcpToolPool = mcpToolPool;
    }

    /**
     * 注入 transport 工厂。
     *
     * @param transportFactory 工厂
     */
    public void setTransportFactory(McpTransportFactory transportFactory) {
        this.transportFactory = transportFactory;
    }

    private ComputerUseMcpServer(ComputerUseCapabilities capabilities,
                                 ComputerUseCoordinateMode coordinateMode,
                                 List<String> installedAppNames,
                                 Map<String, Tool> toolImplementations,
                                 boolean disabled,
                                 Function<McpCallContext, ComputerUseSessionContext> sessionContextProvider) {
        this.capabilities = capabilities;
        this.coordinateMode = coordinateMode;
        this.installedAppNames = installedAppNames == null ? List.of() : List.copyOf(installedAppNames);
        this.toolImplementations = Map.copyOf(toolImplementations);
        this.disabled = disabled;
        this.sessionContextProvider = sessionContextProvider;
    }

    /**
     * 创建 computer-use MCP server · 对齐 CCB {@code mcpServer.ts:255-259} 的
     * {@code createComputerUseMcpServer(adapter, coordinateMode, context?)}。
     *
     * <p><b>参数映射</b>（CCB → 本仓）：
     * <ul>
     *   <li>{@code adapter.executor.capabilities} → {@code capabilities}
     *       （{@code ComputerUseCapabilities}：screenshotFiltering / platform / teachMode）；</li>
     *   <li>{@code coordinateMode} → {@code coordinateMode}
     *       （本仓用 enum，wire 值见 {@link ComputerUseCoordinateMode#wire()}）；</li>
     *   <li>{@code adapter.isDisabled()} → {@code disabled}
     *       （开关关闭 ⇒ {@code ListTools} 返回空列表）；</li>
     *   <li>{@code context?}（{@code ComputerUseSessionContext}）→ {@code sessionContextProvider}
     *       （<b>null ⇒ 未绑定会话</b> ⇒ {@code CallTool} 走 stub handler，CCB 同款分支）；</li>
     *   <li>CCB 的 {@code adapter.handleToolCall}（工具实现本体）→ {@code toolImplementations}
     *       （本仓既有 35 个 {@link Tool} 实现，<b>业务语义零改动</b>）。</li>
     * </ul>
     *
     * @param capabilities         能力记录
     * @param coordinateMode       坐标模式（冻结值）
     * @param installedAppNames    已安装应用名（可空）
     * @param toolImplementations  裸工具名 → 工具实现
     * @param disabled             开关关闭 ⇒ 工具面为空
     * @param sessionContextProvider 会话上下文解析器（null ⇒ 未绑定会话 ⇒ stub）
     * @return server 实例
     */
    public static ComputerUseMcpServer createComputerUseMcpServer(
            ComputerUseCapabilities capabilities,
            ComputerUseCoordinateMode coordinateMode,
            List<String> installedAppNames,
            Map<String, Tool> toolImplementations,
            boolean disabled,
            Function<McpCallContext, ComputerUseSessionContext> sessionContextProvider) {
        return new ComputerUseMcpServer(capabilities, coordinateMode, installedAppNames,
                toolImplementations, disabled, sessionContextProvider);
    }

    /**
     * 把 server 装到 <b>服务端</b> transport 上（装 request handler）。
     *
     * <p>对应 CCB {@code inProcessServer.connect(serverTransport)}（{@code client.ts:944}）
     * —— 本仓无 MCP SDK 的 {@code Server} 类，协议面等价物就是「transport 上的 request handler」。
     *
     * @param serverTransport 服务端 transport（{@link InProcessMcpTransport#createLinkedPair()} 的 b 侧）
     */
    public void installOn(InProcessMcpTransport serverTransport) {
        serverTransport.setRequestHandler(this::handleRequest);
    }

    /**
     * 创建 linked transport pair · 对齐 CCB {@code createLinkedTransportPair()}（{@code client.ts:942}）。
     *
     * <p>返回 {@code [clientTransport, serverTransport]}：a（客户端）{@code sendRequest} →
     * b（服务端）{@code requestHandler} → 结果自动回投 a。服务端 handler 已装好，
     * 服务端 transport 处于 CONNECTED。
     *
     * @param server computer-use server
     * @return pair（a = 客户端侧，b = 服务端侧）
     */
    public static InProcessMcpTransport[] createLinkedPair(ComputerUseMcpServer server) {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        server.installOn(pair[1]);
        // 两侧均置 CONNECTED（客户端侧在生产里还会被 McpToolPool 的 transport.start(config) 再置一次，
        // 幂等）⇒ 本方法自足，调用方拿到 pair 即可直接发请求。
        pair[1].start(null);
        pair[0].start(null);
        return pair;
    }

    /**
     * MCP request handler · 分派 {@code initialize} / {@code ping} / {@code tools/list} / {@code tools/call}。
     *
     * @param method JSON-RPC method
     * @param params JSON-RPC params
     * @return JSON-RPC result（Map）
     */
    public Object handleRequest(String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initializeResult();
            case "ping" -> Map.of();
            case "tools/list" -> listToolsResultUnchecked();
            case "tools/call" -> callToolResult(params);
            default -> throw new IllegalArgumentException(
                    "computer-use MCP server 不支持的方法: " + method);
        };
    }

    /**
     * {@code tools/list} 结果（不抛受检异常）· schema 构造失败 ⇒ fail loud（RuntimeException）。
     *
     * @return tools/list result
     */
    private Map<String, Object> listToolsResultUnchecked() {
        try {
            return listToolsResult();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("[computer-use] 构造 MCP 工具 schema 失败: " + e.getMessage(), e);
        }
    }

    /**
     * {@code initialize} 结果 · 对齐 CCB {@code mcpServer.ts:262-265}
     * （{@code new Server({name: serverName, version: '0.1.3'}, {capabilities: {tools:{}, logging:{}}})}）。
     *
     * @return initialize result
     */
    public Map<String, Object> initializeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of());
        caps.put("logging", Map.of());
        result.put("capabilities", caps);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);
        return result;
    }

    /**
     * {@code tools/list} 结果 · 对齐 CCB {@code mcpServer.ts:272-274}。
     *
     * <p>开关关闭 ⇒ {@code {tools: []}}（<b>空列表</b>，不是报错）。
     * 工具面（含平台裁剪：Windows 35 / macOS 24）由
     * {@link ComputerUseTools#buildComputerUseTools} 内完成 ⇒ 与 CCB 同源。
     *
     * @return tools/list result
     * @throws JsonProcessingException schema 构造失败（fail loud）
     */
    public Map<String, Object> listToolsResult() throws JsonProcessingException {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (!disabled) {
            for (ComputerUseToolDefinition def :
                    ComputerUseTools.buildComputerUseTools(capabilities, coordinateMode.wire(), installedAppNames)) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", def.name());
                tool.put("description", def.description());
                tool.put("inputSchema", def.inputSchema());
                tools.add(tool);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        if (log.isInfoEnabled()) {
            log.info("[computer-use] MCP tools/list → {} 个工具（disabled={}）", tools.size(), disabled);
        }
        return result;
    }

    /**
     * {@code tools/call} 结果 · 对齐 CCB {@code mcpServer.ts:278-288}。
     *
     * @param params tools/call params（{@code name} + {@code arguments} + 可选 {@code _callContext}）
     * @return tools/call result（<b>只含 content + isError</b>）
     */
    public Map<String, Object> callToolResult(JsonNode params) {
        String name = params == null ? "" : params.path("name").asText("");
        JsonNode arguments = params == null ? null : params.path("arguments");
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            arguments = JSON.createObjectNode();
        }
        ComputerUseSessionContext sessionContext = resolveSessionContext(params);
        if (sessionContext == null) {
            // CCB mcpServer.ts:292-312 的 stub 分支：无会话上下文 → 明确报错，不静默
            log.warn("[computer-use] MCP tools/call \"{}\" 到达未绑定会话的 stub 分支 —— 无会话上下文",
                    name);
            return stubResult();
        }
        return callTool(name, arguments, sessionContext);
    }

    /**
     * 分派到既有的工具实现 · <b>不改写任何业务语义</b>。
     *
     * @param name           裸工具名（如 {@code screenshot}）
     * @param arguments      工具入参（JSON）
     * @param sessionContext 会话上下文
     * @return MCP wire 结果（content + isError）
     */
    public Map<String, Object> callTool(String name, JsonNode arguments,
                                         ComputerUseSessionContext sessionContext) {
        Tool tool = toolImplementations.get(name);
        if (tool == null) {
            log.warn("[computer-use] MCP tools/call 未知工具: {}", name);
            return errorResult("Unknown tool: " + name);
        }
        String toolUseId = "mcp-cu-" + toolUseSeq.incrementAndGet();
        ToolUseBlock call = new ToolUseBlock(toolUseId, tool.name(), arguments);
        ToolUseContext ctx = sessionContext.toolUseContext();
        AgentToolResult<?> result;
        try {
            result = tool.execute(call, ctx);
        } catch (Exception e) {
            log.error("[computer-use] MCP tools/call {} 执行异常: {}", name, e.getMessage(), e);
            return errorResult("Tool execution failed: " + e.getMessage());
        }
        Map<String, Object> wire = toWireResult(result);
        if (log.isInfoEnabled()) {
            log.info("[computer-use] MCP tools/call {} → isError={} content={} 块（session={}）",
                    name, wire.get("isError"),
                    ((List<?>) wire.get("content")).size(), sessionContext.sessionId());
        }
        return wire;
    }

    /**
     * 工具结果 → MCP wire 结果 · <b>只保留 content + isError</b>。
     *
     * <p>对应 CCB {@code mcpServer.ts:281-286} 的剥字段动作：{@code screenshot} /
     * {@code telemetry} 两个 piggyback 字段<b>不进 wire</b>。
     *
     * @param result 工具执行结果
     * @return MCP wire 结果
     */
    public static Map<String, Object> toWireResult(AgentToolResult<?> result) {
        Object data = result == null ? null : result.data();
        List<Map<String, Object>> content = new ArrayList<>();
        if (data instanceof ComputerUseScreenshotResult shot) {
            // 截图：只发 image content block；几何元数据留在会话级 store（不进 wire）
            for (ComputerUseMcpContent item : shot.content()) {
                content.add(toWireContent(item));
            }
        } else if (data instanceof ComputerUseMcpContent item) {
            content.add(toWireContent(item));
        } else if (data instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ComputerUseMcpContent c) {
                    content.add(toWireContent(c));
                }
            }
        } else if (data != null) {
            content.add(Map.of("type", "text", "text", asText(data)));
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("content", content);
        wire.put("isError", isError(data));
        return wire;
    }

    /**
     * MCP content block · 逐字 CCB {@code toolCalls.ts:2210-2214}（image）
     * 与 {@code wrapper.tsx:302-318}（text 兜底空串）。
     *
     * @param item MCP content
     * @return wire content block
     */
    private static Map<String, Object> toWireContent(ComputerUseMcpContent item) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (ComputerUseMcpContent.TYPE_IMAGE.equals(item.type())) {
            block.put("type", "image");
            block.put("data", item.data());
            block.put("mimeType", item.mimeType());
        } else {
            block.put("type", "text");
            block.put("text", item.text() == null ? "" : item.text());
        }
        return block;
    }

    /**
     * isError 推导 · 沿用本仓既有口径 {@code LlmAgentLoop.isToolErrorData(data)}。
     *
     * <p><b>WHY</b>：本仓 {@code ToolResult} 已按 CC 4 字段契约删除 {@code isError} 字段
     * （{@code ToolResult.java:142-146}）⇒ 错误信号统一由
     * {@code StreamingToolExecutor:2037} 经 {@code isToolErrorData} 推导。
     * 本 server 走同一条推导路径 ⇒ <b>MCP 路径与直注册路径的 isError 口径一致</b>。
     */
    private static boolean isError(Object data) {
        return com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(data);
    }

    /**
     * 数据 → 文本内容（CCB {@code okJson} = {@code JSON.stringify(obj)} 同款）。
     *
     * @param data 工具结果数据
     * @return 文本
     */
    private static String asText(Object data) {
        if (data instanceof String s) {
            return s;
        }
        if (data instanceof JsonNode node) {
            return node.toString();
        }
        return String.valueOf(data);
    }

    /**
     * 未绑定会话的 stub 结果 · 逐字 CCB {@code mcpServer.ts:301-308}。
     *
     * @return stub 结果（content 一条文本 + isError=true）
     */
    public static Map<String, Object> stubResult() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("content", List.of(Map.of("type", "text", "text", NOT_WIRED_MESSAGE)));
        wire.put("isError", true);
        return wire;
    }

    /**
     * 错误结果（CCB {@code errorResult} 同款：content 一条文本 + isError=true）。
     *
     * @param text 错误文案
     * @return wire 结果
     */
    public static Map<String, Object> errorResult(String text) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("content", List.of(Map.of("type", "text", "text", text)));
        wire.put("isError", true);
        return wire;
    }

    /**
     * 解析会话上下文 · 从 {@code tools/call} params 的信封取。
     *
     * @param params tools/call params
     * @return 会话上下文；未绑定 provider / 信封缺失 / sessionId 为空 ⇒ null（⇒ stub 分支）
     */
    private ComputerUseSessionContext resolveSessionContext(JsonNode params) {
        if (sessionContextProvider == null) {
            return null;
        }
        McpCallContext callContext = McpCallContext.fromWire(
                params == null ? null : params.path(McpCallContext.PARAM_NAME));
        if (callContext == null) {
            return null;
        }
        try {
            return sessionContextProvider.apply(callContext);
        } catch (Exception e) {
            log.error("[computer-use] 解析会话上下文失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /** server 名（对外只读）。 */
    public String serverName() {
        return SERVER_NAME;
    }

    /** 工具实现表（裸工具名 → 工具）· 供接线侧登记权限委托。 */
    public Map<String, Tool> toolImplementations() {
        return toolImplementations;
    }

    /**
     * 启动期装配 · <b>真 MCP 协议往返</b>（initialize + tools/list）并把工具注册进 ToolRegistry。
     *
     * <p><b>对齐 CCB</b>：
     * <ul>
     *   <li>{@code client.ts:929-948}：{@code createComputerUseMcpServerForCli()} →
     *       {@code createLinkedTransportPair()} → {@code inProcessServer.connect(serverTransport)}
     *       → {@code transport = clientTransport}（<b>不 spawn 子进程</b>）—— 本仓等价物 =
     *       把「进程内 server」登记进 {@link McpTransportFactory}（type=in-process），
     *       由 {@link McpToolPool} 走既有 connect → initialize → tools/list 链路；</li>
     *   <li>{@code setup.ts:23-50}：{@code allowedTools = buildComputerUseTools(...).map(t =>
     *       buildMcpToolName(SERVER_NAME, t.name))} —— 本仓等价物 =
     *       {@link McpToolPool} 用 {@link com.nexusai.application.agent.mcp.McpStringUtils#buildMcpToolName}
     *       生成模型可见名（{@code mcp__computer-use__<tool>}）。</li>
     * </ul>
     *
     * <p><b>免弹窗语义</b>：把每个工具的 {@code checkPermissions} <b>委托回底层 CU 工具</b>
     * ⇒ 普通 CU 工具自表态 {@code Allow}（第 3 层原样透传 ⇒ 免弹窗）、
     * {@code request_access} 自表态 {@code Passthrough}（⇒ 第 3 层转 Ask ⇒ 审批唯一入口仍是它）。
     * <br>⛔ <b>不用 allow rule</b>（{@code CheckLayer2b_ToolAlwaysAllowed} 首个命中即返回
     * ⇒ 会把 {@code request_access} 一起放行）。
     *
     * @return 已注册的 MCP 工具（开关关闭 ⇒ 空列表）
     */
    @Override
    public List<Tool> assembleMcpTools() {
        if (disabled || toolImplementations.isEmpty()) {
            if (log.isInfoEnabled()) {
                log.info("[computer-use] MCP server 未装配（disabled={} tools={}）",
                        disabled, toolImplementations.size());
            }
            return List.of();
        }
        if (mcpToolPool == null || transportFactory == null) {
            throw new IllegalStateException(
                    "[computer-use] MCP server 未接线（mcpToolPool / transportFactory 为 null）");
        }
        // 1. 登记进程内 server（不 spawn 子进程）· 对齐 CCB client.ts:929-948
        transportFactory.registerInProcessServer(SERVER_NAME, () -> {
            InProcessMcpTransport[] pair = createLinkedPair(this);
            return pair[0];
        });
        // 2. 会话型 server：tools/call 随带会话信封
        mcpToolPool.registerSessionAwareServer(SERVER_NAME);
        // 3. 权限自决委托：交回底层 CU 工具（免弹窗 + request_access 仍走 Ask）
        for (Map.Entry<String, Tool> entry : toolImplementations.entrySet()) {
            mcpToolPool.registerToolPermissionDelegate(SERVER_NAME, entry.getKey(), entry.getValue());
        }
        // 4. 真协议往返装配：connect → initialize → tools/list → 注册 McpServerTool
        McpTransport.TransportConfig config = new McpTransport.TransportConfig(
                null, List.of(), null, null, SERVER_NAME, "in-process");
        List<McpToolPool.McpToolEntry> entries = mcpToolPool.assembleToolPool(SERVER_NAME, config);
        if (log.isInfoEnabled()) {
            log.info("[computer-use] MCP server 装配完成：{} 个工具（server={} · 传输=in-process）",
                    entries.size(), SERVER_NAME);
        }
        return entries.stream().map(McpToolPool.McpToolEntry::tool).toList();
    }
}

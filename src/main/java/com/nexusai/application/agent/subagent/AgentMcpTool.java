package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpAuthError;
import com.nexusai.application.agent.mcp.McpElicitationStateMachine;
import com.nexusai.application.agent.mcp.McpSessionExpiredException;
import com.nexusai.application.agent.mcp.McpToolExecutionSupport;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Agent-scoped MCP tool 适配器 · 对齐 CC {@code MCPTool.ts} 的 execute() 委托 +
 * client.ts annotations/description/timeout 映射 + fetchToolsForClient per-tool 覆盖
 * （:1779-1803/:1840-1936/:1972-1976）。
 *
 * <p>把 {@link AgentMcpServers.McpToolChannel} 包成 Tool 接口，让 sub-agent
 * 可以像调本地工具一样调 MCP 工具。区别于 {@code McpServerTool}：
 * <ul>
 *   <li>{@code McpServerTool}（{@code com.nexusai.application.agent.mcp}）——
 *       全局 MCP 注册池中的 tool，依赖 {@code McpToolPool.callTool(...)}，
 *       调用路径：{@code pool → transport.sendRequest}</li>
 *   <li>{@link AgentMcpTool}（本类）—— agent-scoped MCP tool，经
 *       {@link AgentMcpServers.McpToolChannel} 调用（agent 轨独占通道；
 *       resetSession 重建会话承载 B8 重试）；agent 结束时连接由
 *       {@link AgentMcpServers.McpServerConnection#cleanup()} 关闭</li>
 * </ul>
 *
 * <p>[S05 统一包装面（Q-09-R2-2）] 对齐 CC client.ts:
 * <ul>
 *   <li><b>B7 进度三态</b>：started（调用前，:1846-1856）/ completed（成功含
 *       elapsedTimeMs，:1884-1895）/ failed（异常含 elapsedTimeMs，:1925-1936）——
 *       {@code {toolUseID, data:{type:'mcp_progress', status, serverName, toolName,
 *       elapsedTimeMs?}}}</li>
 *   <li><b>R2-06 X-1 请求侧 meta</b>：toolUseId → {@code meta={'claudecode/toolUseId':
 *       toolUseId}}（:1840-1843），随 channel.call 的 {@code _meta} 并入 tools/call 请求
 *       （:3096）</li>
 *   <li><b>B8 会话重试</b>：{@code MAX_SESSION_RETRIES=1}（:1859），
 *       {@link McpSessionExpiredException} → {@code channel.resetSession()}（重建
 *       transport+initialize）后重试 1 次，仍失败抛原错误（:1913-1922）</li>
 *   <li><b>B9 elicitation+abort</b>：-32042 → 共享 {@link McpElicitationStateMachine}
 *       （:2850-2897）；decline/cancel → decline 文本 content（isError=false，:3008-3016）；
 *       abortController().onCancel → in-flight future.cancel(true)（finally removeOnCancel
 *       配对防泄漏）</li>
 *   <li><b>B11/B12/B13/B14</b>：searchHint（:1779-1783）/ toAutoClassifierInput
 *       （:1801-1803）/ userFacingName（:1972-1976）/ isResultTruncated（MCPTool.ts:67-69
 *       → terminal.ts:119-131）——全部委托共享 {@link McpToolExecutionSupport}
 *       （单一包装面，本类零复制）</li>
 *   <li>{@code description()} 返真实 tool.description (:1786-1788)；{@code prompt()} 超
 *       2048 截断 (:1789-1793)</li>
 *   <li>{@code execute} future {@code orTimeout} 等价 CC {@code Promise.race([callTool,
 *       timeoutPromise])} (:3091)，timeout 值取注入的 {@code mcpToolTimeoutMs}</li>
 * </ul>
 *
 * <p>[MCP-I-9 Q-31] subagent 上下文（{@code ctx.agentType() != null}）抑制 mcpMeta ·
 * 对齐 CC toolExecution.ts:1464/1727（Java agentId 恒非空不可用，判别沿用 agentType）。
 *
 * <p>package-private（不对外暴露），由 {@link AgentMcpServers#wrapAgentTool} 内部构造。
 *
 * @see AgentMcpServers
 * @see McpServerTool
 */
class AgentMcpTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpTool.class);

    /** CC MAX_SESSION_RETRIES = 1（client.ts:1859）。 */
    private static final int MAX_SESSION_RETRIES = 1;

    private final String serverName;
    private final String toolName;
    private final String mcpToolName;
    private final JsonNode inputSchema;
    private final JsonNode annotations;
    /** CC original: tool._meta（client.ts:1776-1785）· searchHint/alwaysLoad 数据源。 */
    private final JsonNode meta;
    private final String description;
    /** CC original: searchHint（client.ts:1779-1783）· 空白折叠后的搜索提示，非 string 时为 null。 */
    private final String searchHint;
    private final AgentMcpServers.McpToolChannel channel;
    private final long mcpToolTimeoutMs;
    /** 共享 URL elicitation 状态机（生产轨同一实例）· null = 未接线（测试直连，不处理 -32042）。 */
    private final McpElicitationStateMachine elicitationMachine;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 10 参构造器（package-private，仅 AgentMcpServers 调用）。
     *
     * @param serverName        MCP server 名（如 "filesystem"）
     * @param toolName          MCP server 上的 tool 名（如 "Read"）
     * @param mcpToolName       注册到 tool 系统的名字（"mcp__{server}__{tool}"）
     * @param inputSchema       MCP 返回的 input JSON Schema（可为 null）
     * @param annotations       MCP tools/list 返回的 {@code annotations} 节点（可为 null）
     *                          · CC original: {@code tool.annotations} (client.ts:1795-1808)
     * @param meta              MCP tools/list 返回的 {@code _meta} 节点（可为 null）
     *                          · CC original: {@code tool._meta} (client.ts:1776-1785)，
     *                          searchHint/alwaysLoad 数据源
     * @param description       MCP tools/list 返回的真实 {@code description} · CC original:
     *                          {@code tool.description} (client.ts:1786-1788)
     * @param channel           agent-scoped MCP 调用通道（tools/call 委托 +
     *                          resetSession 会话重建）· CC original: {@code client.callTool}
     *                          (client.ts:3092-3097)
     * @param mcpToolTimeoutMs  MCP tool 调用超时 ms · CC original: getMcpToolTimeoutMs()
     *                          (client.ts:224, 默认 100_000_000; Java Web 端默认 60_000)
     * @param elicitationMachine 共享 URL elicitation 状态机（可为 null；null = 不接
     *                          -32042 elicitation，测试直连）
     */
    AgentMcpTool(String serverName, String toolName, String mcpToolName,
                 JsonNode inputSchema, JsonNode annotations, JsonNode meta, String description,
                 AgentMcpServers.McpToolChannel channel, long mcpToolTimeoutMs,
                 McpElicitationStateMachine elicitationMachine) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.mcpToolName = mcpToolName;
        this.inputSchema = inputSchema;
        this.annotations = annotations;
        this.meta = meta;
        this.description = description != null ? description : "";
        this.searchHint = McpToolExecutionSupport.extractSearchHint(meta);
        this.channel = channel;
        this.mcpToolTimeoutMs = mcpToolTimeoutMs;
        this.elicitationMachine = elicitationMachine;
    }

    @Override
    public String name() {
        return mcpToolName;
    }

    @Override
    public String description() {
        // CC client.ts:1786-1788 async description() { return tool.description ?? '' }
        return description;
    }

    @Override
    public String prompt() {
        // CC client.ts:1789-1793: desc.length > MAX_MCP_DESCRIPTION_LENGTH ? slice + '… [truncated]' : desc
        if (description.length() > McpToolExecutionSupport.MAX_MCP_DESCRIPTION_LENGTH) {
            return description.substring(0, McpToolExecutionSupport.MAX_MCP_DESCRIPTION_LENGTH) + "… [truncated]";
        }
        return description;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema == null || inputSchema.isNull() ? null : inputSchema;
    }

    /** CC original: {@code inputJSONSchema}（Tool.ts:397；MCP tool 的 input schema 已是 JSON Schema 原样）。 */
    @Override
    public JsonNode inputJSONSchema() {
        return inputSchema == null || inputSchema.isNull() ? null : inputSchema;
    }

    @Override
    public boolean isMcp() {
        return true;
    }

    @Override
    public McpServerInfo mcpInfo() {
        // [IMP-E1 DC-2] CC mcpInfo 仅 2 字段（client.ts:1780）；agent 侧并行适配器同步迁移。
        return new McpServerInfo(serverName, toolName);
    }

    /**
     * MCP 工具权限自表态 → Passthrough · 对齐 CC 生产路径 {@code client.ts:1814-1832}
     * {@code fetchToolsForClient} 的 per-tool 覆盖（权威路径）。
     *
     * <p><b>语义</b>：MCP 工具不表态（passthrough），把 mcp__ 工具的放行决策交给通用规则层，
     * 由 10 层规则第 3 层兜底转 Ask（默认模式提示用户），替代默认 Allow 的「1c 快速放行」。
     * 与 {@link McpServerTool}（com.nexusai.application.agent.mcp）行为完全一致，
     * 消除两工具 checkPermissions 语义分裂。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("MCP 工具 checkPermissions → Passthrough: server={} tool={} mcpToolName={}",
                serverName, toolName, mcpToolName);
        }
        // CC client.ts:1818-1830 addRules suggestions：allow → localSettings，
        //   toolName = fullyQualifiedName（mcp__server__tool），ruleContent=undefined → wholeTool。
        return new PermissionResult.Passthrough(
            "MCPTool requires permission.",
            null,
            List.of(new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.LOCAL_SETTINGS,
                List.of(new PermissionRule(
                    PermissionRuleSource.LOCAL_SETTINGS,
                    PermissionBehavior.ALLOW,
                    PermissionRuleValue.wholeTool(mcpToolName)
                )),
                PermissionBehavior.ALLOW
            )),
            null,
            null
        );
    }

    /**
     * CC client.ts:1795-1796 isConcurrencySafe() { return tool.annotations?.readOnlyHint ?? false }.
     * Java Tool 接口签名带 input 参数, 但 CC 无参且忽略 input → 忽略 input 直接返映射值 (S5-1 决策).
     */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return readOnlyHint();
    }

    /** CC client.ts:1798-1799 isReadOnly() { return tool.annotations?.readOnlyHint ?? false }. */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return readOnlyHint();
    }

    /** CC client.ts:1804-1805 isDestructive() { return tool.annotations?.destructiveHint ?? false }. */
    @Override
    public boolean isDestructive(JsonNode input) {
        return annotations != null ? annotations.path("destructiveHint").asBoolean(false) : false;
    }

    /** CC client.ts:1807-1808 isOpenWorld() { return tool.annotations?.openWorldHint ?? false }. */
    @Override
    public boolean isOpenWorld(JsonNode input) {
        return annotations != null ? annotations.path("openWorldHint").asBoolean(false) : false;
    }

    /** CC client.ts:1785 alwaysLoad() { return tool._meta?.['anthropic/alwaysLoad'] === true }. */
    @Override
    public boolean alwaysLoad() {
        if (meta == null || meta.isNull() || meta.isMissingNode()) {
            return false;
        }
        JsonNode n = meta.get("anthropic/alwaysLoad");
        return n != null && n.isBoolean() && n.asBoolean();
    }

    /**
     * CC client.ts:1779-1783 searchHint：{@code tool._meta?.['anthropic/searchHint']} string →
     * 空白折叠 + trim || undefined（委托共享 {@link McpToolExecutionSupport#extractSearchHint}）。
     *
     * @return 空白折叠后的搜索提示；无/非 string/空串 → null（对齐 CC undefined）
     */
    @Override
    public String searchHint() {
        return searchHint;
    }

    /** CC client.ts:1972-1976 userFacingName()：`${client.name} - ${annotations?.title || tool.name} (MCP)`. */
    @Override
    public String userFacingName() {
        return McpToolExecutionSupport.userFacingName(serverName, toolName, annotations);
    }

    /** CC MCPTool.ts:35 maxResultSizeChars = 100_000（Tool.java default 50_000 不适用 MCP）。 */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /**
     * CC MCPTool.ts:67-69 isResultTruncated → terminal.ts:119-131 isOutputLineTruncated
     * （>3 换行且第 4 个换行后仍有内容；尾随换行不算）· 委托共享实现。
     */
    @Override
    public boolean isResultTruncated(String content) {
        return McpToolExecutionSupport.isResultTruncated(content);
    }

    /**
     * CC client.ts:1801-1803 mcpToolInputToAutoClassifierInput(input, tool.name)
     * （JS String() 语义强转）· 委托共享实现。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        return McpToolExecutionSupport.toAutoClassifierInput(input, toolName);
    }

    /**
     * 1 参 execute · 非 ctx 调用方（无 abort/elicitation 上下文）· 委托 3 参引擎（onProgress=null
     * → 进度跳过；suppressMcpMeta=false → mcpMeta 全量透传，与历史行为一致）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null, null);
    }

    /**
     * 2 参 execute · [MCP-I-9 Q-31] subagent 上下文抑制 mcpMeta · 对齐 CC
     * toolExecution.ts:1464/1727 {@code mcpMeta: toolUseContext.agentId ? undefined : mcpMeta}.
     *
     * <p>CC 用 {@code toolUseContext.agentId} 判别 subagent；Java {@code agentId} 现 compact ctor
     * 默认 UUID 恒非空（不可用），改用 {@code ctx.agentType() != null}（主链 base TUC agentType=null
     * 经 LlmAgentLoop buildBaseToolUseContext 复验；子代理 TUC agentType 恒设置 createSubagentContext）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, com.nexusai.application.agent.tool.ToolUseContext ctx) {
        return execute(call, ctx, null);
    }

    /**
     * 3 参执行引擎 · 统一包装面（B7 进度三态 + R2-06 X-1 meta + B8 会话重试 + B9
     * elicitation/abort）· 对齐 CC client.ts:1840-1936 call() + callMCPToolWithUrlElicitationRetry
     * （client.ts:2813-3027）：
     * <ol>
     *   <li>toolUseId → meta {'claudecode/toolUseId'}（:1840-1843）</li>
     *   <li>emit started（:1846-1856）</li>
     *   <li>for attempt 0..1（MAX_SESSION_RETRIES=1，:1859）：channel.call(args, meta)
     *       （abortController().onCancel → in-flight future.cancel(true) 传播 abort，
     *       finally removeOnCancel 配对）；McpSessionExpiredException →
     *       channel.resetSession()（重建 transport+initialize）后重试 1 次，仍失败抛原错误
     *       （:1913-1922）；-32042 → 共享 machine.callWithElicitationRetry（:2850-2897），
     *       decline/cancel → decline 文本 content（isError=false，:3008-3016）</li>
     *   <li>成功 → emit completed 含 elapsedTimeMs（:1884-1895）→ mcpMeta
     *       （Q-31 subagent 抑制保留）→ ToolResult</li>
     *   <li>异常 → emit failed（:1925-1936）→ ToolResult.error</li>
     * </ol>
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx,
                                      Consumer<Tool.ToolProgress> onProgress) {
        String toolUseId = call.id();
        // [R2-06 X-1] 请求侧 meta · CC client.ts:1840-1843 extractToolUseId(parentMessage)
        // → meta = toolUseId ? {'claudecode/toolUseId': toolUseId} : {}
        Map<String, Object> meta = toolUseId != null
            ? Map.of("claudecode/toolUseId", toolUseId)
            : Map.of();
        long startTime = System.currentTimeMillis();
        // B7 started 进度（CC client.ts:1846-1856）
        McpToolExecutionSupport.emitMcpProgress(onProgress, serverName, toolName, mcpToolName,
            toolUseId, "started", null);
        AbortController abortController = (ctx != null && ctx.abortController() != null)
            ? ctx.abortController() : AbortController.NOOP;
        // [MCP-I-9 Q-31] subagent 上下文（agentType != null）抑制 mcpMeta · 对齐 CC
        // toolExecution.ts:1464 (success) / :1727 (error)：mcpMeta: agentId ? undefined : mcpMeta
        boolean suppressMcpMeta = ctx != null && ctx.agentType() != null;
        try {
            JsonNode input = call.input();
            // input → Map for tools/call arguments
            Map<String, Object> args = MAPPER.convertValue(input,
                new TypeReference<Map<String, Object>>() {});
            // [B8] MAX_SESSION_RETRIES=1 会话重试循环（CC client.ts:1859-1922）
            for (int attempt = 0; ; attempt++) {
                // [B9] abort 传播：ctx.abortController 取消 → 取消 in-flight future
                //   （CC client.ts:1869 signal + :2958-2962；finally removeOnCancel 配对防泄漏）
                AtomicReference<CompletableFuture<JsonNode>> inFlight = new AtomicReference<>();
                Consumer<AbortController> onCancel = ignored -> {
                    CompletableFuture<JsonNode> f = inFlight.get();
                    if (f != null) {
                        f.cancel(true);
                    }
                };
                abortController.onCancel(onCancel);
                try {
                    InvokeOutcome outcome = invoke(args, meta, inFlight);
                    if (outcome.declined()) {
                        // [B9] decline/cancel → decline 文本 content（isError=false）· 对齐 CC
                        // client.ts:3008-3016（与 McpServerTool 生产轨一致：decline 不发射 completed）
                        log.info("[AgentMcpTool] {}.{} elicitation decline: {}", serverName, toolName,
                            outcome.declineMessage());
                        return makeMcpResult(outcome.declineMessage(), null);
                    }
                    JsonNode result = outcome.result();
                    boolean isError = result.path("isError").asBoolean(false);
                    String content = result.path("content").toString();
                    long elapsed = System.currentTimeMillis() - startTime;
                    // B7 completed 进度（CC client.ts:1884-1895）
                    McpToolExecutionSupport.emitMcpProgress(onProgress, serverName, toolName,
                        mcpToolName, toolUseId, "completed", elapsed);
                    log.info("[AgentMcpTool] {}.{} → isError={} 内容长度={} 耗时={}ms",
                        serverName, toolName, isError, content.length(), elapsed);
                    // [A1·对齐 CC mcp/client.ts:1897-1908] MCP 工具结果填充 mcpMeta
                    // (_meta + structuredContent)，SDK 消费者透传, never sent to model。
                    ToolResult.McpMeta mcpMeta = suppressMcpMeta
                        ? null : McpToolExecutionSupport.buildMcpMeta(result);
                    if (log.isDebugEnabled()) {
                        log.debug("MCP mcpMeta {} (agent-scoped): tool={} id={} hasMeta={} hasStructured={}",
                            suppressMcpMeta ? "抑制 (subagent, Q-31)" : "透传",
                            mcpToolName, abbreviateId(toolUseId),
                            mcpMeta != null && mcpMeta.meta() != null && !mcpMeta.meta().isEmpty(),
                            mcpMeta != null && mcpMeta.structuredContent() != null);
                    }
                    return makeMcpResult(content, mcpMeta);
                } catch (Exception e) {
                    Throwable cause = unwrap(e);
                    // [B8] session-expired → resetSession 重建会话后重试 1 次（CC client.ts:1913-1922）
                    if (cause instanceof McpSessionExpiredException && attempt < MAX_SESSION_RETRIES) {
                        if (log.isDebugEnabled()) {
                            log.debug("[AgentMcpTool] {}.{} 会话过期，resetSession 后重试 (attempt={})",
                                serverName, toolName, attempt);
                        }
                        channel.resetSession();
                        continue;
                    }
                    throw e;
                } finally {
                    abortController.removeOnCancel(onCancel);
                }
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            // B7 failed 进度（CC client.ts:1925-1936）
            McpToolExecutionSupport.emitMcpProgress(onProgress, serverName, toolName, mcpToolName,
                toolUseId, "failed", elapsed);
            Throwable cause = unwrap(e);
            // [G16 401 → McpAuthError] 对齐 CC client.ts:3194-3208 + McpServerTool:550-557：
            //   transport 层 401（HttpMcpTransport/SseMcpTransport/WsMcpTransport 抛 McpAuthError）
            //   解包后分类 → 返 auth 错误文本（needs-auth 语义；错误面对齐）。
            // [R-A1 · A-1 决策] 状态面补齐：McpAuthError 时同步把 appState.mcp.clients
            //   降级 needs-auth（对齐 CC toolExecution.ts:1599-1629 + StreamingToolExecutor
            //   degradeMcpClientToNeedsAuth :3988-4047 的共享执行层面）。全局池轨由
            //   McpServerTool:557-558 pool.markServerNeedsAuth 完成；agent 轨无全局池引用，
            //   复用 ctx.setAppState() 同一降级通道（三条件：按 serverName 查不到 /
            //   type!=='connected' → no-op；否则重建 {name,type:'needs-auth',config}）。
            if (cause instanceof McpAuthError authErr) {
                if (log.isDebugEnabled()) {
                    log.debug("[AgentMcpTool] {}.{} 401 认证失败 → McpAuthError: {}",
                        serverName, toolName, authErr.getMessage());
                }
                degradeMcpClientToNeedsAuth(ctx, authErr);
                return ToolResult.error(toolUseId, authErr.getMessage());
            }
            log.warn("[AgentMcpTool] {}.{} 调用失败(耗时{}ms): {}",
                serverName, toolName, elapsed,
                cause != null && cause.getMessage() != null
                    ? cause.getMessage() : String.valueOf(cause));
            return ToolResult.error(toolUseId, "MCP call failed: " + e.getMessage());
        }
    }

    /** 调用结果 · declined 时返回 decline 文本，否则 result 有效。 */
    private record InvokeOutcome(JsonNode result, String declineMessage) {
        boolean declined() {
            return declineMessage != null;
        }
    }

    /**
     * 执行工具调用（含 -32042 elicitation 重试包装）· 对齐 CC callMCPToolWithUrlElicitationRetry
     * （client.ts:2813-3027）。machine 未接线（null）→ 直连不处理 -32042。
     *
     * @param inFlight 当前 in-flight future 引用（abort 传播取消目标）
     */
    private InvokeOutcome invoke(Map<String, Object> args, Map<String, Object> meta,
                                 AtomicReference<CompletableFuture<JsonNode>> inFlight) {
        if (elicitationMachine == null) {
            // 未接线状态机（测试 null=不接 elicitation）→ 直连
            CompletableFuture<JsonNode> future = timedCall(args, meta);
            inFlight.set(future);
            return new InvokeOutcome(future.join(), null);
        }
        McpElicitationStateMachine.ElicitationOutcome outcome = elicitationMachine.callWithElicitationRetry(
            serverName, toolName, () -> {
                CompletableFuture<JsonNode> future = timedCall(args, meta);
                inFlight.set(future);
                return future;
            });
        if (outcome.declined()) {
            return new InvokeOutcome(null, outcome.declineMessage());
        }
        return new InvokeOutcome(outcome.result(), null);
    }

    /** tools/call + 超时 · 等价 CC Promise.race([client.callTool(...), timeoutPromise])（client.ts:3091）. */
    private CompletableFuture<JsonNode> timedCall(Map<String, Object> args, Map<String, Object> meta) {
        return channel.call(args, meta).orTimeout(mcpToolTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 解包 CompletionException → 原始 cause（future.join 的包装语义）。 */
    private static Throwable unwrap(Throwable e) {
        return e instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : e;
    }

    /**
     * [R-A1 · A-1 决策] McpAuthError → appState.mcp.clients needs-auth 降级 ·
     * 对齐 CC toolExecution.ts:1599-1629 {@code setAppState} 三条件 + Java 共享执行层面
     * {@code StreamingToolExecutor.degradeMcpClientToNeedsAuth}（:3988-4047，全局池轨
     * 由 {@code McpServerTool:557-558} 的 {@code pool.markServerNeedsAuth} 收敛）。
     *
     * <p>WHY 存在：全局池轨 401 时 {@code McpServerTool} 调 pool 标记 needs-auth（缓存 +
     * 连接状态注册表 + OAuth 伪工具替换）；agent 轨（本类）无全局池引用，A-1 前仅返 auth
     * 错误文本（WF-A-UN-3「错误面对齐、状态面未对齐」）。本方法复用 {@code ctx.setAppState()}
     * 把 appState.mcp.clients 中该 server 条目降级为 {@code needs-auth}，使 /mcp 展示与
     * 连接状态通知反映认证失败（CC toolExecution.ts:1599-1629 同一状态面）。
     *
     * <p>CC 三条件（逐条对齐 toolExecution.ts:1601-1629）：
     * <ol>
     *   <li>{@code prevState.mcp.clients.findIndex(c => c.name === serverName) === -1}
     *       → 返回 prevState（no-op）</li>
     *   <li>找到但 {@code type !== 'connected'} → 返回 prevState（不覆盖其他状态）</li>
     *   <li>否则显式重建 {@code {name, type:'needs-auth', config: existing.config}} —
     *       重建对象而非保留全部字段（client 其余字段被丢弃，reflector-H R-2 处置）</li>
     * </ol>
     *
     * @param ctx 工具调用上下文（setAppState 消费者；null / 未接线 → no-op）
     * @param err McpAuthError（携带 serverName）
     */
    private static void degradeMcpClientToNeedsAuth(ToolUseContext ctx, McpAuthError err) {
        if (ctx == null || ctx.setAppState() == null) {
            return;
        }
        String serverName = err.serverName();
        ctx.setAppState().accept(prev -> {
            Map<String, Object> prevMap = prev == null ? Map.of() : prev;
            // 惰性读取 / 创建 CC 结构: appState.mcp.clients (List<Map>)
            Object mcpObj = prevMap.get("mcp");
            List<Map<String, Object>> clients = new ArrayList<>();
            if (mcpObj instanceof Map<?, ?> mcpMap) {
                Object clientsObj = mcpMap.get("clients");
                if (clientsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> clientMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) clientMap);
                            clients.add(copy);
                        }
                    }
                }
            }
            // 条件 1: 按 name 查不到 → 返回 prev (CC toolExecution.ts:1607-1609)
            int existingIndex = -1;
            Map<String, Object> existing = null;
            for (int i = 0; i < clients.size(); i++) {
                Map<String, Object> client = clients.get(i);
                if (serverName != null && serverName.equals(client.get("name"))) {
                    existingIndex = i;
                    existing = client;
                    break;
                }
            }
            if (existingIndex == -1) {
                return prev;
            }
            // 条件 2: type !== 'connected' → 返回 prev (CC toolExecution.ts:1611-1614)
            if (existing == null || !"connected".equals(existing.get("type"))) {
                return prev;
            }
            // 条件 3: 按 CC toolExecution.ts:1616-1620 显式重建 {name, type:'needs-auth',
            // config: existing.config} 三字段 — 重建对象而非"保留全部字段再覆盖"。
            Map<String, Object> updated = new LinkedHashMap<>();
            updated.put("name", serverName);
            updated.put("type", "needs-auth");
            updated.put("config", existing.get("config"));
            clients.set(existingIndex, updated);
            log.info("[AgentMcpTool] MCP 服务器 {} 需要重新授权, 已降级 needs-auth"
                + "（对齐 CC toolExecution.ts:1599-1629, agent 轨 A-1）", serverName);
            Map<String, Object> newMcp = new LinkedHashMap<>();
            if (mcpObj instanceof Map<?, ?> mcpMap) {
                mcpMap.forEach((k, v) -> newMcp.put(String.valueOf(k), v));
            }
            newMcp.put("clients", clients);
            Map<String, Object> next = new LinkedHashMap<>(prevMap);
            next.put("mcp", newMcp);
            return next;
        });
    }

    private boolean readOnlyHint() {
        return annotations != null ? annotations.path("readOnlyHint").asBoolean(false) : false;
    }

    /** 构造带 mcpMeta 的 success/error ToolResult<String>.
     *  [IMP-C2] toolUseId/isError 由 mapper 推导（组 2-1 拍板），参数已删除。 */
    private static ToolResult<String> makeMcpResult(String content, ToolResult.McpMeta mcpMeta) {
        return new ToolResult<>(content, null, null, mcpMeta);
    }

    private static String abbreviateId(String id) {
        return id == null ? "null" : (id.length() <= 24 ? id : id.substring(0, 24));
    }
}

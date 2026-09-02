package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CompletionException;

/**
 * MCP server 工具的 Tool 适配器 · 对齐 CC tools/MCPTool/MCPTool.ts +
 * services/mcp/client.ts 的 fetchToolsForClient tool 包装.
 *
 * <p>把 McpToolPool.callTool() 包成 Tool 接口, 让 LLM 可像调本地工具一样调 MCP 工具.
 * 本类补全 CC annotations/_meta/description/prompt/classify/toAutoClassifierInput/
 * maxResultSizeChars/isResultTruncated/searchHint + call 进度（F1-F7/C1-C5），与子 agent 版
 * {@code AgentMcpTool} 行为统一（消除分裂）.
 *
 * <p>execute 主体对齐 CC {@code callMCPToolWithUrlElicitationRetry}
 * （client.ts:2813-3027）+ {@code transformMCPResult → processMCPResult}
 * （client.ts:2662-2706 / :2720-2799）：URL elicitation 两阶段重试 + 结果三件套
 * （{@link McpResultTransformer} + {@link McpOutputProcessor}），并透传 master 侧
 * C1 进度事件（started / completed / failed）.
 */
class McpServerTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpServerTool.class);

    /** CC MAX_MCP_DESCRIPTION_LENGTH = 2048 (client.ts:218). */
    static final int MAX_MCP_DESCRIPTION_LENGTH = 2048;

    /** CC terminal.ts:7 MAX_LINES_TO_SHOW = 3 · isOutputLineTruncated 判定线. */
    private static final int MAX_LINES_TO_SHOW = 3;

    /** CC MCPTool.ts:35 maxResultSizeChars = 100_000. */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    private final String serverName;
    private final String toolName;
    private final String mcpToolName;
    /**
     * MCP server base URL（远程 http/sse/ws · CC metadata.ts:102-116 语义，
     * Java 侧由 McpToolPool.wrapMcpTool 从 TransportConfig.command 提取）·
     * 供 isOfficialMcpUrl 判定（official registry URL → telemetry 保留真实工具名）。
     * stdio/sdk → null（normalizeOfficial(null) → null → 非官方，fail-closed）。
     */
    private final String serverUrl;
    private final JsonNode inputSchema;
    /** CC original: tool.annotations (client.ts:1795-1808) · tools/list 返回的注解节点. */
    private final JsonNode annotations;
    /** CC original: tool._meta (client.ts:1776-1785) · MCP server 任意元数据（外部 MCP server 可信域外）. */
    private final JsonNode meta;
    /** CC original: tool.description ?? '' (client.ts:1786-1788) · tools/list 返回的真实描述. */
    private final String description;
    /** CC original: searchHint (client.ts:1779-1783) · 空白折叠后的搜索提示，非 string 时为 null. */
    private final String searchHint;
    private final McpToolPool pool;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 9 参构造器（package-private，仅 McpToolPool.wrapMcpTool 调用，DIM-11）。
     *
     * @param serverName  MCP server 名（如 "filesystem"）
     * @param toolName    MCP server 上的 tool 名（如 "Read"）
     * @param mcpToolName 注册到 tool 系统的名字（"mcp__{server}__{tool}"）
     * @param inputSchema MCP 返回的 input JSON Schema（可为 null）
     * @param annotations MCP tools/list 返回的 {@code annotations} 节点（可为 null）
     *                    · CC original: tool.annotations (client.ts:1795-1808)
     * @param meta        MCP tools/list 返回的 {@code _meta} 节点（可为 null）
     *                    · CC original: tool._meta (client.ts:1776-1785)
     * @param description MCP tools/list 返回的真实 {@code description}（可为 null）
     *                    · CC original: tool.description (client.ts:1786-1788)
     * @param serverUrl   MCP server base URL（远程承载于 TransportConfig.command，
     *                    stdio/sdk → null）· CC original: metadata.ts:102-116（Java 侧
     *                    实现已随 D-B10-02 删除，serverUrl 由 McpToolPool.wrapMcpTool
     *                    从 TransportConfig.command 提取，语义不变）
     * @param pool        全局 MCP 注册池（callTool 委托目标）
     */
    McpServerTool(String serverName, String toolName, String mcpToolName,
                  JsonNode inputSchema, JsonNode annotations, JsonNode meta,
                  String description, String serverUrl, McpToolPool pool) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.mcpToolName = mcpToolName;
        this.inputSchema = inputSchema;
        this.annotations = annotations;
        this.meta = meta;
        this.description = description != null ? description : "";
        this.serverUrl = serverUrl;
        this.searchHint = extractSearchHint(meta);
        this.pool = pool;
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
        // CC client.ts:1789-1794 + :218: 超过 2048 截断并追加 '… [truncated]'
        if (description.length() > MAX_MCP_DESCRIPTION_LENGTH) {
            return description.substring(0, MAX_MCP_DESCRIPTION_LENGTH) + "… [truncated]";
        }
        return description;
    }

    @Override
    public JsonNode inputSchema() {
        // [IMP-E1 S-1] CC MCPTool.ts:14 inputSchema = z.object({}).passthrough() — 校验面恒放行：
        //   LLM 缺参/错型 MCP 参数原样发 server（不注入 InputValidationError）。passthrough 的
        //   zod v4 toJSONSchema = {}（Tool.java:558 实测）。真实 tools/list schema 由
        //   inputJSONSchema() 承载（api.ts:157-160 inputJSONSchema 优先，序列化层仍见真实 schema）。
        //   Java 端 empty object schema → ToolInputValidator.safeParseSchema 直接 pass（:164-167）。
        return MAPPER.createObjectNode();
    }

    /**
     * 直接 JSON Schema 声明 · CC original: {@code inputJSONSchema}
     * （{@code Tool.ts:397}；MCP tool 的 input schema 已是 JSON Schema 原样，api.ts:157-160
     * inputJSONSchema 优先）。
     *
     * <p>序列化层优先使用本值，避免 inputSchema() 二次转换。
     *
     * @return MCP tools/list 返回的 input JSON Schema（原样）
     */
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
        // [IMP-E1 DC-2] CC mcpInfo = {serverName, toolName}（client.ts:1780）；serverUrl 不再
        //  承载于 mcpInfo（走 server 配置，getLoggingSafeMcpBaseUrl 语义由 McpToolPool 配置提供）。
        return new McpServerInfo(serverName, toolName);
    }

    /**
     * MCP 工具权限自表态 · 对齐 CC 生产路径 {@code client.ts:1814-1830}
     * {@code fetchToolsForClient} 的 per-tool 覆盖（权威路径，含 addRules suggestions；
     * 非 MCPTool.ts:56-61 base 模板——后者仅 {@code behavior:'passthrough'} + message，无 suggestions）：
     * <pre>
     * async checkPermissions() {
     *   return {
     *     behavior: 'passthrough' as const,
     *     message: 'MCPTool requires permission.',
     *     suggestions: [{
     *       type: 'addRules' as const,
     *       rules: [{ toolName: fullyQualifiedName, ruleContent: undefined }],
     *       behavior: 'allow' as const,
     *       destination: 'localSettings' as const,
     *     }],
     *   }
     * }
     * </pre>
     *
     * <p><b>语义</b>：MCP 工具不表态（passthrough），把 mcp__ 工具的放行决策交给通用规则层——
     * 无规则 + DEFAULT mode → 第 3 层 passthrough→ask（弹窗）；auto-mode → 分类器；deny/ask 规则
     * 在 1a/1b 层先于 1c 命中。若沿用 {@link Tool} 接口默认 {@code Allow}（Tool.java:287-295），
     * 1c 层 {@code CheckLayer1c_ToolCheck} 直接短路放行 → mcp__ 工具在「无规则 + 非 bypass」下被静默执行
     * （CC 会弹窗 ask），且 auto-mode 分类器永不覆盖（放权升级）。注册层 deny（SafeToolWhitelist /
     * LlmAgentLoop deny 过滤）仍独立生效，本方法不触碰。子 agent 版 {@code AgentMcpTool} 同源缺陷
     * 已由 MCP-SEC-01 闭环（AgentMcpTool.checkPermissions → Passthrough）。
     *
     * <p><b>suggestions 映射</b>：CC {@code destination:'localSettings'} →
     * {@link PermissionUpdate.Destination#LOCAL_SETTINGS}；{@code rules[0].toolName} =
     * {@code fullyQualifiedName}（buildMcpToolName，即 Java {@code mcpToolName} =
     * {@code mcp__{server}__{tool}}）→ {@link PermissionRuleValue#wholeTool(String)} 整工具规则；
     * rule source 取 LOCAL_SETTINGS（与 destination 同源；[DEL-WF1-04] 生产者负责 source）。CC
     * {@code ruleContent: undefined} → Java {@code PermissionRuleValue.wholeTool}。
     * {@link com.nexusai.application.agent.permission.check.CheckLayer3_PassthroughToAsk}
     * 对齐 CC permissions.ts:1299-1310 {@code ...toolPermissionResult} spread 透传该 suggestions 到最终 Ask。
     * CC 的 passthrough 变体无 reason 字段 → Java {@code reason=null}（{@link PermissionResult.Passthrough}
     * javadoc 允许 null）。
     *
     * @param input LLM 给的参数（不消费，CC 无参 async）
     * @param ctx   工具调用上下文（不消费）
     * @return      Passthrough（message 逐字对齐 CC MCPTool.ts:59 + addRules suggestions 对齐 client.ts:1818-1830）
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("MCP 工具 checkPermissions → Passthrough + addRules 建议: server={} tool={} mcpToolName={}",
                serverName, toolName, mcpToolName);
        }
        // [RF-6 ①] CC client.ts:1818-1830 addRules suggestions：allow → localSettings，
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
     * Java Tool 接口签名带 input 参数, 但 CC 无参且忽略 input → 忽略 input 直接返映射值.
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
     * CC client.ts:1779-1783 searchHint（G5 已提升为 Tool 接口契约成员，本方法为 override）：
     * <pre>
     * typeof tool._meta?.['anthropic/searchHint'] === 'string'
     *   ? tool._meta['anthropic/searchHint'].replace(/\s+/g, ' ').trim() || undefined
     *   : undefined
     * </pre>
     * 空白折叠 WHY：_meta 对外部 MCP server 开放，含换行会在 deferred-tool 列表
     * 注入孤儿行（formatDeferredToolLine 以 '\n' 拼接）。
     *
     * @return 空白折叠后的搜索提示；无/非 string/空串 → null（对齐 CC undefined）
     */
    @Override
    public String searchHint() {
        return searchHint;
    }

    /**
     * CC client.ts:1801-1803 + :1733-1741 toAutoClassifierInput:
     * <pre>
     * mcpToolInputToAutoClassifierInput(input, tool.name):
     *   keys.length > 0 ? keys.map(k => `${k}=${String(input[k])}`).join(' ') : toolName
     * </pre>
     * 值用 JS {@code String()} 语义强转（对象→[object Object]、数组→逗号拼接、null→"null"）。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode() || input.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("MCP toAutoClassifierInput 空输入回退 toolName: {}", toolName);
            }
            return toolName;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        java.util.Iterator<Map.Entry<String, JsonNode>> it = input.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            parts.add(e.getKey() + "=" + jsString(e.getValue()));
        }
        if (log.isDebugEnabled()) {
            log.debug("MCP toAutoClassifierInput 编码: tool={} 字段数={}", mcpToolName, parts.size());
        }
        return String.join(" ", parts);
    }

    /**
     * CC client.ts:1810-1812 isSearchOrReadCommand() → classifyMcpToolForCollapse
     * （classifyForCollapse.ts:595-610 SEARCH/READ allowlist + normalize）。
     * Java Tool 接口签名带 input 参数，CC 无参 → 忽略 input。
     */
    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return McpToolCollapseClassifier.classify(serverName, toolName);
    }

    /** CC MCPTool.ts:35 maxResultSizeChars = 100_000（Tool.java default 50_000 不适用 MCP）。 */
    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** CC client.ts:1972-1976 userFacingName(): `${client.name} - ${annotations?.title || tool.name} (MCP)`. */
    @Override
    public String userFacingName() {
        String displayName = toolName;
        if (annotations != null && annotations.path("title").isTextual()) {
            String title = annotations.path("title").asText();
            if (!title.isEmpty()) {
                displayName = title;
            }
        }
        return serverName + " - " + displayName + " (MCP)";
    }

    /**
     * CC MCPTool.ts:70-76 mapToolResultToToolResultBlockParam:
     * <pre>
     * { tool_use_id: toolUseID, type: 'tool_result', content }
     * </pre>
     * content 保留原始结果（execute 返回的 String 或 JsonNode），非额外字符串化
     * （对齐 CC string | ContentBlockParam[]）。
     *
     * <p><b>isError / data null 防御</b>：CC mapper 仅在成功路径被调
     * （{@code toolExecution.ts:1292-1295} 位于 {@code endToolExecutionSpan({success:true})}
     * (:1282) 之后），isError 或 data 为空时返回空 Map（对齐 SkillToolImpl:531 惯例）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerTool] mapToToolResultBlockParam 跳过: isError=true (CC mapper 仅成功路径被调 toolExecution.ts:1292-1295)");
            }
            return null;
        }
        Object data = result.data();
        if (data == null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerTool] mapToToolResultBlockParam 跳过: data=null");
            }
            return null;
        }
        // CC original: content（MCPTool.ts:75）—— string | ContentBlockParam[]。Java 端
        // data 为 JsonNode 块结构：数组逐块转 ContentBlockParam（@JsonSubTypes type 判别），
        // 文本/其他 JsonNode 字符串化兜底（G2 契约要求 ToolResultBlockParam.content 限
        // String | List<ContentBlockParam>）。
        Object content = data;
        if (data instanceof JsonNode node) {
            if (node.isArray() && node.size() > 0) {
                java.util.List<ContentBlockParam> blocks = new java.util.ArrayList<>();
                for (JsonNode item : node) {
                    try {
                        blocks.add(MAPPER.treeToValue(item, ContentBlockParam.class));
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug("[McpServerTool] 块转换跳过不可解析项: {}（整体回退字符串化）", e.getMessage());
                        }
                        blocks.clear();
                        break;
                    }
                }
                content = blocks.isEmpty() ? node.toString() : blocks;
            } else if (node.isTextual()) {
                content = node.asText();
            } else {
                content = node.toString();
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpServerTool] mapToToolResultBlockParam 完成: tool={} id={} content类型={}",
                mcpToolName, abbreviateId(toolUseId), content.getClass().getSimpleName());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /**
     * Tool 接口契约成员 override · 对齐 CC {@code Tool.ts:615 isResultTruncated?(output)} +
     * {@code MCPTool.ts:67-69 isResultTruncated(output) { return isOutputLineTruncated(output) }}
     * → {@code terminal.ts:119-133 isOutputLineTruncated(content: string)}。
     *
     * <p>算法与 CC 逐字一致：内容需多于 {@code MAX_LINES_TO_SHOW(3)} 个换行（占满 &gt; 3 行），
     * 且第 4 个换行后仍有内容（尾随换行是终止符不是新行，对齐 renderTruncatedContent 的 trimEnd）。
     * MCP 的 Output = {@code z.string()}（MCPTool.ts:20 outputSchema），故入参为 String。
     *
     * @param content 工具结果文本（MCP output 即 string）
     * @return true = 超 3 行截断语义命中（有更多内容可展开）
     */
    @Override
    public boolean isResultTruncated(String content) {
        if (content == null) {
            return false;
        }
        int pos = 0;
        for (int i = 0; i <= MAX_LINES_TO_SHOW; i++) {
            pos = content.indexOf('\n', pos);
            if (pos == -1) {
                return false;
            }
            pos++;
        }
        return pos < content.length();
    }

    /**
     * 1 参 execute 委托到 2 参（无 ctx）。旧 1 参派发路径无法取 sessionId/workspaceDir →
     * 不处理 audio 块，也不发射进度事件（2 参再委托 3 参，onProgress=null）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /** 2 参 execute 委托到 3 参（有 ctx、无进度回调）。 */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        return execute(call, ctx, null);
    }

    /**
     * 执行 MCP 工具调用 · 对齐 CC client.ts:1840-1936 call() + callMCPToolWithUrlElicitationRetry
     * （client.ts:2813-3027）+ transformMCPResult → processMCPResult（client.ts:2662-2706/:2720-2799）：
     * <ul>
     *   <li>C1 进度：started（调用前）/ completed（成功）/ failed（异常）·
     *       {@code {type:'mcp_progress', status, serverName, toolName, elapsedTimeMs}}</li>
     *   <li>[impl-I-4 T6] URL elicitation 重试包装：-32042 → 状态机两阶段；decline/cancel → decline 文本
     *       （fail-closed，前端未接线 auto-decline）</li>
     *   <li>[impl-I-4 T4/T5] 结果三件套：transformMCPResult（三分支分类 + inferCompactSchema +
     *       transformResultContent 全分支）→ processMCPResult（大结果截断/落盘 + MAX_MCP_OUTPUT_TOKENS）；
     *       audio/blob 落盘在 transformResultContent 内处理（替代旧 buildContent/transformAudioBlock）</li>
     *   <li>[MCP-I-9 Q-31] subagent 上下文抑制 mcpMeta · 对齐 CC toolExecution.ts:1464/1727</li>
     *   <li>[C3 残留 WF-D-O2] 请求侧 {@code claudecode/toolUseId} meta 需扩
     *       McpToolPool.callTool 请求参数，超 D1 允许范围（仅 wrapMcpTool 签名），登记残留</li>
     *   <li>[C2 已闭合 Q-11-5] session-expired 等价类型已补 {@link McpSessionExpiredException}
     *       （对齐 CC client.ts:161-170 McpSessionExpiredError）——HttpMcpTransport 识别
     *       404+-32001 置 transport CLOSED，McpToolPool.callTool 清连接缓存，下次调用经
     *       ensureConnectedClient 重建连接（对齐 CC clearServerCache → 重连）。[DIV-2 补齐]
     *       CC MAX_SESSION_RETRIES=1（client.ts:1859）同调用内重试已实现——callTool 捕获
     *       McpSessionExpiredException 清缓存后同一调用重试一次，重试仍失败抛原错误
     *       （对齐 client.ts:1913-1922）。</li>
     * </ul>
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx,
                                      Consumer<Tool.ToolProgress> onProgress) {
        String toolUseId = call.id();
        long startTime = System.currentTimeMillis();
        emitMcpProgress(onProgress, toolUseId, "started", null);
        try {
            // [MCP-I-9 Q-31] subagent 上下文抑制 mcpMeta · 对齐 CC toolExecution.ts:1464/1727
            //   (mcpMeta: toolUseContext.agentId ? undefined : mcpMeta). ToolRegistry 2 参派发
            //   (tool.execute(call, ctx)) 对生产轨 MCP 工具同样生效 — subagent loop 内 ctx.agentType()!=null
            //   → 抑制; 主链/ctx=null → 透传保留。判别基准同 AgentMcpTool (Java agentId 恒非空不可用)。
            boolean suppressMcpMeta = ctx != null && ctx.agentType() != null;
            JsonNode input = call.input();
            // input → Map for transport.sendRequest arguments
            Map<String, Object> args = MAPPER.convertValue(input,
                new TypeReference<Map<String, Object>>() {});
            // [impl-I-4 T6] URL elicitation 重试包装：-32042 → 状态机两阶段；decline/cancel → decline 文本
            // （fail-closed，前端未接线 auto-decline）。对齐 CC callMCPToolWithUrlElicitationRetry
            // （client.ts:2813-3027）。
            McpElicitationStateMachine.ElicitationOutcome outcome = pool.elicitationMachine()
                .callWithElicitationRetry(serverName, toolName,
                    () -> pool.callTool(serverName, toolName, args));
            if (outcome.declined()) {
                // [impl-I-4 F5 rework] CC callMCPToolWithUrlElicitationRetry 对 decline 返回
                // `{ content: "URL elicitation was declined..." }` —— 正常 content（isError 未设/非错误），
                // 不是错误结果。旧实现返回 ToolResult.error 与 CC 语义偏差 → 改返回正常 content
                // （makeMcpResult isError=false），LLM 拿到 decline 文本而非错误触发重试。
                log.info("[McpServerTool] {}.{} elicitation decline: {}", serverName, toolName,
                    outcome.declineMessage());
                return makeMcpResult(outcome.declineMessage(), null);
            }
            JsonNode result = outcome.result();
            boolean isError = result.path("isError").asBoolean(false);
            // [impl-I-4 T4/T5] 结果三件套：
            //   transformMCPResult（三分支分类 + inferCompactSchema + transformResultContent 全分支）
            //   → processMCPResult（大结果截断/落盘 + MAX_MCP_OUTPUT_TOKENS）。
            // 对齐 CC mcp/client.ts:2662-2706 + :2720-2799。
            Path workspaceDir = (ctx != null && ctx.effectiveCwd() != null)
                ? ctx.effectiveCwd() : null;
            String sessionId = (ctx != null && ctx.sessionId() != null) ? ctx.sessionId() : null;
            McpResultTransformer.TransformContext transformCtx =
                new McpResultTransformer.TransformContext(workspaceDir, sessionId);
            McpResultTransformer.TransformedMCPResult transformed =
                McpResultTransformer.transformMCPResult(result, toolName, serverName, transformCtx);
            String content = McpOutputProcessor.processMCPResult(transformed, toolName, serverName,
                transformCtx, pool.maxMcpOutputTokens());
            long elapsed = System.currentTimeMillis() - startTime;
            emitMcpProgress(onProgress, toolUseId, "completed", elapsed);
            // [MERGE-REWORK] data 载体判定 · 对齐 CC MCPToolResult = string | ContentBlockParam[]
            // （mcpValidation.ts:49）：非截断 contentArray → 块数组 JsonNode；截断/落盘/fallback 或
            // toolResult/structuredContent → String。CC 唯一事实来源（client.ts:2720-2799 原样返回
            // 未截断 content）。
            Object data = resolveData(transformed, content);
            log.info("[McpServerTool] {}.{} → isError={} dataType={} contentLen={}",
                serverName, toolName, isError, data.getClass().getSimpleName(), content.length());
            // [A1·对齐 CC mcp/client.ts:1899-1902] MCP 工具结果填充 mcpMeta (_meta + structuredContent),
            // SDK 消费者透传, never sent to model (messages.ts:483).
            // [MCP-I-9 Q-31] subagent 上下文抑制 mcpMeta（对齐 CC toolExecution.ts:1464）·
            //   旧 residual「无法判别 subagent → 全量透传」已按 agentType 判别落地。
            ToolResult.McpMeta mcpMeta = suppressMcpMeta ? null : buildMcpMeta(result);
            if (log.isDebugEnabled()) {
                log.debug("MCP mcpMeta {}: tool={} id={} hasMeta={} hasStructured={}",
                    suppressMcpMeta ? "抑制 (subagent, Q-31)" : "透传",
                    mcpToolName, abbreviateId(toolUseId),
                    mcpMeta != null && mcpMeta.meta() != null && !mcpMeta.meta().isEmpty(),
                    mcpMeta != null && mcpMeta.structuredContent() != null);
            }
            return makeMcpResult(data, mcpMeta);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            emitMcpProgress(onProgress, toolUseId, "failed", elapsed);
            // [S03 R2-03 △-7] 调用期 401 识别：transport 401（HttpMcpTransport:193 /
            // SseMcpTransport:248 / WsMcpTransport:339 已抛 McpAuthError）经
            // McpElicitationStateMachine.join()/rethrow() 后以原始 McpAuthError 或
            // CompletionException 包装到达此处 → 解包判定。命中 → needs-auth 标记 +
            // 伪工具替换（McpToolPool.markServerNeedsAuth 收敛点，与连接期统一），
            // 错误文本对齐 CC client.ts:3204-3207（McpAuthError message）。旧路径
            // 「catch-all 吞 401 为 'MCP call failed: ' 普通 error 文本」已删除
            // （D-S03-1，脏代码：401 不标记 needs-auth、不更新连接状态、不产伪工具）。
            Throwable cause = e instanceof CompletionException ce && ce.getCause() != null
                ? ce.getCause() : e;
            if (cause instanceof McpAuthError authErr) {
                pool.markServerNeedsAuth(serverName);
                if (log.isDebugEnabled()) {
                    log.debug("[McpServerTool] {}.{} 401 认证失败 → needs-auth 标记 + 伪工具替换: {}",
                        serverName, toolName, authErr.getMessage());
                }
                return ToolResult.error(toolUseId, authErr.getMessage());
            }
            log.warn("[McpServerTool] {}.{} 调用失败(耗时{}ms): {}",
                serverName, toolName, elapsed,
                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return ToolResult.error(toolUseId, "MCP call failed: " + e.getMessage());
        }
    }

    /**
     * C1 进度事件发射 · 对齐 CC client.ts:1846-1856/:1884-1895/:1925-1936：
     * <pre>
     * { toolUseID, data: { type:'mcp_progress', status:'started'|'completed'|'failed',
     *   serverName, toolName, elapsedTimeMs? } }
     * </pre>
     * 进度通道：StreamingToolExecutor:1540 三参 dispatch → wrappedCallback 入队
     * pendingProgress（R32-b15 C8），LlmAgentLoop 注入 onProgress 时再透传。
     *
     * @param onProgress 进度回调（可为 null，null 则跳过发射）
     * @param toolUseId  工具调用 ID
     * @param status     started / completed / failed
     * @param elapsedMs  已耗时 ms（started 为 null）
     */
    private void emitMcpProgress(Consumer<Tool.ToolProgress> onProgress, String toolUseId,
                                 String status, Long elapsedMs) {
        if (onProgress == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "mcp_progress");
        data.put("status", status);
        data.put("serverName", serverName);
        data.put("toolName", toolName);
        if (elapsedMs != null) {
            data.put("elapsedTimeMs", elapsedMs);
        }
        onProgress.accept(new Tool.ToolProgress(toolUseId, data));
        if (log.isDebugEnabled()) {
            log.debug("MCP 进度事件: tool={} id={} status={} 耗时={}ms",
                mcpToolName, abbreviateId(toolUseId), status, elapsedMs);
        }
    }

    /**
     * [A1·对齐 CC mcpMeta] 构造 MCP 透传元数据: _meta (Map) + structuredContent (JsonNode).
     * MCP tools/call result 形如 {content, isError, _meta?, structuredContent?}.
     */
    private static ToolResult.McpMeta buildMcpMeta(JsonNode result) {
        JsonNode metaNode = result.path("_meta");
        Map<String, Object> meta = metaNode.isMissingNode() || metaNode.isNull()
            ? null
            : MAPPER.convertValue(metaNode, new TypeReference<Map<String, Object>>() {});
        JsonNode structuredContent = result.path("structuredContent");
        if ((meta == null || meta.isEmpty()) && (structuredContent.isMissingNode() || structuredContent.isNull())) {
            return null;
        }
        return new ToolResult.McpMeta(meta, structuredContent.isMissingNode() || structuredContent.isNull() ? null : structuredContent);
    }

    /** 构造带 mcpMeta 的 success/error ToolResult<Object>（data = String 或 contentArray 块数组 JsonNode）·
     *  对齐 CC MCPToolResult = string | ContentBlockParam[]（mcpValidation.ts:49）。
     *  [IMP-C2] toolUseId/isError 由 mapper 推导（组 2-1 拍板），参数已删除。 */
    private static ToolResult<Object> makeMcpResult(Object content, ToolResult.McpMeta mcpMeta) {
        return new ToolResult<>(content, null, null, mcpMeta);
    }

    /**
     * 结果载体判定 · 对齐 CC {@code MCPToolResult = string | ContentBlockParam[]}（mcpValidation.ts:49）。
     *
     * <p>{@link McpOutputProcessor#processMCPResult} 仅在「未截断 / ide 直返」路径原样返回
     * {@code transformed.content()}（client.ts:2725-2727 / :2730-2734）；此时若为 contentArray 分支
     * （contentNode != null），CC 返回块数组而非字符串（transformMCPResult :2689 content: ContentBlockParam[]）。
     * 截断/含图片截断/落盘/persist 失败（content 已被改写）或 toolResult/structuredContent → 返回 String。
     *
     * @param transformed transformMCPResult 产物
     * @param processed   processMCPResult 返回的 content
     * @return 块数组 JsonNode（非截断 contentArray）或 String（其余）
     */
    private static Object resolveData(McpResultTransformer.TransformedMCPResult transformed, String processed) {
        if (transformed.contentNode() != null && processed != null && processed.equals(transformed.content())) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerTool] contentArray 非截断 → data 返回块数组 JsonNode（对齐 CC MCPToolResult）");
            }
            return transformed.contentNode();
        }
        return processed;
    }

    /** 提取 readOnlyHint（CC client.ts:1795-1799 两处共用）。 */
    private boolean readOnlyHint() {
        return annotations != null ? annotations.path("readOnlyHint").asBoolean(false) : false;
    }

    /**
     * CC client.ts:1779-1783 searchHint 提取：string → 空白折叠 + trim；空串 → null。
     * 非 string（外部 MCP server 可给任意类型）→ null。
     */
    private static String extractSearchHint(JsonNode meta) {
        if (meta == null || meta.isNull() || meta.isMissingNode()) {
            return null;
        }
        JsonNode hint = meta.get("anthropic/searchHint");
        if (hint == null || !hint.isTextual()) {
            return null;
        }
        String collapsed = collapseWhitespace(hint.asText()).trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /**
     * 空白折叠 · 等价 CC {@code .replace(/\s+/g, ' ')} (client.ts:1781)。
     *
     * <p><b>WHY 手写而非正则 {@code \\s+}</b>：Java 25 起 {@code "\s"} 字符串转义语义改变
     * （{@code \s} 编译为空格字符，正则 {@code \s} 不再匹配换行/制表），用
     * {@link Character#isWhitespace(char)} 逐字符折叠与 JS {@code \s} 行为一致
     * （换行/制表/CR/Unicode 空白均折叠为单个空格）。
     */
    private static String collapseWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString();
    }

    /**
     * JS {@code String(value)} 语义强转（CC client.ts:1736 ${String(input[k])}）：
     * 对象→[object Object]，数组→元素逗号拼接（嵌套递归），null→"null"，文本/数字/布尔→自身。
     */
    private static String jsString(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(jsString(node.get(i)));
            }
            return sb.toString();
        }
        if (node.isObject()) {
            return "[object Object]";
        }
        return "null";
    }

    private static String abbreviateId(String id) {
        return id == null ? "null" : (id.length() <= 24 ? id : id.substring(0, 24));
    }
}

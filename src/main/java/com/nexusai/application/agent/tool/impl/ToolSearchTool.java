package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolReferenceBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ToolSearchTool — 对齐 CC {@code ToolSearchTool.ts}（检索算法全套）。
 *
 * <p>检索语义（H1 对齐范围）：
 * <ul>
 *   <li>{@code select:} 逗号多选（含 partial missing，CC :358-406）</li>
 *   <li>精确名 fast-path（deferred 优先回退全集，CC :194-204）+ {@code mcp__} 前缀（:208-216）</li>
 *   <li>keyword 分区（required {@code +term} / optional）+ 打分
 *       （part 10/12 · subpart 5/6 · full 3 · searchHint 4 · desc 2，CC :259-295）+ 词边界 regex（:167-175）</li>
 *   <li>description(prompt) memoize + deferred 工具集变化失效（CC :66-100）</li>
 *   <li>输出经 {@link #mapToToolResultBlockParam}：非空 matches → {@code tool_reference} blocks；
 *       空结果 → 纯文本 + pending MCP 提示（CC :444-470）</li>
 * </ul>
 *
 * <p>契约：isConcurrencySafe=true（CC :308）、isReadOnly=true（:311）、
 * maxResultSizeChars=100_000（:315）、userFacingName=''（:438）、renderToolUseMessage=null（:435-437）、
 * description()/prompt()=getPrompt()（:316-321，三段逐字 prompt.ts:119-121）。
 */
@Component
public class ToolSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchTool.class);

    // ═══════════════════════════════════════════════════════════════════════
    // getPrompt() 三段（CC prompt.ts:119-121 = PROMPT_HEAD + getToolLocationHint() + PROMPT_TAIL）
    // ═══════════════════════════════════════════════════════════════════════
    /** CC PROMPT_HEAD（prompt.ts:27-29）逐字。 */
    private static final String PROMPT_HEAD = "Fetches full schema definitions for deferred tools so they can be called.\n\n";

    /** CC PROMPT_TAIL（prompt.ts:44-51）逐字（含前导空格）。 */
    private static final String PROMPT_TAIL = " Until fetched, only the name is known — there is no parameter schema, so the tool cannot be invoked. This tool takes a query, matches it against the deferred tool list, and returns the matched tools' complete JSONSchema definitions inside a <functions> block. Once a tool's schema appears in that result, it is callable exactly like any tool defined at the top of the prompt.\n\nResult format: each matched tool appears as one <function>{\"description\": \"...\", \"name\": \"...\", \"parameters\": {...}}</function> line inside the <functions> block — the same encoding as the tool list at the top of this prompt.\n\nQuery forms:\n- \"select:Read,Edit,Grep\" — fetch these exact tools by name\n- \"notebook jupyter\" — keyword search, up to max_results best matches\n- \"+slack send\" — require \"slack\" in the name, rank by remaining terms";

    /** {@code select:} 前缀 · 对齐 CC {@code query.match(/^select:(.+)$/i)}（ToolSearchTool.ts:363）。 */
    private static final Pattern SELECT_PATTERN = Pattern.compile("^select:(.+)$", Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════════════════
    // description(prompt) memoize + 失效（CC ToolSearchTool.ts:49-105）
    // ═══════════════════════════════════════════════════════════════════════
    /** 按工具名 memoize 的描述（prompt）缓存 · 对齐 CC getToolDescriptionMemoized（ToolSearchTool.ts:66-86）。 */
    private final ConcurrentHashMap<String, String> toolDescriptionCache = new ConcurrentHashMap<>();
    /** 缓存失效基线：deferred 工具名集合签名 · 对齐 CC cachedDeferredToolNames（ToolSearchTool.ts:50）。 */
    private volatile String cachedDeferredToolNames;

    /** 无参构造器（Spring 组件 / R32B15_SafeParseTest 依赖）。 */
    public ToolSearchTool() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 契约方法（CC ToolSearchTool.ts:304-327）
    // ═══════════════════════════════════════════════════════════════════════

    /** 工具名 · 对齐 CC constants.ts:1 TOOL_SEARCH_TOOL_NAME='ToolSearch'。 */
    @Override
    public String name() {
        return ToolNameConstants.TOOL_SEARCH_TOOL_NAME;
    }

    /** 描述 · 对齐 CC ToolSearchTool.ts:316-318（getPrompt() 三段逐字）。 */
    @Override
    public String description() {
        return getPrompt();
    }

    /** 提示词 · 对齐 CC ToolSearchTool.ts:319-321（getPrompt()）。 */
    @Override
    public String prompt() {
        return getPrompt();
    }

    /** 并发安全 · CC ToolSearchTool.ts:308-310 isConcurrencySafe()=true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读 · CC ToolSearchTool.ts:311-313 isReadOnly()=true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 是否启用 · 对齐 CC ToolSearchTool.ts:305-306 isEnabled()=isToolSearchEnabledOptimistic()。
     *  <p>feature 关闭（mode=standard）→ false → ToolRegistry isEnabled 守卫（:414/:553/:598）
     *  将 ToolSearch 从 LLM 工具列表过滤（CC tools.ts:249 注册门控，A48）。 */
    @Override
    public boolean isEnabled() {
        return ToolSearchService.isToolSearchEnabledOptimistic();
    }

    /** 结果落盘阈值 · CC ToolSearchTool.ts:315 maxResultSizeChars=100_000。 */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /** 用户可见名 · CC ToolSearchTool.ts:438 userFacingName: () => ''。 */
    @Override
    public String userFacingName() {
        return "";
    }

    /** 工具使用消息 · CC ToolSearchTool.ts:435-437 renderToolUseMessage() 显式返回 null（与 Tool.java:692 默认 null 等价，显式 override 文档化）。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        return null;
    }

    /**
     * [IT-5] 未知键运行时策略 = STRIP · 对齐 CC ToolSearchTool.ts:21-33
     * {@code inputSchema = lazySchema(() => z.object({query, max_results?}))} ——
     * z.object 默认 strip 未知键（safeParse 不报 unrecognized_keys）。
     */
    @Override
    public Tool.UnknownKeysPolicy unknownKeysPolicy() {
        return Tool.UnknownKeysPolicy.STRIP;
    }

    /** 输入 schema · CC ToolSearchTool.ts:21-33（query 必填 + max_results 默认 5，无 [1,50] 钳制——对齐 CC）。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description",
            "Query to find deferred tools. Use \"select:<tool_name>\" for direct selection, or keywords to search.");
        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("default", 5);
        maxResults.put("description", "Maximum number of results to return (default: 5)");
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 输出 schema · CC ToolSearchTool.ts:37-44 outputSchema={matches[], query, total_deferred_tools, pending_mcp_servers?}。 */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode matches = props.putObject("matches");
        matches.put("type", "array");
        matches.putArray("items").addObject().put("type", "string");
        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        ObjectNode total = props.putObject("total_deferred_tools");
        total.put("type", "integer");
        ObjectNode pending = props.putObject("pending_mcp_servers");
        pending.put("type", "array");
        pending.putArray("items").addObject().put("type", "string");
        schema.putArray("required").add("matches").add("query").add("total_deferred_tools");
        schema.put("additionalProperties", false);
        return schema;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // execute（CC ToolSearchTool.ts:328-434）
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String query = input != null && input.has("query") ? input.get("query").asText() : null;
        if (query == null || query.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: query");
        }
        int maxResults = input != null && input.has("max_results") && input.get("max_results").isInt()
                ? input.get("max_results").asInt(5)
                : 5;

        // CC 工具全集来自 call 上下文 options.tools；Java 端取 ctx.availableTools（单参路径为 null → 降级）。
        List<Tool> allTools = ctx != null && ctx.availableTools() != null
                ? ctx.availableTools() : List.of();
        if (allTools.isEmpty() && log.isDebugEnabled()) {
            log.debug("[ToolSearchTool] ctx 无可用工具（单参 execute 路径）→ select/fast-path 降级（CC 依赖 options.tools 全集）");
        }
        List<Tool> deferredTools = allTools.stream()
                .filter(ToolSearchTool::isDeferredTool)
                .collect(Collectors.toList());
        maybeInvalidateCache(deferredTools);

        // ── select: 前缀 —— 直接工具选择（CC ToolSearchTool.ts:358-406）──
        var selectMatch = SELECT_PATTERN.matcher(query);
        if (selectMatch.matches()) {
            List<String> requested = Arrays.stream(selectMatch.group(1).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String toolName : requested) {
                Tool tool = findToolByName(deferredTools, toolName);
                if (tool == null) {
                    tool = findToolByName(allTools, toolName);
                }
                if (tool != null) {
                    if (!found.contains(tool.name())) {
                        found.add(tool.name());
                    }
                } else {
                    missing.add(toolName);
                }
            }

            if (found.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ToolSearchTool] select 失败 — 全部未命中: {}（CC ToolSearchTool.ts:384-386）",
                            String.join(", ", missing));
                }
                logSearchOutcome(found, "select", deferredTools.size(), maxResults, query);
                return ToolResult.success(call.id(), new ToolSearchOutput(
                        found, query, deferredTools.size(), getPendingServerNames(ctx)));
            }
            if (!missing.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ToolSearchTool] 部分 select — 命中: {}, 缺失: {}（CC ToolSearchTool.ts:397-400）",
                            String.join(", ", found), String.join(", ", missing));
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[ToolSearchTool] 已选择 {}（CC ToolSearchTool.ts:402）", String.join(", ", found));
            }
            logSearchOutcome(found, "select", deferredTools.size(), maxResults, query);
            // [activate-on-search] ToolSearch 确认 → 激活匹配工具（开关开时进 API tools，下轮可调用）
            java.util.List<String> activated = ToolSearchService.activateTools(found);
            // [R8] 渲染零分支（CC ToolSearchTool.ts:462-469）：命中恒产纯 tool_reference 块 + （activate
            //   模式激活时的）激活提示，无 provider/model 分流。
            return ToolResult.success(call.id(), buildSearchOutput(
                    found, query, deferredTools.size(), activated));
        }

        // ── 关键词检索（CC ToolSearchTool.ts:409-414）──
        List<String> matches = searchToolsWithKeywords(query, deferredTools, allTools, maxResults);
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearchTool] 关键词检索 \"{}\"，命中 {} 条（CC ToolSearchTool.ts:416-418）",
                    query, matches.size());
        }
        logSearchOutcome(matches, "keyword", deferredTools.size(), maxResults, query);

        // 无命中时带上 pending MCP 服务器信息（CC ToolSearchTool.ts:422-431）
        if (matches.isEmpty()) {
            return ToolResult.success(call.id(), new ToolSearchOutput(
                    matches, query, deferredTools.size(), getPendingServerNames(ctx)));
        }
        // [activate-on-search] ToolSearch 确认 → 激活匹配工具（开关开时进 API tools，下轮可调用）
        java.util.List<String> activated = ToolSearchService.activateTools(matches);
        // [R8] 渲染零分支（同上 select 分支）
        return ToolResult.success(call.id(), buildSearchOutput(
                matches, query, deferredTools.size(), activated));
    }

    /**
     * [R8 · 回归 CC ToolSearchTool.ts:462-469 渲染零分支] ToolSearch 命中输出构造：
     * 仅 matches（渲染为纯 {@code tool_reference} 块，API 客户端展开为完整工具）+ activate 模式
     * 激活时的提示文本。原 [openai-lazy 乙-1] 的 anthropic/非 anthropic provider 分流分支已随 R8
     * 删除（provider 判据现于主循环门控单点，渲染恒无分支）。
     */
    private static ToolSearchOutput buildSearchOutput(
            java.util.List<String> matches, String query, int totalDeferredTools,
            java.util.List<String> activated) {
        return new ToolSearchOutput(matches, query, totalDeferredTools, null, activatedNotice(activated));
    }

    /**
     * 激活提示文本（模型可见）· 用户拍板（2026-09-01）：激活模式必须有提示——「工具已激活放入
     * 工具集、可直接调用」，否则模型/用户不知道工具已从 defer 变为可用。
     */
    private static String activatedNotice(java.util.List<String> activated) {
        if (activated == null || activated.isEmpty()) {
            return null;
        }
        return "Tools activated into the tool set, callable directly now: " + String.join(", ", activated);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // mapToToolResultBlockParam（CC ToolSearchTool.ts:439-470）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * tool_result 输出 · 对齐 CC mapToolResultToToolResultBlockParam（ToolSearchTool.ts:444-470）。
     * 非空 matches → {@code tool_reference} 块数组；空结果 → 纯文本 + pending MCP 提示。
     * 该格式在 1P/Foundry 上可被客户端展开（Bedrock/Vertex 可能不支持，CC :439-443）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        // CC mapper 仅成功路径被调（toolExecution.ts:1292-1295）；isError → 交回默认兜底
        if (result == null || isError) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchTool] mapToToolResultBlockParam 跳过: isError=true（CC mapper 仅成功路径 toolExecution.ts:1292-1295）");
            }
            return null;
        }
        Object rawData = result.data();
        if (!(rawData instanceof ToolSearchOutput out)) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchTool] mapToToolResultBlockParam 跳过: data 非 ToolSearchOutput，类型={}（CC Output union 保证合法）",
                        rawData == null ? "null" : rawData.getClass().getSimpleName());
            }
            return null;
        }
        if (out.matches().isEmpty()) {
            // 空结果 → 纯文本（CC ToolSearchTool.ts:448-461）
            String text = "No matching deferred tools found";
            if (out.pendingMcpServers() != null && !out.pendingMcpServers().isEmpty()) {
                text += ". Some MCP servers are still connecting: " + String.join(", ", out.pendingMcpServers())
                        + ". Their tools will become available shortly — try searching again.";
            }
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchTool] 空结果 text 输出: pendingMcpServers={}（CC ToolSearchTool.ts:448-461）",
                        out.pendingMcpServers() == null ? 0 : out.pendingMcpServers().size());
            }
            return new ToolResultBlockParam(toolUseId, "tool_result", text, false);
        }
        // 命中 → tool_reference blocks（CC ToolSearchTool.ts:462-469）· Anthropic 客户端展开语义。
        //   [R8] 渲染零分支：不再按 provider 追加 <functions> JSONSchema 文本（原 [openai-lazy 乙-1] 扩展
        //   随 R8 删除 —— 非 anthropic 已由主循环门控排除 ToolSearch，无「拿到 schema 才能调用」场景）。
        List<ContentBlockParam> blocks = out.matches().stream()
                .map(ToolReferenceBlockParam::new)
                .collect(Collectors.toList());
        // 激活提示（activate-on-search 开启且本次确认激活）· 用户拍板「激活必有提示」
        String notice = out.activatedNotice();
        if (notice != null && !notice.isBlank()) {
            blocks.add(new ContentBlockParam.TextBlockParam(notice));
        }
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearchTool] 命中 {} 条 → tool_reference blocks {} 个 + 激活提示 {}（CC ToolSearchTool.ts:462-469 渲染零分支）",
                    out.matches().size(), out.matches().size(), notice != null);
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", blocks, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 检索算法（CC ToolSearchTool.ts:110-302）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 检索输出结构 · 对齐 CC Output（ToolSearchTool.ts:47），经 {@link #mapToToolResultBlockParam} 序列化。包可见供测试断言。
     *
     * <p>[R8] 原 [openai-lazy 乙-1] 的 {@code schemas} 字段（完整 JSONSchema，非 anthropic 死锁防护）已随
     * provider 渲染分支删除；{@code activatedNotice} 为 activate 模式独立特性（激活提示文本），保留。
     */
    record ToolSearchOutput(
            List<String> matches,
            String query,
            int totalDeferredTools,
            List<String> pendingMcpServers,
            String activatedNotice) {
        /** 4 参便利构造（测试/无激活提示场景 → 无激活提示；[R8] 原 schemas 字段已删）。 */
        ToolSearchOutput(List<String> matches, String query, int totalDeferredTools, List<String> pendingMcpServers) {
            this(matches, query, totalDeferredTools, pendingMcpServers, null);
        }
    }

    /** parseToolName 返回值 · CC ToolSearchTool.ts:132-136。 */
    private record ParsedToolName(List<String> parts, String full, boolean isMcp) {}

    /**
     * 工具名解析为可检索部分 · 对齐 CC ToolSearchTool.ts:132-161。
     * MCP 工具（mcp__server__action）→ 去前缀后按 {@code __}/{@code _} 切分；常规工具按 CamelCase + 下划线切分。
     */
    private static ParsedToolName parseToolName(String name) {
        if (name.startsWith("mcp__")) {
            String withoutPrefix = name.substring("mcp__".length()).toLowerCase(Locale.ROOT);
            List<String> parts = new ArrayList<>();
            for (String seg : withoutPrefix.split("__")) {
                for (String p : seg.split("_")) {
                    if (!p.isEmpty()) {
                        parts.add(p);
                    }
                }
            }
            String full = withoutPrefix.replace("__", " ").replace("_", " ");
            return new ParsedToolName(parts, full, true);
        }
        List<String> parts = new ArrayList<>();
        for (String p : name.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace("_", " ")
                .toLowerCase(Locale.ROOT)
                .split("\\s+")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return new ParsedToolName(parts, String.join(" ", parts), false);
    }

    /** 词边界 regex 预编译 · 对齐 CC compileTermPatterns（ToolSearchTool.ts:167-175，\b + escapeRegExp → Pattern.quote）。 */
    private static Map<String, Pattern> compileTermPatterns(List<String> terms) {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String term : terms) {
            patterns.computeIfAbsent(term, t -> Pattern.compile("\\b" + Pattern.quote(t) + "\\b"));
        }
        return patterns;
    }

    /** 按名称/别名查找工具 · 对齐 CC findToolByName（Tool.ts:348-360，name+aliases 大小写敏感精确匹配）。 */
    private static Tool findToolByName(List<Tool> tools, String name) {
        for (Tool t : tools) {
            if (t.name().equals(name) || t.aliases().contains(name)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 工具描述（prompt）memoize · 对齐 CC getToolDescriptionMemoized（ToolSearchTool.ts:66-86）。
     * CC 用 {@code tool.prompt({...})}（非 description()）；Java 端对齐用 {@code tool.prompt()}，null → ""。
     */
    private String getToolDescriptionMemoized(String toolName, List<Tool> tools) {
        String cached = toolDescriptionCache.get(toolName);
        if (cached != null) {
            return cached;
        }
        Tool tool = findToolByName(tools, toolName);
        String value = "";
        if (tool != null) {
            String prompt = tool.prompt();
            value = prompt == null ? "" : prompt;
        }
        toolDescriptionCache.put(toolName, value);
        return value;
    }

    /** 失效缓存 · 对齐 CC maybeInvalidateCache（ToolSearchTool.ts:91-100，deferred 工具集签名变化时 clear）。 */
    private void maybeInvalidateCache(List<Tool> deferredTools) {
        String currentKey = deferredTools.stream()
                .map(Tool::name)
                .sorted()
                .collect(Collectors.joining(","));
        if (!currentKey.equals(cachedDeferredToolNames)) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchTool] 描述缓存已失效 - deferred 工具集变化（CC ToolSearchTool.ts:91-100）");
            }
            toolDescriptionCache.clear();
            cachedDeferredToolNames = currentKey;
        }
    }

    /**
     * 清空描述缓存 + 基线 · 对齐 CC {@code clearToolSearchDescriptionCache}
     * （ToolSearchTool.ts:102-105 + commands/clear/caches.ts:134-138）。
     *
     * <p>/clear 会话重置时调用（CommandController /clear 分支接线），强制下轮 search 重新
     * 抓取工具完整 prompt 描述（CC caches.ts:134-138 注释 "full tool prompts, ~500KB for 50 MCP
     * tools"），避免 MCP server 重启后 schema 变更被旧缓存掩盖。
     */
    public void clearToolSearchDescriptionCache() {
        toolDescriptionCache.clear();
        cachedDeferredToolNames = null;
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearchTool] clearToolSearchDescriptionCache: 描述缓存+基线已清空（对齐 CC ToolSearchTool.ts:102-105）");
        }
    }

    /**
     * 关键词检索 · 对齐 CC searchToolsWithKeywords（ToolSearchTool.ts:186-302）。
     * 快路径（精确名）→ mcp__ 前缀 → required/optional 分区 → 打分（part 10/12·subpart 5/6·full 3·hint 4·desc 2）→ 排序截断。
     */
    private List<String> searchToolsWithKeywords(
            String query, List<Tool> deferredTools, List<Tool> tools, int maxResults) {
        String queryLower = query.toLowerCase(Locale.ROOT).trim();

        // 快路径：精确名命中直接返回（CC ToolSearchTool.ts:194-204，deferred 优先回退全集）
        Tool exactMatch = deferredTools.stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).equals(queryLower))
                .findFirst()
                .orElseGet(() -> tools.stream()
                        .filter(t -> t.name().toLowerCase(Locale.ROOT).equals(queryLower))
                        .findFirst().orElse(null));
        if (exactMatch != null) {
            return List.of(exactMatch.name());
        }

        // mcp__ 前缀：按 server 名找工具（CC ToolSearchTool.ts:208-216）
        if (queryLower.startsWith("mcp__") && queryLower.length() > 5) {
            List<String> prefixMatches = deferredTools.stream()
                    .filter(t -> t.name().toLowerCase(Locale.ROOT).startsWith(queryLower))
                    .limit(maxResults)
                    .map(Tool::name)
                    .collect(Collectors.toList());
            if (!prefixMatches.isEmpty()) {
                return prefixMatches;
            }
        }

        List<String> queryTerms = Arrays.stream(queryLower.split("\\s+"))
                .filter(term -> term.length() > 0)
                .collect(Collectors.toList());

        // 分区：required（+ 前缀）与 optional（CC ToolSearchTool.ts:220-229）
        List<String> requiredTerms = new ArrayList<>();
        List<String> optionalTerms = new ArrayList<>();
        for (String term : queryTerms) {
            if (term.startsWith("+") && term.length() > 1) {
                requiredTerms.add(term.substring(1));
            } else {
                optionalTerms.add(term);
            }
        }

        List<String> allScoringTerms = requiredTerms.isEmpty()
                ? queryTerms
                : Stream.concat(requiredTerms.stream(), optionalTerms.stream())
                        .collect(Collectors.toList());
        Map<String, Pattern> termPatterns = compileTermPatterns(allScoringTerms);

        // 预过滤：name/描述必须命中全部 required 词（CC ToolSearchTool.ts:235-257）
        List<Tool> candidateTools = deferredTools;
        if (!requiredTerms.isEmpty()) {
            candidateTools = deferredTools.stream().filter(tool -> {
                ParsedToolName parsed = parseToolName(tool.name());
                String descNormalized = getToolDescriptionMemoized(tool.name(), tools).toLowerCase(Locale.ROOT);
                String hintNormalized = tool.searchHint() == null ? "" : tool.searchHint().toLowerCase(Locale.ROOT);
                return requiredTerms.stream().allMatch(term -> {
                    Pattern pattern = termPatterns.get(term);
                    return parsed.parts().contains(term)
                            || parsed.parts().stream().anyMatch(part -> part.contains(term))
                            || pattern.matcher(descNormalized).find()
                            || (!hintNormalized.isEmpty() && pattern.matcher(hintNormalized).find());
                });
            }).collect(Collectors.toList());
        }

        // 打分（CC ToolSearchTool.ts:259-295）
        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        for (Tool tool : candidateTools) {
            ParsedToolName parsed = parseToolName(tool.name());
            String descNormalized = getToolDescriptionMemoized(tool.name(), tools).toLowerCase(Locale.ROOT);
            String hintNormalized = tool.searchHint() == null ? "" : tool.searchHint().toLowerCase(Locale.ROOT);

            int score = 0;
            for (String term : allScoringTerms) {
                Pattern pattern = termPatterns.get(term);

                // 精确 part 命中（MCP server 名 / 工具名部分高权重，CC :270-275）
                if (parsed.parts().contains(term)) {
                    score += parsed.isMcp() ? 12 : 10;
                } else if (parsed.parts().stream().anyMatch(part -> part.contains(term))) {
                    score += parsed.isMcp() ? 6 : 5;
                }

                // 全名兜底（边角情况，score 仍为 0 时，CC :277-280）
                if (parsed.full().contains(term) && score == 0) {
                    score += 3;
                }

                // searchHint 命中 —— 策展能力短语，信号高于 prompt（CC :282-285）
                if (!hintNormalized.isEmpty() && pattern.matcher(hintNormalized).find()) {
                    score += 4;
                }

                // 描述命中 —— 用词边界避免误报（CC :287-290）
                if (pattern.matcher(descNormalized).find()) {
                    score += 2;
                }
            }
            if (score > 0) {
                scored.add(Map.entry(tool.name(), score));
            }
        }

        return scored.stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 私有辅助（deferred / pending MCP / analytics / getPrompt）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 是否 deferred 工具 · 委托共享服务 {@link ToolSearchService#isDeferredTool(Tool, JsonNode)}
     * （对齐 CC isDeferredTool prompt.ts:62-108 全 7 规则，FORK/KAIROS 分支 N/A 登记）。
     * <p>检索场景无调用输入 → input 传 null（Java shouldDefer(null) 语义与既有实现一致）。
     */
    private static boolean isDeferredTool(Tool tool) {
        return ToolSearchService.isDeferredTool(tool, null);
    }

    /**
     * pending MCP 服务器名 · 对齐 CC getPendingServerNames（ToolSearchTool.ts:335-339，
     * {@code appState.mcp.clients.filter(c => c.type === 'pending')}）。
     *
     * <p><b>【OPD-TS-09-06 受控残留 · fail loud】</b> Java 运行时无 pending 连接状态生产者：
     * <ul>
     *   <li>{@code ToolUseContext.mcpClients()} 为 {@code Map<String, McpClientRuntime>}
     *       （[IMP-E1 DC-2] McpServerInfo 收敛 {serverName,toolName}，instructions 由 McpClientRuntime 承载），
     *       无连接状态/type 字段；</li>
     *   <li>{@code ToolUseContext.mcpServerConnections()}（Q-30 连接继承）为
     *       {@code List<AgentMcpServers.McpServerConnection>}，该接口仅 {@code name()/getTools()/cleanup()}
     *       无 {@code type} 判别（且连接经 {@code connectToServer} 同步 {@code .join()}，失败即丢弃，
     *       无 pending 中间态）；</li>
     *   <li>{@code McpTypesRegistry.MCPCliState/PendingMCPServer/SerializedClient} 已注册 CC 等价类型，
     *       但全仓无运行时 producer 填充（grep 仅 McpTypesRegistry 自身引用）。</li>
     * </ul>
     * → 恒返回 null（无 pending 可报），以 fail-loud debug 日志显式登记，不谎报 wiring 接通。
     * 若后续 MCP 连接生命周期建立 pending 状态生产者，改为读该源 {@code type='pending'} 的 name 列表。
     */
    private static List<String> getPendingServerNames(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearchTool] pending MCP 服务器通道读证（OPD-TS-09-06 受控残留）: "
                + "Java 无 pending 连接状态生产者（McpServerConnection 无 type 字段 / MCPCliState 无 producer）"
                + "→ 恒返回 null（CC ToolSearchTool.ts:335-339）");
        }
        return null;
    }

    /**
     * 检索结果 analytics 日志 · 对齐 CC logSearchOutcome（ToolSearchTool.ts:342-356，
     * 事件名 tengu_tool_search_outcome）。
     * <p>【OPD-H-02】Java 无 analytics 事件通道 → 保留事件名结构，以 slf4j debug 等价承载
     * （queryType/matchCount/totalDeferredTools/maxResults/hasMatches/query）。
     */
    private static void logSearchOutcome(
            List<String> matches, String queryType, int totalDeferredTools, int maxResults, String query) {
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearchTool] analytics tengu_tool_search_outcome: queryType={} matchCount={} "
                            + "totalDeferredTools={} maxResults={} hasMatches={} query={}（OPD-H-02）",
                    queryType, matches.size(), totalDeferredTools, maxResults, !matches.isEmpty(), query);
        }
    }

    /** CC prompt.ts:119-121 getPrompt() = PROMPT_HEAD + getToolLocationHint() + PROMPT_TAIL。 */
    private static String getPrompt() {
        return PROMPT_HEAD + getToolLocationHint() + PROMPT_TAIL;
    }

    /**
     * CC prompt.ts:35-42 getToolLocationHint()：delta 开关
     * {@code USER_TYPE==='ant' || getFeatureValue_CACHED_MAY_BE_STALE('tengu_glacier_2xr', false)}。
     * <p>Java 无 growthbook feature 通道 → tengu_glacier_2xr 恒 false（登记 OPD-H concern），
     * USER_TYPE 经 System.getenv 读取。
     */
    private static String getToolLocationHint() {
        boolean deltaEnabled = "ant".equals(System.getenv("USER_TYPE"));
        return deltaEnabled
                ? "Deferred tools appear by name in <system-reminder> messages."
                : "Deferred tools appear by name in <available-deferred-tools> messages.";
    }
}

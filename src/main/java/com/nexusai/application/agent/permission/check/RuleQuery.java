package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规则查询工具类 · 对齐 CC {@code utils/permissions/permissions.ts:116-362}
 *
 * <h2>9 个查询函数</h2>
 * <ul>
 *   <li>{@link #getAllowRuleForTool} — 找工具的 allow rule（CC 1b 附近）</li>
 *   <li>{@link #getDenyRuleForTool} — 找 deny rule（CC 1a）</li>
 *   <li>{@link #getAskRuleForTool} — 找 ask rule（CC 1b）</li>
 *   <li>{@link #toolAlwaysAllowedRule} — 工具 whole-tool allow（CC 2b）</li>
 *   <li>{@link #getAllowRules} — 拿 allow rules 集合（CC 全局查询）</li>
 *   <li>{@link #getDenyRules} — 拿 deny rules 集合</li>
 *   <li>{@link #getAskRules} — 拿 ask rules 集合</li>
 *   <li>{@link #getRuleForInput} — 按输入查单条 content rule（deny 优先于 ask）</li>
 *   <li>{@link #getDenyRuleByContentsForTool} — 仅查 deny 桶的 content rule（1a 层专用）</li>
 *   <li>{@link #getRuleContentsByBehavior} — 按行为 + 工具名归组 ruleContent Map（SkillTool.ts:470/507 用）</li>
 * </ul>
 *
 * <h2>Phase 2 content 匹配（对齐 CC {@code permissionRuleParser.ts}）</h2>
 * <p>ruleContent 匹配分三种模式：
 * <ul>
 *   <li><b>路径 glob</b>（ruleContent 含 {@code /}）：{@code Edit(/Users/foo/**)} 匹配路径</li>
 *   <li><b>命令前缀</b>（ruleContent 含 {@code :}）：{@code Bash(npm publish:*)} 匹配命令前缀</li>
 *   <li><b>精确匹配</b>（其他）：{@code Bash(rm -rf /)} 精确匹配 command 字段</li>
 * </ul>
 *
 * <h2>无状态 / 线程安全</h2>
 * <p>所有方法都是 static，无状态，线程安全。
 */
public final class RuleQuery {

    /** SLF4J 日志器 · 内容规则提取与 PowerShell 匹配的数据流日志（中文）。 */
    private static final Logger log = LoggerFactory.getLogger(RuleQuery.class);

    /** 通配符转义占位符 · 对齐 CC shellRuleMatching.ts:14-15 ESCAPED_STAR_PLACEHOLDER。 */
    private static final String ESCAPED_STAR_PLACEHOLDER = "\u0000ESCAPED_STAR\u0000";
    /** 反斜杠转义占位符 · 对齐 CC shellRuleMatching.ts:14-15 ESCAPED_BACKSLASH_PLACEHOLDER。 */
    private static final String ESCAPED_BACKSLASH_PLACEHOLDER = "\u0000ESCAPED_BACKSLASH\u0000";
    /**
     * 私有构造：工具类不允许实例化。
     */
    private RuleQuery() {
        // AssertionError 而非 IllegalStateException —— 这是"程序员错误"不应恢复
        throw new AssertionError("RuleQuery is a utility class — do not instantiate");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1a / 1b / 1c：单条规则查询（whole-tool 匹配）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * [R32-#9 / 替代 P0-5] MCP 工具名前缀解析 · 对齐 CC {@code services/mcp/mcpStringUtils.ts:19-32 mcpInfoFromString}.
     *
     * <p>解析 {@code mcp__server__tool} 格式字符串为 {serverName, toolName} 结构.
     * 规则: 必须以 {@code mcp__} 开头且至少有 serverName 部分.
     *
     * <p>示例:
     * <pre>
     * mcpInfoFromString("mcp__server1")           → {serverName:"server1", toolName:null}
     * mcpInfoFromString("mcp__server1__*")        → {serverName:"server1", toolName:"*"}
     * mcpInfoFromString("mcp__server1__tool1")    → {serverName:"server1", toolName:"tool1"}
     * mcpInfoFromString("mcp__server1__a__b")     → {serverName:"server1", toolName:"a__b"} (toolName 含双下划线)
     * mcpInfoFromString("Bash")                   → null (非 MCP)
     * mcpInfoFromString("mcp")                    → null (serverName 缺失)
     * </pre>
     *
     * @param toolString MCP 工具名 (如 {@code "mcp__server__tool"})
     * @return {serverName, toolName} 结构, 非 MCP 格式返回 null
     */
    public static McpToolInfo mcpInfoFromString(String toolString) {
        if (toolString == null || toolString.isBlank()) {
            return null;
        }
        String[] parts = toolString.split("__");
        if (parts.length < 2 || !"mcp".equals(parts[0]) || parts[1].isEmpty()) {
            return null;
        }
        String serverName = parts[1];
        // toolName 部分: 拼接 parts[2..] 以保留双下划线
        String toolName = null;
        if (parts.length > 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) sb.append("__");
                sb.append(parts[i]);
            }
            toolName = sb.toString();
        }
        return new McpToolInfo(serverName, toolName);
    }

    /**
     * [R32-#9] MCP 工具信息 record · 对齐 CC mcpInfoFromString 返回结构.
     *
     * @param serverName MCP server name (必有,非空)
     * @param toolName   MCP tool name (可有可无; {@code null} 表示 whole-server;
     *                   {@code "*"} 表示通配)
     */
    public record McpToolInfo(String serverName, String toolName) {
        public McpToolInfo {
            if (serverName == null || serverName.isEmpty()) {
                throw new IllegalArgumentException("serverName is blank");
            }
            // toolName 可以为 null (whole-server rule)
        }
    }

    /**
     * [R32-#9] 工具在 rule 匹配中的名字 · 对齐 CC {@code services/mcp/mcpStringUtils.ts:60-67 getToolNameForPermissionCheck}.
     *
     * <p>MCP 工具返回 {@code "mcp__server__tool"} 全名形式;非 MCP 工具返回 {@link Tool#name()}.
     *
     * @param tool 工具实例
     * @return 用于 rule 匹配的名字
     */
    public static String getToolNameForPermissionCheck(Tool tool) {
        if (tool == null) return null;
        if (tool.isMcp() && tool.mcpInfo() != null) {
            String serverName = tool.mcpInfo().serverName();
            String toolName = tool.mcpInfo().toolName();
            return buildMcpToolName(serverName, toolName);
        }
        return tool.name();
    }

    /**
     * [R32-#9] 构造 MCP 工具全名 · 对齐 CC {@code services/mcp/mcpStringUtils.ts buildMcpToolName}.
     *
     * <p>[impl-I-4 T9] 私有裸拼接统一到 {@link McpStringUtils}（CC mcpStringUtils.ts:50-52）：
     * server 名含 {@code .} / 空格 / 大写时 {@code "mcp__" + serverName + "__" + toolName} 与
     * {@code mcpInfoFromString} / {@code getMcpPrefix} 的规范化结果失配（权限规则匹配全链漂移）。
     * toolName 为空（whole-server 规则）时保留 {@code "mcp__<normalized_server>"}（无尾部 {@code __}，
     * {@link #mcpInfoFromString} 解析 toolName=null → whole-server 语义不变），仅 server 名规范化。
     *
     * <p>格式: {@code "mcp__<serverName>__<toolName>"} (toolName 为空时省略最后部分).
     */
    private static String buildMcpToolName(String serverName, String toolName) {
        if (serverName == null || serverName.isEmpty()) {
            return "";
        }
        if (toolName == null || toolName.isEmpty()) {
            return McpStringUtils.MCP_PREFIX + McpStringUtils.normalizeNameForMCP(serverName);
        }
        return McpStringUtils.buildMcpToolName(serverName, toolName);
    }

    /**
     * [R32-#9 / 替代 P0-5] 工具与 rule 匹配 · 对齐 CC {@code utils/permissions/permissions.ts:238-269 toolMatchesRule}.
     *
     * <p>两层语义 (任一满足即匹配):
     * <ol>
     *   <li><b>Direct match</b>: {@code rule.toolName === nameForRuleMatch}
     *       (e.g. "Bash" 屏蔽所有 Bash 工具调用, 包括 MCP 重命名后的 "Bash")</li>
     *   <li><b>MCP server-level match</b>: rule "mcp__server1" 屏蔽 server1 全部工具;
     *       rule "mcp__server1__*" 同样匹配 (wildcard).</li>
     * </ol>
     *
     * <p>约束: rule 必须 {@code ruleContent === null} 才是 whole-tool 匹配 (带 content 的
     * rule 不进入此函数, 留给运行时 content check via {@link #getRuleForInput}).
     *
     * @param tool 工具实例
     * @param rule 候选 rule (ruleContent 必须 null)
     * @return true = 匹配 (整工具被 deny/ask/allow)
     */
    public static boolean toolMatchesRule(Tool tool, PermissionRule rule) {
        if (tool == null || rule == null) return false;
        // rule 必须 whole-tool (无 ruleContent)
        if (rule.ruleValue().ruleContent() != null) {
            return false;
        }
        String nameForRuleMatch = getToolNameForPermissionCheck(tool);
        if (nameForRuleMatch == null) return false;

        // 1) Direct match（对齐 CC permissions.ts:254 严格 ===；[OPD-WF3-DC-v4-05] 等价组已删）
        if (toolNameMatches(rule.ruleValue().toolName(), nameForRuleMatch)) {
            return true;
        }

        // 2) MCP server-level match
        McpToolInfo ruleInfo = mcpInfoFromString(rule.ruleValue().toolName());
        McpToolInfo toolInfo = mcpInfoFromString(nameForRuleMatch);
        if (ruleInfo == null || toolInfo == null) {
            return false;
        }
        // rule 是 whole-server (toolName null) 或 wildcard ("*")
        boolean ruleIsWholeServer = ruleInfo.toolName() == null
            || "*".equals(ruleInfo.toolName());
        return ruleIsWholeServer
            && ruleInfo.serverName().equals(toolInfo.serverName());
    }

    /**
     * 找工具的 whole-tool allow rule（对齐 CC permissions.ts:275-285）。
     *
     * <p>遍历所有 source 的 allow 规则，匹配条件统一走 {@link #toolMatchesRule}
     * （CC {@code toolMatchesRule} permissions.ts:238-269）：
     * <ul>
     *   <li><b>direct</b>：{@code rule.toolName == nameForRuleMatch}（整工具）</li>
     *   <li><b>MCP server-level</b>：rule {@code mcp__server} / {@code mcp__server__*}
     *       命中 {@code mcp__server__tool}（whole-server / wildcard 前缀）</li>
     * </ul>
     * 带 content 的 rule（{@code ruleContent != null}）不进入本函数（非 whole-tool）。
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @return        第一个匹配的 rule；无匹配返回 null
     */
    public static PermissionRule toolAlwaysAllowedRule(
            ToolPermissionContext permCtx, Tool tool
    ) {
        // 遍历所有 source 的 allow 规则（按 source 优先级取第一个）
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysAllowRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                // [MCP-SEC-04 / EV-SEC-018 / P0-2] whole-tool 匹配复用 toolMatchesRule
                // （对齐 CC permissions.ts:275-282 getAllowRules.find(toolMatchesRule)）——
                // direct + MCP server-level（mcp__server / mcp__server__*）前缀匹配，
                // 替代原 toolName.equals（whole-server/wildcard allow 规则永不命中）
                if (toolMatchesRule(tool, rule)) {
                    if (log.isDebugEnabled()) {
                        log.debug("RuleQuery.toolAlwaysAllowedRule 命中 whole-tool allow 规则: rule={} tool={}",
                            ruleToString(rule), getToolNameForPermissionCheck(tool));
                    }
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 找工具的 deny rule（对齐 CC permissions.ts:287-296）。
     *
     * <p>遍历所有 source 的 deny 规则，匹配条件：
     * <ul>
     *   <li>{@code rule.toolName == tool.name()}</li>
     *   <li>{@code rule.ruleContent == null}（整个工具规则）</li>
     * </ul>
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @return        第一个匹配的 rule；无匹配返回 null
     */
    public static PermissionRule getDenyRuleForTool(
            ToolPermissionContext permCtx, Tool tool
    ) {
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysDenyRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                // [R32-#9 / 替代 P0-5] 用 toolMatchesRule 替代原精确 equals
                // 支持 MCP prefix 规则 (mcp__server, mcp__server__*)
                if (toolMatchesRule(tool, rule)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 找工具的 ask rule（对齐 CC permissions.ts:297-307）。
     *
     * <p>匹配条件同 {@link #getDenyRuleForTool}（统一走 {@link #toolMatchesRule}，
     * 支持 MCP whole-server / wildcard 前缀匹配）但查 ask 桶。
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @return        第一个匹配的 rule；无匹配返回 null
     */
    public static PermissionRule getAskRuleForTool(
            ToolPermissionContext permCtx, Tool tool
    ) {
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysAskRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                // [MCP-SEC-04 / EV-SEC-018 / P0-2] 复用 toolMatchesRule（对齐 CC
                // permissions.ts:297-302 getAskRules.find(toolMatchesRule)）——
                // direct + MCP server-level（mcp__server / mcp__server__*）前缀匹配，与 deny 桶对称
                if (toolMatchesRule(tool, rule)) {
                    if (log.isDebugEnabled()) {
                        log.debug("RuleQuery.getAskRuleForTool 命中 whole-tool ask 规则: rule={} tool={}",
                            ruleToString(rule), getToolNameForPermissionCheck(tool));
                    }
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 找工具的 whole-tool allow rule（对齐 CC permissions.ts:308-319 agent-level）。
     *
     * <p>与 {@link #toolAlwaysAllowedRule} 区别：本方法同时匹配 whole-tool AND with-content。
     * Agent-level allow 是更宽泛的"任何内容都允许"。
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @return        第一个匹配的 rule；无匹配返回 null
     */
    public static PermissionRule getAllowRuleForTool(
            ToolPermissionContext permCtx, Tool tool
    ) {
        return toolAlwaysAllowedRule(permCtx, tool);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 集合查询：所有 source 的规则合并
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 拿所有 source 的 allow rules 合并集合（对齐 CC permissions.ts:122）。
     *
     * @param permCtx 权限上下文
     * @return        所有 allow rules（不可变 view）
     */
    public static Set<PermissionRule> getAllowRules(ToolPermissionContext permCtx) {
        // 合并 8 source 的 Set<PermissionRule>
        java.util.Set<PermissionRule> all = new java.util.HashSet<>();
        for (Set<PermissionRule> sourceRules : permCtx.alwaysAllowRules().values()) {
            all.addAll(sourceRules);
        }
        return java.util.Set.copyOf(all);
    }

    /**
     * 拿所有 source 的 deny rules 合并集合（对齐 CC permissions.ts:213）。
     *
     * @param permCtx 权限上下文
     * @return        所有 deny rules（不可变 view）
     */
    public static Set<PermissionRule> getDenyRules(ToolPermissionContext permCtx) {
        java.util.Set<PermissionRule> all = new java.util.HashSet<>();
        for (Set<PermissionRule> sourceRules : permCtx.alwaysDenyRules().values()) {
            all.addAll(sourceRules);
        }
        return java.util.Set.copyOf(all);
    }

    /**
     * 拿所有 source 的 ask rules 合并集合（对齐 CC permissions.ts:223）。
     *
     * @param permCtx 权限上下文
     * @return        所有 ask rules（不可变 view）
     */
    public static Set<PermissionRule> getAskRules(ToolPermissionContext permCtx) {
        java.util.Set<PermissionRule> all = new java.util.HashSet<>();
        for (Set<PermissionRule> sourceRules : permCtx.alwaysAskRules().values()) {
            all.addAll(sourceRules);
        }
        return java.util.Set.copyOf(all);
    }

    /**
     * 按行为查"工具名 + ruleContent"归组的规则 Map · 对齐 CC {@code permissions.ts:349-390}
     * {@code getRuleByContentsForToolName}（SkillTool.ts:470/507 的 deny / allow 循环经
     * {@code getRuleByContentsForTool(SkillTool, 'deny'/'allow')} 转调本方法）.
     *
     * <p>合并该 behavior 桶的全部 8 source（复用 {@link #getAllowRules} / {@link #getDenyRules} /
     * {@link #getAskRules}），过滤条件（对齐 CC permissions.ts:380-387）：
     * <ul>
     *   <li>{@code rule.ruleValue.toolName === toolName}（工具名相等）</li>
     *   <li>{@code rule.ruleValue.ruleContent !== undefined}（必须带内容限定，whole-tool 规则不参与）</li>
     *   <li>{@code rule.ruleBehavior === behavior}（行为桶一致）</li>
     * </ul>
     * 以 {@code ruleContent} 为 key 归组 → {@code Map<ruleContent, rule>}。
     *
     * <p>本方法返回 <b>Map 分组</b>（ruleContent → rule），严格按传入 behavior 取对应桶
     * （deny / allow / ask 各自独立），不做"ask 优先于 deny"的 1f 层语义；CC SkillTool 的
     * 授权流程是 <b>deny 循环 → allow 循环 → safe-properties → default Ask</b>（SkillTool.ts:470-577），
     * 每次循环单独拉对应行为桶，同一 command 同时命中 deny 与 allow 时 deny 先检查 → deny 赢
     * （CC 逐循环顺序天然实现）。1f 层的 ask-优先语义留给 {@code CheckLayer1f_ContentSpecificAskRule}
     * 走 {@link #getRuleForInput}（本文件内部 Phase 2 匹配）。
     * 【DEL-WF3-DC-03】旧 {@code getRuleByContentsForToolName}（单条 ask 桶查询，名实错位死代码）
     * 已删——CC 对应物即本方法（Map 语义），v4 WF-3 域确认 0 生产调用方，用户 2026-08-18 拍板删除。
     *
     * @param permCtx 权限上下文
     * @param toolName 工具名（如 {@code "Skill"}）
     * @param behavior 规则行为（ALLOW / DENY / ASK）
     * @return ruleContent → rule 映射（不可变）；空规则集返回空 Map
     */
    public static Map<String, PermissionRule> getRuleContentsByBehavior(
            ToolPermissionContext permCtx,
            String toolName,
            PermissionBehavior behavior) {
        if (permCtx == null) {
            return Map.of();
        }
        Set<PermissionRule> rules = switch (behavior) {
            case ALLOW -> getAllowRules(permCtx);
            case DENY -> getDenyRules(permCtx);
            case ASK -> getAskRules(permCtx);
        };
        Map<String, PermissionRule> byContent = new java.util.LinkedHashMap<>();
        for (PermissionRule rule : rules) {
            if (toolName.equals(rule.ruleValue().toolName())
                    && rule.ruleValue().ruleContent() != null
                    && rule.ruleBehavior() == behavior) {
                byContent.put(rule.ruleValue().ruleContent(), rule);
            }
        }
        return java.util.Map.copyOf(byContent);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1f：内容特定 rule 查询
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 按输入查匹配 tool 的单条 content rule（deny 优先于 ask）。
     *
     * <p>本方法返回<b>单条</b> rule（先查 deny 桶、再查 ask 桶，命中即返回）。
     * 注意：CC {@code getRuleByContentsForTool}（permissions.ts:349-390）返回
     * {@code Map<ruleContent, rule>} 分组语义，Java 端由
     * {@link #getRuleContentsByBehavior}（:399-420）承载；本方法名实不符，已按
     * OPD-WF3-01-07 拍板改名 {@code getRuleForInput} 消除误导（CC Map 语义与
     * 单条输入匹配是两种不同查询，名称不再混用）。
     *
     * <p>遍历所有 source 的所有 behavior 规则，匹配条件：
     * <ul>
     *   <li>{@code rule.toolName == tool.name()}</li>
     *   <li>{@code rule.ruleContent != null}（带内容限定的规则）</li>
     *   <li>ruleContent 与 input 的目标字段匹配（glob / 前缀 / 精确）</li>
     * </ul>
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @param input   已解析 JSON 输入
     * @return        第一个 content-specific rule（deny > ask）；无匹配返回 null
     */
    public static PermissionRule getRuleForInput(
            ToolPermissionContext permCtx,
            Tool tool,
            JsonNode input
    ) {
        // s03-P1#1: deny 优先于 ask（CC deny > ask > allow）。若先查 ask 桶，
        // 同一 command 同时命中 ask 和 deny 时 ask 赢 → deny 被软化（CC 不允许）。
        // 先查 deny 桶确保 deny 规则永远不被 ask 覆盖。
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysDenyRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                if (matchesContent(rule, tool, input)) {
                    return rule;
                }
            }
        }
        // 然后查 ask 桶（content-specific ask）
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysAskRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                if (matchesContent(rule, tool, input)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 仅查 deny 桶的 content-specific rule（对齐 CC permissions.ts deny 优先语义）。
     *
     * <p>与 {@link #getRuleForInput} 区别：本方法<b>只</b>查
     * {@code alwaysDenyRules()}，不碰 ask 桶。用于 1a 层 content deny 检查 —
     * 1a 在 2a(bypass)/2b(allow)/1b(whole-tool ask) 之前，天然 bypass-immune。
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @param input   已解析 JSON 输入
     * @return        第一个 content-specific deny rule；无匹配返回 null
     */
    public static PermissionRule getDenyRuleByContentsForTool(
            ToolPermissionContext permCtx,
            Tool tool,
            JsonNode input
    ) {
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysDenyRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                if (matchesContent(rule, tool, input)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 仅查 ask 桶的 content-specific rule（对齐 CC bashPermissions.ts:957-966 ask 桶）。
     *
     * <p>[WF2-02 / U-4] 内容 ask 桶生产匹配路径接线。CC {@code matchingRulesForInput}
     * （bashPermissions.ts:941-986）对 Bash 工具三桶并行求值：
     * deny（:943-948）、ask（:957-963）、allow（:969-975）。ask 桶剥离参数与 deny 桶
     * 一致：{@code stripAllEnvVars: true, skipCompoundCheck: true}（bashPermissions.ts:957-966）
     * —— 即 {@code FOO=bar ask_command} 与复合命令任一子命令命中 ask 都触发 ask。
     *
     * <p>与 {@link #getDenyRuleByContentsForTool} / {@link #getAllowRuleByContentsForTool}
     * 同构：只查 {@code alwaysAskRules()} 桶，命中 content 匹配即返回。调用方
     * {@code BashTool.checkPermissions} 2b 层把返回 rule 归因为 {@link PermissionDecisionReason.Rule}
     * 的 Ask，供管线 1f（{@link CheckLayer1f_ContentSpecificAskRule}）消费为 bypass-immune。
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @param input   已解析 JSON 输入
     * @return        第一个 content-specific ask rule；无匹配返回 null
     */
    public static PermissionRule getAskRuleByContentsForTool(
            ToolPermissionContext permCtx,
            Tool tool,
            JsonNode input
    ) {
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysAskRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                if (matchesContent(rule, tool, input)) {
                    if (log.isDebugEnabled()) {
                        log.debug("RuleQuery 命中内容 ask 桶规则: rule={} tool={}",
                            ruleToString(rule), tool.name());
                    }
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 写权限链路径规则查询 · 对齐 CC {@code matchingRuleForInput(path, ctx, 'edit', behavior)}
     * （filesystem.ts:955-1025；'edit' 桶固定用 {@code FILE_EDIT_TOOL_NAME='Edit'}
     * （getPatternsByRoot :919-953），且仅匹配 ruleContent 非空的 content 规则
     * （permissions.ts:380-388 {@code ruleContent !== undefined} 过滤））。
     *
     * <p>与 {@link #getRuleForInput} 的区别：本方法<b>不绑定 Tool 实例</b>，
     * 以字符串路径直接匹配 ruleContent——供 {@code WritePermissionChecker} 使用
     * （read 路径 step5 edit-implies-read 时传入的是 Read 工具，无法用 tool.name() 命中
     * Edit 规则；CC 语义正是"路径上的 Edit 规则"，与调用工具名无关）。
     * whole-tool 规则（ruleContent==null）本方法不匹配——CC 中由管线 2b 层
     * toolAlwaysAllowedRule 单独处理，checkWritePermissionForTool 链内不消费。
     *
     * <p><b>OPD-WF5-FS-052 root-relative 重构</b>：匹配语义对齐 CC matchingRuleForInput
     * 的 patternWithRoot 根锚定（filesystem.ts:853-917）——规则前缀决定匹配根：
     * {@code //…} 文件系统根 / {@code ~/…} 家目录 / {@code /…} 规则 source 根
     * （session/cliArg/command=cwd，settings 源≈cwd 近似）/ {@code ./…} 或无前缀 → cwd。
     * 路径先 expandPath（~ 展开 + 相对→绝对 + POSIX 归一）再计算 relativePath(root, path)
     * 匹配（见 {@link #matchesEditPathRuleRootRelative}）。旧 content glob 直比（matchesGlob
     * 对绝对路径字符串做 glob）为近似，拍板重构对齐 CC。
     *
     * @param permCtx  权限上下文
     * @param path     待匹配路径（input.file_path / input.path 提取值）
     * @param behavior 规则行为桶（ALLOW / DENY / ASK）
     * @return         第一个 content 匹配的 edit 组规则；无匹配返回 null
     */
    public static PermissionRule getEditRuleByContentsForPath(
            ToolPermissionContext permCtx,
            String path,
            PermissionBehavior behavior) {
        return getEditRuleByContentsForPath(permCtx, path, behavior, null);
    }

    /**
     * 带 cwd 的写权限链路径规则查询 · 与 {@link #getEditRuleByContentsForPath(ToolPermissionContext, String, PermissionBehavior)}
     * 同语义，额外透传校验基准 cwd（root-relative 匹配的根锚定）。
     *
     * <p>调用方 {@code BashPathValidator}（有 cwd 参数）与 {@code WritePermissionChecker}
     * （有 ctx.effectiveCwd()）传显式 cwd；其余调用方（PathValidation / EditFileTool /
     * PowerShellPathValidator）走 3 参重载，cwd=null → 统一入口 {@link CwdResolution#getCwd}
     * （[WF-1D · DEL-06] 原 user.dir 直读兜底改走统一入口，对齐 CC resolve(cwd, path) cwd=getCwd()）。
     *
     * @param permCtx  权限上下文
     * @param path     待匹配路径（input.file_path / input.path 提取值）
     * @param behavior 规则行为桶（ALLOW / DENY / ASK）
     * @param cwd      校验基准 cwd（null → {@link CwdResolution#getCwd} 统一入口解析）
     * @return         第一个 content 匹配的 edit 组规则；无匹配返回 null
     */
    public static PermissionRule getEditRuleByContentsForPath(
            ToolPermissionContext permCtx,
            String path,
            PermissionBehavior behavior,
            String cwd) {
        if (permCtx == null || path == null) {
            return null;
        }
        String effectiveCwd = cwd != null && !cwd.isEmpty()
            ? cwd : CwdResolution.getCwd();
        Map<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> bucket =
            switch (behavior) {
                case ALLOW -> permCtx.alwaysAllowRules();
                case DENY -> permCtx.alwaysDenyRules();
                case ASK -> permCtx.alwaysAskRules();
            };
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : bucket.entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                if (rule.ruleValue().ruleContent() == null) {
                    continue;
                }
                // edit 组工具名（CC 仅 'Edit' 精确匹配，filesystem.ts:919-953 FILE_EDIT_TOOL_NAME；
                // [OPD-WF3-DC-v4-05] Java 兼容组 Edit/edit_file/edit 已删对齐 CC）
                if (!toolNameMatches(rule.ruleValue().toolName(), "Edit")) {
                    continue;
                }
                if (matchesEditPathRuleRootRelative(
                        rule.ruleValue().ruleContent(), path, entry.getKey(), effectiveCwd)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * 查 content-specific <b>allow</b> rule（对齐 CC permissions.ts:349-360 allow 桶）。
     *
     * <p>[H13 v4] 补齐 allow 匹配缺口：ExecAgentHook 把
     * {@code Read(/transcriptPath)} 以 with-content 形式加入 hook agent 的
     * DONT_ASK permCtx（execAgentHook.ts:141-153 session rule）。旧实现（R26 hook 层）
     * 只查 whole-tool allow（{@link #toolAlwaysAllowedRule} 要求 ruleContent==null），
     * content-specific allow 规则永不命中 → hook 读 transcript 被 DONT_ASK deny。
     * 本方法遍历 {@code alwaysAllowRules()} 桶，命中 content 匹配即返回。
     *
     * @param permCtx 权限上下文
     * @param tool    工具实例
     * @param input   已解析 JSON 输入
     * @return        第一个 content-specific allow rule；无匹配返回 null
     */
    public static PermissionRule getAllowRuleByContentsForTool(
            ToolPermissionContext permCtx,
            Tool tool,
            JsonNode input
    ) {
        for (Map.Entry<com.nexusai.application.agent.permission.PermissionRuleSource, Set<PermissionRule>> entry
                : permCtx.alwaysAllowRules().entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                if (matchesContent(rule, tool, input)) {
                    return rule;
                }
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：content 匹配（Phase 2 — 真正的 glob / 前缀 / 精确匹配）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 工具名匹配 · 对齐 CC {@code permissions.ts:254}（toolMatchesRule direct 分支严格 {@code ===}）。
     *
     * <p>[H13 v4 / OPD-WF3-DC-v4-05] 文件工具名等价组（Read↔read_file 等）已删——CC 仅精确相等
     * （permissions.ts:254 {@code rule.ruleValue.toolName === nameForRuleMatch}）。B2 后工具注册
     * 主名对齐 CC（ReadFileTool.name()='Read' / EditFileTool.name()='Edit' /
     * WriteFileTool.name()='Write'，IMP-C3 已删旧 snake_case alias），故 CC 名规则与 CC 名工具
     * 精确命中不受影响。
     *
     * <p>H13 历史 transcript 兼容评估（用户 2026-08-18 拍板删等价组）：旧 snake_case 规则名
     * （read_file 等）不再命中 CC 主名——这是 CC 语义（Java 偏离 CC 即脏代码），历史 transcript
     * 兼容成本由拍板接受。MCP server-level 匹配（mcp__server / mcp__server__*）由
     * {@link #toolMatchesRule} 第 2 段承载，不受本方法影响。
     *
     * @param ruleToolName 规则里的工具名（CC 名）
     * @param toolName     实际工具名（Java 注册名）
     * @return true = 精确相等（===）
     */
    static boolean toolNameMatches(String ruleToolName, String toolName) {
        if (ruleToolName == null || toolName == null) {
            return false;
        }
        return ruleToolName.equals(toolName);
    }

    /**
     * content 匹配：检查 rule 的 ruleContent 是否匹配 tool + input。
     *
     * <p>匹配流程：
     * <ol>
     *   <li>ruleContent 为 null → false（非 content-specific rule）</li>
     *   <li>toolName 不匹配 → false</li>
     *   <li>从 input 提取匹配目标（Bash→command, Edit/Write/Read→file_path）</li>
     *   <li>目标为 null（未知工具类型）→ false</li>
     *   <li>按 ruleContent 特征选择匹配模式（glob / 前缀 / 精确）</li>
     * </ol>
     *
     * @param rule  权限规则
     * @param tool  工具实例
     * @param input   已解析 JSON 输入
     * @return        true = ruleContent 匹配 input 内容
     */
    private static boolean matchesContent(
            PermissionRule rule, Tool tool,
            JsonNode input
    ) {
        // 必须有 content 才算 content-specific rule
        if (rule.ruleValue().ruleContent() == null) {
            return false;
        }
        // toolName 匹配（对齐 CC permissions.ts:254 严格 ===；[OPD-WF3-DC-v4-05] 等价组已删）
        if (!toolNameMatches(rule.ruleValue().toolName(), tool.name())) {
            return false;
        }
        // 从 input 提取匹配目标字段（Bash→command, Edit/Write/Read→file_path）
        String target = extractMatchTarget(tool.name(), input);
        // 未知工具类型，无法匹配具体内容
        if (target == null) {
            return false;
        }
        // 按 ruleContent 特征 + toolName 选择匹配模式（[s03 P2 #8 修补] 加 toolName 参数；
        // [s09] 加 ruleBehavior 参数：Bash deny/ask 桶与 allow 桶剥离参数不同）
        return matchRuleContent(rule.ruleValue().ruleContent(), target, tool.name(),
            rule.ruleBehavior());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：匹配模式分发
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 根据 ruleContent 特征 + toolName 选择匹配模式并执行匹配。
     *
     * <p>[s03 P2 #8 修补] 修补前按"contains(&#39;/&#39;) → glob, contains(&#39;:&#39;) → prefix"分派,
     * 有 2 个 bug:
     * <ul>
     *   <li>{@code Bash(cat /var/log:*)} 这种含 {@code /} 的命令前缀规则被误送 glob,前缀语义丢失</li>
     *   <li>{@code Bash(rm -rf /)} 精确规则被当 glob 处理</li>
     * </ul>
     *
     * <p>修补后分派语义(对齐 CC permissionRuleParser.ts):
     * <ul>
     *   <li><b>PathTool</b>(read_file/write_file/edit_file)+ ruleContent 含 {@code /} 或 {@code **} → glob</li>
     *   <li><b>BashTool</b> → {@link com.nexusai.application.agent.bash.BashRuleMatcher}
     *       （[s09] 对齐 CC bashPermissions.ts filterRulesByContentsMatchingInput：
     *       env/wrapper 剥离 + compound guard + xargs 前缀 + wildcard exact 拒绝；
     *       deny/ask 桶 stripAllEnvVars=true + skipCompoundCheck=true，allow 桶正常）</li>
     *   <li><b>Other</b>+ ruleContent 含 {@code :*} → command prefix</li>
     *   <li>否则 → 精确字符串匹配</li>
     * </ul>
     *
     * @param ruleContent 规则内容字符串
     * @param target      从 input 提取的匹配目标
     * @param toolName    工具名(PathTool/BashTool/Other 三种类型)
     * @param behavior    规则行为桶（Bash 匹配的剥离参数分桶依据；
     *                    CC matchingRulesForInput :937-986：deny/ask 桶
     *                    stripAllEnvVars=true + skipCompoundCheck=true）
     * @return            true = 匹配
     */
    private static boolean matchRuleContent(
            String ruleContent, String target, String toolName,
            com.nexusai.application.agent.permission.PermissionBehavior behavior) {
        // 0. PowerShell 工具：大小写不敏感独立匹配路径（OPD-PERM-37）
        //    PowerShell cmdlet 大小写不敏感（Get-Process == get-process），CC powershellPermissions.ts
        //    全程 strEquals/strStartsWith 小写比较 + matchWildcardPattern(..., true)；
        //    独立路径确保 Bash 大小写敏感语义（matchesCommandPrefix / equals）不受影响。
        if ("PowerShell".equals(toolName)) {
            return matchesPowerShellRuleContent(ruleContent, target);
        }
        // [s09] Bash 工具：CC bashPermissions.ts 匹配器语义（剥离 + compound guard +
        // xargs 前缀 + wildcard exact 拒绝）。管线 1a/1f 单层查询用 prefix 模式
        // （CC bashToolCheckPermission 先 exact 后 prefix；prefix 模式是 exact 的超集：
        // exact 规则精确相等、prefix 规则前缀匹配、wildcard 匹配非 compound）。
        // 剥离参数按行为桶：deny/ask 全 env 剥离 + 跳过 compound guard（CC :948-967），
        // allow 桶正常（compound guard 生效，CC :969-979）。
        if ("Bash".equals(toolName)) {
            boolean isDenyOrAsk = behavior != com.nexusai.application.agent.permission.PermissionBehavior.ALLOW;
            return com.nexusai.application.agent.bash.BashRuleMatcher.matchesRuleContent(
                ruleContent, target, false, isDenyOrAsk, isDenyOrAsk);
        }
        // [P6] isPathTool 识别 · 对齐 CC permissionRuleParser.ts —— 仅 CC 主名 Edit/Write/Read。
        // IMP-13 删等价组 + IMP-C3 删 snake_case alias 后，matchesContent 只传 tool.name()=CC 主名
        // （如 ReadFileTool.name()='Read'），read_/write_/edit_ 前缀分支生产不可达（死代码）——
        // 随 R7 extractMatchTarget 清理一并移除。大小写敏感: CC 名仅当 equals 时匹配（严格 ===）。
        boolean isPathTool = toolName != null
            && (toolName.equals("Edit")
                || toolName.equals("Write")
                || toolName.equals("Read"));
        // 1. PathTool (file_path 类工具): 用 glob (路径必须含 `/` 才能视为 glob)
        if (isPathTool) {
            if (ruleContent.startsWith("/") || ruleContent.contains("**")) {
                return matchesGlob(ruleContent, target);
            }
            // PathTool 但 ruleContent 不像 glob (如 "src/main.java") → 精确
            return ruleContent.equals(target);
        }
        // 2. Other 工具: 用 :* 前缀语法
        if (ruleContent.contains(":*")) {
            return matchesCommandPrefix(ruleContent, target);
        }
        // 3. 否则: 精确字符串匹配 (含 "/" 的 bash 命令如 "rm -rf /" 也走精确)
        return ruleContent.equals(target);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：从 input JSON 提取匹配目标字段
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 从工具的 input JSON 中提取用于 ruleContent 匹配的目标字段。
     *
     * <p>CC 根据工具类型取不同字段（对齐 {@code permissionRuleParser.ts}）：
     * <ul>
     *   <li>Bash → {@code input.command}（String）</li>
     *   <li>Edit → {@code input.file_path}（String）</li>
     *   <li>Write → {@code input.file_path}（String）</li>
     *   <li>Read → {@code input.file_path}（String）</li>
     *   <li>其他 → null（无特定字段，不匹配）</li>
     * </ul>
     *
     * @param toolName 工具名
     * @param input    已解析 JSON 输入
     * @return         匹配目标字符串；未知工具类型返回 null
     */
    static String extractMatchTarget(String toolName, JsonNode input) {
        // input 为 null 时无法提取
        if (input == null) {
            return null;
        }
        // 根据工具名决定从 input 中取哪个字段
        String target = switch (toolName) {
            // Bash / PowerShell 工具：取 command 字段（CC BASH_TOOL_NAME='Bash'、
            // POWERSHELL_TOOL_NAME='PowerShell'；powershellToolHasPermission 链内
            // matchingRulesForInput 同样按 command 前缀匹配，:676/:841；
            // powershellPermissions.ts:176 input.command.trim()，匹配走大小写不敏感独立路径）
            case "Bash", "PowerShell" -> getTextOrNull(input, "command");
            // 文件工具：取 file_path 字段
            // [R7] 对齐 CC 严格 === 语义：仅保留 CC 主名（Read/Edit/Write，ReadFileTool.name()='Read'
            // 等，IMP-C3 已删 snake_case alias）。旧 snake_case/lowercase 分支（read_file/
            // write_file/edit_file/read/write/edit）为已删除工具名的兼容壳——管线 matchesContent
            // 先经 toolNameMatches 严格 === 门控（tool.name()=CC 名），这些分支在生产不可达，
            // 移除对齐 CC（OPD-WF3-DC-v4-05 等价组删 + R7 清理）
            case "Edit", "Write", "Read" -> getTextOrNull(input, "file_path");
            // 未知工具类型：无特定字段，不匹配
            default -> null;
        };
        if (log.isDebugEnabled()) {
            log.debug("RuleQuery.extractMatchTarget: tool={} 提取内容规则匹配目标={}",
                toolName, target);
        }
        return target;
    }

    /**
     * 安全地从 JsonNode 中取指定字段的文本值。
     *
     * @param node      JSON 节点
     * @param fieldName 字段名
     * @return          字段文本值；字段不存在或非文本返回 null
     */
    private static String getTextOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        // 字段不存在或不是文本类型
        if (field == null || !field.isTextual()) {
            return null;
        }
        return field.asText();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：路径 glob 匹配
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 路径 glob 匹配（对齐 CC 的路径 glob 语义）。
     *
     * <p>匹配规则：
     * <ul>
     *   <li>{@code **} 匹配任意层级路径（含 {@code /}）</li>
     *   <li>{@code *} 匹配单个路径段（不含 {@code /}）</li>
     *   <li>其他字符按字面匹配</li>
     * </ul>
     *
     * <p>使用 {@link PathMatcher} 的 glob 语法实现。
     * {@code **} 在 NIO glob 中匹配任意深度路径，{@code *} 匹配单层，
     * 与 CC 语义一致。
     *
     * @param pattern glob 模式（如 {@code /Users/foo/**}）
     * @param path    实际路径（如 {@code /Users/foo/bar.txt}）
     * @return        true = 路径匹配 glob 模式
     */
    static boolean matchesGlob(String pattern, String path) {
        try {
            // 使用 NIO PathMatcher 的 glob 语法：** 匹配任意深度，* 匹配单层
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(java.nio.file.Paths.get(path));
        } catch (Exception e) {
            // glob 模式非法时回退到精确匹配
            return pattern.equals(path);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // OPD-WF5-FS-052 · matchingRuleForInput root-relative 匹配（对齐 CC patternWithRoot + relativePath + ignore）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * CC {@code matchingRuleForInput} 的 Java root-relative 等价（filesystem.ts:955-1025
     * + patternWithRoot :853-917）。仅用于 Edit 路径规则（matchingRuleForInput 'edit' 桶）。
     *
     * <p>匹配三步：
     * <ol>
     *   <li><b>路径展开</b>（CC :961 expandPath）——~ 展开 + 相对→绝对（对齐 CC getCwd
     *       / homedir）+ Windows 反斜杠→POSIX 归一（CC :964-966）；</li>
     *   <li><b>根锚定</b>（CC patternWithRoot :853-917）——规则前缀决定根：
     *       {@code //…} 文件系统根（Windows {@code //c/…} 盘符根）、{@code ~/…} 家目录、
     *       {@code /…} 规则 source 根（session/cliArg/command=cwd；settings 源≈cwd 近似，
     *       CC rootPathForSource 的 settings-root 解析归 settings 域专项）、
     *       {@code ./…} 或无前缀 → cwd（CC :907-916）；</li>
     *   <li><b>相对路径匹配</b>（CC :991-1020）——relativePath(root, path) 以 {@code ..} 开头
     *       即越界不匹配（:997-1000）；空路径跳过（:1002-1005）；POSIX glob 匹配
     *       relativePath 与 relativePattern（CC 剥 {@code /**} 后缀用 ignore 库，Java 用
     *       {@link #globMatchesPosix} 等价表达：bare 目录模式匹配其下全部内容）。</li>
     * </ol>
     *
     * @param ruleContent Edit 规则内容（如 {@code //etc/**} / {@code ~/.claude/**} / {@code /.claude/**}）
     * @param path        待匹配路径（原始 input 或已展开绝对路径均可，内部统一 expand）
     * @param source      规则来源（决定 {@code /…} 前缀的根锚定）
     * @param cwd         校验基准 cwd（CC getOriginalCwd 等价）
     * @return            true = root-relative 命中
     */
    static boolean matchesEditPathRuleRootRelative(
            String ruleContent, String path,
            com.nexusai.application.agent.permission.PermissionRuleSource source,
            String cwd) {
        if (ruleContent == null || path == null) {
            return false;
        }
        String pattern = ruleContent.trim();
        if (pattern.isEmpty()) {
            return false;
        }
        // 1. 展开待匹配路径（CC :961-966 expandPath + Windows POSIX 归一）
        String target = expandPathForMatch(path, cwd);
        // 2. 根锚定
        String root;
        String relativePattern;
        if (pattern.startsWith("//")) {
            // CC :860-892 —— 文件系统根（// 前缀）
            String withoutDoubleSlash = pattern.substring(1); // /…/**
            // Windows 盘符形 //c/Users/…（CC :867-887）
            if (isWindows() && withoutDoubleSlash.matches("^/[a-zA-Z]/.*")) {
                String drive = withoutDoubleSlash.substring(1, 2).toUpperCase() + ":";
                String pathAfterDrive = withoutDoubleSlash.substring(2);
                String relativeFromDrive = pathAfterDrive.startsWith("/")
                    ? pathAfterDrive.substring(1) : pathAfterDrive;
                root = drive;
                relativePattern = relativeFromDrive;
            } else {
                // 文件系统根：Windows 归一为当前盘根（C:\），Unix 为 /
                root = posixNormalize(Paths.get("/").toAbsolutePath().normalize().toString());
                relativePattern = withoutDoubleSlash;
            }
        } else if (pattern.startsWith("~/") || pattern.startsWith("~\\")) {
            // CC :893-898 —— 家目录根
            root = posixNormalize(System.getProperty("user.home", "."));
            relativePattern = pattern.substring(1); // 去 ~，留 /…/**
        } else if (pattern.startsWith("/")) {
            // CC :899-905 —— 规则 source 根（session/cliArg/command=cwd；settings 源≈cwd 近似）
            root = posixNormalize(cwd);
            relativePattern = pattern;
        } else {
            // CC :906-916 —— 无根：./ 前缀剥离，root=cwd
            String normalized = pattern.startsWith("./") ? pattern.substring(2) : pattern;
            root = posixNormalize(cwd);
            relativePattern = normalized;
        }
        // 3. 相对路径匹配（CC :991-1020）
        String rel = posixRelative(root, target);
        if (rel == null || rel.isEmpty()) {
            return false; // 越界（..）或空路径 → 不匹配（CC :997-1005）
        }
        // 相对模式剥前导 / 再 glob（裸目录模式匹配其下全部内容，CC ignore 库语义）
        String globPattern = relativePattern;
        while (globPattern.startsWith("/")) {
            globPattern = globPattern.substring(1);
        }
        boolean matched = globMatchesPosix(globPattern, rel);
        if (matched && log.isDebugEnabled()) {
            log.debug("RuleQuery.matchesEditPathRuleRootRelative: 命中 root={} pattern={} target={} rel={}",
                root, relativePattern, target, rel);
        }
        return matched;
    }

    /** 平台判定（Windows → 盘符根 / POSIX 归一）。 */
    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    /** 展开待匹配路径：~ 展开 + 相对→绝对 + POSIX 归一（CC expandPath filesystem.ts:169-193）。 */
    private static String expandPathForMatch(String path, String cwd) {
        String p = path;
        String home = System.getProperty("user.home", ".");
        if (p.equals("~")) {
            p = home;
        } else if (p.startsWith("~/") || p.startsWith("~\\")) {
            p = home + p.substring(1);
        }
        try {
            Path abs = Paths.get(p);
            if (!abs.isAbsolute()) {
                // 以 / 开头的盘相对路径（Windows 下非绝对）→ 归一为当前盘根
                if (p.startsWith("/") || p.startsWith("\\")) {
                    abs = abs.toAbsolutePath();
                } else {
                    Path base = Paths.get(cwd != null && !cwd.isEmpty() ? cwd : ".");
                    abs = base.resolve(p);
                }
            }
            return posixNormalize(abs.normalize().toString());
        } catch (Exception e) {
            return posixNormalize(p);
        }
    }

    /** POSIX 归一：反斜杠 → 正斜杠（CC windowsPathToPosixPath 等价）。 */
    private static String posixNormalize(String s) {
        if (s == null) {
            return null;
        }
        String r = s.replace('\\', '/');
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /**
     * POSIX 相对路径计算（CC filesystem.ts:170-211 relativePath 等价）：root 是 target
     * 的祖先 → 返回相对路径；否则返回 null（越界，CC 用 {@code ..} 前缀 + 跳过）。
     */
    private static String posixRelative(String root, String target) {
        String r = posixNormalize(root);
        String t = posixNormalize(target);
        if (r == null || t == null) {
            return null;
        }
        if (t.equals(r)) {
            return "";
        }
        // 盘符大小写归一（Windows C: vs c:）
        String rt = r, tt = t;
        if (isWindows() && r.length() >= 2 && r.charAt(1) == ':') {
            rt = Character.toUpperCase(r.charAt(0)) + r.substring(1);
        }
        if (isWindows() && t.length() >= 2 && t.charAt(1) == ':') {
            tt = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        if (tt.startsWith(rt + "/")) {
            return tt.substring(rt.length() + 1);
        }
        return null; // 不在 root 下 → 越界（CC .. 前缀跳过）
    }

    /**
     * POSIX glob 匹配（CC ignore 库 gitignore 语义等价）：{@code **} 任意深度、{@code *}
     * 单段、{@code ?} 单字符；bare 目录模式匹配其下全部内容（相对模式剥 {@code /**} 后
     * 由本方法对子路径前缀命中，等价 CC matchingRuleForInput :980-1019 剥 {@code /**}
     * 后 ignore 库把目录模式应用于其下内容）。
     */
    static boolean globMatchesPosix(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        String pat = pattern;
        while (pat.endsWith("/")) {
            pat = pat.substring(0, pat.length() - 1);
        }
        // 剥尾 / 后目录模式：foo → foo（匹配 foo 自身及其下全部内容）
        if (!pat.isEmpty() && pat.indexOf('*') < 0 && pat.indexOf('?') < 0) {
            return path.equals(pat) || path.startsWith(pat + "/");
        }
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < pat.length()) {
            char c = pat.charAt(i);
            if (c == '*') {
                if (i + 1 < pat.length() && pat.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        sb.append("$");
        return path.matches(sb.toString());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：命令前缀匹配
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 命令前缀匹配（对齐 CC {@code permissionRuleParser.ts} 的 {@code :} 分隔符）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code npm publish:*} → 前缀为 {@code npm publish}，
     *       匹配 {@code "npm publish --access public"}</li>
     *   <li>{@code :} 是分隔符，{@code :*} 表示"此前缀开头的任何命令"</li>
     *   <li>前缀匹配要求命令以 ruleContent 的 {@code :} 前部分开头</li>
     * </ul>
     *
     * @param ruleContent 规则内容（如 {@code "npm publish:*"}）
     * @param command     实际命令（如 {@code "npm publish --access public"}）
     * @return            true = 命令匹配前缀
     */
    static boolean matchesCommandPrefix(String ruleContent, String command) {
        // 找到 ':' 分隔符的位置
        int colonIdx = ruleContent.indexOf(':');
        if (colonIdx < 0) {
            // 无 ':' —— 回退到精确匹配
            return ruleContent.equals(command);
        }
        // 提取 ':' 前的前缀部分
        String prefix = ruleContent.substring(0, colonIdx);
        // ':' 后的部分
        String suffix = ruleContent.substring(colonIdx + 1);
        if ("*".equals(suffix)) {
            // 后缀为 '*'：前缀匹配（命令以 prefix 开头）
            // 如果 prefix 为空，则匹配任何命令
            return prefix.isEmpty() || command.startsWith(prefix);
        }
        // 后缀非 '*'：精确匹配 prefix + suffix 整体
        return command.equals(prefix + suffix);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：PowerShell 大小写不敏感匹配（独立路径，OPD-PERM-37）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * PowerShell 内容规则匹配 · 独立大小写不敏感路径（对齐 CC powershellPermissions.ts:170-333
     * {@code filterRulesByContentsMatchingInput} 的 matchesCommand 分支，非 canonical 部分）。
     *
     * <p>规则类型解析对齐 CC {@code parsePermissionRule}（shellRuleMatching.ts:159-184）：
     * {@code :*} 结尾（前缀非空）→ prefix；含未转义 {@code *} → wildcard；否则 exact。
     * 匹配语义（matchMode='prefix'，即 powershellToolCheckPermission 的规则分派）：
     * <ul>
     *   <li>exact 规则 → {@code strEquals(rule.command, cmd)}（:225-226，大小写不敏感）</li>
     *   <li>prefix 规则 → {@code strEquals(cmd, prefix) || strStartsWith(cmd, prefix + ' ')}
     *       （:227-237；前缀以空格分隔，防止 {@code Get-ProcessFoo} 误命中 {@code Get-Process:*}）</li>
     *   <li>wildcard 规则 → {@code matchWildcardPattern(rule.pattern, cmd, true)}（:239-243）</li>
     * </ul>
     *
     * <p>输入命令按 CC :176 先 {@code trim()}；canonical cmdlet 解析（rm→Remove-Item 等
     * resolveToCanonical 交叉匹配）属 S05 接线范围，本方法只做命令原文匹配。
     *
     * @param ruleContent 规则内容（如 {@code "Get-Process *"}）
     * @param command     工具 input 的 command 字段原文
     * @return            true = 大小写不敏感匹配
     */
    static boolean matchesPowerShellRuleContent(String ruleContent, String command) {
        // CC :176 输入命令先 trim
        String cmd = command.trim();
        // 1. legacy :* 前缀规则（CC permissionRuleExtractPrefix shellRuleMatching.ts:43-48，
        //    正则 /^(.+):\*$/ —— 必须以 :* 结尾且前缀非空）
        String prefix = extractLegacyPrefix(ruleContent);
        if (prefix != null) {
            boolean matched = ciEquals(cmd, prefix) || ciStartsWith(cmd, prefix + " ");
            if (matched) {
                log.info("PowerShell 内容规则命中（前缀）: rule={} command={}", ruleContent, cmd);
            } else if (log.isDebugEnabled()) {
                log.debug("PowerShell 内容规则未命中（前缀）: rule={} command={}", ruleContent, cmd);
            }
            return matched;
        }
        // 2. 通配规则（CC hasWildcards shellRuleMatching.ts:54-78）
        if (hasUnescapedWildcard(ruleContent)) {
            boolean matched = matchesWildcardPatternCI(ruleContent, cmd);
            if (matched) {
                log.info("PowerShell 内容规则命中（通配）: rule={} command={}", ruleContent, cmd);
            } else if (log.isDebugEnabled()) {
                log.debug("PowerShell 内容规则未命中（通配）: rule={} command={}", ruleContent, cmd);
            }
            return matched;
        }
        // 3. exact 规则（CC :225-226 strEquals）
        boolean matched = ciEquals(ruleContent, cmd);
        if (matched) {
            log.info("PowerShell 内容规则命中（精确）: rule={} command={}", ruleContent, cmd);
        } else if (log.isDebugEnabled()) {
            log.debug("PowerShell 内容规则未命中（精确）: rule={} command={}", ruleContent, cmd);
        }
        return matched;
    }

    /**
     * 提取 legacy {@code :*} 前缀 · 对齐 CC {@code permissionRuleExtractPrefix}
     * （shellRuleMatching.ts:43-48，正则 {@code /^(.+):\*$/}）。
     *
     * @param ruleContent 规则内容
     * @return 前缀；非 {@code :*} 语法或前缀为空返回 null
     */
    private static String extractLegacyPrefix(String ruleContent) {
        if (ruleContent.endsWith(":*") && ruleContent.length() > 2) {
            return ruleContent.substring(0, ruleContent.length() - 2);
        }
        return null;
    }

    /**
     * 是否含未转义 {@code *} · 对齐 CC {@code hasWildcards}（shellRuleMatching.ts:54-78）。
     *
     * <p>以 {@code :*} 结尾视为 legacy 前缀语法而非通配；{@code *} 前为偶数个
     * （含 0 个）反斜杠时为未转义。
     *
     * @param pattern 规则模式
     * @return true = 含未转义通配符
     */
    private static boolean hasUnescapedWildcard(String pattern) {
        if (pattern.endsWith(":*")) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                int backslashCount = 0;
                for (int j = i - 1; j >= 0 && pattern.charAt(j) == '\\'; j--) {
                    backslashCount++;
                }
                // 偶数个反斜杠（含 0）→ 该 * 未转义
                if (backslashCount % 2 == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 大小写不敏感通配匹配 · 对齐 CC {@code matchWildcardPattern}
     * （shellRuleMatching.ts:90-154，caseInsensitive=true）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code *} 匹配任意字符序列（含换行，DOTALL）</li>
     *   <li>{@code \*} 匹配字面星号</li>
     *   <li>{@code \\} 匹配字面反斜杠</li>
     *   <li>模式以 {@code ' *'} 结尾且仅含一个未转义 {@code *} → 尾随参数可选
     *       （:136-145，{@code 'git *'} 同时命中裸 {@code git}）</li>
     * </ul>
     *
     * @param pattern 通配模式（如 {@code "Get-Process *"}）
     * @param command 实际命令
     * @return        true = 大小写不敏感通配匹配
     */
    static boolean matchesWildcardPatternCI(String pattern, String command) {
        // 先 trim 模式（CC :95-96）
        String trimmedPattern = pattern.trim();
        // 处理转义序列 \* 与 \\（CC :98-123）
        StringBuilder processed = new StringBuilder();
        int i = 0;
        while (i < trimmedPattern.length()) {
            char ch = trimmedPattern.charAt(i);
            if (ch == '\\' && i + 1 < trimmedPattern.length()) {
                char next = trimmedPattern.charAt(i + 1);
                if (next == '*') {
                    // \* → 字面星号占位符
                    processed.append(ESCAPED_STAR_PLACEHOLDER);
                    i += 2;
                    continue;
                } else if (next == '\\') {
                    // \\ → 字面反斜杠占位符
                    processed.append(ESCAPED_BACKSLASH_PLACEHOLDER);
                    i += 2;
                    continue;
                }
            }
            processed.append(ch);
            i++;
        }
        // 转义 regex 特殊字符（CC :126 字符类 [.+?^${}()|[\]\\'"]，不含 *）
        String escaped = escapeRegexSpecials(processed.toString());
        // 未转义 * → .*（CC :129）
        String withWildcards = escaped.replace("*", ".*");
        // 占位符还原为转义字面量（CC :132-134）
        String regexPattern = withWildcards
            .replace(ESCAPED_STAR_PLACEHOLDER, "\\*")
            .replace(ESCAPED_BACKSLASH_PLACEHOLDER, "\\\\");
        // 尾随 ' *' 且是唯一未转义通配符 → 尾随空格+参数整体可选（CC :136-145）
        int unescapedStarCount = countUnescapedStars(processed.toString());
        if (regexPattern.endsWith(" .*") && unescapedStarCount == 1) {
            regexPattern = regexPattern.substring(0, regexPattern.length() - 3) + "( .*)?";
        }
        // ^...$ + s + i（CC :148-151）：DOTALL 让 . 匹配换行；CASE_INSENSITIVE 大小写不敏感。
        // 用 \z 而非 $ —— Java 的 $ 还匹配末尾换行符之前，JS 的 $（无 m 标志）只匹配字符串末尾。
        Pattern regex = Pattern.compile("^" + regexPattern + "\\z",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        return regex.matcher(command).matches();
    }

    /**
     * 统计未转义 {@code *} 数量（对齐 CC :142 的 {@code processed.match(...)} 计数逻辑）。
     * 转义星号已被占位符替换（占位符不含 {@code *}），故 processed 中残留的 {@code *}
     * 全部是未转义星号。
     *
     * @param processed 转义处理后的模式串
     * @return 未转义星号数量
     */
    private static int countUnescapedStars(String processed) {
        int count = 0;
        for (int k = 0; k < processed.length(); k++) {
            if (processed.charAt(k) == '*') {
                count++;
            }
        }
        return count;
    }

    /**
     * 转义 regex 特殊字符 · 对齐 CC :126 {@code /[.+?^${}()|[\]\\'"]/g}（不含 {@code *}）。
     *
     * @param s 待转义字符串
     * @return 每个特殊字符前加 {@code \} 的字符串
     */
    private static String escapeRegexSpecials(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            switch (ch) {
                case '.', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\', '\'', '"' ->
                    sb.append('\\').append(ch);
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 大小写不敏感相等 · 对齐 CC {@code strEquals}（powershellPermissions.ts:178-180）。
     *
     * @param a 字符串 A
     * @param b 字符串 B
     * @return  true = 忽略大小写相等
     */
    private static boolean ciEquals(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /**
     * 大小写不敏感前缀 · 对齐 CC {@code strStartsWith}（powershellPermissions.ts:181-183）。
     *
     * @param str    被检字符串
     * @param prefix 前缀
     * @return       true = 忽略大小写以 prefix 开头
     */
    private static boolean ciStartsWith(String str, String prefix) {
        return str.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：ruleContent 比较
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 比较两个 ruleContent 是否"等价"（string equals）。
     *
     * <p>用于 {@link com.nexusai.application.agent.permission.PermissionUpdate.RemoveRules}
     * 时的规则匹配。
     *
     * @param a 规则 A 的 ruleContent
     * @param b 规则 B 的 ruleContent
     * @return  true 等价
     */
    public static boolean ruleContentEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * 把 rule 序列化为字符串（用于日志 / 错误消息）。
     *
     * @param rule 规则（可为 null）
     * @return     "toolName(ruleContent)" 或 "toolName" 或 "null"
     */
    public static String ruleToString(PermissionRule rule) {
        if (rule == null) return "null";
        PermissionRuleValue v = rule.ruleValue();
        if (v.ruleContent() == null) {
            return v.toolName();
        }
        return v.toolName() + "(" + v.ruleContent() + ")";
    }

    /**
     * 把 rule 的 behavior 转为可读字符串（枚举字面量名，对齐 CC PermissionBehavior）。
     */
    public static String behaviorToString(PermissionBehavior b) {
        return b == null ? "null" : b.name();
    }
}

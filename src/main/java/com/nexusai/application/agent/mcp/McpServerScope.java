package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.tool.Tool;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP server scope 解析 · 对齐 CC Open-ClaudeCode/src/services/mcp/utils.ts:413-436
 * {@code getMcpServerScopeFromToolName}.
 *
 * <p><b>[R32-b12 D-10 P1 必修]</b> CC 真源返回类型 {@code 'local' | 'project' | 'user' | 'claudeai' | null}.
 * Java 端为字符串（与 CC 等价）.
 *
 * <h2>解析规则</h2>
 * <ol>
 *   <li>非 MCP 工具（工具名不以 {@code mcp__} 开头）→ null</li>
 *   <li>工具名格式：{@code mcp__<serverName>__<toolName>} —— 解析 serverName</li>
 *   <li>serverName 以 {@code claude_ai_} 开头 → "claudeai"（CC: fallback）</li>
 *   <li>其他 → null</li>
 * </ol>
 *
 * <p><b>[IMP-E1 DC-2] scope 通道迁移</b>：CC 真源 {@code getMcpServerScopeFromToolName}
 * （utils.ts:413-436）经 {@code getMcpConfigByName(serverName).scope} 从<b>配置</b>派生 scope，
 * 不承载于 mcpInfo。McpServerInfo 已收敛为 {serverName,toolName} 2 字段，本静态层无配置源
 * （McpConfigLoader 为实例 bean），且生产构造点从未填充 scope（恒 null）→ 本方法不再从
 * mcpClients map 读 scope，返回 null（与既有生产行为一致：OTel mcp_server_scope 不发射）。
 * 配置派生 scope 需注入配置源，登记为受控限制（IMP-E1 progress）。
 *
 * <h2>WHY</h2>
 * <p>OTel {@code tool_result} 事件的 {@code mcp_server_scope} 字段用于分析
 * MCP 工具来源（local CLI / 项目配置 / 用户全局 / Claude.ai 远程服务）.
 *
 * @since R32-b12
 */
public final class McpServerScope {

    /**
     * MCP 工具名前缀. 严格对齐 CC isMcpTool 判定（utils.ts:413-436）.
     */
    public static final String MCP_PREFIX = "mcp__";

    /**
     * Claude.ai 服务器名前缀. CC 真源: {@code mcpInfo.serverName.startsWith('claude_ai_')}
     * (utils.ts:425-427).
     */
    public static final String CLAUDE_AI_PREFIX = "claude_ai_";

    /**
     * MCP 工具名解析正则: mcp__&lt;serverName&gt;__&lt;toolName&gt;.
     * serverName 不含 {@code __}（CC mcpInfoFromString 实现）.
     * 注: serverName 可含 {@code _} (e.g., claude_ai_search), 所以中间段排除 {@code __} 而非 {@code _}.
     */
    private static final Pattern MCP_NAME_PATTERN = Pattern.compile("^mcp__((?:[^_]|_(?!_))+)__(.+)$");

    private McpServerScope() {}

    /**
     * 解析 MCP 工具名的 server scope · 对齐 CC getMcpServerScopeFromToolName（utils.ts:413-436）。
     *
     * <p>[IMP-E1 DC-2] 签名收敛：CC 真源仅 {@code getMcpServerScopeFromToolName(toolName)}
     * （scope 经配置 getMcpConfigByName 派生）；Java 旧 3 参/2 参（toolName, tool, mcpClients）
     * 是 McpServerInfo 承载 scope 时代的产物，scope 字段删除后 mcpClients 参数已无消费 →
     * 收敛为 (toolName, tool)（tool 仅用于 isMcp 判定，可为 null）。
     *
     * @param toolName 工具名
     * @param tool     工具对象（用于 isMcp 判定，可为 null）
     * @return scope 字符串（"claudeai"）或 null
     */
    public static String getMcpServerScopeFromToolName(String toolName, Tool tool) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        // 1. 非 MCP 工具 → null
        if (!isMcpTool(toolName, tool)) {
            return null;
        }
        // 2. 解析 serverName
        Matcher matcher = MCP_NAME_PATTERN.matcher(toolName);
        if (!matcher.matches()) {
            return null;
        }
        String serverName = matcher.group(1);
        // fallback: claude_ai_ 前缀服务器 → "claudeai"（CC utils.ts:428-430）
        if (serverName.startsWith(CLAUDE_AI_PREFIX)) {
            return "claudeai";
        }
        return null;
    }

    /**
     * MCP 工具判定 · 对齐 CC isMcpTool (utils.ts:413-436).
     *
     * <p>判定优先级：
     * <ol>
     *   <li>Tool 对象非 null 且 {@code tool.isMcp() == true} → true</li>
     *   <li>工具名前缀以 {@code mcp__} 开头 → true</li>
     *   <li>否则 → false</li>
     * </ol>
     */
    public static boolean isMcpTool(String toolName, Tool tool) {
        if (tool != null) {
            try {
                if (tool.isMcp()) {
                    return true;
                }
            } catch (Exception ignore) {
                // 降级到工具名前缀判定
            }
        }
        return toolName != null && toolName.startsWith(MCP_PREFIX);
    }
}
package com.nexusai.application.agent.mcp;

import java.util.Objects;

/**
 * MCP 工具/服务器名解析纯工具函数 · 对齐 CC services/mcp/mcpStringUtils.ts。
 *
 * <p>无外部依赖，避免循环 import。是 MCP 客户端和服务端共用的字符串处理层。
 *
 * <h2>CC 对齐</h2>
 * <ul>
 *   <li>{@link #normalizeNameForMCP} ↔ CC {@code normalization.ts:17-23 normalizeNameForMCP}</li>
 *   <li>{@link #buildMcpToolName} ↔ CC {@code mcpStringUtils.ts:50-52 buildMcpToolName}</li>
 *   <li>{@link #mcpInfoFromString} ↔ CC {@code mcpStringUtils.ts:19-32 mcpInfoFromString}</li>
 * </ul>
 */
public final class McpStringUtils {

    /**
     * Claude.ai 服务器名前缀 · CC normalization.ts:5 CLAUDEAI_SERVER_PREFIX
     */
    public static final String CLAUDEAI_SERVER_PREFIX = "claude.ai ";

    /**
     * MCP 工具名前缀 (用于解析时识别 MCP 工具).
     */
    public static final String MCP_PREFIX = "mcp__";

    private McpStringUtils() {
        // 工具类
    }

    /**
     * 规范化 MCP 服务器/工具名 · 对齐 CC normalizeNameForMCP。
     *
     * <p>规则:
     * <ol>
     *   <li>替换所有非 {@code [a-zA-Z0-9_-]} 字符为 {@code _}</li>
     *   <li>如果原始名以 {@code "claude.ai "} 开头, 额外合并连续 {@code _} + 去除首尾 {@code _}
     *       (避免与 {@code __} 分隔符冲突)</li>
     * </ol>
     *
     * @param name 原始名
     * @return 规范化名 (1-64 字符, 仅含 [a-zA-Z0-9_-])
     */
    public static String normalizeNameForMCP(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String normalized = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (name.startsWith(CLAUDEAI_SERVER_PREFIX)) {
            normalized = normalized.replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        }
        return normalized;
    }

    /**
     * 生成 MCP 工具/命令名前缀 · 对齐 CC mcpStringUtils.ts:42-44 getMcpPrefix。
     *
     * @param serverName 服务器名 (未规范化)
     * @return 形如 {@code "mcp__<normalized_server>__"}
     */
    public static String getMcpPrefix(String serverName) {
        return MCP_PREFIX + normalizeNameForMCP(serverName) + "__";
    }

    /**
     * 构造完整 MCP 工具名 · 对齐 CC mcpStringUtils.ts:50-52 buildMcpToolName。
     *
     * @param serverName 服务器名 (未规范化)
     * @param toolName   工具名 (未规范化)
     * @return 形如 {@code "mcp__<server>__<tool>"} 的完全限定名
     */
    public static String buildMcpToolName(String serverName, String toolName) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(toolName, "toolName");
        return getMcpPrefix(serverName) + normalizeNameForMCP(toolName);
    }

    /**
     * 从工具名解析 MCP 服务器/工具信息 · 对齐 CC mcpStringUtils.ts:19-32 mcpInfoFromString。
     *
     * <p>已知限制 (CC 注释): 如果服务器名包含 {@code "__"}, 解析会不准确 (实际罕见).
     *
     * <p>NG-4 注意: 本方法<b>不适用</b>于 MCP prompt 命令名的 promptName 反解——复合名
     * {@code mcp__<server>__<prompt>} 中 server 含 {@code __} 时会截断 server 段导致反解失效。
     * CC 的 getPromptForCommand 闭包直接捕获 {@code prompt.name}（client.ts:2078），Java 端等价
     * 实现见 {@code McpToolPool.wirePromptFunctions}（按已知前缀 {@code getMcpPrefix} 剥离）。
     *
     * @param toolString 待解析字符串 (期望格式: {@code "mcp__server__tool"})
     * @return 解析结果, 非 MCP 工具名返回 null
     */
    public static McpInfo mcpInfoFromString(String toolString) {
        if (toolString == null || !toolString.startsWith(MCP_PREFIX)) {
            return null;
        }
        String[] parts = toolString.split("__");
        if (parts.length < 2 || !"mcp".equals(parts[0]) || parts[1].isEmpty()) {
            return null;
        }
        String serverName = parts[1];
        String toolName = null;
        if (parts.length > 2) {
            // 保留 tool name 中可能的双下划线 (parts[2:])
            StringBuilder sb = new StringBuilder(parts[2]);
            for (int i = 3; i < parts.length; i++) {
                sb.append("__").append(parts[i]);
            }
            toolName = sb.length() > 0 ? sb.toString() : null;
        }
        return new McpInfo(serverName, toolName);
    }

    /**
     * MCP 工具解析结果 · 对齐 CC mcpInfoFromString 返回类型。
     */
    public record McpInfo(String serverName, String toolName) {
        public McpInfo {
            Objects.requireNonNull(serverName, "serverName");
        }
    }
}

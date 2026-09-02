package com.nexusai.infra.util;

/**
 * SlashCommandParser · 对齐 CC utils/slashCommandParsing.ts.
 *
 * <p>L1 语义: 解析 {@code /commandName args} 字符串 → (commandName, args, isMcp)。
 * 支持 MCP 命令格式 {@code /mcp:tool (MCP) arg1 arg2}。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: parseSlashCommand(input)→Parsed|null;不 '/' 开头 → null</li>
 *   <li><b>A2 Golden Trace</b>: '/search foo bar' → ('search', 'foo bar', false);
 *       '/mcp:tool (MCP) arg1' → ('mcp:tool (MCP)', 'arg1', true);
 *       '/only' → ('only', '', false);</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output</li>
 *   <li><b>A4 边界</b>: 空字符串 → null;仅 '/' → null</li>
 *   <li><b>A5 业务场景</b>: slash command palette 输入解析 → 路由 dispatch</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS string.split + 数组索引 → Java String.split + array access;
 * TS array.slice + join → Java String.join;
 * TS trim + startsWith → Java trim + startsWith。
 */
public final class SlashCommandParser {

    public record ParsedSlashCommand(String commandName, String args, boolean isMcp) {}

    private SlashCommandParser() {}

    /**
     * Parse a slash command input into its component parts.
     *
     * @param input raw user input (should start with {@code /})
     * @return ParsedSlashCommand or null
     */
    public static ParsedSlashCommand parseSlashCommand(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null;

        String withoutSlash = trimmed.substring(1);
        String[] words = withoutSlash.split(" ", -1);
        if (words.length == 0 || words[0].isEmpty()) return null;

        String commandName = words[0];
        boolean isMcp = false;
        int argsStartIdx = 1;

        // MCP commands: second word is "(MCP)"
        if (words.length > 1 && "(MCP)".equals(words[1])) {
            commandName = commandName + " (MCP)";
            isMcp = true;
            argsStartIdx = 2;
        }

        // Args = everything after command, joined by space
        String args = words.length > argsStartIdx
            ? String.join(" ", java.util.Arrays.copyOfRange(words, argsStartIdx, words.length))
            : "";

        return new ParsedSlashCommand(commandName, args, isMcp);
    }
}
